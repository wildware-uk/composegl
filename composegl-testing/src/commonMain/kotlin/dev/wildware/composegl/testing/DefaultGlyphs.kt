package dev.wildware.composegl.testing

import dev.wildware.composegl.game.ItemMarks
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.Prompt
import dev.wildware.composegl.ui.input.PromptStyle
import dev.wildware.composegl.ui.input.Prompts
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.GamepadKeyboardLayout

/**
 * Every character a widget draws **because nobody said otherwise**.
 *
 * A backend bakes a font atlas from a list of characters, and a character outside that list comes
 * out as an empty box. So a default the toolkit ships and a default the backend bakes have to
 * agree, or a game that touched neither gets a hole in its interface: `ItemMarks.Arrows` defaulted
 * to `▼` for a while while no shipped atlas had `▼` in it, which put a blank square exactly where
 * the "this is worse" answer goes.
 *
 * Nothing here is written down twice. Every string is read back out of the real default — the
 * prompt table, the pad labels, the item card's marks — so the list cannot drift away from what the
 * widgets actually draw. **A new default glyph goes here**, by asking the thing that owns it, and
 * then every backend's test says whether that backend can bake it.
 *
 * Characters a *game* passes in are not this list's business: a game that writes its own item names
 * in Japanese registers the characters for them, and that is what `codepointsOf` is for.
 */
object DefaultGlyphs {

    /** The actions the prompt table answers for out of the box. */
    private val Actions = listOf(
        Action.Confirm, Action.Cancel, Action.Menu, Action.Interact, Action.Map,
        Action.Sprint, Action.Crouch, Action.Reload,
    )

    /**
     * Every default label, each with the name of what put it there, so a failure says which
     * widget's default is the one nothing can draw.
     */
    val Labelled: List<Pair<String, String>> = buildList {
        // Text that had to be cut short. The one character that says something is missing.
        add("TextStyle.ellipsis" to TextStyle().ellipsis)

        // An action nobody has bound.
        add("Prompt.Unbound" to Prompt.Unbound.label)

        // The prompt table, as a game with no bindings of its own sees it.
        val prompts = Prompts()
        Actions.forEach { action ->
            PromptStyle.entries.forEach { style ->
                add("Prompts.prompt(${action.name}, $style)" to prompts.prompt(action, style).label)
            }
        }

        // What is printed on each button of each make of pad, which is what a rebinding screen and
        // every `PromptGlyph` draw once a game has bound something.
        GamepadButton.entries.forEach { button ->
            PromptStyle.entries.forEach { style ->
                add("InputBinding.Gamepad($button).prompt($style)" to InputBinding.Gamepad(button).prompt(style).label)
            }
        }
        PointerButton.entries.forEach { button ->
            add("InputBinding.Mouse($button).prompt()" to InputBinding.Mouse(button).prompt().label)
        }
        // Every key this toolkit has a name for. `Key` is a value class over an Int rather than an
        // enum, so the range is walked; an unnamed code answers "Unknown", which is ASCII and
        // harmless to include.
        (0..KeyCodes).forEach { code ->
            val key = Key(code)
            add("InputBinding.Keyboard(${key.name}).prompt()" to InputBinding.Keyboard(key).prompt().label)
        }

        // The on-screen keyboard a player with nothing but a pad types on. Every key on it is drawn.
        GamepadKeyboardLayout.All.forEach { page ->
            add("GamepadKeyboardLayout(${page.label}).label" to page.label)
            page.rows.forEachIndexed { row, keys ->
                add("GamepadKeyboardLayout(${page.label}).rows[$row]" to keys.joinToString(""))
            }
        }

        // The item card's marks, which carry the answer for a player who cannot tell red from green.
        val marks = ItemMarks.Arrows
        add("ItemMarks.Arrows.better" to marks.better)
        add("ItemMarks.Arrows.worse" to marks.worse)
        add("ItemMarks.Arrows.same" to marks.same)
    }

    /** The same, as one string. */
    val All: String = Labelled.joinToString("") { it.second }

    /** Every distinct codepoint in [All], sorted. What a backend's baked set has to cover. */
    val Codepoints: List<Int> = All.codePointsOf().distinct().sorted()

    /**
     * The ones outside printable ASCII, which are the only ones a backend can get wrong: every
     * atlas bakes `0x20..0x7E`, and nobody has ever shipped one that did not.
     */
    val BeyondAscii: List<Int> = Codepoints.filter { it < 0x20 || it > 0x7E }

    /** Where [Labelled] found [codepoint], for a failure that names the widget rather than a number. */
    fun sourceOf(codepoint: Int): String =
        Labelled.firstOrNull { (_, text) -> text.codePointsOf().any { it == codepoint } }?.first ?: "unknown"

    /** `U+25BC ▼`, the way a person reads a missing character. */
    fun describe(codepoint: Int): String =
        "U+" + codepoint.toString(16).uppercase().padStart(4, '0') + " " + stringOf(codepoint) +
            " (" + sourceOf(codepoint) + ")"

    /** As far as [Key]'s own numbering goes. Past the end the name is "Unknown", which costs nothing. */
    private const val KeyCodes = 200
}

/** Every codepoint of this string, surrogate pairs joined back up. Written out because it is common. */
private fun String.codePointsOf(): List<Int> {
    val out = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val first = this[index]
        val second = if (index + 1 < length) this[index + 1] else null
        if (first.isHighSurrogate() && second != null && second.isLowSurrogate()) {
            out += 0x10000 + ((first.code - 0xD800) shl 10) + (second.code - 0xDC00)
            index += 2
        } else {
            out += first.code
            index++
        }
    }
    return out
}

/** One codepoint as the text it is, surrogate pair and all. */
private fun stringOf(codepoint: Int): String =
    if (codepoint < 0x10000) {
        codepoint.toChar().toString()
    } else {
        val above = codepoint - 0x10000
        charArrayOf((0xD800 + (above shr 10)).toChar(), (0xDC00 + (above and 0x3FF)).toChar()).concatToString()
    }
