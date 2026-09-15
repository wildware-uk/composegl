package dev.wildware.composegl.ui.input

/**
 * One thing a player can press, on whatever they are holding.
 *
 * What a controls screen stores per action and what `KeyBindButton` hands back. A key, a mouse
 * button or a pad button, and nothing more: no chords, no axes. A binding is a fact about a
 * device, so it compares by value and a game can keep a map of them and save it by
 * [Key.name], [PointerButton.name] and [GamepadButton.name].
 */
sealed interface InputBinding {

    data class Keyboard(val key: Key) : InputBinding

    data class Mouse(val button: PointerButton) : InputBinding

    data class Gamepad(val button: GamepadButton) : InputBinding

    /**
     * What to draw for this binding — the same [Prompt] a `PromptGlyph` draws.
     *
     * A key and a mouse button look the same on every device. A pad button does not: South is
     * **A** on [PromptStyle.Xbox], **✕** on [PromptStyle.PlayStation] and **B** on
     * [PromptStyle.Nintendo], and on a style that is not a pad it is drawn the Xbox way, which is
     * the one most players can read.
     */
    fun prompt(style: PromptStyle = PromptStyle.Xbox): Prompt = when (this) {
        is Keyboard -> Prompt("key.${key.name.lowercase()}", keyLabel(key))
        is Mouse -> Prompt("mouse.${button.name.lowercase()}", mouseLabel(button))
        is Gamepad -> Prompt("pad.${padKey(button)}", padLabel(button, style))
    }
}

/**
 * Binds [action] to [binding] on the devices it belongs to, so every `PromptGlyph` for the action
 * shows it on the next frame.
 *
 * A key or a mouse button is the keyboard's prompt; a pad button is every pad's, each drawn its own
 * way. What a controls screen calls from `KeyBindButton`'s `onBind`.
 */
fun Prompts.bind(action: Action, binding: InputBinding) {
    when (binding) {
        is InputBinding.Keyboard, is InputBinding.Mouse ->
            bind(action, PromptStyle.Keyboard, binding.prompt(PromptStyle.Keyboard))
        is InputBinding.Gamepad -> PadStyles.forEach { bind(action, it, binding.prompt(it)) }
    }
}

/**
 * Every entry already bound to [binding], leaving out [ignoring].
 *
 * The toolkit does not decide what a clash means — swap the two, refuse, or allow it because
 * crouch and slide really are the same key. It only says who else has it:
 *
 * ```kotlin
 * onBind = { input ->
 *     val clash = binds.clashesWith(input, ignoring = Jump)
 *     if (clash.isEmpty()) binds[Jump] = input else warning = "Already used by ${clash.first()}"
 * }
 * ```
 */
fun <K> Map<K, InputBinding?>.clashesWith(binding: InputBinding, ignoring: K? = null): List<K> =
    filter { (key, bound) -> key != ignoring && bound == binding }.keys.toList()

private val PadStyles = listOf(PromptStyle.Xbox, PromptStyle.PlayStation, PromptStyle.Nintendo)

/** Short enough for a box a line high, and the same words a keyboard prompt already uses. */
private fun keyLabel(key: Key): String = when (key) {
    Key.Escape -> "ESC"
    Key.Enter -> "ENTER"
    Key.Tab -> "TAB"
    Key.Space -> "SPACE"
    Key.Backspace -> "BKSP"
    Key.Delete -> "DEL"
    Key.Insert -> "INS"
    Key.Shift -> "SHIFT"
    Key.Control -> "CTRL"
    Key.Alt -> "ALT"
    Key.Meta -> "META"
    Key.CapsLock -> "CAPS"
    Key.PageUp -> "PGUP"
    Key.PageDown -> "PGDN"
    Key.Minus -> "-"
    Key.Equals -> "="
    Key.LeftBracket -> "["
    Key.RightBracket -> "]"
    Key.Backslash -> "\\"
    Key.Semicolon -> ";"
    Key.Apostrophe -> "'"
    Key.Grave -> "`"
    Key.Comma -> ","
    Key.Period -> "."
    Key.Slash -> "/"
    Key.Unknown -> "?"
    else -> key.name.removePrefix("Digit").uppercase()
}

private fun mouseLabel(button: PointerButton): String = when (button) {
    PointerButton.Primary -> "LMB"
    PointerButton.Secondary -> "RMB"
    PointerButton.Tertiary -> "MMB"
}

/** The dotted name a skin's button atlas uses: `south`, `dpad.up`, `leftbumper`. */
private fun padKey(button: GamepadButton): String = when (button) {
    GamepadButton.DpadUp -> "dpad.up"
    GamepadButton.DpadDown -> "dpad.down"
    GamepadButton.DpadLeft -> "dpad.left"
    GamepadButton.DpadRight -> "dpad.right"
    else -> button.name.lowercase()
}

/**
 * What is printed on the button, per make of pad.
 *
 * Nintendo's four are where everybody else's opposite ones are — South is B, East is A — which is
 * the same trap the prompt table spells out in full rather than derives.
 */
private fun padLabel(button: GamepadButton, style: PromptStyle): String {
    val pad = if (style in PadStyles) style else PromptStyle.Xbox
    return when (button) {
        GamepadButton.DpadUp -> "D-UP"
        GamepadButton.DpadDown -> "D-DOWN"
        GamepadButton.DpadLeft -> "D-LEFT"
        GamepadButton.DpadRight -> "D-RIGHT"
        GamepadButton.Guide -> if (pad == PromptStyle.Nintendo) "HOME" else "GUIDE"
        else -> when (pad) {
            PromptStyle.PlayStation -> when (button) {
                GamepadButton.South -> "✕"
                GamepadButton.East -> "○"
                GamepadButton.West -> "□"
                GamepadButton.North -> "△"
                GamepadButton.LeftBumper -> "L1"
                GamepadButton.RightBumper -> "R1"
                GamepadButton.Back -> "SHARE"
                GamepadButton.Start -> "☰"
                GamepadButton.LeftStick -> "L3"
                else -> "R3"
            }
            PromptStyle.Nintendo -> when (button) {
                GamepadButton.South -> "B"
                GamepadButton.East -> "A"
                GamepadButton.West -> "Y"
                GamepadButton.North -> "X"
                GamepadButton.LeftBumper -> "L"
                GamepadButton.RightBumper -> "R"
                GamepadButton.Back -> "−"
                GamepadButton.Start -> "+"
                GamepadButton.LeftStick -> "LS"
                else -> "RS"
            }
            else -> when (button) {
                GamepadButton.South -> "A"
                GamepadButton.East -> "B"
                GamepadButton.West -> "X"
                GamepadButton.North -> "Y"
                GamepadButton.LeftBumper -> "LB"
                GamepadButton.RightBumper -> "RB"
                GamepadButton.Back -> "VIEW"
                GamepadButton.Start -> "☰"
                GamepadButton.LeftStick -> "LS"
                else -> "RS"
            }
        }
    }
}
