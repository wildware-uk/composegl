package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.node.UiNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, written into the device's vertex storage and handed over in as
 * few draw calls as the textures, blend modes and clips allow.
 *
 * One copy for every backend, so a draw-call trace means the same thing whichever one is drawing.
 * The coordinates reaching here already count y upwards: the canvas flips once on the way in.
 *
 * A vertex carries its fill and border as the colour's own four bytes, one slot each, and its shadow
 * as four floats: see [ShapeVertex]. Every colour arriving has already had the canvas's alpha and
 * tint stacks multiplied into it, so this batch has no idea either exists.
 */
class QuadBatch(private val device: GpuDevice, private val maxQuads: Int = 2048) {

    private val vertices = device.vertices(maxQuads)
    private val capacity = maxQuads * 4 * ShapeVertex.Floats

    /**
     * What is queued, written here and handed to [vertices] in one copy when it flushes. Not
     * straight into [vertices]: a float at a time through a platform buffer was a fifth of a frame
     * on Android.
     */
    private val floats = FloatArray(capacity)
    private var used = 0

    private var texture: DeviceTexture? = null

    /** Which shape program what is queued needs: see [ShapeProgram]. */
    private var program = ShapeProgram.Common
    private var drawing = false

    /*
     * The blend, the rounded clip and the scissor are asked for here and handed on only when a
     * quad is queued under them, so a change undone before anything was drawn — a clip round a
     * list scrolled out of sight — cuts nothing. What the queue was queued under is [blend],
     * [mask] and [scissorOn] with [scissorBox]; what the next quad asks for is the wanted one.
     */
    private var blend = Blend.SourceOver
    private var wantedBlend = Blend.SourceOver
    private var mask: ClipMask? = null
    private var wantedMask: ClipMask? = null
    private var scissorOn = false
    private val scissorBox = IntArray(4)
    private var wantedScissor = false
    private val wantedBox = IntArray(4)
    private var scissorAsked = false

    /** Why the first change asked for since the queue last caught up would cut it, and while drawing what. */
    private var askedReason: BatchBreak? = null
    private var askedBy: UiNode? = null

    private val projection = FloatArray(16)

    /** How many times this batch talked to the device since [begin]. */
    var renderCalls = 0
        private set

    /** Told why, each time [renderCalls] goes up. Null tells nobody. */
    var trace: DrawCallTrace? = null

    /** Starts a frame. [projection] is a column-major 4x4, in design coordinates. */
    fun begin(projection: FloatArray) {
        check(!drawing) { "begin() was called twice without an end()" }
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        drawing = true
        renderCalls = 0
        projection.copyInto(this.projection)
        blend = Blend.SourceOver
        wantedBlend = Blend.SourceOver
        askedReason = null
        askedBy = null
        mask = null
        wantedMask = null
        // A frame that threw between holdInside and letGo must not hold the next one's pictures.
        holding = false
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush(BatchBreak.End)
        drawing = false
    }

    /** Points the following quads somewhere else. Flushes first: what is queued was for the old one. */
    fun projection(projection: FloatArray) {
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        flush(BatchBreak.Layer)
        projection.copyInto(this.projection)
    }

    /**
     * How the following quads are combined with what is already there.
     *
     * The one place this batch's blending is decided. What is queued was queued to blend the old
     * way, so it goes first, blamed on [reason] — when the next quad is queued, and only if it is
     * to blend differently: a mode put back before anything was drawn in it breaks nothing.
     *
     * @param premultiplied true for a layer being drawn back, whose colours are already multiplied
     *   by their own opacity.
     */
    fun blend(mode: BlendMode, premultiplied: Boolean, reason: BatchBreak = BatchBreak.Blend) {
        wantedBlend = Blend.of(mode, premultiplied, lightCovers)
        asked(reason)
    }

    /**
     * Whether light's opacity accumulates in the target for the frame being drawn: see
     * [Blend.lightCovers]. Set before [begin].
     */
    var lightCovers = false

    /**
     * Nothing lands outside this box, in the target's pixels counted up from its bottom-left, from
     * the next quad queued on. Handed to the device then, and only if it differs from the box the
     * queue was queued under, which goes first, blamed on the clip.
     */
    fun scissor(x: Int, y: Int, width: Int, height: Int) {
        wantedScissor = true
        wantedBox[0] = x
        wantedBox[1] = y
        wantedBox[2] = width
        wantedBox[3] = height
        scissorAsked = true
        asked(BatchBreak.Clip)
    }

