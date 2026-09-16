package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.skin.LocalSkin
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.LocalTextScale
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.roundToInt

/**
 * One thing said, or one sound worth writing down.
 *
 * Identity is the object rather than the text, exactly as a [Notification]'s is: a line repeated
 * word for word — "Reloading." twice — is two lines the player reads twice, not one that refuses
 * to leave. The other way round too: one object is one line, so showing the same one again while it
 * is still up or still waiting does nothing.
 *
 * @param text what is written. Wrapped and cut to a line limit by [Subtitles]; it is the player's
 *   own language, so it may be Arabic or Hebrew and is laid out accordingly.
 * @param speaker who is saying it, written above the line and hidden when the player has turned
 *   speaker names off. Null for a line nobody in particular says.
 * @param speakerColour the colour that name is written in, for a game whose cast is in data rather
 *   than in a skin file. Null takes the colour [Subtitles] was given for this speaker, and failing
 *   that the skin's.
 * @param caption whether this is a caption rather than dialogue: `[a door slams]`, `[distant
 *   gunfire]`. Captions are drawn in a style of their own and never carry a speaker.
 * @param durationMillis how long it stays up. Zero works it out from how long the line is, at the
 *   queue's reading speed, which is what a game hands over when it has no recorded length.
 * @param style a skin style for this one line — a shout, a radio voice. Null takes the widget's. The
 *   name over the line takes `"<style>.speaker"` when the skin has one, so a radio voice can have a
 *   radio-voice name; a style with no `.speaker` of its own leaves the name as the widget draws it.
 */
@Suppress("LongParameterList")
class SubtitleLine(
    val text: String,
    val speaker: String? = null,
    val speakerColour: Colour? = null,
    val caption: Boolean = false,
    val durationMillis: Int = 0,
    val style: String? = null,
) {

    /** How long this has left once it is up. Counted down by the queue, in milliseconds. */
    internal var left by mutableStateOf(0)

    /**
     * How much of its time is left, in milliseconds, or zero for a line not on screen yet.
     *
     * What a game draws a "skip" hint or a little countdown from, and what a test asserts on.
     */
    val remainingMillis: Int get() = left.coerceAtLeast(0)
}

/**
 * The lines waiting to be said, and the one or two being said now.
 *
 * A queue rather than a list, for the reason [NotificationQueue] is one: a cutscene hands over its
 * whole script at once, and a widget that drew everything it was given would cover the screen with
 * the conversation instead of showing the sentence being spoken. So [capacity] are up and the rest
 * wait their turn.
 *
 * ```kotlin
 * val subs = rememberSubtitleQueue()
 * Subtitles(subs, Modifier.align(Alignment.BottomCentre).padding(bottom = 48f))
 *
 * subs.show(speaker = "Mira", text = strings["intro.1"], durationMillis = 3_200)
 * subs.caption("[explosion in the distance]")
 * ```
 *
 * **Time comes from one of two places.** By default it is [clock]'s, so a line holds behind a pause
 * menu when the queue is on [Clock.World] and runs out over one when it is on [Clock.Ui]. A game
 * whose lines are voiced wants neither: the words must leave when the actor stops speaking, however
 * long the frame took and however much the sound card drifted. That game passes `clock = null` and
 * calls [playTo] with the audio system's playback position every frame, and the subtitles are then
 * as accurate as the audio is.
 *
 * Nothing here costs anything while it is empty: with nothing to say there is no text to compose,
 * no band to draw and no frame to ask for.
 *
 * @param capacity how many lines are up at once. Two by default, so a caption for a sound can sit
 *   under the sentence somebody is speaking — which is the case captions exist for. One is the
 *   strictest reading of a subtitle and is perfectly reasonable.
 * @param backlog how many may wait behind those. Past this the **oldest** waiting line is dropped,
 *   because a queue still reading out a fight that finished a minute ago is worse than a gap.
 * @param clock which clock the lines are timed against, or null for a queue the game drives itself
 *   through [advance] or [playTo].
 * @param charactersPerSecond how fast the player is assumed to read, for a line with no duration of
 *   its own. Fourteen is a slow, safe reading speed; subtitling guidelines sit between twelve and
 *   twenty.
 * @param minimumMillis how long even a two-word line stays up. A line that flashes is a line that
 *   was not read.
 */
