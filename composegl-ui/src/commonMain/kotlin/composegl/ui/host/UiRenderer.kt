package composegl.ui.host

import composegl.ui.debug.FrameBudget
import composegl.ui.draw.DrawPass
import composegl.ui.graphics.UiCanvas
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.Viewport
import composegl.ui.layout.run

/**
 * A whole frame of interface, in one call.
 *
 * ```kotlin
 * val ui = UiRenderer(host, canvas)   // once
 *
 * // …and in the game loop, after the game has drawn its own world:
 * ui.render(viewport, System.nanoTime())
 * ```
 *
 * The five lines it replaces are still there and still public, because a game that wants to put
 * something between them — its own world drawn into the same canvas, an effect, a second tree —
 * needs them apart. This is for everybody else, which is most people.
 *
 * What it does, in order: ask the runtime whether anything changed, lay the tree out for the
 * viewport, tell [onLaidOut] that positions exist, open the canvas's frame, draw, close it, and
 * file the timings. Nothing is allocated per node and almost nothing per frame; see
 * [composegl.ui.layout.MeasurePass] for why the one object it does make has to be made again.
 *
 * @param budget where the three-way timing split goes. Switched off it costs a boolean, so it is
 *   here by default rather than being something to add later when somebody complains.
 */
class UiRenderer(
    private val host: UiHost,
    private val canvas: UiCanvas,
    val budget: FrameBudget = FrameBudget(),
) {

    /** Made once: a pass holds the canvas and nothing else. */
    private val draw = DrawPass(canvas)

    /**
     * Run after the tree is laid out and before it is drawn, with the frame's time in
     * milliseconds.
     *
     * Where input belongs. Working out what the pointer is over means knowing where everything
     * is, so it cannot happen before layout; and acting on it before drawing is what stops a
     * click taking a frame to show. Set once rather than passed per frame, so a game that uses it
     * allocates nothing for it.
     */
    var onLaidOut: ((Long) -> Unit)? = null

    /**
     * One frame. Returns whether anything actually changed, which is what a game checks before
     * bothering to swap buffers.
     *
     * [nanos] is the frame's time, from whatever clock the game already reads —
     * `System.nanoTime()` on a desktop.
     */
    fun render(viewport: Viewport, nanos: Long): Boolean {
        val changed = budget.recompose { host.frame(nanos) }
        budget.layout { MeasurePass().run(host.root, viewport) }
        onLaidOut?.invoke(nanos / 1_000_000)

        canvas.begin(viewport)
        budget.draw { draw.draw(host.root) }
        canvas.end()

        // After end(), because that is when the last batch is actually handed over.
        budget.endFrame(canvas.drawCalls, changed)
        return changed
    }
}
