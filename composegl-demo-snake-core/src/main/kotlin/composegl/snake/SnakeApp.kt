package composegl.snake

import composegl.snake.game.HighScoreStore
import composegl.snake.game.SnakeSession
import composegl.snake.game.StepResult
import composegl.snake.render.BoardRenderer
import composegl.snake.ui.SnakeUi
import composegl.ui.backend.Clipboard
import composegl.ui.backend.SoftKeyboard
import composegl.ui.debug.FrameBudget
import composegl.ui.input.InputSource
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Rect
import composegl.ui.geometry.Size
import composegl.ui.graphics.UiCanvas
import composegl.ui.host.UiHost
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.Viewport
import composegl.ui.layout.run
import composegl.ui.text.FontProvider

/**
 * The whole of Snake that is not a window.
 *
 * Rules, board, interface, input and the order of a frame, with nothing in it that knows whether it
 * is running on a desktop, in LibGDX or on a phone. A launcher opens a window, makes fonts and a
 * canvas, and calls the four methods below in order; that is the entire port surface, and it is why
 * the Android launcher is sixty lines rather than a second copy of the game.
 *
 * ```kotlin
 * app.update(delta)
 * app.layout(viewport, nanos)
 * canvas.begin(viewport)
 * app.draw(canvas)
 * canvas.end()
 * app.endFrame(canvas.drawCalls)
 * ```
 *
 * @param fonts the backend's fonts, already carrying body at 13, 16 and 20 and display at 34.
 * @param scores where high scores are kept, which is the one thing every platform does differently.
 * @param clipboard the backend's, for the name field on the menu.
 * @param softKeyboard the backend's on-screen keyboard, which only a phone actually has.
 * @param initialSource what to assume the player is holding before they touch anything: a phone
 *   launcher says touch, so the HUD says "swipe" from the first frame rather than after it.
 */
class SnakeApp(
    fonts: FontProvider,
    scores: HighScoreStore,
    clipboard: Clipboard = Clipboard.None,
    softKeyboard: SoftKeyboard = SoftKeyboard.None,
    initialSource: InputSource = InputSource.Mouse,
) : AutoCloseable {

    val session = SnakeSession(scores)

    /** What the interface costs a frame. Off, because it is a debug tool; F3 puts it up. */
    val budget = FrameBudget().also { it.isOn = false }

    private val board = BoardRenderer()
    private val host = UiHost()
    private val skin = snakeSkin(fonts)

    val input = SnakeInput(session, host.root, budget, initialSource)

    private var changed = false

    init {
        host.setContent { SnakeUi(session, fonts, skin.skin, clipboard, softKeyboard, input.source, budget) }
    }

    /** The game's half of a frame: the rules, the board's animation, and the skin file's clock. */
    fun update(delta: Float) {
        skin.reloadIfChanged()
        board.advance(delta)
        if (session.advance(delta) == StepResult.Ate) board.onEat()
    }

    /** The interface's half, up to but not including drawing. */
    fun layout(viewport: Viewport, nanos: Long) {
        changed = budget.recompose { host.frame(nanos) }
        budget.layout { MeasurePass().run(host.root, viewport) }
        input.frame(nanos / 1_000_000)
    }

    /**
     * The board first, the interface over it, into one canvas.
     *
     * Called between the backend's own `begin` and `end`, which is the only reason this is not one
     * method with [layout].
     */
    fun draw(canvas: UiCanvas) {
        board.draw(canvas, BoardArea, session.game, session.showGrid, session.stepProgress)
        budget.draw { DrawPass(canvas).draw(host.root) }
    }

    /** Closes the frame off, after the backend has flushed and can say what it cost. */
    fun endFrame(drawCalls: Int) = budget.endFrame(drawCalls, changed)

    override fun close() = host.dispose()

    companion object {

        /** The size everything is laid out in. A window of any shape is fitted to it. */
        val Design = Size(1280f, 720f)

        /** Where the board goes, leaving the left edge for the HUD. */
        val BoardArea = Rect(330f, 40f, 1240f, 680f)
    }
}
