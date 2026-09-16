package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect

/**
 * The clip, opacity, blend mode, tint and transform currently in force, as a stack.
 *
 * Every backend needs exactly this and would otherwise get it subtly wrong in the same few ways:
 * forgetting that a nested clip is the *intersection* of the two rather than the inner one,
 * forgetting that a nested opacity multiplies, forgetting that a nested blend mode does neither —
 * it simply replaces — and forgetting that a layer keeps the tint it was made under. So it is
 * written once, here, and tested.
 *
 * Not an interface and not inherited — a backend holds one.
 */
class CanvasState(bounds: Rect) {

    private val clips = ArrayDeque<Rect>().apply { addLast(bounds) }
    private val alphas = ArrayDeque<Float>().apply { addLast(1f) }
    private val modes = ArrayDeque<BlendMode>().apply { addLast(BlendMode.SourceOver) }
    private val tints = ArrayDeque<Colour>().apply { addLast(Colour.White) }

    /** The area anything drawn now is allowed to touch. Can be empty, meaning "draw nothing". */
    val clip: Rect get() = clips.last()

    /** The opacity anything drawn now is multiplied by. */
    val alpha: Float get() = alphas.last()

    /** How anything drawn now is combined with what is already there. */
    val blend: BlendMode get() = modes.last()

    /**
     * The opaque colour every colour drawn now is multiplied by, channel by channel. White, which
     * changes nothing, for almost every call there has ever been.
     */
    val tint: Colour get() = tints.last()

    /**
     * How much anything drawn now is grown by, and where its origin lands: a point at (x, y) is
     * drawn at ([mapX] x, [mapY] y). One and nothing for almost every call there has ever been.
     *
     * The [clip] is not in these coordinates. It is kept where it lands — the frame's, or the
     * layer's — which is what a scissor wants, so [pushClip] maps the rectangle it is handed first.
     */
    var transformScale: Float = 1f
        private set

    var transformX: Float = 0f
        private set

    var transformY: Float = 0f
        private set

    /** The scale glyphs should be made for, which can lag behind [transformScale]. See [UiCanvas.pushTransform]. */
    var textScale: Float = 1f
        private set

    /** Whether anything drawn now is moved or grown at all. */
    val isTransformed: Boolean get() = transformScale != 1f || transformX != 0f || transformY != 0f

    // Four floats a level — scale, x, y and text scale — rather than a stack of objects, because a
    // pan-and-zoom canvas pushes one per child per frame.
    private var transforms = FloatArray(16)
    private var transformDepth = 0

    /** An x as it is drawn. */
    fun mapX(x: Float): Float = x * transformScale + transformX

    /** A y as it is drawn. */
    fun mapY(y: Float): Float = y * transformScale + transformY

    /** A rectangle as it is drawn: [rect] itself when nothing is transformed. */
    fun map(rect: Rect): Rect =
        if (!isTransformed) rect else Rect(mapX(rect.left), mapY(rect.top), mapX(rect.right), mapY(rect.bottom))

    /** A thickness — a border, a radius, a spread — as it is drawn. */
    fun mapLength(length: Float): Float = length * transformScale

    /** Composes: the new transform is applied first, then everything already in force. */
    fun pushTransform(scale: Float, translateX: Float, translateY: Float, text: Float = scale) {
        if (transformDepth * 4 + 4 > transforms.size) transforms = transforms.copyOf(transforms.size * 2)
        val at = transformDepth * 4
        transforms[at] = transformScale
        transforms[at + 1] = transformX
        transforms[at + 2] = transformY
        transforms[at + 3] = textScale
        transformDepth++
        transformX += translateX * transformScale
        transformY += translateY * transformScale
        transformScale *= scale
        textScale *= text
    }

    fun popTransform() {
        check(transformDepth > 0) { "popTransform without a matching pushTransform" }
        transformDepth--
        val at = transformDepth * 4
        transformScale = transforms[at]
        transformX = transforms[at + 1]
        transformY = transforms[at + 2]
        textScale = transforms[at + 3]
    }

    /** True when the current clip has no area, so drawing can be skipped entirely. */
    val isHidden: Boolean get() = clip.isEmpty || alpha <= 0f

    fun reset(bounds: Rect) {
        clips.clear(); clips.addLast(bounds)
        alphas.clear(); alphas.addLast(1f)
        modes.clear(); modes.addLast(BlendMode.SourceOver)
        tints.clear(); tints.addLast(Colour.White)
        transformDepth = 0
        transformScale = 1f
        transformX = 0f
        transformY = 0f
        textScale = 1f
    }

    /**
     * The state a layer the size of [bounds] starts drawing with.
     *
     * A fresh clip, full opacity and plain blending, for the reasons [UiCanvas.layer] gives — but
     * the tint in force out here, carried in as the bottom of the new stack. A multiply by a colour
     * comes out the same whether it happens to each part or to the finished picture, so doing it
     * inside means a picture drawn back needs nothing more, and a shader effect is handed a picture
     * that is already tinted instead of one that never can be.
     *
     * The transform in force comes in too, for the same kind of reason: [bounds] is where the layer
     * lands, and what is drawn inside it still has to land in the same place and at the same size,
     * so the picture is taken at the screen's resolution of what is really there.
     */
    fun forLayer(bounds: Rect): CanvasState = CanvasState(bounds).also { inner ->
        inner.tints.clear()
        inner.tints.addLast(tint)
        inner.transformScale = transformScale
        inner.transformX = transformX
        inner.transformY = transformY
        inner.textScale = textScale
    }

    /** [rect] is in the coordinates being drawn in, and is kept as it lands. */
    fun pushClip(rect: Rect) {
        clips.addLast(clip.intersect(map(rect)))
    }

    fun popClip() {
        check(clips.size > 1) { "popClip without a matching pushClip" }
        clips.removeLast()
    }

    fun pushAlpha(value: Float) {
        alphas.addLast(alpha * value.coerceIn(0f, 1f))
    }

    fun popAlpha() {
        check(alphas.size > 1) { "popAlpha without a matching pushAlpha" }
        alphas.removeLast()
    }

    /**
     * The innermost mode wins outright.
     *
     * Unlike a clip and an opacity there is nothing to combine: "add light" inside "cover what is
     * behind" is just "add light", and multiplying or intersecting two of these would mean
     * inventing a third mode nobody asked for.
     */
    fun pushBlend(mode: BlendMode) {
        modes.addLast(mode)
    }

    fun popBlend() {
        check(modes.size > 1) { "popBlend without a matching pushBlend" }
        modes.removeLast()
    }

    /**
     * Multiplies, like an opacity: a red team colour inside a grey locked slot is both, darker and
     * redder, rather than whichever was pushed last. [value]'s alpha is how much of it applies —
     * see [Colour.asTint].
     */
    fun pushTint(value: Colour) {
        tints.addLast(tint.modulate(value.asTint()))
    }

    fun popTint() {
        check(tints.size > 1) { "popTint without a matching pushTint" }
        tints.removeLast()
    }

    /** Every stack back at the bottom. Checked at the end of a frame; an imbalance is a bug. */
    val isBalanced: Boolean
        get() = clips.size == 1 && alphas.size == 1 && modes.size == 1 && tints.size == 1 && transformDepth == 0
}
