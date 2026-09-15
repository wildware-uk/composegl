package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.skin.StateStyle
import dev.wildware.composegl.ui.skin.Style
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line between two sections, composed on a real screen and judged by where it landed and what
 * it drew.
 */
class DividerTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** What [tag] drew, and nothing else. */
    private fun UiTest.drawn(tag: String): List<DrawCall> {
        val node = node(tag)
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.calls
    }

    private val red = Colour.rgb(0xFF0000)

    private fun redDividers() = Skin(
        styles = mapOf("divider" to Style(StateStyle(background = SkinDrawable.Fill(red)))),
    )

    @Test
    fun `a horizontal divider spans its column and is one pixel thick between the two sections`() {
        val ui = open {
            Column(Modifier.width(200f)) {
                Box(Modifier.size(50f, 20f).testTag("above"))
                Divider(Modifier.testTag("line"))
                Box(Modifier.size(50f, 20f).testTag("below"))
            }
        }

        assertEquals(Rect.of(0f, 20f, 200f, 1f), ui.node("line").boundsInRoot)
        assertEquals(21f, ui.node("below").boundsInRoot.top, "the section below starts under the line")
    }

    @Test
    fun `a vertical divider is as tall as its row and pushes the next thing along by its thickness`() {
        val ui = open {
            Row(Modifier.height(40f)) {
                Box(Modifier.size(30f, 10f))
                Divider(Modifier.testTag("line"), vertical = true, thickness = 2f)
                Box(Modifier.size(30f, 10f).testTag("after"))
            }
        }

        assertEquals(Rect.of(30f, 0f, 2f, 40f), ui.node("line").boundsInRoot)
        assertEquals(32f, ui.node("after").boundsInRoot.left)
    }

    @Test
    fun `thickness sets how thick the line is and nothing about how long`() {
        val ui = open {
            Column(Modifier.width(120f)) { Divider(Modifier.testTag("line"), thickness = 4f) }
        }

        assertEquals(Size(120f, 4f), ui.node("line").boundsInRoot.size)
    }

    @Test
    fun `a size modifier makes a short rule rather than a full one`() {
        val ui = open {
            Column(Modifier.width(300f)) { Divider(Modifier.width(80f).testTag("line")) }
        }

        assertEquals(80f, ui.node("line").boundsInRoot.width)
    }

    @Test
    fun `a vertical divider in a row nobody sized takes all the height the row may have`() {
        val ui = open {
            Row(Modifier.testTag("row")) {
                Box(Modifier.size(30f, 10f))
                Divider(Modifier.testTag("line"), vertical = true)
            }
        }

        assertEquals(300f, ui.node("line").boundsInRoot.height, "the screen is the only limit")
        assertEquals(300f, ui.node("row").boundsInRoot.height, "and the row grows to hold it")
    }

    @Test
    fun `in a row sized to its contents a vertical divider is as tall as the tallest thing beside it`() {
        val ui = open {
            Row(Modifier.height(IntrinsicSize.Min).testTag("row")) {
                Box(Modifier.size(30f, 24f))
                Divider(Modifier.testTag("line"), vertical = true, thickness = 2f)
                Box(Modifier.size(30f, 40f))
            }
        }

        assertEquals(40f, ui.node("row").boundsInRoot.height, "the line does not stretch the row to the screen")
        assertEquals(Rect.of(30f, 0f, 2f, 40f), ui.node("line").boundsInRoot)
    }

    @Test
    fun `in a menu as wide as its widest button a divider spans that width and no more`() {
        val ui = open {
            Column(Modifier.width(IntrinsicSize.Max).testTag("menu")) {
                Box(Modifier.size(60f, 20f))
                Divider(Modifier.testTag("line"))
                Box(Modifier.size(140f, 20f))
            }
        }

        assertEquals(140f, ui.node("menu").boundsInRoot.width, "the line does not widen the menu")
        assertEquals(Rect.of(0f, 20f, 140f, 1f), ui.node("line").boundsInRoot)
    }

    @Test
    fun `where there is no limit on height a vertical divider is zero tall until it is given one`() {
        val ui = open {
            ScrollArea(Modifier.size(200f, 100f)) {
                Row {
                    Box(Modifier.size(30f, 10f))
                    Divider(Modifier.testTag("unsized"), vertical = true)
                    Divider(Modifier.height(16f).testTag("sized"), vertical = true)
                }
            }
        }

        assertEquals(0f, ui.node("unsized").boundsInRoot.height)
        assertEquals(16f, ui.node("sized").boundsInRoot.height)
    }

    @Test
    fun `it draws the default skin's divider colour across the whole line`() {
        val ui = open {
            Column(Modifier.width(200f)) {
                Box(Modifier.size(10f, 20f))
                Divider(Modifier.testTag("line"))
            }
        }

        val expected = (Skin.Default.resolve("divider").background as SkinDrawable.Fill).colour
        val rects = ui.drawn("line").filterIsInstance<DrawCall.Rectangle>()

        assertEquals(1, rects.size, "one line: ${ui.drawn("line")}")
        assertEquals(expected, rects.single().colour)
        assertEquals(Rect.of(0f, 20f, 200f, 1f), rects.single().rect)
    }

    @Test
    fun `the colour comes from the skin so overriding the style recolours every divider`() {
        val ui = open {
            SkinOverride(redDividers()) {
                Column(Modifier.width(100f)) {
                    Divider(Modifier.testTag("one"))
                    Divider(Modifier.testTag("two"), vertical = true, thickness = 3f)
                }
            }
        }

        for (tag in listOf("one", "two")) {
            val rect = ui.drawn(tag).filterIsInstance<DrawCall.Rectangle>().single()
            assertEquals(red, rect.colour, "#$tag")
        }
    }

    @Test
    fun `a variant name falls back to the divider style`() {
        val ui = open {
            SkinOverride(redDividers()) {
                Column(Modifier.width(100f)) { Divider(Modifier.testTag("line"), style = "divider.strong") }
            }
        }

        assertEquals(red, ui.drawn("line").filterIsInstance<DrawCall.Rectangle>().single().colour)
    }

    @Test
    fun `a style with no background draws nothing`() {
        val ui = open {
            SkinOverride(Skin(styles = mapOf("divider" to Style(StateStyle(background = SkinDrawable.Blank))))) {
                Column(Modifier.width(100f)) { Divider(Modifier.testTag("line")) }
            }
        }

        assertEquals(emptyList(), ui.drawn("line"))
    }

    @Test
    fun `the pad steps straight over a divider from one button to the next`() {
        val ui = open {
            Column(Modifier.width(200f)) {
                Button("AUDIO", onClick = {}, initialFocus = true, modifier = Modifier.testTag("audio"))
                Divider(Modifier.testTag("line"))
                Button("VIDEO", onClick = {}, modifier = Modifier.testTag("video"))
            }
        }
        ui.assertFocused("audio")

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("video")

        ui.key(Key.Up)
        ui.assertFocused("audio")
    }

    @Test
    fun `a click on the line is a click on nothing`() {
        var clicks = 0
        val ui = open {
            Column(Modifier.width(200f)) {
                Divider(Modifier.testTag("line"), thickness = 6f)
                Button("GO", onClick = { clicks++ }, modifier = Modifier.testTag("go"))
            }
        }

        assertFalse(ui.click("line"), "nothing took a press on the divider")
        assertEquals(0, clicks)

        ui.click("go")
        assertEquals(1, clicks)
    }

    @Test
    fun `turning a divider vertical on a live screen lays it out again`() {
        var vertical by mutableStateOf(false)
        val ui = open {
            Row(Modifier.height(50f)) {
                Column(Modifier.width(80f)) { Divider(Modifier.testTag("line"), vertical = vertical) }
            }
        }
        assertEquals(Size(80f, 1f), ui.node("line").boundsInRoot.size)

        vertical = true
        ui.settle()

        assertEquals(Size(1f, 50f), ui.node("line").boundsInRoot.size)
        assertTrue(ui.drawn("line").isNotEmpty())
    }

    @Test
    fun `running again with nothing new to show leaves a still screen still`() {
        var style by mutableStateOf("divider")
        val ui = open {
            Column(Modifier.width(200f)) { Divider(Modifier.testTag("line"), thickness = 2f, style = style) }
        }
        ui.render()

        // A variant with nothing of its own resolves to the same look, so the divider really runs
        // again — equal arguments alone would be skipped — and has nothing new to show.
        style = "divider.strong"

        // A policy or a draw made fresh on every recomposition would make each one a change, and
        // a menu that recomposes for a clock would redraw every frame for a line that never moved.
        assertFalse(ui.render(), "an equal divider is no change")
    }

    @Test
    fun `a divider that leaves the screen takes nothing with it`() {
        var shown by mutableStateOf(true)
        val ui = open {
            Column(Modifier.width(200f)) {
                if (shown) Divider(Modifier.testTag("line"), thickness = 5f)
                Box(Modifier.size(50f, 20f).testTag("below"))
            }
        }
        assertEquals(5f, ui.node("below").boundsInRoot.top)

        shown = false
        ui.settle()

        ui.assertDoesNotExist("line")
        assertEquals(0f, ui.node("below").boundsInRoot.top, "the section below moves up into its place")
    }

    @Test
    fun `a zero thickness divider takes no room and draws nothing`() {
        val ui = open {
            Column(Modifier.width(200f)) {
                Divider(Modifier.testTag("line"), thickness = 0f)
                Box(Modifier.size(50f, 20f).testTag("below"))
            }
        }

        assertEquals(0f, ui.node("line").boundsInRoot.height)
        assertEquals(0f, ui.node("below").boundsInRoot.top)
        assertEquals(emptyList(), ui.drawn("line"))
    }

    @Test
    fun `a negative thickness is refused where it is written`() {
        assertFailsWith<IllegalArgumentException> {
            open { Divider(thickness = -1f) }
        }
    }
}
