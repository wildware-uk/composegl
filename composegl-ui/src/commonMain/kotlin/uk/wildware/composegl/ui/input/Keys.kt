package uk.wildware.composegl.ui.input

import kotlin.jvm.JvmInline

/**
 * A key on a keyboard, as this toolkit names it.
 *
 * Deliberately our own numbering rather than a re-export of anybody's. A backend translates its
 * platform's codes into these, which is the whole reason the toolkit can be used without LibGDX,
 * GLFW or an Android runtime present.
 *
 * A value class holding an Int rather than an enum: a key is compared and passed around constantly,
 * an enum of two hundred entries is a poor fit for `when` in practice, and an unknown key from a
 * strange keyboard should be representable rather than crash.
 */
@JvmInline
value class Key(val code: Int) {

    override fun toString(): String = names[code]?.let { "Key.$it" } ?: "Key(unknown $code)"

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        val Unknown = Key(0)

        val A = Key(1); val B = Key(2); val C = Key(3); val D = Key(4); val E = Key(5)
        val F = Key(6); val G = Key(7); val H = Key(8); val I = Key(9); val J = Key(10)
        val K = Key(11); val L = Key(12); val M = Key(13); val N = Key(14); val O = Key(15)
        val P = Key(16); val Q = Key(17); val R = Key(18); val S = Key(19); val T = Key(20)
        val U = Key(21); val V = Key(22); val W = Key(23); val X = Key(24); val Y = Key(25)
        val Z = Key(26)

        val Digit0 = Key(30); val Digit1 = Key(31); val Digit2 = Key(32); val Digit3 = Key(33)
        val Digit4 = Key(34); val Digit5 = Key(35); val Digit6 = Key(36); val Digit7 = Key(37)
        val Digit8 = Key(38); val Digit9 = Key(39)

        val F1 = Key(50); val F2 = Key(51); val F3 = Key(52); val F4 = Key(53)
        val F5 = Key(54); val F6 = Key(55); val F7 = Key(56); val F8 = Key(57)
        val F9 = Key(58); val F10 = Key(59); val F11 = Key(60); val F12 = Key(61)

        val Left = Key(70); val Right = Key(71); val Up = Key(72); val Down = Key(73)
        val Home = Key(74); val End = Key(75); val PageUp = Key(76); val PageDown = Key(77)

        val Enter = Key(80); val Escape = Key(81); val Tab = Key(82); val Space = Key(83)
        val Backspace = Key(84); val Delete = Key(85); val Insert = Key(86)

        val Shift = Key(90); val Control = Key(91); val Alt = Key(92); val Meta = Key(93)
        val CapsLock = Key(94)

        val Minus = Key(100); val Equals = Key(101); val LeftBracket = Key(102)
        val RightBracket = Key(103); val Backslash = Key(104); val Semicolon = Key(105)
        val Apostrophe = Key(106); val Grave = Key(107); val Comma = Key(108)
        val Period = Key(109); val Slash = Key(110)

        /** Android and console hardware back. Closes a dialogue, like Escape. */
        val Back = Key(120)

        private val names: Map<Int, String> = buildMap {
            ('A'..'Z').forEachIndexed { index, letter -> put(index + 1, letter.toString()) }
            (0..9).forEach { put(30 + it, "Digit$it") }
            (1..12).forEach { put(49 + it, "F$it") }
            putAll(
                mapOf(
                    0 to "Unknown",
                    70 to "Left", 71 to "Right", 72 to "Up", 73 to "Down",
                    74 to "Home", 75 to "End", 76 to "PageUp", 77 to "PageDown",
                    80 to "Enter", 81 to "Escape", 82 to "Tab", 83 to "Space",
                    84 to "Backspace", 85 to "Delete", 86 to "Insert",
                    90 to "Shift", 91 to "Control", 92 to "Alt", 93 to "Meta", 94 to "CapsLock",
                    100 to "Minus", 101 to "Equals", 102 to "LeftBracket", 103 to "RightBracket",
                    104 to "Backslash", 105 to "Semicolon", 106 to "Apostrophe", 107 to "Grave",
                    108 to "Comma", 109 to "Period", 110 to "Slash",
                    120 to "Back",
                ),
            )
        }
    }
}

/**
 * Which modifier keys were held.
 *
 * `Meta` is Command on macOS and the Windows key elsewhere. Shortcuts should ask [isPrimary]
 * rather than picking one, so a game does not have to know which machine it is on.
 */
@JvmInline
value class Modifiers(val bits: Int) {

    val shift: Boolean get() = bits and SHIFT != 0
    val control: Boolean get() = bits and CONTROL != 0
    val alt: Boolean get() = bits and ALT != 0
    val meta: Boolean get() = bits and META != 0

    /** The one shortcuts use: Command on macOS, Control everywhere else. */
    val isPrimary: Boolean get() = if (isMac) meta else control

    val none: Boolean get() = bits == 0

    operator fun plus(other: Modifiers) = Modifiers(bits or other.bits)

    override fun toString(): String {
        if (none) return "Modifiers(none)"
        val held = buildList {
            if (shift) add("Shift")
            if (control) add("Control")
            if (alt) add("Alt")
            if (meta) add("Meta")
        }
        return "Modifiers(${held.joinToString("+")})"
    }

    companion object {
        const val SHIFT = 1
        const val CONTROL = 2
        const val ALT = 4
        const val META = 8

        val None = Modifiers(0)
        val Shift = Modifiers(SHIFT)
        val Control = Modifiers(CONTROL)
        val Alt = Modifiers(ALT)
        val Meta = Modifiers(META)

        /**
         * Whether the primary shortcut key is Command rather than Control.
         *
         * Set by the backend at startup, because only a backend knows what it is running on. The
         * toolkit does not ask the platform anything — it has no platform to ask — and the default
         * of Control is the right answer for Android, iOS, Windows, Linux and every console.
         */
        var isMac: Boolean = false
    }
}

enum class KeyEventType { Down, Up }

/**
 * A key going down or coming up.
 *
 * Not a character. Text arrives as [TextEvent] instead, because the two are genuinely different
 * things: one Shift and one A produce two key events and one character, and a Chinese keyboard
 * produces many key events and one character much later.
 */
data class KeyEvent(
    val key: Key,
    val type: KeyEventType,
    val modifiers: Modifiers = Modifiers.None,
    /** True when the platform is repeating a held key rather than reporting a fresh press. */
    val repeat: Boolean = false,
)

/**
 * Text the platform has decided on, ready to insert.
 *
 * Backends must send committed text here and never as key events, and must not send control
 * characters at all. Both rules are the two bugs that reached a user in the previous version of
 * this project: backspace arrived as a character and was inserted as a square, and a held backspace
 * arrived only as repeated characters and so deleted nothing.
 */
data class TextEvent(val text: String)
