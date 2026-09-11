package dev.wildware.composegl.snake

import dev.wildware.composegl.snake.game.HighScoreStore
import dev.wildware.composegl.snake.game.SnakeSession
import dev.wildware.composegl.snake.game.StepResult
import dev.wildware.composegl.snake.render.BoardRenderer
import dev.wildware.composegl.snake.ui.SnakeUi
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.TextInput
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.FontProvider

/**
 * The whole of Snake that is not a window.
 *
 * Rules, board, interface, input and the order of a frame, with nothing in it that knows whether it
 * is running on a desktop, in LibGDX or on a phone. A launcher opens a window, makes fonts and a
 * canvas, and calls the two methods below; that is the entire port surface, and it is why the
 * Android launcher is sixty lines rather than a second copy of the game.
 *
 * ```kotlin
 * app.update(delta)
 * app.frame(canvas, viewport, nanos)
 * ```
 *
 * @param fonts the backend's fonts, already carrying body at 13, 16 and 20 and display at 34.
 * @param scores where high scores are kept, which is the one thing every platform does differently.
 * @param clipboard the backend's, for the name field on the menu.
 * @param softKeyboard the backend's on-screen keyboard, which only a phone actually has.
 * @param textInput the platform's input method, for the name field. Without one the field only
 *   ever sees a character per key, which is enough for English and nothing else.
 * @param initialSource what to assume the player is holding before they touch anything: a phone
 *   launcher says touch, so the HUD says "swipe" from the first frame rather than after it.
 */
class SnakeApp(
    fonts: FontProvider,
    scores: HighScoreStore,
    clipboard: Clipboard = Clipboard.None,
    softKeyboard: SoftKeyboard = SoftKeyboard.None,
    textInput: TextInput = TextInput.None,
    initialSource: InputSource = InputSource.Mouse,
) : AutoCloseable {

    val session = SnakeSession(scores)

    /** What the interface costs a frame. Off, because it is a debug tool; F3 puts it up. */
    val budget = FrameBudget().also { it.isOn = false }

    private val board = BoardRenderer()
    private val host = UiHost()
    private val skin = snakeSkin(fonts)

    val input = SnakeInput(session, host.root, budget, initialSource)

    init {
        host.setContent {
            SnakeUi(session, fonts, skin.skin, clipboard, softKeyboard, textInput, input.source, budget)
        }
    }

    /** The game's half of a frame: the rules, the board's animation, and the skin file's clock. */
    fun update(delta: Float) {
        skin.reloadIfChanged()
        board.advance(delta)
        if (session.advance(delta) == StepResult.Ate) board.onEat()
    }

    /**
     * The interface's half of a frame: recompose, lay out, take input, draw the board, draw the
     * interface over it, and file what it all cost.
     *
     * Returns whether anything changed, which a loop that can skip a frame would check.
     *
     * @param canvas the backend's. The same one every frame for the life of the window, which is
     *   what lets the renderer be made once.
     */
    fun frame(canvas: UiCanvas, viewport: Viewport, nanos: Long): Boolean =
        renderer(canvas).render(viewport, nanos)

    /**
     * The renderer for [canvas], made on the first frame and kept.
     *
     * Kept rather than remade because it holds a draw pass, and a pass holds its canvas. A launcher
     * that somehow swapped canvases — a lost GL context on a phone — gets a new one rather than a
     * pass pointed at something dead.
     */
    private fun renderer(canvas: UiCanvas): UiRenderer {
        held?.takeIf { it.canvas === canvas }?.let { return it }
        return UiRenderer(host, canvas, budget).also {
            it.onLaidOut = input::frame
            // The board goes under the interface, inside the canvas's own frame, so that the whole
            // thing is one batch rather than two.
            it.drawBehind = ::drawBoard
            held = it
        }
    }

    private var held: UiRenderer? = null

    private fun drawBoard(canvas: UiCanvas) =
        board.draw(canvas, BoardArea, session.game, session.showGrid, session.stepProgress)

    override fun close() = host.dispose()

    companion object {

        /** The size everything is laid out in. A window of any shape is fitted to it. */
        val Design = Size(1280f, 720f)

        /** Where the board goes, leaving the left edge for the HUD. */
        val BoardArea = Rect(330f, 40f, 1240f, 680f)
    }
}
