package dev.wildware.composegl.ui.input

/**
 * A key and the modifiers held with it: Ctrl+S, Ctrl+Shift+Z, F5.
 *
 * What a menu item shows beside its label and fires from anywhere on the screen. Written the way it
 * is read out:
 *
 * ```kotlin
 * Item("Save", shortcut = Modifiers.Primary + Key.S) { save() }
 * Item("Redo", shortcut = Modifiers.Primary + Modifiers.Shift + Key.Z) { redo() }
 * Item("Play", shortcut = KeyShortcut(Key.F5)) { play() }
 * ```
 *
 * [Modifiers.Primary] is Command on a Mac and Control everywhere else, so one definition is the
 * right shortcut on every machine. It is read when the shortcut is built, which is after a backend
 * has said which machine it is on.
 *
 * The modifiers must match exactly: Ctrl+Shift+S is not Ctrl+S with Shift held by accident, and a
 * game with both would otherwise fire the wrong one.
 */
data class KeyShortcut(val key: Key, val modifiers: Modifiers = Modifiers.None) {

    /** Whether [event] is this shortcut going down. A held key repeating is not a second press. */
    fun matches(event: KeyEvent): Boolean =
        event.type == KeyEventType.Down && !event.repeat && event.key == key && event.modifiers == modifiers

    /**
     * What a menu writes beside the item: `Ctrl+Shift+S` on most machines and `Shift+Cmd+S` on a Mac,
     * in the order each platform prints them.
     *
     * Words rather than the Mac's ⌘ and ⇧ symbols, because a game's bitmap font rarely has those
     * glyphs and a box where a symbol should be is worse than a word.
     */
    val label: String
        get() = buildString {
            val names = if (Modifiers.isMac) {
                listOf(modifiers.control to "Ctrl", modifiers.alt to "Opt", modifiers.shift to "Shift", modifiers.meta to "Cmd")
            } else {
                listOf(modifiers.control to "Ctrl", modifiers.alt to "Alt", modifiers.shift to "Shift", modifiers.meta to "Meta")
            }
            names.forEach { (held, name) -> if (held) append(name).append('+') }
            append(shortcutKeyName(key))
        }

    override fun toString(): String = "KeyShortcut($label)"
}

/** This key with these modifiers held: `Modifiers.Primary + Key.S`. */
operator fun Modifiers.plus(key: Key): KeyShortcut = KeyShortcut(key, this)

/** A key's name as a menu prints it: `S`, `F5`, `Del`, `PgUp`. */
private fun shortcutKeyName(key: Key): String = when (key) {
    Key.Escape -> "Esc"
    Key.Backspace -> "Backspace"
    Key.Delete -> "Del"
    Key.Insert -> "Ins"
    Key.PageUp -> "PgUp"
    Key.PageDown -> "PgDn"
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
    else -> key.name.removePrefix("Digit")
}
