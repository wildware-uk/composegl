package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.focus.focusOnNode
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.LocalLocale
import dev.wildware.composegl.ui.text.LocalStrings
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.MenuScope
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.widget.rememberLazyListState

// --- what a chat is made of -------------------------------------------------------------------

/**
 * One place messages go: Say, Team, Party, a guild, a raid.
 *
 * A channel is the game's, not the widget's — a game knows what it has and what they are called in
 * the player's language. All the widget does with one is draw its tab, colour the lines that came
 * from it, and recognise its [prefix] at the start of a typed line.
 *
 * ```kotlin
 * val Team = ChatChannel("team", "Team", prefix = "/t", style = "chat.team")
 * ```
 *
 * @param id what the game calls this channel. Two channels with the same id are the same channel.
 * @param name what the player reads on the tab, already in their language.
 * @param prefix what typing it at the start of a line means "send this here": `/t`. A line that is
 *   only the prefix switches to the channel instead of saying anything in it. Null for a channel
 *   with no shorthand.
 * @param style the skin style its messages are drawn in, which is where a channel's colour comes
 *   from. Null draws them in the box's own `"<style>.message"`.
 */
data class ChatChannel(
    val id: String,
    val name: String,
    val prefix: String? = null,
    val style: String? = null,
) {

    companion object {

        /** The channel a game that has named none of its own talks in. */
        val Say: ChatChannel = ChatChannel("say", "Say")
    }
}

/**
 * One thing somebody said.
 *
 * **Identity is the object, not the words.** Two players saying "ready" are two messages, and the
 * second does not replace the first, so a game builds a message where it receives one rather than
 * rebuilding it every frame.
 *
 * A message with no [from] is a **system message** — "Mira has joined", "you cannot talk here" —
 * and is drawn in `"<style>.system"` rather than with a name in front of it.
 *
 * @param text what was said.
 * @param from who said it, or null for something the game itself said.
 * @param channel which channel it came in on, or null for a system message that belongs to none.
 * @param style a skin style for this one message, beating the channel's. For the one line that has
 *   to stand out: a whisper, a warning, a kill.
 * @param runs the parts of [text] that look different from the rest — a link, a term, an item name
 *   in its rarity colour.
 * @param tag whatever the game wants back when a player clicks the name: an account id, a handle,
 *   the object for the player who said it. The widget only carries it.
 */
class ChatMessage(
    val text: String,
    val from: String? = null,
    val channel: ChatChannel? = null,
    val style: String? = null,
    val runs: List<TextRun> = emptyList(),
    val tag: Any? = null,
)

/**
 * The chat itself: what has been said, what is still on screen, and what the player is typing.
 *
 * ```kotlin
 * val chat = rememberChatState(maxMessages = 200)
 * ChatBox(chat, channels = listOf(All, Team, Party), onSend = { channel, text -> net.send(channel, text) })
 * // when the server says something arrived:
 * chat.receive(ChatMessage("on my way", from = "Mira", channel = Team))
 * ```
 *
 * What the player types is **not** put in the log by the box. The game puts it there when its
 * server says the message went out, which is what stops a line appearing twice and what makes a
 * message that was refused not appear at all.
 *
 * Nothing here costs anything while the chat is quiet: with the box closed and the last few lines
 * faded there is no line to compose, no animation to run and no frame to ask for.
 *
 * @param maxMessages how many lines the log keeps. Past it the oldest go.
 * @param idleLines how many of the newest lines show over the HUD while the box is closed.
 * @param idleMillis how long one of those stays up before it starts to fade.
 * @param fadeMillis how long it takes to go.
 * @param sentLimit how many of the player's own lines Up and Down walk back through.
 * @param clock which clock the fading runs on. The interface's by default, so chat still fades
 *   behind a pause menu — a game that wants it to wait for the world passes [Clock.World].
 */
