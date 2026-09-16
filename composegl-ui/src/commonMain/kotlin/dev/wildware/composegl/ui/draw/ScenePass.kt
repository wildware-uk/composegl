package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneViewState
import kotlin.math.ceil

/**
 * The prepass: renders every dirty `SceneView` on [tree] into its own picture, through [canvas].
 *
 * [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer] runs one of these for you, after layout
 * and before the frame begins, and that is what nearly every game wants. **Driving it yourself is
 * the exception**, for a game that has to put scene rendering between passes of its own — a shadow
 * map the preview shares, say:
 *
 * ```kotlin
 * renderer.renderScenes = false
 * renderer.onLaidOut = { _ ->
 *     shadows.render()
 *     renderer.scenes.render(viewport, nanos)
 * }
 * ```
 *
 * Two rules. It runs **after** layout, because a scene is rendered at its panel's size and that
 * size comes from layout; and **outside** the canvas's frame, because a scene binds a picture of
 * its own and a canvas refuses it in the middle of the interface's batch. Run it after the tree is
 * drawn instead and every new picture shows a frame late.
 *
 * A clean scene view costs a comparison. A tree with none costs one.
 */
class ScenePass(val tree: UiTree, val canvas: UiCanvas) {

    private val scope = SceneDrawScope()
    private var rendering: SceneViewState? = null

    /** Made once, so a frame that renders a scene allocates nothing for the call. */
    private val fill: (SceneTarget) -> Unit = { target ->
        scope.target = target
        scope.width = target.width
        scope.height = target.height
        try {
            checkNotNull(rendering).content(scope)
        } finally {
            scope.target = null
        }
    }

    /**
     * Renders every scene view that is dirty or whose pixel size changed, and returns how many it
     * rendered.
     *
     * @param viewport the one the tree was laid out in, for how many real pixels a design unit is.
     * @param nanos the frame's time, handed to each draw block as [SceneDrawScope.nanos].
     */
    fun render(viewport: Viewport, nanos: Long): Int {
        val nodes = tree.scenes
        if (nodes.isEmpty()) return 0
        scope.nanos = nanos
        var rendered = 0
        var at = 0
        while (at < nodes.size) {
            val node = nodes[at++]
            val state = node.sceneView ?: continue
            if (render(node, state, viewport)) rendered++
        }
        return rendered
    }

    private fun render(node: UiNode, state: SceneViewState, viewport: Viewport): Boolean {
        if (!node.everMeasured) return false
        val padding = node.resolved.padding
        val grown = node.scaleInRoot * state.resolutionScale
        val width = pixels((node.width - padding.left - padding.right) * grown * viewport.scaleX)
        val height = pixels((node.height - padding.top - padding.bottom - node.baselineTop - node.baselineBottom) * grown * viewport.scaleY)
        // Kept for input, which needs the scale before there is a picture to measure it by.
        state.pixelsPerUnitX = grown * viewport.scaleX
        state.pixelsPerUnitY = grown * viewport.scaleY
        // No room: nothing to draw into, and still wanted for when there is.
        if (width <= 0 || height <= 0) return false

        val current = state.surface?.takeUnless { it.closed }
        val resized = current == null || width != state.askedWidth || height != state.askedHeight
        if (!state.dirty && !resized) return false

        rendering = state
        val made = try {
            canvas.scene(current, width, height, fill)
        } finally {
            rendering = null
        } ?: return false

        if (current != null && made !== current) current.close()
        state.surface = made
        state.askedWidth = width
        state.askedHeight = height
        state.width = made.width
        state.height = made.height
        state.dirty = false
        state.draws++
        return true
    }

    private companion object {
        /**
         * Design units times a scale, as whole pixels. Rounded up, so a part pixel is not a row lost,
         * less a hair so that 240.00002 is still 240.
         */
        fun pixels(size: Float): Int = if (size <= 0f) 0 else ceil(size - 0.01f).toInt()
    }
}
