package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.repeatingClickable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A drag and a hold on the same press. A press that turns into a drag has stopped being a hold, so
 * the long press and the repeat it was waiting on never fire — on the node being dragged, or on a
 * button inside a window the drag was handed to.
 */
class DragAndHoldUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(500f, 400f), content = content).also { opened += it }

    /** A window that can be dragged, with a slot that long presses and a + that repeats. */
    @Composable
    private fun Window() {
        var at by remember { mutableStateOf(Offset(40f, 40f)) }
        var log by remember { mutableStateOf("") }
        Column {
            Box(Modifier.testTag("window").offset(at.x, at.y).size(300f, 200f).draggable { at += it }) {
                Column {
                    Box(Modifier.testTag("slot").size(120f, 40f).clickable(onLongPress = { log += "L" }) { log += "C" })
                    Box(Modifier.testTag("plus").size(60f, 40f).repeatingClickable { log += "+" })
                }
            }
            Text(log, Modifier.testTag("log"))
        }
    }

    @Test
    fun `dragging a window from a slot that long presses moves the window and never long presses`() {
        val ui = open { Window() }
        val start = ui.node("window").boundsInRoot.left

        ui.press("slot")
        ui.advanceBy(100)
        ui.moveTo(ui.node("slot").boundsInRoot.centre + Offset(30f, 0f))
        ui.advanceBy(1_000)
        ui.release()

        ui.assertText("log", "")
        assertEquals(start + 30f, ui.node("window").boundsInRoot.left, 0.01f)
    }

    @Test
    fun `dragging a window from a held plus does not keep counting`() {
        val ui = open { Window() }

        ui.press("plus")
        ui.moveTo(ui.node("plus").boundsInRoot.centre + Offset(20f, 0f))
        ui.advanceBy(2_000)
        ui.release()

        ui.assertText("log", "")
    }

    @Test
    fun `a card that is both dragged and long pressed only drags once it moves`() {
        val ui = open {
            var at by remember { mutableStateOf(Offset(20f, 20f)) }
            var log by remember { mutableStateOf("") }
            Column {
                Box(
                    Modifier.testTag("card").offset(at.x, at.y).size(80f, 50f)
                        .clickable(onLongPress = { log += "L" }) { log += "C" }
                        .draggable { at += it },
                )
                Text(log, Modifier.testTag("log"))
            }
        }
        val start = ui.node("card").boundsInRoot.left

        ui.press("card")
        ui.moveTo(ui.node("card").boundsInRoot.centre + Offset(20f, 0f))
        ui.advanceBy(1_000)
        ui.release()

        ui.assertText("log", "")
        assertEquals(start + 20f, ui.node("card").boundsInRoot.left, 0.01f)

        // Held still, the same card is a long press as before.
        ui.press("card")
        ui.advanceBy(1_000)
        ui.release()
        ui.assertText("log", "L")
    }
}
