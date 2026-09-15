package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.TextDecoration
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A label with parts of it styled differently, and a pointer that can find those parts.
 *
 * The point of the whole thing is that the toolkit keeps ownership of measuring and breaking: the
 * application says which characters are the term and nothing else. So the tests are about what
 * reaches the canvas — the right characters, in the right colours, with a line under the right ones
 * — and about a term being hoverable and clickable without the sentence being split into one node
 * per word.
 *
 * Monospace at size 10: six pixels a character, 12.5 a line.
 */
class TextRunsTest {

    private val host = UiHost()
    private val fonts = MonospaceFontProvider()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)

    private val style = TextStyle(size = 10f)
    private val orange = Colour.rgb(0xE08A3C)

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(fonts) { content() } }
        frame()
        frame()
    }

    private fun frame() {
        canvas.clear()
        host.frame(0L)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
    }

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun rectangles() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    private fun move(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))

    private fun press(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun release(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    // --- what gets drawn --------------------------------------------------------------------------

    @Test
    fun `a run is drawn in its own colour and the rest in the label's`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), colour = orange)),
            )
        }

        val drawn = texts()
        assertEquals(listOf("one ", "two", " three"), drawn.map { it.text })
        assertEquals(listOf(Colour.White, orange, Colour.White), drawn.map { it.colour })
    }

    @Test
    fun `the pieces are drawn end to end where the words are`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), colour = orange)),
            )
        }

        val drawn = texts()
        assertEquals(0f, drawn[0].at.x, 0.001f)
        assertEquals(24f, drawn[1].at.x, 0.001f, "four characters in")
        assertEquals(42f, drawn[2].at.x, 0.001f, "seven characters in")
        assertTrue(drawn.all { it.at.y == drawn[0].at.y }, "one line, one y")
    }

    @Test
    fun `no runs draws the whole string in one piece`() {
        show { Text("one two three", textStyle = style, colour = Colour.White, runs = emptyList()) }

        assertEquals(listOf("one two three"), texts().map { it.text })
    }

    @Test
    fun `an underline is drawn below the baseline across the run and nothing else`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), decoration = TextDecoration.Underline)),
            )
        }

        val lines = rectangles()
        assertEquals(1, lines.size, "one decoration, not one per character")
        val line = lines.single().rect
        assertEquals(24f, line.left, 0.001f, "it starts where the run starts")
        assertEquals(42f, line.right, 0.001f, "and ends where the run ends")
        val metrics = fonts.metrics(style)
        assertTrue(line.top > metrics.ascent, "under the baseline, not through the words")
    }

    @Test
    fun `a strike is drawn through the capitals rather than under them`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), decoration = TextDecoration.Strike)),
            )
        }

        val metrics = fonts.metrics(style)
        val line = rectangles().single().rect
        assertTrue(line.top < metrics.ascent, "above the baseline: ${line.top}")
        assertTrue(line.top > metrics.ascent - metrics.capHeight, "and below the cap top")
    }

    @Test
    fun `a decoration takes the run's colour`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(
                    TextRun(TextRange(4, 7), colour = orange, decoration = TextDecoration.Underline),
                ),
            )
        }

        assertEquals(orange, rectangles().single().colour)
    }

    @Test
    fun `a run that wrapped is underlined on both lines`() {
        show {
            Text(
                "aaa bbb ccc",
                modifier = Modifier.size(width = 42f, height = 100f),
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 11), decoration = TextDecoration.Underline)),
            )
        }

        val lines = rectangles()
        assertEquals(2, lines.size, "one per line the run touches: $lines")
        assertTrue(lines[1].rect.top > lines[0].rect.top, "the second one is lower down")
    }

    @Test
    fun `the later of two overlapping runs wins`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(
                    TextRun(TextRange(0, 13), colour = Colour.Grey),
                    TextRun(TextRange(4, 7), colour = orange),
                ),
            )
        }

        assertEquals(listOf(Colour.Grey, orange, Colour.Grey), texts().map { it.colour })
    }

    @Test
    fun `centring centres each line rather than leaving them ragged`() {
        show {
            Text(
                "aaa bbbbb",
                modifier = Modifier.size(width = 30f, height = 100f),
                textStyle = style,
                align = HorizontalAlignment.Centre,
                colour = Colour.White,
                runs = emptyList(),
            )
        }

        val drawn = texts()
        assertEquals(2, drawn.size)
        assertEquals(6f, drawn[0].at.x, 0.001f, "the short line is inset inside the block")
        assertEquals(0f, drawn[1].at.x, 0.001f)
    }

    // --- what the pointer finds -------------------------------------------------------------------

    @Test
    fun `hovering a term reports it and leaving reports nothing`() {
        val seen = mutableListOf<Any?>()
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunHover = { seen += it?.tag },
            )
        }

        move(30f, 5f)
        move(60f, 5f)

        assertEquals(listOf("two", null), seen)
    }

    @Test
    fun `hovering the same term again says nothing`() {
        var told = 0
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunHover = { told++ },
            )
        }

        move(28f, 5f)
        move(30f, 5f)
        move(38f, 5f)

        assertEquals(1, told, "told when it changed, not once a frame")
    }

    @Test
    fun `a pointer that leaves the label altogether stops reporting the term`() {
        // The router delivers a move to what is under the pointer and to nothing else, so a label
        // the pointer has left hears nothing. Without the node saying it is no longer hovered, a
        // term would stay lit for as long as the screen was up.
        val seen = mutableListOf<Any?>()
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunHover = { seen += it?.tag },
            )
        }

        move(30f, 5f)
        move(300f, 300f)
        frame()

        assertEquals(listOf("two", null), seen)
    }

    @Test
    fun `pressing and releasing on a term follows it`() {
        val clicked = mutableListOf<Any?>()
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunClick = { clicked += it.tag },
            )
        }

        press(30f, 5f)
        release(30f, 5f)

        assertEquals(listOf("two"), clicked)
    }

    @Test
    fun `pressing a term and sliding off it does not follow it`() {
        val clicked = mutableListOf<Any?>()
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunClick = { clicked += it.tag },
            )
        }

        press(30f, 5f)
        release(60f, 5f)

        assertTrue(clicked.isEmpty(), "a press that ended somewhere else is not a click")
    }

    @Test
    fun `the plain part of the sentence reports nothing`() {
        val seen = mutableListOf<TextRun?>()
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunHover = { seen += it },
            )
        }

        // Starting on plain text says nothing at all — there was nothing to leave. Crossing the
        // term and coming out the other side is what has to report the term and then the nothing.
        move(6f, 5f)
        assertTrue(seen.isEmpty(), "nothing to report before anything was hovered")

        move(30f, 5f)
        move(60f, 5f)

        assertEquals(2, seen.size)
        assertNull(seen[1], "leaving the term reports nothing rather than staying on it")
    }

    @Test
    fun `padding does not move the term out from under the pointer`() {
        // The pointer is given a point in the whole widget; the text starts a padding in from it.
        // Getting this wrong makes every term hittable a padding to the left of where it is drawn.
        val seen = mutableListOf<Any?>()
        show {
            Text(
                "one two three",
                modifier = Modifier.padding(20f),
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunHover = { seen += it?.tag },
            )
        }

        move(50f, 25f)

        assertEquals(listOf("two"), seen)
    }

    @Test
    fun `a sentence with a term in it is still one node`() {
        show {
            Text(
                "one two three",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(4, 7), tag = "two")),
                onRunClick = {},
            )
        }

        assertEquals(1, count(host.root, "text"), "not one node per word")
    }

    // --- what it says it painted -----------------------------------------------------------------

    @Test
    fun `what an underlined label says it painted covers the underline`() {
        show {
            Text(
                "one",
                textStyle = style,
                colour = Colour.White,
                runs = listOf(TextRun(TextRange(0, 3), decoration = TextDecoration.Underline)),
            )
        }

        // A decoration is drawn off the baseline rather than inside the glyphs, so it can reach
        // past them. Whatever it reaches, the readback has to cover, or a caller sizing a
        // background to the ink would clip the line off the bottom of it.
        val underline = rectangles().single().rect
        val painted = checkNotNull(find("text")?.paintedInRoot) { "nothing was painted" }
        assertTrue(painted.bottom >= underline.bottom, "$painted does not cover $underline")
        assertTrue(painted.right >= underline.right, "$painted does not cover $underline")
    }

    @Test
    fun `an undecorated label paints its glyphs and not its line box`() {
        show { Text("one", textStyle = style, colour = Colour.White, runs = emptyList()) }

        val metrics = fonts.metrics(style)
        val painted = checkNotNull(find("text")?.paintedInRoot) { "nothing was painted" }
        assertEquals(metrics.ascent + metrics.descent, painted.bottom, 0.001f)
        assertEquals(18f, painted.right, 0.001f, "three characters, not the width it was offered")
    }

    private fun find(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { find(name, it) }

    private fun count(node: UiNode, name: String): Int =
        (if (node.name == name) 1 else 0) + node.children.sumOf { count(it, name) }
}
