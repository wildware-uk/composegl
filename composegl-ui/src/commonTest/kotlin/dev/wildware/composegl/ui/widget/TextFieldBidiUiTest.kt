package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Typing Hebrew into a field, and moving about in it, with real clicks, keys and characters.
 *
 * The caret is found in the drawing: it is the one rectangle a caret's width across.
 */
class TextFieldBidiUiTest {

    private val opened = mutableListOf<UiTest>()
    private val headless = HeadlessBackend()
    private var typed by mutableStateOf("")

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(direction: LayoutDirection = LayoutDirection.Ltr, placeholder: String? = null): UiTest {
        val content: @Composable () -> Unit = {
            ProvideLayoutDirection(direction) {
                TextField(
                    typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.width(240f).testTag("name"),
                    placeholder = placeholder,
                )
            }
        }
        return uiTest(Size(600f, 400f), headless, content = content).also { opened += it }
    }

    private fun UiTest.frame(): List<DrawCall> {
        headless.canvas.clear()
        render()
        return headless.canvas.calls
    }

    private fun List<DrawCall>.caretX(): Float =
        filterIsInstance<DrawCall.Rectangle>().single { abs(it.rect.width - 1.5f) < 0.01f }.rect.left

    private fun List<DrawCall>.textAt(text: String): Float =
        filterIsInstance<DrawCall.Text>().single { it.text == text }.at.x

    @Test
    fun `typed hebrew is drawn reversed and the english before it stays in order`() {
        val ui = open()
        ui.click("name")

        ui.type("abc שלום")

        assertEquals("abc שלום", typed)
        assertEquals(listOf("abc ", "םולש"), ui.texts("name"))
    }

    @Test
    fun `the arrows move the caret across hebrew the way they point`() {
        val ui = open()
        ui.click("name")
        ui.type("שלום")

        ui.key(Key.Left)
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.type("X")

        assertEquals("שלXום", typed, "left at the left end went nowhere, and two rights went back two letters")
    }

    @Test
    fun `the caret is drawn at the letter it is beside`() {
        val ui = open()
        ui.click("name")
        ui.type("שלום")

        val atEnd = ui.frame()
        val textLeft = atEnd.textAt("םולש")
        assertEquals(textLeft, atEnd.caretX(), 0.01f, "after the last letter typed is the left edge of the word")

        ui.key(Key.Right)
        val oneBack = ui.frame().caretX()
        repeat(3) { ui.key(Key.Right) }
        val atStart = ui.frame().caretX()

        assertTrue(atStart > textLeft + 10f, "the start of a Hebrew word is on its right")
        assertEquals((atStart - textLeft) / 4f, oneBack - textLeft, 0.01f, "one press is one letter along")
    }

    @Test
    fun `clicking the left end of a hebrew word puts the caret after its last letter`() {
        val ui = open()
        ui.click("name")
        ui.type("שלום")
        ui.key(Key.Home)

        val textLeft = ui.frame().textAt("םולש")
        ui.click(Offset(textLeft + 1f, ui.node("name").boundsInRoot.centre.y))
        ui.type("X")

        assertEquals("שלוםX", typed)
    }

    @Test
    fun `in a right to left screen the text sits against the right of the field`() {
        typed = "abc"
        val english = open()
        english.click("name")
        english.key(Key.End)
        val ltr = english.frame()
        val padding = ltr.textAt("abc") - english.node("name").boundsInRoot.left
        val width = ltr.caretX() - ltr.textAt("abc")
        english.close()
        opened.remove(english)

        val hebrew = open(LayoutDirection.Rtl)
        val right = hebrew.node("name").boundsInRoot.right

        assertEquals(right - padding - 1.5f - width, hebrew.frame().textAt("abc"), 0.01f)
    }

    @Test
    fun `a placeholder in a right to left screen is against the right`() {
        val english = open(placeholder = "Call sign")
        val ltrLeft = english.frame().textAt("Call sign")
        english.close()
        opened.remove(english)

        val hebrew = open(LayoutDirection.Rtl, placeholder = "Call sign")

        assertTrue(hebrew.frame().textAt("Call sign") > ltrLeft + 100f)
    }

    @Test
    fun `a still field with hebrew in it draws nothing new`() {
        typed = "שלום world"
        val ui = open(LayoutDirection.Rtl)

        ui.render()

        assertFalse(ui.render())
    }
}
