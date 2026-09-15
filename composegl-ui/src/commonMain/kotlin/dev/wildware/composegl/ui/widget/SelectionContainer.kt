package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.text.Movement
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.move
import dev.wildware.composegl.ui.text.selectAll

/**
 * What is selected inside a [SelectionContainer], and in which piece of text.
 *
 * One selection for the whole container, held by one [Text] at a time: pressing a second label
 * takes the selection away from the first, exactly as clicking a second paragraph on a web page
 * does. A game can hold one of these to read what the player has selected, or to clear it.
 */
@Stable
class SelectionState {

    /** Which label holds the selection, or null for none. Compared by identity, never read. */
    private var owner: Any? by mutableStateOf(null)

    private var held: TextFieldValue by mutableStateOf(TextFieldValue.Empty)

    /**
     * Whether shift is down, learned from the keys that reach the container, so a shift-click can
     * extend a selection. A pointer event says nothing about the keyboard.
     */
    internal var shiftHeld = false

    /** The selected text, or an empty string when nothing is selected. */
    val selectedText: String get() = if (owner == null) "" else held.selected

    /** The selected stretch of the label that holds it, or null when no label does. */
    val selection: TextRange? get() = if (owner == null) null else held.selection

    /** Selects nothing. */
    fun clear() {
        owner = null
        held = TextFieldValue.Empty
    }

    /** The range [key] has selected, or null when the selection is somewhere else. Reading subscribes. */
    internal fun rangeIn(key: Any): TextRange? = if (owner === key) held.selection else null

    /** What the gestures on [key] start from: its own selection, or a caret at the start of [text]. */
    internal fun valueFor(key: Any, text: String): TextFieldValue =
        if (owner === key && held.text == text) held else TextFieldValue(text, TextRange(0))

    internal fun select(key: Any, value: TextFieldValue) {
        owner = key
        held = value
    }

    /** [key] is going, or has lost focus, or says something else now. Its selection goes with it. */
    internal fun forget(key: Any) {
        if (owner === key) clear()
    }

    /**
     * The label holding the selection now reads [text]. A seed that was rerolled while it was
     * selected must not leave half of a stretch of the old one highlighted, or on the clipboard.
     */
    internal fun textChanged(key: Any, text: String) {
        if (owner === key && held.text != text) clear()
    }

    /** The keys a selection answers to. Null for any it has no use for, which carry on outwards. */
    internal fun onKey(
        key: Key,
        shift: Boolean,
        primary: Boolean,
        clipboard: Clipboard,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): Boolean {
        val holder = owner ?: return false
        val value = held
        return when {
            primary && key == Key.C -> {
                // Nothing selected is nothing to copy, and Ctrl+C carries on to whatever is around.
                if (value.selection.collapsed) false else {
                    clipboard.write(value.selected)
                    true
                }
            }
            primary && key == Key.A -> {
                select(holder, value.selectAll())
                true
            }
            shift -> {
                val movement = when (key) {
                    Key.Left -> if (primary) Movement.WordLeft else Movement.Left
                    Key.Right -> if (primary) Movement.WordRight else Movement.Right
                    Key.Home -> if (primary) Movement.TextStart else Movement.LineStart
                    Key.End -> if (primary) Movement.TextEnd else Movement.LineEnd
                    else -> return false
                }
                select(holder, value.move(movement, extend = true, direction = direction))
                true
            }
            else -> false
        }
    }
}

/** What a [Text] inside a [SelectionContainer] is told: where the selection lives, and its colour. */
internal class SelectionHost(val state: SelectionState, val highlight: ResolvedStyle)

/** The container a label is inside, or null for none — which is every label a game did not wrap. */
internal val LocalSelection: ProvidableCompositionLocal<SelectionHost?> = compositionLocalOf { null }

/**
 * Makes the text inside it selectable, and copyable.
 *
 * ```kotlin
 * SelectionContainer { Text("Seed: 8F3A-22C1") }
 * ```
 *
 * The things a player wants to copy out of a game — a seed, a server address, a lobby code, an
 * error message to paste into a bug report — are ordinary labels, and a label is scenery: the
 * pointer passes straight through it. Inside one of these every [Text] takes the same gestures a
 * [TextField] does, from the same code: press and drag to select, double-click a word, triple-click
 * a line, shift-click to extend. Ctrl+C copies (Command+C on a Mac), Ctrl+A selects the whole
 * label, and shift with the arrows, Home and End moves the far end of the selection.
 *
 * Pressing a label brings the keyboard to it without making it somewhere Tab or a pad can go —
 * see [dev.wildware.composegl.ui.modifier.focusableByPointer] — so wrapping a menu in one of these
 * leaves its pad navigation exactly as it was. Focus going anywhere else clears the selection.
 *
 * The labels on a [Button], a [Checkbox], a [Stepper], a [Dropdown], a hotbar slot or a notification
 * a click dismisses stay unselectable, so pressing them still presses the control;
 * [DisableSelection] does the same for anything else. A clickable run in a styled label keeps its
 * click too, and the rest of that label selects around it.
 *
 * Anything else that is pressable keeps its click only if its text says so: a selectable label takes
 * the press before a `clickable` panel around it does, so wrap a save slot's or a list row's text in
 * [DisableSelection] when the whole row is the thing to press.
 *
 * One thing is different about a label inside: the toolkit breaks its lines itself, as a styled
 * [Text] does, because a selection has to know where each character is. Very occasionally that puts
 * a line break one word away from where the same label outside a container would have it.
 *
 * @param style the skin style the highlight is drawn from — its background, behind the glyphs.
 *   A skin that does not name it gets `"field.selection"`, so selected text looks the same in a
 *   label as in a field unless a game says otherwise.
 * @param state pass one in to read [SelectionState.selectedText] from outside, or to clear it.
 * @param clipboard where Ctrl+C puts the text. The one the game provided, by default.
 */
@Composable
fun SelectionContainer(
    modifier: Modifier = Modifier,
    style: String = "selection",
    state: SelectionState = remember { SelectionState() },
    clipboard: Clipboard = LocalClipboard.current,
    content: @Composable () -> Unit,
) {
    val named = if (LocalSkin.current.has(style)) style else "field.selection"
    val highlight = rememberStyle(named)
    val host = remember(state, highlight) { SelectionHost(state, highlight) }

    // On the container rather than on each label, because a key starts at the focused label and walks
    // outwards through here — and there is one selection, so there is one thing to copy.
    val direction = LocalLayoutDirection.current
    val keys = remember(state, clipboard, direction) {
        KeyHandler { event ->
            state.shiftHeld = event.modifiers.shift
            event.type == KeyEventType.Down &&
                state.onKey(event.key, event.modifiers.shift, event.modifiers.isPrimary, clipboard, direction)
        }
    }

    Box(modifier.onKeyEvent(keys)) {
        CompositionLocalProvider(LocalSelection provides host, content = content)
    }
}

/**
 * Text inside this is not selectable, even inside a [SelectionContainer].
 *
 * For a label that is really a control — something a player presses rather than reads. [Button],
 * [Checkbox], [Toggle], [RadioButton] and [Stepper] do this for their own labels already, and so
 * do a hotbar's slots and a notification a click dismisses.
 */
@Composable
fun DisableSelection(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSelection provides null, content = content)
