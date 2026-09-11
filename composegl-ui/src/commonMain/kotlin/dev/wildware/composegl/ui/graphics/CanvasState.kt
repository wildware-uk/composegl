package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect

/**
 * The clip and opacity currently in force, as a stack.
 *
 * Every backend needs exactly this and would otherwise get it subtly wrong in the same two ways:
 * forgetting that a nested clip is the *intersection* of the two rather than the inner one, and
 * forgetting that a nested opacity multiplies. So it is written once, here, and tested.
 *
 * Not an interface and not inherited — a backend holds one.
 */
class CanvasState(bounds: Rect) {

    private val clips = ArrayDeque<Rect>().apply { addLast(bounds) }
    private val alphas = ArrayDeque<Float>().apply { addLast(1f) }

    /** The area anything drawn now is allowed to touch. Can be empty, meaning "draw nothing". */
    val clip: Rect get() = clips.last()

    /** The opacity anything drawn now is multiplied by. */
    val alpha: Float get() = alphas.last()

    /** True when the current clip has no area, so drawing can be skipped entirely. */
    val isHidden: Boolean get() = clip.isEmpty || alpha <= 0f

    fun reset(bounds: Rect) {
        clips.clear(); clips.addLast(bounds)
        alphas.clear(); alphas.addLast(1f)
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

    /** Both stacks back at the bottom. Checked at the end of a frame; an imbalance is a bug. */
    val isBalanced: Boolean get() = clips.size == 1 && alphas.size == 1
}
