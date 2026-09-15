package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A round portrait over a square button, composed for real and driven with a pointer.
 *
 * The portrait's box covers the button's entirely, so every click the button gets in here is one
 * the circle gave up: what you cannot see you cannot press, and the click goes to what you can.
 */
class ClipShapeTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private var clock = 0L

    private val red = Colour.rgb(0xFF0000)
    private val yellow = Colour.rgb(0xFFFF00)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        frame()
    }

    /** One turn of a game loop: recompose, lay out, settle focus, draw. */
    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun click(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))
        frame()
    }

    private var underClicks by mutableStateOf(0)
    private var portraitClicks by mutableStateOf(0)
    private val portraitState = InteractionState()

    /** A 100 square button, and a 100 square portrait over it cut to [shape], both at 20, 20. */
    @Composable
    private fun Screen(shape: dev.wildware.composegl.ui.geometry.Shape = Shapes.Circle) {
        Box(Modifier.padding(20f)) {
            Box(Modifier.size(100f).background(if (underClicks > 0) green else blue).clickable { underClicks++ })
            Box(Modifier.size(100f).clipShape(shape)) {
                Box(
                    Modifier.size(100f)
                        .interaction(portraitState)
                        .background(if (portraitClicks > 0) yellow else red)
                        .clickable { portraitClicks++ },
                )
            }
        }
    }

    @Test
    fun `the portrait is drawn cut to a circle over the square button`() {
        show { Screen() }

        val layer = canvas.only<DrawCall.Layer>().single()
        assertEquals(Rect.of(20f, 20f, 100f, 100f), layer.bounds)
        assertTrue(layer.outline!!.size >= 32, "put down through a circle, not a box")
        val colours = canvas.only<DrawCall.Rectangle>().map { it.colour }
        assertEquals(listOf(blue, red), colours, "the button whole underneath, the portrait's art inside the picture")
    }

    @Test
    fun `a click in a corner the circle cut away goes to the button underneath`() {
        show { Screen() }

        click(24f, 24f)
        assertEquals(1, underClicks, "the corner is the button's")
        assertEquals(0, portraitClicks)
        assertTrue(canvas.only<DrawCall.Rectangle>().any { it.colour == green }, "and the button shows it was pressed")

        click(70f, 70f)
        assertEquals(1, portraitClicks, "the middle is the portrait's")
        assertEquals(1, underClicks)
        assertTrue(canvas.only<DrawCall.Rectangle>().any { it.colour == yellow })
    }

    @Test
    fun `the portrait is hovered only inside the circle`() {
        show { Screen() }

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(22f, 118f)))
        assertFalse(portraitState.isHovered, "the bottom-left corner is cut away")

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(70f, 118f)))
        assertTrue(portraitState.isHovered, "the bottom of the circle is not")
    }

    @Test
    fun `dragging a press off the circle and letting go there is not a click`() {
        show { Screen() }

        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(70f, 70f)))
        assertTrue(portraitState.isPressed)
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(24f, 24f), setOf(PointerButton.Primary)))
        assertFalse(portraitState.isPressed, "off the circle is off the portrait, even inside its box")
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(24f, 24f)))
        frame()

        assertEquals(0, portraitClicks)
        assertEquals(0, underClicks, "and the button never had the press to give a click for")
    }

    @Test
    fun `a diamond gives its corners away the same way`() {
        show { Screen(Shapes.Diamond) }

        click(30f, 30f)
        assertEquals(1, underClicks)
        click(70f, 30f)
        assertEquals(1, portraitClicks, "the top point of the diamond")
    }

    @Test
    fun `a hit shape alone makes a node round to the pointer without cutting what it draws`() {
        var clicks by mutableStateOf(0)
        show {
            Box(Modifier.padding(20f)) {
                Box(Modifier.size(100f).clickable { underClicks++ })
                Box(Modifier.size(100f).background(red).hitShape(Shapes.Circle).clickable { clicks++ })
            }
        }

        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "nothing is cut, so no picture is taken")
        click(24f, 24f)
        assertEquals(0, clicks)
        assertEquals(1, underClicks, "the square corner is drawn but given away")
        click(70f, 70f)
        assertEquals(1, clicks)
    }

    @Test
    fun `the later of a shaped hit test and a lambda one wins`() {
        var clicks by mutableStateOf(0)
        show {
            Box(Modifier.padding(20f)) {
                Box(Modifier.size(100f).hitShape(Shapes.Circle).hitShape { true }.clickable { clicks++ })
            }
        }

        click(24f, 24f)
        assertEquals(1, clicks, "the lambda came later and claims the corner")
    }
}
