package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.rememberTypewriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screens sized by their contents, driven through [uiTest] the way a player drives them.
 *
 * The widgets here keep something between frames while they measure — how far a list is scrolled,
 * where a lazy list's window is — so a question about their size must not disturb it.
 */
class IntrinsicScreensTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    @Test
    fun `a menu sized to its longest label is clicked and walked by pad at that width`() {
        var pressed = ""
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("menu")) {
                Button("GO", onClick = { pressed = "go" }, initialFocus = true, modifier = Modifier.fillMaxWidth().testTag("go"))
                Button("SETTINGS", onClick = { pressed = "settings" }, modifier = Modifier.fillMaxWidth().testTag("settings"))
            }
        }

        val menu = ui.node("menu")
        assertTrue(menu.width < 400f, "the menu is not the screen's width, it was ${menu.width}")
        assertEquals(menu.width, ui.node("go").width, "the short button is as wide as the long one")
        assertEquals(menu.width, ui.node("settings").width)

        // Right at the end of GO's widened bar, past where its own label stops.
        val go = ui.node("go").boundsInRoot
        ui.click(Offset(go.right - 1f, (go.top + go.bottom) / 2f))
        assertEquals("go", pressed)

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("settings")
        ui.pad(GamepadButton.South)
        assertEquals("settings", pressed)
    }

    @Test
    fun `a slider in a column sized to its contents is its own length and drags end to end`() {
        var value by mutableStateOf(0f)
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("panel")) {
                Text("VOLUME", Modifier.fillMaxWidth().testTag("label"))
                Slider(value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth().testTag("slider"), length = 160f)
            }
        }

        assertEquals(160f, ui.node("panel").width, "as long as the slider wants, not the screen")
        val slider = ui.node("slider").boundsInRoot
        val middle = (slider.top + slider.bottom) / 2f

        // Pressing the track puts the value under the pointer: the far end is the top only if the
        // knob's travel was worked out from the slider's real length.
        ui.click(Offset(slider.right - 8f, middle))
        ui.advanceBy(50)
        assertEquals(1f, value, absoluteTolerance = 0.01f, message = "pressed at the far end")

        ui.click(Offset(slider.left + 8f + 72f, middle))
        ui.advanceBy(50)
        assertEquals(0.5f, value, absoluteTolerance = 0.01f, message = "pressed halfway along the travel")
    }

    @Test
    fun `a stepper widened by a column sized to its label steps from its far arrow`() {
        var quality by mutableStateOf("Low")
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("panel")) {
                Text("TEXTURE QUALITY ON EVERY SURFACE", Modifier.fillMaxWidth())
                Stepper(listOf("Low", "High"), quality, onSelect = { quality = it }, modifier = Modifier.fillMaxWidth().testTag("quality"))
            }
        }

        val panel = ui.node("panel").width
        assertTrue(panel < 400f, "the panel is its label's width, not the screen's, it was $panel")
        assertEquals(panel, ui.node("quality").width, "the stepper is stretched to the label")

        // The right arrow sits at the end of the stretched stepper, where only the real measure put
        // it: a question asked first must not leave the pointer looking at the narrow one.
        val right = ui.node("quality").children[2].boundsInRoot
        assertTrue(ui.click(right.centre))
        ui.advanceBy(50)
        assertEquals("High", quality)
    }

    @Test
    fun `a panel with a smallest width sets the column however short its label is`() {
        var closed = 0
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("menu")) {
                Box(Modifier.widthIn(min = 240f).testTag("panel")) { Text("OK") }
                Button("CLOSE", onClick = { closed++ }, modifier = Modifier.fillMaxWidth().testTag("close"))
            }
        }

        assertEquals(240f, ui.node("menu").width)
        assertEquals(240f, ui.node("close").width)

        // Far past where CLOSE on its own would end.
        val close = ui.node("close").boundsInRoot
        ui.click(Offset(close.left + 230f, (close.top + close.bottom) / 2f))
        assertEquals(1, closed)
    }

    @Test
    fun `an adaptive grid in a column sized to its contents lays its tiles on one row and takes clicks`() {
        var picked = ""
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("panel")) {
                Grid(GridCells.Adaptive(minSize = 48f), Modifier.testTag("grid"), spacing = 4f) {
                    Button("A", onClick = { picked = "a" }, modifier = Modifier.testTag("a"))
                    Button("B", onClick = { picked = "b" }, modifier = Modifier.testTag("b"))
                    Button("C", onClick = { picked = "c" }, modifier = Modifier.testTag("c"))
                }
            }
        }

        // Asked how wide it would like to be, an adaptive grid cannot count columns in unbounded
        // room; it answers with every tile on one row of its own 48-wide cells instead of throwing.
        assertTrue(ui.node("a").width <= 48f, "the tiles fit their cells, so none is squeezed")
        assertEquals(48f * 3f + 8f, ui.node("panel").width, "three cells and two gaps, not the screen")
        assertEquals(ui.node("a").boundsInRoot.top, ui.node("c").boundsInRoot.top, "all on one row")
        assertTrue(ui.node("c").boundsInRoot.left >= 104f, "C is in the third cell")

        ui.click("c")
        assertEquals("c", picked)
    }

    @Test
    fun `a scrolled area in a column sized to its contents stays where the wheel left it`() {
        val state = ScrollState()
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max)) {
                ScrollArea(Modifier.height(100f).testTag("area"), state, bars = false) {
                    Column { repeat(20) { Text("LINE $it", Modifier.height(20f)) } }
                }
            }
        }

        ui.scroll("area", Offset(0f, 60f))
        ui.advanceBy(100)

        assertTrue(state.y > 0f, "the wheel scrolled the area")
        val scrolled = state.y
        ui.advanceBy(200)
        assertEquals(scrolled, state.y, "asking the area how wide it is does not scroll it back")
        assertEquals(300f, state.maxY, "and it still knows it is 400 of content in 100 of window")
    }

    @Test
    fun `a lazy list in a column sized to its contents stays where the wheel left it`() {
        val state = LazyListState()
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max)) {
                LazyColumn(40, Modifier.height(100f).testTag("list"), state, bars = false) {
                    Box(Modifier.size(80f, 20f)) {}
                }
            }
        }

        ui.scroll("list", Offset(0f, 60f))
        ui.advanceBy(100)

        assertTrue(state.position > 0f, "the wheel scrolled the list")
        val scrolled = state.position
        ui.advanceBy(200)
        assertEquals(scrolled, state.position, "asking the list how wide it is does not scroll it back")
        assertEquals(80f, ui.node("list").width, "and it is as wide as its items")
    }

    @Test
    fun `a typewriter sized to its narrowest is as wide as its longest word and keeps typing`() {
        val ui = open {
            Column {
                Text("SYSTEMS", Modifier.testTag("word"))
                Box(Modifier.width(IntrinsicSize.Min).testTag("box")) {
                    Typewriter(rememberTypewriter("ALL SYSTEMS GO"), Modifier.testTag("typed"))
                }
            }
        }

        ui.advanceBy(1_000)

        assertEquals(ui.node("word").width, ui.node("box").width, "broken at every space")
        assertEquals(ui.node("word").height * 3f, ui.node("typed").height, "three words and three lines")
    }

    @Test
    fun `a field in a column sized to its contents keeps its text where the typing left it`() {
        val typed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        var value by mutableStateOf(TextFieldValue(""))
        val ui = open {
            Box(Modifier.width(120f)) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    TextField(value, onValueChange = { value = it }, initialFocus = true, modifier = Modifier.testTag("field"))
                }
            }
        }

        ui.type(typed)
        // Back a few letters: the caret is still in view, so nothing should scroll.
        repeat(3) { ui.key(Key.Left) }
        ui.advanceBy(100)
        assertEquals(typed.length - 3, value.selection.end)

        // The end of the text is still at the right-hand edge, so a tap there lands on it.
        val field = ui.node("field").boundsInRoot
        ui.click(Offset(field.right - 12f, (field.top + field.bottom) / 2f))
        assertTrue(value.selection.end >= typed.length - 1, "the tap landed at ${value.selection.end} of ${typed.length}")
    }
}
