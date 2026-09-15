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
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
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
import kotlin.test.assertTrue

/**
 * Overdraw: how many times each part of the screen is painted, counted off the frame's own draw
 * pass.
 *
 * The counts are read with `UiTest.overdraw()` on screens composed for real. The overlay that shades
 * them is `composegl-debug`'s, and tested there.
 */
class OverdrawTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    // --- the screen ------------------------------------------------------------------------------

    /**
     * A button, a blue panel at 100, 60 — 200 by 120 — with a green card on it at 120, 80 — 80 by
     * 40 — and a half-black scrim across the whole screen that a click on the panel puts up.
     */
    @Composable
    private fun Screen() {
        var scrim by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Button("debug", onClick = {}, modifier = Modifier.testTag("toggle"), initialFocus = true)
            Box(
                Modifier.offset(100f, 60f).size(200f, 120f).background(Colour.Blue)
                    .clickable { scrim = !scrim }.testTag("panel"),
            ) {
                Box(Modifier.offset(20f, 20f).size(80f, 40f).background(Colour.Green).testTag("card"))
            }
            if (scrim) Box(Modifier.fillMaxSize().background(Colour.argb(0x80000000)).testTag("scrim"))
        }
    }

    @Test
    fun `a debug overlay on the screen is not counted`() {
        val marks = object : DebugOverlay {
            override fun invoke(canvas: UiCanvas, content: Rect) = canvas.rect(Rect(0f, 0f, 400f, 300f), Colour.Red)
        }
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(100f, 100f).size(50f, 50f).background(Colour.Blue))
                Box(Modifier.fillMaxSize().drawBehind { rect(Rect(0f, 0f, 10f, 10f), Colour.Green) })
                Layout(Modifier.fillMaxSize(), name = "marks", draw = marks, measurePolicy = MeasurePolicy.Stack)
            }
        }

        val map = ui.overdraw()
        assertEquals(1, map.at(125f, 125f), "the panel, and not the overlay's wash over it")
        assertEquals(0, map.at(300f, 250f), "bare screen under the wash")
        assertEquals(1, map.at(5f, 5f), "ordinary drawing is still counted")
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
}
