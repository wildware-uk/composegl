package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.input.TextHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.onTextEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.TextFieldValue

/**
 * Something the on-screen keyboard types into.
 *
 * `TextField` is one, and hands the keyboard the same two handlers a real keyboard reaches it
 * through — so a letter picked with a pad goes through the field's own [dev.wildware.composegl.ui.text.KeyboardEditor],
 * and the length limit, the refusal of control characters and the caret all behave exactly as
 * they do for a player at a desk.
 */
interface KeyboardTarget {

    /** What is in it now, read after every key so the keyboard's own line shows the result. */
    val value: TextFieldValue

    /** Shown on the keyboard's line while there is nothing in it, so the player knows what is asked. */
    val placeholder: String? get() = null

    /** Whether the keyboard offers an Enter key. */
    val multiline: Boolean

    fun onText(event: TextEvent): Boolean

    fun onKey(event: KeyEvent): Boolean
}

/**
 * One page of keys: the letters, the symbols, a number pad.
 *
 * @param label what the key that switches *to* this page says.
 * @param rows the keys, a row at a time. Every key is one character, typed as it is written.
 * @param shifts whether Shift means anything here. Only letters have capitals.
 */
class GamepadKeyboardLayout(
    val label: String,
    val rows: List<List<String>>,
    val shifts: Boolean = false,
) {
    companion object {

        /** Written as strings of characters because that is how a keyboard is read. */
        private fun of(vararg rows: String): List<List<String>> = rows.map { row -> row.map { it.toString() } }

        val Letters = GamepadKeyboardLayout(
            label = "ABC",
            rows = of("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.?"),
            shifts = true,
        )

        val Symbols = GamepadKeyboardLayout(
            label = "#+=",
            rows = of("!@#$%^&*()", "-_=+[]{}\\|", ";:\"/<>~`€£"),
        )

        /** A number pad, for a seed, a port or a count — big keys, because there are only twelve. */
        val Numbers = GamepadKeyboardLayout(
            label = "123",
            rows = of("123", "456", "789", "-0."),
        )

        val All: List<GamepadKeyboardLayout> = listOf(Letters, Symbols, Numbers)
    }
}

/** The keyboard the fields inside [ProvideGamepadKeyboard] open. Null outside one. */
val LocalGamepadKeyboard: ProvidableCompositionLocal<GamepadKeyboard?> = staticCompositionLocalOf { null }

/**
 * A keyboard made of buttons, for a player holding nothing but a pad.
 *
 * A phone has a keyboard of its own and a desk has a real one; a console, a Steam Deck in handheld
 * mode or a television with a pad plugged in has neither, and a game there still has to name a save
 * or a squad. This is that keyboard. It is a grid of ordinary buttons, walked with the d-pad by the
 * focus manager everything else uses, and each button sends its character to the field through the
 * same handlers a keyboard would.
 *
 * It opens for pad players only. A field that takes focus while the input source tracker
 * (`LocalInputSource`) says the player is on a pad opens it; a player with a mouse or a keyboard
 * never sees it. And it gets out of the way the moment the player shows it is not wanted: touching
 * the mouse or typing on a real keyboard closes it — the typing still reaches the field.
 *
 * The state is plain Compose state, so a game can open it itself for something that is not a
 * `TextField` by handing [open] its own [KeyboardTarget].
 *
 * @param layouts the pages, in the order the page key cycles through them. The first is where every
 *   opening starts.
 * @param openOnFocus whether a field opens it just by being focused from a pad. False leaves it to
 *   South on the focused field, for a form a player walks down and would rather not have a
 *   keyboard spring up on every box they pass.
 */
