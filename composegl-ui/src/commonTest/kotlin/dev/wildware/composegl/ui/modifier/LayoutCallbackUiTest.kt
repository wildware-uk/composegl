package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The popup-under-a-button screen again, this time through the [uiTest] harness: a click moves the
 * toolbar, a pad press widens it, and the popup and the size readout follow with nobody polling.
 */
class LayoutCallbackUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private var toolbarX by mutableStateOf(20f)
    private var toolbarWidth by mutableStateOf(120f)
    private var anchor: Rect? by mutableStateOf(null)
    private var toolbarSize: Size? by mutableStateOf(null)
    private var placedCalls = 0
    private var sizeCalls = 0

    private fun openScreen() = open {
        val placed = remember { PlacedHandler { node -> placedCalls++; anchor = node.boundsInRoot } }
        val sized = remember { SizeChangedHandler { placedSize -> sizeCalls++; toolbarSize = placedSize } }
        Box(Modifier.size(400f, 300f)) {
            Column {
                Button("MOVE", onClick = { toolbarX += 50f }, initialFocus = true, modifier = Modifier.testTag("move"))
                Button("GROW", onClick = { toolbarWidth += 40f }, modifier = Modifier.testTag("grow"))
            }
            Box(Modifier.offset(toolbarX, 100f).size(toolbarWidth, 30f).onSizeChanged(sized).testTag("toolbar")) {
                Box(Modifier.offset(10f, 5f).size(40f, 20f).onPlaced(placed).testTag("anchor")) {}
            }
            anchor?.let { under ->
                Box(Modifier.offset(under.left, under.bottom + 4f).size(60f, 40f).testTag("popup")) {}
            }
        }
    }

    @Test
    fun `a popup follows its button when a click moves the toolbar`() {
        val ui = openScreen()
        assertEquals(30f, ui.node("popup").boundsInRoot.left)
        assertEquals(129f, ui.node("popup").boundsInRoot.top)

        ui.click("move")

        assertEquals(80f, ui.node("popup").boundsInRoot.left, "the popup is under the button where it is now")
        assertEquals(ui.node("anchor").boundsInRoot.left, ui.node("popup").boundsInRoot.left)
        assertEquals(Size(120f, 30f), toolbarSize, "moving is not resizing")
        assertEquals(1, sizeCalls)
    }

    @Test
    fun `a pad press that widens the toolbar is reported as its new size`() {
        val ui = openScreen()
        assertEquals(Size(120f, 30f), toolbarSize)
        val placedBefore = placedCalls

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("grow")
        ui.pad(GamepadButton.South)

        assertEquals(Size(160f, 30f), toolbarSize)
        assertEquals(2, sizeCalls)
        assertEquals(placedBefore, placedCalls, "the button did not move, so it is not told again")
    }

    @Test
    fun `a still screen calls nothing however many frames pass`() {
        val ui = openScreen()
        val placed = placedCalls
        val sized = sizeCalls

        ui.advanceBy(1000)

        assertEquals(placed, placedCalls)
        assertEquals(sized, sizeCalls)
    }
}