    /** No scissor from the next quad queued on, as [scissor] hands one on. */
    fun noScissor() {
        wantedScissor = false
        scissorAsked = true
        asked(BatchBreak.Clip)
    }

    /**
     * No scissor on the device right now, as a clear of a whole target needs, and none wanted
     * until [scissor] says otherwise. Whatever is queued goes first.
     */
    fun noScissorNow() {
        flush(BatchBreak.Clip)
        wantedScissor = false
        scissorOn = false
        scissorAsked = false
        if (!differs()) forgetAsked()
        device.noScissor()
    }

    /**
     * What is queued goes to the device, blamed on [reason], and the device takes the scissor
     * asked for: before drawing that does not go through the batch — a picture through somebody's
     * shader, a game's own — and must be cut by the clip in force all the same.
     */
    fun flushForDevice(reason: BatchBreak) {
        flush(reason)
        catchUpScissor()
        if (!differs()) forgetAsked()
    }

    /**
     * Blames the cut, when it comes, on the first change asked for that still stands — unless
     * nothing now differs from what the queue holds, when there is nothing to blame.
     */
    private fun asked(reason: BatchBreak) {
        if (!differs()) return forgetAsked()
        if (askedReason != null) return
        askedReason = reason
        askedBy = trace?.node
    }

    private fun forgetAsked() {
        askedReason = null
        askedBy = null
    }

    /** Whether what the next quad asks for differs from what the queue was queued under. */
    private fun differs(): Boolean =
        wantedBlend != blend || wantedMask !== mask || (scissorAsked && !scissorIsWanted())

    /** Before a quad is queued: what it asks for differs from what the queue holds, so the queue goes first. */
    private fun catchUp() {
        val reason = askedReason ?: return
        if (differs()) cut(reason, askedBy)
        blend = wantedBlend
        mask = wantedMask
        catchUpScissor()
        forgetAsked()
    }

    private fun catchUpScissor() {
        if (!scissorAsked) return
        scissorAsked = false
        if (scissorIsWanted()) return
        scissorOn = wantedScissor
        if (wantedScissor) {
            wantedBox.copyInto(scissorBox)
            device.scissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        } else {
            device.noScissor()
        }
    }

    private fun scissorIsWanted(): Boolean =
        if (!wantedScissor) {
            !scissorOn
        } else {
            scissorOn && wantedBox[0] == scissorBox[0] && wantedBox[1] == scissorBox[1] &&
                wantedBox[2] == scissorBox[2] && wantedBox[3] == scissorBox[3]
        }

    /**
     * The rounded clip the following quads are kept inside, or null for none.
     *
     * What is queued was queued under the old one, so it goes first, blamed on the clip — when the
     * next quad is queued, and only if it is to be kept inside a different one. The batch holds
     * [next] rather than a copy, so whoever fills one in again says so first: see [refilling].
     */
    fun mask(next: ClipMask?) {
        wantedMask = next
        asked(BatchBreak.Clip)
    }

    /**
     * [mask] is about to be filled in again. Anything still queued inside it goes now, while it
     * still says where it was: a clip taken off with nothing drawn since leaves the queue holding it.
     */
    fun refilling(mask: ClipMask) {
        if (this.mask !== mask || used == 0) return
        val reason = askedReason
        if (reason != null) cut(reason, askedBy) else cut(BatchBreak.Clip, trace?.node)
    }

    /**
     * Hands what is queued to the device, blaming [reason] on the trace — but only when something
     * was queued, since an empty flush costs no draw call.
     */
    fun flush(reason: BatchBreak) {
        if (used == 0) return
        draw()
        trace?.record(reason)
    }

    /** [flush], blamed on [node] — the one that asked for the change — rather than the one drawing now. */
    private fun cut(reason: BatchBreak, node: UiNode?) {
        if (used == 0) return
        draw()
        val trace = trace ?: return
        val now = trace.node
        trace.node = node
        trace.record(reason)
        trace.node = now
    }

    private fun draw() {
        val quads = used / (4 * ShapeVertex.Floats)
        vertices.put(floats, used)
        device.drawShapes(vertices, quads, checkNotNull(texture), blend, projection, mask, program)
        renderCalls++
        used = 0
    }

