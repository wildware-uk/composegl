package composegl.lwjgl3

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import org.lwjgl.glfw.GLFW

/**
 * GLFW keycodes to Compose keys.
 *
 * GLFW's codes are ASCII-ish for the printable keys and arbitrary above 255; Compose's `Key` on
 * desktop wraps AWT virtual key codes. There is no relationship to lean on, so this is a table,
 * and a test asserts every `GLFW_KEY_*` constant is either in it or in an explicit ignore list.
 *
 * @return null for keys Compose has no name for; the caller drops those.
 */
@Suppress("CyclomaticComplexMethod")
internal fun composeKeyFor(glfwKey: Int): Key? = when (glfwKey) {
    // GLFW numbers the letters and digits in ASCII order, and so does Compose's Key, so these
    // two ranges are the one place arithmetic is safe.
    in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> Key(Key.A.keyCode + (glfwKey - GLFW.GLFW_KEY_A))
    in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> Key(Key.Zero.keyCode + (glfwKey - GLFW.GLFW_KEY_0))
    in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12 -> Key(Key.F1.keyCode + (glfwKey - GLFW.GLFW_KEY_F1))
    in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9 -> Key(Key.NumPad0.keyCode + (glfwKey - GLFW.GLFW_KEY_KP_0))

    GLFW.GLFW_KEY_SPACE -> Key.Spacebar
    GLFW.GLFW_KEY_APOSTROPHE -> Key.Apostrophe
    GLFW.GLFW_KEY_COMMA -> Key.Comma
    GLFW.GLFW_KEY_MINUS -> Key.Minus
    GLFW.GLFW_KEY_PERIOD -> Key.Period
    GLFW.GLFW_KEY_SLASH -> Key.Slash
    GLFW.GLFW_KEY_SEMICOLON -> Key.Semicolon
    GLFW.GLFW_KEY_EQUAL -> Key.Equals
    GLFW.GLFW_KEY_LEFT_BRACKET -> Key.LeftBracket
    GLFW.GLFW_KEY_BACKSLASH -> Key.Backslash
    GLFW.GLFW_KEY_RIGHT_BRACKET -> Key.RightBracket
    GLFW.GLFW_KEY_GRAVE_ACCENT -> Key.Grave

    GLFW.GLFW_KEY_ESCAPE -> Key.Escape
    GLFW.GLFW_KEY_ENTER -> Key.Enter
    GLFW.GLFW_KEY_TAB -> Key.Tab
    GLFW.GLFW_KEY_BACKSPACE -> Key.Backspace
    GLFW.GLFW_KEY_INSERT -> Key.Insert
    GLFW.GLFW_KEY_DELETE -> Key.Delete
    GLFW.GLFW_KEY_RIGHT -> Key.DirectionRight
    GLFW.GLFW_KEY_LEFT -> Key.DirectionLeft
    GLFW.GLFW_KEY_DOWN -> Key.DirectionDown
    GLFW.GLFW_KEY_UP -> Key.DirectionUp
    GLFW.GLFW_KEY_PAGE_UP -> Key.PageUp
    GLFW.GLFW_KEY_PAGE_DOWN -> Key.PageDown
    GLFW.GLFW_KEY_HOME -> Key.MoveHome
    GLFW.GLFW_KEY_END -> Key.MoveEnd
    GLFW.GLFW_KEY_CAPS_LOCK -> Key.CapsLock
    GLFW.GLFW_KEY_SCROLL_LOCK -> Key.ScrollLock
    GLFW.GLFW_KEY_NUM_LOCK -> Key.NumLock
    GLFW.GLFW_KEY_PRINT_SCREEN -> Key.PrintScreen
    GLFW.GLFW_KEY_PAUSE -> Key.Break
    GLFW.GLFW_KEY_MENU -> Key.Menu

    GLFW.GLFW_KEY_KP_DECIMAL -> Key.NumPadDot
    GLFW.GLFW_KEY_KP_DIVIDE -> Key.NumPadDivide
    GLFW.GLFW_KEY_KP_MULTIPLY -> Key.NumPadMultiply
    GLFW.GLFW_KEY_KP_SUBTRACT -> Key.NumPadSubtract
    GLFW.GLFW_KEY_KP_ADD -> Key.NumPadAdd
    GLFW.GLFW_KEY_KP_ENTER -> Key.NumPadEnter
    GLFW.GLFW_KEY_KP_EQUAL -> Key.NumPadEquals

    GLFW.GLFW_KEY_LEFT_SHIFT -> Key.ShiftLeft
    GLFW.GLFW_KEY_RIGHT_SHIFT -> Key.ShiftRight
    GLFW.GLFW_KEY_LEFT_CONTROL -> Key.CtrlLeft
    GLFW.GLFW_KEY_RIGHT_CONTROL -> Key.CtrlRight
    GLFW.GLFW_KEY_LEFT_ALT -> Key.AltLeft
    GLFW.GLFW_KEY_RIGHT_ALT -> Key.AltRight
    GLFW.GLFW_KEY_LEFT_SUPER -> Key.MetaLeft
    GLFW.GLFW_KEY_RIGHT_SUPER -> Key.MetaRight

    else -> null
}

/** GLFW's mouse buttons to Compose's. */
internal fun composeButtonFor(glfwButton: Int): PointerButton = when (glfwButton) {
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
    GLFW.GLFW_MOUSE_BUTTON_4 -> PointerButton.Back
    GLFW.GLFW_MOUSE_BUTTON_5 -> PointerButton.Forward
    else -> PointerButton.Primary
}

/**
 * GLFW hands the modifier bits to the callback, which is better than asking the keyboard
 * afterwards: these are the modifiers as they were when the event happened.
 */
internal fun modifiersOf(glfwMods: Int): PointerKeyboardModifiers = PointerKeyboardModifiers(
    isShiftPressed = glfwMods and GLFW.GLFW_MOD_SHIFT != 0,
    isCtrlPressed = glfwMods and GLFW.GLFW_MOD_CONTROL != 0,
    isAltPressed = glfwMods and GLFW.GLFW_MOD_ALT != 0,
    isMetaPressed = glfwMods and GLFW.GLFW_MOD_SUPER != 0,
    isCapsLockOn = glfwMods and GLFW.GLFW_MOD_CAPS_LOCK != 0,
    isNumLockOn = glfwMods and GLFW.GLFW_MOD_NUM_LOCK != 0,
)
