package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextOutline
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the toolkit can draw, and the whole of what a backend must implement.
 *
 * Deliberately short. A long drawing interface is a long list of things every future backend has
 * to reimplement, and most of what an interface actually draws is a rectangle with rounded corners
 * and some text on it. Anything richer goes through [raw], which hands back the backend's own
 * drawing object for a game or a widget to use directly.
 *
 * Coordinates are virtual pixels with y growing downwards. Backends flip once, internally.
 *
 * Clip, alpha and blend mode are stacks: a nested clip is the intersection of the two, a nested
 * alpha multiplies, and a nested blend mode replaces. [CanvasState] implements all three correctly
 * and backends are expected to hold one.
 */
interface UiCanvas {

    /**
     * Opens a frame: everything after this is drawn in [viewport]'s design coordinates, until the
     * matching [end].
     *
     * Here, rather than on each backend, so that a whole frame of interface can be one call — see
     * [dev.wildware.composegl.ui.host.UiRenderer]. Every backend that draws to a screen has this pair anyway:
     * something has to set the scale, the letterbox and the projection once rather than per
     * rectangle.
     *
     * Both do nothing by default, for a canvas with no frame boundary to speak of — a recording
     * one in a test, or one drawing into somebody else's already-open pass.
     */
    fun begin(viewport: Viewport) = Unit

    /** Closes the frame, hands whatever is left to the GPU, and puts back any state it borrowed. */
    fun end() = Unit

    /**
     * Builds whatever this canvas would otherwise build in the first frame it draws.
     *
     * A mesh and a compiled shader cost a few milliseconds, and a backend that makes them on
     * demand spends those in the first frame the player sees — which is exactly the frame a
     * stutter is noticed in. Call this on a loading screen, on the thread that holds the context,
     * and the first real frame has nothing left to pay for.
     *
     * Here rather than only on the backends, so that a game holding a [UiCanvas] — which is what
     * this toolkit asks a game to hold — can call it at all. Nothing to build is the default, so a
     * canvas with no GPU behind it says nothing about this and a game that never calls it loses
     * nothing. Calling it twice does nothing the second time.
     */
    fun warmUp() = Unit

    /** A filled rectangle. [corner] is the corner radius; zero is a plain rectangle. */
    fun rect(rect: Rect, colour: Colour, corner: Float = 0f)

    /**
     * A filled rectangle whose colour changes across it — see [Brush].
     *
     * The gradient is measured against [rect] itself, so it runs edge to edge whatever size the box
     * is, and [corner] cuts it exactly as it cuts a flat fill.
     *
     * The default body draws [Brush.first] flat. That is the nearest honest thing a canvas with no
     * gradients can do — the right box in the colour it starts from — and it means a backend
     * compiled before this existed keeps drawing every panel rather than dropping the ones a skin
     * has since given a gradient. Ask [drawsGradients] first if a flat fill would be worse.
     */
    fun rect(rect: Rect, brush: Brush, corner: Float = 0f) = rect(rect, brush.first, corner)

    /**
     * Whether the [rect] that takes a [Brush] really draws a gradient.
     *
     * False means it draws the brush's first colour flat instead. The same bargain as
     * [drawsLayers] and [supports]: a question with an honest default.
     */
    val drawsGradients: Boolean get() = false

    /** An outline drawn inside [rect], [width] thick. */
    fun border(rect: Rect, colour: Colour, width: Float, corner: Float = 0f)

