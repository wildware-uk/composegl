package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.debugBounds
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.drawsOutside
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.moulded
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.shadow
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.ProvideTextOutline
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A node whose drawing cannot reach the screen is not drawn at all, nor is anything under it: the
 * rows of a long page below the fold, a card scrolled away. A node whose shadow, outline, glow,
 * turn or growth reaches into view is drawn, and so is one that says it draws where layout cannot
 * see.
 */
class OffClipUiTest {

    /** One frame from scratch into the headless canvas. */
    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    private val grey = Colour.rgb(0x404040)
    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val gold = Colour.rgb(0xFFCC00)
    private val black = Colour.rgb(0x000000)

    /** Forty rows, forty tall, in a scroll area 300 tall: seven and a bit rows show. */
    private fun page(state: ScrollState): UiTest = uiTest(Size(400f, 300f)) {
        ScrollArea(Modifier.fillMaxSize(), state, bars = false) {
            Column {
                repeat(40) { row ->
                    Box(Modifier.fillMaxWidth().height(40f).background(grey)) { Text("Row $row") }
                }
            }
        }
    }

    /**
     * The rows [drawn] drew, by their words and by their grey backgrounds: every one of [showing],
     * and nothing further away than the row just past either end — whose words may lean out of it
     * far enough to be drawn, and no further. [scrolled] is how far the page has moved up.
     */
    private fun assertRows(showing: IntRange, drawn: RecordingCanvas, scrolled: Float, frame: String) {
        val near = (showing.first - 1)..(showing.last + 1)
        val words = drawn.texts().map { it.removePrefix("Row ").toInt() }
        assertTrue(words.containsAll(showing.toList()), "$frame: every row that shows has its words drawn: $words")
        assertEquals(emptyList(), words.filterNot { it in near }, "$frame: words drawn for rows far from the window")
        val backgrounds = drawn.only<DrawCall.Rectangle>()
            .filter { it.colour == grey }
            .map { ((it.rect.top + scrolled) / 40f).roundToInt() }
        assertTrue(backgrounds.containsAll(showing.toList()), "$frame: every row that shows has its background drawn: $backgrounds")
        assertEquals(emptyList(), backgrounds.filterNot { it in near }, "$frame: backgrounds drawn for rows far from the window")
    }

    @Test
    fun `rows below the fold make no draw calls`() {
        val state = ScrollState()
        page(state).use { ui ->
            repeat(2) { frame ->
                assertRows(0..7, ui.drawn(), scrolled = 0f, "frame ${frame + 1}")
            }
        }
    }

    @Test
    fun `a row scrolled into view is drawn on that frame`() {
        val state = ScrollState()
        page(state).use { ui ->
            assertRows(0..7, ui.drawn(), scrolled = 0f, "at the top")
            state.scrollTo(y = 810f)
            // Row 20 is at -10 to 30 now, and row 27 at 270 to 310.
            assertRows(20..27, ui.drawn(), scrolled = 810f, "scrolled")
        }
    }

