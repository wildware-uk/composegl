package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A custom layout telling its children apart by name, on a screen that is clicked and pressed.
 *
 * The layout here is a list item with three slots: an icon on the left, a label after it, a badge
 * against the right edge. The badge is the child that is only there sometimes, and it is written
 * first on purpose — which is exactly the shape that breaks a layout reading child order.
 */
class LayoutIdTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @androidx.compose.runtime.Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private val listItem = MeasurePolicy { measurables, constraints ->
        val loose = constraints.loosen()
        val placeables = measurables.map { it.measure(loose) }
        fun slot(id: Any) = measurables.indexOfFirst { it.layoutId == id }.takeIf { it >= 0 }?.let { placeables[it] }

        val icon = slot("icon")
        val label = slot("label")
        val badge = slot("badge")
        val width = constraints.maxWidth

        layout(width, placeables.maxOfOrNull { it.height } ?: 0f) {
            icon?.at(0f, 0f)
            label?.at((icon?.width ?: 0f) + 8f, 0f)
            badge?.at(width - badge.width, 0f)
        }
    }

    /** Where [tag] sits inside the list item, which is what the slots decide. */
    private fun UiTest.x(tag: String) = node(tag).x

    @Test
    fun `a badge that appears in front of the icon does not take the icon's place`() {
        var badged by mutableStateOf(false)
        val ui = open {
            Column {
                Button("BADGE", onClick = { badged = !badged }, initialFocus = true, modifier = Modifier.testTag("toggle"))
                Layout(name = "item", measurePolicy = listItem, content = {
                    if (badged) LeafLayout(Modifier.size(12f).layoutId("badge").testTag("badge"), name = "badge")
                    LeafLayout(Modifier.size(16f).layoutId("icon").testTag("icon"), name = "icon")
                    LeafLayout(Modifier.size(50f, 10f).layoutId("label").testTag("label"), name = "label")
                })
            }
        }

        assertEquals(0f, ui.x("icon"))
        assertEquals(24f, ui.x("label"))
        ui.assertDoesNotExist("badge")

        ui.click("toggle")

        assertEquals(0f, ui.x("icon"), "the icon is still in its slot, though it is second now")
        assertEquals(24f, ui.x("label"), "and the label still follows it")
        assertEquals(388f, ui.x("badge"), "the new child went to its own slot, not the icon's")

        // Away again, from the pad this time: the icon keeps its slot when the child before it goes.
        ui.pad(GamepadButton.South)

        ui.assertDoesNotExist("badge")
        assertEquals(0f, ui.x("icon"))
        assertEquals(24f, ui.x("label"))
    }

    @Test
    fun `renaming a child on a later frame moves it to the new slot`() {
        var swapped by mutableStateOf(false)
        val ui = open {
            Column {
                LeafLayout(Modifier.size(40f, 20f).clickable { swapped = !swapped }.testTag("toggle"), name = "toggle")
                Layout(name = "item", measurePolicy = listItem, content = {
                    LeafLayout(Modifier.size(16f).layoutId(if (swapped) "label" else "icon").testTag("square"), name = "square")
                    LeafLayout(Modifier.size(50f, 10f).layoutId(if (swapped) "icon" else "label").testTag("wide"), name = "wide")
                })
            }
        }

        assertEquals(0f, ui.x("square"))
        assertEquals(24f, ui.x("wide"))

        ui.click("toggle")

        // The node is the same node; only its name changed. A layout that kept last frame's name
        // would leave both where they were.
        assertEquals(0f, ui.x("wide"), "the wide one is the icon now")
        assertEquals(58f, ui.x("square"), "and the square one follows it as the label")

        ui.click("toggle")

        assertEquals(0f, ui.x("square"), "and back")
        assertEquals(24f, ui.x("wide"))
    }

    @Test
    fun `recomposing with the same name leaves a still screen still`() {
        var frame by mutableStateOf(0)
        val ui = open {
            Layout(name = "item", measurePolicy = listItem, content = {
                // Read here, so every write reruns the children and hands each a fresh modifier chain.
                @Suppress("UNUSED_VARIABLE") val unused = frame
                LeafLayout(Modifier.size(16f).layoutId("icon"), name = "icon")
                LeafLayout(Modifier.size(50f, 10f).layoutId(Slot.Trailing), name = "label")
            })
        }
        ui.render()

        frame++

        // A name compared by identity rather than by equals would make every recomposition a
        // change, and a menu that recomposes for a clock would redraw every frame for nothing.
        assertFalse(ui.render(), "an equal name is no change")
    }

    @Test
    fun `a layout sees only its own children's names`() {
        val ui = open {
            Layout(name = "item", measurePolicy = listItem, content = {
                Box(Modifier.testTag("wrapper")) {
                    LeafLayout(Modifier.size(16f).layoutId("icon"), name = "icon")
                }
                LeafLayout(Modifier.size(50f, 10f).layoutId("label").testTag("label"), name = "label")
            })
        }

        assertEquals(8f, ui.x("label"), "the icon's name is on the wrapper's child, so the item has no icon")
    }

    @Test
    fun `a slot layout with every child away is empty rather than broken`() {
        val ui = open {
            Column {
                Layout(Modifier.testTag("item"), name = "item", measurePolicy = listItem, content = {})
                LeafLayout(Modifier.size(10f).testTag("after"), name = "after")
            }
        }

        assertEquals(0f, ui.node("item").height)
        assertEquals(0f, ui.node("after").y, "nothing below it is pushed down")
    }

    @Test
    fun `the built-in layouts ignore a name`() {
        val ui = open {
            Row {
                LeafLayout(Modifier.size(30f).layoutId("icon").testTag("first"), name = "first")
                LeafLayout(Modifier.size(30f).layoutId("badge").testTag("second"), name = "second")
            }
        }

        assertEquals(0f, ui.x("first"))
        assertEquals(30f, ui.x("second"))
    }

    @Test
    fun `a name is any value with an equals`() {
        assertEquals(Slot.Trailing, Modifier.layoutId(Slot.Trailing).resolve().layoutId)
        assertEquals("b", Modifier.layoutId("a").layoutId("b").resolve().layoutId, "the later name is the one")
        assertNull(Modifier.size(10f).resolve().layoutId)
    }

    private enum class Slot { Trailing }
}
