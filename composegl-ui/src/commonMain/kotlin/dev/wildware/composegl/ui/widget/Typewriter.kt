package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import kotlin.math.sin

/**
 * A line of dialogue, and how much of it the player has been shown.
 *
 * Held apart from the widget because a game asks it things and tells it things: whether the line
 * has finished, and that the player pressed the button to see the rest of it now. [skip] is the
 * one every game needs and half of them get wrong — a player who has read ahead should get the
 * whole line at once, not a faster crawl.
 *
 * Time is a [Clock]'s, so a line can be on the world's clock and stop when the world does.
 */
class TypewriterState internal constructor(text: String, val clock: Clock) {

    var text by mutableStateOf(text)
        private set

    /** How many characters have been shown. */
    var revealed by mutableStateOf(0)
        private set

    val isRevealing: Boolean get() = revealed < text.length

    val isFinished: Boolean get() = !isRevealing

    /** When each character appeared, in the clock's nanoseconds. Read by a per-character effect. */
    private var appeared = LongArray(text.length)

    /**
     * Bumped once a frame while anything is still moving.
     *
     * The widget draws from this object rather than from its arguments, so something in the
     * composition has to change for the node to be redrawn. This is that something, and it stops
     * changing the moment the line has settled — which is what makes a finished line free.
     */
    internal var pulse by mutableStateOf(0)
        private set

    internal fun markFrame() {
        pulse++
    }

    private var nextAt = NotStarted

    /** The whole line, now. What the button a player presses to read ahead is wired to. */
    fun skip() {
        if (!isRevealing) return
        val now = lastNow
        for (index in revealed until text.length) appeared[index] = now
        revealed = text.length
    }

    /** Back to the beginning of the same line. */
    fun restart() {
        revealed = 0
        nextAt = NotStarted
    }

    internal fun replace(value: String) {
        if (value == text) return
        text = value
        appeared = LongArray(value.length)
        restart()
    }

    private var lastNow = 0L

    /**
     * Shows whatever [now] says should be showing.
     *
     * Driven by the clock rather than by counting frames, so a slow frame shows the characters it
     * owed rather than falling behind, and a stopped clock shows nothing new at all.
     *
     * @return whether there is still more to show.
     */
    internal fun tick(now: Long, perCharacterNanos: Long, pauses: Boolean): Boolean {
        lastNow = now
        if (nextAt == NotStarted) nextAt = now
        while (isRevealing && now >= nextAt) {
            val character = text[revealed]
            appeared[revealed] = nextAt
            revealed++
            nextAt += perCharacterNanos * (if (pauses) character.beat() else 1)
        }
        return isRevealing
    }

    /** How long character [index] has been on screen, in seconds. Zero for one not shown yet. */
    internal fun ageOf(index: Int, now: Long): Float {
        if (index >= revealed) return 0f
        return ((now - appeared[index]).coerceAtLeast(0L)) / 1_000_000_000f
    }

    private companion object {
        const val NotStarted = Long.MIN_VALUE
    }
}

/**
 * How long to wait after a character, as a multiple of the usual gap.
 *
 * Punctuation is where a voice would stop, and text that stops there reads as speech rather than
 * as a machine printing. Nothing here is tuned by measurement — it is what reading out loud
 * sounds like.
 */
private fun Char.beat(): Int = when (this) {
    '.', '!', '?', '…', '\n' -> 8
    ',', ';', ':', '—' -> 4
    else -> 1
}

/** A typewriter for [text], which starts again whenever the text changes. */
@Composable
fun rememberTypewriter(text: String, clock: Clock = Clock.Ui): TypewriterState {
    val state = remember(clock) { TypewriterState(text, clock) }
    state.replace(text)
    return state
}

/**
 * What one character looks like as it appears, for an effect to change.
 *
 * One object, reused for every character of every frame: a paragraph is hundreds of characters and
 * a new object each would be hundreds of allocations a frame, on the one screen a game is most
 * likely to be reading from a disc in the background.
 */
class RevealedCharacter internal constructor() {

    /** Where this character is in the whole text. */
    var index: Int = 0
        internal set

    var character: Char = ' '
        internal set

    /** How long it has been on screen, in seconds. */
    var age: Float = 0f
        internal set

    /** Moved by this much from where it would be. A shake, a bounce, a letter falling in. */
    var offsetX: Float = 0f

    var offsetY: Float = 0f

    var colour: Colour = Colour.White

