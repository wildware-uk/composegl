package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextAnchor
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.PromptGlyph
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.TooltipHost
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.rememberTypewriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `TextMetricsOverlay`: the line box, ascent, cap height, baseline and descent of every piece of
 * text, drawn through it.
 *
 * Every test composes a real screen with [uiTest], turns the overlay on the way a player would, and
 * reads the lines back off a recording canvas. The headless fonts make every number workable on
 * paper: at 20, a character is 12 wide, the ascent is 16, the capitals 14, the descent 4 and a line
 * 25 — so the lines of a label placed at y sit at y, y + 2, y + 16 and y + 20, in a box to y + 25.
 */
class TextMetricsOverlayTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val twenty = TextStyle(size = 20f)

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private fun drawn(ui: UiTest): List<DrawCall> {
        val canvas = ui.backend.canvas as RecordingCanvas
        canvas.clear()
        ui.render()
        return canvas.calls.toList()
    }

    private fun List<DrawCall>.lines(colour: Colour) =
        filterIsInstance<DrawCall.Rectangle>().filter { it.colour == colour }.map { it.rect }

    private fun List<DrawCall>.boxes(colour: Colour) =
        filterIsInstance<DrawCall.Border>().filter { it.colour == colour }.map { it.rect }

    private fun isOverlay(call: DrawCall) = when (call) {
        is DrawCall.Border -> call.colour in OverlayInks
        is DrawCall.Rectangle -> call.colour in OverlayInks
        else -> false
    }

    /** A one-unit line from [left] to [right] with its top at [y]. */
    private fun line(left: Float, right: Float, y: Float) = Rect(left, y, right, y + 1f)

    /** "HP" at 100, 50 in the 20-unit style, and a button that toggles the overlay. */
    @Composable
    private fun Screen(show: Set<TextGuide> = TextGuide.All) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"), initialFocus = true)
            Text("HP", Modifier.offset(100f, 50f).testTag("hp"), textStyle = twenty)
            TextMetricsOverlay(debug, show)
        }
    }

    @Test
    fun `off it composes nothing and draws nothing`() {
        val ui = open { Screen() }

        assertNull(ui.root.firstOrNull { it.name == TextOverlayName })
        assertTrue(drawn(ui).none(::isOverlay))
    }

    @Test
    fun `a click draws the line box and the four lines of a label in their own colours`() {
        val ui = open { Screen() }
        ui.click("toggle")
        val calls = drawn(ui)

        assertEquals(Rect(100f, 50f, 124f, 75f), ui.node("hp").layoutBoundsInRoot, "the label is where the numbers say")
        assertTrue(Rect(100f, 50f, 124f, 75f) in calls.boxes(TextMetricsColours.LineBox), "line box")
        assertTrue(line(100f, 124f, 50f) in calls.lines(TextMetricsColours.Ascent), "ascent")
        assertTrue(line(100f, 124f, 52f) in calls.lines(TextMetricsColours.CapHeight), "cap height")
        assertTrue(line(100f, 124f, 66f) in calls.lines(TextMetricsColours.Baseline), "baseline")
        assertTrue(line(100f, 124f, 70f) in calls.lines(TextMetricsColours.Descent), "descent")

        ui.click("toggle")
        assertTrue(drawn(ui).none(::isOverlay), "a second click takes it off")
    }

    @Test
    fun `the pad turns it on as well`() {
        val ui = open { Screen() }
        ui.assertFocused("toggle")

        ui.pad(GamepadButton.South)

        assertTrue(line(100f, 124f, 66f) in drawn(ui).lines(TextMetricsColours.Baseline))
    }

    @Test
    fun `every piece of text gets its own lines and the button label is text too`() {
        val ui = open { Screen() }
        ui.click("toggle")

        val baselines = drawn(ui).lines(TextMetricsColours.Baseline)
        val texts = mutableListOf<UiNode>()
        fun collect(node: UiNode) {
            if (node.name == "text") texts += node
            node.children.forEach(::collect)
        }
        collect(ui.root)
        assertEquals(2, texts.size, "the button's label and HP")
        assertEquals(texts.size, baselines.size)
        texts.forEach { text ->
            val box = text.layoutBoundsInRoot
            assertTrue(baselines.any { it.top == box.top + text.firstBaseline }, "a baseline for ${text.name} at $box")
        }
    }

    @Test
    fun `text anchored by its capitals or its baseline shows that line on the y it was given`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Text("CAP", Modifier.offset(20f, 200f), textStyle = twenty, anchor = TextAnchor.CapTop)
                // Two sizes placed by their baselines on one y: one green line, at that y, under both.
                Text("HP", Modifier.offset(120f, 200f).testTag("small"), textStyle = TextStyle(size = 16f), anchor = TextAnchor.Baseline)
                Text("120", Modifier.offset(180f, 200f).testTag("large"), textStyle = TextStyle(size = 32f), anchor = TextAnchor.Baseline)
                TextMetricsOverlay(debug)
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(line(20f, 56f, 200f) in calls.lines(TextMetricsColours.CapHeight), "the capitals start at 200: ${calls.lines(TextMetricsColours.CapHeight)}")
        val baselines = calls.lines(TextMetricsColours.Baseline)
        assertTrue(line(120f, 139.2f, 200f) in baselines, "the small label stands on 200: $baselines")
        assertTrue(line(180f, 237.6f, 200f) in baselines, "and so does the large one")
        assertTrue(ui.node("small").layoutBoundsInRoot.top != ui.node("large").layoutBoundsInRoot.top, "though their boxes start apart")
    }

    @Test
    fun `a wrapped label draws a set of lines for every line`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                // Room for five characters: "AB CD", then "EF".
                Text("AB CD EF", Modifier.offset(40f, 20f).width(60f).testTag("wrapped"), textStyle = twenty)
                TextMetricsOverlay(true)
            }
        }
        val calls = drawn(ui)

        assertEquals(
            listOf(Rect(40f, 20f, 100f, 45f), Rect(40f, 45f, 100f, 70f)),
            calls.boxes(TextMetricsColours.LineBox),
        )
        assertEquals(listOf(36f, 61f), calls.lines(TextMetricsColours.Baseline).map { it.top })
        assertEquals(listOf(40f, 65f), calls.lines(TextMetricsColours.Descent).map { it.top })
        assertEquals(ui.node("wrapped").layoutBoundsInRoot.top + ui.node("wrapped").lastBaseline, 61f)
    }

    @Test
    fun `styled runs centred line by line put each line's guides under its own glyphs`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "AAAA BB",
                    Modifier.offset(0f, 100f).width(60f).testTag("runs"),
                    textStyle = twenty,
                    align = HorizontalAlignment.Centre,
                    runs = listOf(TextRun(TextRange(0, 4), colour = Colour.Red)),
                )
                TextMetricsOverlay(true, setOf(TextGuide.Baseline))
            }
        }
        val node = ui.node("runs")
        val baselines = drawn(ui).lines(TextMetricsColours.Baseline)

        // "AAAA" is 48 of the 60, so 6 in; "BB" is 24, so 18 in.
        assertEquals(listOf(line(6f, 54f, 116f), line(18f, 42f, 141f)), baselines)
        assertEquals(node.layoutBoundsInRoot.top + node.firstBaseline, baselines.first().top)
        assertEquals(node.layoutBoundsInRoot.top + node.lastBaseline, baselines.last().top)
    }

    @Test
    fun `a centred label in a wider box has its lines under the glyphs and not the box`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Text("HP", Modifier.offset(100f, 0f).width(200f).padding(10f), textStyle = twenty, align = HorizontalAlignment.Centre)
                TextMetricsOverlay(true, setOf(TextGuide.LineBox, TextGuide.Baseline))
            }
        }
        val calls = drawn(ui)

        // The content box is 110 to 290 and 10 down; "HP" is 24 of its 180, so 78 in.
        assertEquals(listOf(Rect(188f, 10f, 212f, 35f)), calls.boxes(TextMetricsColours.LineBox))
        assertEquals(listOf(line(188f, 212f, 26f)), calls.lines(TextMetricsColours.Baseline))
    }

    @Test
    fun `show picks which lines are drawn`() {
        val ui = open { Screen(show = setOf(TextGuide.Baseline, TextGuide.CapHeight)) }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(calls.lines(TextMetricsColours.Baseline).isNotEmpty())
        assertTrue(calls.lines(TextMetricsColours.CapHeight).isNotEmpty())
        assertTrue(calls.boxes(TextMetricsColours.LineBox).isEmpty())
        assertTrue(calls.lines(TextMetricsColours.Ascent).isEmpty())
        assertTrue(calls.lines(TextMetricsColours.Descent).isEmpty())
    }

    @Test
    fun `the lines follow the text when it changes size`() {
        val ui = open {
            var big by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("grow", onClick = { big = true }, modifier = Modifier.testTag("grow"))
                Text("HP", Modifier.offset(100f, 50f), textStyle = TextStyle(size = if (big) 40f else 20f))
                TextMetricsOverlay(true, setOf(TextGuide.Baseline))
            }
        }
        assertTrue(line(100f, 124f, 66f) in drawn(ui).lines(TextMetricsColours.Baseline))

        ui.click("grow")

        val baselines = drawn(ui).lines(TextMetricsColours.Baseline)
        assertTrue(line(100f, 148f, 82f) in baselines, "twice the size, twice as far down: $baselines")
        assertFalse(line(100f, 124f, 66f) in baselines)
    }

    @Test
    fun `turning it on moves nothing and a click still reaches the button under it`() {
        var clicks = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                Button("count", onClick = { clicks++ }, modifier = Modifier.offset(0f, 100f).testTag("count"))
                TextMetricsOverlay(debug)
            }
        }
        val before = ui.node("count").boundsInRoot

        ui.click("toggle")
        ui.click("count")

        assertEquals(before, ui.node("count").boundsInRoot)
        assertEquals(1, clicks)
        assertEquals(0f, ui.root.firstOrNull { it.name == TextOverlayName }!!.width)
    }

    @Test
    fun `a still screen with it on is not redrawn and recomposing it changes nothing`() {
        var tick by mutableStateOf(0)
        var recomposed = 0
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = true }, modifier = Modifier.testTag("toggle"))
                // A new set equal to the last every tick, the way a screen writing `setOf(...)` inline
                // does. Whether the call is skipped by value or runs and finds its remembered painter,
                // the node must hear nothing.
                val show = TextGuide.entries.filter { tick >= 0 && it != TextGuide.Ascent }.toSet()
                SideEffect { recomposed++ }
                TextMetricsOverlay(debug, show)
            }
        }
        ui.click("toggle")
        ui.render()

        assertFalse(ui.render(), "a still frame")
        val changed = ui.host.changedFrames
        repeat(3) {
            val before = recomposed
            tick++
            assertFalse(ui.render(), "recomposed with the same arguments on tick $tick")
            assertTrue(recomposed > before)
        }
        assertEquals(changed, ui.host.changedFrames)
        assertTrue(drawn(ui).lines(TextMetricsColours.Baseline).isNotEmpty(), "and it is still drawing")
    }

    @Test
    fun `faded out text is left out`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Text("SEEN", Modifier.offset(0f, 0f), textStyle = twenty)
                Box(Modifier.offset(0f, 100f).alpha(0f)) {
                    Text("GONE", textStyle = twenty)
                }
                TextMetricsOverlay(true, setOf(TextGuide.Baseline))
            }
        }

        assertEquals(listOf(line(0f, 48f, 16f)), drawn(ui).lines(TextMetricsColours.Baseline))
    }

    @Test
    fun `scaled text shows its lines where it is drawn`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Text("HP", Modifier.offset(100f, 100f).scale(2f).testTag("scaled"), textStyle = twenty)
                TextMetricsOverlay(true, setOf(TextGuide.Baseline, TextGuide.LineBox))
            }
        }
        ui.render()
        val node = ui.node("scaled")
        val where = node.boundsInRoot
        assertEquals(Rect(88f, 87.5f, 136f, 137.5f), where, "scaled about its centre")
        val calls = drawn(ui)

        assertEquals(listOf(where), calls.boxes(TextMetricsColours.LineBox))
        assertEquals(listOf(Rect(88f, 87.5f + 32f, 136f, 87.5f + 33f)), calls.lines(TextMetricsColours.Baseline))
    }

    @Test
    fun `a text field shows the lines of what is typed into it and of its hint when empty`() {
        val ui = open {
            var name by remember { mutableStateOf("") }
            Box(Modifier.fillMaxSize()) {
                TextField(name, onValueChange = { name = it }, placeholder = "NAME", modifier = Modifier.offset(40f, 60f).width(200f).testTag("name"))
                TextMetricsOverlay(true, setOf(TextGuide.LineBox, TextGuide.Baseline))
            }
        }
        val field = ui.root.firstOrNull { it.name == "field" }!!
        val box = field.layoutBoundsInRoot
        val baseline = box.top + field.firstBaseline

        // "NAME" is the hint: four characters of the default face.
        val hintWidth = ui.backend.fonts.measure("NAME", TextStyle.Default).size.width
        assertEquals(listOf(line(box.left, box.left + hintWidth, baseline)), drawn(ui).lines(TextMetricsColours.Baseline), "the hint's")

        ui.click("name")
        ui.type("Ada")

        val typed = ui.backend.fonts.measure("Ada", TextStyle.Default).size.width
        val calls = drawn(ui)
        assertEquals(listOf(line(box.left, box.left + typed, baseline)), calls.lines(TextMetricsColours.Baseline), "the words'")
        assertEquals(1, calls.boxes(TextMetricsColours.LineBox).size)
    }

    @Test
    fun `a field typed past its edge keeps its lines inside its box`() {
        val ui = open {
            var name by remember { mutableStateOf("") }
            Box(Modifier.fillMaxSize()) {
                TextField(name, onValueChange = { name = it }, modifier = Modifier.offset(40f, 60f).width(80f).testTag("name"))
                TextMetricsOverlay(true, setOf(TextGuide.Baseline))
            }
        }
        ui.click("name")
        ui.type("A VERY LONG NAME INDEED")

        val box = ui.root.firstOrNull { it.name == "field" }!!.layoutBoundsInRoot
        val calls = drawn(ui)
        val baselines = calls.lines(TextMetricsColours.Baseline)
        assertEquals(1, baselines.size)
        assertEquals(box.left, baselines.single().left, "scrolled, so it starts off the left edge and is cut there")
        // The words stop a caret's width short of the edge, scrolled to keep the caret in view.
        val words = calls.filterIsInstance<DrawCall.Text>().single { it.text == "A VERY LONG NAME INDEED" }
        assertTrue(words.at.x < box.left, "the words really have scrolled: ${words.at} in $box")
        val end = words.at.x + ui.backend.fonts.measure(words.text, TextStyle.Default).size.width
        assertTrue(end < box.right, "short of the edge: $end in $box")
        assertEquals(end, baselines.single().right, "and the line runs to where the words end")
    }

    @Test
    fun `a typewriter shows every line it will type where the letters land`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                val line = rememberTypewriter("AB CD EF")
                // Room for five characters: "AB CD", then "EF".
                Typewriter(line, Modifier.offset(40f, 30f).width(60f), textStyle = twenty, charactersPerSecond = 400f, pauses = false)
                TextMetricsOverlay(true, setOf(TextGuide.LineBox, TextGuide.Baseline))
            }
        }
        ui.advanceBy(200)
        val calls = drawn(ui)

        assertEquals(listOf(Rect(40f, 30f, 100f, 55f), Rect(40f, 55f, 64f, 80f)), calls.boxes(TextMetricsColours.LineBox))
        assertEquals(listOf(line(40f, 100f, 46f), line(40f, 64f, 71f)), calls.lines(TextMetricsColours.Baseline))
        // Under the words actually drawn: each run starts where its line box does.
        val runs = calls.filterIsInstance<DrawCall.Text>().filter { it.text == "AB CD" || it.text == "EF" }
        assertEquals(listOf(40f to 30f, 40f to 55f), runs.map { it.at.x to it.at.y })
    }

    @Test
    fun `a tooltip's words are marked while it is up and not before`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                TooltipHost(delayMillis = 100, fadeMillis = 20) {
                    Box(Modifier.fillMaxSize()) {
                        Tooltip("tip", Modifier.offset(100f, 100f)) { Box(Modifier.size(40f).testTag("icon")) }
                    }
                }
                TextMetricsOverlay(true, setOf(TextGuide.LineBox, TextGuide.Baseline))
            }
        }
        assertTrue(drawn(ui).boxes(TextMetricsColours.LineBox).isEmpty(), "nothing is up yet")

        ui.moveTo("icon")
        ui.advanceBy(300)

        val calls = drawn(ui)
        val words = calls.filterIsInstance<DrawCall.Text>().single { it.text == "tip" }
        val box = calls.boxes(TextMetricsColours.LineBox).single()
        val baseline = calls.lines(TextMetricsColours.Baseline).single()
        assertEquals(words.at.x, box.left, "the box starts where the words are drawn: $box and ${words.at}")
        assertEquals(words.at.y, box.top)
        assertTrue(box.top > 140f, "below the icon: $box")
        assertEquals(box.left, baseline.left)
        assertEquals(box.right, baseline.right)
        assertTrue(baseline.top > box.top && baseline.top < box.bottom, "the words stand inside their line: $baseline in $box")
    }

    @Test
    fun `a prompt glyph's letter stands on the same green line as the sentence around it`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.offset(20f, 40f), verticalAlignment = VerticalAlignment.Centre) {
                    Text("Press", Modifier.testTag("press"))
                    PromptGlyph(Action.Confirm, Modifier.testTag("prompt"))
                }
                TextMetricsOverlay(true, setOf(TextGuide.Baseline))
            }
        }
        val calls = drawn(ui)
        val letter = calls.filterIsInstance<DrawCall.Text>().single { it.text == "E" }
        val prompt = ui.node("prompt").layoutBoundsInRoot
        val press = ui.node("press")

        val baselines = calls.lines(TextMetricsColours.Baseline)
        assertEquals(2, baselines.size, "one for the word and one for the letter: $baselines")
        val onLetter = baselines.single { it.left >= prompt.left }
        assertEquals(letter.at.x, onLetter.left, "under the letter, not across the key: $onLetter at ${letter.at}")
        assertTrue(onLetter.right > onLetter.left && onLetter.right < prompt.right)
        assertEquals(press.layoutBoundsInRoot.top + press.firstBaseline, onLetter.top, "on the sentence's baseline")
    }

    @Test
    fun `with the layout overlay on too neither marks the other`() {
        val ui = open {
            var debug by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                Button("debug", onClick = { debug = !debug }, modifier = Modifier.testTag("toggle"))
                LayoutOverlay(debug, setOf(Show.Bounds))
                TextMetricsOverlay(debug, setOf(TextGuide.LineBox))
            }
        }
        ui.click("toggle")
        val calls = drawn(ui)

        assertTrue(calls.lines(LayoutOverlayColours.Bounds).isEmpty(), "no dot for the text overlay: ${calls.lines(LayoutOverlayColours.Bounds)}")
        assertEquals(1, calls.boxes(TextMetricsColours.LineBox).size, "one line box: the button's label")
        assertTrue(calls.boxes(LayoutOverlayColours.Bounds).isNotEmpty(), "and the layout is still outlined")
    }

    private companion object {
        val OverlayInks = setOf(
            TextMetricsColours.LineBox,
            TextMetricsColours.Ascent,
            TextMetricsColours.CapHeight,
            TextMetricsColours.Baseline,
            TextMetricsColours.Descent,
        )
    }
}