@Stable
class GamepadKeyboard(
    val layouts: List<GamepadKeyboardLayout> = GamepadKeyboardLayout.All,
    val openOnFocus: Boolean = true,
) {
    init {
        require(layouts.isNotEmpty()) { "a keyboard needs at least one layout" }
    }

    /** What it is typing into, or null while it is closed. */
    var target: KeyboardTarget? by mutableStateOf(null)
        private set

    val isOpen: Boolean get() = target != null

    var layout: GamepadKeyboardLayout by mutableStateOf(layouts.first())
        private set

    /** Whether the next letter is a capital. It lets go after one letter, as a phone's does. */
    var shifted: Boolean by mutableStateOf(false)
        private set

    /** The target's text as of the last key, as state, so the keyboard's line redraws. */
    var value: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    /** The device that opened it. Any other one showing up closes it. */
    internal var openedFrom: InputSource? = null
        private set

    /**
     * The target just closed, whose field is about to get focus back.
     *
     * Focus goes into the keyboard while it is open and comes back to the field when it closes, and
     * that return is not a player asking for a keyboard: without this the field would open it again
     * on the frame it closed.
     */
    private var returningTo: KeyboardTarget? = null

    /**
     * Opens over the screen, typing into [target].
     *
     * @param from the device the player is using, so that a different one appearing closes it
     *   again. Null for a keyboard that should stay up whatever the player picks up.
     */
    fun open(target: KeyboardTarget, from: InputSource? = InputSource.Gamepad) {
        this.target = target
        value = target.value
        layout = layouts.first()
        shifted = false
        openedFrom = from
        returningTo = null
    }

    /** Closes it. Focus goes back to whatever had it before, which is normally the field. */
    fun close() {
        val was = target ?: return
        target = null
        returningTo = was
    }

    /** Types [key], a capital if Shift is on. */
    fun type(key: String) {
        val into = target ?: return
        val text = if (shifted) key.uppercase() else key
        into.onText(TextEvent(text))
        if (shifted) shifted = false
        sync()
    }

    fun space() {
        target?.onText(TextEvent(" "))
        sync()
    }

    fun backspace() = press(Key.Backspace)

    fun left() = press(Key.Left)

    fun right() = press(Key.Right)

    /** A new line, in a field that has lines. Nothing anywhere else. */
    fun enter() {
        if (target?.multiline == true) press(Key.Enter)
    }

    fun toggleShift() {
        if (layout.shifts) shifted = !shifted
    }

    /** The next page of keys, round to the first after the last. */
    fun nextLayout() {
        layout = layouts[(layouts.indexOf(layout) + 1) % layouts.size]
        shifted = false
    }

    /** The page [nextLayout] would go to. What the page key says on it. */
    val nextLayoutLabel: String get() = layouts[(layouts.indexOf(layout) + 1) % layouts.size].label

    /**
     * The pad's shortcuts, while it is open: every console keyboard has them, and a player who has
     * used one reaches for them.
     *
     * West deletes, North is a space, the bumpers move the caret, pressing the left stick is Shift
     * and Start is done. The pad navigator has no use for any of these, so a game puts this in
     * front of it: `keyboard.onGamepad(event) || pad.onGamepad(event)`. Closed, it takes nothing.
     */
    fun onGamepad(event: GamepadEvent): Boolean {
        if (!isOpen || event !is GamepadEvent.ButtonDown) return false
        when (event.button) {
            GamepadButton.West -> backspace()
            GamepadButton.North -> space()
            GamepadButton.LeftBumper -> left()
            GamepadButton.RightBumper -> right()
            GamepadButton.LeftStick -> toggleShift()
            GamepadButton.Start -> close()
            else -> return false
        }
        return true
    }

    private fun press(key: Key) {
        target?.onKey(KeyEvent(key, KeyEventType.Down))
        sync()
    }

    /** Reads the target's text back. Called after every key, and by a field whose value changed. */
    internal fun sync() {
        target?.let { if (it.value != value) value = it.value }
    }

    /** Whether focus coming back to [target] is the keyboard closing, and forgets it if so. */
    internal fun isReturningTo(target: KeyboardTarget): Boolean {
        if (returningTo !== target) return false
        returningTo = null
        return true
    }

    /** [target] is leaving the screen, so a keyboard typing into it has nothing left to type into. */
    internal fun forget(target: KeyboardTarget) {
        if (this.target === target) this.target = null
        if (returningTo === target) returningTo = null
    }
}

/**
 * Gives every `TextField` inside it a pad keyboard, drawn along the bottom of the screen.
 *
 * ```kotlin
 * ProvideGamepadKeyboard {
 *     NameYourSave()
 * }
 * ```
 *
 * It fills its parent, like a `Dialog`, and for the same reason: the keyboard is laid over the
 * content, so this goes at the top of a screen, around everything that might hold a field.
 *
 * While it is open focus cannot leave it, B and Escape close it, and focus goes back to the field.
 *
 * Every part of it has a test tag, so a game's own test can drive it by name:
 * `gamepad-keyboard`, `gamepad-keyboard.line`, `gamepad-keyboard.key.<character>`, and
 * `gamepad-keyboard.` followed by `layout`, `shift`, `space`, `left`, `right`, `delete`, `enter`
 * or `done`.
 *
 * @param keyboard the state, for a game that wants to open it, close it or read it itself.
 * @param modifier for the keyboard's panel. Six hundred wide by default, which fits a 720p screen
 *   with room to see the field above it.
 * @param style the panel's skin name. The keys are `"button.key"`, a lit Shift is `"button.key.on"`
 *   and Done is `"button.primary"`, so a skin with no keyboard styles still draws buttons.
 */
