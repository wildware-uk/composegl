package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.LocalLocale
import dev.wildware.composegl.ui.text.LocalStrings
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.TypewriterEffect
import dev.wildware.composegl.ui.widget.rememberLazyListState
import dev.wildware.composegl.ui.widget.rememberTypewriter

// --- what a conversation is made of ---------------------------------------------------------------

/**
 * One thing somebody says: who says it, what they say, and the face they say it with.
 *
 * **Identity is the object, not the words.** Two lines that say exactly the same thing — a second
 * "…" in an awkward silence — are two lines, and the box types the second one out again rather than
 * leaving the first one sitting there. So a game builds a line where it builds its script, and does
 * not rebuild it every frame.
 *
 * @param text what is said. It is revealed a character at a time by
 *   [Typewriter][dev.wildware.composegl.ui.widget.Typewriter], so it may be as long as a paragraph.
 * @param speaker the name over the line, or null for narration nobody says.
 * @param portrait which face this line is said with — an expression name, a texture, whatever the
 *   game's portrait slot understands. Compared by equality, and a line whose portrait differs from
 *   the one before it is what makes the portrait swap. Null for a line with no face at all.
 * @param runs the parts of [text] that look different from the rest: a name in a colour, a term
 *   underlined. Colours are applied as the characters arrive; the whole run, decoration and all, is
 *   drawn in [DialogueHistory], where the line is an ordinary
 *   [Text][dev.wildware.composegl.ui.widget.Text]. See the note on `runs` in [DialogueBox].
 * @param speakerStyle a skin style for this speaker's name, so the captain is gold and the ship's
 *   computer is green. Null takes the box's own `"<style>.speaker"`.
 */
class DialogueLine(
    val text: String,
    val speaker: String? = null,
    val portrait: Any? = null,
    val runs: List<TextRun> = emptyList(),
    val speakerStyle: String? = null,
)

/**
 * One answer the player may give.
 *
 * A disabled choice is **shown rather than hidden**, with [reason] beside it. That is the whole
 * point of having one: "Pay the toll (200 credits)" greyed out with *you have 40* under it tells a
 * player what to go and do, and a choice quietly left off the list tells them nothing.
 *
 * @param text the answer, in the player's language — the game looks it up, not the widget.
 * @param enabled false greys it out and it cannot be picked, focused or reached by the pad.
 * @param reason why it cannot be picked. Drawn under the answer, and only while it is disabled.
 * @param tag whatever the game wants back: the branch to jump to, an id, an index into its script.
 */
data class DialogueChoice(
    val text: String,
    val enabled: Boolean = true,
    val reason: String? = null,
    val tag: Any? = null,
)

/** One line as it went by, with the answer the player gave to it if it asked for one. */
class DialogueEntry internal constructor(val line: DialogueLine) {

    /** What the player said to this line, or null for a line that asked nothing. */
    var answer: String? by mutableStateOf(null)
        internal set
}

/**
 * What has been said so far, for the log a player opens when they were not paying attention.
 *
 * Kept by the game rather than by the box, because a conversation outlives the widget: the box goes
 * away while the player reads the log, and comes back with the same log behind it.
 *
 * ```kotlin
 * val log = rememberDialogueLog()
 * DialogueBox(line, choices = choices, onChoose = ::answer, log = log)
 * if (showingLog) DialogueHistory(log, Modifier.fillMaxSize())
 * ```
 *
 * @param limit how many lines to keep. Past it the oldest go, because a log nobody can reach the
 *   top of is a log that only costs memory.
 */
@Stable
class DialogueLog(val limit: Int = 200) {

    private val said = mutableStateListOf<DialogueEntry>()

    /** Everything said so far, oldest first. */
    val entries: List<DialogueEntry> get() = said

    val isEmpty: Boolean get() = said.isEmpty()

    /**
     * Writes [line] down, unless it is the line already at the end — which is what a box that
     * recomposes twice for the same line would otherwise add twice.
     */
    fun say(line: DialogueLine): DialogueEntry {
        said.lastOrNull()?.takeIf { it.line === line }?.let { return it }
        val entry = DialogueEntry(line)
        said.add(entry)
        while (said.size > limit) said.removeAt(0)
        return entry
    }

    /** Puts the player's answer against the line that asked for it, which is the last one said. */
    fun answer(text: String) {
        said.lastOrNull()?.answer = text
    }

