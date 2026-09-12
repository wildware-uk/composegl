package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect

/**
 * The clip, opacity and blend mode currently in force, as a stack.
 *
 * Every backend needs exactly this and would otherwise get it subtly wrong in the same three ways:
 * forgetting that a nested clip is the *intersection* of the two rather than the inner one,
 * forgetting that a nested opacity multiplies, and forgetting that a nested blend mode does
 * neither — it simply replaces. So it is written once, here, and tested.
 *
 * Not an interface and not inherited — a backend holds one.
 */
class CanvasState(bounds: Rect) {

    private val clips = ArrayDeque<Rect>().apply { addLast(bounds) }
    private val alphas = ArrayDeque<Float>().apply { addLast(1f) }
    private val modes = ArrayDeque<BlendMode>().apply { addLast(BlendMode.SourceOver) }

    /** The area anything drawn now is allowed to touch. Can be empty, meaning "draw nothing". */
    val clip: Rect get() = clips.last()

    /** The opacity anything drawn now is multiplied by. */
    val alpha: Float get() = alphas.last()

    /** How anything drawn now is combined with what is already there. */
    val blend: BlendMode get() = modes.last()

    /** True when the current clip has no area, so drawing can be skipped entirely. */
    val isHidden: Boolean get() = clip.isEmpty || alpha <= 0f

    fun reset(bounds: Rect) {
        clips.clear(); clips.addLast(bounds)
        alphas.clear(); alphas.addLast(1f)
        modes.clear(); modes.addLast(BlendMode.SourceOver)
    }

    fun pushClip(rect: Rect) {
        clips.addLast(clip.intersect(rect))
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

    /** Every stack back at the bottom. Checked at the end of a frame; an imbalance is a bug. */
    val isBalanced: Boolean get() = clips.size == 1 && alphas.size == 1 && modes.size == 1
}
