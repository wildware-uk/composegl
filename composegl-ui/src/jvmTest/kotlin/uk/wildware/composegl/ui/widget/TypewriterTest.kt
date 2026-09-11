package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.width
import uk.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Text that arrives a character at a time.
 *
 * The issue's three: skipping shows the whole line at once, the layout does not move while the
 * text arrives, and an effect can move and colour each character.
 */
class TypewriterTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect(0f, 0f, 400f, 300f))

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(Rect(0f, 0f, 400f, 300f))
        MeasurePass().run(host.root, Constraints.atMost(400f, 300f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(2)
    }

    /** What is on screen, in the order it was drawn — one call a line, or one a character. */
    private fun shown(): String =
        canvas.calls.filterIsInstance<DrawCall.Text>().joinToString("") { it.text }

    private fun node(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { node(name, it) }

    private fun size() = node("typewriter")!!.let { it.width to it.height }

    // --- the issue's three ----------------------------------------------------------------------

    @Test
    fun `skipping shows the whole line at once`() {
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter("the relay went quiet six hours ago", Clock.Ui)
            Typewriter(line, charactersPerSecond = 20f, pauses = false)
        }

        frames(4, 16)
        assertTrue(line.revealed < line.text.length, "it should still be arriving")

        line.skip()
        frames(2, 0)

        assertEquals(line.text, shown(), "a player who has read ahead gets the whole line")
        assertTrue(line.isFinished)
    }

    @Test
    fun `the layout does not move as the text arrives`() {
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter(
                "the relay went quiet six hours ago and nobody has heard from it since",
                Clock.Ui,
            )
            Typewriter(line, Modifier.width(200f), charactersPerSecond = 60f, pauses = false)
        }

        frames(2, 16)
        val first = size()

        repeat(20) {
            frames(2, 40)
            assertEquals(first, size(), "the box moved while the text was still arriving")
        }
        assertTrue(line.isFinished, "and by now it has all arrived")
    }

    @Test
    fun `an effect moves and colours each character on its own`() {
        val shifted = TypewriterEffect { character ->
            character.offsetY = character.index.toFloat()
            character.colour = uk.wildware.composegl.ui.graphics.Colour.argb(0xFF00FF00)
        }
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter("abc", Clock.Ui)
            Typewriter(line, charactersPerSecond = 1000f, pauses = false, effect = shifted)
        }

        frames(4, 16)

        val drawn = canvas.calls.filterIsInstance<DrawCall.Text>()
        assertEquals(listOf("a", "b", "c"), drawn.map { it.text }, "an effect draws a character at a time")
        assertTrue(drawn[1].at.y - drawn[0].at.y == 1f, "each one is moved on its own")
        assertTrue(drawn.all { it.colour == uk.wildware.composegl.ui.graphics.Colour.argb(0xFF00FF00) })
    }

    // --- the rest of it -------------------------------------------------------------------------

    @Test
    fun `it arrives a character at a time rather than all at once`() {
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter("hello there", Clock.Ui)
            Typewriter(line, charactersPerSecond = 20f, pauses = false)
        }

        frames(2, 60)
        val early = line.revealed
        frames(4, 60)

        assertTrue(early in 1..4, "too much arrived at once: $early")
        assertTrue(line.revealed > early, "and it kept going")
    }

    @Test
    fun `it rests at punctuation`() {
        lateinit var quick: TypewriterState
        lateinit var slow: TypewriterState
        show {
            quick = rememberTypewriter("abcdefgh", Clock.Ui)
            slow = rememberTypewriter("ab.cdefg", Clock.Ui)
            Typewriter(quick, charactersPerSecond = 30f, pauses = true)
            Typewriter(slow, charactersPerSecond = 30f, pauses = true)
        }

        frames(8, 33)

        assertTrue(slow.revealed < quick.revealed, "the full stop should have held it up")
    }

    @Test
    fun `a finished line costs nothing`() {
        show {
            val line = rememberTypewriter("short", Clock.Ui)
            Typewriter(line, charactersPerSecond = 100f, pauses = false)
        }

        frames(20, 40)

        repeat(50) { assertFalse(frame(), "frame $it redrew a line that has finished") }
    }

    @Test
    fun `it says when it has finished and only once`() {
        var finished = 0
        show {
            val line = rememberTypewriter("done", Clock.Ui)
            Typewriter(line, charactersPerSecond = 100f, pauses = false, onFinished = { finished++ })
        }

        frames(20, 40)

        assertEquals(1, finished, "a game that advances the dialogue on this would have skipped one")
    }

    @Test
    fun `new text starts again from the beginning`() {
        var which by mutableStateOf(0)
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter(if (which == 0) "first line" else "second line", Clock.Ui)
            Typewriter(line, charactersPerSecond = 30f, pauses = false)
        }

        frames(20, 40)
        assertTrue(line.isFinished)

        which = 1
        frames(2, 0)

        assertEquals("second line", line.text)
        assertTrue(line.revealed < line.text.length, "it should be typing the new line, not showing it")
    }

    @Test
    fun `a wrapped paragraph is drawn a line at a time`() {
        lateinit var line: TypewriterState
        show {
            line = rememberTypewriter("one two three four five six seven eight", Clock.Ui)
            Typewriter(line, Modifier.width(100f), charactersPerSecond = 500f, pauses = false)
        }

        frames(6, 40)

        val drawn = canvas.calls.filterIsInstance<DrawCall.Text>()
        assertTrue(drawn.size > 1, "it should have wrapped")
        assertTrue(drawn.all { it.at.x == drawn.first().at.x }, "every line starts at the same edge")
        assertEquals(line.text.replace(" ", ""), shown().replace(" ", ""), "every word is on screen once")
    }
}
