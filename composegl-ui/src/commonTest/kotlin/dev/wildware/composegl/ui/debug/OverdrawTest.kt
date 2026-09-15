package dev.wildware.composegl.ui.debug

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
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Overdraw: how many times each part of the screen is painted, counted off the frame's own draw
 * pass, and `OverdrawOverlay` shading it over a running screen.
 *
 * The counts are read with `UiTest.overdraw()` on screens composed for real. The overlay is turned on
 * the way a player would — a click, a pad button — and its shading read back off a recording canvas.
 */
class OverdrawTest {

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

    // --- counting --------------------------------------------------------------------------------

    @Test
    fun `a panel is painted once and a card on it twice`() {
        val ui = open { Screen() }
        val map = ui.overdraw()

        assertEquals(0, map.at(50f, 250f), "bare screen")
        assertEquals(1, map.at(250f, 150f), "the panel alone")
        assertEquals(2, map.at(150f, 100f), "the card on the panel")
        assertEquals(1, map.at(119f, 100f), "just left of the card")
        assertEquals(2, map.at(120f, 80f), "the card's own corner")
        assertEquals(1, map.at(200f, 100f), "its right edge is out")
        assertEquals(80f * 40f, map.areaAtLeast(2) - areaAtLeast2Outside(ui, map))
    }

    /** What the toggle button paints twice or more, which is not what this test is about. */
    private fun areaAtLeast2Outside(ui: UiTest, map: OverdrawMap): Float {
        val panel = ui.node("panel").layoutBoundsInRoot
        var area = 0f
        for (row in 0 until map.rows) for (column in 0 until map.columns) {
            if (map.atCell(column, row) >= 2 && Offset(column + 0.5f, row + 0.5f) !in panel) area += 1f
        }
        return area
    }

    @Test
    fun `a scrim put up by a click adds one everywhere`() {
        val ui = open { Screen() }
        ui.click("panel")
        ui.assertExists("scrim")
        val map = ui.overdraw()

        assertEquals(1, map.at(50f, 250f))
        assertEquals(2, map.at(250f, 150f))
        assertEquals(3, map.at(150f, 100f))
        assertTrue(map.deepest >= 3)
        assertTrue(map.average > 1f, "a screen covered once and then some: ${map.average}")
    }

    @Test
    fun `a clip keeps what it hides out of the count`() {
        val ui = open {
            Box(Modifier.offset(50f, 50f).size(100f, 100f).clip().background(Colour.Blue)) {
                Box(Modifier.offset(60f, 60f).size(100f, 100f).background(Colour.Red))
            }
        }
        val map = ui.overdraw()

        assertEquals(2, map.at(140f, 140f), "inside the clip")
        assertEquals(0, map.at(170f, 170f), "the child's overflow is cut off and paints nothing")
    }

