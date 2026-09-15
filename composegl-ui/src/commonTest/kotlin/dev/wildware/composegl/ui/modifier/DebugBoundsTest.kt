package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.DebugBounds
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `Modifier.debugBounds`: a box drawn round a widget to show where it landed, which must change
 * nothing about where it landed.
 *
 * Every test composes a real screen with [uiTest], drives it the way a player would, and reads the
 * answer off the layout boxes and off what was drawn. The size label is rectangles rather than text,
 * so it is read back by putting those rectangles on a grid and matching them against the glyphs.
 */
class DebugBoundsTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 300f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    /** One fresh frame of [ui], as a list of calls. */
    private fun drawn(ui: UiTest): List<DrawCall> {
        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        return canvas.calls.toList()
    }

    private fun List<DrawCall>.outlines(colour: Colour) =
        filterIsInstance<DrawCall.Border>().filter { it.colour == colour }

    // --- the element -----------------------------------------------------------------------------

    @Test
    fun `it says nothing about size or padding or input`() {
        val resolved = Modifier.debugBounds(label = true).resolve()

        assertEquals(null, resolved.size)
        assertEquals(null, resolved.fill)
        assertEquals(Padding.None, resolved.padding)
        assertFalse(resolved.isInteractive, "a debug box is scenery to the pointer")
        assertEquals(1, resolved.inFront.size, "it is painted in front")
        assertTrue(resolved.behind.isEmpty())
    }

    @Test
    fun `two debug boxes written the same way compare equal`() {
        assertEquals(Modifier.debugBounds(Colour.Red, label = true), Modifier.debugBounds(Colour.Red, label = true))
        assertNotEquals(Modifier.debugBounds(Colour.Red), Modifier.debugBounds(Colour.Green))
        assertNotEquals(Modifier.debugBounds(Colour.Red), Modifier.debugBounds(Colour.Red, label = true))
    }

    @Test
    fun `the label is the size rounded to whole units`() {
        assertEquals("120x40", DebugBounds.label(Rect.of(0f, 0f, 119.6f, 40.2f)))
        assertEquals("0x0", DebugBounds.label(Rect(10f, 10f, 5f, 5f)))
    }

    // --- on a composed screen --------------------------------------------------------------------

    @Composable
    private fun Toolbar(debug: Boolean) {
        val box = if (debug) Modifier.debugBounds(Colour.Red, label = true) else Modifier
        Row(Modifier.padding(12f).testTag("row")) {
            Box(box.padding(8f).testTag("first")) {
                Box(Modifier.size(60f, 30f).background(Colour.Blue).testTag("inside"))
            }
            Box(Modifier.size(90f, 46f).background(Colour.Green).testTag("second"))
        }
    }

    @Test
    fun `turning a debug box on moves nothing on the screen`() {
        val tags = listOf("row", "first", "inside", "second")
        val plain = open { Toolbar(debug = false) }
        val debugged = open { Toolbar(debug = true) }

        for (tag in tags) {
            assertEquals(plain.node(tag).boundsInRoot, debugged.node(tag).boundsInRoot, "#$tag moved")
        }
    }

    @Test
    fun `the outline is the widget's own rectangle and is drawn over its children`() {
        val ui = open { Toolbar(debug = true) }
        val calls = drawn(ui)

        val outline = calls.outlines(Colour.Red).single()
        assertEquals(ui.node("first").boundsInRoot, outline.rect)
        assertEquals(1f, outline.width)

        val child = calls.indexOfFirst { it is DrawCall.Rectangle && it.colour == Colour.Blue }
        assertTrue(child >= 0, "the child was drawn")
        assertTrue(calls.indexOf(outline) > child, "the outline goes on top of what is inside")

        val wash = calls.filterIsInstance<DrawCall.Rectangle>()
            .single { it.rect == outline.rect && it.colour.red == 0xFF && it.colour.alpha < 0xFF }
        assertTrue(wash.colour.alpha in 1..0x40, "a faint wash, not a fill: ${wash.colour}")
    }

    @Test
    fun `the label reads the size of the widget`() {
        val ui = open { Toolbar(debug = true) }

        assertEquals("76x46", readLabel(drawn(ui), ui.node("first").boundsInRoot, Colour.Red))
    }

    @Test
    fun `no label unless one is asked for`() {
        val ui = open { Box(Modifier.debugBounds(Colour.Red).size(80f, 40f)) }
        val calls = drawn(ui)

        assertEquals(1, calls.outlines(Colour.Red).size)
        assertTrue(calls.none { it is DrawCall.Rectangle && it.colour == Colour.Red }, "no chip was drawn")
    }

    @Test
    fun `a click goes straight through a debug box laid over a button`() {
        var clicks = 0
        val ui = open {
            Box {
                Button("GO", onClick = { clicks++ }, modifier = Modifier.size(120f, 48f).testTag("go"))
                // On top of the button and the same size, so every point on it is covered.
                Box(Modifier.size(120f, 48f).debugBounds(label = true).testTag("overlay"))
            }
        }
        assertEquals(ui.node("go").boundsInRoot, ui.node("overlay").boundsInRoot)

        ui.click("go")

        assertEquals(1, clicks)
    }

    @Test
    fun `the box and its label follow a widget that grows when it is clicked`() {
        val ui = open {
            var wide by remember { mutableStateOf(false) }
            Box(
                Modifier.debugBounds(Colour.Yellow, label = true)
                    .size(if (wide) 150f else 60f, 40f)
                    .clickable { wide = true }
                    .testTag("grower"),
            )
        }
        assertEquals("60x40", readLabel(drawn(ui), ui.node("grower").boundsInRoot, Colour.Yellow))

        ui.click("grower")

        val calls = drawn(ui)
        val bounds = ui.node("grower").boundsInRoot
        assertEquals(150f, bounds.width)
        assertEquals(bounds, calls.outlines(Colour.Yellow).single().rect)
        assertEquals("150x40", readLabel(calls, bounds, Colour.Yellow))
    }

    @Test
    fun `the box follows focus moving with the keyboard onto a debugged button`() {
        val ui = open {
            Column {
                Button("ONE", onClick = {}, initialFocus = true, modifier = Modifier.testTag("one"))
                Button("TWO", onClick = {}, modifier = Modifier.debugBounds(Colour.Cyan).testTag("two"))
            }
        }

        ui.key(Key.Down)

        ui.assertFocused("two")
        assertEquals(ui.node("two").boundsInRoot, drawn(ui).outlines(Colour.Cyan).single().rect)
    }

    @Test
    fun `the box moves with a row scrolled by the wheel`() {
        val state = ScrollState()
        val ui = open {
            ScrollArea(Modifier.size(200f, 100f).testTag("area"), state, bars = false) {
                Column {
                    repeat(10) { Box(Modifier.size(200f, 30f)) }
                    Box(Modifier.debugBounds(Colour.Red, label = true).size(120f, 30f).testTag("row"))
                    repeat(10) { Box(Modifier.size(200f, 30f)) }
                }
            }
        }
        val before = ui.node("row").boundsInRoot

        ui.scroll("area", Offset(0f, 10f))
        ui.advanceBy(300)

        assertTrue(state.y > 0f, "the wheel scrolled the area")
        val after = ui.node("row").boundsInRoot
        assertNotEquals(before.top, after.top)
        val calls = drawn(ui)
        assertEquals(after, calls.outlines(Colour.Red).single().rect)
        assertEquals("120x30", readLabel(calls, after, Colour.Red))
    }

    @Test
    fun `recomposing with the same debug box is not a change to the widget`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Column {
                Button("ADD", onClick = { count++ }, modifier = Modifier.testTag("add"))
                Text("$count", modifier = Modifier.testTag("count"))
                Box(Modifier.debugBounds(Colour.Red, label = true).size(80f, 40f).testTag("box"))
            }
        }
        val before = ui.node("box").resolved

        ui.click("add")

        ui.assertText("count", "1")
        assertSame(before, ui.node("box").resolved, "an equal modifier keeps the node's resolution")
    }

    @Test
    fun `a still screen with a debug box on it has nothing to redraw`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Column {
                Button("ADD", onClick = { count++ }, modifier = Modifier.testTag("add"))
                Text("$count")
                Box(Modifier.debugBounds(Colour.Red, label = true).size(80f, 40f))
            }
        }
        ui.render()
        assertFalse(ui.render(), "nothing changed, so the frame is not new")

        ui.click("add")
        ui.render()

        assertFalse(ui.render(), "after the recompose settles the screen is still again")
    }

    @Test
    fun `nested debug boxes each show and the outer one is drawn last`() {
        val ui = open {
            Box(Modifier.debugBounds(Colour.Red).padding(10f).testTag("outer")) {
                Box(Modifier.debugBounds(Colour.Cyan).size(50f, 20f).testTag("inner"))
            }
        }
        val calls = drawn(ui)

        val outer = calls.outlines(Colour.Red).single()
        val inner = calls.outlines(Colour.Cyan).single()
        assertEquals(ui.node("outer").boundsInRoot, outer.rect)
        assertEquals(ui.node("inner").boundsInRoot, inner.rect)
        assertTrue(calls.indexOf(outer) > calls.indexOf(inner), "a parent's box goes over its child's")
    }

    @Test
    fun `a pad press turns the debug box off and removing the widget leaves nothing behind`() {
        val ui = open {
            var debug by remember { mutableStateOf(true) }
            var shown by remember { mutableStateOf(true) }
            Column {
                Button(
                    "TOGGLE", onClick = { if (debug) debug = false else shown = false },
                    initialFocus = true, modifier = Modifier.testTag("toggle"),
                )
                if (shown) {
                    val box = if (debug) Modifier.debugBounds(Colour.Red, label = true) else Modifier
                    Box(box.size(80f, 40f).background(Colour.Blue).testTag("box"))
                }
            }
        }
        assertEquals(1, drawn(ui).outlines(Colour.Red).size)

        ui.pad(GamepadButton.South)

        ui.assertExists("box")
        val off = drawn(ui)
        // The outline, the wash and the chip are all red; nothing else on this screen has any red in it.
        assertTrue(off.none { it.colour()?.red == 0xFF }, "no outline, wash or chip left")
        assertTrue(off.any { it is DrawCall.Rectangle && it.colour == Colour.Blue }, "the widget itself is still drawn")

        ui.pad(GamepadButton.South)

        ui.assertDoesNotExist("box")
        assertTrue(drawn(ui).none { it is DrawCall.Rectangle && it.colour == Colour.Blue })
    }

    private fun DrawCall.colour(): Colour? = when (this) {
        is DrawCall.Rectangle -> colour
        is DrawCall.Border -> colour
        else -> null
    }

    @Test
    fun `padding before the debug box puts it inside the padding`() {
        val ui = open { Box(Modifier.padding(10f).debugBounds(Colour.Red).size(80f, 40f).testTag("padded")) }

        val bounds = ui.node("padded").boundsInRoot
        assertEquals(bounds.inset(10f), drawn(ui).outlines(Colour.Red).single().rect)
    }

    @Test
    fun `a widget with no width still shows as a line`() {
        val ui = open { Box(Modifier.debugBounds(Colour.Red, label = true).size(0f, 40f).testTag("flat")) }
        val bounds = ui.node("flat").boundsInRoot
        val calls = drawn(ui)

        val line = calls.filterIsInstance<DrawCall.Rectangle>().first { it.colour == Colour.Red }
        assertEquals(Rect(bounds.left, bounds.top, bounds.left + 1f, bounds.bottom), line.rect)
        assertEquals("0x40", readLabel(calls, bounds, Colour.Red))
    }

    @Test
    fun `the label is dark on a light colour and light on a dark one`() {
        val yellow = open { Box(Modifier.debugBounds(Colour.Yellow, label = true).size(80f, 40f)) }
        val magenta = open { Box(Modifier.debugBounds(Colour.Magenta, label = true).size(80f, 40f)) }

        assertTrue(drawn(yellow).any { it is DrawCall.Rectangle && it.colour == Colour.Black })
        assertTrue(drawn(magenta).any { it is DrawCall.Rectangle && it.colour == Colour.White })
    }

    @Test
    fun `the default colour is magenta`() {
        val ui = open { Box(Modifier.debugBounds().size(80f, 40f)) }

        assertEquals(1, drawn(ui).outlines(Colour.Magenta).size)
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The text the label drew for a box at [bounds], read back off its rectangles.
     *
     * The chip is the solid rectangle in [colour] at the box's top-left; the ink is every rectangle
     * drawn after it inside it. Each ink rectangle is put on the glyph grid, and each column of the
     * grid is matched against the glyph table.
     */
    private fun readLabel(calls: List<DrawCall>, bounds: Rect, colour: Colour): String {
        val rects = calls.filterIsInstance<DrawCall.Rectangle>()
        val chip = rects.single {
            it.colour == colour && it.rect.left == bounds.left && it.rect.top == bounds.top &&
                it.rect.height == DebugBounds.ChipHeight
        }
        val originX = chip.rect.left + DebugBounds.Pad
        val originY = chip.rect.top + DebugBounds.Pad
        val glyphs = ((chip.rect.width - 2 * DebugBounds.Pad + DebugBounds.Pixel) / DebugBounds.Advance).toInt()
        val masks = IntArray(glyphs)

        val ink = rects.drop(rects.indexOf(chip) + 1).filter { it.colour != colour && chip.rect.contains(it.rect) }
        for (call in ink) {
            val row = ((call.rect.top - originY) / DebugBounds.Pixel).toInt()
            var x = call.rect.left
            while (x < call.rect.right) {
                val column = ((x - originX) / DebugBounds.Pixel).toInt()
                val glyph = column / (DebugBounds.Columns + 1)
                val inGlyph = column % (DebugBounds.Columns + 1)
                masks[glyph] = masks[glyph] or DebugBounds.bit(row, inGlyph)
                x += DebugBounds.Pixel
            }
        }
        return masks.joinToString("") { mask ->
            DebugBounds.Alphabet.firstOrNull { DebugBounds.glyph(it) == mask }?.toString() ?: "?"
        }
    }

    private fun Rect.contains(other: Rect) =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}
