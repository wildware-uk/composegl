package composegl.ui.widget

import androidx.compose.runtime.Composable
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Rect
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.Action
import composegl.ui.input.InputSource
import composegl.ui.input.InputSourceTracker
import composegl.ui.input.Prompt
import composegl.ui.input.PromptStyle
import composegl.ui.input.Prompts
import composegl.ui.layout.Arrangement
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.Row
import composegl.ui.layout.VerticalAlignment
import composegl.ui.skin.Skin
import composegl.ui.modifier.Modifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The button a player is being asked to press.
 *
 * The issue's three: picking up a different device swaps every glyph on screen with nothing
 * reloaded, an action nobody bound draws something honest, and a glyph in the middle of a sentence
 * sits on the sentence's baseline.
 */
class PromptGlyphTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect(0f, 0f, 400f, 200f))
    private val source = InputSourceTracker(InputSource.Keyboard)
    private val prompts = Prompts()

    @AfterEach
    fun tearDown() = host.dispose()

    private var wall = 0L

    private fun frame() {
        wall += 16_000_000L
        host.frame(wall)
        canvas.clear(Rect(0f, 0f, 400f, 200f))
        MeasurePass().run(host.root, Constraints.atMost(400f, 200f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                ProvideInputSource(source) {
                    ProvidePrompts(prompts, content)
                }
            }
        }
        repeat(2) { frame() }
    }

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun glyph(): String = texts().first().text

    // --- the issue's three ----------------------------------------------------------------------

    @Test
    fun `picking up a pad swaps every glyph with nothing reloaded`() {
        show {
            Row {
                PromptGlyph(Action.Confirm)
                PromptGlyph(Action.Cancel)
            }
        }

        assertEquals(listOf("E", "ESC"), texts().map { it.text }, "a keyboard says E and ESC")

        source.saw(InputSource.Gamepad)
        repeat(2) { frame() }
        assertEquals(listOf("A", "B"), texts().map { it.text }, "an Xbox pad says A and B")

        prompts.padStyle = PromptStyle.PlayStation
        repeat(2) { frame() }
        assertEquals(listOf("✕", "○"), texts().map { it.text }, "a PlayStation pad has its own shapes")

        source.saw(InputSource.Mouse)
        repeat(2) { frame() }
        assertEquals(listOf("E", "ESC"), texts().map { it.text }, "and back again when the mouse moves")
    }

    @Test
    fun `an action nobody has bound draws something rather than nothing`() {
        show { PromptGlyph(Action("open-the-hatch")) }

        assertEquals(Prompt.Unbound.label, glyph(), "a blank prompt tells a player to press nothing")
        assertNotNull(canvas.calls.filterIsInstance<DrawCall.Rectangle>().firstOrNull(), "and the box is still drawn")
    }

    @Test
    fun `a glyph in a sentence sits on the sentence's baseline`() {
        show {
            Row(
                Modifier,
                horizontalArrangement = Arrangement.spacedBy(4f),
                verticalAlignment = VerticalAlignment.Centre,
            ) {
                Text("Press")
                PromptGlyph(Action.Confirm)
                Text("to open")
            }
        }

        // A baseline is where the letters sit, not where their boxes start: the prompt is drawn a
        // size smaller than the sentence, so the same top would be the wrong line.
        val fonts = MonospaceFontProvider()
        val sentence = Skin.Default.resolve("label").textStyle
        val prompt = Skin.Default.resolve("prompt").textStyle
        val drawn = texts()

        assertEquals(listOf("Press", "E", "to open"), drawn.map { it.text })
        assertEquals(
            drawn[0].at.y + fonts.metrics(sentence).ascent,
            drawn[1].at.y + fonts.metrics(prompt).ascent,
            0.5f,
            "the glyph's letter is off the line of the words",
        )
        assertEquals(drawn[0].at.y, drawn[2].at.y, 0.5f)
    }

    // --- the rest of it -------------------------------------------------------------------------

    @Test
    fun `rebinding changes what is drawn`() {
        show { PromptGlyph(Action.Interact) }

        assertEquals("F", glyph())

        prompts.bind(Action.Interact, PromptStyle.Keyboard, Prompt("key.q", "Q"))
        repeat(2) { frame() }

        assertEquals("Q", glyph(), "a rebinding screen writes here and every prompt follows")
    }

    @Test
    fun `a nintendo pad has A and B the other way round`() {
        prompts.padStyle = PromptStyle.Nintendo
        source.saw(InputSource.Gamepad)
        show {
            Row {
                PromptGlyph(Action.Confirm)
                PromptGlyph(Action.Cancel)
            }
        }

        assertEquals(listOf("A", "B"), texts().map { it.text })
        assertEquals("pad.east", prompts.prompt(Action.Confirm, PromptStyle.Nintendo).key, "A is the east button there")
    }

    @Test
    fun `a single letter is square and a long one is not squashed`() {
        show {
            Row {
                PromptGlyph(Action.Confirm)
                PromptGlyph(Action.Cancel)
            }
        }

        // Side by side with nothing between them, so the second box starts exactly where the
        // first one ends: ordered by their left edge rather than by a gap.
        val boxes = canvas.calls.filterIsInstance<DrawCall.Rectangle>().sortedBy { it.rect.left }
        val letter = boxes.first()
        val word = boxes.last()

        assertEquals(letter.rect.height, letter.rect.width, 0.5f, "one letter should be a square")
        assertTrue(word.rect.width > letter.rect.width, "and three letters need more room")
        assertEquals(letter.rect.height, word.rect.height, 0.5f, "both are one line high")
    }

    @Test
    fun `a prompt can be asked for a device the player is not using`() {
        show { PromptGlyph(Action.Confirm, promptStyle = PromptStyle.PlayStation) }

        assertEquals("✕", glyph(), "a settings screen shows every device at once")
    }
}
