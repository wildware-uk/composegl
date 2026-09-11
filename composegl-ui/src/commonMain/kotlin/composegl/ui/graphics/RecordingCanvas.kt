package composegl.ui.graphics

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.text.TextLayout

/** One thing a [RecordingCanvas] was asked to draw, with the clip and opacity in force at the time. */
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

    data class Image(
        val texture: TextureHandle,
        val destination: Rect,
        val tint: Colour,
        /** The part of the texture drawn, in texture pixels. Null is all of it. */
        val source: Rect?,
        override val clip: Rect,
        override val alpha: Float,
    ) : DrawCall

    /** Recorded but not run: a recording canvas has no backend object to hand the block. */
    data class Raw(
        override val clip: Rect,
        override val alpha: Float,
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

    private val state = CanvasState(bounds)
    private val recorded = mutableListOf<DrawCall>()

    /** Everything drawn since the last [clear], in the order it was drawn. */
    val calls: List<DrawCall> get() = recorded

    /** Ready to record another frame. */
    fun clear(bounds: Rect = Rect.of(0f, 0f, 1000f, 1000f)) {
        recorded.clear()
        state.reset(bounds)
    }

    /**
     * Fails unless every clip and alpha pushed was popped.
     *
     * Worth calling at the end of any test that draws a tree: an imbalance means a widget leaked
     * state onto whatever is drawn after it, and on a real backend that is a scissor left switched
     * on rather than an exception.
     */
    fun assertBalanced() {
        check(state.isBalanced) { "a clip or an alpha was pushed and never popped" }
    }

    override fun rect(rect: Rect, colour: Colour, corner: Float) {
        recorded += DrawCall.Rectangle(rect, colour, corner, state.clip, state.alpha)
    }

    override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) {
        recorded += DrawCall.Border(rect, colour, width, corner, state.clip, state.alpha)
    }

    override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) {
        recorded += DrawCall.Shadow(rect, colour, spread, corner, state.clip, state.alpha)
    }

    override fun fan(points: FloatArray, colour: Colour) {
        if (points.size < 6) return
        val offsets = (points.indices step 2).map { Offset(points[it], points[it + 1]) }
        recorded += DrawCall.Fan(offsets, colour, state.clip, state.alpha)
    }

    override fun text(layout: TextLayout, at: Offset, colour: Colour) {
        recorded += DrawCall.Text(layout.text, at, colour, state.clip, state.alpha)
    }

    override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) {
        recorded += DrawCall.Image(texture, destination, tint, source, state.clip, state.alpha)
    }

    override fun pushClip(rect: Rect) = state.pushClip(rect)

    override fun popClip() = state.popClip()

    override fun pushAlpha(alpha: Float) = state.pushAlpha(alpha)

    override fun popAlpha() = state.popAlpha()

    override fun raw(block: (Any) -> Unit) {
        recorded += DrawCall.Raw(state.clip, state.alpha)
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
        else recorded.joinToString(separator = "\n", prefix = "RecordingCanvas:\n") { "  $it" }
}
