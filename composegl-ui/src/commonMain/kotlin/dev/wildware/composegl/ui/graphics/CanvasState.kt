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
 * Not an interface and not inherited — a backend holds one. A canvas that draws every frame keeps
 * one for the frame and one per layer depth, and [reset]s them or fills them with [forLayer] rather
 * than making new ones (#252), so every stack here is plain numbers: a push or a pop makes nothing.
 */
class CanvasState(bounds: Rect) {

    // Four floats a level — left, top, right, bottom — rather than a stack of rectangles, because a
    // frame pushes a clip for every clipped node and every scroll area in it.
    private var clips = FloatArray(4 * StartingRoom)

    /** Each level's clip as a [Rect], made the first time it is asked for and kept until it changes. */
    private var clipRects = arrayOfNulls<Rect>(StartingRoom)
    private var clipDepth = 0

    private var alphas = FloatArray(StartingRoom)
    private var alphaDepth = 0

    private var modes = Array(StartingRoom) { BlendMode.SourceOver }
    private var modeDepth = 0

    /** Each level's tint as its [Colour.argb], so a push boxes nothing. */
    private var tints = IntArray(StartingRoom)
    private var tintDepth = 0

    /**
     * The area anything drawn now is allowed to touch. Can be empty, meaning "draw nothing".
     *
     * Made the first time it is asked for after a push and kept for that level, so asking twice is
     * one rectangle. A canvas that only needs the edges reads [clipLeft], [clipTop], [clipRight]
     * and [clipBottom], which make nothing.
     */
    val clip: Rect
        get() = clipRects[clipDepth] ?: Rect(clipLeft, clipTop, clipRight, clipBottom).also { clipRects[clipDepth] = it }

    /** The left edge of [clip]. */
    val clipLeft: Float get() = clips[clipDepth * 4]

    /** The top edge of [clip]. */
    val clipTop: Float get() = clips[clipDepth * 4 + 1]

    /** The right edge of [clip]. */
    val clipRight: Float get() = clips[clipDepth * 4 + 2]

    /** The bottom edge of [clip]. */
    val clipBottom: Float get() = clips[clipDepth * 4 + 3]

    /** The opacity anything drawn now is multiplied by. */
    val alpha: Float get() = alphas[alphaDepth]

    /** How anything drawn now is combined with what is already there. */
    val blend: BlendMode get() = modes[modeDepth]

    /**
     * The opaque colour every colour drawn now is multiplied by, channel by channel. White, which
     * changes nothing, for almost every call there has ever been.
     */
    val tint: Colour get() = Colour(tints[tintDepth])

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

    init {
        reset(bounds)
    }

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
    val isHidden: Boolean get() = clipRight <= clipLeft || clipBottom <= clipTop || alpha <= 0f

    /** Every stack back to one level: a clip of [bounds], and nothing else in force. */
    fun reset(bounds: Rect) {
        reset(bounds.left, bounds.top, bounds.right, bounds.bottom)
        // The rectangle handed in is the clip, so asking for it makes nothing.
        clipRects[0] = bounds
    }

    /** The same, with the clip given as its edges: what a frame's `begin` calls, so it makes nothing. */
    fun reset(left: Float, top: Float, right: Float, bottom: Float) {
        clipDepth = 0
        clips[0] = left
        clips[1] = top
        clips[2] = right
        clips[3] = bottom
        clipRects[0] = null
        alphaDepth = 0
        alphas[0] = 1f
        modeDepth = 0
        modes[0] = BlendMode.SourceOver
        tintDepth = 0
        tints[0] = Colour.White.argb
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
    fun forLayer(bounds: Rect): CanvasState = forLayer(bounds, CanvasState(bounds))

    /**
     * The same, written into [into] — whatever it held before — rather than into a new state, so a
     * canvas that keeps one state per layer depth makes nothing for a layer. Returns [into].
     */
    fun forLayer(bounds: Rect, into: CanvasState): CanvasState {
        require(into !== this) { "a state cannot be the layer of itself" }
        into.reset(bounds)
        into.tints[0] = tints[tintDepth]
        into.transformScale = transformScale
        into.transformX = transformX
        into.transformY = transformY
        into.textScale = textScale
        return into
    }

    /** [rect] is in the coordinates being drawn in, and is kept as it lands. */
    fun pushClip(rect: Rect) {
        // Through the transform, as [map] does, but without a rectangle for the answer.
        val transformed = isTransformed
        val left = if (transformed) mapX(rect.left) else rect.left
        val top = if (transformed) mapY(rect.top) else rect.top
        val right = if (transformed) mapX(rect.right) else rect.right
        val bottom = if (transformed) mapY(rect.bottom) else rect.bottom
        // Cut to the clip in force, as [Rect.intersect] does.
        val at = clipDepth * 4
        val newLeft = maxOf(clips[at], left)
        val newTop = maxOf(clips[at + 1], top)
        val newRight = minOf(clips[at + 2], right)
        val newBottom = minOf(clips[at + 3], bottom)
        if (at + 8 > clips.size) clips = clips.copyOf(clips.size * 2)
        if (clipDepth + 2 > clipRects.size) clipRects = clipRects.copyOf(clipRects.size * 2)
        clipDepth++
        clips[at + 4] = newLeft
        clips[at + 5] = newTop
        clips[at + 6] = newRight
        clips[at + 7] = newBottom
        clipRects[clipDepth] = null
    }

    fun popClip() {
        check(clipDepth > 0) { "popClip without a matching pushClip" }
        // Let go of the rectangle made for this level, if one was, rather than keep it alive.
        clipRects[clipDepth] = null
        clipDepth--
    }

    fun pushAlpha(value: Float) {
        val next = alpha * value.coerceIn(0f, 1f)
        if (alphaDepth + 2 > alphas.size) alphas = alphas.copyOf(alphas.size * 2)
        alphas[++alphaDepth] = next
    }

    fun popAlpha() {
        check(alphaDepth > 0) { "popAlpha without a matching pushAlpha" }
        alphaDepth--
    }

    /**
     * The innermost mode wins outright.
     *
     * Unlike a clip and an opacity there is nothing to combine: "add light" inside "cover what is
     * behind" is just "add light", and multiplying or intersecting two of these would mean
     * inventing a third mode nobody asked for.
     */
    fun pushBlend(mode: BlendMode) {
        if (modeDepth + 2 > modes.size) {
            val old = modes
            modes = Array(old.size * 2) { if (it < old.size) old[it] else BlendMode.SourceOver }
        }
        modes[++modeDepth] = mode
    }

    fun popBlend() {
        check(modeDepth > 0) { "popBlend without a matching pushBlend" }
        modeDepth--
    }

    /**
     * Multiplies, like an opacity: a red team colour inside a grey locked slot is both, darker and
     * redder, rather than whichever was pushed last. [value]'s alpha is how much of it applies —
     * see [Colour.asTint].
     */
    fun pushTint(value: Colour) {
        val next = tint.modulate(value.asTint())
        if (tintDepth + 2 > tints.size) tints = tints.copyOf(tints.size * 2)
        tints[++tintDepth] = next.argb
    }

    fun popTint() {
        check(tintDepth > 0) { "popTint without a matching pushTint" }
        tintDepth--
    }

    /** Every stack back at the bottom. Checked at the end of a frame; an imbalance is a bug. */
    val isBalanced: Boolean
        get() = clipDepth == 0 && alphaDepth == 0 && modeDepth == 0 && tintDepth == 0 && transformDepth == 0

    private companion object {
        /** Levels each stack has room for before it grows: deeper than nearly any screen nests. */
        const val StartingRoom = 8
    }
}