    internal fun begin(index: Int, character: Char, age: Float, colour: Colour) {
        this.index = index
        this.character = character
        this.age = age
        this.colour = colour
        offsetX = 0f
        offsetY = 0f
    }
}

/**
 * Changes how each character of a revealed line looks.
 *
 * Called for every character on screen, every frame, in order. Change what you want on the
 * [RevealedCharacter] and leave the rest — it is handed back the way the skin had it each time.
 *
 * Note what having one costs: the text is drawn a character at a time rather than a line at a
 * time, so kerning between characters is lost. That is the trade an effect makes, and it is the
 * reason there is a fast path without one.
 */
fun interface TypewriterEffect {

    fun style(character: RevealedCharacter)

    companion object {

        /** A letter that shakes for a moment as it lands, and then settles. */
        fun shake(amount: Float = 1.5f, settleSeconds: Float = 0.18f): TypewriterEffect =
            TypewriterEffect { character ->
                val left = 1f - (character.age / settleSeconds).coerceIn(0f, 1f)
                if (left <= 0f) return@TypewriterEffect
                // A different phase per character, so a word jitters rather than waving.
                val phase = character.index * 1.7f + character.age * 90f
                character.offsetX += sin(phase) * amount * left
                character.offsetY += sin(phase * 1.3f) * amount * left
            }

        /** A letter that fades up rather than appearing. */
        fun fadeIn(seconds: Float = 0.12f): TypewriterEffect =
            TypewriterEffect { character ->
                val through = (character.age / seconds).coerceIn(0f, 1f)
                character.colour = character.colour.scaleAlpha(through)
            }
    }
}

/**
 * Text that appears a character at a time.
 *
 * The layout **never moves**: the whole line is measured before the first character is shown, so
 * the box a paragraph sits in is its final size from the start and nothing below it jumps as the
 * text arrives. That is the one thing a typewriter has to get right, and the reason this measures
 * what it will eventually say rather than what it is saying now.
 *
 * It pauses at punctuation, so a line reads like somebody talking rather than a machine printing,
 * and [TypewriterState.skip] shows the rest at once for a player who has read ahead.
 *
 * ```kotlin
 * val line = rememberTypewriter(dialogue[at])
 * Typewriter(line, Modifier.clickable { if (line.isFinished) advance() else line.skip() })
 * ```
 *
 * @param charactersPerSecond how fast it types. Thirty is slow and deliberate, sixty is brisk.
 * @param pauses whether to rest at punctuation.
 * @param effect an optional per-character effect. See [TypewriterEffect] for what it costs.
 * @param onFinished called once, on the frame the last character appears.
 */
@Composable
fun Typewriter(
    state: TypewriterState,
    modifier: Modifier = Modifier,
    style: String? = null,
    textStyle: TextStyle? = null,
    colour: Colour? = null,
    charactersPerSecond: Float = 45f,
    pauses: Boolean = true,
    effect: TypewriterEffect? = null,
    onFinished: () -> Unit = {},
) {
    val clocks = LocalClocks.current
    val named = rememberStyle(style ?: "label")
    val inherited = LocalContentStyle.current
    val resolved = if (style == null && inherited != null) inherited else named
    val fonts = rememberFonts()
    val face = textStyle ?: resolved.textStyle
    val ink = colour ?: resolved.textColour

    // Only while there is more to show. A finished line asks the runtime for nothing at all.
    LaunchedEffect(state, state.text, charactersPerSecond, pauses, effect) {
        clocks.register(state.clock)
        val perCharacter = (1_000_000_000f / charactersPerSecond.coerceAtLeast(1f)).toLong()
        while (state.tick(clocks.time(state.clock), perCharacter, pauses)) {
            withFrameNanos { }
            state.markFrame()
        }
        onFinished()

        // An effect is still moving the last few characters after the last one has appeared, so
        // the frames carry on for as long as one could still be settling — and then stop.
        if (effect != null) {
            val until = clocks.time(state.clock) + EffectTailNanos
            while (clocks.time(state.clock) < until) {
                withFrameNanos { }
                state.markFrame()
            }
        }
    }

    val painter = remember(state.text, face, ink, fonts, effect) {
        TypewriterPainter(state, face, ink, fonts, effect, clocks)
    }

    // A new lambda whenever anything has changed, because a node is only redrawn when what it was
    // told to draw is not what it was told last time.
    val draw = remember(painter, state.revealed, state.pulse) { painter.drawAt(state.revealed) }

    LeafLayout(modifier, name = "typewriter", measurePolicy = painter, draw = draw)
}

