package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A field's hint follows its placeholder: a game that changes what an empty field asks for — a
 * search box whose tab changed — sees the new words on the next frame, not when someone types.
 */
class TextFieldPlaceholderUiTest {

    private val headless = HeadlessBackend()
    private var typed by mutableStateOf("")
    private var hint by mutableStateOf<String?>("Search items")
    private var ui: UiTest? = null

    @AfterTest
    fun tearDown() {
        ui?.close()
    }

    private fun open(): UiTest = uiTest(Size(600f, 400f), headless) {
        TextField(
            typed,
            onValueChange = { typed = it },
            modifier = Modifier.width(240f).testTag("search"),
            placeholder = hint,
        )
    }.also { ui = it }

    private fun UiTest.drawn(): List<String> {
        headless.canvas.clear()
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
    }

    @Test
    fun `an empty field draws its new hint on the frame the hint changes`() {
        val ui = open()
        assertEquals(listOf("Search items"), ui.drawn())

        hint = "Search quests"

        assertEquals(listOf("Search quests"), ui.drawn())
    }

    @Test
    fun `a hint changed while the field had text in it is the one shown once it is emptied`() {
        val ui = open()
        ui.click("search")
        ui.type("bow")
        assertEquals(listOf("bow"), ui.drawn())

        hint = "Search quests"
        typed = ""

        assertEquals(listOf("Search quests"), ui.drawn())
    }
}