    /**
     * A soft shadow under [rect], reaching [spread] beyond it.
     *
     * Its own call rather than part of [rect] because a shadow is drawn behind a whole group as
     * often as it is drawn behind one box.
     */
    fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float = 0f)

    /**
     * A filled rectangle with its own radius on each corner. See [Corners].
     *
     * An overload rather than a change to the call above, so every backend written against one
     * radius keeps compiling and keeps drawing. Its default body draws the box with the
     * [smallest][Corners.smallest] of the four on every corner: the right place and the right size,
     * with the rounding left out rather than put where it was not asked for. A tab that meets its
     * panel square along the bottom still meets it square. Ask [roundsCornersSeparately] first if
     * that matters.
     *
     * Corners that are all the same come here too and are drawn as the single-radius call would
     * draw them — but the toolkit's own drawing sends those through the call above instead, so a
     * canvas that wraps another and overrides only that one keeps seeing every box it used to.
     */
    fun rect(rect: Rect, colour: Colour, corners: Corners) = rect(rect, colour, corners.smallest)

    /** An outline drawn inside [rect], [width] thick, with its own radius on each corner. */
    fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) =
        border(rect, colour, width, corners.smallest)

    /** A soft shadow under [rect], reaching [spread] beyond it, with its own radius on each corner. */
    fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) =
        shadow(rect, colour, spread, corners.smallest)

    /**
     * A gradient box with its own radius on each corner: a tab shaded top to bottom.
     *
     * The default body is the [Brush] call above at the smallest of the four, so a backend that
     * draws gradients but has never heard of [Corners] keeps its gradient and squares the corners.
     */
    fun rect(rect: Rect, brush: Brush, corners: Corners) = rect(rect, brush, corners.smallest)

    /**
     * Whether the calls that take [Corners] really round each corner by its own radius.
     *
     * False means they draw every corner at the smallest of the four, which is a squarer box in the
     * right place — nothing vanishes and nothing throws. Same shape as [rotatesImages] and
     * [supports]: a question with an honest default.
     */
    val roundsCornersSeparately: Boolean get() = false

    /**
     * Text that has already been measured, with [x] and [y] as its top-left.
     *
     * Floats rather than an [Offset] because of what draws the most text per frame: a layer of
     * damage numbers places a couple of hundred runs every frame and would otherwise make a
     * couple of hundred throwaway objects doing it.
     */
    fun text(layout: TextLayout, x: Float, y: Float, colour: Colour)

    /** The same, for the ordinary case where the caller already has the point. */
    fun text(layout: TextLayout, at: Offset, colour: Colour) = text(layout, at.x, at.y, colour)

    /**
     * The same, with a ring of [outline] round the letters. Null draws it plainly.
     *
     * Here rather than in a widget because a ring is nine copies of the run and a widget could only
     * make them by stacking nine *nodes* — nine things to lay out, place and draw, for one label.
     * Done here it is nine batches of quads out of the atlas that was already bound: no re-measure,
     * no extra node, no render target, and the same draw call while the batch has room.
     *
     * The default body is built out of the plain [text] above and nothing else, so every backend
     * already draws this correctly, including ones outside this repository that were compiled
     * against an earlier release and have never heard of it. This module compiles with
     * `-jvm-default=no-compatibility`, so on the JVM it really is a Java default method on the
     * interface: there is no `DefaultImpls` class and no compatibility bridge, and an implementor
     * that does not mention it inherits this body directly. Keep that flag — building without it
     * would move where this body lives and break implementors compiled against the flagged build.
     *
     * All eight outline copies are drawn before the fill, so no letter's ring can land on the face
     * of the letter beside it. That is why this takes the whole run: a caller that draws its own
     * glyphs one at a time — [dev.wildware.composegl.ui.widget.Typewriter] with an effect on it is
     * the one in this repository — has to make the same two passes itself to keep the guarantee.
     *
     * **The ring is drawn at [outline]'s colour scaled by [colour]'s alpha**, not at its own colour
     * flat. Fading a run means handing this a faded [colour] — that is what a damage number does as
     * it rises — and a ring stamped at full strength round letters that have faded to nothing is a
     * black silhouette of a number that is supposed to have gone. A backend that overrides this
     * owes callers the same rule.
     *
     * A backend may override this. That is the point of putting it on the interface rather than
     * shipping it as a free function: the day a distance-field backend exists it replaces this one
     * method with a real stroke and every caller above is unchanged. See [TextOutline] for what the
     * stamped version can and cannot do — it is not a stroke, and the doc there says so plainly.
     */
    fun text(layout: TextLayout, x: Float, y: Float, colour: Colour, outline: TextOutline?) {
        if (outline != null && outline.isVisible) {
            val ring = outline.colour.scaleAlpha(colour.alphaFraction)
            outline.forEachStamp { dx, dy -> textRing(layout, x + dx, y + dy, ring) }
        }
        text(layout, x, y, colour)
    }

    /**
     * One copy of [layout] stamped as part of an outline's ring, in [colour].
     *
     * The default is the plain [text], which is right for letters: a letter is only coverage, so a
     * copy in the ring colour is its silhouette. A backend that draws some glyphs as pictures in
     * their own colours — an emoji — overrides this to leave those out, or the ring would be eight
     * more emoji smeared round the real one. Anything that stamps a ring itself, a character at a
     * time, calls this rather than [text] for the copies so that it gets the same treatment.
     */
    fun textRing(layout: TextLayout, x: Float, y: Float, colour: Colour) = text(layout, x, y, colour)

    /** The same, for the ordinary case where the caller already has the point. */
    fun text(layout: TextLayout, at: Offset, colour: Colour, outline: TextOutline?) =
        text(layout, at.x, at.y, colour, outline)

    /**
     * A picture, stretched to fill [destination]. [tint] multiplies; white leaves it alone.
     *
     * [source] picks a part of the texture, in texture pixels from its top-left, y downwards.
     * Null means all of it. It is here so that slicing a picture up — a nine-patch, a sprite
     * sheet, one frame of an animation — is arithmetic the toolkit does once, rather than a
     * feature every backend has to implement and can implement differently.
     *
     * [NineRegions] is nine pieces rather than one picture, and every canvas here refuses one by
     * name — it has no size to stretch to fit. Draw it through [NinePatch] instead.
     */
    fun image(
        texture: TextureHandle,
        destination: Rect,
        tint: Colour = Colour.White,
        source: Rect? = null,
    )

    /**
     * The same picture, turned.
     *
     * [degrees] turns it **clockwise on screen**, because y grows downwards here and a positive
     * angle should turn the same way the axes do. Zero is exactly the upright call above, in what
     * it draws and in what a [RecordingCanvas] writes down, so a widget handing over a variable
     * that happens to be zero behaves today as it did before this existed.
     *
     * [pivotX] and [pivotY] are where the turn happens, as fractions of [destination]: 0.5, 0.5 is
     * the middle, and 0, 0.5 is the middle of the left edge — which is what a ray of a sunburst
     * wants, since every ray shares one hub. Floats rather than an [Offset] for the reason [text]
     * already gives: a sunburst is a dozen of these per card per frame, and an object each would
     * be litter.
     *
     * **[destination] is the box before turning.** The pixels that come out can fall well outside
     * it — with the pivot on an edge they leave it almost entirely — so it is not a bound on what
     * gets painted. Anything that needs a bound, a layer being sized round this or a clip meant to
     * contain it, has to work one out itself.
     *
     * No antialiasing: a picture goes through the shader with the shape maths switched off, so a
     * turned hard-edged sprite has stair-stepped edges. Art with a soft edge, or art drawn larger
     * than it is shown, is the answer. That is true of the upright call too; it is only visible
     * here.
     *
     * The default body draws it upright, which is the nearest honest thing a backend that cannot
     * turn a picture can do: right place, right size, not turned. Ask [rotatesImages] first if
     * that matters.
     */
    @Suppress("LongParameterList")
    fun image(
        texture: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        tint: Colour = Colour.White,
        source: Rect? = null,
    ) = image(texture, destination, tint, source)

    /**
     * Whether the [image] overload that takes an angle really turns the picture.
     *
     * False means it draws upright instead — nothing vanishes and nothing throws. A caller that
     * would rather draw different art than show an unturned sunburst asks this before it commits.
     */
    val rotatesImages: Boolean get() = false

    /**
     * A triangle fan: a filled shape a rectangle cannot be.
     *
     * [points] is x, y, x, y… in design coordinates, and the first point is the hub every triangle
     * shares. So a cooldown's wedge, a radial menu's slice and a compass's needle are all one call
     * with a handful of points in it, rather than three primitives every backend has to write.
     *
     * It is the only shape here that is not a box, and it is deliberately low level: no curve, no
     * radius, no stroke. Whatever wants a circle walks one itself, at whatever smoothness it is
     * being drawn at, which is the only place that knows.
     *
     * Fewer than three points draws nothing.
     */
    fun fan(points: FloatArray, colour: Colour)

    /**
     * A filled circle, or a regular polygon when [segments] says so.
     *
     * Walked onto [fan] here rather than asked of the backend, because the only thing that knows
     * how smooth a circle has to be is the place that knows how big it is being drawn - and that
     * is this call, not the renderer. A backend that has a real circle primitive is free to
     * override this; every other backend gets one for nothing.
     *
     * [segments] of zero picks a count from the radius: roughly one segment per two units of
     * circumference, held between 8 and 180, which is smooth at a badge's size and not wasteful at
     * a dot's.
     */
    fun circle(centre: Offset, radius: Float, colour: Colour, segments: Int = 0) {
        if (radius <= 0f) return
        arc(centre, radius, startDegrees = 0f, sweepDegrees = 360f, colour = colour, segments = segments)
    }

    /**
     * A filled wedge: the slice of a circle between two angles, with its point at [centre].
     *
     * Angles are degrees clockwise from three o'clock, which is clockwise *on screen* because
     * these are design coordinates and y grows downwards. A countdown that empties clockwise is
     * therefore a negative [sweepDegrees], and a full turn in either direction is a circle.
     *
     * This is the shape a radial timer, a cooldown shade and a pie slice are all made of.
     */
    fun arc(
        centre: Offset,
        radius: Float,
        startDegrees: Float,
        sweepDegrees: Float,
        colour: Colour,
        segments: Int = 0,
    ) {
        if (radius <= 0f || sweepDegrees == 0f) return
        val span = sweepDegrees.coerceIn(-360f, 360f)
        val steps = arcSteps(radius, span, segments)
        // Hub first: fan() shares the first point across every triangle, which is exactly a wedge.
        val points = FloatArray((steps + 2) * 2)
        points[0] = centre.x
        points[1] = centre.y
        val startRadians = startDegrees * PI_OVER_180
        val stepRadians = span * PI_OVER_180 / steps
        for (i in 0..steps) {
            val angle = startRadians + stepRadians * i
            points[(i + 1) * 2] = centre.x + cos(angle) * radius
            points[(i + 1) * 2 + 1] = centre.y + sin(angle) * radius
        }
        fan(points, colour)
    }

    /**
     * A straight line of a given thickness, as a filled quad.
     *
     * A line rather than a rectangle because the interesting ones are not axis-aligned: a
     * connector between two cells, a needle, the stroke under a word that follows the word.
     * Nothing is drawn for a line of no length or no width.
     */
    fun line(from: Offset, to: Offset, width: Float, colour: Colour) {
        if (width <= 0f) return
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        // The perpendicular, half a width long, is the offset from the centreline to each edge.
        val halfX = -dy / length * (width / 2f)
        val halfY = dx / length * (width / 2f)
        fan(
            floatArrayOf(
                from.x + halfX, from.y + halfY,
                to.x + halfX, to.y + halfY,
                to.x - halfX, to.y - halfY,
                from.x - halfX, from.y - halfY,
            ),
            colour,
        )
    }

    /**
     * A filled convex polygon through [points], given as x, y, x, y… in design coordinates.
     *
     * Convex is the contract, and it is [fan]'s contract rather than an extra restriction: a fan
     * shares its first point across every triangle, so a shape that turns back on itself comes out
     * with its dents filled in. Split a concave outline into convex pieces and draw each.
     *
     * Fewer than three points draws nothing, as [fan] already says.
     */
    fun polygon(points: FloatArray, colour: Colour) = fan(points, colour)

    /** Nothing outside [rect] is drawn until the matching [popClip]. Nests by intersection. */
    fun pushClip(rect: Rect)

    fun popClip()

    /** Everything drawn until the matching [popAlpha] is faded. Nests by multiplication. */
    fun pushAlpha(alpha: Float)

    fun popAlpha()

    /**
     * Everything drawn until the matching [popBlend] is combined with the screen [mode]'s way.
     *
     * A pair rather than an argument on every call, because changing how the GPU blends is a batch
     * boundary: the quads already queued were queued to blend the old way, so they have to be sent
     * before the new mode is set. Put six additive embers between one push and one pop and that
     * costs two boundaries; pass a mode to six separate calls and it costs twelve. The cost is
     * where a reader can see it, and it is per group rather than per call.
     *
     * Nests by replacement, not by combination — see [BlendMode]. [popBlend] goes back to the mode
     * underneath.
     *
     * Inside [layer] the mode starts again at [BlendMode.SourceOver], the same reset the clip and
     * the opacity already get, and whatever was in force out here is back afterwards. It has to
     * be: a layer's picture starts as transparent black, so adding into it and then compositing
     * the result the ordinary way is not the same as adding onto the screen. To make a whole group
     * glow, push the mode round the [drawLayer] rather than round the [layer] — and that holds
     * whether or not the [drawLayer] has a [dev.wildware.composegl.ui.effect.ShaderEffect] on it,
     * so a blurred group glows the same as an unblurred one.
     *
     * Both do nothing by default, and then everything draws [BlendMode.SourceOver] — a glow reads
     * as a coloured smudge, which is what it looked like before this existed. Ask [supports]
     * first if that matters.
     */
    fun pushBlend(mode: BlendMode) = Unit

    fun popBlend() = Unit

    /**
     * Whether [pushBlend] really does anything with [mode].
     *
     * A query rather than an exception, because a backend without a blend mode is not a mistake
     * the caller made — it is the same bargain [layer] makes. A caller that would rather skip its
     * halo than paint a smudge asks this before it draws one.
     */
    fun supports(mode: BlendMode): Boolean = mode == BlendMode.SourceOver

    /**
     * Every colour drawn until the matching [popTint] is multiplied by [tint], channel by channel —
     * a rectangle's fill, a border, text, and a picture's own tint. White changes nothing.
     *
     * [tint]'s alpha is how much of it applies, not an opacity: `Colour.Red.scaleAlpha(0.5f)` is
     * halfway to red, and at zero nothing changes. Nests by multiplication. See [Colour.asTint].
     *
     * No shader and no picture: it is the same multiply a tinted image already gets on the GPU, so
     * it costs nothing but the arithmetic, and a hotbar flashing red is not a hotbar in a layer.
     *
     * **Unlike opacity it carries into [layer].** A multiply comes out the same done to each part
     * or to the finished picture, so it is done to the parts, and [drawLayer] does not tint again.
     * That is what lets a tinted subtree with a blur in it hand the blur a tinted picture — and it
     * means a caller tinting a picture it made earlier pushes the tint round the [layer], not round
     * the [drawLayer]. What a shader adds of its own, an outline's ring, is not tinted.
     *
     * [raw] is not tinted either: whatever the game draws with its own object is its own business.
     *
     * Both do nothing by default, and then everything draws its own colour. Ask [tints] first if a
     * screen would rather show something else.
     */
    fun pushTint(tint: Colour) = Unit

    fun popTint() = Unit

    /**
     * Whether [pushTint] really does anything. Same shape as [supports] and [drawsLayers]: a
     * question with an honest default.
     */
    val tints: Boolean get() = false

    /**
     * Everything drawn until the matching [popTransform] is grown by [scale] and moved by
     * [translateX] and [translateY]: a point at (x, y) lands at (x × scale + translateX,
     * y × scale + translateY) in the coordinates in force before the push.
     *
     * The camera a pan-and-zoom canvas is built on. It is not a picture: every rectangle, glyph and
     * picture is placed where the transform puts it and drawn at that size, so a zoomed-in skill
     * tree has sharp edges and a map bigger than the screen has no ceiling, which is the difference
     * from `Modifier.scale`. Thicknesses scale too — a border, a corner, a shadow's spread — because
     * a border is part of the thing being zoomed.
     *
     * Nests by composition, inner first: pushed twice, a point goes through the inner one and then
     * the outer one. A clip pushed inside is the transformed rectangle, so `pushClip` keeps meaning
     * "this box, where I am drawing". [layer] takes a picture of the transformed area at the
     * screen's own resolution and carries the transform in, so an effect on a zoomed node is as
     * sharp as the node.
     *
     * No flush: the multiply happens to each position as it is queued, so a thousand nodes each
     * pushing their own transform are still one draw call.
     *
     * Both do nothing by default, and then everything is drawn where it was asked, unmoved and at
     * its own size. Ask [transforms] first: a canvas answering no cannot show a zoom at all.
     */
    fun pushTransform(scale: Float, translateX: Float, translateY: Float) = Unit

    /**
     * The same, saying the [textScale] glyphs should be made for.
     *
     * Glyphs are pictures made at one pixel size, so text zoomed past its size goes soft. A canvas
     * that can make them again does so at [TextZoom.snap] of the total text scale, one of a handful
     * of steps, and stretches the nearest copy between steps. Passing a text scale that lags behind
     * [scale] while a gesture is under way — the one a pan-and-zoom canvas passes — keeps the glyphs
     * already made on screen during a pinch and makes new ones once the hand stops, rather than a
     * new set every time the zoom crosses a step.
     *
     * The default body ignores it and pushes [scale], which is right for a canvas that never makes
     * glyphs again.
     */
    fun pushTransform(scale: Float, translateX: Float, translateY: Float, textScale: Float) =
        pushTransform(scale, translateX, translateY)

    fun popTransform() = Unit

    /**
     * Whether [pushTransform] really moves and grows what is drawn. Same shape as [tints] and
     * [supports]: a question with an honest default.
     */
    val transforms: Boolean get() = false

    /**
     * Draws [block] into an offscreen picture the size of [bounds] instead of onto the screen, and
     * hands the picture back.
     *
     * This is what an effect is built on. A blur has nothing to blur until the thing being blurred
     * exists as pixels somewhere other than the screen; so does an outline, a dissolve, or a group
     * that fades as one object rather than as a pile of separately fading parts.
     *
     * Inside the block the clip is [bounds] and the opacity is full. The opacity in force out here
     * is applied when the picture is drawn back, which is the difference between a panel fading and
     * every overlapping thing on the panel fading through each other.
     *
     * Returns null when this canvas has no offscreen drawing, or when [bounds] is too big for one —
     * **and then nothing has been drawn at all**, so the caller draws [block] itself and goes
     * without the effect. That is the whole error path: an effect degrades to no effect.
     *
     * The picture belongs to the canvas and is reused. It is good until the end of the frame and
     * must not be kept past it.
     */
    fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null

    /**
     * Whether [layer] draws anything at all on this canvas.
     *
     * Worth asking before a screen commits to something built on offscreen pictures — a panel that
     * arrives by scaling, a group that fades as one object — so it can choose a different animation
     * rather than find out by having nothing happen. Same shape as [rotatesImages] and
     * [supports]: a question with an honest default.
     *
     * It answers "can this canvas make pictures", not "can it make one that big". A canvas may
     * still refuse an individual picture, and both backends in this repository refuse one over 4096
     * screen pixels a side — their own limit, not a rule of this interface, and another canvas is
     * free to pick a different one. Either way [layer] hands back null and the caller draws
     * plainly, so a subtree that must scale should be viewport-sized rather than laid out bigger
     * than the screen.
     */
    val drawsLayers: Boolean get() = false

    /**
     * Draws a picture that [layer] made, filling [destination], optionally through a shader.
     *
     * Its own call rather than [image] because the colours in a layer are already multiplied by
     * their own opacity, and a backend has to blend it differently — drawing one through [image]
     * puts a dark halo round everything soft. Nothing else should be passed here.
     *
     * With an [effect], the shader decides what each pixel comes out as; see [ShaderSource] for
     * what it is handed. A backend that cannot compile shaders draws the picture plainly and says
     * nothing, which is the same bargain [layer] makes: an effect degrades to no effect.
     *
     * The canvas's current opacity applies, as it does to every other call. A shader gets it as
     * `u_alpha` and is expected to multiply by it, since nothing outside the shader can.
     */
    fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect? = null) =
        image(layer, destination)

    /**
     * The same picture, turned clockwise by [degrees] about a pivot given as a fraction of
     * [destination] — (0.5, 0.5) is its middle, (0, 0) its top-left corner.
     *
     * What `Modifier.rotate` is composited with. Its own overload rather than a parameter on the
     * call above so that a backend written before rotation existed keeps working: the default body
     * draws the picture upright, in the right place and at the right size, which is the nearest
     * honest thing a canvas that cannot turn one can do.
     *
     * **[destination] is the box before turning.** The pixels can fall well outside it, so it is
     * not a bound on what gets painted — the same warning [image] carries, and for the same
     * reason. Ask [turnsLayers] first if drawing it unturned would be worse than not drawing it.
     *
     * Blending and opacity are [drawLayer]'s, not [image]'s: the colours in a layer are already
     * multiplied by their own opacity and a backend has to composite them differently.
     *
     * No shader here, unlike the call above. A turned effect is the shader's answer turned, which
     * is a second picture, so whoever wants both takes the second picture deliberately rather than
     * having this call take one quietly.
     */
    fun drawLayer(
        layer: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
    ) = drawLayer(layer, destination)

    /**
     * Whether the [drawLayer] overload that takes an angle really turns the picture.
     *
     * False means it composites it upright instead — nothing vanishes and nothing throws, so a
     * turned subtree is drawn straight rather than not at all. Same shape as [rotatesImages],
     * [drawsLayers] and [supports]: a question with an honest default.
     *
     * Separate from [rotatesImages] because they are different calls with different blending, and
     * a backend can perfectly well manage one and not the other.
     */
    val turnsLayers: Boolean get() = false

    /**
     * The same picture, cut to [outline] as it is put down: nothing outside the outline lands.
     *
     * What `Modifier.clipShape` is composited with — a round portrait, a diamond minimap, a hexagon
     * tile. A scissor can only ever be a rectangle, so a shape is a picture of the subtree drawn
     * back through the shape instead.
     *
     * [outline] is x, y, x, y… in design coordinates, convex, in order round the edge, and at
     * least three points. [destination] is the whole picture's rectangle; the outline says which
     * part of it survives and normally lies inside it. A backend is expected to soften the edge
     * over about one screen pixel, the way a rounded corner already is, so a curve does not come
     * out as a staircase — [featherOutline] does that for any batch that draws quads.
     *
     * Blending and opacity are [drawLayer]'s: the picture is premultiplied.
     *
     * The default body puts the picture down whole, uncut, which is the nearest honest thing a
     * canvas that cannot cut can do: everything is there, square. Ask [cutsLayers] first.
     */
    fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) = drawLayer(layer, destination)

    /**
     * Whether [cutLayer] really cuts.
     *
     * False means it puts the picture down whole — the same bargain [turnsLayers] makes. The draw
     * pass asks this before it takes a picture for a shaped clip at all, and clips to the node's
     * rectangle instead when the answer is no.
     */
    val cutsLayers: Boolean get() = false

    /**
     * The same picture, filling [destination], with its left and right swapped when [mirrorX] is
     * set and its top and bottom when [mirrorY] is.
     *
     * What `Modifier.mirror` is composited with. An overload with an honest default for the same
     * reason the turned one is: a backend written before mirroring existed draws the picture the
     * right way round, in the right place, rather than failing to compile or drawing nothing.
     *
     * [destination] is unchanged by the mirror — the pixels land exactly where the plain call puts
     * them, reading the picture from the other side — so it is still a bound on what gets painted.
     *
     * Blending and opacity are the plain [drawLayer]'s. No shader: a mirrored effect is the shader
     * working on a mirrored picture, which the draw pass takes as a picture of its own.
     */
    fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) =
        drawLayer(layer, destination)

    /**
     * Whether the [drawLayer] overload that takes a mirror really mirrors the picture.
     *
     * False means it composites it the right way round instead, and the draw pass does not bother
     * taking a picture for a mirror alone — hit testing then stays unmirrored too, so a canvas that
     * cannot flip is at least clicked where it draws. Same shape as [turnsLayers].
     */
    val mirrorsLayers: Boolean get() = false

    /**
     * The same picture, put down on four corners that need not be a rectangle.
     *
     * [corners] is eight numbers, in the toolkit's y-down coordinates: where the picture's top-left
     * lands, then its top-right, its bottom-right and its bottom-left, each as an x then a y. What
     * `Modifier.skew` is composited with, and a slant and a turn together ride the same call, so
     * any transform that keeps straight lines straight and parallel ones parallel is one quad.
     *
     * The picture is stretched affinely across the quad. For a parallelogram — which every slant,
     * turn and scale makes — that is exact. Four corners that are not a parallelogram are drawn as
     * two triangles, each exact on its own, with a visible crease along the diagonal between
     * them; a perspective tilt wants a backend that can divide by depth, and this is not that.
     *
     * [destination] is the box before any of it: the rectangle a canvas that cannot do this
     * draws the picture into instead, upright and the right size, which is what the default body
     * does. Ask [drawsLayersOnto] first if that would be worse than not drawing it.
     *
     * Blending and opacity are [drawLayer]'s, for the reason given there. No shader, for the
     * reason the turned overload gives.
     */
    fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) =
        drawLayer(layer, destination)

    /**
     * Whether [drawLayerOnto] really puts the picture on its four corners.
     *
     * False means it composites it upright into the destination instead. Same shape as
     * [turnsLayers], and separate from it because a backend that can turn a quad about a pivot has
     * not necessarily been taught to take four corners from somebody else.
     */
    val drawsLayersOnto: Boolean get() = false

    /**
     * The same picture, put down through a transform in depth: turned, tilted and seen by a camera.
     *
     * [transform] maps the toolkit's y-down coordinates to themselves, with a depth — see
     * [dev.wildware.composegl.ui.geometry.Matrix4]. Each corner of [destination] goes through it,
     * and the picture is stretched across the quad that makes *perspective-correctly*: divided by
     * depth for every pixel, not only at the corners, so a tilted card's texture does not bend
     * along the diagonal. That is the difference from [drawLayerOnto], and why a backend needs a
     * shader that can take a w to do it. A corner at or behind the camera is clipped away rather
     * than drawn inside out.
     *
     * What `Modifier.rotate3d` is composited with, and the skew and turn on the same node ride the
     * same matrix.
     *
     * [destination] is also the rectangle a canvas that cannot do this draws the picture into
     * instead, flat, which is what the default body does. Ask [tiltsLayers] first if that would be
     * worse than not drawing it.
     *
     * Blending and opacity are [drawLayer]'s. No shader, for the reason the turned overload gives.
     */
    fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) =
        drawLayer(layer, destination)

    /**
     * Whether the transformed [drawLayer] really tilts the picture.
     *
     * False means it composites it flat into the destination instead. Same shape as
     * [drawsLayersOnto], and separate from it because four corners are not a perspective: a backend
     * that can place a quad has not necessarily been taught to divide by depth.
     */
    val tiltsLayers: Boolean get() = false

    /**
     * The backend's own drawing object, for whatever this interface does not cover — a shader, a
     * particle system, a mesh, a game's existing render code.
     *
     * The escape hatch, and the reason the interface above is allowed to stay short. Whatever is
     * passed is the backend's business; a widget that uses it is choosing to be backend-specific
     * and should say so.
     *
     * A backend must leave its own state as it found it around this call, and so must the block.
     */
    fun raw(block: (Any) -> Unit)

    /**
     * Whether [raw] has a backend object to hand over.
     *
     * The same bargain as [drawsLayers], [rotatesImages], [turnsLayers] and [supports]: ask before
     * you rely on it. False means [raw] is no use — it throws, or it writes the call down and never
     * runs the block — so a widget that draws through the hatch can pick a composed fallback, skip
     * that layer, or refuse at construction with a message about itself rather than at the first
     * frame with a message about a canvas.
     *
     * The one capability that varies within a backend rather than between backends: the same
     * `GdxCanvas` class answers yes or no depending on whether a `SpriteBatch` was passed to its
     * constructor, so "which backend am I on" does not answer this and no other flag does either.
     *
     * There is no honest degrade for an escape hatch, which is why this is a question and not a
     * fallback. Unlike a layer or a turn, the toolkit cannot draw an approximation of a block it
     * knows nothing about — only the caller knows what it was going to draw.
     */
    val handsOverRaw: Boolean get() = false

    /**
     * [raw], with the backend's origin moved to a corner of [destination].
     *
     * The overwhelmingly common thing to want from the hatch is "draw this, *here*", and without
     * this every caller writes the same subtraction to get there — off [rawX] and [rawY], against a
     * rectangle, with the axis directions to keep straight. That arithmetic is easy to get subtly
     * wrong in a way that only shows up as art in the wrong place.
     *
     * The corner is the one the *backend's* own axes start from, not the toolkit's, because from
     * here on the block is writing the backend's coordinates: the block's (0, 0) is [destination]'s
     * top-left where the backend measures y downwards, and its bottom-left where the backend
     * measures y upwards, so in both cases a block filling `0, 0, width, height` fills the node.
     *
     * Nothing else changes: no scale, no clip, no state the block did not ask for. A block that
     * wants the whole design space still has plain [raw].
     *
     * Ask [movesRawOrigin] first. A canvas that answers false runs the block against the ordinary
     * origin, which is the wrong place — that is why it is a question rather than a quiet default.
     */
    fun raw(destination: Rect, block: (Any) -> Unit) = raw(block)

    /**
     * Whether the [raw] overload that takes a destination really moves the origin there.
     *
     * False means it behaves as plain [raw] and the block draws against the layer's own origin. A
     * backend that overrides [raw] should override both, because unlike an unturned image or an
     * uncomposited layer, an unmoved origin is not a lesser picture — it is the same drawing in the
     * wrong place.
     */
    val movesRawOrigin: Boolean get() = false

    /**
     * An x this interface would take, as the x the object [raw] hands over wants.
     *
     * The companion to [rawY], and the same reasoning: from the moment the drawing object is handed
     * over the block is writing coordinates the backend takes literally, so the conversion has to
     * come from the canvas.
     *
     * Both backends here answer with the x unchanged, and that is the default. x is the easy axis —
     * it points the same way everywhere, and both backends put a layer's own left edge into the
     * projection rather than into the coordinates. It is still worth asking rather than assuming: a
     * backend whose layers start at zero would have to shift it, and a caller cannot see which kind
     * it is holding.
     */
    fun rawX(x: Float): Float = x

    /**
     * A y this interface would take, as the y the object [raw] hands over wants.
     *
     * The toolkit measures y downwards from the top of the design space. A backend's own drawing
     * object may not - LibGDX measures it upwards from the bottom - and every call above reconciles
     * the two on the caller's behalf. [raw] cannot: it hands over the drawing object itself, and
     * from that moment the block is writing coordinates the backend will take literally.
     *
     * So the conversion has to come from here. A block drawing a rectangle at [Rect.top] passes it
     * through this to find the edge its own draw call should be given, and a backend that shares
     * the toolkit's convention returns it untouched - which is the default, because most will.
     *
     * It is not something a caller can work out for itself even knowing the design size. Inside
     * [layer] the origin moves to the layer's own bounds, so the answer depends on state the canvas
     * does not otherwise publish, and the case it differs in is the one nobody thinks to test.
     */
    fun rawY(y: Float): Float = y

    private companion object {

        const val PI_OVER_180 = 0.017453292f

        /**
         * How many straight pieces a curve of this size is worth.
         *
         * Proportional to the arc's own length rather than to the angle, so a small dial and a
         * large one are both drawn to about the same smoothness per unit on screen, and a sliver
         * of a big circle does not cost what the whole circle would.
         */
        fun arcSteps(radius: Float, sweepDegrees: Float, asked: Int): Int {
            if (asked > 0) return asked
            val length = abs(sweepDegrees) * PI_OVER_180 * radius
            return (length / 2f).toInt().coerceIn(8, 180)
        }
    }

    /**
     * How many times this canvas has handed work to the GPU since the frame began, or -1 when the
     * backend does not count.
     *
     * Here, rather than on each backend, so that [dev.wildware.composegl.ui.debug.FrameBudget] can show the
     * number without knowing what a backend is. It is the one number in a frame budget that the
     * toolkit genuinely cannot work out for itself.
     */
    val drawCalls: Int get() = -1

    /**
     * Where to say why each draw call happened, or null to stop saying.
     *
     * A backend that batches calls [DrawCallTrace.record] each time it hands queued work to the GPU,
     * with the reason it had to: a new texture, a blend, a clip, a layer. The trace already knows
     * which node is being drawn, so the backend only has to know why. Called before [begin], and
     * kept until it is called again. The default ignores it, and says so in [tracesDrawCalls].
     */
    fun traceDrawCalls(trace: DrawCallTrace?) = Unit

    /** Whether [traceDrawCalls] records anything. */
    val tracesDrawCalls: Boolean get() = false
}

