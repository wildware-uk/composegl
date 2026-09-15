package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.paddingFrom
import dev.wildware.composegl.ui.modifier.paddingFromBaseline
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.wrapContentHeight
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Text lined up by the line its letters stand on, and spaced from that line.
 *
 * Every screen here is composed for real in a [uiTest], laid out by the real pass and driven by the
 * mouse, keyboard and pad the way a player would; the numbers come from the monospace provider so
 * they can be checked on paper. At 32: ascent 25.6 and a line of 40, so the letters stand 25.6 down
 * and reach 14.4 below. At 16: ascent 12.8, a line of 20, 7.2 below.
 */
class BaselineTest {

    private val backend = HeadlessBackend(Rect.of(0f, 0f, 400f, 400f))
    private var ui: UiTest? = null

    private val big = TextStyle(size = 32f)
    private val small = TextStyle(size = 16f)

    @AfterTest
    fun tearDown() {
        ui?.close()
    }

    private fun show(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 400f), backend, content = content).also { ui = it }

    private fun node(tag: String) = checkNotNull(ui).node(tag)

    private fun top(tag: String) = node(tag).boundsInRoot.top

    /** Where the first line of [node]'s text stands, in the root's coordinates. */
    private fun baselineInRoot(node: UiNode): Float {
        assertFalse(node.firstBaseline.isNaN(), "${node.name} reported no baseline:\n${checkNotNull(ui).root.debugTree()}")
        return node.layoutBoundsInRoot.top + node.firstBaseline
    }

    private fun baselineInRoot(tag: String) = baselineInRoot(node(tag))

    /** Where [text] was drawn, in a whole frame drawn the way a game draws it. */
    private fun drawnAt(text: String): Offset {
        backend.canvas.clear(Rect.of(0f, 0f, 400f, 400f))
        checkNotNull(ui).render()
        return backend.canvas.calls.filterIsInstance<DrawCall.Text>().single { it.text == text }.at
    }

    private fun near(expected: Float, actual: Float, message: String = "") =
        assertTrue(abs(expected - actual) < 1e-3f, "expected $expected but was $actual. $message")

    // --- a row lining text up ----------------------------------------------------------------------

    @Test
    fun `a baseline row stands a big number and a small unit on one line`() {
        show {
            Row(Modifier.testTag("row"), verticalAlignment = VerticalAlignment.Baseline) {
                Text("120", Modifier.testTag("value"), textStyle = big)
                Text("HP", Modifier.testTag("unit"), textStyle = small)
            }
        }

        near(0f, top("value"), "the tallest reach above the line sits at the top")
        near(12.8f, top("unit"), "the small label drops by the difference in ascent")
        near(baselineInRoot("value"), baselineInRoot("unit"), "and both stand on the same line")
        near(40f, node("row").height, "25.6 above the line and 14.4 below it")
        near(25.6f, node("row").firstBaseline, "the row reports the line it lined up")
        near(12.8f, drawnAt("HP").y, "the unit is drawn where it was laid out")
    }

    @Test
    fun `a top row leaves the same two labels with their tops level`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Top) {
                Text("120", Modifier.testTag("value"), textStyle = big)
                Text("HP", Modifier.testTag("unit"), textStyle = small)
            }
        }

        near(0f, top("unit"), "without asking for a baseline nothing lines up")
    }

    @Test
    fun `a child with no text in a baseline row goes at the top`() {
        show {
            Row(Modifier.testTag("row"), verticalAlignment = VerticalAlignment.Baseline) {
                Box(Modifier.size(10f).testTag("pip"))
                Text("HP", Modifier.testTag("unit"), textStyle = small)
                Box(Modifier.size(60f).testTag("tall"))
            }
        }

        near(0f, top("pip"))
        near(0f, top("tall"))
        near(0f, top("unit"), "the only text there is sets the line")
        near(60f, node("row").height, "the tallest child still decides the height")
    }

    @Test
    fun `a child that says where it goes is not lined up`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("120", textStyle = big)
                Text("HP", Modifier.align(Alignment.BottomStart).testTag("unit"), textStyle = small)
            }
        }

        near(20f, top("unit"), "its own bottom alignment wins over the row's baseline")
    }

    @Test
    fun `children can ask for the baseline in a row that does not`() {
        val onTheLine = Alignment(vertical = VerticalAlignment.Baseline)
        show {
            Row(verticalAlignment = VerticalAlignment.Centre) {
                Text("120", Modifier.align(onTheLine).testTag("value"), textStyle = big)
                Text("HP", Modifier.align(onTheLine).testTag("unit"), textStyle = small)
                Box(Modifier.size(100f).testTag("icon"))
            }
        }

        near(baselineInRoot("value"), baselineInRoot("unit"))
        near(12.8f, top("unit"))
        near(0f, top("icon"), "the icon is centred in a row exactly as tall as it is")
    }

    @Test
    fun `a paragraph lines up by its first line`() {
        show {
            Row(Modifier.testTag("row"), verticalAlignment = VerticalAlignment.Baseline) {
                Text("A\nB", Modifier.testTag("value"), textStyle = big)
                Text("HP", Modifier.testTag("unit"), textStyle = small)
            }
        }

        near(12.8f, top("unit"))
        near(65.6f, node("value").lastBaseline, "the last line is a line height further down")
        near(80f, node("row").height)
    }

    @Test
    fun `padding counts toward where the line is and offset does not`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("120", textStyle = big)
                Text("HP", Modifier.padding(top = 6f).testTag("padded"), textStyle = small)
                Text("MP", Modifier.offset(y = 5f).testTag("nudged"), textStyle = small)
            }
        }

        near(6.8f, top("padded"), "the letters stand 18.8 down a padded label, so it goes 6.8 down")
        near(17.8f, top("nudged"), "lined up at 12.8 and then moved 5 by its own offset")
    }

    @Test
    fun `a column inside a row stands on the line of its first text`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("120", textStyle = big)
                Column(Modifier.testTag("stack")) {
                    Text("HP", textStyle = small)
                    Text("MAX", textStyle = small)
                }
            }
        }

        val stack = node("stack")
        near(12.8f, stack.firstBaseline, "a layout reports the first line inside it")
        near(32.8f, stack.lastBaseline, "and the last")
        near(12.8f, stack.boundsInRoot.top)
    }

    @Test
    fun `a label with styled runs lines up like any other`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("120", Modifier.testTag("value"), textStyle = big)
                Text("HP", Modifier.testTag("unit"), textStyle = small, runs = emptyList())
            }
        }

        near(baselineInRoot("value"), baselineInRoot("unit"))
        near(12.8f, top("unit"))
    }

    @Test
    fun `a text field stands on the same line as the label beside it`() {
        show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("NAME", Modifier.testTag("label"), textStyle = big)
                TextField("ada", onValueChange = {}, modifier = Modifier.width(120f).testTag("field"))
            }
        }

        near(baselineInRoot("label"), baselineInRoot("field"))
    }

    @Test
    fun `typing past the end of a field does not move it off the line`() {
        var name by mutableStateOf("")
        val ui = show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("NAME", Modifier.testTag("label"), textStyle = big)
                TextField(name, onValueChange = { name = it }, modifier = Modifier.width(60f).testTag("field"))
            }
        }
        ui.click("field")
        val before = baselineInRoot("field")
        near(baselineInRoot("label"), before)

        // Far more than 60 wide holds, so the field scrolls to keep the caret in view.
        ui.type("a very long name indeed")

        assertEquals("a very long name indeed", name, "the keys reached the field")
        near(before, baselineInRoot("field"), "the field's line did not hop as it scrolled")
        near(baselineInRoot("label"), baselineInRoot("field"))
    }

    @Test
    fun `a button beside a label stays on its line as the label grows`() {
        var large by mutableStateOf(false)
        val ui = show {
            Row(verticalAlignment = VerticalAlignment.Baseline) {
                Text("SCORE", Modifier.testTag("score"), textStyle = if (large) big else small)
                Button("GROW", onClick = { large = !large }, modifier = Modifier.testTag("grow"))
            }
        }

        val label = { node("grow").firstOrNull { it.name == "text" }!! }
        near(baselineInRoot("score"), baselineInRoot(label()), "a button's baseline is its label's")
        val before = baselineInRoot("score")

        ui.click("grow")

        near(25.6f, node("score").firstBaseline, "the click made the score big")
        near(baselineInRoot("score"), baselineInRoot(label()), "and the button followed it down")
        assertTrue(baselineInRoot("score") > before, "the line moved down as the score grew")
    }

    @Test
    fun `a pad press that shrinks the value brings the unit back up to its line`() {
        var large by mutableStateOf(true)
        val ui = show {
            Column {
                Button("SHRINK", onClick = { large = !large }, modifier = Modifier.testTag("shrink"))
                Row(Modifier.testTag("row"), verticalAlignment = VerticalAlignment.Baseline) {
                    Text("120", Modifier.testTag("value"), textStyle = if (large) big else small)
                    Text("HP", Modifier.testTag("unit"), textStyle = small)
                }
            }
        }
        val rowTop = top("row")
        near(rowTop + 12.8f, top("unit"))

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("shrink")
        ui.pad(GamepadButton.South)

        near(rowTop, top("unit"), "both the same size now, so both at the top of the row")
        near(baselineInRoot("value"), baselineInRoot("unit"))
        near(20f, node("row").height)
        near(rowTop, drawnAt("HP").y, "and drawn there")
    }

    // --- spacing from a baseline -------------------------------------------------------------------

    @Test
    fun `padding from the first baseline puts the line that far down`() {
        show {
            Column {
                Box(Modifier.height(10f))
                Text("Title", Modifier.paddingFrom(Baseline.First, before = 24f).testTag("title"), textStyle = small)
            }
        }

        val title = node("title")
        near(10f, title.boundsInRoot.top)
        near(24f, title.firstBaseline, "the line is 24 down from the top of the node")
        near(31.2f, title.height, "11.2 of room above a 20 line")
        near(21.2f, drawnAt("Title").y, "and the letters are drawn below that room")
    }

    @Test
    fun `padding from a baseline already further down adds nothing`() {
        show { Text("Title", Modifier.paddingFrom(Baseline.First, before = 5f).testTag("title"), textStyle = small) }

        near(20f, node("title").height)
        near(0f, drawnAt("Title").y)
    }

    @Test
    fun `padding after the last baseline pushes the next thing down`() {
        show {
            Column {
                Text("Body", Modifier.paddingFrom(Baseline.Last, after = 20f).testTag("body"), textStyle = small)
                Box(Modifier.size(10f).testTag("next"))
            }
        }

        near(32.8f, node("body").height, "7.2 below the line becomes 20")
        near(32.8f, top("next"))
        near(0f, drawnAt("Body").y, "nothing was added above it")
    }

    @Test
    fun `padding from both baselines of a paragraph`() {
        show { Text("one\ntwo", Modifier.paddingFromBaseline(top = 30f, bottom = 30f).testTag("para"), textStyle = small) }

        val para = node("para")
        near(30f, para.firstBaseline)
        near(50f, para.lastBaseline)
        near(80f, para.height, "30 to the first line and 20 between them and 30 after the last")
    }

    @Test
    fun `padding from a baseline on something with no text adds nothing`() {
        show { Box(Modifier.size(10f).paddingFrom(Baseline.First, before = 40f).testTag("pip")) }

        near(10f, node("pip").height)
        assertTrue(node("pip").firstBaseline.isNaN())
    }

    @Test
    fun `a panel spaced from the text inside it paints across the room it added`() {
        val red = Colour.rgb(0xFF0000)
        show {
            Box(Modifier.background(red).paddingFrom(Baseline.First, before = 30f).testTag("panel")) {
                Text("HP", Modifier.testTag("unit"), textStyle = small)
            }
        }

        near(37.2f, node("panel").height)
        near(17.2f, top("unit"), "the text inside moved down by the room")
        near(17.2f, drawnAt("HP").y)
        val painted = backend.canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == red }
        near(37.2f, painted.rect.height, "the background covers the whole node")
    }

    @Test
    fun `a label spaced from its baseline inside a rounded clip is drawn on that line`() {
        show {
            Text(
                "HP",
                Modifier.clip(8f).paddingFrom(Baseline.First, before = 30f).testTag("unit"),
                textStyle = small,
            )
        }

        near(30f, node("unit").firstBaseline)
        near(top("unit") + 17.2f, drawnAt("HP").y, "drawn below the room, not over it")
    }

    @Test
    fun `clicking a button that changes the spacing moves the title`() {
        var gap by mutableStateOf(20f)
        val ui = show {
            Column {
                Button("MORE", onClick = { gap += 20f }, modifier = Modifier.testTag("more"))
                Text("Title", Modifier.paddingFrom(Baseline.First, before = gap).testTag("title"), textStyle = small)
            }
        }
        near(20f, node("title").firstBaseline)

        ui.click("more")

        near(40f, node("title").firstBaseline)
        near(top("title") + 27.2f, drawnAt("Title").y, "drawn 27.2 into the node after the click")
    }

    @Test
    fun `pressing enter on a focused button tightens the spacing again`() {
        var gap by mutableStateOf(40f)
        val ui = show {
            Column {
                Button("LESS", onClick = { gap -= 20f }, modifier = Modifier.testTag("less"))
                Text("Title", Modifier.paddingFrom(Baseline.First, before = gap).testTag("title"), textStyle = small)
            }
        }
        near(47.2f, node("title").height)

        ui.key(Key.Tab)
        ui.assertFocused("less")
        ui.key(Key.Enter)

        near(20f, node("title").firstBaseline, "the line came back up to 20")
        near(27.2f, node("title").height)
    }

    @Test
    fun `a click on a word below the room from a baseline lands on that word`() {
        val clicked = mutableListOf<Any?>()
        val ui = show {
            Text(
                "one two",
                Modifier.paddingFrom(Baseline.First, before = 40f).testTag("label"),
                textStyle = small,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunClick = { clicked += it.tag },
            )
        }
        ui.render()
        val label = node("label").boundsInRoot
        // Seven letters of one width, so the middle of "two" is five and a half letters in.
        val x = label.left + label.width * 5.5f / 7f

        ui.click(Offset(x, 10f))
        assertEquals(emptyList(), clicked, "the room above the letters is not the word")

        ui.render()
        ui.click(Offset(x, 27.2f + 10f))
        assertEquals(listOf<Any?>("two"), clicked, "the letters were drawn 27.2 down, and clicked there")
    }

    @Test
    fun `negative room from a baseline is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.paddingFrom(Baseline.First, before = -1f) }
        assertFailsWith<IllegalArgumentException> { Modifier.paddingFrom(Baseline.Last, after = -1f) }
    }

    @Test
    fun `a label kept small in a forced slot hands its parent the line where it actually stands`() {
        var bottom by mutableStateOf(false)
        var reported = Float.NaN
        val ui = show {
            Column {
                Button("MOVE", onClick = { bottom = !bottom }, modifier = Modifier.testTag("move"))
                // An 80-tall slot forced on the label, which wraps its content and so stays 20 tall
                // inside it. The layout reads the line off the placeable, the way a baseline row does.
                Layout(
                    Modifier.testTag("slot"),
                    content = {
                        val where = if (bottom) VerticalAlignment.Bottom else VerticalAlignment.Top
                        Text("HP", Modifier.wrapContentHeight(where).testTag("unit"), textStyle = small)
                    },
                ) { measurables, _ ->
                    val placeable = measurables[0].measure(Constraints.fixed(100f, 80f))
                    reported = placeable.firstBaseline
                    layout(100f, 80f) { placeable.at(0f, 0f) }
                }
            }
        }

        near(12.8f, reported, "at the top of the slot the line is where the label's own is")
        near(baselineInRoot("unit") - top("slot"), reported)

        ui.click("move")

        near(20f, node("unit").height, "still its own height")
        near(60f + 12.8f, reported, "moved to the bottom, the line it reports moved with it")
        near(baselineInRoot("unit") - top("slot"), reported, "and matches where its letters are drawn from")
    }

    // --- the model underneath ----------------------------------------------------------------------

    @Test
    fun `a custom layout reports its own baseline and a row lines it up`() {
        val tree = UiTree()
        val glyph = UiNode("glyph").apply {
            measurePolicy = MeasurePolicy { _, constraints ->
                layout(constraints.constrainWidth(20f), constraints.constrainHeight(30f), 10f, 10f) {}
            }
        }
        val label = UiNode("label").apply {
            measurePolicy = MeasurePolicy { _, constraints ->
                layout(constraints.constrainWidth(20f), constraints.constrainHeight(24f), 20f, 20f) {}
            }
        }
        val plain = UiNode("plain").apply { measurePolicy = MeasurePolicy.Empty }
        val row = UiNode("row").apply {
            measurePolicy = LinearPolicy(true, Arrangement.Start, Alignment(vertical = VerticalAlignment.Baseline))
            insertAt(0, glyph)
            insertAt(1, label)
            insertAt(2, plain)
        }
        tree.root.insertAt(0, row)
        MeasurePass().run(tree.root, Constraints.atMost(400f, 400f))

        near(10f, glyph.y, "10 above its line, lined up with a line 20 down")
        near(0f, label.y)
        near(40f, row.height, "20 above the line and 20 below the glyph's")
        near(20f, row.firstBaseline)
        assertTrue(plain.firstBaseline.isNaN(), "a layout with no text and no children has no line")
    }

    @Test
    fun `a baseline alone in a box is the top`() {
        assertEquals(0f, Alignment(vertical = VerticalAlignment.Baseline).yIn(100f, 20f))
    }
}
