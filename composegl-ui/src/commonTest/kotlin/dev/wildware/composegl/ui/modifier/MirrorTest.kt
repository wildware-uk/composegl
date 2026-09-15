package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A mirror on real composed UI: what the player sees flipped, and every way in agreeing with it.
 *
 * Each test composes a screen through [uiTest] and drives it the way a player would — pointer
 * presses and drags, arrow keys, a pad — asserting on where things are drawn and what happened. A
 * mirror that only flipped the picture would pass the drawing assertions and fail every other one
 * here.
 */
class MirrorTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun show(content: @Composable () -> Unit): UiTest = uiTest(content = content).also { opened += it }

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** Two 100-wide tiles in a row: "first" laid out on the left, "second" on the right. */
    @Composable
    private fun Tiles(mirrored: Boolean, clicks: MutableList<String>) {
        Row(Modifier.mirror(horizontal = mirrored).testTag("row")) {
            Box(
                Modifier.size(100f, 50f).background(red).focusable()
                    .clickable { clicks += "first" }.testTag("first"),
            ) {}
            Box(
                Modifier.size(100f, 50f).background(blue).focusable()
                    .clickable { clicks += "second" }.testTag("second"),
            ) {}
        }
    }

    @Test
    fun `a mirrored row draws its first child on the right and takes its clicks there`() {
        val clicks = mutableListOf<String>()
        val ui = show { Tiles(mirrored = true, clicks = clicks) }

        assertEquals(Rect.of(100f, 0f, 100f, 50f), ui.node("first").boundsInRoot)
        assertEquals(Rect.of(0f, 0f, 100f, 50f), ui.node("second").boundsInRoot)
        // Layout itself did not move: the slot is the one the row measured.
        assertEquals(Rect.of(0f, 0f, 100f, 50f), ui.node("first").layoutBoundsInRoot)

        ui.click(Offset(150f, 25f))
        ui.click(Offset(50f, 25f))
        assertEquals(listOf("first", "second"), clicks, "each click hit the tile drawn under it")
    }

    @Test
    fun `the picture is put down read from the other side`() {
        val ui = show { Tiles(mirrored = true, clicks = mutableListOf()) }

        val canvas = RecordingCanvas()
        DrawPass(canvas).draw(ui.root)

        val layer = canvas.calls.filterIsInstance<DrawCall.Layer>().single()
        assertTrue(layer.mirrorX, "the row's picture is flipped left for right")
        assertFalse(layer.mirrorY, "and not top for bottom")
        assertEquals(Rect.of(0f, 0f, 200f, 50f), layer.bounds, "in the row's own rectangle")
        // Inside the picture the tiles are drawn where layout put them; the flip is the composite.
        val red = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first { it.colour == red }
        assertEquals(Rect.of(0f, 0f, 100f, 50f), red.rect)
    }

    @Test
    fun `arrow keys and the pad walk focus the way the row is drawn`() {
        val ui = show { Tiles(mirrored = true, clicks = mutableListOf()) }

        ui.focus.focusOn(ui.node("second"))
        ui.key(Key.Right)
        ui.assertFocused("first")

        ui.pad(GamepadButton.DpadLeft)
        ui.assertFocused("second")
    }

    @Test
    fun `flipping from game state moves what a click hits`() {
        val clicks = mutableListOf<String>()
        var facingRight by mutableStateOf(false)
        val ui = show { Tiles(mirrored = facingRight, clicks = clicks) }

        ui.click(Offset(50f, 25f))
        assertEquals(listOf("first"), clicks, "unmirrored, the first tile is on the left")
        val canvas = RecordingCanvas()
        DrawPass(canvas).draw(ui.root)
        assertTrue(canvas.calls.none { it is DrawCall.Layer }, "and a mirror switched off takes no picture")

        facingRight = true
        ui.settle()
        ui.click(Offset(50f, 25f))
        assertEquals(listOf("first", "second"), clicks, "flipped, the second tile is drawn there")

        facingRight = false
        ui.settle()
        ui.click(Offset(50f, 25f))
        assertEquals(listOf("first", "second", "first"), clicks, "and flipped back, the first again")
    }

    @Test
    fun `a drag inside a mirror runs the other way in the handler's own coordinates`() {
        val seen = mutableListOf<Offset>()
        val ui = show {
            Box(Modifier.mirror()) {
                Box(Modifier.size(200f, 100f).onPointer { event -> seen += event.position; true }) {}
            }
        }

        ui.drag(Offset(150f, 20f), Offset(170f, 20f))

        assertEquals(Offset(50f, 20f), seen[0], "150 across the screen is 50 in from its own left edge")
        assertEquals(Offset(30f, 20f), seen[1], "and moving right on screen moves left in its own units")
    }

    @Test
    fun `a hit shape is asked in the node's own mirrored coordinates`() {
        var clicks = 0
        val ui = show {
            Box(Modifier.mirror()) {
                // Only the left quarter of the node is its own — which, mirrored, is drawn on the right.
                Box(Modifier.size(200f, 100f).hitShape { it.x < 50f }.clickable { clicks++ }) {}
            }
        }

        ui.click(Offset(20f, 50f))
        assertEquals(0, clicks, "the drawn left edge is the node's own right edge, outside its shape")
        ui.click(Offset(180f, 50f))
        assertEquals(1, clicks, "the drawn right edge is its own left quarter")
    }

    @Test
    fun `a shaped clip inside a mirror is clicked on the side it is drawn`() {
        var clicks = 0
        val ui = show {
            Box(Modifier.mirror()) {
                // A triangle over the node's own top-left half — which, mirrored, is drawn top-right.
                Box(
                    Modifier.size(200f, 100f).clipShape(Shapes.polygon(0f, 0f, 1f, 0f, 0f, 1f))
                        .clickable { clicks++ },
                ) {}
            }
        }

        ui.click(Offset(20f, 20f))
        assertEquals(0, clicks, "the drawn top-left corner is the corner the triangle cut away")
        ui.click(Offset(180f, 20f))
        assertEquals(1, clicks, "the drawn top-right corner is inside it")
    }

    @Test
    fun `a flipped portrait inside a flipped panel faces the way it was drawn`() {
        val clicks = mutableListOf<String>()
        val ui = show {
            Row(Modifier.mirror()) {
                Box(Modifier.size(100f, 50f)) {}
                Row(Modifier.mirror()) {
                    Box(Modifier.size(30f, 50f).clickable { clicks += "left" }.testTag("left")) {}
                    Box(Modifier.size(70f, 50f).clickable { clicks += "right" }.testTag("right")) {}
                }
            }
        }

        // The inner row is laid out on the right and drawn on the left; inside it the two mirrors
        // cancel, so its first child is on its left again.
        assertEquals(Rect.of(0f, 0f, 30f, 50f), ui.node("left").boundsInRoot)
        assertEquals(Rect.of(30f, 0f, 70f, 50f), ui.node("right").boundsInRoot)
        ui.click(Offset(10f, 25f))
        assertEquals(listOf("left"), clicks)
    }

    @Test
    fun `a real button in a mirrored row is clicked where it is drawn`() {
        var played = 0
        val ui = show {
            Row(Modifier.mirror()) {
                Button("PLAY", onClick = { played++ }, modifier = Modifier.testTag("play"))
                Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
            }
        }

        val play = ui.node("play").boundsInRoot
        val quit = ui.node("quit").boundsInRoot
        assertTrue(play.left > quit.left, "PLAY, laid out first, is drawn to the right of QUIT: $play $quit")

        ui.click("play")
        assertEquals(1, played)
    }

    @Test
    fun `a child hanging out of a mirror cannot be clicked where nothing is drawn`() {
        var clicks = 0
        val ui = show {
            Box(Modifier.offset(200f, 0f).size(100f, 50f).mirror()) {
                // Laid out past the right edge; flipped, that is past the left edge, and the picture
                // stops at the node's rectangle either way.
                Box(Modifier.offset(120f, 0f).size(20f, 50f).clickable { clicks++ }) {}
            }
        }

        ui.click(Offset(170f, 25f))
        ui.click(Offset(330f, 25f))
        assertEquals(0, clicks, "neither where it would be flipped to nor where it was laid out")
    }

    @Test
    fun `a mirrored slider follows the pointer across the screen`() {
        var value by mutableStateOf(0.5f)
        val ui = show {
            Box(Modifier.mirror()) {
                Slider(value, onValueChange = { value = it }, modifier = Modifier.width(200f).testTag("slider"))
            }
        }

        // The knob is 16 across, so its travel is 184 and its middle reaches 8 in from either end.
        ui.press(Offset(192f, 8f))
        assertNear(0f, value, "the drawn right end is the slider's own left, its minimum")
        ui.release()
        ui.drag(Offset(100f, 8f), Offset(8f, 8f))
        assertNear(1f, value, "and dragging to the drawn left end is its maximum")
    }

    /**
     * A press at [from], a move to [to] with the button held, and a release there, settling after
     * each. Through a router of its own on the same screen, because the harness only hovers: its
     * move carries no held button, and a drag is exactly the difference.
     */
    private fun UiTest.drag(from: Offset, to: Offset) {
        val pointer = PointerRouter(root, focus)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, from))
        settle()
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, to, pressed = setOf(PointerButton.Primary)))
        settle()
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, to))
        settle()
    }

    @Test
    fun `the arrows and the pad move a mirrored slider's knob the way they point`() {
        var value by mutableStateOf(0.5f)
        val ui = show {
            Box(Modifier.mirror()) {
                Slider(value, onValueChange = { value = it }, modifier = Modifier.width(200f).testTag("slider"))
            }
        }
        ui.focus.focusOn(ui.node("slider"))
        val knobAt = { ui.root.firstOrNull { it.name == "slider.knob" }!!.boundsInRoot.left }

        val before = knobAt()
        ui.key(Key.Right)
        assertNear(0.45f, value, "right on screen is towards the slider's own left")
        assertTrue(knobAt() > before, "so the knob moved right, where the arrow points")

        ui.pad(GamepadButton.DpadLeft)
        ui.pad(GamepadButton.DpadLeft)
        assertNear(0.55f, value, "and the pad's left is towards its own right")
        ui.assertFocused("slider")
    }

    @Test
    fun `text inside a mirror is still the text the player is shown`() {
        val ui = show {
            Box(Modifier.mirror()) { Text("HEY", modifier = Modifier.testTag("word")) }
        }

        ui.assertText("word", "HEY")
    }

    @Test
    fun `what a mirrored sprite painted is reported on the side it was drawn`() {
        val ui = show {
            Box(Modifier.size(200f, 50f).mirror().testTag("sprite")) {
                // A face 40 across on its own left, and nothing else painted.
                Box(Modifier.size(40f, 50f).background(red)) {}
            }
        }

        assertEquals(Rect.of(160f, 0f, 40f, 50f), ui.node("sprite").paintedInRoot)
    }

    @Test
    fun `a vertical mirror stacks a column bottom to top for clicks and focus`() {
        val clicks = mutableListOf<String>()
        val ui = show {
            Column(Modifier.mirror(horizontal = false, vertical = true)) {
                Box(Modifier.size(100f, 40f).focusable().clickable { clicks += "top" }.testTag("top")) {}
                Box(Modifier.size(100f, 40f).focusable().clickable { clicks += "bottom" }.testTag("bottom")) {}
            }
        }

        assertEquals(Rect.of(0f, 40f, 100f, 40f), ui.node("top").boundsInRoot)
        ui.click(Offset(50f, 20f))
        assertEquals(listOf("bottom"), clicks, "the second child is drawn at the top")

        ui.focus.focusOn(ui.node("top"))
        ui.key(Key.Up)
        ui.assertFocused("bottom")
    }

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 0.001f, "$message: expected $expected, was $actual")
}