@Stable
class ChatState(
    val maxMessages: Int = 200,
    val idleLines: Int = 6,
    val idleMillis: Int = 8_000,
    val fadeMillis: Int = 400,
    val sentLimit: Int = 30,
    val clock: Clock = Clock.Ui,
) {

    private val log = mutableStateListOf<ChatMessage>()
    private val live = mutableStateListOf<ChatMessage>()
    private val typed = mutableStateListOf<String>()

    /** Everything said, oldest first. What the open box shows. */
    val messages: List<ChatMessage> get() = log

    /** The newest few, still up over the HUD while the box is closed. They fade and then leave. */
    val recent: List<ChatMessage> get() = live

    /** The player's own lines, oldest last. Up and Down at the input walk back through these. */
    val sent: List<String> get() = typed

    /** Whether the box is open: a full history, and somewhere to type with focus in it. */
    var isOpen: Boolean by mutableStateOf(false)
        private set

    /**
     * Which channel the player is speaking in. The tabs write it, a prefix on its own line writes
     * it, and a game may write it — [open] takes one, to open the box straight into a whisper.
     *
     * **It has to be one of the channels [ChatBox] was given.** The box keeps it among them, so a
     * channel that goes away — a party that disbanded — leaves the player speaking in the first one
     * that is left rather than into nothing; and a channel that was never on the list is put back
     * the same way on the next frame. A game opening straight into a whisper passes that whisper
     * channel in `channels` too.
     */
    var channel: ChatChannel by mutableStateOf(ChatChannel.Say)

    /** Nothing on screen and nothing being typed, which is when the whole thing costs nothing. */
    val isIdle: Boolean get() = !isOpen && live.isEmpty()

    /**
     * Goes up by one every time a line arrives or the log is emptied.
     *
     * What the open box watches to follow the newest line. The size of the log is not enough: a
     * chat at its limit drops one line for every line it gains, so a busy channel would sit at
     * exactly [maxMessages] for ever and a view watching the size would stop following it.
     */
    internal var revision: Int by mutableIntStateOf(0)
        private set

    /** What is in the input, with the caret. The field writes it; the history rewrites it. */
    internal var draft: TextFieldValue by mutableStateOf(TextFieldValue())

    /** Where Up and Down are in [sent]: -1 is the line being typed now. */
    private var walk = -1

    /** What was half-typed before Up went into the history, so Down brings it back. */
    private var held = ""

    // --- what arrives ---------------------------------------------------------------------------

    /** Something somebody said. It goes into the log, and up over the HUD for a few seconds. */
    fun receive(message: ChatMessage): ChatMessage {
        log += message
        while (log.size > maxMessages) log.removeAt(0)
        revision++
        live += message
        // The oldest lingering line goes rather than the newest: a player glancing at the corner
        // wants the last thing that was said, not the first.
        while (live.size > idleLines) live.removeAt(0)
        return message
    }

    /** The same, for the common case of a line with a name on it. */
    fun receive(text: String, from: String? = null, channel: ChatChannel? = null): ChatMessage =
        receive(ChatMessage(text, from, channel))

    /** Something the game said rather than a player: drawn with no name, in its own colour. */
    fun system(text: String): ChatMessage = receive(ChatMessage(text))

    // --- being open -----------------------------------------------------------------------------

    /** Opens it, in [channel]. What the open key does, and what a game's own "reply" button does. */
    fun open(channel: ChatChannel = this.channel) {
        this.channel = channel
        isOpen = true
    }

    /**
     * Closes it. Half a line left in the input is still there when it comes back, and the lines
     * that were up linger over the HUD and fade the way a new one does.
     */
    fun close() {
        isOpen = false
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    /** Everything gone: a new game, a new server, a player leaving a group. */
    fun clear() {
        log.clear()
        live.clear()
        revision++
    }

    /** Called by a line once it has finished fading over the HUD. */
    internal fun retire(message: ChatMessage) {
        live.remove(message)
    }

    // --- the input --------------------------------------------------------------------------------

    /**
     * Sends what is in the input, to the channel its prefix names or the one being spoken in.
     *
     * A line that is **only** a prefix is the player choosing a channel rather than saying anything
     * in it, so it switches and sends nothing. A prefixed line is sent to that channel without
     * changing which one the player is speaking in: `/t on my way` is one message to the team, not
     * a move to the team channel.
     *
     * @return whether the line only moved the player to another channel. The box stays open for
     *   that: the player said where they want to talk and has not said anything there yet.
     */
    internal fun send(channels: List<ChatChannel>, onSend: (ChatChannel, String) -> Unit): Boolean {
        val routed = route(draft.text, channels)
        setDraft("")
        walk = -1
        held = ""
        if (routed.body.isEmpty()) {
            val moved = routed.channel != null
            routed.channel?.let { channel = it }
            return moved
        }
        if (typed.lastOrNull() != routed.body) {
            typed += routed.body
            while (typed.size > sentLimit) typed.removeAt(0)
        }
        onSend(routed.channel ?: channel, routed.body)
        return false
    }

    /** Up: one further back through what the player has sent. */
    internal fun earlier() {
        if (typed.isEmpty()) return
        if (walk < 0) {
            held = draft.text
            walk = typed.size
        }
        if (walk <= 0) return
        walk--
        setDraft(typed[walk])
    }

    /** Down: back towards the line that was being typed when Up was first pressed. */
    internal fun later() {
        if (walk < 0) return
        walk++
        if (walk >= typed.size) {
            walk = -1
            setDraft(held)
        } else {
            setDraft(typed[walk])
        }
    }

    /** The field changed the text itself, which ends whatever walk through the history was going on. */
    internal fun onInput(value: TextFieldValue) {
        if (value.text != draft.text) walk = -1
        draft = value
    }

    private fun setDraft(text: String) {
        draft = TextFieldValue(text, TextRange(text.length))
    }
}

/** A chat that lives as long as the screen it is on. */
@Composable
fun rememberChatState(
    maxMessages: Int = 200,
    idleLines: Int = 6,
    idleMillis: Int = 8_000,
    fadeMillis: Int = 400,
    sentLimit: Int = 30,
    clock: Clock = Clock.Ui,
): ChatState = remember(maxMessages, idleLines, idleMillis, fadeMillis, sentLimit, clock) {
    ChatState(maxMessages, idleLines, idleMillis, fadeMillis, sentLimit, clock)
}

// --- the box ------------------------------------------------------------------------------------

/**
 * In-game chat: the last few lines over the HUD, and a history with somewhere to type when it is open.
 *
 * ```kotlin
 * val chat = rememberChatState()
 * ChatBox(
 *     state = chat,
 *     modifier = Modifier.align(Alignment.BottomStart).padding(16f),
 *     channels = listOf(All, Team, Party),
 *     onSend = { channel, text -> net.send(channel, text) },
 *     openKey = Key.Enter,
 *     width = 420f,
 * )
 * ```
 *
 * Where it goes is the game's — this is a column, and a game puts it in the corner it wants.
 *
 * **Closed**, it is the newest few lines drawn over whatever is behind them. Each holds for
 * [ChatState.idleMillis], fades, and goes; with nothing up it draws nothing and asks for no frames,
 * so a quiet chat costs a game nothing at all.
 *
 * **Open**, it is a panel: the channel tabs, the whole history scrolled to the newest line, and an
 * input with focus already in it. The history follows the newest line **unless the player has
 * scrolled back to read something**, which is the difference between a log you can read and one
 * that keeps snatching itself away.
 *
 * What the keyboard does:
 *
 * - **[openKey]** opens it from wherever focus happens to be — a shortcut, so a game's own field
 *   still gets its chat, and a dialogue that traps focus still keeps it.
 * - **Enter** sends the line and, unless [closeOnSend] says otherwise, closes the box: one key in,
 *   one key out, which is how a player types between fights. Enter on an empty box just closes it,
 *   and Enter on a line that is only a channel's prefix moves to that channel and stays open.
 * - **Up** and **Down** walk back through what the player has sent. **PageUp** and **PageDown**
 *   scroll the history. **Escape** and Back close it.
 * - **Everything else is eaten while it is open**, so typing `wait` does not also make the player
 *   walk forward. Tab and the arrows are not: they are how focus reaches the tabs, and focus
 *   cannot leave the box while it is open anyway.
 *
 * On a pad, **[openButton]** opens it and the bumpers walk the channels; East closes it through the
 * game's `BackStack`. A pad player types with the button keyboard from `ProvideGamepadKeyboard` if
 * the game provides one, exactly as in any other field — and on a phone the on-screen keyboard and
 * the input method come up with it, because what is typed into is an ordinary
 * [TextField][dev.wildware.composegl.ui.widget.TextField].
 *
 * **Channels** are chosen by clicking a tab or by typing a prefix: `/t` on its own switches to the
 * team channel, and `/t on my way` sends one line there without leaving the channel the player was
 * in. Each channel's messages are drawn in its own style, which is where its colour comes from.
 *
 * **Names are clickable** while the box is open and the game has handed in [nameMenu] or [onName]:
 * the menu is an ordinary context menu, so it opens on right-click, on a long press, on Shift+F10
 * and on the pad, and it is written in the same scope a `MenuBar`'s menus are. A line fading over
 * the HUD is not clickable — it is half gone, and aiming at it is not a thing a player can do.
 *
 * ```kotlin
 * // Whisper is in `channels` as well: [ChatState.open] only holds for a channel the box was given.
 * nameMenu = { message ->
 *     Item("Whisper") { chat.open(Whisper) }
 *     Item("Mute") { mute(message.tag) }
 *     Separator()
 *     Item("Report") { report(message.tag) }
 * }
 * ```
 *
 * **Right to left** is the layout's: in Arabic the tabs start on the right and the name sits to the
 * right of the words it is in front of. A line that mixes Hebrew and English reads by its own first
 * letter rather than by the screen's direction, so the same line reads the same way on either — the
 * whole of that is an ordinary [Text][dev.wildware.composegl.ui.widget.Text]'s doing.
 *
 * Skin names, all under [style]: the panel itself, `"<style>.message"` for a line, `"<style>.system"`
 * for one nobody said, `"<style>.name"` for the name in front of it, `"<style>.channel"` for the
 * channel tag and the label by the input, `"<style>.tab"` and `"<style>.tab.selected"` for the tabs,
 * and `"<style>.field"` — with `.placeholder`, `.caret`, `.selection` and `.composition` under it —
 * for the input.
 *
 * @param channels what the player may talk in, in the order their tabs are drawn. One channel draws
 *   no tabs at all.
 * @param onSend called with the channel and the line when the player presses Enter. The game sends
 *   it; nothing is written into the log here.
 * @param openKey the key that opens it, or null for a game that opens it its own way.
 * @param openButton the pad button that opens it, or null for none. It only opens: while the box is
 *   open the pad belongs to what is in it.
 * @param width how wide the box is, or zero to be as wide as it is given. Lines wrap inside it.
 * @param historyHeight how tall the scrolling history is when the box is open.
 * @param placeholder the hint in the empty input. Null looks up `chat.say` in the game's
 *   [Strings][dev.wildware.composegl.ui.text.Strings] and falls back to English.
 * @param maxLength how long a line may be, or zero for no limit. Most servers have one.
 * @param closeOnSend whether sending closes the box. Off for a chat the player leaves open.
 * @param show which messages this box draws — the open log **and** the lines fading over the HUD,
 *   so a box narrowed to one channel is narrowed to it whether it is open or shut. For a game whose
 *   tabs filter the log. Null draws all of them, which is what a single log with a colour per
 *   channel wants.
 * @param nameMenu the menu that opens on a name: whisper, mute, report. Null and names are not
 *   clickable.
 * @param onName called when a name is clicked, for a game that wants a click to do something on its
 *   own — open a profile, target the player.
 */
@Suppress("LongParameterList")
@Composable
fun ChatBox(
    state: ChatState,
    modifier: Modifier = Modifier,
    channels: List<ChatChannel> = listOf(ChatChannel.Say),
    onSend: (ChatChannel, String) -> Unit = { _, _ -> },
    openKey: Key? = Key.Enter,
    openButton: GamepadButton? = GamepadButton.North,
    width: Float = 0f,
    historyHeight: Float = 180f,
    placeholder: String? = null,
    maxLength: Int = 0,
    closeOnSend: Boolean = true,
    style: String = "chat",
    show: ((ChatMessage) -> Boolean)? = null,
    nameMenu: (MenuScope.(ChatMessage) -> Unit)? = null,
    onName: ((ChatMessage) -> Unit)? = null,
) {
    // The channel being spoken in has to be one of the ones on offer. A party that disbanded takes
    // its channel with it, and a player left speaking into a channel with no tab has no way back.
    val speaking = channels.firstOrNull { it == state.channel } ?: channels.firstOrNull() ?: ChatChannel.Say
    SideEffect { if (state.channel != speaking) state.channel = speaking }

    // Only the key that opens it: everything else about the keyboard belongs to a box that is open.
    // Offered to the shortcut layer, so it works wherever focus is.
    val open = remember(state, openKey) {
        KeyHandler { event -> event.opens(openKey).also { if (it) state.open() } }
    }
    val pad = remember(state, openButton) {
        GamepadHandler { event ->
            (!state.isOpen && openButton != null && event is GamepadEvent.ButtonDown && event.button == openButton)
                .also { if (it) state.open() }
        }
    }

    var box = modifier.onShortcutKey(open).onShortcutGamepad(pad).testTag(ChatTags.Root)
    if (width > 0f) box = box.width(width)

    Column(box) {
        if (state.isOpen) {
            ChatPanel(
                state, channels, onSend, style, historyHeight, placeholder,
                maxLength, closeOnSend, show, nameMenu, onName,
            )
        } else {
            IdleLines(state, style, channels.size > 1, show)
        }
    }
}

/** Test tags on the chat's own nodes, prefixed so they never meet a game's. */
object ChatTags {
    const val Root = "chat:root"
    const val Panel = "chat:panel"
    const val Log = "chat:log"
    const val Input = "chat:input"

    /** One channel tab, by the channel's id. */
    fun tab(id: String): String = "chat:tab:$id"

    /** The name in front of a line, by who said it. */
    fun name(from: String): String = "chat:name:$from"

    /** What was said on a line, by the words themselves. */
    fun said(text: String): String = "chat:said:$text"
}

/**
 * The newest few lines over the HUD, each fading out in its own time.
 *
 * Nothing at all while the chat is quiet: an empty column composes nothing and asks for no frames,
 * which is what lets a game leave this on the screen for the whole of a match.
 *
 * Nothing here is clickable, and nothing here is somewhere focus can go. A line half faded is not
 * something to aim at, and a HUD whose Tab key walks through chat lines that are leaving is worse
 * than one with no chat at all. Opening the box is what makes the names live.
 *
 * [show] filters these the way it filters the open log: a box narrowed to one channel is narrowed
 * to it whether it is open or shut, and a game does not get the other channels fading over its HUD.
 */
@Composable
private fun IdleLines(
    state: ChatState,
    style: String,
    showChannel: Boolean,
    show: ((ChatMessage) -> Boolean)?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2f)) {
        state.recent.forEach { message ->
            key(message) {
                if (show == null || show(message)) {
                    FadingLine(state, message, style, showChannel)
                } else {
                    // A line nothing draws still has to leave: retiring is a fading line's own job,
                    // and one that never fades would sit among the lingering lines for ever and a
                    // chat that should have gone quiet never would.
                    LaunchedEffect(message) { state.retire(message) }
                }
            }
        }
    }
}

