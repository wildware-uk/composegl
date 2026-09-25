package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout

/**
 * One thing a [RecordingCanvas] was asked to draw, with the clip and opacity in force at the time.
 *
 * The blend mode in force is not here, for the reason [RotatedImage] gives about its own existence:
 * these are published data classes and a new constructor parameter breaks every one of them at the
 * binary level. Ask the canvas instead — [RecordingCanvas.blendOf] takes a call and answers.
 */
sealed interface DrawCall {

    /** The clip that applied. A call whose clip is empty would have drawn nothing on screen. */
    val clip: Rect

    /** The opacity that applied, already multiplied down the tree. */
    val alpha: Float

    data class Rectangle(
        val rect: Rect,
        val colour: Colour,
        val corner: Float,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /**
     * A rectangle filled with a [Brush].
     *
     * Its own kind rather than a brush on [Rectangle], for the reason [RotatedImage] gives: a new
     * constructor parameter on a published data class is a binary break. A test that only asks for
     * flat rectangles keeps seeing exactly the ones it always saw.
     */
    data class GradientRectangle(
        val rect: Rect,
        val brush: Brush,
        val corner: Float,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    data class Border(
        val rect: Rect,
        val colour: Colour,
        val width: Float,
        val corner: Float,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    data class Shadow(
        val rect: Rect,
        val colour: Colour,
        val spread: Float,
        val corner: Float,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** An outline hugging the outside of a box: [UiCanvas.borderOutside]. */
    data class OutsideBorder(
        val rect: Rect,
        val colour: Colour,
        val width: Float,
        val corners: Corners,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** Shade falling inwards from a box's edge, moved by an offset: [UiCanvas.innerShade]. */
    data class InnerShade(
        val rect: Rect,
        val colour: Colour,
        val depth: Float,
        val corners: Corners,
        val offset: Offset,
        val hardness: Float,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /**
     * A filled box whose corners were not all given the same radius.
     *
     * Its own kind rather than a new field on [Rectangle], for the reason [RotatedImage] gives:
     * [Rectangle] is published, and a new constructor parameter on a data class is a binary break.
     * A box whose four corners agree is recorded as a plain [Rectangle], with that one radius, so a
     * test written before [Corners] existed keeps passing.
     */
    data class CorneredRectangle(
        val rect: Rect,
        val colour: Colour,
        val corners: Corners,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** An outline whose corners differ. One whose corners agree is a plain [Border]. */
    data class CorneredBorder(
        val rect: Rect,
        val colour: Colour,
        val width: Float,
        val corners: Corners,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** A shadow whose corners differ. One whose corners agree is a plain [Shadow]. */
    data class CorneredShadow(
        val rect: Rect,
        val colour: Colour,
        val spread: Float,
        val corners: Corners,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** A gradient box whose corners differ. One whose corners agree is a plain [GradientRectangle]. */
    data class CorneredGradientRectangle(
        val rect: Rect,
        val brush: Brush,
        val corners: Corners,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** A triangle fan. The first point is the hub; [points] is in the order it was handed over. */
    data class Fan(
        val points: List<Offset>,
        val colour: Colour,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    data class Text(
        val text: String,
        val at: Offset,
        val colour: Colour,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /**
     * A picture, upright or turned.
     *
     * Here so that a test which only cares *that* a picture was drawn — and where, and in what
     * colour — can ask for these and get both, rather than asking twice and joining the answers
     * itself. A test that cares about the angle asks for [RotatedImage].
     */
    sealed interface Pictured : DrawCall {
        val texture: TextureHandle
        val destination: Rect
        val tint: Colour

        /** The part of the texture drawn, in texture pixels. Null is all of it. */
        val source: Rect?
    }

    data class Image(
        override val texture: TextureHandle,
        override val destination: Rect,
        override val tint: Colour,
        override val source: Rect?,
        override val clip: Rect,
        override val alpha: Float,
    ) : Pictured

    /**
     * A picture that was asked for at an angle.
     *
     * Its own kind rather than two more fields on [Image], because [Image] is published and adding
     * a parameter to a data class replaces its constructor — source-compatible, binary-broken. A
     * new kind costs nothing to anyone who does not mention it.
     *
     * A turn of zero is recorded as a plain [Image], so a widget handing over a variable that
     * happens to be zero records exactly what it always did.
     *
     * [destination] is the box before turning and [pivotX], [pivotY] are fractions of it; see
     * [UiCanvas.image].
     */
    data class RotatedImage(
        override val texture: TextureHandle,
        override val destination: Rect,
        val degrees: Float,
        val pivotX: Float,
        val pivotY: Float,
        override val tint: Colour,
        override val source: Rect?,
        override val clip: Rect,
        override val alpha: Float,
    ) : Pictured

    /**
     * A subtree that was drawn into an offscreen picture, and then drawn back — with a shader when
     * there was one.
     *
     * The calls the subtree made are recorded before this, in the order they were made, so a test
     * can assert both what a widget drew and what it was drawn through.
     */
    data class Layer(
        val bounds: Rect,
        val effect: ShaderEffect?,
        /** How far clockwise the picture was turned as it was put down. Zero unless asked. */
        val degrees: Float = 0f,
        /** Where it turned about, as a fraction of [bounds]. Meaningless when [degrees] is zero. */
        val pivotX: Float = 0.5f,
        val pivotY: Float = 0.5f,
        override val clip: Rect,
        override val alpha: Float,
        /**
         * The outline the picture was cut to as it was put down, x, y pairs in design coordinates,
         * or null when it was put down whole. See [UiCanvas.cutLayer].
         */
        val outline: List<Float>? = null,
        /** Whether the picture was put down with its left and right swapped. */
        val mirrorX: Boolean = false,
        /** Whether the picture was put down with its top and bottom swapped. */
        val mirrorY: Boolean = false,
    ) : DrawCall

    /**
     * A subtree drawn into an offscreen picture and put down on four corners — a slant, or a slant
     * and a turn together.
     *
     * Its own kind rather than more fields on [Layer], for the reason [RotatedImage] gives. The
     * calls the subtree made are recorded before this, as they are for a [Layer].
     *
     * [bounds] is the box before anything was done to it, and [corners] are where its top-left,
     * top-right, bottom-right and bottom-left ended up, in that order.
     */
    data class LayerOnto(
        val bounds: Rect,
        val corners: List<Offset>,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /**
     * A subtree drawn into an offscreen picture and put down through a transform in depth — a
     * `Modifier.rotate3d`, with any slant and flat turn on the same node folded in.
     *
     * [bounds] is the box before anything was done to it. [corners] are where its top-left,
     * top-right, bottom-right and bottom-left were seen on the screen, depth divided out, and
     * [depths] the w each of them had: more than one is further away than the picture's own plane,
     * less is nearer, and zero or less is at or behind the camera. The calls the subtree made are
     * recorded before this, as they are for a [Layer].
     */
    data class TiltedLayer(
        val bounds: Rect,
        val transform: Matrix4,
        val corners: List<Offset>,
        val depths: List<Float>,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** Recorded but not run: a recording canvas has no backend object to hand the block. */
    data class Raw(
        override val clip: Rect,
        override val alpha: Float,
        /**
         * The rectangle the block's origin was moved to, or null for plain
         * [UiCanvas.raw] against the layer's own origin.
         *
         * Written down rather than acted on, because the block never runs here. It is still the
         * useful half to assert: a widget drawing through the hatch is asserting that it pointed it
         * at itself, and getting that wrong is exactly the bug this records.
         */
        val destination: Rect? = null,
    ) : DrawCall
}

/**
 * A canvas that writes down what it was asked to draw instead of drawing it.
 *
 * This is how nearly everything in this toolkit is tested. A widget's job is to work out *what* to
 * draw and *where*, and that is a decision, not a picture — so it can be asserted directly, on any
 * machine, with no window, no OpenGL and no screenshot to eyeball. Only the backend that turns
 * these calls into pixels needs a GPU.
 *
 * It lives in the main source set on purpose: the tests that need it most are in the modules
 * downstream of this one.
 *
 * It is strict about balance. An unmatched `popClip` throws here rather than producing a slightly
 * wrong frame somewhere else, which is the failure it exists to prevent.
 */
class RecordingCanvas(bounds: Rect = Rect.of(0f, 0f, 1000f, 1000f)) : UiCanvas {

    private var state = CanvasState(bounds)
    private val recorded = mutableListOf<DrawCall>()
    private val recordedBlends = mutableListOf<BlendMode>()
    private val recordedTints = mutableListOf<Colour>()
    private val recordedScales = mutableListOf<Float>()
    private var drawing = false

    /** Everything drawn since the last [clear], in the order it was drawn. */
    val calls: List<DrawCall> get() = recorded

    /**
     * The blend mode each of [calls] was drawn under, same length and same order.
     *
     * Beside the calls rather than on them because [DrawCall] and its kinds are published data
     * classes, and a new constructor parameter on one of those is a binary break for everybody who
     * compiled against 0.1.0. They are filled in together, in one place, so they cannot drift.
     *
     * [blendOf] is usually the friendlier way in: [only] and [invisible] hand back filtered lists
     * with no index left to look a mode up by.
     */
    val blends: List<BlendMode> get() = recordedBlends

    /**
     * What [call] was drawn under.
     *
     * Matched by identity, not by value — two rectangles of the same colour in the same place are
     * two calls, and they may well have been drawn under different modes. So this only answers for
     * a call that came out of this canvas; anything else is [BlendMode.SourceOver] by default and
     * would be a question about a call that was never made.
     */
    fun blendOf(call: DrawCall): BlendMode {
        val at = recorded.indexOfFirst { it === call }
        return if (at < 0) BlendMode.SourceOver else recordedBlends[at]
    }

    /**
     * The tint [call] was drawn under: the opaque colour a real backend multiplies its colours by,
     * or white for none. Matched by identity, like [blendOf], and beside the calls for the same
     * reason.
     *
     * A [DrawCall.Layer] always answers white. The tint went into the picture while it was being
     * drawn — see [UiCanvas.pushTint] — and a backend does not multiply the picture by it a second
     * time, so a test asking about the composite would be asking about a multiply that never
     * happens.
     */
    fun tintOf(call: DrawCall): Colour {
        val at = recorded.indexOfFirst { it === call }
        return if (at < 0) Colour.White else recordedTints[at]
    }

    /**
     * How much [call] was grown by a [pushTransform] in force when it was drawn: one for none.
     * Matched by identity, like [blendOf], and beside the calls for the same reason.
     *
     * Every position and size a call records is already where the transform put it — the rectangle
     * on the screen, the point a run of text starts at — so a test compares them with
     * `boundsInRoot` directly. This is for what a position cannot say: how big the letters of a
     * [DrawCall.Text] were drawn.
     */
    fun scaleOf(call: DrawCall): Float {
        val at = recorded.indexOfFirst { it === call }
        return if (at < 0) 1f else recordedScales[at]
    }

    /** Everything drawn under one mode. The quick way to ask "did this group glow?". */
    fun calls(mode: BlendMode): List<DrawCall> =
        recorded.filterIndexed { at, _ -> recordedBlends[at] == mode }

    /** The one place a call and what it was drawn under are written down, so they stay in step. */
    private fun record(call: DrawCall) {
        recorded += call
        recordedBlends += state.blend
        recordedTints += if (call is DrawCall.Layer) Colour.White else state.tint
        recordedScales += state.transformScale
    }

    /** A thickness as it is drawn. */
    private fun length(value: Float) = state.mapLength(value)

    /** Corners as they are drawn. */
    private fun Corners.drawn(): Corners =
        if (state.transformScale == 1f) this
        else Corners(length(topLeft), length(topRight), length(bottomRight), length(bottomLeft))

    /** x, y pairs as they are drawn: the same array when nothing is transformed. */
    private fun FloatArray.drawn(): FloatArray {
        if (!state.isTransformed) return this
        return FloatArray(size) { if (it % 2 == 0) state.mapX(this[it]) else state.mapY(this[it]) }
    }

    /**
     * How many frames have been opened and closed *cleanly* since this canvas was made.
     *
     * The answer to "did my app object actually render?", with no GPU in the question. A game
     * object that holds a canvas and a [dev.wildware.composegl.ui.host.UiRenderer] can be built in
     * a plain test, given this canvas, and asked whether a frame came out the other end.
     *
     * A frame whose [end] threw does not count. It reached [end] but it did not come out the other
     * end — on a real backend that is a scissor left switched on — and a test that caught the
     * failure and then read a number saying the frame rendered would be reading a lie.
     *
     * Counted from when the canvas was made, not from the last [clear]: [clear] throws away what
     * was drawn, and a count of frames that reset with it would be a count of one.
     */
    var frames: Int = 0
        private set

    /**
     * Ready to record another frame.
     *
     * Closes any frame still open, because a test usually gets here *because* a frame failed
     * halfway through: assert the failure, clear, render again. Leaving the frame open would meet
     * that next render with "begin() was called twice without an end()", which names the wrong
     * bug entirely. The unfinished frame is not counted in [frames].
     */
    fun clear(bounds: Rect = Rect.of(0f, 0f, 1000f, 1000f)) {
        recorded.clear()
        recordedBlends.clear()
        recordedTints.clear()
        recordedScales.clear()
        drawing = false
        state.reset(bounds)
    }

    /**
     * Opens a frame, as strictly as a canvas that draws does.
     *
     * [UiCanvas] leaves these two doing nothing, for a canvas with no frame to speak of. Taking
     * that default would make the canvas every test in this toolkit uses the most forgiving one in
     * it: a class that ended a frame it never began, or began two in a row, would pass here and
     * fail on a screen. Same invariants, same words, so a test finds the mistake first — which is
     * the argument [layer] already makes for matching a real backend's clip and opacity.
     *
     * The clip is reset to [viewport]'s design rectangle, exactly as `GdxCanvas` and `GlCanvas`
     * reset theirs, replacing whatever bounds this canvas was made with. A frame drawn through a
     * viewport can reach the design area and nothing else, and a test asserting against a wider
     * clip than a real backend would have allowed is a test asserting about a screen that does not
     * exist.
     */
    override fun begin(viewport: Viewport) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        state.reset(Rect.of(0f, 0f, viewport.design.width, viewport.design.height))
    }

    /** Closes the frame and counts it. What was drawn is kept; [clear] is how a test starts over. */
    override fun end() {
        check(drawing) { "end() without a begin()" }
        drawing = false

        // The frame is marked closed before this complains, so the next one can begin — the same
        // order the canvases that draw use. The count comes after it, because a frame that failed
        // its balance check is not a frame that rendered; see [frames].
        check(state.isBalanced) { Unbalanced }
        frames++
    }

    /**
     * Fails unless every clip, alpha and blend mode pushed was popped.
     *
     * Worth calling at the end of any test that draws a tree: an imbalance means a widget leaked
     * state onto whatever is drawn after it, and on a real backend that is a scissor left switched
     * on rather than an exception.
     */
    fun assertBalanced() {
        check(state.isBalanced) { Unbalanced }
    }

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        record(DrawCall.Rectangle(state.map(rect), colour, length(corner), state.clip, state.alpha))
    }

    override fun rect(rect: Rect, brush: Brush, corner: Float) {
        record(DrawCall.GradientRectangle(state.map(rect), brush, length(corner), state.clip, state.alpha))
    }

    /** It writes the brush down, which is the whole of what this canvas can do about anything. */
    override val drawsGradients: Boolean get() = true

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        record(DrawCall.Border(state.map(rect), colour, length(width), length(corner), state.clip, state.alpha))
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        record(DrawCall.Shadow(state.map(rect), colour, length(spread), length(corner), state.clip, state.alpha))
    }

    /**
     * Four equal corners record a plain [DrawCall.Rectangle], exactly as the single-radius call
     * would; only corners that differ record a [DrawCall.CorneredRectangle].
     */
    override fun rect(rect: Rect, colour: Colour, corners: Corners) {
        if (corners.isUniform) return rect(rect, colour, corners.topLeft)
        record(DrawCall.CorneredRectangle(state.map(rect), colour, corners.drawn(), state.clip, state.alpha))
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        if (corners.isUniform) return border(rect, colour, width, corners.topLeft)
        record(DrawCall.CorneredBorder(state.map(rect), colour, length(width), corners.drawn(), state.clip, state.alpha))
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corners: Corners) {
        if (corners.isUniform) return shadow(rect, colour, spread, corners.topLeft)
        record(DrawCall.CorneredShadow(state.map(rect), colour, length(spread), corners.drawn(), state.clip, state.alpha))
    }

    override fun rect(rect: Rect, brush: Brush, corners: Corners) {
        if (corners.isUniform) return rect(rect, brush, corners.topLeft)
        record(DrawCall.CorneredGradientRectangle(state.map(rect), brush, corners.drawn(), state.clip, state.alpha))
    }

    /** It writes all four down, which is the whole of what this canvas can do about anything. */
    override val roundsCornersSeparately: Boolean get() = true

    override fun borderOutside(rect: Rect, colour: Colour, width: Float, corner: Float) =
        borderOutside(rect, colour, width, Corners.all(corner))

    override fun borderOutside(rect: Rect, colour: Colour, width: Float, corners: Corners) {
        record(DrawCall.OutsideBorder(state.map(rect), colour, length(width), corners.drawn(), state.clip, state.alpha))
    }

    override fun innerShade(rect: Rect, colour: Colour, depth: Float, corner: Float, offsetX: Float, offsetY: Float, hardness: Float) =
        innerShade(rect, colour, depth, Corners.all(corner), offsetX, offsetY, hardness)

    override fun innerShade(rect: Rect, colour: Colour, depth: Float, corners: Corners, offsetX: Float, offsetY: Float, hardness: Float) {
        record(
            DrawCall.InnerShade(
                state.map(rect), colour, length(depth), corners.drawn(),
                Offset(length(offsetX), length(offsetY)), hardness, state.clip, state.alpha,
            ),
        )
    }

    /** It writes both down, which is the whole of what this canvas can do about anything. */
    override val shadesInside: Boolean get() = true

    override fun fan(points: FloatArray, colour: Colour) {
        if (points.size < 6) return
        val offsets = (points.indices step 2).map { Offset(state.mapX(points[it]), state.mapY(points[it + 1])) }
        record(DrawCall.Fan(offsets, colour, state.clip, state.alpha))
    }

    override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) {
        record(DrawCall.Text(layout.text, Offset(state.mapX(x), state.mapY(y)), colour, state.clip, state.alpha))
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        // Every canvas that draws refuses nine separately-cut pieces, so this one does too. A test
        // that recorded them would be passing against art no real backend would put on a screen.
        refuseNineRegions(texture)
        record(DrawCall.Image(texture, state.map(destination), tint, source, state.clip, state.alpha))
    }

    /**
     * A turn of zero records a plain [DrawCall.Image], exactly as the upright call would.
     *
     * So a widget that passes an angle which happens to be zero — a card that is only tilted while
     * it is being dragged — records what it has always recorded, and a test written before this
     * existed keeps passing.
     */
    @Suppress("LongParameterList")
    override fun image(
        texture: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
        tint: Colour,
        source: Rect?,
    ) {
        refuseNineRegions(texture)
        if (degrees == 0f) {
            record(DrawCall.Image(texture, state.map(destination), tint, source, state.clip, state.alpha))
            return
        }
        record(
            DrawCall.RotatedImage(
                texture, state.map(destination), degrees, pivotX, pivotY, tint, source, state.clip, state.alpha,
            ),
        )
    }

    /** It writes the angle down, which is the whole of what this canvas can do about anything. */
    override val rotatesImages: Boolean get() = true

    /** It writes them down, which is the whole of what this canvas can do about anything. */
    override val drawsLayers: Boolean get() = true

    /**
     * Runs [block] and hands back a picture that only exists as a name.
     *
     * A recording canvas has no pixels, so a layer here is a marker: the subtree's calls are
     * recorded as they happen, with the clip narrowed to the layer the way a real backend narrows
     * it, and a [DrawCall.Layer] follows when the picture is drawn back.
     */
    override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
        // A real backend gives the block a clip of exactly the layer, full opacity and plain
        // source-over blending, so this one does too — otherwise a test would pass against a
        // canvas that behaves differently from every canvas that draws. The tint comes in with it,
        // as it does on those.
        val outer = state
        state = outer.forLayer(outer.map(bounds))
        try {
            block()
            check(state.isBalanced) { UnbalancedInLayer }
        } finally {
            state = outer
        }
        return LayerHandle(bounds)
    }

    override fun drawLayer(layer: TextureHandle, destination: Rect, effect: ShaderEffect?) {
        record(DrawCall.Layer(state.map(destination), effect, clip = state.clip, alpha = state.alpha))
    }

    override fun drawLayer(
        layer: TextureHandle,
        destination: Rect,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
    ) {
        record(
            DrawCall.Layer(state.map(destination), null, degrees, pivotX, pivotY, state.clip, state.alpha),
        )
    }

    /** It records the angle, so it really turns one. */
    override val turnsLayers: Boolean get() = true

    override fun cutLayer(layer: TextureHandle, destination: Rect, outline: FloatArray) {
        record(DrawCall.Layer(state.map(destination), null, clip = state.clip, alpha = state.alpha, outline = outline.drawn().toList()))
    }

    /** It records the outline, so it really cuts one. */
    override val cutsLayers: Boolean get() = true

    override fun drawLayer(layer: TextureHandle, destination: Rect, mirrorX: Boolean, mirrorY: Boolean) {
        record(
            DrawCall.Layer(
                state.map(destination), null, clip = state.clip, alpha = state.alpha, mirrorX = mirrorX, mirrorY = mirrorY,
            ),
        )
    }

    /** It records which way round, so it really mirrors one. */
    override val mirrorsLayers: Boolean get() = true

    override fun drawLayerOnto(layer: TextureHandle, destination: Rect, corners: FloatArray) {
        require(corners.size == 8) { "four corners are eight numbers, not ${corners.size}" }
        record(
            DrawCall.LayerOnto(
                state.map(destination),
                List(4) { Offset(state.mapX(corners[it * 2]), state.mapY(corners[it * 2 + 1])) },
                state.clip,
                state.alpha,
            ),
        )
    }

    /** It records the corners, so it really puts one on them. */
    override val drawsLayersOnto: Boolean get() = true

    override fun drawLayer(layer: TextureHandle, destination: Rect, transform: Matrix4) {
        val xs = floatArrayOf(destination.left, destination.right, destination.right, destination.left)
        val ys = floatArrayOf(destination.top, destination.top, destination.bottom, destination.bottom)
        // The camera's transform, after the node's own: one matrix, the way a canvas that draws uses it.
        val seen = if (!state.isTransformed) transform else Matrix4.zoom(state.transformScale, state.transformX, state.transformY) * transform
        record(
            DrawCall.TiltedLayer(
                // Before anything, the camera included: the corners are this through [seen].
                destination,
                seen,
                List(4) { seen.map(xs[it], ys[it]) },
                List(4) { seen.depthOf(xs[it], ys[it]) },
                state.clip,
                state.alpha,
            ),
        )
    }

    /** It records the transform, so it really tilts one. */
    override val tiltsLayers: Boolean get() = true

    /** A picture with nothing in it: there are no pixels here to be a handle to. */
    private class LayerHandle(bounds: Rect) : TextureHandle {
        override val width: Int = bounds.width.toInt()
        override val height: Int = bounds.height.toInt()
    }

    override fun pushClip(rect: Rect) = state.pushClip(rect)

    override fun popClip() = state.popClip()

    override fun pushAlpha(alpha: Float) = state.pushAlpha(alpha)

    override fun popAlpha() = state.popAlpha()

    override fun pushBlend(mode: BlendMode) = state.pushBlend(mode)

    override fun popBlend() = state.popBlend()

    override fun pushTint(tint: Colour) = state.pushTint(tint)

    override fun popTint() = state.popTint()

    override fun pushTransform(scale: Float, translateX: Float, translateY: Float) =
        state.pushTransform(scale, translateX, translateY)

    override fun pushTransform(scale: Float, translateX: Float, translateY: Float, textScale: Float) =
        state.pushTransform(scale, translateX, translateY, textScale)

    override fun popTransform() = state.popTransform()

    /** It moves every position it writes down, which is what a transform is. */
    override val transforms: Boolean get() = true

    /** The scale glyphs would be made for now, for a test of a canvas passing one. */
    val textScale: Float get() = state.textScale

    /** It writes the tint down, which is the whole of what this canvas can do about anything. */
    override val tints: Boolean get() = true

    /** Every mode, because writing one down costs the same as writing another one down. */
    override fun supports(mode: BlendMode): Boolean = true

    /**
     * No, and saying so is the point.
     *
     * A recording canvas writes the call down and never runs the block, so a widget that asks
     * before it draws takes its composed fallback here — which is what a test of that fallback
     * needs. A widget that does not ask records a [DrawCall.Raw] and draws nothing, which is the
     * same thing a real canvas with no backend object would do, minus the throw.
     */
    override val handsOverRaw: Boolean get() = false

    override fun raw(block: (Any) -> Unit) {
        record(DrawCall.Raw(state.clip, state.alpha))
    }

    override fun raw(destination: Rect, block: (Any) -> Unit) {
        record(DrawCall.Raw(state.clip, state.alpha, state.map(destination)))
    }

    private val recordedScenes = mutableListOf<RecordedScene>()

    /**
     * Every [scene] this canvas rendered, in order, since it was made.
     *
     * Not emptied by [clear], unlike [calls]: a scene is rendered in the prepass of one frame and
     * then drawn as a picture for many, and a test counting redraws across frames wants them all.
     */
    val scenes: List<RecordedScene> get() = recordedScenes

    /** Yes: it makes a picture of the size asked for and writes down what the block did to it. */
    override val drawsScenes: Boolean get() = true

    /** The biggest picture this canvas pretends a device will make. Set it to test a small GPU. */
    override var maxSceneSize: Int = Int.MAX_VALUE

    /**
     * Runs [draw] against a target that writes its clears and hands-over down, and returns a
     * picture of the size asked for — the same one when the size has not changed. The block handed
     * to [SceneTarget.raw] is not run, for the reason [handsOverRaw] gives.
     */
    override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? {
        check(!drawing) {
            "scene() inside a frame: scenes are rendered before the frame begins. " +
                "A WorldPanel with a SceneView in it opens its frame itself: panel.draw(canvas) { tree -> target.draw(canvas) { tree() } }"
        }
        if (width <= 0 || height <= 0) return surface
        val reused = (surface as? RecordedSceneSurface)?.takeIf { !it.closed && it.width == width && it.height == height }
        val picture = reused ?: RecordedSceneSurface(width, height)
        val scene = RecordedScene(picture, width, height, allocated = reused == null)
        recordedScenes += scene
        draw(scene.target)
        return picture
    }

    /** Only the calls of one kind, which is what an assertion usually wants. */
    inline fun <reified T : DrawCall> only(): List<T> = calls.filterIsInstance<T>()

    /** The text that was drawn, in order. The quickest way to ask "does this say the right thing". */
    fun texts(): List<String> = only<DrawCall.Text>().map { it.text }

    /** Calls that would have put nothing on screen, because their clip had no area. */
    fun invisible(): List<DrawCall> = calls.filter { it.clip.isEmpty || it.alpha <= 0f }

    /** A readable dump, so a failing assertion says what actually happened. */
    override fun toString(): String =
        if (recorded.isEmpty()) "RecordingCanvas(nothing drawn)"
        else recorded.indices.joinToString(separator = "\n", prefix = "RecordingCanvas:\n") { at ->
            val mode = recordedBlends[at]
            val tint = recordedTints[at]
            val marks = (if (mode == BlendMode.SourceOver) "" else "[$mode] ") +
                (if (tint == Colour.White) "" else "[tint $tint] ")
            "  $marks${recorded[at]}"
        }

    private companion object {
        const val Unbalanced = "a clip, an alpha, a blend or a tint was pushed and never popped"
        const val UnbalancedInLayer = "a clip, an alpha, a blend or a tint was pushed inside a layer and never popped"
    }
}

/** A picture [RecordingCanvas.scene] made: a size and nothing else. */
class RecordedSceneSurface(override val width: Int, override val height: Int) : SceneSurface {

    override var closed: Boolean = false
        private set

    override fun close() {
        closed = true
    }

    override fun toString(): String = "RecordedSceneSurface(${width}x$height${if (closed) ", closed" else ""})"
}

/** One [RecordingCanvas.scene]: the picture, its size, whether it was new, and what was done to it. */
class RecordedScene(
    val surface: RecordedSceneSurface,
    val width: Int,
    val height: Int,
    /** True when the picture was made for this render rather than filled again. */
    val allocated: Boolean,
) {
    private val recordedClears = mutableListOf<Colour>()

    /** The colours the scene was cleared to, in order. */
    val clears: List<Colour> get() = recordedClears

    /** How many times the scene asked for the backend's drawing object. */
    var raws: Int = 0
        private set

    internal val target = object : SceneTarget {
        override val width: Int get() = this@RecordedScene.width
        override val height: Int get() = this@RecordedScene.height

        override fun clear(colour: Colour) {
            recordedClears += colour
        }

        override fun raw(block: (Any) -> Unit) {
            raws++
        }
    }

    override fun toString(): String = "RecordedScene(${width}x$height, allocated=$allocated, clears=$clears, raws=$raws)"
}
