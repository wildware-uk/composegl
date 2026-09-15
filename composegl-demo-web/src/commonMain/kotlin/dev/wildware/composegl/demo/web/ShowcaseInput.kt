package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.TextEvent

/**
 * The tour's shortcuts in front of the screen: [ShowcaseState.onKey] and [ShowcaseState.onGamepad]
 * are asked first, and whatever they leave goes on to [screen] — the way a game puts its own
 * bindings in front of the interface.
 */
class ShowcaseInput(private val state: ShowcaseState, private val screen: InputSink) : InputSink {
    override fun onPointer(event: PointerEvent) = screen.onPointer(event)
    override fun onKey(event: KeyEvent) = state.onKey(event) || screen.onKey(event)
    override fun onText(event: TextEvent) = screen.onText(event)
    override fun onGamepad(event: GamepadEvent) = state.onGamepad(event) || screen.onGamepad(event)
}
