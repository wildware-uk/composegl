package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.slideInRelative
import dev.wildware.composegl.ui.animation.slideOutRelative
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.TextHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.onTextEvent
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.rememberScrollState

/**
 * The console a game is poked with while it runs: type a command, read the log.
 *
 * ```kotlin
 * val console = rememberDevConsole {
 *     command("noclip") { player.collides = !player.collides }
 *     command("timescale", arg<Float>("scale")) { clocks.world.scale = it }
 *     command("give", arg<String>("item", suggest = { items.ids }), arg<Int>("count", default = 1)) { id, n ->
 *         give(id, n)
 *     }
 * }
 * console.log("Loaded level 3")
 * ```
 *
 * The commands are built once, the first time this is composed, so they should call through
 * something that stays put — a game's own state object. A screen that brings its own commands adds
 * them later with [DevConsoleState.define], and naming one twice replaces the first.
 *
 * @param history where the typed lines are kept between runs of the game. Null keeps them in memory
 *   for as long as the game is running; see [ConsoleHistoryStore] for keeping them longer.
 * @param maxLines how many lines the log holds before the oldest start to go.
 * @param commands the game's commands. See [ConsoleScope].
 */
@Composable
fun rememberDevConsole(
    history: ConsoleHistoryStore? = null,
    maxLines: Int = ConsoleDefaultLines,
    commands: ConsoleScope.() -> Unit,
): DevConsoleState = remember {
    DevConsoleState(history ?: ConsoleHistoryStore.inMemory(), maxLines).also { it.define(commands) }
}

/**
 * Draws [console]: a panel that slides down from the top of the screen with the log in it and a
 * prompt along the bottom.
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Game()
 *     DevConsole(console, toggleKey = Key.Grave)
 * }
 * ```
 *
 * Put it last on the screen, like the overlays: it fills whatever it is given and draws over
 * everything composed before it.
 *
 * What it does with the keyboard, which is most of what a console is:
 *
 * - **[toggleKey]** brings it down and puts it away, from wherever focus happens to be. It is a
 *   shortcut rather than an ordinary key, so a game whose own field has focus still gets its console
 *   — but a dialogue that traps focus keeps it, the same rule every shortcut follows.
 * - **Enter** runs the line. **Up** and **Down** walk back through what was typed before, which
 *   [ConsoleHistoryStore] can keep between runs.
 * - **Tab** fills in the word being typed — a command, or whatever the argument in that place
 *   suggests — as far as every choice agrees, then walks the list of them. **Shift+Tab** walks it
 *   backwards, and while a choice is picked out the arrows move along the list instead of through
 *   the history. **Escape** puts the list away, and closes the console once it is away.
 * - **PageUp** and **PageDown** scroll the log. Text in it can be selected with the pointer and
 *   copied with Ctrl+C, because the log is inside a `SelectionContainer` — which is how a stack
 *   trace gets into a bug report.
 * - **Everything else is eaten** while it is down, so typing `noclip` does not also make the player
 *   walk. The pad is not: only [togglePad] is taken, so a game driven by a pad carries on behind it.
 *
 * On a pad, [togglePad] is a chord — every button in it held at once — because there is no spare
 * button on a pad and a chord is not pressed by accident. A pad player types with the button
 * keyboard from `ProvideGamepadKeyboard`, if the game provides one, exactly as in any other field.
 *
 * Skin names, all under [style]: the panel itself, `"<style>.title"`, `"<style>.prompt"`,
 * `"<style>.line"` with one per level under it — `"<style>.line.warn"` — `"<style>.suggestion"` and
 * `"<style>.suggestion.selected"` for the list, and `"<style>.field"` for the two boxes typed into.
 *
 * @param heightFraction how much of the screen it covers when it is down.
 * @param toggleKey the key that brings it down. Null leaves opening it to the game.
 * @param togglePad the buttons held together to bring it down. Empty leaves the pad alone.
 * @param slideMillis how long the slide takes. Zero for a console that is simply there.
 */
@Composable
fun DevConsole(
    console: DevConsoleState,
    modifier: Modifier = Modifier,
    toggleKey: Key? = Key.Grave,
    togglePad: Set<GamepadButton> = DefaultPadChord,
    heightFraction: Float = 0.4f,
    style: String = "console",
    slideMillis: Int = 160,
) {
    // The one that opens it, offered to the shortcut layer wherever focus is. It is deliberately
    // only the toggle: everything else about the keyboard belongs to a console that is down.
    val toggle = remember(console, toggleKey) {
        KeyHandler { event -> event.isToggle(toggleKey).also { if (it) console.toggle() } }
    }
    val chord = remember(togglePad) { PadChord(togglePad) }
    val pad = remember(console, chord) {
        GamepadHandler { event -> chord.onGamepad(event).also { if (it) console.toggle() } }
    }

    Box(
        modifier
            .fillMaxSize()
            .onShortcutKey(toggle)
            .onShortcutGamepad(pad)
            .testTag(ConsoleTags.Root),
    ) {
        AnimatedVisibility(
            visible = console.isOpen,
            modifier = Modifier.align(Alignment.TopStart),
            enter = slideInRelative(Offset(0f, -1f), Tween(slideMillis, easing = Easings.EaseOut)),
            exit = slideOutRelative(Offset(0f, -1f), Tween(slideMillis, easing = Easings.EaseIn)),
            initiallyVisible = false,
        ) {
            ConsolePanel(console, style, heightFraction, toggleKey, pad)
        }
    }
}