    /**
     * One rounded box with a radius for each corner, and any of a fill, a border and a shadow.
     *
     * The corners are named as they look on screen. Each is held to half the box's shorter side.
     *
     * A plain box — a fill, perhaps a border, nothing cast — that is big enough is split: its
     * middle, where no corner, border or soft edge reaches, is a flat quad the shader treats as a
     * picture of the white spot, and the band round it is four strips through the distance field.
     * Big panels are most of what a screen paints, and the picture path is the cheapest thing the
     * shader does. The pixels are the ones the single quad drew. An outline's clear middle is left
     * out, so it is four quads; under a premultiplied blend a box keeps its one quad.
     *
     * @param white where solid colour is sampled from.
     * @param borderWidth how thick the border is, drawn inside the edge; negative draws it outside.
     * @param shadowSpread how far the shadow reaches outside the shape; negative shades inside it.
     * @param shadowOffsetX how far the inside shade is moved across, so it gathers along one edge.
     * @param shadowOffsetY the same, in this batch's y-up coordinates. Ignored by a shadow outside.
     * @param shadowHardness how hard the inside shade's inner edge is: 0 fades the whole way in, near
     *   1 holds and then drops. Ignored by a shadow outside.
     * @param aa how wide the softened edge is, in the same units as everything else.
     */
    @Suppress("LongParameterList")
    fun shape(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        fill: Colour,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        shadow: Colour,
        shadowSpread: Float,
        aa: Float,
        shadowOffsetX: Float = 0f,
        shadowOffsetY: Float = 0f,
        shadowHardness: Float = 0f,
    ) {
        // Room for whatever reaches outside the box: a shadow's spread, or a border drawn outside.
        val margin = maxOf(shadowSpread, -borderWidth, 0f) + aa
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
        val u = white.u
        val v = white.v
        val outerLeft = left - margin
        val outerBottom = bottom - margin
        val outerRight = left + width + margin
        val outerTop = bottom + height + margin

        // A shade inside the shape is one of the paths only the full program has. Every part of
        // the box, its flat middle too, goes through the one program, so splitting it never cuts
        // the batch: every program has the picture path.
        val program = if (shadowSpread < 0f) ShapeProgram.Full else ShapeProgram.Common

        /** Part of the box's quad, through the distance field: the whole of it, or a strip of its edge. */
        fun part(partLeft: Float, partBottom: Float, partRight: Float, partTop: Float) {
            use(white.texture, program)
            quad(
                left = partLeft,
                bottom = partBottom,
                right = partRight,
                top = partTop,
                centreX = left + halfWidth,
                centreY = bottom + halfHeight,
                u = u, v = v, u2 = u, v2 = v,
                fill = fill,
                border = border,
                shadow = shadow,
                halfWidth = halfWidth,
                halfHeight = halfHeight,
                radii = radii,
                borderWidth = borderWidth,
                shadowSpread = shadowSpread,
                aa = aa,
                // A shade inside the shape has no gradient, so its offset and how hard it falls ride
                // in the gradient's slots: the axis, and the kind as a negative number.
                gradient = if (shadowSpread < 0f) -shadowHardness.coerceIn(0f, 0.95f) else 0f,
                gradientX = if (shadowSpread < 0f) shadowOffsetX else 0f,
                gradientY = if (shadowSpread < 0f) shadowOffsetY else 0f,
            )
        }

        // Plain: nothing cast, or nothing to see in what is. Zero soft edge would say "a picture".
        // Under a premultiplied blend — a layer being drawn back, never a box in practice — the
        // box keeps its one quad: there a colour with no opacity is not nothing, and the middle
        // would have to copy exactly what the distance field makes of one.
        // [wantedBlend] is what the box will be drawn under, whatever is still queued.
        if (aa > 0f && (shadowSpread == 0f || shadow.alpha == 0) && !wantedBlend.premultiplied) {
            // Where the distance field stops changing anything: past the soft edge, and past a
            // border drawn inside. Every pixel further in comes out the fill, exactly.
            val flatFrom = aa + maxOf(borderWidth, 0f)
            val topLeftIn = flatInset(radii[0], flatFrom)
            val topRightIn = flatInset(radii[1], flatFrom)
            val bottomRightIn = flatInset(radii[2], flatFrom)
            val bottomLeftIn = flatInset(radii[3], flatFrom)
            val innerLeft = left + maxOf(topLeftIn, bottomLeftIn)
            val innerRight = left + width - maxOf(topRightIn, bottomRightIn)
            val innerBottom = bottom + maxOf(bottomLeftIn, bottomRightIn)
            val innerTop = bottom + height - maxOf(topLeftIn, topRightIn)
            if (innerRight > innerLeft && innerTop > innerBottom &&
                (innerRight - innerLeft) * (innerTop - innerBottom) >= MinFlatPixels * aa * aa
            ) {
                // The band round the middle: the bottom and top strips the whole width, the sides
                // between them. Every pixel of the one quad lands in exactly one of the five, and
                // the first corner written is still the box's outer bottom-left.
                part(outerLeft, outerBottom, outerRight, innerBottom)
                part(outerLeft, innerTop, outerRight, outerTop)
                part(outerLeft, innerBottom, innerLeft, innerTop)
                part(innerRight, innerBottom, outerRight, innerTop)
                // A clear middle changes no pixel under a straight blend — its colour is weighed by
                // its opacity and its opacity adds nothing — so an outline leaves it out.
                if (fill.alpha > 0) {
                    use(white.texture, program)
                    quad(
                        left = innerLeft,
                        bottom = innerBottom,
                        right = innerRight,
                        top = innerTop,
                        centreX = 0f,
                        centreY = 0f,
                        u = u, v = v, u2 = u, v2 = v,
                        fill = fill,
                        border = Colour.Transparent,
                        shadow = Colour.Transparent,
                        halfWidth = 0f,
                        halfHeight = 0f,
                        radii = noRadii,
                        borderWidth = 0f,
                        shadowSpread = 0f,
                        // Zero says "a picture": the white spot times the fill, and nothing else.
                        aa = 0f,
                    )
                }
                return
            }
        }

        part(outerLeft, outerBottom, outerRight, outerTop)
    }

