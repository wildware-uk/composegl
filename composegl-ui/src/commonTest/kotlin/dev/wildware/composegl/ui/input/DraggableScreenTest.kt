package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `draggable` on screens composed by [uiTest] and dragged by its mouse: the cases where the screen
 * itself changes under a drag — the thing being dragged goes away, or stops being draggable.
 */
class DraggableScreenTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), content = content).also { opened += it }

    private var at by mutableStateOf(Offset(50f, 50f))
    private val heard = mutableListOf<String>()
    private var clicks = 0

    @Test
    fun `the harness mouse drags a card to where it lets go`() {
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(at.x, at.y).size(80f, 40f).draggable { at += it }.testTag("card"))
            }
        }

        ui.press("card")
        ui.moveTo(Offset(150f, 100f))
        ui.moveTo(Offset(390f, 270f))
        ui.release()

        // Pressed on its middle at 90, 70, let go 300 across and 200 down from there.
        assertEquals(Rect.of(350f, 250f, 80f, 40f), ui.node("card").boundsInRoot)
    }

    @Test
    fun `an item dragged into the bin is gone and its drag is cancelled`() {
        var inBag by mutableStateOf(true)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                if (inBag) {
                    Box(
                        Modifier.offset(at.x, at.y).size(80f, 40f)
                            .draggable(onDragEnd = { heard += "end" }, onDragCancel = { heard += "cancel" }) {
                                heard += "drag"
                                at += it
                                // Past the bin: the screen throws it away while the hand is still down.
                                if (at.x > 300f) inBag = false
                            }
                            .testTag("item"),
                    )
                }
            }
        }

        ui.press("item")
        ui.moveTo(Offset(400f, 70f))
        ui.assertDoesNotExist("item")
        ui.moveTo(Offset(500f, 70f))
        ui.release()

        assertEquals(listOf("drag", "cancel"), heard, "told once that it moved, then that it was cancelled")
    }

    @Test
    fun `an item taken away while pressed never starts a drag`() {
        var inBag by mutableStateOf(true)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                if (inBag) {
                    Box(
                        Modifier.offset(at.x, at.y).size(80f, 40f)
                            .draggable(
                                onDragStart = { heard += "start" },
                                onDragEnd = { heard += "end" },
                                onDragCancel = { heard += "cancel" },
                            ) { heard += "drag" }
                            .testTag("item"),
                    )
                }
            }
        }

        ui.press("item")
        // Sold from under the hand before it moved: the screen rebuilt without it.
        inBag = false
        ui.settle()
        ui.assertDoesNotExist("item")
        ui.moveTo(Offset(300f, 200f))
        ui.release()

        assertEquals(emptyList(), heard, "nothing on the screen was dragged, so nothing hears about a drag")
    }

    @Test
    fun `a card locked in the middle of a drag stays put and is not clicked`() {
        var locked by mutableStateOf(false)
        val ui = open {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.offset(at.x, at.y).size(80f, 40f)
                        .clickable { clicks++ }
                        .draggable(enabled = !locked, onDragCancel = { heard += "cancel" }) {
                            at += it
                            if (at.x >= 100f) locked = true
                        }
                        .testTag("card"),
                )
            }
        }

        val start = ui.node("card").boundsInRoot.centre
        ui.press("card")
        ui.moveTo(start + Offset(60f, 0f))
        ui.moveTo(start + Offset(120f, 0f))
        // Back over the card, and let go there: still a drag, not a click.
        ui.moveTo(start + Offset(60f, 0f))
        ui.release()

        assertEquals(Rect.of(110f, 50f, 80f, 40f), ui.node("card").boundsInRoot, "it stopped where it was locked")
        assertEquals(listOf("cancel"), heard)
        assertEquals(0, clicks)
    }
}