/** One line over the HUD: up, held, faded, gone. Its own animation, because each has its own timing. */
@Composable
private fun FadingLine(state: ChatState, message: ChatMessage, style: String, showChannel: Boolean) {
    val clocks = LocalClocks.current
    val fade = remember(clocks, state.clock) { Animatable(1f, FloatVectoriser, state.clock, clocks) }

    // The hold is the clock's, so a chat on Clock.World waits behind a pause menu instead of
    // running out behind it. It starts again whenever the box closes, which is what makes the last
    // few lines linger after a player has finished reading them.
    LaunchedEffect(message) {
        clocks.wait(state.clock, state.idleMillis)
        fade.animateTo(0f, Tween(state.fadeMillis, easing = Easings.EaseIn))
        state.retire(message)
    }

    Box(Modifier.fillMaxWidth().alpha(fade.value)) {
        ChatLine(message, style, showChannel, nameMenu = null, onName = null)
    }
}

/** The open box: the tabs, the whole history, and somewhere to type. */
@Suppress("LongParameterList")
@Composable
private fun ChatPanel(
    state: ChatState,
    channels: List<ChatChannel>,
    onSend: (ChatChannel, String) -> Unit,
    style: String,
    historyHeight: Float,
    placeholder: String?,
    maxLength: Int,
    closeOnSend: Boolean,
    show: ((ChatMessage) -> Boolean)?,
    nameMenu: (MenuScope.(ChatMessage) -> Unit)?,
    onName: ((ChatMessage) -> Unit)?,
) {
    val shown = if (show == null) state.messages else state.messages.filter(show)
    val list = rememberLazyListState()

    // A click on a tab is a player choosing what to say next, not a player leaving the box, so
    // focus goes straight back to where they are typing. Without this the next letter goes into
    // the tab they pressed, which means nowhere.
    val caret = remember { InputFocus() }

    // Back is the pad's East and a phone's back button, and this is only composed while the box is
    // open, so the innermost thing that can be closed is this one.
    OnBack { state.close() }

    // Asked after everything inside it, because a key that reaches here is one the input did not
    // want. The box eats it rather than letting the game behind act on a letter that was meant for
    // the chat. Tab and the arrows are let through: they are how focus reaches the tabs.
    val keys = remember(state) {
        KeyHandler { event ->
            when {
                event.type == KeyEventType.Down && event.key == Key.Escape -> {
                    state.close()
                    true
                }
                event.key in FocusKeys -> false
                else -> true
            }
        }
    }

    // The bumpers walk the channels, which is the only way to a tab on a pad while the on-screen
    // keyboard has the rest of it. Everything else is left alone: the pad still drives what is
    // inside the box.
    val channelsNow by rememberUpdatedState(channels)
    val pad = remember(state) {
        GamepadHandler { event ->
            if (event !is GamepadEvent.ButtonDown) {
                false
            } else {
                when (event.button) {
                    GamepadButton.LeftBumper -> step(state, channelsNow, -1)
                    GamepadButton.RightBumper -> step(state, channelsNow, 1)
                    else -> false
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .styled(style)
            .focusTrap()
            .onKeyEvent(keys)
            .onGamepadEvent(pad)
            .testTag(ChatTags.Panel),
        verticalArrangement = Arrangement.spacedBy(6f),
    ) {
        // Round the lot rather than round the log alone, so Ctrl+C reaches it from the input, where
        // focus is. Only the lines themselves are selectable; the tabs, the names and the input all
        // say they are not, because a drag on them means something else.
        SelectionContainer {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
                if (channels.size > 1) DisableSelection { ChatTabs(state, channels, style, caret) }
                ChatLog(state, shown, list, style, channels.size > 1, historyHeight, nameMenu, onName)
                DisableSelection {
                    ChatInput(
                        state, channels, onSend, style, placeholder,
                        maxLength, closeOnSend, list, historyHeight, caret,
                    )
                }
            }
        }
    }
}

/** The channels, as tabs. The one being spoken in is marked; clicking another moves to it. */
@Composable
private fun ChatTabs(state: ChatState, channels: List<ChatChannel>, style: String, caret: InputFocus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4f)) {
        channels.forEach { channel ->
            val chosen = channel == state.channel
            Button(
                channel.name,
                onClick = {
                    state.channel = channel
                    caret.take()
                },
                modifier = Modifier.testTag(ChatTags.tab(channel.id)),
                style = if (chosen) "$style.tab.selected" else "$style.tab",
            )
        }
    }
}