@Composable
fun ProvideGamepadKeyboard(
    keyboard: GamepadKeyboard = remember { GamepadKeyboard() },
    modifier: Modifier = Modifier.width(DefaultKeyboardWidth),
    style: String = "panel.keyboard",
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGamepadKeyboard provides keyboard) {
        Box(Modifier.fillMaxSize()) {
            content()
            val target = keyboard.target
            if (target != null) {
                // Its own full-size layer, so the panel can sit at the bottom without the content
                // above being aligned anywhere it did not ask to be.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCentre) {
                    KeyboardPanel(keyboard, target, modifier, style)
                }
            }
        }
    }
}

/** How wide the keyboard is unless a game says otherwise. */
private const val DefaultKeyboardWidth = 600f

@Composable
private fun KeyboardPanel(keyboard: GamepadKeyboard, target: KeyboardTarget, modifier: Modifier, style: String) {
    OnBack { keyboard.close() }

    // A player who picks up the mouse or starts typing on a real keyboard does not want this one.
    val source = LocalInputSource.current
    val now = source.current
    LaunchedEffect(keyboard, now) {
        val from = keyboard.openedFrom
        if (from != null && now != from) keyboard.close()
    }

    // Text is delivered to the focused node only, and while this is open that is always a key. Each
    // key hands it on, so the letter that told the tracker a keyboard is in use is not lost.
    val passOn = remember(keyboard, target) {
        TextHandler { event -> target.onText(event).also { keyboard.sync() } }
    }

    val layout = keyboard.layout
    val shifted = keyboard.shifted

    Panel(modifier.focusTrap().testTag("gamepad-keyboard"), style = style) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
            KeyboardLine(keyboard.value, target.placeholder)

            layout.rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEachIndexed { column, key ->
                        Button(
                            text = if (shifted) key.uppercase() else key,
                            onClick = { keyboard.type(key) },
                            modifier = Modifier.weight(1f).onTextEvent(passOn).testTag("gamepad-keyboard.key.$key"),
                            style = "button.key",
                            // The top-left key, where a player's eye starts on every keyboard.
                            initialFocus = rowIndex == 0 && column == 0,
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                fun Modifier.key(name: String, share: Float) =
                    weight(share).onTextEvent(passOn).testTag("gamepad-keyboard.$name")

                Button(keyboard.nextLayoutLabel, { keyboard.nextLayout() }, Modifier.key("layout", 1.5f), style = "button.key")
                Button(
                    "SHIFT",
                    { keyboard.toggleShift() },
                    Modifier.key("shift", 1.5f),
                    style = if (shifted) "button.key.on" else "button.key",
                    // Not focusable on a page with no capitals, so a pad walks past it.
                    enabled = layout.shifts,
                )
                Button("SPACE", { keyboard.space() }, Modifier.key("space", 3f), style = "button.key")
                Button("<", { keyboard.left() }, Modifier.key("left", 1f), style = "button.key")
                Button(">", { keyboard.right() }, Modifier.key("right", 1f), style = "button.key")
                Button("DEL", { keyboard.backspace() }, Modifier.key("delete", 1.5f), style = "button.key")
                if (target.multiline) {
                    Button("ENTER", { keyboard.enter() }, Modifier.key("enter", 1.5f), style = "button.key")
                }
                Button("DONE", { keyboard.close() }, Modifier.key("done", 1.5f), style = "button.primary")
            }
        }
    }
}

/**
 * The text being typed, with a caret, along the top of the keyboard.
 *
 * The field itself may well be underneath the keyboard, so the player reads what they typed here.
 * The caret is a bar between the text before it and the text after it, rather than a character
 * inside one string, so moving it never changes what the line says.
 */
@Composable
private fun KeyboardLine(value: TextFieldValue, placeholder: String?) {
    val text = value.text
    val at = value.selection.end.coerceIn(0, text.length)
    Box(Modifier.fillMaxWidth().styled("field").testTag("gamepad-keyboard.line")) {
        Row(verticalAlignment = VerticalAlignment.Centre) {
            if (text.isEmpty() && placeholder != null) {
                Box(Modifier.size(CaretWidth, CaretHeight).styled("field.caret"))
                Text(placeholder, style = "field.placeholder")
            } else {
                if (at > 0) Text(text.substring(0, at))
                Box(Modifier.size(CaretWidth, CaretHeight).styled("field.caret"))
                if (at < text.length) Text(text.substring(at))
            }
        }
    }
}

private const val KeyGap = 4f
private const val CaretWidth = 2f
private const val CaretHeight = 18f
