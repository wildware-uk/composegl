package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OverdrawOverlay` shading how many times each part of the screen is painted, over a running screen.
 *
 * The counting itself is `composegl-ui`'s, tested there in `OverdrawTest`. Here the overlay is turned
 * on the way a player would — a click, a pad button — and its shading read back off a recording
 * canvas, next to the counts `UiTest.overdraw()` reads.
 */
class OverdrawOverlayTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private fun drawn(ui: UiTest): List<DrawCall> {
        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        return canvas.calls.toList()
    }

    private fun List<DrawCall>.fills(colour: Colour) =
        filterIsInstance<DrawCall.Rectangle>().filter { it.colour == colour }.map { it.rect }

    private fun List<DrawCall>.shades() =
        filterIsInstance<DrawCall.Rectangle>().filter { it.colour in Shades }

    private fun List<Rect>.covers(x: Float, y: Float) = any { Offset(x, y) in it }

    private fun List<Rect>.areaInside(area: Rect) = sumOf { it.intersect(area).size.run { width * height }.toDouble() }.toFloat()

    // --- the screen ------------------------------------------------------------------------------

    /**
     * A toggle, a blue panel at 100, 60 — 200 by 120 — with a green card on it at 120, 80 — 80 by
     * 40 — and a half-black scrim across the whole screen that a click on the panel puts up.
     */
    @Composable
    private fun Screen(cell: Float = 2f) {
        var debug by remember { mutableStateOf(false) }
        var scrim by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"), initialFocus = true)
            Box(
                Modifier.offset(100f, 60f).size(200f, 120f).background(Colour.Blue)
                    .clickable { scrim = !scrim }.testTag("panel"),
            ) {
                Box(Modifier.offset(20f, 20f).size(80f, 40f).background(Colour.Green).testTag("card"))
            }
            if (scrim) Box(Modifier.fillMaxSize().background(Colour.argb(0x80000000)).testTag("scrim"))
            OverdrawOverlay(debug, cell)
        }
    }

    // --- the overlay -----------------------------------------------------------------------------

    @Test
    fun `off it composes nothing and draws nothing`() {
        val ui = open { Screen() }

        assertNull(ui.root.firstOrNull { it.name == OverdrawName })
        assertTrue(drawn(ui).shades().isEmpty())
    }

    @Test
    fun `a click turns it on and the card painted twice is washed blue`() {
        val ui = open { Screen() }
        ui.click("toggle")
        assertNotNull(ui.root.firstOrNull { it.name == OverdrawName })

        val calls = drawn(ui)
        val panel = ui.node("panel").layoutBoundsInRoot
        val twice = calls.fills(OverdrawColours.Twice)
        assertTrue(twice.covers(150f, 100f), "the card")
        assertFalse(calls.shades().map { it.rect }.covers(250f, 150f), "the panel alone is left as it is")
        assertEquals(80f * 40f, twice.areaInside(panel), "exactly the card, inside the panel")
        assertTrue(calls.fills(OverdrawColours.Three).areaInside(panel) == 0f)
        // Shading goes on top of everything it shades.
        val lastScene = calls.indexOfLast { it is DrawCall.Rectangle && it.colour == Colour.Green }
        assertTrue(calls.indexOfFirst { it is DrawCall.Rectangle && it.colour in Shades } > lastScene)
    }

    @Test
    fun `it follows a scrim going up and clicks go through it`() {
        val ui = open { Screen() }
        ui.click("toggle")
        val panel = ui.node("panel").layoutBoundsInRoot

        ui.click("panel")
        ui.assertExists("scrim")
        assertEquals(panel, ui.node("panel").layoutBoundsInRoot, "nothing moved")

        val calls = drawn(ui)
        assertTrue(calls.fills(OverdrawColours.Three).covers(150f, 100f), "the card is now green")
        assertTrue(calls.fills(OverdrawColours.Twice).covers(250f, 150f), "the panel is now blue")
        assertFalse(calls.shades().map { it.rect }.covers(50f, 250f), "bare screen under the scrim once")
    }

    @Test
    fun `deep stacks go pink then red`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(60f, 30f).clickable { debug = true }.testTag("toggle"))
                Box(Modifier.offset(100f, 100f).size(100f, 100f).background(Colour.Grey)) {
                    Box(Modifier.size(80f, 80f).background(Colour.Grey)) {
                        Box(Modifier.size(60f, 60f).background(Colour.Grey)) {
                            Box(Modifier.size(40f, 40f).background(Colour.Grey)) {
                                Box(Modifier.size(20f, 20f).background(Colour.Grey))
                            }
                        }
                    }
                }
                OverdrawOverlay(debug)
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(calls.fills(OverdrawColours.Twice).covers(170f, 170f))
        assertTrue(calls.fills(OverdrawColours.Three).covers(150f, 150f))
        assertTrue(calls.fills(OverdrawColours.Four).covers(130f, 130f))
        assertTrue(calls.fills(OverdrawColours.More).covers(110f, 110f))
        assertEquals(5, ui.overdraw().deepest)
    }

    @Test
    fun `a pad button turns it off again`() {
        val ui = open { Screen() }
        ui.pad(GamepadButton.South)
        assertTrue(drawn(ui).shades().isNotEmpty(), "the focused toggle turned it on")

        ui.pad(GamepadButton.South)
        assertTrue(drawn(ui).shades().isEmpty(), "and off")
    }

    @Test
    fun `a still screen with it on stays still and its own shading is not counted`() {
        val ui = open { Screen() }
        ui.click("toggle")
        val first = drawn(ui).shades()

        assertFalse(ui.render(), "a still frame")
        assertEquals(first, drawn(ui).shades(), "the same shading frame after frame, not its own shading counted in")
        assertEquals(2, ui.overdraw().at(150f, 100f), "the card is still twice, not three times")
    }

    @Test
    fun `a coarser cell shades coarser squares`() {
        val ui = open { Screen(cell = 16f) }
        ui.click("toggle")
        val shades = drawn(ui).shades()

        assertTrue(shades.isNotEmpty())
        for (shade in shades) {
            assertEquals(0f, shade.rect.left % 16f, "${shade.rect} starts on a cell")
            assertEquals(0f, shade.rect.top % 16f, "${shade.rect} starts on a cell")
        }
    }

    @Test
    fun `a layout overlay beside it does not mark it`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(50f, 50f).background(Colour.Blue))
                OverdrawOverlay(true)
                LayoutOverlay(true, setOf(Show.Bounds))
            }
        }
        assertTrue(drawn(ui).fills(LayoutOverlayColours.Bounds).isEmpty(), "no one-unit dot where the overdraw overlay sits")
    }

    @Test
    fun `a layout overlay turned on beside it is not counted`() {
        val ui = open {
            var layout by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(50f, 50f).background(Colour.Blue).clickable { layout = true }.testTag("panel"))
                OverdrawOverlay(true, cell = 1f)
                LayoutOverlay(layout, setOf(Show.Bounds))
            }
        }
        val before = ui.overdraw()
        assertEquals(1, before.at(100.5f, 125f))

        ui.click("panel")
        val calls = drawn(ui)
        assertTrue(calls.any { it is DrawCall.Rectangle && it.colour == LayoutOverlayColours.Bounds || it is DrawCall.Border && it.colour == LayoutOverlayColours.Bounds }, "the layout overlay is up")

        val after = ui.overdraw()
        assertEquals(1, after.at(100.5f, 125f), "the panel's outline is the layout overlay's, not the screen's")
        assertEquals(before.areaAtLeast(1), after.areaAtLeast(1))
        assertEquals(before.areaAtLeast(2), after.areaAtLeast(2))
        assertFalse(calls.shades().map { it.rect }.covers(100.5f, 125f), "and so not shaded")
    }

    @Test
    fun `an inspector pinned beside it is not counted and does not point at it`() {
        val ui = open {
            Inspector(true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(100f, 100f).size(50f, 50f).background(Colour.Blue).testTag("panel"))
                    OverdrawOverlay(true, cell = 1f)
                }
            }
        }
        assertEquals(1, ui.overdraw().at(125f, 125f))

        ui.click("panel")
        assertEquals("pinned", ui.text(InspectorTags.Heading))
        assertTrue(ui.text(InspectorTags.Title).endsWith("#panel"), "the click finds the panel, not the overlay: ${ui.text(InspectorTags.Title)}")
        val calls = drawn(ui)
        assertTrue(calls.any { it is DrawCall.Rectangle && it.colour == InspectorColours.PinnedWash }, "the inspector's highlight is up")

        assertEquals(1, ui.overdraw().at(125f, 125f), "the highlight is the inspector's, not the screen's")
        assertFalse(calls.shades().map { it.rect }.covers(125f, 125f), "and so not shaded")
    }

    @Test
    fun `a focus overlay turned on beside it is not counted`() {
        val ui = open {
            var focus by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(50f, 50f).background(Colour.Blue).clickable { focus = true }.testTag("panel"))
                OverdrawOverlay(true, cell = 1f)
                FocusOverlay(focus)
            }
        }
        val before = ui.overdraw()
        assertEquals(1, before.at(125f, 125f))

        ui.click("panel")
        val calls = drawn(ui)
        assertTrue(calls.any { it is DrawCall.Rectangle && it.colour == FocusOverlayColours.HitArea }, "the focus overlay's tint is up")

        val after = ui.overdraw()
        assertEquals(1, after.at(125f, 125f), "the tint is the focus overlay's, not the screen's")
        assertEquals(before.areaAtLeast(2), after.areaAtLeast(2))
        assertFalse(calls.shades().map { it.rect }.covers(125f, 125f), "and so not shaded")
    }

    @Test
    fun `a text metrics overlay turned on beside it is not counted`() {
        val ui = open {
            var guides by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(200f, 50f).background(Colour.Blue).clickable { guides = true }.testTag("panel")) {
                    Text("LABEL")
                }
                OverdrawOverlay(true, cell = 1f)
                TextMetricsOverlay(guides)
            }
        }
        val before = ui.overdraw()
        // The label's box on the panel: painted twice, and its baseline 16 below its top.
        assertEquals(2, before.at(110f, 116.5f))

        ui.click("panel")
        val calls = drawn(ui)
        assertTrue(calls.any { it is DrawCall.Rectangle && it.colour == TextMetricsColours.Baseline }, "the text metrics overlay is up")

        val after = ui.overdraw()
        assertEquals(2, after.at(110f, 116.5f), "the baseline is the text metrics overlay's, not the screen's")
        for (depth in 1..4) assertEquals(before.areaAtLeast(depth), after.areaAtLeast(depth), "painted at least $depth times")
    }

    private companion object {
        val Shades = setOf(OverdrawColours.Twice, OverdrawColours.Three, OverdrawColours.Four, OverdrawColours.More)
    }
}