/**
 * Everything said, oldest at the top, only building the lines that can be seen.
 *
 * It follows the newest line, unless the player has scrolled back to read something — then it holds
 * still and lets the new lines pile up below. Scrolling back to the end starts it following again.
 */
@Suppress("LongParameterList")
@Composable
private fun ChatLog(
    state: ChatState,
    shown: List<ChatMessage>,
    list: LazyListState,
    style: String,
    showChannel: Boolean,
    historyHeight: Float,
    nameMenu: (MenuScope.(ChatMessage) -> Unit)?,
    onName: ((ChatMessage) -> Unit)?,
) {
    val follow = remember { ChatFollow() }

    // Asked more than once on purpose: a lazy list does not know how many lines it has or how tall
    // they are until it has laid itself out, so the first ask happens before any of that and moves
    // it nowhere. A log short enough to fit never moves at all, and never stops following either.
    LaunchedEffect(state.revision, shown.size) {
        if (shown.isEmpty() || !follow.isFollowing(list, shown)) return@LaunchedEffect
        repeat(ChatScrollTries) {
            withFrameNanos { }
            list.scrollToItem(shown.size - 1)
        }
        follow.moved(list, shown)
    }

    Box(Modifier.fillMaxWidth().height(historyHeight).testTag(ChatTags.Log)) {
        LazyColumn(
            count = shown.size,
            modifier = Modifier.fillMaxWidth(),
            state = list,
            key = { shown[it] },
            spacing = 2f,
        ) { index ->
            ChatLine(shown[index], style, showChannel, nameMenu, onName)
        }
    }
}