    @Test
    fun `a box below the screen draws nothing`() = uiTest(Size(400f, 300f)) {
        // No clip anywhere: the screen's own edge is the one that counts.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.offset(0f, 290f).size(50f, 20f).background(blue))
            Box(Modifier.offset(100f, 310f).size(50f, 20f).background(red))
        }
    }.use { ui ->
        val drawn = ui.drawn()
        assertTrue(blue in drawn.colours(), "the box across the bottom edge:\n$drawn")
        assertFalse(red in drawn.colours(), "the box below it:\n$drawn")
    }

    /**
     * A window 100 tall, clipped, with a box 10 below its bottom edge carrying [reach] — and a
     * plain red one beside it that reaches nowhere, to show the window really does skip things.
     */
    private fun window(reach: Modifier): UiTest = uiTest(Size(400f, 300f)) {
        Box(Modifier.size(400f, 100f).clip()) {
            Box(Modifier.offset(0f, 110f).size(50f, 20f).background(red))
            Box(Modifier.offset(100f, 110f).size(50f, 20f).then(reach).background(blue))
        }
    }

    private fun RecordingCanvas.colours(): List<Colour> = only<DrawCall.Rectangle>().map { it.colour }

    @Test
    fun `a box below the clip draws nothing`() = window(Modifier).use { ui ->
        val drawn = ui.drawn()
        assertFalse(red in drawn.colours(), "the plain box is skipped:\n$drawn")
        assertFalse(blue in drawn.colours(), "so is the other one, with nothing reaching up:\n$drawn")
    }

    @Test
    fun `a shadow reaching into the clip is drawn though its box is not`() =
        window(Modifier.shadow(black, spread = 20f)).use { ui ->
            val drawn = ui.drawn()
            assertEquals(1, drawn.only<DrawCall.Shadow>().size, "the shadow:\n$drawn")
            assertTrue(blue in drawn.colours(), "and the box it belongs to:\n$drawn")
            assertFalse(red in drawn.colours(), "the plain box beside it is still skipped:\n$drawn")
        }

    @Test
    fun `an outline reaching into the clip is drawn though its box is not`() =
        window(Modifier.borderOutside(black, width = 14f)).use { ui ->
            val drawn = ui.drawn()
            assertEquals(1, drawn.only<DrawCall.OutsideBorder>().size, "the outline:\n$drawn")
            assertTrue(blue in drawn.colours(), "and the box it belongs to:\n$drawn")
        }

    @Test
    fun `an effect whose glow reaches into the clip is drawn though its box is not`() {
        val glow = ShaderEffect(ShaderSource("glow", "void main() { }"), bleed = 20f)
        window(Modifier.effect(glow)).use { ui ->
            val drawn = ui.drawn()
            assertEquals(glow, drawn.only<DrawCall.Layer>().single().effect, "the glow's picture:\n$drawn")
            assertTrue(blue in drawn.colours(), "and the box inside it:\n$drawn")
        }
    }

    @Test
    fun `a box turned up into the clip is drawn though its upright box is not`() =
        // 20 tall upright, at 110 to 130; turned a quarter about its middle it is 50 tall, 95 to 145.
        window(Modifier.rotate(90f)).use { ui ->
            val drawn = ui.drawn()
            assertTrue(blue in drawn.colours(), "the turned box:\n$drawn")
            assertFalse(red in drawn.colours(), "the plain one is still skipped:\n$drawn")
        }

    @Test
    fun `a box slanted up into the clip is drawn though its upright box is not`() =
        // Slanted 45 degrees about its middle, its right edge rises 25, to 85.
        window(Modifier.skew(y = -45f)).use { ui ->
            val drawn = ui.drawn()
            assertTrue(blue in drawn.colours(), "the slanted box:\n$drawn")
        }

    @Test
    fun `a box grown up into the clip is drawn though its own box is not`() =
        // Grown three times about its middle, 120: 90 to 150.
        window(Modifier.scale(3f)).use { ui ->
            val drawn = ui.drawn()
            assertTrue(blue in drawn.colours(), "the grown box:\n$drawn")
        }

    @Test
    fun `a box shrunk about its middle stays skipped`() =
        // Shrunk to half about its middle it is 115 to 125: further away, not nearer.
        window(Modifier.scale(0.5f)).use { ui ->
            val drawn = ui.drawn()
            assertFalse(blue in drawn.colours(), "the shrunk box:\n$drawn")
        }

    @Test
    fun `a box that says it draws outside is drawn wherever it is`() {
        // Paints a bar 30 above itself, which layout cannot know.
        val above = Modifier.drawBehind { box ->
            rect(Rect.of(box.left, box.top - 30f, box.width, 10f), gold)
        }
        window(above).use { ui ->
            assertFalse(gold in ui.drawn().colours(), "unsaid, it is skipped with its box")
        }
        window(above.drawsOutside()).use { ui ->
            val drawn = ui.drawn()
            assertTrue(gold in drawn.colours(), "said, it is drawn:\n$drawn")
            assertFalse(red in drawn.colours(), "and only it:\n$drawn")
        }
    }

    @Test
    fun `a box that moves into view without a layout of its own is drawn`() {
        // The parent moves its child by placing it somewhere else: the child's reach is its own,
        // and where it is comes from the parent each frame.
        val state = ScrollState()
        uiTest(Size(400f, 300f)) {
            ScrollArea(Modifier.size(400f, 100f), state, bars = false) {
                Column {
                    Box(Modifier.fillMaxWidth().height(110f).background(grey))
                    Box(Modifier.fillMaxWidth().height(20f).background(gold))
                }
            }
        }.use { ui ->
            assertFalse(gold in ui.drawn().colours(), "below the window at first")
            state.scrollTo(y = 30f)
            assertTrue(gold in ui.drawn().colours(), "scrolled into the window")
        }
    }

    @Test
    fun `a moulded box whose shadow reaches into the clip is drawn`() =
        // A shadow a whole shorter side deep: 20 past a box 20 tall, up to 90.
        window(Modifier.moulded(shadow = 1f)).use { ui ->
            assertTrue(blue in ui.drawn().colours(), "the moulded box")
        }

    @Test
    fun `a box painted past its edge by a negative padding is drawn`() =
        // The background after the padding is painted 15 past the box all round, up to 95.
        window(Modifier.padding(-15f)).use { ui ->
            assertTrue(blue in ui.drawn().colours(), "the background painted past the box")
        }

    @Test
    fun `a debug label wider than its box is drawn where it reaches`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.size(400f, 100f).clip()) {
            // Ten wide, wholly left of the window; its chip reads 10x10 and is 42 wide.
            Box(Modifier.offset(-30f, 10f).size(10f).debugBounds(red, label = true))
            Box(Modifier.offset(-30f, 40f).size(10f).debugBounds(gold))
        }
    }.use { ui ->
        val drawn = ui.drawn()
        assertTrue(red in drawn.colours(), "the chip reaching in:\n$drawn")
        assertTrue(drawn.only<DrawCall.Border>().none { it.colour == gold }, "the box with no label is skipped:\n$drawn")
    }

    @Test
    fun `a widget's own drawing a little past its box is drawn`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.size(400f, 100f).clip()) {
            // Draws a line 8 above its own box, as an outline round a word or a leaning glyph does.
            LeafLayout(Modifier.offset(0f, 106f).size(50f, 20f), draw = { box ->
                rect(Rect.of(box.left, box.top - 8f, box.width, 2f), gold)
            })
        }
    }.use { ui ->
        assertTrue(gold in ui.drawn().colours(), "the line, 2 inside the window")
    }

    @Test
    fun `a widget whose ink runs past its box is drawn where the ink is`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.size(400f, 100f).clip()) {
            // A box wholly left of the window whose words run 200 to the right, as a line of text
            // squeezed into a box too small for it does.
            val ink: (Rect) -> Rect? = { box -> Rect(box.left, box.top, box.right + 200f, box.bottom) }
            LeafLayout(
                Modifier.offset(-120f, 10f).size(50f, 20f),
                draw = { box -> rect(Rect(box.left, box.top, box.right + 200f, box.bottom), gold) },
                ink = ink,
            )
        }
    }.use { ui ->
        assertTrue(gold in ui.drawn().colours(), "the ink reaching into the window")
    }

    @Test
    fun `a debug overlay of no size is drawn wherever it is`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.size(400f, 100f).clip()) {
            // Draws over the whole screen from a node of no size, as every debug overlay does.
            LeafLayout(Modifier.offset(0f, 150f), draw = Overlay)
        }
    }.use { ui ->
        assertTrue(gold in ui.drawn().colours(), "the overlay's mark")
    }

    private val Overlay = object : DebugOverlay {
        override fun invoke(canvas: UiCanvas, box: Rect) = canvas.rect(Rect.of(10f, 10f, 20f, 20f), gold)
    }

    @Test
    fun `words whose outline reaches into the clip are drawn and words far below are not`() =
        uiTest(Size(400f, 300f)) {
            Box(Modifier.size(400f, 100f).clip()) {
                ProvideTextOutline(black, width = 6f) {
                    // Its line starts 4 below the window, and its outline rises 6 above the line.
                    Text("Edge", modifier = Modifier.offset(0f, 104f))
                    Text("Deep", modifier = Modifier.offset(0f, 160f))
                }
            }
        }.use { ui ->
            val texts = ui.drawn().texts()
            assertTrue("Edge" in texts, "the outlined line at the edge: $texts")
            assertFalse("Deep" in texts, "the line far below: $texts")
        }
}
