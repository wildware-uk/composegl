package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A fast flick on a table's column divider or a splitter's divider.
 *
 * A hand moving quickly sends one move that lands well past the thin grab area before a frame has
 * laid the divider out under the pointer again. The drag must keep its grip: the edge follows the
 * pointer, the divider stays lit as pressed until the button comes up, and letting go anywhere ends it.
 * (A release carries the edge to where it happens, so the tests let go straight above or below the
 * last move, far off the handle.)
 *
 * The events go straight to the input, with no frame between them, as a real flick does.
 */
class DividerFlickTest {

    private val opened = mutableListOf<UiTest>()

    @AfterEach
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size, content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    private fun UiTest.send(event: PointerEvent) = input.onPointer(event)

    private fun UiTest.pressAt(at: Offset) {
        send(PointerEvent.Move(PointerId.Mouse, at))
        send(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
    }

    private fun UiTest.flickTo(at: Offset) =
        send(PointerEvent.Move(PointerId.Mouse, at, setOf(PointerButton.Primary)))

    private fun UiTest.releaseAt(at: Offset) =
        send(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))

    private val UiNode.pressed: Boolean get() = resolved.interactions.any { it.isPressed }

    // --- the table --------------------------------------------------------------------------------

    private class Row(val id: Int)

    @Composable
    private fun Scoreboard() {
        Table(rows = listOf(Row(1), Row(2)), modifier = Modifier.size(400f, 200f), key = { it.id }) {
            column("Name", weight = 1f) { Text("n${it.id}") }
            column("Kills", width = 64f) { Text("k${it.id}") }
            column("Ping", width = 64f) { Text("p${it.id}") }
        }
    }

    private fun UiTest.divider(): UiNode = named("table.divider")[0]

    /** Where the Name column's end edge is drawn: its title's right side. */
    private fun UiTest.nameEdge(): Float = named("table.header.cell")[0].boundsInRoot.right

    @Test
    fun `one big move past a table divider keeps the drag and the pressed look`() {
        val ui = open(Size(600f, 400f)) { Scoreboard() }
        val grab = ui.divider().boundsInRoot.centre
        val edge = ui.nameEdge()

        ui.pressAt(grab)
        assertTrue(ui.divider().pressed, "pressed on the press")

        ui.flickTo(grab - Offset(60f, 0f))
        assertTrue(ui.divider().pressed, "still pressed straight after the flick, before any frame")
        ui.settle()
        assertEquals(edge - 60f, ui.nameEdge(), 0.5f, "the edge followed the pointer")
        assertEquals(grab.x - 60f, ui.divider().boundsInRoot.centre.x, 0.5f, "and the divider is under it")
        assertTrue(ui.divider().pressed, "still pressed once laid out again")

        ui.releaseAt(Offset(grab.x - 60f, 390f))
        ui.settle()
        assertFalse(ui.divider().pressed, "letting go far away ends it")
        assertEquals(edge - 60f, ui.nameEdge(), 0.5f)

        ui.send(PointerEvent.Move(PointerId.Mouse, Offset(500f, 390f)))
        ui.settle()
        assertEquals(edge - 60f, ui.nameEdge(), 0.5f, "a move after the release drags nothing")
    }

    @Test
    fun `several moves between frames past a table divider keep the drag`() {
        val ui = open(Size(600f, 400f)) { Scoreboard() }
        val grab = ui.divider().boundsInRoot.centre
        val edge = ui.nameEdge()

        ui.pressAt(grab)
        for (by in listOf(-15f, -40f, -80f, -50f)) {
            ui.flickTo(grab + Offset(by, 30f))
            assertTrue(ui.divider().pressed, "pressed after a move of $by with no frame")
        }
        ui.settle()
        assertEquals(edge - 50f, ui.nameEdge(), 0.5f, "the edge is where the pointer ended")
        assertTrue(ui.divider().pressed, "still pressed after the frame")

        // Back past it the other way in one go, then several frames later.
        ui.flickTo(grab + Offset(25f, -20f))
        ui.settle()
        assertEquals(edge + 25f, ui.nameEdge(), 0.5f)
        assertTrue(ui.divider().pressed)

        ui.releaseAt(Offset(grab.x + 25f, 390f))
        ui.settle()
        assertFalse(ui.divider().pressed, "released")
        assertEquals(edge + 25f, ui.nameEdge(), 0.5f, "and the release landed nothing extra")
    }

    // --- the splitter -----------------------------------------------------------------------------

    private var split by mutableStateOf(0.25f)

    @Composable
    private fun Editor() {
        Splitter(
            split, { split = it }, Modifier.fillMaxSize().testTag("splitter"),
            first = { Box(Modifier.fillMaxSize()) },
            second = { Box(Modifier.fillMaxSize()) },
        )
    }

    private fun UiTest.splitDivider(): UiNode = node("splitter").children[1]

    @Test
    fun `one big move past a splitter divider keeps the drag and the pressed look`() {
        val ui = open(Size(406f, 300f)) { Editor() }
        val grab = ui.splitDivider().boundsInRoot.centre

        ui.pressAt(grab)
        ui.flickTo(grab + Offset(100f, 0f))
        assertTrue(ui.splitDivider().pressed, "still pressed straight after the flick, before any frame")
        ui.settle()
        assertEquals(grab.x + 100f, ui.splitDivider().boundsInRoot.centre.x, 0.5f, "the divider followed the pointer")
        assertTrue(ui.splitDivider().pressed, "still pressed once laid out again")

        for (by in listOf(130f, 160f, 60f)) {
            ui.flickTo(grab + Offset(by, 50f))
            assertTrue(ui.splitDivider().pressed, "pressed after a move of $by with no frame")
        }
        ui.settle()
        assertEquals(grab.x + 60f, ui.splitDivider().boundsInRoot.centre.x, 0.5f)
        assertTrue(ui.splitDivider().pressed)

        ui.releaseAt(Offset(grab.x + 60f, 295f))
        ui.settle()
        assertFalse(ui.splitDivider().pressed, "letting go far away ends it")
        assertEquals(grab.x + 60f, ui.splitDivider().boundsInRoot.centre.x, 0.5f)
    }
}