    /** A new conversation, or a new game. */
    fun clear() = said.clear()
}

/** A log that lives as long as the screen it is on. */
@Composable
fun rememberDialogueLog(limit: Int = 200): DialogueLog = remember(limit) { DialogueLog(limit) }

// --- the box ---------------------------------------------------------------------------------------

/**
 * The dialogue box: a speaker, a portrait, a line that types itself out, and the answers to it.
 *
 * The conversation is the game's — this draws whatever [line] it is handed and says what the player
 * did about it. There is no script, no state machine and no "next" inside the widget, because every
 * game already has its own and none of them agree.
 *
 * ```kotlin
 * // A beat of the game's own script. The widget never sees this type: it is handed the line and
 * // the answers separately, because a line is a line whoever wrote the script around it.
 * class Beat(val line: DialogueLine, val answers: List<DialogueChoice> = emptyList())
 *
 * val beat = script.getOrNull(at)
 * DialogueBox(
 *     line = beat?.line,
 *     choices = beat?.answers.orEmpty(),
 *     onChoose = { branch(it.tag) },
 *     onAdvance = { at++ },
 *     log = log,
 *     auto = settings.auto,
 *     onAutoChange = { settings.auto = it },
 *     portrait = { Image(it.portrait as String, Modifier.size(96f)) },
 * )
 * ```
 *
 * **Advancing** is one press that does two things, which is the rule every player already knows:
 * while the line is still arriving it shows the rest of it at once, and once it has arrived it
 * moves on. A click on the box, Space, Enter or the pad's South all do it. Once there are choices
 * up, advancing does nothing — the answer is the way on — and Space, Enter and South go back to
 * pressing whichever answer has focus.
 *
 * **Choices** appear when the line has finished. Focus moves to the first one that can be taken, so
 * a pad or a keyboard can answer without touching anything else even when the player was last on
 * the box's own Auto button; `1` to `9` take an answer straight off the number row; a disabled one
 * is drawn with its [DialogueChoice.reason] under it. A question with *every* answer disabled is a
 * wall rather than a question: it is drawn with its reasons, but focus is not trapped on it and
 * advancing works as it would on a line with no answers at all.
 *
 * **Auto, skip and history** are the game's state and the game's buttons, offered here because
 * every visual novel has all three. Each appears only when it has somewhere to go: pass
 * [onAutoChange] and the box draws an Auto button wired to it. Skipping types nothing — the line is
 * whole the moment it arrives — and **stops at a question**, because reading past a line the player
 * has seen is one thing and answering for them is another. Holding Ctrl skips while it is held, the
 * way a visual novel has done it for thirty years.
 *
 * **Its own words are localised.** Auto, Skip and Log are looked up in the game's
 * [Strings][dev.wildware.composegl.ui.text.Strings] as `dialogue.auto`, `dialogue.skip` and
 * `dialogue.log`, and a game that has translated none of them gets the English ones rather than the
 * keys. Everything else on the box — the line, the speaker, the answers, the reasons — is the
 * game's own text, already in the player's language before it arrives here.
 *
 * **Right to left** is the layout's: in Arabic the portrait sits on the right, the name and the
 * answers start there, and the timer bar drains the other way.
 *
 * **It costs nothing when it is not talking.** A null [line] draws nothing, composes nothing and
 * asks for no frames. A finished line asks for one thing only: the small arrow breathing to say it
 * is waiting, which is what an [indicator] of your own — a static glyph, a prompt — turns off.
 *
 * @param line what is being said now, or null for a conversation that is over.
 * @param choices the answers to this line, or empty for one that only needs advancing. They appear
 *   once the line has been read out.
 * @param onChoose called with the answer the player took.
 * @param onAdvance called when the player is done with this line and wants the next one.
 * @param log written to as each line starts and each answer is given, for [DialogueHistory].
 * @param auto whether the game is advancing by itself. The box waits [autoMillis] after a line and
 *   then advances.
 * @param onAutoChange told when the player presses the Auto button. Null draws no button.
 * @param skipping whether the game is fast-forwarding. Lines arrive whole and advance after
 *   [skipMillis].
 * @param onSkippingChange told when the player presses Skip, holds or lets go of Ctrl, or when the
 *   box stops skipping because a question came up. Null draws no button and listens for no Ctrl.
 * @param onHistory called when the player presses the Log button. Null draws no button; the game
 *   opens [DialogueHistory] itself, because only it knows where.
 * @param onTimeout called when a choice's timer runs out with nothing chosen. The game decides what
 *   silence means — a default answer, a branch, a slap.
 * @param timerMillis how long the player has to answer, or zero for as long as they like. A bar
 *   under the answers drains over it.
 * @param charactersPerSecond how fast the line types.
 * @param autoMillis how long an automatic conversation rests on a finished line before moving on.
 * @param skipMillis the same while skipping. Short, but not nothing: a player watching the text
 *   flash past should still be able to see where they are.
 * @param advanceOnClick whether a press anywhere on the box advances it.
 * @param trapFocus whether focus stays inside the box while there are answers up. On, because a
 *   question a player can tab away from is a question they can leave unanswered forever.
 * @param style the skin style. `"<style>.speaker"` is the name, `"<style>.text"` the line,
 *   `"<style>.choice"` an answer with `"<style>.choice.reason"` under a disabled one,
 *   `"<style>.timer.track"` and `"<style>.timer.fill"` the timer, `"<style>.control"` the Auto,
 *   Skip and Log buttons with `"<style>.control.on"` for one that is on, and `"<style>.advance"`
 *   the little arrow that says the line has finished.
 * @param clock which clock the typing, the waiting and the timer run on. The interface's, so a
 *   conversation still runs over a stopped world — which is what a conversation usually does.
 * @param effect an optional per-character effect: a letter that shakes as it lands, a word that
 *   fades up. See [TypewriterEffect] for what having one costs.
 * @param portrait draws the face for a line. Called with the line whose portrait is showing, which
 *   during a swap is still the old one until it has faded out. Null for a box with no portraits.
 * @param indicator what says "this line is done, press on": a small blinking arrow by default, and
 *   the place to put a [PromptGlyph][dev.wildware.composegl.ui.widget.PromptGlyph] instead.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun DialogueBox(
    line: DialogueLine?,
    modifier: Modifier = Modifier,
    choices: List<DialogueChoice> = emptyList(),
    onChoose: (DialogueChoice) -> Unit = {},
    onAdvance: () -> Unit = {},
    log: DialogueLog? = null,
    auto: Boolean = false,
    onAutoChange: ((Boolean) -> Unit)? = null,
    skipping: Boolean = false,
    onSkippingChange: ((Boolean) -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    onTimeout: (() -> Unit)? = null,
    timerMillis: Int = 0,
    charactersPerSecond: Float = 45f,
    autoMillis: Int = 1_400,
    skipMillis: Int = 60,
    advanceOnClick: Boolean = true,
    trapFocus: Boolean = true,
    style: String = "dialogue",
    clock: Clock = Clock.Ui,
    effect: TypewriterEffect? = null,
    portrait: @Composable ((DialogueLine) -> Unit)? = null,
    indicator: @Composable (() -> Unit)? = null,
) {
    if (line == null) return

    val clocks = LocalClocks.current
    val writer = rememberTypewriter(line.text, clock)

    // Whether the line has been read out. Held here rather than asked of the typewriter every pass,
    // because asking would recompose the whole box on every character; this changes twice a line.
    var finished by remember { mutableStateOf(false) }

    // A new line is a new line even when it says what the last one said, so it starts again from
    // nothing. Written while composing rather than waited for in an effect, so the first frame of
    // the new line is already empty instead of holding the old one for a frame.
    remember(line) {
        writer.restart()
        finished = false
        line
    }

    val advanceNow by rememberUpdatedState(onAdvance)
    val timeoutNow by rememberUpdatedState(onTimeout)
    val skipChange by rememberUpdatedState(onSkippingChange)

    val showChoices = finished && choices.isNotEmpty()

    // A question only counts as one while there is an answer that can be taken. Every answer greyed
    // out is a wall rather than a question: there is nothing to focus and nothing to press, so the
    // box keeps its trap open and lets the player press on past it instead of standing there for
    // ever. The answers are still drawn, with their reasons, which is the point of showing them.
    val answerable = showChoices && choices.any { it.enabled }

    // The answer goes into the log against the line that asked for it. Done here rather than left
    // to the game, because a log that has the lines and not the answers is half a conversation.
    val chose: (DialogueChoice) -> Unit = { choice ->
        log?.answer(choice.text)
        onChoose(choice)
    }

    // The handlers are this object's, so they are the same objects every pass and the box does not
    // rebuild its modifiers as it types. What changes each pass is only what they read.
    val input = remember { DialogueInput() }
    input.advance = {
        when {
            writer.isRevealing -> writer.skip()
            !answerable -> advanceNow()
            else -> Unit
        }
    }
    input.choices = choices
    input.onChoose = chose
    input.showingChoices = answerable
    input.onSkipping = onSkippingChange
    input.onHistory = onHistory

    // The line goes into the log as it starts rather than as it ends, so a player who opens the log
    // sees the line they are looking at rather than everything except it.
    LaunchedEffect(line, log) { log?.say(line) }

    // Skipping is not fast typing: the line is simply there. A player holding skip is looking for
    // the end of a conversation, not reading it at four hundred characters a second.
    LaunchedEffect(line, skipping) { if (skipping) writer.skip() }

    // And the two ways a conversation moves on by itself. Neither runs while there is a question
    // up: answering for the player is the one thing automation must never do.
    LaunchedEffect(line, finished, auto, skipping, answerable) {
        if (!finished || answerable) return@LaunchedEffect
        val rest = when {
            skipping -> skipMillis
            auto -> autoMillis
            else -> return@LaunchedEffect
        }
        clocks.wait(clock, rest)
        advanceNow()
    }

    // Skip stops at a question, and says so, so the game's own Skip button comes back up. Keyed on
    // the flag as well as on the question: skip turned on while a question is already up has to go
    // down too, or the rest of the conversation blows past the moment the player answers.
    LaunchedEffect(answerable, skipping) {
        if (answerable && skipping) skipChange?.invoke(false)
    }

    // Where focus goes when the answers arrive. `initialFocus` on the first of them is not enough:
    // a screen only picks its initial focus when nothing has any, and by the time a question comes
    // up focus is usually on the box's own Auto button — so the answer is focused outright. It has
    // no node until it has been laid out, which is the frame after this, hence the second look.
    val answerFocus = remember { AnswerFocus() }
    LaunchedEffect(line, answerable) {
        if (!answerable) return@LaunchedEffect
        repeat(FocusTries) {
            if (answerFocus.take()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    val timer = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }
    LaunchedEffect(line, showChoices, timerMillis) {
        if (!showChoices || timerMillis <= 0) return@LaunchedEffect
        timer.snapTo(1f)
        timer.animateTo(0f, Tween(timerMillis, easing = Easings.Linear))
        timeoutNow?.invoke()
    }

    // Only the colours: a run's underline needs to know where a character is on a line, which is
    // the one thing a typewriter drawing letter by letter does not have. The whole run is drawn in
    // the history, where the line is an ordinary label. Remembered, because the effect is what the
    // typing is keyed on — a new one each pass would start the line again every frame.
    val typed = remember(line.runs, effect) { runColours(line.runs, effect) }

    var box = modifier
        .onShortcutKey(input.keys)
        .onShortcutGamepad(input.pad)
        .focusTrap(trapFocus && answerable)
    if (advanceOnClick) box = box.clickable(onClick = input.press)

    Column(box.styled(style), verticalArrangement = Arrangement.spacedBy(8f)) {
        val controls = onAutoChange != null || onSkippingChange != null || onHistory != null
        if (line.speaker != null || controls) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = VerticalAlignment.Centre) {
                if (line.speaker != null) {
                    Text(line.speaker, style = line.speakerStyle ?: "$style.speaker")
                }
                Spacer(Modifier.weight(1f))
                if (controls) {
                    Controls(style, auto, onAutoChange, skipping, onSkippingChange, onHistory)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12f)) {
            if (portrait != null) Portrait(line, clock, portrait)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8f)) {
                val words: @Composable () -> Unit = {
                    Typewriter(
                        writer,
                        Modifier.fillMaxWidth(),
                        style = "$style.text",
                        charactersPerSecond = charactersPerSecond,
                        effect = typed,
                        onFinished = { finished = true },
                    )
                }
                // A box a click advances keeps that click: dragging across the words to select them
                // would fight the press that moves the conversation on.
                if (advanceOnClick) DisableSelection(words) else words()

                if (showChoices) {
                    Choices(choices, chose, style, answerFocus.placed)
                    if (timerMillis > 0) Countdown(timer, style, clock)
                }
            }
        }

        // The little arrow, and only once there is something for it to mean.
        if (finished && !showChoices) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                if (indicator != null) indicator() else AdvanceArrow(style, clock)
            }
        }
    }
}

/** The answers, in the order the game gave them, with the first one that can be taken holding focus. */
@Composable
private fun Choices(
    choices: List<DialogueChoice>,
    onChoose: (DialogueChoice) -> Unit,
    style: String,
    placed: PlacedHandler,
) {
    val first = choices.indexOfFirst { it.enabled }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
        // Identity is the position, which is what a list of answers has: two answers with the same
        // words are still two answers, and the row a player is standing on is the second one.
        choices.forEachIndexed { index, choice ->
            Button(
                onClick = { onChoose(choice) },
                // The box puts focus here itself once the answers are up, which is what [placed] is
                // for: `initialFocus` alone would leave it on whatever already had it.
                modifier = if (index == first) {
                    Modifier.fillMaxWidth().onPlaced(placed)
                } else {
                    Modifier.fillMaxWidth()
                },
                style = "$style.choice",
                enabled = choice.enabled,
                initialFocus = index == first,
                contentAlignment = Alignment.CentreStart,
            ) {
                Column(horizontalAlignment = HorizontalAlignment.Start) {
                    Text(choice.text)
                    // Only while it cannot be taken: the reason a player can do something is not
                    // news, and a list of reasons under every answer is unreadable.
                    if (!choice.enabled && choice.reason != null) {
                        Text(choice.reason, style = "$style.choice.reason")
                    }
                }
            }
        }
    }
}

