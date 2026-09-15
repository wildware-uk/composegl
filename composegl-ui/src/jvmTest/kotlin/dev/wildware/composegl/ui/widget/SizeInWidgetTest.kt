package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.defaultMinSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.heightIn
import dev.wildware.composegl.ui.modifier.sizeIn
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two cases the issue opened with, on a real screen driven the way a player drives it: a panel
 * that grows with its text and stops, and a button that is never narrower than a thumb.
 */
class SizeInWidgetTest {

    private val headless = HeadlessBackend()

    private fun screen(width: Float = 400f, content: @androidx.compose.runtime.Composable () -> Unit): UiTest =
        uiTest(Size(width, 400f), headless, content = content)

    /** Draws a frame and hands back the one rectangle painted in [Marker]. */
    private fun UiTest.painted(): DrawCall.Rectangle {
        headless.canvas.clear()
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == Marker }
    }

    @Test
    fun `a panel grows with its text as the player adds to it and stops at its maximum`() {
        var message by mutableStateOf("hi")
        screen {
            Column {
                Button("MORE", onClick = { message += " and a good deal more" }, modifier = Modifier.testTag("more"))
                Box(Modifier.widthIn(min = 120f, max = 240f).background(Marker).testTag("panel")) { Text(message) }
            }
        }.use { ui ->
            val oneLine = ui.node("panel").height

            assertEquals(120f, ui.node("panel").width, "short text is lifted to the minimum")
            assertEquals(120f, ui.painted().rect.width, "and the background is drawn that wide too")

            ui.click("more")
            val grown = ui.node("panel").width
            assertTrue(grown > 120f && grown < 240f, "a few more words widen it, was $grown")

            repeat(4) { ui.click("more") }
            // Wrapped text breaks at a word, so it settles a little inside the maximum rather than
            // on it. Without the range it would have run on across the whole 400 of the screen.
            val stopped = ui.node("panel").width
            assertTrue(stopped > 200f && stopped <= 240f, "and it stops at the maximum, was $stopped")
            assertEquals(stopped, ui.painted().rect.width)
            assertTrue(ui.node("panel").height > oneLine, "because the text wrapped rather than running on")
        }
    }

    @Test
    fun `a one-letter button is still as wide as its default minimum and all of it is clickable`() {
        var clicks = 0
        screen { TouchButton("A", Modifier) { clicks++ } }.use { ui ->
            assertEquals(96f, ui.node("touch").width)
            assertEquals(48f, ui.node("touch").height)

            ui.click(Offset(90f, 44f))
            assertEquals(1, clicks, "the corner a label alone would never have reached still clicks")
        }
    }

    @Test
    fun `the screen using a widget can still overrule its default minimum`() {
        var clicks = 0
        screen { TouchButton("A", Modifier.width(30f)) { clicks++ } }.use { ui ->
            assertEquals(30f, ui.node("touch").width)

            ui.click(Offset(60f, 10f))
            assertEquals(0, clicks, "nothing is clickable past the width the screen asked for")
        }
    }

    @Test
    fun `a list that grows from the keyboard stays inside its height range`() {
        var rows by mutableStateOf(1)
        screen {
            Column {
                Button("ADD", onClick = { rows++ }, initialFocus = true)
                Box(Modifier.heightIn(min = 40f, max = 100f).background(Marker).testTag("list")) {
                    Column { repeat(rows) { Text("row $it") } }
                }
            }
        }.use { ui ->
            assertEquals(40f, ui.node("list").height, "one row is lifted to the minimum")

            repeat(2) { ui.key(Key.Enter) }
            val three = ui.node("list").height
            assertTrue(three > 40f && three < 100f, "a few rows make it taller, was $three")

            repeat(6) { ui.key(Key.Enter) }
            assertEquals(100f, ui.node("list").height, "and it stops at the maximum")
            assertEquals(100f, ui.painted().rect.height)
        }
    }

    @Test
    fun `a panel filling its space up to a maximum follows that space down but not up`() {
        var room by mutableStateOf(600f)
        screen(width = 1000f) {
            Column {
                Button("WIDE", onClick = { room = 900f }, modifier = Modifier.testTag("wide"))
                Button("NARROW", onClick = { room = 200f }, modifier = Modifier.testTag("narrow"))
                Box(Modifier.width(room)) {
                    Box(Modifier.widthIn(max = 300f).fillMaxWidth().background(Marker).testTag("panel")) {
                        Text("status")
                    }
                }
            }
        }.use { ui ->
            assertEquals(300f, ui.node("panel").width, "plenty of room gives it the maximum")

            ui.click("wide")
            assertEquals(300f, ui.node("panel").width, "more room gives it no more")

            ui.click("narrow")
            assertEquals(200f, ui.node("panel").width, "a little room gives it all there is")
            assertEquals(200f, ui.painted().rect.width)
        }
    }

    @Test
    fun `a range the player changes is laid out and drawn again`() {
        var roomy by mutableStateOf(false)
        screen {
            Column {
                Button("ROOMY", onClick = { roomy = true }, modifier = Modifier.testTag("roomy"))
                Box(Modifier.widthIn(max = if (roomy) 300f else 150f).background(Marker).testTag("panel")) {
                    Text("a long line of text that wants a lot more room than either")
                }
            }
        }.use { ui ->
            // Wrapped at a word, so a little inside each maximum rather than on it.
            val narrow = ui.node("panel").width
            assertTrue(narrow > 120f && narrow <= 150f, "held to the first maximum, was $narrow")

            ui.click("roomy")
            val wide = ui.node("panel").width
            assertTrue(wide > 250f && wide <= 300f, "the new maximum was read, not the old one kept, was $wide")
            assertEquals(wide, ui.painted().rect.width)
        }
    }

    @Test
    fun `recomposing with the same range costs a still screen nothing`() {
        var ticks by mutableStateOf(0)
        screen {
            // Read here so this scope runs again and hands the panel freshly built ranges.
            ticks.let {
                Box(
                    Modifier.sizeIn(minWidth = 64f, maxWidth = 200f).heightIn(max = 80f)
                        .defaultMinSize(minHeight = 40f).background(Marker).testTag("panel"),
                ) { Text("still") }
            }
        }.use { ui ->
            ui.render()
            assertFalse(ui.render(), "nothing moved")

            ticks++
            assertFalse(ui.render(), "the recomposition wrote equal ranges, so nothing needs laying out or drawing")
        }
    }

    @Test
    fun `an empty panel is lifted to its minimum and takes clicks across all of it`() {
        var clicks = 0
        screen {
            Box(Modifier.sizeIn(minWidth = 64f, minHeight = 64f).clickable { clicks++ }.testTag("empty"))
        }.use { ui ->
            assertEquals(64f, ui.node("empty").width)
            assertEquals(64f, ui.node("empty").height)

            ui.click("empty")
            ui.click(Offset(60f, 60f))
            assertEquals(2, clicks)
        }
    }

    @Test
    fun `a panel with a maximum of zero takes up nothing and takes no clicks`() {
        var clicks = 0
        screen {
            Column {
                Box(Modifier.widthIn(max = 0f).clickable { clicks++ }.testTag("gone")) { Text("hidden") }
                Text("after", Modifier.testTag("after"))
            }
        }.use { ui ->
            assertEquals(0f, ui.node("gone").width)

            ui.click(Offset(5f, 5f))
            assertEquals(0, clicks)
        }
    }

    @Test
    fun `a range inside a narrower range stays inside the outer one`() {
        screen {
            Box(Modifier.widthIn(max = 200f).testTag("outer")) {
                Box(Modifier.widthIn(min = 300f).background(Marker).testTag("inner"))
            }
        }.use { ui ->
            assertEquals(200f, ui.node("inner").width, "a minimum cannot push past what its parent allows")
            assertEquals(200f, ui.node("outer").width)
            assertEquals(200f, ui.painted().rect.width)
        }
    }

    /** What a widget with a touch target looks like: a minimum on itself, the caller's modifier first. */
    @androidx.compose.runtime.Composable
    private fun TouchButton(label: String, modifier: Modifier, onClick: () -> Unit) =
        Button(
            label,
            onClick = onClick,
            modifier = modifier.defaultMinSize(minWidth = 96f, minHeight = 48f).testTag("touch"),
        )

    private companion object {
        val Marker = Colour.rgb(0xABCDEF)
    }
}
