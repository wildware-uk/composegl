package uk.wildware.composegl.ui.input

/**
 * Which kind of device the player last used.
 *
 * The interface reads this to decide whether to draw a focus ring, whether to show a mouse cursor,
 * and whether a prompt says **E** or **A**. It changes the moment somebody picks up a pad, without
 * anything being reloaded.
 */
enum class InputSource { Mouse, Touch, Keyboard, Gamepad }

/**
 * Everything the outside world can tell the toolkit.
 *
 * A backend translates its platform's events into these four calls and nothing else. That is the
 * entire input contract, and it is why the toolkit compiles and tests with no engine present: a
 * test hands it the same events a keyboard would.
 *
 * Every call returns whether the toolkit used the event, so a game can give its interface first
 * refusal and then handle what is left.
 */
interface InputSink {

    fun onPointer(event: PointerEvent): Boolean

    fun onKey(event: KeyEvent): Boolean

    fun onText(event: TextEvent): Boolean

    fun onGamepad(event: GamepadEvent): Boolean
}
