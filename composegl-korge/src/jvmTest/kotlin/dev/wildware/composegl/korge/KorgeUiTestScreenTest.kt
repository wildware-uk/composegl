package dev.wildware.composegl.korge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A realistic screen — a panel with a name field, two buttons and a scrolling list of saves — built
 * by `uiTest` with KorGE as its backend, played with the pointer and the keyboard, and judged both by
 * the tree's state and by the pixels KorGE drew.
 *
 * The toolkit has this test headless already. What this adds is that every frame of it goes through
 * [KorgeCanvas]: a list that scrolled in the tree but not on screen, or a typed name that is in the
 * state but never reached the atlas, fails here.
 */
class KorgeUiTestScreenTest {

    private val size = KorgeGl.size.toFloat()

    private fun backend() = KorgeBackend(
        KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16, 18, 22, 26)) },
    )

    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    /** The pixels inside [box], as one number each, so two frames of one area can be compared. */
    private fun Bitmap32.area(box: Rect): List<Int> {
        val rows = box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(height)
        val columns = box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(width)
        return rows.flatMap { y -> columns.map { x -> this[x, y].let { (it.r shl 16) or (it.g shl 8) or it.b } } }
    }

    private fun List<Int>.lit(): Int = count { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) > 240 }

    @Test
    fun `a player names a save, adds it, scrolls the list and deletes one, and KorGE draws each step`() {
        val backend = backend()
        val ui = uiTest(Size(size, size), backend) {
            var name by remember { mutableStateOf("") }
            val saves = remember { mutableStateListOf(*Array(12) { "Save ${it + 1}" }) }
            Panel(Modifier.offset(20f, 20f).width(360f).testTag("panel")) {
                Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                    Text("SAVES: ${saves.size}", Modifier.testTag("count"))
                    TextField(name, { name = it }, Modifier.fillMaxWidth().testTag("name"), placeholder = "Name")
                    Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                        Button("ADD", onClick = { if (name.isNotBlank()) saves.add(0, name).also { name = "" } }, modifier = Modifier.testTag("add"))
                        Button("DELETE", onClick = { if (saves.isNotEmpty()) saves.removeAt(0) }, modifier = Modifier.testTag("delete"))
                    }
                    LazyColumn(saves.size, Modifier.size(320f, 150f).testTag("list"), key = { saves[it] }, bars = false) { index ->
                        Text(saves[index], Modifier.fillMaxWidth().height(30f).testTag("row $index"))
                    }
                }
            }
        }
        try {
            val panel = ui.node("panel").boundsInRoot
            val first = frame(ui, backend)
            val panelPixels = first.area(panel)
            assertTrue(panelPixels.toSet().size > 4, "the panel is drawn with its widgets in it, not as one flat colour")
            assertEquals(0, first.area(Rect.of(0f, 0f, 15f, 15f)).lit(), "nothing is drawn outside the panel's top-left corner")
            ui.assertText("count", "SAVES: 12")
            ui.assertText("row 0", "Save 1")

            // The pointer focuses the field and the keyboard types into it; the typed name is drawn.
            val field = ui.node("name").boundsInRoot
            val emptyField = first.area(field)
            ui.click("name")
            ui.assertFocused("name")
            ui.type("Castle")
            ui.assertText("name", "Castle")
            val typed = frame(ui, backend).area(field)
            assertTrue(typed != emptyField, "the typed name changed the field's pixels")

            // Backspace takes the last letter off, in the tree and on screen.
            ui.key(Key.Backspace)
            ui.assertText("name", "Castl")
            assertTrue(frame(ui, backend).area(field) != typed, "backspace changed the field's pixels")

            // Adding puts the name at the top of the list and clears the field.
            ui.click("add")
            ui.assertText("count", "SAVES: 13")
            ui.assertText("row 0", "Castl")
            // An empty field reads out its placeholder.
            ui.assertText("name", "Name")
            val rowZero = ui.node("row 0").boundsInRoot
            val beforeScroll = frame(ui, backend)
            assertTrue(beforeScroll.area(rowZero).lit() > 10, "the new top row's words are drawn in lit pixels")

            // The wheel scrolls the list; what was on top leaves the drawn window.
            val list = ui.node("list").boundsInRoot
            repeat(3) { ui.scroll("list", Offset(0f, 120f)) }
            ui.advanceBy(500)
            ui.assertDoesNotExist("row 0")
            val scrolled = frame(ui, backend)
            assertTrue(scrolled.area(list) != beforeScroll.area(list), "the scroll moved the list's pixels")
            assertEquals(
                0,
                scrolled.area(Rect.of(list.left, list.bottom + 2f, list.width, 14f)).lit(),
                "rows scrolled past the list's bottom are clipped, not drawn under it",
            )

            // Scroll back, delete once with the pointer, then again with Enter on the focused button.
            repeat(3) { ui.scroll("list", Offset(0f, -120f)) }
            ui.advanceBy(500)
            ui.click("delete")
            ui.assertText("count", "SAVES: 12")
            ui.assertText("row 0", "Save 1")
            ui.assertFocused("delete")
            ui.key(Key.Enter)
            ui.assertText("count", "SAVES: 11")
            ui.assertText("row 0", "Save 2")
            val deleted = frame(ui, backend)
            assertFalse(deleted.area(ui.node("row 0").boundsInRoot) == beforeScroll.area(rowZero), "the top row's pixels show the new top save")
        } finally {
            ui.close()
            backend.close()
        }
    }
}