/**
 * One run of text, with a ring round it only if there is a ring worth drawing.
 *
 * Every widget that draws text — in this module, in `composegl-game`, or in a game's own code —
 * should go through here rather than calling the five-argument [UiCanvas.text] straight, for two
 * reasons that both come down to the plain call being the one everything already knows about.
 *
 * A canvas is very often a wrapper — `UiCanvas by inner` in a test, something that counts clips or
 * tints a subtree — and a wrapper written before outlines existed overrides the four-argument call
 * and nothing else. Sending an unoutlined run through the five-argument call would hand it to the
 * wrapped canvas instead, and the wrapper would silently stop seeing text it used to see.
 *
 * And it keeps the ordinary path exactly as cheap as it was: a label with no outline reaches the
 * backend through the same one call it always did.
 */
fun UiCanvas.textRun(
    layout: TextLayout,
    x: Float,
    y: Float,
    colour: Colour,
    outline: TextOutline?,
) {
    if (outline == null || !outline.isVisible) text(layout, x, y, colour)
    else text(layout, x, y, colour, outline)
}

/**
 * A filled box, through the single-radius call whenever one radius says it.
 *
 * The same reasoning as [textRun]. A wrapper canvas written before [Corners] existed overrides
 * `rect(Rect, Colour, Float)` and nothing else; a box with four equal corners sent through the
 * [Corners] overload would go straight past it to whatever it wraps. So only a box whose corners
 * really differ takes the new call.
 */
internal fun UiCanvas.box(rect: Rect, colour: Colour, corners: Corners) {
    if (corners.isUniform) rect(rect, colour, corners.topLeft) else rect(rect, colour, corners)
}

/** The same, for a box filled with a [Brush]. */
internal fun UiCanvas.box(rect: Rect, brush: Brush, corners: Corners) {
    if (corners.isUniform) rect(rect, brush, corners.topLeft) else rect(rect, brush, corners)
}

/** The same, for an outline. */
internal fun UiCanvas.boxBorder(rect: Rect, colour: Colour, width: Float, corners: Corners) {
    if (corners.isUniform) border(rect, colour, width, corners.topLeft)
    else border(rect, colour, width, corners)
}

/** The same, for a shadow. */
internal fun UiCanvas.boxShadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
    if (corners.isUniform) shadow(rect, colour, spread, corners.topLeft)
    else shadow(rect, colour, spread, corners)
}