/** Test tags on the console's own nodes, prefixed so they never meet a game's. */
object ConsoleTags {
    const val Root = "console:root"
    const val Panel = "console:panel"
    const val Log = "console:log"
    const val Prompt = "console:prompt"
    const val Filter = "console:filter"
    const val Suggestions = "console:suggestions"

    /** One line of the suggestion list, by its place in it. */
    fun suggestion(index: Int): String = "console:suggestion:$index"
}

/** How many lines the log holds unless a game says otherwise. */
const val ConsoleDefaultLines = 500

/**
 * The pad chord that brings the console down: Back and the right bumper together.
 *
 * Two buttons rather than one, and one of them a button a game rarely uses in play, because opening
 * a developer console by accident in the middle of a fight is worse than not having one.
 */
val DefaultPadChord: Set<GamepadButton> = setOf(GamepadButton.Back, GamepadButton.RightBumper)

/** The panel itself, composed only while the console is down or on its way out. */
@Composable
private fun ConsolePanel(
    console: DevConsoleState,
    style: String,
    heightFraction: Float,
    toggleKey: Key?,
    pad: GamepadHandler,
) {
    val scroll = rememberScrollState()

    // Asked after the prompt, because it is round it: a key that reaches here is one the prompt did
    // not want. The console eats it rather than letting the game behind act on a letter that was
    // meant for the prompt — which is the whole difference between a console and a chat box.
    val keys = remember(console, toggleKey) {
        KeyHandler { event ->
            when {
                event.isToggle(toggleKey) -> console.toggle()
                event.type == KeyEventType.Down && event.key == Key.Escape -> console.close()
            }
            true
        }
    }

    // A press on the console is the console's, whatever is under it. Scrolling is let through, so
    // the wheel still reaches the log inside.
    val presses = remember { PointerHandler { it is PointerEvent.Press || it is PointerEvent.Release } }

    Panel(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction.coerceIn(0.05f, 1f))
            .focusTrap(console.isOpen)
            .onKeyEvent(keys)
            .onGamepadEvent(pad)
            .onPointer(presses)
            .testTag(ConsoleTags.Panel),
        style = style,
    ) {
        // Round the lot rather than round the log alone, so Ctrl+C reaches it from the prompt, where
        // focus is. Only the log's own labels are selectable; everything else says it is not.
        SelectionContainer {
            Column(Modifier.fillMaxSize()) {
                DisableSelection { ConsoleHeader(console, style) }
                ConsoleLog(console, style, scroll, Modifier.fillMaxWidth().weight(1f))
                DisableSelection {
                    ConsoleSuggestions(console, style)
                    ConsolePrompt(console, style, scroll)
                }
            }
        }
    }
}

/** The title along the top, and the box that filters the log. */
@Composable
private fun ConsoleHeader(console: DevConsoleState, style: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text("CONSOLE", style = "$style.title")
        Row(horizontalArrangement = Arrangement.spacedBy(6f), verticalAlignment = VerticalAlignment.Centre) {
            Text("filter", style = "$style.title")
            TextField(
                value = console.filter,
                onValueChange = { console.filter = it },
                modifier = Modifier.width(FilterWidth).testTag(ConsoleTags.Filter),
                style = "$style.field",
                placeholder = "all",
            )
        }
    }
}

/**
 * The log: every line the filter lets through, oldest at the top.
 *
 * It follows the newest line, unless the player has scrolled back to read something — then it holds
 * still and lets the new lines pile up below, which is the difference between a log you can read and
 * one that keeps snatching itself away.
 */
@Composable
private fun ConsoleLog(
    console: DevConsoleState,
    style: String,
    scroll: ScrollState,
    modifier: Modifier,
) {
    LaunchedEffect(console.revision) {
        // Read before the frame: layout has not seen the new line yet, so these are still where the
        // player was looking when it arrived.
        val following = scroll.maxY - scroll.y <= StickySlack
        withFrameNanos { }
        if (following) scroll.scrollTo(y = scroll.maxY)
    }

    Box(modifier.testTag(ConsoleTags.Log)) {
        ScrollArea(Modifier.fillMaxSize(), scroll) {
            Column(Modifier.fillMaxWidth()) {
                console.visibleLines.forEach { line ->
                    Text(line.text, style = lineStyle(style, line.level))
                }
            }
        }
    }
}