/**
 * A handle on the first answer that can be taken, so focus can be moved onto it.
 *
 * A node exists only once it has been laid out, so the box asks [take] again on the next frame
 * until it lands. Nothing to focus — the answers gone, the box gone — is false rather than an
 * error: an answer that is no longer in a tree has no focus manager over it.
 */
private class AnswerFocus {

    private var node: UiNode? = null

    /** The same object every pass, so the answer does not rebuild its modifiers as it is drawn. */
    val placed = PlacedHandler { node = it }

    fun take(): Boolean = focusOnNode(node)
}

/**
 * The bar that drains while the player thinks.
 *
 * In its own composable because reading an [Animatable]'s value recomposes whoever read it: read in
 * the box's own scope, the words and every answer would be composed again on every frame of the
 * countdown. Read here, a frame of it costs one bar — the same way [Portrait] and [AdvanceArrow]
 * keep their animations to themselves.
 */
@Composable
private fun Countdown(timer: Animatable<Float>, style: String, clock: Clock) {
    Bar(
        timer.value,
        Modifier.fillMaxWidth(),
        style = "$style.timer",
        thickness = 4f,
        trail = false,
        clock = clock,
    )
}

/**
 * The Auto, Skip and Log buttons, each drawn only when the game has somewhere to send it.
 *
 * Their words are looked up in the game's strings and fall back to English, the same way a
 * [CompassBar]'s points do: a game that has translated nothing still has buttons that say
 * something.
 */