/**
 * Whether the log is still following the newest line.
 *
 * It remembers where the window really ended up after the last time it was moved, rather than where
 * it was asked to go: a log with three lines in it cannot scroll at all, and comparing against what
 * was asked for would decide that such a log had been scrolled back and stop following it.
 *
 * What it remembers is the **line** at the top of the window, not its number. A line falling off
 * the front of a full log moves every other line up one, and so does a line the filter stops
 * drawing, and an index remembered on its own cannot tell either of those from a player scrolling
 * back. Finding the line again says where the window really is whatever moved under it.
 */
private class ChatFollow {

    /** The line the window was at the top of the last time it was moved, before anything moved it. */
    private var mark: ChatMessage? = null

    fun isFollowing(list: LazyListState, shown: List<ChatMessage>): Boolean {
        val line = mark ?: return true
        // The line the mark was taken on has gone — off the front of a full log, out of the filter,
        // or with the whole log when it was emptied. That is not a log anybody is holding still.
        val where = shown.indexOfFirst { it === line }
        if (where < 0) return true
        return list.firstVisibleItem >= where
    }

    fun moved(list: LazyListState, shown: List<ChatMessage>) {
        mark = shown.getOrNull(list.firstVisibleItem)
    }
}

/** The line being typed: which channel it is going to, and the field it is typed in. */
@Suppress("LongParameterList")
@Composable
private fun ChatInput(
    state: ChatState,
    channels: List<ChatChannel>,
    onSend: (ChatChannel, String) -> Unit,
    style: String,
    placeholder: String?,
    maxLength: Int,
    closeOnSend: Boolean,
    list: LazyListState,
    historyHeight: Float,
    caret: InputFocus,
) {
    // Before the field's own handler, because the caller's modifier goes on first: the arrows and
    // Escape mean something to a chat box that they do not mean to a field.
    val keys = remember(state, list, historyHeight) {
        KeyHandler { event ->
            if (event.type != KeyEventType.Down) {
                false
            } else {
                when (event.key) {
                    Key.Up -> used { state.earlier() }
                    Key.Down -> used { state.later() }
                    Key.PageUp -> used { list.scrollBy(-page(historyHeight)) }
                    Key.PageDown -> used { list.scrollBy(page(historyHeight)) }
                    Key.Escape -> used { state.close() }
                    else -> false
                }
            }
        }
    }

    val hint = placeholder ?: chatWord("chat.say", "Say something")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text(state.channel.name, style = state.channel.style ?: "$style.channel")
        TextField(
            value = state.draft,
            onValueChange = state::onInput,
            modifier = Modifier.weight(1f).onPlaced(caret.placed).onKeyEvent(keys).testTag(ChatTags.Input),
            style = "$style.field",
            placeholder = hint,
            maxLength = maxLength,
            initialFocus = true,
            onSubmit = {
                // A line that was only a prefix moved the player to that channel and said nothing,
                // so the box stays open with an empty input in the channel they just chose.
                val moved = state.send(channels, onSend)
                if (closeOnSend && !moved) state.close()
            },
        )
    }
}

