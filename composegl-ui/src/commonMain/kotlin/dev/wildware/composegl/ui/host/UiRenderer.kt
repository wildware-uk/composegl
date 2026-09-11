package dev.wildware.composegl.ui.host

import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.layout.run

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
 * A game that draws its own world into the same canvas sets [drawBehind] and still gets the one
 * call. The five lines this replaces are all still public, for the rarer case that wants them
 * genuinely apart — two trees, an effect between them, a pass of its own.
 *
 * What it does, in order: ask the runtime whether anything changed, lay the tree out for the
 * viewport, tell [onLaidOut] that positions exist, open the canvas's frame, draw, close it, and
 * file the timings. Nothing is allocated per node and almost nothing per frame; see
 * [dev.wildware.composegl.ui.layout.MeasurePass] for why the one object it does make has to be made again.
 *
 * @param budget where the three-way timing split goes. Switched off it costs a boolean, so it is
 *   here by default rather than being something to add later when somebody complains.
 */
class UiRenderer(
    private val host: UiHost,
    /** The canvas it draws into, for a game that keeps one of these per canvas. */
    val canvas: UiCanvas,
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
     * Run inside the canvas's frame, before the interface is drawn: the game's own world, under
     * its heads-up display, in the same batch.
     *
     * Without it a game drawing through [UiCanvas] could not use this class at all — its board
     * has to go between `begin` and `end`, and this owns both. What it draws is deliberately not
     * counted in the budget's draw time, which is there to answer "what is the interface costing
     * me" and would stop meaning that if the game's own world were in it.
     *
     * Set once, for the same reason as [onLaidOut]: a lambda that mentions anything around it is
     * a fresh object, and per frame is exactly where this project does not want one.
     */
    var drawBehind: ((UiCanvas) -> Unit)? = null

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
        drawBehind?.invoke(canvas)
        budget.draw { draw.draw(host.root) }
        canvas.end()

        // After end(), because that is when the last batch is actually handed over.
        budget.endFrame(canvas.drawCalls, changed)
        return changed
    }
}
