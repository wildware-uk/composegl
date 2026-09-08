package composegl.gdx

import androidx.compose.ui.input.key.Key
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers

/**
 * LibGDX keycodes to Compose keys.
 *
 * There is no arithmetic relationship to lean on: LibGDX's codes come from Android, and Compose's
 * `Key` on desktop wraps AWT virtual key codes. So this is a table, matched by name, and a test
 * asserts every `Input.Keys` constant is either here or in an explicit ignore list — otherwise a
 * LibGDX upgrade would quietly stop delivering a key.
 *
 * @return null for keys Compose has no name for; the caller drops those.
 */
@Suppress("CyclomaticComplexMethod")
internal fun composeKeyFor(keycode: Int): Key? = when (keycode) {
    Input.Keys.A -> Key.A
    Input.Keys.B -> Key.B
    Input.Keys.C -> Key.C
    Input.Keys.D -> Key.D
    Input.Keys.E -> Key.E
    Input.Keys.F -> Key.F
    Input.Keys.G -> Key.G
    Input.Keys.H -> Key.H
    Input.Keys.I -> Key.I
    Input.Keys.J -> Key.J
    Input.Keys.K -> Key.K
    Input.Keys.L -> Key.L
    Input.Keys.M -> Key.M
    Input.Keys.N -> Key.N
    Input.Keys.O -> Key.O
    Input.Keys.P -> Key.P
    Input.Keys.Q -> Key.Q
    Input.Keys.R -> Key.R
    Input.Keys.S -> Key.S
    Input.Keys.T -> Key.T
    Input.Keys.U -> Key.U
    Input.Keys.V -> Key.V
    Input.Keys.W -> Key.W
    Input.Keys.X -> Key.X
    Input.Keys.Y -> Key.Y
    Input.Keys.Z -> Key.Z

    Input.Keys.NUM_0 -> Key.Zero
    Input.Keys.NUM_1 -> Key.One
    Input.Keys.NUM_2 -> Key.Two
    Input.Keys.NUM_3 -> Key.Three
    Input.Keys.NUM_4 -> Key.Four
    Input.Keys.NUM_5 -> Key.Five
    Input.Keys.NUM_6 -> Key.Six
    Input.Keys.NUM_7 -> Key.Seven
    Input.Keys.NUM_8 -> Key.Eight
    Input.Keys.NUM_9 -> Key.Nine

    Input.Keys.F1 -> Key.F1
    Input.Keys.F2 -> Key.F2
    Input.Keys.F3 -> Key.F3
    Input.Keys.F4 -> Key.F4
    Input.Keys.F5 -> Key.F5
    Input.Keys.F6 -> Key.F6
    Input.Keys.F7 -> Key.F7
    Input.Keys.F8 -> Key.F8
    Input.Keys.F9 -> Key.F9
    Input.Keys.F10 -> Key.F10
    Input.Keys.F11 -> Key.F11
    Input.Keys.F12 -> Key.F12

    // Editing and navigation. LibGDX's DEL is Android's: the backspace key.
    Input.Keys.BACKSPACE -> Key.Backspace
    Input.Keys.FORWARD_DEL -> Key.Delete
    Input.Keys.ENTER -> Key.Enter
    Input.Keys.TAB -> Key.Tab
    Input.Keys.SPACE -> Key.Spacebar
    Input.Keys.ESCAPE -> Key.Escape
    Input.Keys.INSERT -> Key.Insert
    // On the desktop backends these come from the Home and End keys, not Android's home button.
    Input.Keys.HOME -> Key.MoveHome
    Input.Keys.END -> Key.MoveEnd
    Input.Keys.PAGE_UP -> Key.PageUp
    Input.Keys.PAGE_DOWN -> Key.PageDown

    Input.Keys.DPAD_UP -> Key.DirectionUp
    Input.Keys.DPAD_DOWN -> Key.DirectionDown
    Input.Keys.DPAD_LEFT -> Key.DirectionLeft
    Input.Keys.DPAD_RIGHT -> Key.DirectionRight
    Input.Keys.DPAD_CENTER -> Key.DirectionCenter

    Input.Keys.SHIFT_LEFT -> Key.ShiftLeft
    Input.Keys.SHIFT_RIGHT -> Key.ShiftRight
    Input.Keys.CONTROL_LEFT -> Key.CtrlLeft
    Input.Keys.CONTROL_RIGHT -> Key.CtrlRight
    Input.Keys.ALT_LEFT -> Key.AltLeft
    Input.Keys.ALT_RIGHT -> Key.AltRight
    Input.Keys.CAPS_LOCK -> Key.CapsLock
    Input.Keys.NUM_LOCK -> Key.NumLock
    Input.Keys.SCROLL_LOCK -> Key.ScrollLock
    Input.Keys.PRINT_SCREEN -> Key.PrintScreen
    Input.Keys.PAUSE -> Key.Break
    Input.Keys.MENU -> Key.Menu
    Input.Keys.SYM -> Key.Symbol

    // Punctuation.
    Input.Keys.COMMA -> Key.Comma
    Input.Keys.PERIOD -> Key.Period
    Input.Keys.MINUS -> Key.Minus
    Input.Keys.PLUS -> Key.Plus
    Input.Keys.EQUALS -> Key.Equals
    Input.Keys.SEMICOLON -> Key.Semicolon
    Input.Keys.APOSTROPHE -> Key.Apostrophe
    Input.Keys.GRAVE -> Key.Grave
    Input.Keys.SLASH -> Key.Slash
    Input.Keys.BACKSLASH -> Key.Backslash
    Input.Keys.LEFT_BRACKET -> Key.LeftBracket
    Input.Keys.RIGHT_BRACKET -> Key.RightBracket
    Input.Keys.AT -> Key.At
    Input.Keys.POUND -> Key.Pound
    Input.Keys.STAR -> Key.Multiply
    Input.Keys.NUM -> Key.Number

    Input.Keys.NUMPAD_0 -> Key.NumPad0
    Input.Keys.NUMPAD_1 -> Key.NumPad1
    Input.Keys.NUMPAD_2 -> Key.NumPad2
    Input.Keys.NUMPAD_3 -> Key.NumPad3
    Input.Keys.NUMPAD_4 -> Key.NumPad4
    Input.Keys.NUMPAD_5 -> Key.NumPad5
    Input.Keys.NUMPAD_6 -> Key.NumPad6
    Input.Keys.NUMPAD_7 -> Key.NumPad7
    Input.Keys.NUMPAD_8 -> Key.NumPad8
    Input.Keys.NUMPAD_9 -> Key.NumPad9
    Input.Keys.NUMPAD_DIVIDE -> Key.NumPadDivide
    Input.Keys.NUMPAD_MULTIPLY -> Key.NumPadMultiply
    Input.Keys.NUMPAD_SUBTRACT -> Key.NumPadSubtract
    Input.Keys.NUMPAD_ADD -> Key.NumPadAdd
    Input.Keys.NUMPAD_DOT -> Key.NumPadDot
    Input.Keys.NUMPAD_COMMA -> Key.NumPadComma
    Input.Keys.NUMPAD_ENTER -> Key.NumPadEnter
    Input.Keys.NUMPAD_EQUALS -> Key.NumPadEquals
    Input.Keys.NUMPAD_LEFT_PAREN -> Key.NumPadLeftParenthesis
    Input.Keys.NUMPAD_RIGHT_PAREN -> Key.NumPadRightParenthesis

    // Gamepad and device buttons. Mostly meaningless to a HUD, but Compose has names for them
    // and a game might bind them, so they are delivered rather than dropped.
    Input.Keys.BUTTON_A -> Key.ButtonA
    Input.Keys.BUTTON_B -> Key.ButtonB
    Input.Keys.BUTTON_C -> Key.ButtonC
    Input.Keys.BUTTON_X -> Key.ButtonX
    Input.Keys.BUTTON_Y -> Key.ButtonY
    Input.Keys.BUTTON_Z -> Key.ButtonZ
    Input.Keys.BUTTON_L1 -> Key.ButtonL1
    Input.Keys.BUTTON_R1 -> Key.ButtonR1
    Input.Keys.BUTTON_L2 -> Key.ButtonL2
    Input.Keys.BUTTON_R2 -> Key.ButtonR2
    Input.Keys.BUTTON_THUMBL -> Key.ButtonThumbLeft
    Input.Keys.BUTTON_THUMBR -> Key.ButtonThumbRight
    Input.Keys.BUTTON_START -> Key.ButtonStart
    Input.Keys.BUTTON_SELECT -> Key.ButtonSelect
    Input.Keys.BUTTON_MODE -> Key.ButtonMode

    Input.Keys.BACK -> Key.Back
    Input.Keys.CALL -> Key.Call
    Input.Keys.ENDCALL -> Key.EndCall
    Input.Keys.CAMERA -> Key.Camera
    Input.Keys.CLEAR -> Key.Clear
    Input.Keys.ENVELOPE -> Key.Envelope
    Input.Keys.EXPLORER -> Key.Browser
    Input.Keys.FOCUS -> Key.Focus
    Input.Keys.HEADSETHOOK -> Key.HeadsetHook
    Input.Keys.MUTE -> Key.MicrophoneMute
    Input.Keys.NOTIFICATION -> Key.Notification
    Input.Keys.PICTSYMBOLS -> Key.PictureSymbols
    Input.Keys.POWER -> Key.Power
    Input.Keys.SEARCH -> Key.Search
    Input.Keys.SOFT_LEFT -> Key.SoftLeft
    Input.Keys.SOFT_RIGHT -> Key.SoftRight
    Input.Keys.SWITCH_CHARSET -> Key.SwitchCharset
    Input.Keys.VOLUME_UP -> Key.VolumeUp
    Input.Keys.VOLUME_DOWN -> Key.VolumeDown

    Input.Keys.MEDIA_PLAY_PAUSE -> Key.MediaPlayPause
    Input.Keys.MEDIA_STOP -> Key.MediaStop
    Input.Keys.MEDIA_NEXT -> Key.MediaNext
    Input.Keys.MEDIA_PREVIOUS -> Key.MediaPrevious
    Input.Keys.MEDIA_REWIND -> Key.MediaRewind
    Input.Keys.MEDIA_FAST_FORWARD -> Key.MediaFastForward

    else -> null
}

/**
 * The modifier state Compose needs, read at event time.
 *
 * LibGDX events carry no modifier bits at all, so the only way to know whether Shift was held is
 * to ask the keyboard now — which is correct, because "now" is when the event is being delivered.
 */
internal fun currentModifiers(): PointerKeyboardModifiers = modifiersOf(
    shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT),
    ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT),
    alt = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT),
    meta = Gdx.input.isKeyPressed(Input.Keys.SYM),
)

internal fun modifiersOf(
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
    meta: Boolean = false,
): PointerKeyboardModifiers = PointerKeyboardModifiers(
    isShiftPressed = shift,
    isCtrlPressed = ctrl,
    isAltPressed = alt,
    isMetaPressed = meta,
)