/**
 * A handle on the input, so focus can be put back on it.
 *
 * A node exists only once it has been laid out, which by the time a tab is pressed it has been.
 * Nothing to focus — the box closed between the press and this — is false rather than an error.
 */
private class InputFocus {

    private var node: UiNode? = null

    /** The same object every pass, so the field does not rebuild its modifiers as it is typed in. */
    val placed = PlacedHandler { node = it }

    fun take(): Boolean = focusOnNode(node)
}

/** One line: which channel it came in on, who said it, and what they said. */
@Composable
private fun ChatLine(
    message: ChatMessage,
    style: String,
    showChannel: Boolean,
    nameMenu: (MenuScope.(ChatMessage) -> Unit)?,
    onName: ((ChatMessage) -> Unit)?,
) {
    // A message's own style beats its channel's, and a line nobody said is drawn as the system's.
    val said = message.style
        ?: message.channel?.style
        ?: if (message.from == null) "$style.system" else "$style.message"

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4f)) {
        val channel = message.channel
        if (showChannel && channel != null) {
            Text("[${channel.name}]", style = channel.style ?: "$style.channel")
        }
        if (message.from != null) ChatName(message, style, nameMenu, onName)
        Text(
            message.text,
            Modifier.weight(1f).testTag(ChatTags.said(message.text)),
            style = said,
            runs = message.runs,
        )
    }
}

