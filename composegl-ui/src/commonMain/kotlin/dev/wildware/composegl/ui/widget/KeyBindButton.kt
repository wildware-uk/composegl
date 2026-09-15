package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PromptStyle
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * Whether a [KeyBindButton] is waiting for a press.
 *
 * Held outside the widget for the screens that want to say something while it waits — "Esc to
 * cancel" under the list — or to open a controls screen already listening for the first action.
 */
@Stable
class KeyBindState {

    var isListening: Boolean by mutableStateOf(false)
        private set

    /** Starts waiting for the next press. What clicking the button does. */
    fun listen() {
        isListening = true
    }

    /** Stops waiting, and nothing is bound. What the cancel key does. */
    fun cancel() {
        isListening = false
    }

    internal fun bound() {
        isListening = false
    }
}

/**
 * A button that shows what an action is bound to and, once pressed, takes the next key, mouse
 * button or pad button as the new binding.
 *
 * ```kotlin
 * KeyBindButton(
 *     binding = binds[Jump],
 *     onBind = { input -> binds[Jump] = input },
 *     cancelKey = Key.Escape,
 * )
 * ```
 *
 * Click it, press Enter on it, or press South on it, and it says [listeningText]. The next press
 * of anything is the answer, and while it waits nothing else hears it: an arrow key or the d-pad
 * is bound rather than moving focus, East is bound rather than going back, Enter is bound rather
 * than pressing the button again. The press that answers is kept whole — its release and its key
 * repeats go nowhere either — so binding Enter or South does not start it listening again.
 *
 * [cancelKey] and [cancelButton] give up and leave the binding as it was, which means those two are
 * the only things this cannot bind. Focus leaving the button gives up too: a player who pushed the
 * stick or clicked something else has moved on.
 *
 * A mouse button is heard when it is pressed on the button, which is where the cursor already is
 * when a mouse player clicked it to start. Sticks and triggers are not bindings and pass straight
 * through.
 *
 * It decides nothing about clashes. [onBind] is handed every press [accepts] allowed, including one
 * that another action already has; [clashesWith][dev.wildware.composegl.ui.input.clashesWith]
 * finds who, and what to do about it is the game's.
 *
 * @param binding what is bound now, or null for nothing, which draws a dash.
 * @param onBind the press the player chose.
 * @param accepts return false to ignore a press and keep listening — a game with no mouse bindings
 *   refuses [InputBinding.Mouse] here.
 * @param style the skin style while showing the binding. While listening it is
 *   [listeningStyle], `"<style>.listening"` by default, which falls back to [style] in a skin that
 *   has no such thing.
 * @param state pass one in to read or start listening from outside.
 */
@Composable
fun KeyBindButton(
    binding: InputBinding?,
    onBind: (InputBinding) -> Unit,
    modifier: Modifier = Modifier,
    cancelKey: Key = Key.Escape,
    cancelButton: GamepadButton = GamepadButton.Back,
    accepts: (InputBinding) -> Boolean = { true },
    listeningText: String = "PRESS A KEY",
    style: String = "button",
    listeningStyle: String = "$style.listening",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    state: KeyBindState = remember { KeyBindState() },
) {
    val listening = state.isListening && enabled
    val resolved = rememberStyle(if (listening) listeningStyle else style, rememberStates(interaction, enabled))

    val capture = remember(state) { BindCapture(state) }
    capture.cancelKey = cancelKey
    capture.cancelButton = cancelButton
    capture.accepts = accepts
    capture.onBind = onBind
    capture.enabled = enabled

    // Focus going anywhere else is a player who has moved on, and a button left listening off to
    // one side would take the next press meant for something else. Going, not absent: a screen
    // that opens already listening, before focus has arrived, has not been walked away from.
    val focused = interaction.isFocused
    LaunchedEffect(focused, enabled) {
        if (!enabled || (!focused && capture.hadFocus)) state.cancel()
        capture.hadFocus = focused
    }

    val prompts = LocalPrompts.current
    val label = when {
        listening -> listeningText
        binding == null -> "—"
        binding is InputBinding.Gamepad -> binding.prompt(prompts.padStyle).label
        else -> binding.prompt(PromptStyle.Keyboard).label
    }

    val keys = remember(capture) { KeyHandler { capture.key(it) } }
    val pad = remember(capture) { GamepadHandler { capture.pad(it) } }
    val pointer = remember(capture) { PointerHandler { capture.pointer(it) } }
    val click = remember(capture) { { capture.click() } }

    Box(
        modifier = modifier
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .onPointer(pointer)
            .clickable(enabled = enabled, onClick = click)
            .onKeyEvent(keys)
            .onGamepadEvent(pad)
            .styled(resolved),
        contentAlignment = Alignment.Centre,
    ) {
        ProvideContentStyle(resolved) { Text(label) }
    }
}

