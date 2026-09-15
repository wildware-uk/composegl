package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.event.GamePadConnectionEvent
import korlibs.event.GamePadUpdateEvent
import korlibs.event.KeyEvent
import korlibs.event.MouseEvent
import korlibs.event.PauseEvent
import korlibs.event.TouchEvent
import korlibs.korge.view.Stage
import korlibs.math.geom.Point
import korlibs.render.awt.BaseAwtGameWindow
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities

/**
 * Every kind of KorGE input, translated and pushed into one [InputSink]: the four translators, and
 * the listening that feeds them.
 *
 * [ComposeGlView] makes one of these for itself. A game makes its own when one window's input has to
 * be shared out first — split-screen, with an `InputRouter` as the sink and the window's own pixels
 * as the viewport:
 *
 * ```kotlin
 * val router = InputRouter()
 * val players = listOf(left, right)          // ComposeGlView(..., listens = false)
 * KorgeInput(router, { Viewport.oneToOne(windowSize) }).listen(stage)
 * players.forEach { router.assignPointer(it.viewport, it.player) }
 * router.assignGamepad(GamepadId(0), left.player)
 * ```
 *
 * An event the interface used is marked `preventDefault`, so a game listening on the same stage can
 * give the interface first refusal and handle what is left.
 *
 * @param sink where every translated event goes.
 * @param viewport the viewport window pixels are turned into design units by.
 * @param stageToWindow the stage's units back to window pixels, for touches.
 * @param clock monotonic milliseconds.
 * @param text the typed-text translator, which is also the text fields' [dev.wildware.composegl.ui.backend.TextInput].
 * @param deadZone how far a pad's stick must move before it has moved.
 */
class KorgeInput(
    sink: InputSink,
    viewport: () -> Viewport,
    stageToWindow: (Point) -> Point = { it },
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val text: KorgeTextInput = KorgeTextInput(sink),
    deadZone: Float = 0.2f,
) {

    val pointer = KorgePointerInput(sink, viewport, clock, stageToWindow)

    val keyboard = KorgeKeyboardInput(sink)

    val gamepads = KorgeGamepadInput(sink, deadZone)

    /**
     * Starts listening to [stage]: the mouse, touches, keys, pads, and the window going away. Close
     * what comes back to stop, which also lets go of every pad.
     */
    fun listen(stage: Stage): AutoCloseable {
        val closeables = mutableListOf<AutoCloseable>()
        closeables += stage.onEvents(*MouseEvent.Type.ALL) { if (pointer.onMouse(it)) it.preventDefault() }
        closeables += stage.onEvents(*TouchEvent.Type.ALL) { if (pointer.onTouch(it)) it.preventDefault() }
        closeables += stage.onEvents(*KeyEvent.Type.ALL) { event ->
            val used = keyboard.onKey(event) or text.onKey(event)
            if (used) event.preventDefault()
        }
        closeables += stage.onEvents(*GamePadConnectionEvent.Type.ALL) { gamepads.onConnection(it) }
        closeables += stage.onEvent(GamePadUpdateEvent) { gamepads.onUpdate(it) }
        // A phone's app going to the background.
        closeables += stage.onEvent(PauseEvent) { lostFocus() }
        focusLoss(stage)?.let { closeables += it }
        return AutoCloseable {
            closeables.forEach { it.close() }
            closeables.clear()
            gamepads.stop()
        }
    }

    /**
     * The window is no longer in front: abandon every press and drag without a click, and let go of
     * every key, since the platform will not necessarily report what was released while it was away.
     */
    fun lostFocus() {
        pointer.cancelAll()
        keyboard.releaseAll()
    }

    /**
     * A desktop window losing focus. KorGE sends no event for it — its AWT window only listens for
     * closing — so this asks AWT directly and hands the news to KorGE's own thread.
     */
    private fun focusLoss(stage: Stage): AutoCloseable? {
        val gameWindow = stage.views.gameWindow
        val component = (gameWindow as? BaseAwtGameWindow)?.component ?: return null
        val window = component as? Window ?: SwingUtilities.getWindowAncestor(component) ?: return null
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = Unit
            override fun windowLostFocus(e: WindowEvent) {
                gameWindow.queue { lostFocus() }
            }
        }
        window.addWindowFocusListener(listener)
        return AutoCloseable { window.removeWindowFocusListener(listener) }
    }
}