@Composable
private fun Controls(
    style: String,
    auto: Boolean,
    onAutoChange: ((Boolean) -> Unit)?,
    skipping: Boolean,
    onSkippingChange: ((Boolean) -> Unit)?,
    onHistory: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4f), verticalAlignment = VerticalAlignment.Centre) {
        if (onAutoChange != null) {
            Control(word("dialogue.auto", "Auto"), auto, style) { onAutoChange(!auto) }
        }
        if (onSkippingChange != null) {
            Control(word("dialogue.skip", "Skip"), skipping, style) { onSkippingChange(!skipping) }
        }
        if (onHistory != null) {
            Control(word("dialogue.log", "Log"), on = false, style = style, onClick = onHistory)
        }
    }
}

@Composable
private fun Control(text: String, on: Boolean, style: String, onClick: () -> Unit) {
    Button(text, onClick, style = if (on) "$style.control.on" else "$style.control")
}

/** A word of the box's own, in the player's language, falling back to the English one. */
@Composable
private fun word(key: String, english: String): String {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    // A key nothing has translated comes back as itself, which is the cue to keep the English word
    // rather than print `dialogue.auto` on a button.
    return remember(strings, locale, key, english) {
        strings.get(locale, key).takeIf { it != key } ?: english
    }
}

/**
 * The face, and the dip as it changes.
 *
 * The old expression fades out and the new one fades in, so a face that goes from smiling to furious
 * is a change the player sees happen rather than one frame of one and one frame of the other. Only
 * the *expression* swaps: two lines from the same speaker with the same face do not blink between
 * them, which is why it turns on [DialogueLine.portrait] rather than on the line.
 */