/**
 * The name in front of a line.
 *
 * It is only somewhere to press when the game has given it something to do. A name that does
 * nothing is not focusable either, or a pad walking the box would stop on every line of the log.
 */
@Composable
private fun ChatName(
    message: ChatMessage,
    style: String,
    nameMenu: (MenuScope.(ChatMessage) -> Unit)?,
    onName: ((ChatMessage) -> Unit)?,
) {
    val label = "${message.from}:"
    val tagged = Modifier.testTag(ChatTags.name(message.from.orEmpty()))
    if (nameMenu == null && onName == null) {
        Text(label, tagged, style = "$style.name")
        return
    }

    var name: Modifier = tagged
    if (onName != null) name = name.clickable { onName(message) }
    if (nameMenu != null) name = name.contextMenu { this.nameMenu(message) }

    // Not selectable: a drag across a name is how the menu is reached on a touch screen.
    DisableSelection { Text(label, name.focusable(), style = "$style.name") }
}

/** Moves the channel [by] places along the list, wrapping, which is what a bumper does. */
private fun step(state: ChatState, channels: List<ChatChannel>, by: Int): Boolean {
    if (channels.size < 2) return false
    val at = channels.indexOf(state.channel)
    if (at < 0) return false
    state.channel = channels[(at + by + channels.size) % channels.size]
    return true
}