/**
 * The measuring and the drawing of a line that is still arriving.
 *
 * Lines are broken here rather than by the backend, because this has to know where each character
 * is and a backend only hands back a size. Greedy on spaces, which is what wrapping is; the price
 * is that a line broken here and the same line measured whole could differ by a hair of kerning at
 * the break, which nothing can see.
 */
private class TypewriterPainter(
    private val state: TypewriterState,
    private val style: TextStyle,
    private val colour: Colour,
    private val fonts: FontProvider,
    private val effect: TypewriterEffect?,
    private val clocks: dev.wildware.composegl.ui.animation.Clocks,
) : MeasurePolicy {

    private var lines: List<Line> = emptyList()

    private val scratch = RevealedCharacter()

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        lines = wrap(state.text, constraints.maxWidth)
        val width = lines.maxOfOrNull { it.whole.size.width } ?: 0f
        val height = lines.size * style.lineHeight
        return layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {}
    }

    /**
     * The text, broken into lines that will never be broken again.
     *
     * Done once, on the whole text, which is what stops the layout moving: a line that is only
     * half shown is still as wide as it is going to be.
     */
    private fun wrap(text: String, maxWidth: Float): List<Line> {
        val result = mutableListOf<Line>()
        var start = 0
        text.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                result += Line(start, "", fonts.measure("", style))
                start += 1
                return@forEach
            }
            var lineStart = 0
            var lastBreak = -1
            var index = 0
            while (index < paragraph.length) {
                if (paragraph[index] == ' ') lastBreak = index
                val candidate = paragraph.substring(lineStart, index + 1)
                if (fonts.measure(candidate, style).size.width > maxWidth && lastBreak > lineStart) {
                    val line = paragraph.substring(lineStart, lastBreak)
                    result += Line(start + lineStart, line, fonts.measure(line, style))
                    lineStart = lastBreak + 1
                    lastBreak = -1
                    index = lineStart
                    continue
                }
                index++
            }
            val line = paragraph.substring(lineStart)
            result += Line(start + lineStart, line, fonts.measure(line, style))
            start += paragraph.length + 1
        }
        return result
    }

    /** What to draw with [revealed] characters showing. */
    fun drawAt(revealed: Int): UiCanvas.(Rect) -> Unit = { bounds ->
        val now = clocks.time(state.clock)
        lines.forEachIndexed { index, line ->
            val shown = (revealed - line.start).coerceIn(0, line.text.length)
            if (shown > 0) {
                val y = bounds.top + index * style.lineHeight
                if (effect == null) {
                    text(line.prefix(shown), bounds.left, y, colour)
                } else {
                    drawWithEffect(this, line, shown, bounds.left, y, now)
                }
            }
        }
    }

    /** One character at a time, because an effect moves and colours them one at a time. */
    private fun drawWithEffect(canvas: UiCanvas, line: Line, shown: Int, left: Float, top: Float, now: Long) {
        for (offset in 0 until shown) {
            val character = line.text[offset]
            if (character == ' ') continue
            scratch.begin(line.start + offset, character, state.ageOf(line.start + offset, now), colour)
            effect?.style(scratch)
            canvas.text(
                line.glyph(character),
                left + line.xOf(offset) + scratch.offsetX,
                top + scratch.offsetY,
                scratch.colour,
            )
        }
    }

    /** One line of the wrapped text, and everything measured about it, measured once. */
    private inner class Line(val start: Int, val text: String, val whole: TextLayout) {

        private val prefixes = HashMap<Int, TextLayout>()
        private val glyphs = HashMap<Char, TextLayout>()
        private val xs = FloatArray(text.length + 1) { Unmeasured }

        /** The first [count] characters, measured. Kept, because the same prefix is drawn for frames. */
        fun prefix(count: Int): TextLayout =
            if (count >= text.length) whole else prefixes.getOrPut(count) {
                fonts.measure(text.substring(0, count), style)
            }

        fun glyph(character: Char): TextLayout =
            glyphs.getOrPut(character) { fonts.measure(character.toString(), style) }

        /** How far along the line character [offset] starts. */
        fun xOf(offset: Int): Float {
            if (xs[offset] == Unmeasured) {
                xs[offset] = if (offset == 0) 0f else fonts.measure(text.substring(0, offset), style).size.width
            }
            return xs[offset]
        }
    }
}

private const val Unmeasured = -1f

/** How long the frames carry on after the last character, so an effect can finish settling. */
private const val EffectTailNanos = 500_000_000L