    /**
     * How far in from both of its edges a corner of [radius] lets the flat middle begin: where the
     * distance field is at least [flatFrom] inside the box.
     *
     * Past a corner no rounder than [flatFrom], that is [flatFrom]. A rounder corner's curve cuts
     * across the middle's corner instead: a point `a` in from both edges is `√2·(radius − a)` from
     * the curve's centre, so `radius − √2·(radius − a)` inside, which is [flatFrom] at the `a`
     * given here.
     */
    private fun flatInset(radius: Float, flatFrom: Float): Float =
        if (radius <= flatFrom) flatFrom else radius - (radius - flatFrom) * InverseRootTwo

    /**
     * One rounded box as a lit surface: the difference a light makes to it, over whatever is under.
     *
     * The shape is given a height along its edge — cut flat, rolled over, or curving across the
     * whole face — and the normal of that height is lit by one light. Nothing about the fill is
     * needed, because what comes out is the light's own contribution: dark where the surface falls
     * away and bright where it faces the light, which lies over any fill at all.
     *
     * @param kind which shape the edge is: one of [ShapeVertex.ReliefChamfer], [ShapeVertex.ReliefFillet]
     *   or [ShapeVertex.ReliefDome].
     * @param bevel how far the climb reaches in from the edge.
     * @param strength how much difference the light makes.
     * @param lightX where the light is, as a direction; [lightZ] is how high above the surface.
     * @param gloss how bright the specular highlight is. Zero for a matte surface.
     * @param polish how tight that highlight is: low spreads it across the lit side, high draws it
     *   to a point. Rides in the fill colour's red, which a lit quad does not otherwise use.
     * @param face the colour to light, or transparent to light whatever is underneath instead. A
     *   colour of its own is what makes a highlight the same hue only brighter; laid over another
     *   fill, a lit quad can only add white, which takes the colour out of the bright parts.
     */
    @Suppress("LongParameterList")
    fun relief(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        kind: Float,
        bevel: Float,
        strength: Float,
        lightX: Float,
        lightY: Float,
        lightZ: Float,
        gloss: Float,
        polish: Float,
        face: Colour,
        faceU: Float,
        faceV: Float,
        faceWidth: Float,
        faceTiles: Float,
        aa: Float,
    ) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)

        use(white.texture, ShapeProgram.Full)
        quad(
            left = left - aa,
            bottom = bottom - aa,
            right = left + width + aa,
            top = bottom + height + aa,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            // Where the face's run of colours starts on the atlas, if it has one. A lit quad has
            // no texture of its own, so the texture coordinate is free to point at the strip.
            u = faceU, v = faceV, u2 = faceU, v2 = faceV,
            // Opaque white but for the red, which carries how polished the surface is.
            fill = Colour(255, (polish.coerceIn(0f, 1f) * 255f).toInt(), 255, 255),
            border = face,
            // The light, packed as a colour: a direction of minus one to one, written zero to one.
            shadow = Colour(
                (gloss.coerceIn(0f, 1f) * 255f).toInt(),
                ((lightX * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255),
                ((lightY * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255),
                ((lightZ * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255),
            ),
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            radii = radii,
            // A width of one says the quad paints the face colour rather than lying over a fill.
            borderWidth = if (face.alpha > 0) 1f else 0f,
            // How far along the atlas row the run of colours reaches, or — as a negative — how
            // many times a material is laid across the face. Zero says the face is one flat colour.
            // A lit quad casts no shadow, so the spread is free to say which of the three it is.
            shadowSpread = if (faceTiles > 0f) -faceTiles else faceWidth,
            aa = aa,
            gradient = kind,
            gradientX = bevel,
            gradientY = strength,
        )
    }

    /**
     * One rounded box filled from a strip of the atlas: a gradient of more than two colours.
     *
     * The same quad and the same distance field as [shape], and the strip is on the same texture
     * flat colour comes from, so it costs no texture switch. It does need the full program
     * ([ShapeProgram.Full]), so it shares a draw call only with lit surfaces, inside shades and other
     * runs drawn next to it. Where the strip is rides in the shadow colour's slot, which a gradient
     * never uses, so the border's slot is free and a run of stops can carry an outline in the same quad.
     *
     * @param tint what the strip is multiplied by: the alpha and tint in force.
     * @param u where the strip starts, in texture coordinates, and [u2] where it ends.
     */
    @Suppress("LongParameterList")
    fun rampGradient(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        tint: Colour,
        radial: Boolean,
        axisX: Float,
        axisY: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        aa: Float,
    ) = rampGradient(
        white.texture, white.u, white.v, left, bottom, width, height, tint, radial, axisX, axisY,
        u, v, u2, v2, topLeft, topRight, bottomRight, bottomLeft, border, borderWidth, aa,
    )

    /**
     * The same, with the [WhiteSpot]'s three parts handed over loose: the strip's [texture], and the
     * [whiteU], [whiteV] the quad itself reads. What a canvas calls for every run of stops it draws,
     * so it makes no spot each time (#252).
     */
    @Suppress("LongParameterList")
    fun rampGradient(
        texture: DeviceTexture,
        whiteU: Float,
        whiteV: Float,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        tint: Colour,
        radial: Boolean,
        axisX: Float,
        axisY: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        aa: Float,
    ) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
        val margin = maxOf(-borderWidth, 0f) + aa

        use(texture, ShapeProgram.Full)
        quad(
            left = left - margin,
            bottom = bottom - margin,
            right = left + width + margin,
            top = bottom + height + margin,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            u = whiteU, v = whiteV, u2 = whiteU, v2 = whiteV,
            fill = tint,
            border = border,
            // The strip's two ends, as a colour that is really four numbers.
            shadow = Colour.Transparent,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            radii = radii,
            borderWidth = borderWidth,
            shadowSpread = 0f,
            aa = aa,
            gradient = if (radial) ShapeVertex.RadialRamp else ShapeVertex.LinearRamp,
            gradientX = axisX,
            gradientY = axisY,
            ramp = rampEnds.also {
                it[0] = u
                it[1] = v
                it[2] = u2
                it[3] = v2
            },
        )
    }

    /** Where [rampGradient] writes the strip's two ends for its quad: read straight after, so kept rather than made. */
    private val rampEnds = FloatArray(4)

    /**
     * One rounded box filled with a gradient between [start] and [end]: the same quad and the same
     * distance field as [shape], so it batches with every flat panel. The end colour rides in the
     * border colour's slot.
     *
     * @param axisX how far along a straight gradient one unit across moves it, already divided by
     *   its length. Ignored when [radial].
     * @param axisY the same, in this batch's y-up coordinates.
     */
    @Suppress("LongParameterList")
    fun gradient(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        start: Colour,
        end: Colour,
        radial: Boolean,
        axisX: Float,
        axisY: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        aa: Float,
    ) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
        val u = white.u
        val v = white.v

        // The common program reads the axis at mediump on a phone, whose smallest ordinary number
        // is 2^-14: a gradient stretched past about 16,000 units each way, across a box wide enough
        // for that to show, takes the full program instead, which reads it whole.
        val fits = radial || fitsSmall(axisX, halfWidth + aa) && fitsSmall(axisY, halfHeight + aa)
        use(white.texture, if (fits) ShapeProgram.Common else ShapeProgram.Full)
        quad(
            left = left - aa,
            bottom = bottom - aa,
            right = left + width + aa,
            top = bottom + height + aa,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            u = u, v = v, u2 = u, v2 = v,
            fill = start,
            border = end,
            shadow = Colour.Transparent,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            radii = radii,
            borderWidth = 0f,
            shadowSpread = 0f,
            aa = aa,
            gradient = if (radial) ShapeVertex.Radial else ShapeVertex.Linear,
            gradientX = axisX,
            gradientY = axisY,
        )
    }

    /**
     * Whether a gradient axis of [axis] a unit still says what it should at `mediump`, over a box
     * reaching [half] units each side of its middle: big enough to be an ordinary number there, or
     * too small to move the gradient a thousandth of the way however it is rounded.
     */
    private fun fitsSmall(axis: Float, half: Float): Boolean {
        val size = abs(axis)
        return size >= SmallestMedium || size * half < 1f / 1024f
    }

    /** The four radii of the box being written, top-left then clockwise. Read straight after. */
    private val radii = FloatArray(4)

    /** What a picture, a glyph or a fan vertex carries: no corners, since it has no shape. */
    private val noRadii = FloatArray(4)

    /** Where a picture's reads are held while [holdInside] is in force: see there. */
    private val held = FloatArray(4)
    private var holding = false

    /** What a picture's vertices carry in the radii's place: the box its reads are held in, or nothing. */
    private val pictureRadii: FloatArray get() = if (holding) held else noRadii

    /**
     * The program a picture written now needs: a held one reads its radii, which the common program
     * leaves out. [ShapeProgram.Held] is the light one that reads them; the full one does too, since
     * it draws everything.
     */
    private val pictureProgram: ShapeProgram get() = if (holding) ShapeProgram.Held else ShapeProgram.Common

    /**
     * Until [letGo], every picture written holds its reads inside the texture box [left], [bottom],
     * [right], [top]: the corner of a pooled picture a layer was drawn into, half a texel in from
     * each edge. A turned or stretched layer then reads its own edge just past it, as a picture its
     * own size clamps there, rather than the clear strip round the corner.
     *
     * It rides in the radii, which a picture has no use for. The common program does not read them,
     * so a held picture is drawn through [ShapeProgram.Held]: a draw call of its own beside unheld
     * quads, but it is one anyway, since a held picture is a pooled layer's own texture.
     */
    fun holdInside(left: Float, bottom: Float, right: Float, top: Float) {
        held[0] = left
        held[1] = bottom
        held[2] = right
        held[3] = top
        holding = true
    }

    fun letGo() {
        holding = false
    }

    /**
     * A triangle fan, in coordinates already flipped.
     *
     * The batch draws quads and nothing else, so each quad carries *two* of the fan's triangles:
     * the quad's own winding — 0,1,2 then 2,3,0 — is already hub, a, b and then b, c, hub. An odd
     * point at the end repeats, which draws a triangle of no area.
     */
    fun fan(white: WhiteSpot, points: FloatArray, colour: Colour) {
        if (points.size < 6) return
        val u = white.u
        val v = white.v
        val hubX = points[0]
        val hubY = points[1]

        var at = 2
        while (at + 3 < points.size) {
            val cx = if (at + 5 < points.size) points[at + 4] else points[at + 2]
            val cy = if (at + 5 < points.size) points[at + 5] else points[at + 3]
            use(white.texture)
            flat(hubX, hubY, u, v, colour)
            flat(points[at], points[at + 1], u, v, colour)
            flat(points[at + 2], points[at + 3], u, v, colour)
            flat(cx, cy, u, v, colour)
            at += 4
        }
    }

    /** A picture, or a glyph. No shape and no softened edge — whatever the texture says. */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
        premultiplied: Boolean = false,
    ) {
        use(texture, pictureProgram)
        quad(
            left = left,
            bottom = bottom,
            right = left + width,
            top = bottom + height,
            centreX = 0f,
            centreY = 0f,
            u = u, v = v, u2 = u2, v2 = v2,
            fill = tint,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            halfWidth = 0f,
            halfHeight = 0f,
            radii = pictureRadii,
            borderWidth = 0f,
            shadowSpread = 0f,
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
            gradient = if (premultiplied) ShapeVertex.PremultipliedPicture else 0f,
        )
    }

    /**
     * The same picture, turned round a pivot, written as an ordinary quad so it batches with every
     * other quad from the same texture.
     *
     * [degrees] turns it clockwise as the toolkit's y-down coordinates see it; everything here is
     * already flipped, so the sign looks back to front on purpose. [pivotX] and [pivotY] are a point
     * in the same flipped coordinates as [left] and [bottom].
     */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        pivotX: Float,
        pivotY: Float,
        degrees: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
        premultiplied: Boolean = false,
    ) {
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        val right = left + width
        val top = bottom + height
        val kind = if (premultiplied) ShapeVertex.PremultipliedPicture else 0f

        use(texture, pictureProgram)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        turned(left, bottom, pivotX, pivotY, turnCos, turnSin, u, v2, tint, kind)
        turned(left, top, pivotX, pivotY, turnCos, turnSin, u, v, tint, kind)
        turned(right, top, pivotX, pivotY, turnCos, turnSin, u2, v, tint, kind)
        turned(right, bottom, pivotX, pivotY, turnCos, turnSin, u2, v2, tint, kind)
    }

    /**
     * The same picture on four corners somebody else worked out: top-left, top-right, bottom-right,
     * bottom-left, each an x then a y in this batch's y-up coordinates.
     */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(texture, pictureProgram)
        flat(corners[6], corners[7], u, v2, tint, pictureRadii)
        flat(corners[0], corners[1], u, v, tint, pictureRadii)
        flat(corners[2], corners[3], u2, v, tint, pictureRadii)
        flat(corners[4], corners[5], u2, v2, tint, pictureRadii)
    }

    /**
     * The same picture on four corners that each carry a depth: twelve numbers, top-left, top-right,
     * bottom-right, bottom-left, each an x, a y and a w *before* the divide. The GPU divides per
     * pixel, so the picture does not bend along the diagonal.
     */
    @Suppress("LongParameterList")
    fun projected(
        texture: DeviceTexture,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(texture, pictureProgram)
        deep(corners, 9, u, v2, tint)
        deep(corners, 0, u, v, tint)
        deep(corners, 3, u2, v, tint)
        deep(corners, 6, u2, v2, tint)
    }

    /**
     * Four corners of a picture, each with its own place, texture coordinate and colour: what a
     * picture cut to a shape is drawn with. In winding order, in coordinates already flipped.
     */
    @Suppress("LongParameterList")
    fun corners(
        texture: DeviceTexture,
        ax: Float, ay: Float, au: Float, av: Float, aColour: Colour,
        bx: Float, by: Float, bu: Float, bv: Float, bColour: Colour,
        cx: Float, cy: Float, cu: Float, cv: Float, cColour: Colour,
        dx: Float, dy: Float, du: Float, dv: Float, dColour: Colour,
    ) {
        use(texture, pictureProgram)
        flat(ax, ay, au, av, aColour, pictureRadii)
        flat(bx, by, bu, bv, bColour, pictureRadii)
        flat(cx, cy, cu, cv, cColour, pictureRadii)
        flat(dx, dy, du, dv, dColour, pictureRadii)
    }

    /** One vertex of solid colour, or of a picture, with the distance field switched off. */
    private fun flat(x: Float, y: Float, u: Float, v: Float, colour: Colour, radii: FloatArray = noRadii) {
        vertex(
            x = x, y = y, u = u, v = v,
            fill = colour,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = radii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
        )
    }

    private fun deep(corners: FloatArray, at: Int, u: Float, v: Float, tint: Colour) {
        vertex(
            x = corners[at], y = corners[at + 1], u = u, v = v,
            fill = tint, border = Colour.Transparent, shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = pictureRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
            w = corners[at + 2],
        )
    }

    /** One corner of a turned picture. The minus on the sine is the y flip. */
    @Suppress("LongParameterList")
    private fun turned(
        x: Float, y: Float,
        pivotX: Float, pivotY: Float,
        turnCos: Float, turnSin: Float,
        u: Float, v: Float,
        tint: Colour,
        kind: Float,
    ) {
        val acrossX = x - pivotX
        val acrossY = y - pivotY
        vertex(
            x = pivotX + acrossX * turnCos + acrossY * turnSin,
            y = pivotY - acrossX * turnSin + acrossY * turnCos,
            u = u, v = v,
            fill = tint,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = pictureRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
            gradient = kind,
        )
    }

    /**
     * Makes room for one quad from [next], drawn through [needs]. What is queued goes first when it
     * was queued under another blend, clip or scissor, was from another texture, needs another
     * program, or fills the batch.
     */
    private fun use(next: DeviceTexture, needs: ShapeProgram = ShapeProgram.Common) {
        catchUp()
        if (texture != next) {
            flush(BatchBreak.Texture)
            texture = next
        } else if (needs != program) {
            flush(BatchBreak.Program)
        } else if (used + 4 * ShapeVertex.Floats > capacity) {
            flush(BatchBreak.Full)
        }
        program = needs
    }

    @Suppress("LongParameterList")
    private fun quad(
        left: Float, bottom: Float, right: Float, top: Float,
        centreX: Float, centreY: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        fill: Colour, border: Colour, shadow: Colour,
        halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
        ramp: FloatArray? = null,
    ) {
        // Wound anticlockwise from the bottom-left; `v` is the coordinate at the quad's *top*.
        vertex(left, bottom, u, v2, fill, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY, ramp = ramp)
        vertex(left, top, u, v, fill, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY, ramp = ramp)
        vertex(right, top, u2, v, fill, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY, ramp = ramp)
        vertex(right, bottom, u2, v2, fill, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY, ramp = ramp)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        fill: Colour, border: Colour, shadow: Colour,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
        w: Float = 1f,
        ramp: FloatArray? = null,
    ) {
        val out = floats
        var at = used
        out[at++] = x
        out[at++] = y
        out[at++] = w
        // The colour's bytes as they are, one store each; the GPU reads them as fractions of 255.
        out[at++] = ShapeVertex.packed(fill)
        out[at++] = ShapeVertex.packed(border)
        // A strip of the atlas rides where the shadow's colour would be: a gradient never casts one.
        if (ramp != null) {
            out[at++] = ramp[0]
            out[at++] = ramp[1]
            out[at++] = ramp[2]
            out[at++] = ramp[3]
        } else {
            at = writeColour(shadow, at)
        }
        out[at++] = u
        out[at++] = v
        out[at++] = localX
        out[at++] = localY
        out[at++] = halfWidth
        out[at++] = halfHeight
        out[at++] = borderWidth
        out[at++] = shadowSpread
        out[at++] = aa
        out[at++] = radii[0]
        out[at++] = radii[1]
        out[at++] = radii[2]
        out[at++] = radii[3]
        out[at++] = gradient
        out[at++] = gradientX
        out[at++] = gradientY
        used = at
    }

    /** The shadow's four floats, red first. The toolkit's packed integer undone once, here. */
    private fun writeColour(colour: Colour, at: Int): Int {
        floats[at] = colour.red / 255f
        floats[at + 1] = colour.green / 255f
        floats[at + 2] = colour.blue / 255f
        floats[at + 3] = colour.alphaFraction
        return at + 4
    }

    private companion object {
        /** The smallest ordinary number `mediump` promises: 2^-14. Smaller ones may read as zero. */
        const val SmallestMedium = 1f / 16384f

        /**
         * The fewest pixels a plain box's flat middle covers before it is drawn on its own: 64 by
         * 64. Splitting costs four more quads, sixteen vertices of 100 bytes written, uploaded and
         * shaded, against at least 0.62 Mali-G57 cycles saved on each pixel of the middle. Much
         * smaller and the vertices cost about what the pixels save.
         */
        const val MinFlatPixels = 4096f

        const val InverseRootTwo = 0.70710677f
    }
}

/**
 * Where solid colour is sampled from: a texture and one texel-exact point inside a white block.
 *
 * The glyph atlas's white block when there are fonts, so a panel and its label are one texture and
 * one draw call.
 */
class WhiteSpot(val texture: DeviceTexture, val u: Float, val v: Float)