@Stable
@Suppress("LongParameterList")
class SubtitleQueue(
    val capacity: Int = 2,
    val backlog: Int = 32,
    val clock: Clock? = Clock.Ui,
    val charactersPerSecond: Float = 14f,
    val minimumMillis: Int = 1_000,
) {

    init {
        require(capacity > 0) { "at least one line is up at a time, not $capacity" }
        require(backlog >= 0) { "a backlog holds none or more, not $backlog" }
        require(charactersPerSecond > 0f) { "a reading speed is faster than nothing, not $charactersPerSecond" }
        require(minimumMillis >= 0) { "a line stays up for no negative time, not $minimumMillis" }
    }

    private val onScreen = mutableStateListOf<SubtitleLine>()
    private val queued = mutableStateListOf<SubtitleLine>()

    /** Where the audio was the last time [playTo] was called, or [NotPlaying] before the first. */
    private var playhead = NotPlaying

    /** What is up now, oldest first. */
    val shown: List<SubtitleLine> get() = onScreen

    /** How many lines are waiting their turn. */
    val waiting: Int get() = queued.size

    /** Nothing up and nothing waiting, which is when this costs nothing at all. */
    val isIdle: Boolean get() = onScreen.isEmpty() && queued.isEmpty()

    /**
     * Says a line. It goes up now if there is room and waits behind the others if not.
     *
     * @see SubtitleLine for what each parameter means.
     */
    @Suppress("LongParameterList")
    fun show(
        text: String,
        speaker: String? = null,
        durationMillis: Int = 0,
        speakerColour: Colour? = null,
        style: String? = null,
    ): SubtitleLine = show(SubtitleLine(text, speaker, speakerColour, false, durationMillis, style))

    /**
     * Writes down a sound rather than a word: `[a door slams]`, `[thunder]`.
     *
     * The brackets are the game's own, because what goes round a caption is a house style and a
     * translated string already has them where its language puts them.
     */
    fun caption(text: String, durationMillis: Int = 0, style: String? = null): SubtitleLine =
        show(SubtitleLine(text, caption = true, durationMillis = durationMillis, style = style))

    /**
     * Says a line a game built itself, so that it can keep hold of it and [dismiss] it later.
     *
     * **A line already up or already waiting is left where it is.** Identity is the object, and one
     * object counts down once: a game that kept a line and showed it twice would otherwise get two
     * of it on the band, sharing one countdown, with [dismiss] only ever taking one of them away.
     * Say the same words twice by saying a second [SubtitleLine] with the same text.
     */
    fun show(line: SubtitleLine): SubtitleLine {
        if (line in onScreen || line in queued) return line
        if (onScreen.size < capacity) {
            raise(line)
        } else {
            queued.add(line)
            while (queued.size > backlog) queued.removeAt(0)
        }
        return line
    }

    /**
     * Takes a line down early — the player skipped it, or the scene it belonged to ended.
     *
     * The next one waiting takes its place straight away, so skipping is how a player gets through
     * a conversation quickly rather than how they lose the next sentence.
     */
    fun dismiss(line: SubtitleLine) {
        if (onScreen.remove(line)) admit() else queued.remove(line)
    }

    /** Everything gone at once: a scene change, a death, a skipped cutscene. */
    fun clear() {
        onScreen.clear()
        queued.clear()
        playhead = NotPlaying
    }

    /**
     * Takes [millis] off every line that is up, and moves the queue on past the ones that ran out.
     *
     * [Subtitles] calls this from [clock] by itself. A game calls it only when it is driving the
     * queue from somewhere else — a cutscene's own timeline, a recording being scrubbed.
     */
    fun advance(millis: Int) {
        if (millis <= 0 || onScreen.isEmpty()) return
        onScreen.forEach { it.left -= millis }
        if (onScreen.removeAll { it.left <= 0 }) admit()
    }

    /**
     * Moves the queue to where the audio has got to, in milliseconds from the start of playback.
     *
     * Called every frame with whatever the sound system reports. The first call only takes the
     * mark — there is no gap to measure yet — and after that each one advances the queue by the
     * distance the audio travelled, so a frame that took 40ms takes 40ms off the line however long
     * the game thought the frame was.
     *
     * **A position that goes backwards clears the queue.** The audio only goes backwards when the
     * player seeked, restarted the scene or skipped to the next one, and in every one of those the
     * words on screen belong to a moment that is no longer happening.
     */
    fun playTo(positionMillis: Long) {
        val was = playhead
        playhead = positionMillis
        if (was == NotPlaying) return
        if (positionMillis < was) {
            clear()
            playhead = positionMillis
            return
        }
        advance((positionMillis - was).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Puts a line up and starts its clock. */
    private fun raise(line: SubtitleLine) {
        line.left = if (line.durationMillis > 0) line.durationMillis else readingTime(line.text)
        onScreen.add(line)
    }

    /** Fills the empty places from the queue, oldest first. */
    private fun admit() {
        while (queued.isNotEmpty() && onScreen.size < capacity) raise(queued.removeAt(0))
    }

    /**
     * How long a line with no duration of its own stays up.
     *
     * Characters rather than words, because a word is not the same length in every language and the
     * point of this number is that a game which recorded no timings still gets readable subtitles.
     */
    private fun readingTime(text: String): Int =
        ((text.length / charactersPerSecond) * 1_000f).roundToInt().coerceAtLeast(minimumMillis)

    private companion object {

        /** No position taken yet, so the next one is a mark rather than a step. */
        const val NotPlaying = Long.MIN_VALUE
    }
}

/** A queue that lives as long as the screen it is on. */
@Suppress("LongParameterList")
@Composable
fun rememberSubtitleQueue(
    capacity: Int = 2,
    backlog: Int = 32,
    clock: Clock? = Clock.Ui,
    charactersPerSecond: Float = 14f,
    minimumMillis: Int = 1_000,
): SubtitleQueue = remember(capacity, backlog, clock, charactersPerSecond, minimumMillis) {
    SubtitleQueue(capacity, backlog, clock, charactersPerSecond, minimumMillis)
}

/**
 * How big the player asked for their subtitles to be.
 *
 * Presets rather than a slider, because a font is baked at whole sizes at startup and a slider
 * would mean baking every size between. Four is what console certification asks for and what
 * players recognise.
 *
 * ```kotlin
 * // Every size the fonts have to carry for the presets to work:
 * fonts.registerTrueType("body", file, scaledTextSizes(listOf(16), SubtitleSize.scales))
 * ```
 *
 * @param scale how much bigger than the skin says the letters are drawn.
 */
enum class SubtitleSize(val scale: Float) {
    Small(0.85f),
    Medium(1f),
    Large(1.25f),
    Huge(1.5f),
    ;

    companion object {

        /** Every preset's scale, for [dev.wildware.composegl.ui.text.scaledTextSizes]. */
        val scales: List<Float> = entries.map { it.scale }
    }
}

/**
 * The three things a player is allowed to change about subtitles, and what they are set to.
 *
 * A game holds one of these in its settings, saves it with the rest and hands it to [Subtitles].
 * Every field here is a row in an accessibility menu; nothing here is a decision the game makes on
 * the player's behalf.
 *
 * ```kotlin
 * var subtitles by remember { mutableStateOf(SubtitleSettings()) }
 * Subtitles(subs, settings = subtitles)
 *
 * // in the options screen:
 * Toggle(subtitles.speakerNames, { subtitles = subtitles.copy(speakerNames = it) }, "Speaker names")
 * ```
 *
 * @param size how big the letters are, **on its own rather than on top of the interface's text
 *   scale**. A player sets their subtitle size in the subtitle menu and the number they set is the
 *   number they get, whatever the rest of the interface happens to be scaled to — which is the
 *   whole point of a subtitle size being its own setting. See
 *   [dev.wildware.composegl.ui.widget.LocalTextScale] for the setting that does scale everything.
 * @param backgroundOpacity how solid the band behind the words is, from nothing to the skin's own.
 *   The one setting that matters most over bright scenes, and the one a player turns down once
 *   they are used to reading them.
 * @param speakerNames whether who is speaking is written above the line.
 * @param maxLines how many lines one subtitle may take before it is cut off with an ellipsis. Three
 *   is the usual ceiling; two is the usual choice.
 * @param widthFraction how much of the width the words may use before they wrap. A subtitle that
 *   runs the whole way across a wide screen is read by moving your head.
 */
data class SubtitleSettings(
    val size: SubtitleSize = SubtitleSize.Medium,
    val backgroundOpacity: Float = 0.8f,
    val speakerNames: Boolean = true,
    val maxLines: Int = 3,
    val widthFraction: Float = 0.68f,
) {

    init {
        require(backgroundOpacity in 0f..1f) { "a background opacity is 0 to 1, not $backgroundOpacity" }
        require(maxLines > 0) { "a subtitle has at least one line, not $maxLines" }
        require(widthFraction > 0f && widthFraction <= 1f) { "a width fraction is 0 to 1, not $widthFraction" }
    }
}

/**
 * Draws [queue]: the line being spoken, the captions beside it, and who is speaking.
 *
 * Where it goes is the game's — this is a band that wraps its own words, and a game puts it where
 * its own safe area is. Bottom centre, a little up from the edge, is what nearly every game means:
 *
 * ```kotlin
 * val subs = rememberSubtitleQueue()
 * Subtitles(subs, Modifier.align(Alignment.BottomCentre).padding(bottom = 64f), settings = settings.subtitles)
 * ```
 *
 * **The words wrap and are cut to a line limit**, at [SubtitleSettings.widthFraction] of the width
 * this is given rather than at a fixed number of pixels, so the same setting reads the same on a
 * handheld and on a television. Wrapping, line breaking and the direction the words run are the
 * text stack's, so an Arabic or Hebrew line is laid out right to left with no flag to set — and the
 * band itself is centred, which is the same place in either.
 *
 * **Nothing here is a control.** It takes no focus, swallows no click and has no state a player can
 * get stuck in: a subtitle over a fight must never be the thing that eats the button press. Its
 * words are not selectable either, so a screen wrapped in a
 * [dev.wildware.composegl.ui.widget.SelectionContainer] keeps the shot fired through the band.
 *
 * With nothing to say it draws nothing and asks for no frames.
 *
 * @param settings the player's own subtitle options. @see SubtitleSettings
 * @param speakerColours the colour each speaker's name is written in, for the usual case of a cast
 *   fixed at the start of a scene. A line's own [SubtitleLine.speakerColour] wins over this, and a
 *   speaker in neither takes the skin's `"<style>.speaker"` colour.
 * @param style the skin style of the band and of the words on it. `"<style>.speaker"` is the name
 *   above a line and `"<style>.caption"` is a sound written down, both falling back to [style]. A
 *   line with a [SubtitleLine.style] of its own takes that style's own `.speaker` where the skin has
 *   one, and this one's where it has not.
 */
@Composable
fun Subtitles(
    queue: SubtitleQueue,
    modifier: Modifier = Modifier,
    settings: SubtitleSettings = SubtitleSettings(),
    speakerColours: Map<String, Colour> = emptyMap(),
    style: String = "subtitle",
) {
    val clocks = LocalClocks.current

    // Only while there is something to time. An idle queue holds no frame subscription at all, so a
    // game with subtitles turned on and nobody speaking pays exactly nothing for them.
    val ticking = if (queue.isIdle) null else queue.clock
    LaunchedEffect(queue, clocks, ticking) {
        val clock = ticking ?: return@LaunchedEffect
        clocks.register(clock)
        var counted = clocks.time(clock)
        while (true) {
            withFrameNanos { }
            // The remainder is kept rather than rounded away: sixteen milliseconds taken from a
            // 16.67ms frame is a subtitle four per cent short by the end of a long line.
            val millis = (clocks.time(clock) - counted) / 1_000_000L
            if (millis > 0L) {
                queue.advance(millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                counted += millis * 1_000_000L
            }
        }
    }

    if (queue.shown.isEmpty()) return

    val band = rememberStyle(style)
    // The opacity is the player's, and it belongs to the band rather than to the words: text faded
    // to match its own background is text nobody asked to be harder to read.
    val faded = remember(band, settings.backgroundOpacity) {
        band.copy(tint = band.tint.scaleAlpha(settings.backgroundOpacity))
    }

    // Provided rather than multiplied in, unlike ProvideTextScale. A subtitle size is its own
    // setting in its own menu; a player who set the interface to 150% and subtitles to Medium asked
    // for Medium subtitles.
    CompositionLocalProvider(LocalTextScale provides settings.size.scale) {
        WrappingAt(settings.widthFraction, modifier) {
            Column(
                Modifier.styled(faded),
                verticalArrangement = Arrangement.spacedBy(LineGap),
                horizontalAlignment = HorizontalAlignment.Centre,
            ) {
                queue.shown.forEach { line ->
                    key(line) { SubtitleEntry(line, settings, speakerColours, style) }
                }
            }
        }
    }
}

/** One line: who is speaking, and what they said. */
@Composable
private fun SubtitleEntry(
    line: SubtitleLine,
    settings: SubtitleSettings,
    speakerColours: Map<String, Colour>,
    style: String,
) {
    // A caption is a sound rather than a sentence, so nobody is speaking it however the game filled
    // the line in.
    val speaker = line.speaker?.takeIf { settings.speakerNames && !line.caption }

    // A line's own style covers the name over it as well as the words, when the skin has a name
    // style to go with it: a radio voice under a normal-voice name is half a radio voice. Asked for
    // by name rather than left to the skin's own one-dot-at-a-time fallback, which would otherwise
    // draw the name in the spoken style — a name the size of a sentence — for a line style with no
    // `.speaker` of its own.
    val skin = LocalSkin.current
    val named = line.style?.let { "$it.speaker" }?.takeIf { skin.has(it) } ?: "$style.speaker"

    Column(horizontalAlignment = HorizontalAlignment.Centre) {
        // Nothing here is a control, and nothing here is selectable either: a game that wrapped its
        // screen in a SelectionContainer so a seed could be copied would otherwise lose the shot it
        // fired through the band to text selection. Hotbar and Notifications do the same.
        DisableSelection {
            if (speaker != null) {
                Text(
                    speaker,
                    style = named,
                    colour = line.speakerColour ?: speakerColours[speaker],
                    align = HorizontalAlignment.Centre,
                    runs = emptyList(),
                )
            }
            Text(
                line.text,
                style = line.style ?: if (line.caption) "$style.caption" else style,
                align = HorizontalAlignment.Centre,
                maxLines = settings.maxLines,
                // The overload that breaks its own lines, on purpose. It is the one that centres line
                // by line rather than centring a block of ragged lines — which is what a subtitle is —
                // and it is the one the toolkit lays out bidirectionally rather than handing the whole
                // string to a backend that draws every string left to right.
                runs = emptyList(),
            )
        }
    }
}

/**
 * One child, wrapped at [fraction] of the width this was given rather than at all of it.
 *
 * The band is then as wide as its longest line, which is what keeps a two-word caption from sitting
 * in the middle of a screen-wide box. A fraction rather than a width in pixels because the setting
 * has to mean the same thing on a handheld and on a television.
 */
@Composable
private fun WrappingAt(fraction: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val policy = remember(fraction) {
        MeasurePolicy { children, constraints ->
            val room = if (constraints.hasBoundedWidth) constraints.maxWidth * fraction else constraints.maxWidth
            val child = children.first().measure(constraints.copy(minWidth = 0f, maxWidth = room))
            // Centred rather than left-aligned, because a caller who said fillMaxWidth() made this
            // node wider than its band and a band pinned to one edge is the wrong place in either
            // language. Centre is the same place in both, which is what the widget promises.
            val width = constraints.constrainWidth(child.width)
            layout(width, constraints.constrainHeight(child.height)) {
                child.at((width - child.width) / 2f, 0f)
            }
        }
    }
    Layout(modifier, name = "subtitles", content = content, measurePolicy = policy)
}

/** The gap between two subtitles that are up together — a caption under a spoken line. */
private const val LineGap = 4f
