package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Labels holding Hebrew, composed for real and read back off the drawing.
 *
 * What a player sees is what the recording canvas was handed: each piece of text in the order it is
 * drawn, where it is drawn. The headless font gives every character the same width, so a label's
 * own box divided by its length is one character.
 */
class BidiTextUiTest {

    private val opened = mutableListOf<UiTest>()
    private val headless = HeadlessBackend()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), headless, content = content).also { opened += it }

    private fun UiTest.drawnTexts(): List<DrawCall.Text> {
        headless.canvas.clear()
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Text>()
    }

    @Test
    fun `a hebrew word in an english label is drawn in reading order`() {
        val ui = open { Text("Press שלום to start", Modifier.testTag("line")) }

        assertEquals(listOf("Press ", "םולש", " to start"), ui.texts("line"))
    }

    @Test
    fun `the pieces of a mixed label run left to right across it without gaps`() {
        val text = "Press שלום to start"
        val ui = open { Text(text, Modifier.testTag("line")) }
        val box = ui.node("line").boundsInRoot
        val character = box.width / text.length

        val calls = ui.drawnTexts()

        assertEquals(listOf("Press ", "םולש", " to start"), calls.map { it.text })
        listOf(0f, 6f, 10f).forEachIndexed { index, characters ->
            assertEquals(box.left + characters * character, calls[index].at.x, 0.01f, "piece $index")
        }
    }

    @Test
    fun `an english label is still one piece handed to the backend whole`() {
        val ui = open { Text("Press start", Modifier.testTag("line")) }

        assertEquals(listOf("Press start"), ui.texts("line"))
    }

    @Test
    fun `a hebrew sentence reads from the right with its full stop at the far left`() {
        val ui = open { Text("שלום world.", Modifier.testTag("line")) }

        assertEquals(listOf(".", "world", " םולש"), ui.texts("line"))
    }

    @Test
    fun `digits in a right to left screen are drawn in the screen's order`() {
        val ui = open {
            Column {
                Text("12 - 5", Modifier.testTag("english"))
                ProvideLayoutDirection(LayoutDirection.Rtl) { Text("12 - 5", Modifier.testTag("hebrew")) }
            }
        }

        assertEquals(listOf("12 - 5"), ui.texts("english"))
        assertEquals("5 - 12", ui.texts("hebrew").joinToString(""))
    }

    @Test
    fun `a label in a right to left screen sits against the right of its slot`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Column(Modifier.width(300f)) {
                    Text("Hello", Modifier.width(300f).testTag("wide"))
                    Text("Hello", Modifier.testTag("natural"))
                }
            }
        }
        val natural = ui.node("natural").boundsInRoot

        assertTrue(natural.left > 200f, "the column puts its narrow child against the right: $natural")
        assertEquals(listOf(natural.left, natural.left), ui.drawnTexts().map { it.at.x }, "and the wide label draws its words there too")
    }

    @Test
    fun `end aligned text in a right to left screen goes to the left`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Text("Hello", Modifier.width(300f).testTag("wide"), align = HorizontalAlignment.End)
            }
        }

        assertEquals(ui.node("wide").boundsInRoot.left, ui.drawnTexts().single().at.x)
    }

    @Test
    fun `a coloured run over a hebrew word is that word drawn reversed on the right`() {
        val red = Colour.rgb(0xFF0000)
        val text = "abc שלום"
        val ui = open { Text(text, Modifier.testTag("line"), runs = listOf(TextRun(TextRange(4, 8), colour = red))) }
        val box = ui.node("line").boundsInRoot
        val character = box.width / text.length

        val coloured = ui.drawnTexts().single { it.colour == red }

        assertEquals("םולש", coloured.text)
        assertEquals(box.left + 4 * character, coloured.at.x)
    }

    @Test
    fun `hovering the hebrew word finds its run where it is drawn`() {
        val text = "abc שלום"
        val term = TextRun(TextRange(4, 8), tag = "shalom")
        var hovered: TextRun? = null
        val ui = open { Text(text, Modifier.testTag("line"), runs = listOf(term), onRunHover = { hovered = it }) }
        val box = ui.node("line").boundsInRoot
        val character = box.width / text.length

        ui.moveTo(Offset(box.left + 7.5f * character, box.centre.y))
        assertEquals(term, hovered, "the right-hand end of the label is the Hebrew")

        ui.moveTo(Offset(box.left + 1.5f * character, box.centre.y))
        assertNull(hovered)
    }

    @Test
    fun `dragging from the right across the first hebrew word selects it`() {
        val state = SelectionState()
        val text = "שלום עולם"
        val ui = open { SelectionContainer(state = state) { Text(text, Modifier.testTag("line")) } }
        val box = ui.node("line").boundsInRoot
        val character = box.width / text.length

        ui.press(Offset(box.right - 1f, box.centre.y))
        ui.moveTo(Offset(box.right - 4 * character, box.centre.y))
        ui.release()
        assertEquals("שלום", state.selectedText)

        ui.key(Key.Left, Modifiers.Shift)
        assertEquals("שלום ", state.selectedText, "shift and left carries on leftwards, into the space")

        ui.key(Key.C, Modifiers.Control)
        assertEquals("שלום ", headless.clipboard.read())
    }

    @Test
    fun `the highlight of a selected hebrew word is drawn over that word`() {
        val text = "שלום עולם"
        val ui = open { SelectionContainer { Text(text, Modifier.testTag("line")) } }
        val box = ui.node("line").boundsInRoot
        val character = box.width / text.length

        ui.press(Offset(box.right - 1f, box.centre.y))
        ui.moveTo(Offset(box.right - 4 * character, box.centre.y))
        ui.release()
        ui.drawnTexts()

        val highlight = headless.canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == Colour.rgb(0x3F6BD6) }
        assertEquals(box.right - 4 * character, highlight.rect.left, 0.01f)
        assertEquals(box.right, highlight.rect.right, 0.01f)
    }

    @Test
    fun `a still screen of mixed text draws nothing new`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Column {
                    Text("שלום world.", Modifier.testTag("line"))
                    Text("abc שלום", runs = listOf(TextRun(TextRange(4, 8), colour = Colour.rgb(0xFF0000))))
                }
            }
        }

        ui.render()

        assertFalse(ui.render(), "nothing moved, so nothing is drawn again")
    }
}