/**
 * What the button does with a press, outside composition.
 *
 * The whole difficulty is the other half of the press that answered. Binding happens on the way
 * down, so the release is still to come, and a release is a click to a button: Enter coming up or
 * South coming up or the mouse coming up would start it listening straight away again. So the
 * answering key, pad button and mouse press are remembered until they come up, and swallowed.
 */
private class BindCapture(private val state: KeyBindState) {

    var cancelKey: Key = Key.Escape
    var cancelButton: GamepadButton = GamepadButton.Back
    var accepts: (InputBinding) -> Boolean = { true }
    var onBind: (InputBinding) -> Unit = {}
    var enabled = true

    /** Whether focus was on the button last time it was composed, so focus leaving can be told apart. */
    var hadFocus = false

    private val heldKeys = mutableSetOf<Key>()
    private val heldButtons = mutableSetOf<GamepadButton>()

    /** The mouse press that answered, whose click is still on its way. */
    private var swallowClick = false

    private val listening get() = state.isListening && enabled

    fun click() {
        if (swallowClick) {
            swallowClick = false
            return
        }
        state.listen()
    }

    fun key(event: KeyEvent): Boolean {
        // Only an up whose down was ours, as with the pad: Enter still held from the press that
        // started this is the navigator's to let go of.
        if (event.type == KeyEventType.Up) return heldKeys.remove(event.key)
        // A repeat is the answering key still held. A fresh press of it means its release went to
        // whatever focus moved on to, and this is a new press, not the old one.
        if (event.key in heldKeys) {
            if (event.repeat) return true
            heldKeys -= event.key
        }
        if (!listening) return false
        if (event.repeat) return true

        heldKeys += event.key
        if (event.key == cancelKey) state.cancel() else offer(InputBinding.Keyboard(event.key))
        return true
    }

    fun pad(event: GamepadEvent): Boolean = when (event) {
        // A pad does not repeat, so a button coming down that is already held had its release go
        // to whatever focus moved on to. It is a new press either way.
        is GamepadEvent.ButtonDown -> {
            heldButtons -= event.button
            if (!listening) {
                false
            } else {
                heldButtons += event.button
                if (event.button == cancelButton) state.cancel() else offer(InputBinding.Gamepad(event.button))
                true
            }
        }
        // Only an up whose down was ours. A d-pad already held when listening began is the
        // navigator's to let go of, or it goes on stepping focus for ever.
        is GamepadEvent.ButtonUp -> heldButtons.remove(event.button)
        else -> false
    }

    fun pointer(event: PointerEvent): Boolean {
        if (event !is PointerEvent.Press) return false
        if (!listening) {
            // A fresh press on a button not waiting for anything: whatever an earlier answer left
            // behind belonged to a gesture that ended somewhere else.
            swallowClick = false
            return false
        }
        if (offer(InputBinding.Mouse(event.button))) swallowClick = true
        return true
    }

    private fun offer(binding: InputBinding): Boolean {
        if (!accepts(binding)) return false
        state.bound()
        onBind(binding)
        return true
    }
}
