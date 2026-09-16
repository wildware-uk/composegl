package dev.wildware.composegl.ui.host

import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.draw.ScenePass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Viewport

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
 * What it does, in order: [settle] the tree — ask the runtime whether anything changed, lay it out
 * for the viewport, refresh [focus] — then tell [onLaidOut] that positions exist, render any dirty
 * `SceneView` into its picture ([scenes]), open the canvas's frame, draw, close it, and file the
 * timings. The first three of those are not written out here:
 * they are [settle], which is where that order is kept so a test that never draws can have the
 * same one. Nothing is allocated per node and almost nothing per frame; see
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

    init {
        // So a budget asked for its busiest nodes knows which tree to ask.
        budget.watch(host.tree)
    }

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
     * The focus manager to keep pointing at something real, refreshed once a frame after layout.
     *
     * It lives here rather than on [UiHost] because this is the class whose job is one whole
     * frame; a host owns no loop and no clock, and a property there would silently do nothing for
     * the trees — a [dev.wildware.composegl.ui.world.WorldPanel] on a wall — that have a host but
     * never come through here.
     *
     * Two reasons to leave it null. A game whose [onLaidOut] already calls `refresh` is done, and
     * setting this as well would refresh twice; note that the refresh here happens *before*
     * [onLaidOut], since routing input needs focus already settled, so a game that moves a pointer
     * inside [onLaidOut] and refreshes after it is not the same order and should keep its own.
     * And `refresh` builds a fresh list of focusable nodes every call, so setting this costs an
     * allocation a frame — small, but this project counts them. The [budget] does not show it: its
     * three numbers are the recompose, the layout and the draw, and the refresh is outside all
     * three, so a game that wants to know what it costs has to measure it itself.
     */
    var focus: FocusManager? = null

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
     * The prepass that renders every dirty `SceneView` into its own picture. Run by [render] after
     * [onLaidOut] and before the canvas's frame opens, unless [renderScenes] is off. Each render is
     * timed and counted in [budget], so the overlay shows what the scenes cost.
     */
    val scenes: ScenePass = ScenePass(host.tree, canvas, budget)

    /**
     * Whether [render] runs [scenes] itself. On by default. Switch it off only for a game that has
     * to put scene rendering between passes of its own and calls `scenes.render` itself — after
     * layout, outside the canvas's frame: inside [onLaidOut] is the place.
     */
    var renderScenes: Boolean = true

    /**
     * One frame. Returns whether anything actually changed, which is what a game checks before
     * bothering to swap buffers.
     *
     * [nanos] is the frame's time, from whatever clock the game already reads —
     * `System.nanoTime()` on a desktop.
     */
    fun render(viewport: Viewport, nanos: Long): Boolean {
        val changed = host.settle(viewport, focus, nanos, budget)
        onLaidOut?.invoke(nanos / 1_000_000)
        // After input has had its say, so a click that marks a scene dirty shows this frame; before
        // the frame opens, so a scene's state changes are not in the middle of the batch.
        val rendered = renderScenes && scenes.render(viewport, nanos) > 0
        val redrew = changed || rendered

        // Only while the budget is on: switched off, nobody is told anything and nothing is blamed.
        val trace = if (budget.measuring) budget.trace else null
        draw.trace = trace
        canvas.traceDrawCalls(trace)

        canvas.begin(viewport)
        drawBehind?.invoke(canvas)
        budget.draw { draw.draw(host.root) }
        canvas.end()

        // After end(), because that is when the last batch is actually handed over.
        budget.endFrame(canvas.drawCalls, redrew)
        return redrew
    }
}