@Composable
private fun Portrait(line: DialogueLine, clock: Clock, content: @Composable (DialogueLine) -> Unit) {
    val clocks = LocalClocks.current
    val fade = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }

    // What is on screen, which during a swap is still the old line until it has faded out.
    var shown by remember { mutableStateOf(line) }

    LaunchedEffect(line) {
        if (line.portrait == shown.portrait) {
            shown = line
            return@LaunchedEffect
        }
        fade.animateTo(0f, Tween(SwapMillis / 2, easing = Easings.EaseIn))
        shown = line
        fade.animateTo(1f, Tween(SwapMillis / 2, easing = Easings.EaseOut))
    }

    // The fade is on a box round whatever the game drew, so a portrait can be a picture, a whole
    // panel or an animation and still swap the same way.
    Box(Modifier.alpha(fade.value)) { content(shown) }
}

/** The blinking arrow that says the line is done and the conversation is waiting on the player. */
@Composable
private fun AdvanceArrow(style: String, clock: Clock) {
    val clocks = LocalClocks.current
    val colour = rememberStyle("$style.advance").textColour
    val blink = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }

    LaunchedEffect(blink) {
        // Breathing rather than blinking: an arrow that goes on and off is read as broken.
        while (true) {
            blink.animateTo(0.25f, Tween(BlinkMillis, easing = Easings.Sine))
            blink.animateTo(1f, Tween(BlinkMillis, easing = Easings.Sine))
        }
    }

    val draw = remember(colour) { arrow(colour) }
    Spacer(Modifier.size(ArrowSize).alpha(blink.value).drawBehind(draw))
}

