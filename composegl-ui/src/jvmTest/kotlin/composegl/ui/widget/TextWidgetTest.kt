package composegl.ui.widget

import androidx.compose.runtime.Composable
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.geometry.Offset
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.graphics.UiCanvas
import composegl.ui.host.UiHost
import composegl.ui.layout.Constraints
import composegl.ui.layout.HorizontalAlignment
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.width
import composegl.ui.node.UiNode
import composegl.ui.skin.Skin
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The rule text has to keep: what was measured is what is drawn.
 *
 * Everything here is one assertion in different clothes. If a widget measures text once to decide
 * how much room to reserve and the backend lays it out again at draw time, the two can disagree —
 * a word moves to the next line, an ellipsis appears or does not — and the text ends up outside
 * the space kept for it. So the layout object handed back by the font provider is the object the
 * canvas is given, checked by identity rather than by value.
 */
class TextWidgetTest {

    private val host = UiHost()
    private val fonts = SpyFonts()
    private val canvas = CapturingCanvas()

    @AfterEach
    fun tearDown() = host.dispose()

    /** 16pt monospace at 0.6: every character is 9.6 wide and every line 20 tall. */
    private val advance = 9.6f
    private val line = 20f

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(fonts) { content() } }
    }

    private fun frame(width: Float = 400f, height: Float = 400f) {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(width, height))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private var clock = 0L

    private fun text() = find(host.root, "text") ?: error("no text node was composed")

    private fun find(node: UiNode, name: String): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(it, name) }

    private fun drawn() = canvas.calls.filterIsInstance<DrawCall.Text>().single()

    @Test
    fun `the layout that was measured is the layout that is drawn`() {
        show { Text("hello") }

        frame()

        assertEquals(1, fonts.handed.size, "measured once, not once per pass")
        assertSame(fonts.handed.single(), canvas.drawn.single(), "and the same object reaches the canvas")
    }

    @Test
    fun `a run of text is exactly as big as what was measured`() {
        show { Text("hello") }

        frame()

        val measured = fonts.handed.single()
        assertEquals(measured.size.width, text().width, "the node is the width of the text")
        assertEquals(measured.size.height, text().height)
        assertEquals(Offset(0f, 0f), drawn().at, "drawn at its own top-left")
    }

    @Test
    fun `wrapping happens at the width the node was given`() {
        show { Text("the relay went quiet six hours ago", Modifier.width(10f * advance)) }

        frame()

        assertEquals(10f * advance, fonts.asked.single().maxWidth, "the constraint reached the font")
        val measured = fonts.handed.single()
        assertTrue(measured.lineCount > 1, "it wrapped")
        assertTrue(measured.size.width <= 10f * advance, "and nothing sticks out sideways")
        assertEquals(measured.lineCount * line, text().height, "the node reserved every line it drew")
    }

    @Test
    fun `without soft wrap the text is measured as one line and is allowed to run on`() {
        show { Text("the relay went quiet six hours ago", Modifier.width(10f * advance), softWrap = false) }

        frame()

        assertEquals(Float.POSITIVE_INFINITY, fonts.asked.single().maxWidth)
        assertEquals(1, fonts.handed.single().lineCount)
        assertEquals(10f * advance, text().width, "the node is still only as wide as it was allowed")
    }

    @Test
    fun `maxLines cuts the text off and the node is the height of what is left`() {
        show { Text("the relay went quiet six hours ago", Modifier.width(10f * advance), maxLines = 2) }

        frame()

        assertEquals(2, fonts.asked.single().style.maxLines, "the limit reached the font")
        val measured = fonts.handed.single()
        assertEquals(2, measured.lineCount, "two lines and an ellipsis")
        assertEquals(2f * line, text().height, "and the node is two lines tall, not five")
        assertEquals(2f * line, measured.size.height)
    }

    @Test
    fun `ellipsised text still measures exactly as it draws`() {
        show { Text("the relay went quiet six hours ago", Modifier.width(10f * advance), maxLines = 2) }

        frame()

        assertSame(fonts.handed.single(), canvas.drawn.single())
        assertEquals(Offset(0f, 0f), drawn().at)
    }

    @Test
    fun `the ellipsis is the caller's to choose`() {
        show { Text("the relay went quiet six hours ago", Modifier.width(10f * advance), maxLines = 1, ellipsis = ">>") }

        frame()

        assertEquals(">>", fonts.asked.single().style.ellipsis, "what a cut line ends with reached the font")
        assertEquals(1, fonts.handed.single().lineCount)
    }

    @Test
    fun `alignment moves the text inside the room and not the room itself`() {
        show {
            Text("ab", Modifier.width(100f), align = HorizontalAlignment.Centre)
        }

        frame()

        val measured = fonts.handed.single()
        assertEquals(100f, text().width, "the node still fills the width it was given")
        assertEquals(0f, text().x, "and has not moved")
        assertEquals((100f - measured.size.width) / 2f, drawn().at.x, "only the block inside it moved")
    }

    @Test
    fun `alignment at the end puts the last character against the right edge`() {
        show { Text("ab", Modifier.width(100f), align = HorizontalAlignment.End) }

        frame()

        assertEquals(100f - 2f * advance, drawn().at.x)
    }

    @Test
    fun `the colour comes from the skin`() {
        show { Text("hello") }
        frame()
        assertEquals(Skin.Default.resolve("label").textColour, drawn().colour, "no colour is written in the widget")
    }

    @Test
    fun `an explicit colour wins over the style`() {
        show { Text("hello", colour = Colour.rgb(0xFF00FF)) }

        frame()

        assertEquals(Colour.rgb(0xFF00FF), drawn().colour)
    }

    @Test
    fun `a named style picks the font the skin says`() {
        show { Text("hello", style = "label.title") }

        frame()

        assertEquals(Skin.Default.resolve("label.title").textStyle.size, fonts.asked.single().style.size)
    }

    @Test
    fun `text with no fonts anywhere says so instead of drawing nothing`() {
        val bare = UiHost()
        try {
            val failure = assertThrows<IllegalStateException> {
                bare.setContent { Text("hello") }
                bare.frame(0L)
            }
            assertTrue(failure.message.orEmpty().contains("no fonts"), "it says what is missing: ${failure.message}")
        } finally {
            bare.dispose()
        }
    }

    @Test
    fun `the same text twice does not redraw the frame`() {
        show { Text("hello") }
        frame()

        assertEquals(false, host.frame(clock), "nothing changed, so nothing is invalidated")
    }

    /** What was asked for, and what was handed back, so a test can assert on both ends. */
    private class SpyFonts(private val real: FontProvider = MonospaceFontProvider()) : FontProvider {

        data class Ask(val text: String, val style: TextStyle, val maxWidth: Float)

        val asked = mutableListOf<Ask>()
        val handed = mutableListOf<TextLayout>()

        override fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout {
            asked += Ask(text, style, maxWidth)
            return real.measure(text, style, maxWidth).also { handed += it }
        }

        override fun metrics(style: TextStyle) = real.metrics(style)
    }

    /**
     * A recording canvas that also keeps the layout objects themselves.
     *
     * [RecordingCanvas] writes down the string, which is the right thing for nearly every test and
     * the wrong thing for this one: two different layouts of the same string have the same string.
     */
    private class CapturingCanvas(private val inner: RecordingCanvas = RecordingCanvas()) : UiCanvas by inner {

        val drawn = mutableListOf<TextLayout>()

        val calls: List<DrawCall> get() = inner.calls

        fun clear() = inner.clear()

        fun assertBalanced() = inner.assertBalanced()

        override fun text(layout: TextLayout, at: Offset, colour: Colour) {
            drawn += layout
            inner.text(layout, at, colour)
        }
    }
}
