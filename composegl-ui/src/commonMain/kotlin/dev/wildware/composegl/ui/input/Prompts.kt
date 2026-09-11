package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.jvm.JvmInline

/**
 * Something the player can do, named by the game.
 *
 * A name rather than a key or a button, because that is the only thing that stays the same across
 * a keyboard, four makes of pad and a player who has rebound everything. "Confirm" is a fact about
 * the game; **E** and **A** and **✕** are facts about what is plugged in.
 */
@JvmInline
value class Action(val name: String) {

    companion object {
        val Confirm = Action("confirm")
        val Cancel = Action("cancel")
        val Menu = Action("menu")
        val Interact = Action("interact")
        val Map = Action("map")
        val Sprint = Action("sprint")
        val Crouch = Action("crouch")
        val Reload = Action("reload")
    }
}

/**
 * Which family of glyphs to draw.
 *
 * Not which pad exactly: the difference that matters to an interface is that the bottom button is
 * called **A** on one pad and **✕** on another, and the two Nintendo pads have A and B the other
 * way round from everybody else.
 */
enum class PromptStyle { Keyboard, Xbox, PlayStation, Nintendo, Touch }

/**
 * One glyph to draw.
 *
 * @param key what the skin can give art for, as a dotted name — `"pad.south"`, `"key.e"`. A skin
 *   with a button atlas names its regions after these and gets real buttons; a skin with none gets
 *   [label] in a box, which is readable everywhere and wrong nowhere.
 * @param label what to draw when there is no art.
 */
data class Prompt(val key: String, val label: String) {

    companion object {

        /**
         * What an action nobody has bound looks like.
         *
         * Visible on purpose. A blank prompt says "press nothing", which sends a player looking
         * through the settings for a binding they cannot see is missing.
         */
        val Unbound = Prompt("unbound", "—")
    }
}

/**
 * What each action looks like on each kind of device, and which kind is in the player's hands.
 *
 * One object for a whole game. The bindings are Compose state, so rebinding a key in the settings
 * changes every prompt on screen on the next frame, with nothing reloaded and no event delivered
 * to each of them — and so does picking up a pad, because [InputSourceTracker.current] is state
 * too.
 *
 * What is here by default is the ordinary set: confirm, cancel, a menu button. Everything else is
 * the game's to [bind], which is also how a rebinding screen writes its result.
 *
 * @param padStyle which pad's glyphs to draw. A game sets it from the pad's name when one is
 *   plugged in; there is no reliable way to ask, so it is a decision rather than a detection.
 */
@Stable
class Prompts(padStyle: PromptStyle = PromptStyle.Xbox) {

    var padStyle: PromptStyle by mutableStateOf(padStyle)

    private val bound = mutableStateMapOf<Binding, Prompt>()

    /** Binds [action] on [style] to a glyph. What a rebinding screen calls when the player is done. */
    fun bind(action: Action, style: PromptStyle, prompt: Prompt) {
        bound[Binding(style, action)] = prompt
    }

    /** The same, for every device at once. For a game whose prompts are the same everywhere. */
    fun bind(action: Action, prompt: Prompt) {
        PromptStyle.entries.forEach { bind(action, it, prompt) }
    }

    /** Back to the default, or to nothing at all when there was no default. */
    fun unbind(action: Action, style: PromptStyle) {
        bound.remove(Binding(style, action))
    }

    /**
     * What to draw for [action] on [style].
     *
     * A binding the game made wins over the default, and [Prompt.Unbound] is what comes back when
     * there is neither — never null, because every caller of this is drawing something.
     */
    fun prompt(action: Action, style: PromptStyle): Prompt =
        bound[Binding(style, action)] ?: Defaults[Binding(style, action)] ?: Prompt.Unbound

    /** Whether anything, default or bound, knows what this action looks like. */
    fun isBound(action: Action, style: PromptStyle): Boolean =
        bound.containsKey(Binding(style, action)) || Defaults.containsKey(Binding(style, action))

    /** Which glyphs suit what the player is using now. */
    fun styleFor(source: InputSource): PromptStyle = when (source) {
        InputSource.Gamepad -> padStyle
        InputSource.Touch -> PromptStyle.Touch
        InputSource.Mouse, InputSource.Keyboard -> PromptStyle.Keyboard
    }

    private data class Binding(val style: PromptStyle, val action: Action)

    private companion object {

        /**
         * The prompts every game has, on every device this knows about.
         *
         * Nintendo's A and B are where everybody else's B and A are, which is the single most
         * common mistake in a prompt table and the reason this one is written out in full rather
         * than derived from the Xbox one.
         */
        val Defaults: Map<Binding, Prompt> = buildMap {
            fun put(action: Action, style: PromptStyle, key: String, label: String) {
                put(Binding(style, action), Prompt(key, label))
            }

            put(Action.Confirm, PromptStyle.Keyboard, "key.e", "E")
            put(Action.Confirm, PromptStyle.Xbox, "pad.south", "A")
            put(Action.Confirm, PromptStyle.PlayStation, "pad.south", "✕")
            put(Action.Confirm, PromptStyle.Nintendo, "pad.east", "A")
            put(Action.Confirm, PromptStyle.Touch, "touch.tap", "TAP")

            put(Action.Cancel, PromptStyle.Keyboard, "key.escape", "ESC")
            put(Action.Cancel, PromptStyle.Xbox, "pad.east", "B")
            put(Action.Cancel, PromptStyle.PlayStation, "pad.east", "○")
            put(Action.Cancel, PromptStyle.Nintendo, "pad.south", "B")
            put(Action.Cancel, PromptStyle.Touch, "touch.back", "BACK")

            put(Action.Menu, PromptStyle.Keyboard, "key.tab", "TAB")
            put(Action.Menu, PromptStyle.Xbox, "pad.start", "☰")
            put(Action.Menu, PromptStyle.PlayStation, "pad.start", "☰")
            put(Action.Menu, PromptStyle.Nintendo, "pad.start", "+")
            put(Action.Menu, PromptStyle.Touch, "touch.menu", "MENU")

            put(Action.Interact, PromptStyle.Keyboard, "key.f", "F")
            put(Action.Interact, PromptStyle.Xbox, "pad.west", "X")
            put(Action.Interact, PromptStyle.PlayStation, "pad.west", "□")
            put(Action.Interact, PromptStyle.Nintendo, "pad.north", "X")

            put(Action.Map, PromptStyle.Keyboard, "key.m", "M")
            put(Action.Map, PromptStyle.Xbox, "pad.north", "Y")
            put(Action.Map, PromptStyle.PlayStation, "pad.north", "△")
            put(Action.Map, PromptStyle.Nintendo, "pad.west", "Y")
        }
    }
}