/** A triangle pointing down, drawn rather than written, so it needs no glyph in the game's font. */
private fun arrow(colour: Colour): UiCanvas.(Rect) -> Unit = { box ->
    fan(
        floatArrayOf(box.left, box.top, box.right, box.top, (box.left + box.right) / 2f, box.bottom),
        colour,
    )
}

/**
 * The colours of a line's runs, as something the typewriter can apply a character at a time.
 *
 * Later runs win where two cover the same character, which is the rule a styled
 * [Text][dev.wildware.composegl.ui.widget.Text] follows, so a name in a colour looks the same
 * whether it is being typed out or read back in the log.
 */
private fun runColours(runs: List<TextRun>, then: TypewriterEffect?): TypewriterEffect? {
    val coloured = runs.filter { it.colour != null }
    if (coloured.isEmpty()) return then
    return TypewriterEffect { character ->
        coloured.forEach { run ->
            if (character.index >= run.range.min && character.index < run.range.max) {
                character.colour = run.colour ?: character.colour
            }
        }
        then?.style(character)
    }
}

/**
 * Every way into the box that is not a click on it, held in one object.
 *
 * Its handlers are stable, so a box the player is reading does not rebuild its modifiers as the
 * characters arrive; the fields are plain rather than state because the composition writes them and
 * only the handlers read them.
 */
private class DialogueInput {

    var advance: () -> Unit = {}
    var choices: List<DialogueChoice> = emptyList()
    var onChoose: (DialogueChoice) -> Unit = {}
    var showingChoices = false
    var onSkipping: ((Boolean) -> Unit)? = null
    var onHistory: (() -> Unit)? = null

    /** A press on the box itself. The same object every pass, for the same reason the rest are. */
    val press: () -> Unit = { advance() }

    /**
     * The keyboard, heard wherever focus is — a player pressing on through a conversation has not
     * first clicked on the box.
     *
     * Space and Enter are **left alone while there are answers up**: they are how a keyboard
     * presses the answer that has focus, and a box that swallowed them would be a box whose answers
     * could only be clicked.
     */
    val keys = KeyHandler { event ->
        if (event.type != KeyEventType.Down) {
            // Letting go of Ctrl is the end of a skip, and the only thing an up event here means.
            if (event.key == Key.Control) onSkipping?.invoke(false)
            false
        } else {
            when {
                event.key == Key.Space || event.key == Key.Enter -> {
                    if (showingChoices) {
                        false
                    } else {
                        advance()
                        true
                    }
                }
                // The number row answers a question without reaching for anything, which is how a
                // visual novel is played with one hand.
                showingChoices && event.key.code >= Key.Digit1.code && event.key.code <= Key.Digit9.code -> {
                    take(event.key.code - Key.Digit1.code)
                }
                // Held, not toggled: skipping stops the moment the player stops asking for it.
                // Never taken, because Ctrl is half of every other shortcut on the screen.
                event.key == Key.Control -> {
                    onSkipping?.invoke(true)
                    false
                }
                else -> false
            }
        }
    }