    @Test
    fun `a faded out subtree counts nothing`() {
        val ui = open {
            var faded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(60f, 30f).background(Colour.Grey).clickable { faded = true }.testTag("fade"))
                Box(Modifier.offset(100f, 100f).alpha(if (faded) 0f else 1f).size(50f, 50f).background(Colour.Red))
            }
        }
        assertEquals(1, ui.overdraw().at(125f, 125f))
        ui.click("fade")
        assertEquals(0, ui.overdraw().at(125f, 125f))
    }

    @Test
    fun `a border counts its edges and not its middle`() {
        val ui = open { Box(Modifier.offset(100f, 100f).size(100f, 60f).border(Colour.White, 4f)) }
        val map = ui.overdraw()

        assertEquals(1, map.at(101f, 130f), "left edge")
        assertEquals(1, map.at(150f, 158f), "bottom edge")
        assertEquals(1, map.at(101f, 101f), "a corner once, not twice")
        assertEquals(0, map.at(150f, 130f), "the middle")
        assertEquals(0, map.at(105f, 130f), "just inside the edge")
    }

    @Test
    fun `a circle counts inside its curve and not its corners`() {
        val ui = open {
            Box(Modifier.offset(100f, 100f).size(100f, 100f).drawBehind { circle(it.centre, 50f, Colour.White) })
        }
        val map = ui.overdraw()

        assertEquals(1, map.at(150f, 150f), "the middle")
        assertEquals(1, map.at(150f, 102f), "near the top of the curve")
        assertEquals(0, map.at(104f, 104f), "the corner of its box is outside the curve")
    }

    @Test
    fun `a scale counts the picture and where it is put down`() {
        val ui = open { Box(Modifier.offset(100f, 100f).size(100f, 100f).scale(0.5f).background(Colour.Blue)) }
        val map = ui.overdraw()

        assertEquals(2, map.at(150f, 150f), "drawn into the picture, then the picture put down")
        assertEquals(1, map.at(110f, 110f), "only the picture, which is the node's full size")
        assertEquals(0, map.at(90f, 90f))
    }

    @Test
    fun `a turn counts the turned square and not its box`() {
        val ui = open { Box(Modifier.offset(200f, 100f).size(100f, 100f).rotate(45f).background(Colour.Blue)) }
        val map = ui.overdraw()

        assertEquals(2, map.at(250f, 150f), "the middle: the picture and the turned composite")
        assertEquals(1, map.at(203f, 103f), "a corner of the box: in the picture, outside the diamond")
        assertEquals(1, map.at(250f, 90f), "the diamond's tip: past the box, inside the diamond")
        assertEquals(0, map.at(210f, 90f), "outside both")
    }

    @Test
    fun `a canvas that cannot take pictures counts a scale once and nothing on the tree changes`() {
        val ui = open { Box(Modifier.offset(100f, 100f).size(100f, 100f).scale(0.5f).background(Colour.Blue).testTag("scaled")) }
        val before = ui.node("scaled").boundsInRoot
        assertEquals(Rect(125f, 125f, 175f, 175f), before)

        val flat = object : UiCanvas by RecordingCanvas() {
            override val drawsLayers: Boolean get() = false
        }
        val map = measureOverdraw(ui.root, like = flat)

        assertEquals(1, map.at(150f, 150f), "drawn straight, at its full size")
        assertEquals(1, map.at(110f, 110f))
        assertEquals(before, ui.node("scaled").boundsInRoot, "the count did not tell the node its scale was refused")
    }

    @Test
    fun `a run of text counts its box`() {
        val map = OverdrawMap(100f, 100f)
        val layout = object : TextLayout {
            override val text = "hi"
            override val size = Size(30f, 12f)
            override val lineCount = 1
            override val firstBaseline = 10f
        }
        OverdrawCanvas(map, null).text(layout, 10f, 20f, Colour.White)

        assertEquals(1, map.at(10f, 20f))
        assertEquals(1, map.at(39f, 31f))
        assertEquals(0, map.at(40f, 25f))
        assertEquals(0, map.at(20f, 32f))
    }

    @Test
    fun `a cell counts only when its middle is covered`() {
        val map = OverdrawMap(10f, 10f, cell = 2f)
        map.fill(0f, 0f, 3f, 2f) // middles at 1 and 3: the second is outside
        map.fill(0.9f, 0f, 1.1f, 1.5f) // a sliver over the first middle
        map.fill(0f, 0f, 2f, 1f) // a bottom edge on a middle leaves it out, as a right edge does
        map.fill(1.1f, 4f, 2.9f, 6f) // a sliver between two middles covers neither

        assertEquals(5, map.columns)
        assertEquals(2, map.atCell(0, 0))
        assertEquals(0, map.atCell(1, 0))
        assertEquals(0, map.atCell(0, 2))
        assertEquals(0, map.atCell(1, 2))
        assertEquals(2, map.deepest)
        assertEquals(4f, map.areaAtLeast(1))
        assertEquals(0, map.at(-1f, 0f))
        assertEquals(0, map.at(10f, 0f))
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

    private companion object {
        val Shades = setOf(OverdrawColours.Twice, OverdrawColours.Three, OverdrawColours.Four, OverdrawColours.More)
    }
}