/** What a typed line turned out to be: where it is going, and what is left of it once the prefix is off. */
private class Routed(val channel: ChatChannel?, val body: String)

/**
 * Reads the prefix off the front of a typed line.
 *
 * The longest prefix wins, so a game with both `/t` and `/te` gets the one the player typed rather
 * than whichever came first in the list.
 */
private fun route(text: String, channels: List<ChatChannel>): Routed {
    val typed = text.trim()
    val match = channels
        .filter { !it.prefix.isNullOrEmpty() }
        .sortedByDescending { it.prefix!!.length }
        .firstOrNull { typed == it.prefix || typed.startsWith("${it.prefix} ") }
        ?: return Routed(null, typed)
    return Routed(match, typed.removePrefix(match.prefix!!).trim())
}

/** Whether this event is the key that opens the box, with nothing held with it. */
private fun KeyEvent.opens(openKey: Key?): Boolean =
    openKey != null && type == KeyEventType.Down && !repeat && key == openKey && modifiers.none

/** Runs [action] and says the key was used, which is what every branch that has one wants. */
private inline fun used(action: () -> Unit): Boolean {
    action()
    return true
}

/** How far PageUp and PageDown move: nearly a boxful, keeping a line or two to read across. */
private fun page(historyHeight: Float): Float = (historyHeight * 0.9f).coerceAtLeast(1f)

/** A word of the box's own, in the player's language, falling back to the English one. */
@Composable
private fun chatWord(key: String, english: String): String {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    // A key nothing has translated comes back as itself, which is the cue to keep the English word
    // rather than print `chat.say` in the box.
    return remember(strings, locale, key, english) {
        strings.get(locale, key).takeIf { it != key } ?: english
    }
}

/**
 * The keys an open box does not eat: the ones focus moves on.
 *
 * Everything else is swallowed while it is open, so a letter meant for the chat does not also drive
 * the game. These are how a player reaches the tabs from the input and comes back again — and they
 * cannot leave the box, because it traps focus while it is open.
 */
private val FocusKeys = setOf(Key.Tab, Key.Left, Key.Right, Key.Up, Key.Down)

/** How many frames the log gives itself to reach its newest line. See where it is used. */
private const val ChatScrollTries = 3
