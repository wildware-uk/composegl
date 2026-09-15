package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.text.TextStyle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Flow rows and flow columns in real composed UI, driven the way a player drives them.
 *
 * The hand-built trees in the layout tests say where things go; these say that a player can click,
 * walk focus through and see what wrapped onto the next line.
 */
class FlowUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 400f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    @Composable
    private fun Chip(index: Int) {
        Box(Modifier.size(90f, 30f).background(Colour.rgb(0x2C3545)).testTag("chip$index")) {}
    }

    @Composable
    private fun Buttons(words: List<String>, clicked: MutableList<String> = mutableListOf()) {
        FlowRow(Modifier.width(200f).testTag("flow"), horizontalSpacing = 8f, verticalSpacing = 8f) {
            words.forEach { word ->
                Button(
                    word,
                    onClick = { clicked += word },
                    initialFocus = word == words.first(),
                    modifier = Modifier.width(90f).testTag(word),
                )
            }
        }
    }

    private fun UiTest.tops(tags: List<String>) = tags.map { node(it).boundsInRoot.top }

    @Test
    fun `chips that do not fit wrap onto a second line in composed UI`() {
        val ui = open {
            FlowRow(Modifier.width(300f).testTag("flow"), horizontalSpacing = 10f, verticalSpacing = 5f) {
                repeat(5) { Chip(it) }
            }
        }

        val flow = ui.node("flow")
        assertEquals("flowRow", flow.name)
        // 90 + 10 + 90 + 10 + 90 = 290 fits in 300; the fourth starts a new line.
        assertEquals(listOf(0f, 0f, 0f, 35f, 35f), ui.tops(List(5) { "chip$it" }))
        assertEquals(100f, ui.node("chip1").boundsInRoot.left)
        assertEquals(0f, ui.node("chip3").boundsInRoot.left)
        assertEquals(65f, flow.height)
    }

    @Test
    fun `a button that wrapped onto the second line takes the click where it is drawn`() {
        val clicked = mutableListOf<String>()
        val ui = open { Buttons(listOf("ONE", "TWO", "THREE", "FOUR"), clicked) }

        val four = ui.node("FOUR").boundsInRoot
        val one = ui.node("ONE").boundsInRoot
        assertTrue(four.top >= one.bottom, "FOUR wrapped below ONE: $four vs $one")

        assertTrue(ui.click("FOUR"))
        assertEquals(listOf("FOUR"), clicked)
        ui.assertFocused("FOUR")
    }

    @Test
    fun `the words on wrapped buttons are still drawn`() {
        val ui = open { Buttons(listOf("ONE", "TWO", "THREE")) }

        ui.assertText("THREE", "THREE")
        assertEquals(listOf("ONE", "TWO", "THREE"), ui.texts("flow"))
    }

    @Test
    fun `adding an item at runtime rewraps the flow`() {
        var count by mutableStateOf(3)
        val ui = open {
            Column {
                Button("MORE", onClick = { count++ }, modifier = Modifier.testTag("more"))
                FlowRow(Modifier.width(300f).testTag("flow"), horizontalSpacing = 10f) {
                    repeat(count) { Chip(it) }
                }
            }
        }
        assertEquals(30f, ui.node("flow").height)
        ui.assertDoesNotExist("chip3")

        ui.click("more")

        assertEquals(60f, ui.node("flow").height, "the new chip opened a second line")
        assertEquals(ui.node("flow").boundsInRoot.top + 30f, ui.node("chip3").boundsInRoot.top)
    }

    @Test
    fun `removing items at runtime pulls the flow back onto one line`() {
        var count by mutableStateOf(5)
        val ui = open {
            FlowRow(Modifier.width(300f).testTag("flow"), horizontalSpacing = 10f) {
                repeat(count) { Chip(it) }
            }
        }
        assertEquals(60f, ui.node("flow").height)

        count = 2
        ui.settle()

        assertEquals(30f, ui.node("flow").height)
        ui.assertDoesNotExist("chip4")
        assertEquals(2, ui.node("flow").children.size)
    }

    @Test
    fun `a flow taken off the screen takes its children with it`() {
        var shown by mutableStateOf(true)
        val ui = open {
            Column {
                Button("HIDE", onClick = { shown = false }, modifier = Modifier.testTag("hide"))
                if (shown) {
                    FlowRow(Modifier.width(200f).testTag("flow")) { repeat(4) { Chip(it) } }
                }
            }
        }
        ui.assertExists("chip3")

        ui.click("hide")

        ui.assertDoesNotExist("flow")
        ui.assertDoesNotExist("chip0")
        ui.assertDoesNotExist("chip3")
    }

    @Test
    fun `an empty flow takes no room in composed UI`() {
        val ui = open {
            Column {
                FlowRow(Modifier.testTag("flow"), horizontalSpacing = 10f, verticalSpacing = 10f) {}
                Box(Modifier.size(10f).testTag("after")) {}
            }
        }

        assertEquals(0f, ui.node("flow").width)
        assertEquals(0f, ui.node("flow").height)
        assertEquals(0f, ui.node("after").boundsInRoot.top)
    }

    @Test
    fun `a narrower screen wraps the same flow into more lines`() {
        // The screen size is the one thing uiTest fixes when it opens, so this drives a host itself.
        val host = UiHost()
        val focus = FocusManager(host.root)
        var room = Constraints.atMost(400f, 400f)
        var clock = 0L
        fun settle() {
            repeat(8) {
                clock += 16_666_667L
                if (!host.settle(room, focus, nanos = clock)) return
            }
            throw AssertionError("settle still reported a change after 8 turns")
        }
        try {
            host.setContent {
                ProvideFonts(MonospaceFontProvider()) {
                    FlowRow(Modifier.testTag("flow"), horizontalSpacing = 10f) {
                        repeat(4) { Chip(it) }
                    }
                }
            }
            settle()
            assertEquals(30f, host.root.find("flow").height, "four chips fit across 400")

            room = Constraints.atMost(200f, 400f)
            settle()

            assertEquals(60f, host.root.find("flow").height)
            assertEquals(listOf(0f, 0f, 30f, 30f), (0 until 4).map { host.root.find("chip$it").boundsInRoot.top })
        } finally {
            host.dispose()
        }
    }

    @Test
    fun `the arrow keys walk focus down from one line to the next`() {
        val ui = open { Buttons(listOf("ONE", "TWO", "THREE", "FOUR")) }
        ui.assertFocused("ONE")

        ui.key(Key.Right)
        ui.assertFocused("TWO")
        ui.key(Key.Down)
        ui.assertFocused("FOUR")
        ui.key(Key.Left)
        ui.assertFocused("THREE")
        ui.key(Key.Up)
        ui.assertFocused("ONE")
    }

    @Test
    fun `a gamepad presses a button that wrapped`() {
        val clicked = mutableListOf<String>()
        val ui = open { Buttons(listOf("ONE", "TWO", "THREE"), clicked) }

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("THREE")

        ui.pad(GamepadButton.South)
        assertEquals(listOf("THREE"), clicked)
    }

    @Test
    fun `a limit on items per row wraps chips that would fit`() {
        val ui = open {
            FlowRow(Modifier.width(400f), maxItemsInEachRow = 2) {
                repeat(4) { Chip(it) }
            }
        }

        assertEquals(listOf(0f, 0f, 30f, 30f), ui.tops(List(4) { "chip$it" }))
        assertEquals(0f, ui.node("chip2").boundsInRoot.left)
    }

    @Test
    fun `a centred flow centres its short last line on its own`() {
        val ui = open {
            FlowRow(Modifier.width(300f).testTag("flow"), horizontalSpacing = 10f, horizontalArrangement = Arrangement.Centre) {
                repeat(4) { Chip(it) }
            }
        }

        assertEquals(5f, ui.node("chip0").boundsInRoot.left, "line one is 290 wide in 300")
        assertEquals(105f, ui.node("chip3").boundsInRoot.left, "a lone chip in the middle of 300")
    }

    @Test
    fun `a flow inside a flow wraps inside its own width`() {
        val ui = open {
            FlowRow(Modifier.width(300f), horizontalSpacing = 10f, verticalSpacing = 10f) {
                Box(Modifier.size(150f, 20f).testTag("wide")) {}
                FlowRow(Modifier.width(100f).testTag("inner")) {
                    repeat(3) { Box(Modifier.size(50f).testTag("cell$it")) {} }
                }
            }
        }

        val inner = ui.node("inner").boundsInRoot
        assertEquals(160f to 0f, inner.left to inner.top, "the inner flow fits beside the wide box")
        assertEquals(100f, inner.height, "two lines of fifty inside it")
        assertEquals(160f to 50f, ui.node("cell2").boundsInRoot.let { it.left to it.top })
    }

    @Test
    fun `a flow column wraps into a second column in composed UI`() {
        val ui = open {
            FlowColumn(Modifier.size(300f, 100f), horizontalSpacing = 10f, verticalSpacing = 5f) {
                repeat(4) { index ->
                    Box(Modifier.size(40f, 30f).testTag("cell$index")) {}
                }
            }
        }

        val corners = (0 until 4).map { ui.node("cell$it").boundsInRoot.let { b -> b.left to b.top } }
        assertEquals(listOf(0f to 0f, 0f to 35f, 0f to 70f, 50f to 0f), corners)
    }

    @Test
    fun `the down key walks a flow column and right jumps to the next column`() {
        val ui = open {
            FlowColumn(Modifier.size(300f, 100f), horizontalSpacing = 10f) {
                listOf("A", "B", "C").forEach { word ->
                    Button(word, onClick = {}, initialFocus = word == "A", modifier = Modifier.size(60f, 40f).testTag(word))
                }
            }
        }

        ui.key(Key.Down)
        ui.assertFocused("B")
        ui.key(Key.Right)
        ui.assertFocused("C")
    }

    @Test
    fun `the wrapped line is drawn where it was laid out`() {
        val red = Colour.rgb(0xFF0000)
        val ui = open {
            FlowRow(Modifier.width(100f), verticalSpacing = 4f) {
                repeat(3) { Box(Modifier.size(40f, 20f).background(red)) {} }
            }
        }

        val canvas = RecordingCanvas()
        DrawPass(canvas).draw(ui.root)
        val drawn = canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.colour == red }
        assertEquals(
            listOf(0f to 0f, 40f to 0f, 0f to 24f),
            drawn.map { it.rect.left to it.rect.top },
            "the third box is painted on the second line",
        )
    }

    @Test
    fun `a panel sized to its narrowest flow stacks the chips and still takes clicks`() {
        val clicked = mutableListOf<String>()
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Min).testTag("panel")) {
                FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
                    listOf("ONE", "TWO", "THREE").forEach { word ->
                        Button(word, onClick = { clicked += word }, modifier = Modifier.width(90f).testTag(word))
                    }
                }
            }
        }

        // A flow can wrap, so the least room it needs is its widest child, not the whole line.
        assertEquals(90f, ui.node("panel").width)
        assertEquals(ui.node("TWO").boundsInRoot.top, ui.node("ONE").boundsInRoot.bottom + 8f)

        assertTrue(ui.click("THREE"))
        assertEquals(listOf("THREE"), clicked)
    }

    @Test
    fun `a bar beside a flow sized to its height reaches the bottom of the last line`() {
        var count by mutableStateOf(4)
        val ui = open {
            Row(Modifier.height(IntrinsicSize.Min).testTag("row")) {
                FlowRow(Modifier.width(200f).testTag("flow"), horizontalSpacing = 10f, verticalSpacing = 5f) {
                    repeat(count) { Chip(it) }
                }
                Box(Modifier.width(6f).fillMaxHeight().testTag("bar")) {}
            }
        }

        // Two lines of 30 and a gap of 5; asked how tall it is at 200 wide, the flow says so.
        assertEquals(65f, ui.node("flow").height)
        assertEquals(65f, ui.node("bar").height)

        count = 5
        ui.settle()
        assertEquals(100f, ui.node("bar").height, "a third line makes the bar longer too")
    }

    @Test
    fun `a flow column sized to its contents is as wide as its columns`() {
        val ui = open {
            Row(Modifier.width(IntrinsicSize.Max).testTag("panel")) {
                FlowColumn(Modifier.height(70f), horizontalSpacing = 10f, verticalSpacing = 10f) {
                    repeat(3) { index -> Box(Modifier.size(40f, 30f).testTag("cell$index")) {} }
                }
            }
        }

        assertEquals(50f, ui.node("cell2").boundsInRoot.left, "the third cell opened a second column")
        assertEquals(90f, ui.node("panel").width, "two columns of 40 and the gap between them")
    }

    @Test
    fun `a baseline flow row stands the words of each line on one line`() {
        var value by mutableStateOf("120")
        val ui = open {
            // 57.6 for "120" and 19.2 for "HP" fit in 100; "99" at 38.4 more does not.
            FlowRow(Modifier.width(100f).testTag("flow"), verticalSpacing = 4f, verticalAlignment = VerticalAlignment.Baseline) {
                Text(value, Modifier.testTag("value"), textStyle = TextStyle(size = 32f))
                Text("HP", Modifier.testTag("hp"), textStyle = TextStyle(size = 16f))
                Text("99", Modifier.testTag("mp"), textStyle = TextStyle(size = 32f))
                Text("MP", Modifier.testTag("unit"), textStyle = TextStyle(size = 16f))
            }
        }

        fun baseline(tag: String) = ui.node(tag).let { it.layoutBoundsInRoot.top + it.firstBaseline }
        fun near(expected: Float, actual: Float, message: String) =
            assertTrue(kotlin.math.abs(expected - actual) < 1e-3f, "expected $expected but was $actual. $message")

        assertTrue(ui.node("mp").boundsInRoot.top > 0f, "the second pair wrapped: ${ui.root.debugTree()}")
        near(baseline("value"), baseline("hp"), "the first line's words share a line")
        near(baseline("mp"), baseline("unit"), "and so do the second line's")
        assertTrue(ui.node("hp").boundsInRoot.top > ui.node("value").boundsInRoot.top, "the small word dropped")
        near(baseline("value"), ui.node("flow").layoutBoundsInRoot.top + ui.node("flow").firstBaseline, "the flow reports the first line")
    }

    @Test
    fun `recomposing with the same settings costs the frame nothing`() {
        var ticks by mutableStateOf(0)
        val ui = open {
            // A content lambda built afresh each tick, so the flow itself runs again rather than
            // being skipped, and builds its policy and a spacedBy arrangement all over again.
            val tick = ticks
            val chips: @Composable () -> Unit = remember(tick) { { repeat(3) { Chip(it) } } }
            FlowRow(
                Modifier.width(300f),
                horizontalSpacing = 4f,
                verticalSpacing = 4f,
                horizontalArrangement = Arrangement.spacedBy(2f),
                content = chips,
            )
        }
        ui.render()

        ticks++
        assertFalse(ui.render(), "an equal flow policy is no change to draw")
    }
}