    /**
     * The pad. South advances while the line is arriving and while nothing is being asked, and is
     * left to the navigator once there are answers up, where it presses the one with focus.
     *
     * North opens the log, which is where a console player expects it and the only pad button a
     * conversation has spare.
     */
    val pad = GamepadHandler { event ->
        if (event !is GamepadEvent.ButtonDown) {
            false
        } else {
            when (event.button) {
                GamepadButton.South -> if (showingChoices) {
                    false
                } else {
                    advance()
                    true
                }
                GamepadButton.North -> {
                    val open = onHistory
                    if (open == null) {
                        false
                    } else {
                        open()
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun take(index: Int): Boolean {
        val choice = choices.getOrNull(index) ?: return false
        if (!choice.enabled) return false
        onChoose(choice)
        return true
    }
}

// --- the log ---------------------------------------------------------------------------------------

/**
 * Everything said so far, scrollable, with the answers the player gave.
 *
 * The other half of a dialogue box, and the half that is always left out: a player who looked away
 * for ten seconds has no way back to what was said. This is a list rather than a panel, so a
 * conversation a thousand lines long costs a screenful.
 *
 * ```kotlin
 * Panel(Modifier.fillMaxSize()) { DialogueHistory(log) }
 * ```
 *
 * Each line is drawn with its [DialogueLine.runs] in full — colours and underlines both — because
 * here it is an ordinary [Text][dev.wildware.composegl.ui.widget.Text] rather than something being
 * typed out. It opens at the newest line, and follows it when another arrives.
 *
 * @param style the skin style. `"<style>.speaker"` is a name, `"<style>.line"` what was said and
 *   `"<style>.answer"` what the player said back.
 * @param entry draws one line of the log, for a game that wants portraits or timestamps in it.
 */
@Composable
fun DialogueHistory(
    log: DialogueLog,
    modifier: Modifier = Modifier,
    style: String = "dialogue.history",
    spacing: Float = 10f,
    state: LazyListState = rememberLazyListState(),
    entry: @Composable ((DialogueEntry) -> Unit)? = null,
) {
    val entries = log.entries

    // At the bottom, where the conversation is. A log opened at the top is a log every player
    // scrolls to the end of before reading a word of it.
    //
    // Asked more than once on purpose: a lazy list does not know how many lines it has or how tall
    // they are until it has laid itself out, so the first ask — which happens before any of that —
    // moves it nowhere. It stops the moment the ask has taken, which is the frame after the first
    // layout, and a log short enough to fit on the screen never moves at all.
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        repeat(ScrollTries) {
            withFrameNanos { }
            state.scrollToItem(entries.size - 1)
            if (state.position > 0f) return@LaunchedEffect
        }
    }

    LazyColumn(
        count = entries.size,
        modifier = modifier,
        state = state,
        key = { entries[it] },
        spacing = spacing,
    ) { index ->
        val at = entries[index]
        if (entry != null) entry(at) else HistoryEntry(at, style)
    }
}

/** One remembered line: who said it, what they said, and what the player said back. */
@Composable
private fun HistoryEntry(entry: DialogueEntry, style: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2f)) {
        val line = entry.line
        if (line.speaker != null) {
            Text(line.speaker, style = line.speakerStyle ?: "$style.speaker")
        }
        Text(line.text, Modifier.fillMaxWidth(), style = "$style.line", runs = line.runs)
        entry.answer?.let { Text(it, Modifier.fillMaxWidth(), style = "$style.answer") }
    }
}

/** How long the portrait takes to change: out and back in, together. */
private const val SwapMillis = 180

/** How long the arrow takes to fade down and back up again. */
private const val BlinkMillis = 620

/** How big the arrow is. Small: it is punctuation, not a button. */
private const val ArrowSize = 9f

/** How many frames the log gives itself to reach its newest line. See where it is used. */
private const val ScrollTries = 3

/** How many frames the box gives itself to put focus on the first answer. See [AnswerFocus]. */
private const val FocusTries = 4
