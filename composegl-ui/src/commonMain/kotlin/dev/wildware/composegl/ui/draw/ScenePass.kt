package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.FrameBudget
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
 * What it spends, and when:
 *
 * - **Allocate** on the first render, at the panel's pixels times the resolution scale. A scene
 *   view that is never laid out with some room where it can be seen — on the screen, and inside
 *   every clip above it, such as a scroll area's — makes no picture at all.
 * - **Resize**: while the panel's size is still changing, the picture it has is kept and stretched
 *   over the panel (and filled again at its old size, if the game marked it dirty). Once the size
 *   has held for a frame, a picture of exactly the new size is made and rendered. A splitter drag
 *   stays smooth and a little soft, and lands sharp.
 * - **Cap**: neither side passes [UiCanvas.maxSceneSize]. The scale is cut, keeping the shape, and
 *   [warn] is told once per state rather than an allocation failing mid-frame.
 * - **Count**: each render is timed and counted in [budget] and blamed in its draw-call trace, so
 *   the frame budget overlay shows four live viewports as four scenes.
 *
 * A clean scene view costs a comparison. A tree with none costs one.
 *
 * @param budget where each render's time and count go. A [UiRenderer][dev.wildware.composegl.ui.host.UiRenderer]
 *   hands over its own. Null counts nothing.
 */
class ScenePass(val tree: UiTree, val canvas: UiCanvas, val budget: FrameBudget? = null) {

    /**
     * Told, once per `SceneViewState`, that a scene was cut down to fit the device. Prints by
     * default; a game with a log of its own points it there.
     */
    var warn: (String) -> Unit = { println(it) }

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
     * Renders every scene view that is dirty or has settled at a new pixel size, and returns how
     * many it rendered.
     *
     * @param viewport the one the tree was laid out in, for how many real pixels a design unit is.
     * @param nanos the frame's time, handed to each draw block as [SceneDrawScope.nanos].
     */
    fun render(viewport: Viewport, nanos: Long): Int {
        val nodes = tree.scenes
        if (nodes.isEmpty() || !canvas.drawsScenes) return 0
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
        val drawn = node.scaleInRoot
        // The panel in real pixels, before the resolution scale.
        val panelWidth = (node.width - padding.left - padding.right) * drawn * viewport.scaleX
        val panelHeight = (node.height - padding.top - padding.bottom - node.baselineTop - node.baselineBottom) * drawn * viewport.scaleY
        val seenWidth = pixels(panelWidth)
        val seenHeight = pixels(panelHeight)
        // Kept for input, which needs the scale before there is a picture to measure it by.
        state.pixelsPerUnitX = drawn * state.resolutionScale * viewport.scaleX
        state.pixelsPerUnitY = drawn * state.resolutionScale * viewport.scaleY
        // No room: nothing to draw into, and still wanted for when there is.
        if (seenWidth <= 0 || seenHeight <= 0) {
            state.seenWidth = 0
            state.seenHeight = 0
            return false
        }
        val steady = seenWidth == state.seenWidth && seenHeight == state.seenHeight
        state.seenWidth = seenWidth
        state.seenHeight = seenHeight

        // The scale asked for, cut so that neither side passes the biggest picture the canvas makes.
        val most = canvas.maxSceneSize.coerceAtLeast(1)
        var scale = state.resolutionScale
        var width = pixels(panelWidth * scale)
        var height = pixels(panelHeight * scale)
        val clamped = width > most || height > most
        if (clamped) {
            scale *= minOf(most / (panelWidth * scale), most / (panelHeight * scale))
            width = pixels(panelWidth * scale).coerceIn(1, most)
            height = pixels(panelHeight * scale).coerceIn(1, most)
            state.pixelsPerUnitX = drawn * scale * viewport.scaleX
            state.pixelsPerUnitY = drawn * scale * viewport.scaleY
        }

        val current = state.surface?.takeUnless { it.closed }
        val resized = current != null && (width != state.askedWidth || height != state.askedHeight)
        if (current != null && !state.dirty && !resized) return false
        if (!onScreen(node, viewport)) return false

        val stretching = resized && !steady
        if (stretching) {
            // Still being dragged: the tree stretches the picture it has over the panel, and the
            // frame after this one is asked for, since it may be the one the size holds still in.
            state.askForFrame()
            if (!state.dirty) return false
            // A live scene keeps moving during the drag, in the picture it already has.
            width = state.askedWidth
            height = state.askedHeight
        }

        rendering = state
        val made = try {
            val budget = budget
            if (budget != null && budget.measuring) {
                val trace = budget.trace
                trace.node = node
                trace.record(BatchBreak.Scene)
                trace.node = null
                budget.scene { canvas.scene(current, width, height, fill) }
            } else {
                canvas.scene(current, width, height, fill)
            }
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
        // A stretched render is at the old size, which says nothing new about the cap.
        if (!stretching) {
            state.clamped = clamped
            if (clamped) warnOnce(state, most)
        }
        return true
    }

    private fun warnOnce(state: SceneViewState, most: Int) {
        if (state.warnedClamped) return
        state.warnedClamped = true
        warn(
            "SceneView: a panel of ${state.seenWidth}x${state.seenHeight} pixels at resolution scale " +
                "${state.resolutionScale} is more than this device's biggest texture, $most pixels a side. " +
                "Rendering it at ${state.askedWidth}x${state.askedHeight} instead.",
        )
    }

    /**
     * Whether any of [node] can be seen: inside the viewport's area on the screen, and inside every
     * ancestor that cuts off what it holds. A panel laid out entirely out of sight is not worth a
     * picture, and a live one there is not worth rendering.
     *
     * The ancestors that cut are the ones the draw pass and the pointer agree on: a `clip` — which
     * is what a scroll area and a lazy list are — a resize still under way, and a scale or a
     * mirror, whose subtree is captured at exactly that node's rectangle. So a scene scrolled out
     * of a scroll area that is itself well inside the window counts as off the screen.
     */
    private fun onScreen(node: UiNode, viewport: Viewport): Boolean {
        val bounds = node.boundsInRoot
        val area = viewport.area
        val origin = viewport.origin
        var left = maxOf(bounds.left, (area.left - origin.x) / viewport.scaleX)
        var top = maxOf(bounds.top, (area.top - origin.y) / viewport.scaleY)
        var right = minOf(bounds.right, (area.right - origin.x) / viewport.scaleX)
        var bottom = minOf(bounds.bottom, (area.bottom - origin.y) / viewport.scaleY)
        var ancestor = node.parent
        while (ancestor != null && left < right && top < bottom) {
            if (cuts(ancestor)) {
                val clip = ancestor.boundsInRoot
                left = maxOf(left, clip.left)
                top = maxOf(top, clip.top)
                right = minOf(right, clip.right)
                bottom = minOf(bottom, clip.bottom)
            }
            ancestor = ancestor.parent
        }
        return left < right && top < bottom
    }

    /** Whether nothing of [node]'s children outside its own rectangle is drawn. */
    private fun cuts(node: UiNode): Boolean =
        node.resolved.clip != null || node.isResizing || node.drawnScale != 1f || node.drawnMirrorX || node.drawnMirrorY

    private companion object {
        /**
         * Design units times a scale, as whole pixels. Rounded up, so a part pixel is not a row lost,
         * less a hair so that 240.00002 is still 240.
         */
        fun pixels(size: Float): Int = if (size <= 0f) 0 else ceil(size - 0.01f).toInt()
    }
}