/** What Tab is offering, over the prompt, with the one it has picked out marked. */
@Composable
private fun ConsoleSuggestions(console: DevConsoleState, style: String) {
    val options = console.suggestions
    if (options.isEmpty()) return
    val picked = console.highlighted

    Column(
        Modifier.fillMaxWidth().padding(bottom = 4f).testTag(ConsoleTags.Suggestions),
    ) {
        options.forEachIndexed { index, option ->
            val chosen = index == picked
            Text(
                option,
                modifier = Modifier
                    .fillMaxWidth()
                    // A click is the pointer's way of choosing one, for the hand already on the mouse.
                    .clickable { console.choose(index) }
                    .styled(if (chosen) "$style.suggestion.selected" else "$style.suggestion")
                    .testTag(ConsoleTags.suggestion(index)),
                style = if (chosen) "$style.suggestion.selected" else "$style.suggestion",
            )
        }
    }
}

/** The prompt: a `>` and somewhere to type, along the bottom of the panel. */
@Composable
private fun ConsolePrompt(console: DevConsoleState, style: String, scroll: ScrollState) {
    // Before the field's own handler, because the caller's modifier goes on first: Tab, the arrows
    // and Escape mean something to a console that they do not mean to a field.
    val keys = remember(console, scroll) {
        KeyHandler { event ->
            if (event.type != KeyEventType.Down) {
                false
            } else {
                when (event.key) {
                    Key.Tab -> consumed { console.completeNext(backwards = event.modifiers.shift) }
                    Key.Up -> consumed { console.previous() }
                    Key.Down -> consumed { console.next() }
                    Key.Escape -> consumed { if (!console.dismissSuggestions()) console.close() }
                    // Enter on a suggestion takes it and stops; the next one runs the line.
                    Key.Enter -> if (console.highlighted >= 0) consumed { console.dismissSuggestions() } else false
                    Key.PageUp -> consumed { scroll.scrollBy(dy = -pageOf(scroll)) }
                    Key.PageDown -> consumed { scroll.scrollBy(dy = pageOf(scroll)) }
                    else -> false
                }
            }
        }
    }

    // The key that closes the console is usually a key with a character on it, and a backend sends
    // the character straight after the key. By then the console is closed but the prompt still has
    // focus for this one frame, so without this a ` is left sitting in it for next time.
    val closing = remember(console) { TextHandler { !console.isOpen } }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text(">", style = "$style.prompt")
        TextField(
            value = console.input,
            onValueChange = console::onInput,
            modifier = Modifier.weight(1f).onKeyEvent(keys).onTextEvent(closing).testTag(ConsoleTags.Prompt),
            style = "$style.field",
            placeholder = "type help",
            // Off while it slides away, so the key that closed it cannot also leave a letter behind.
            enabled = console.isOpen,
            initialFocus = true,
            onSubmit = console::submit,
        )
    }
}

/** How far PageUp and PageDown move: nearly a screenful, keeping a line or two to read across. */
private fun pageOf(scroll: ScrollState): Float = (scroll.viewport.height * 0.9f).coerceAtLeast(1f)

/** Runs [action] and says the key was used, which is what every branch above wants. */
private inline fun consumed(action: () -> Unit): Boolean {
    action()
    return true
}

/** Whether this event is the key that opens and closes the console, with nothing held with it. */
private fun KeyEvent.isToggle(toggleKey: Key?): Boolean =
    toggleKey != null && type == KeyEventType.Down && !repeat && key == toggleKey && modifiers.none

/** The skin name for a line of the log: `console.line.warn` and the like. */
private fun lineStyle(style: String, level: ConsoleLevel): String = "$style.line." + level.name.lowercase()

/** How near the bottom counts as being at the bottom, in the interface's units. */
private const val StickySlack = 8f

/** How wide the filter box is. Wide enough for a word, narrow enough to leave the title room. */
private const val FilterWidth = 160f

/**
 * Several pad buttons held at once.
 *
 * A pad has no spare button, so the way to reach a developer tool from one is a chord nobody plays
 * by accident. It fires on the press that completes the set and not again until one is let go of,
 * so holding the chord does not open and close the console thirty times a second.
 */
internal class PadChord(private val buttons: Set<GamepadButton>) {

    private val held = mutableSetOf<GamepadButton>()
    private var fired = false

    /** True on the press that completes the chord, and only that one. */
    fun onGamepad(event: GamepadEvent): Boolean {
        if (buttons.isEmpty()) return false
        when (event) {
            is GamepadEvent.ButtonDown -> {
                if (event.button !in buttons) return false
                held += event.button
                if (!fired && held.containsAll(buttons)) {
                    fired = true
                    return true
                }
            }
            is GamepadEvent.ButtonUp -> {
                held -= event.button
                if (event.button in buttons) fired = false
            }
            // A pad taken away while the chord was held is a chord that is no longer held.
            is GamepadEvent.Disconnected -> {
                held.clear()
                fired = false
            }
            else -> Unit
        }
        return false
    }
}
