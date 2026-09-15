package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.DefaultCameraDistance
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A card that flips over on a composed screen, driven through `uiTest` the way a player drives it —
 * a click, the pad, the keyboard, the clock — and judged by what the next frame drew.
 */
class Rotate3dUiTest {

    private val red = Colour.rgb(0xE5484D)
    private var clicks = 0

    /** Where the card is laid out: 120 by 80, its middle at (160, 100). */
    private val box = Rect.of(100f, 60f, 120f, 80f)

    /**
     * A card that turns over when pressed and back on Escape, peeks sixty degrees on Right, and
     * leaves on Delete. Its face says which side is up, and swaps at the halfway point.
     */
    private fun card(): UiTest = uiTest(Size(400f, 300f)) {
        var flipped by remember { mutableStateOf(false) }
        var held by remember { mutableStateOf<Float?>(null) }
        var shown by remember { mutableStateOf(true) }
        val flip by animateFloatAsState(if (flipped) 180f else 0f, Tween(durationMillis = 400))
        val angle = held ?: flip
        Box(Modifier.fillMaxSize()) {
            if (shown) {
                Box(
                    Modifier.testTag("card")
                        .offset(box.left, box.top)
                        .size(box.width, box.height)
                        .rotate3d(y = angle)
                        .background(red)
                        .focusable(initial = true)
                        .clickable {
                            clicks++
                            flipped = true
                        }
                        .onKeyEvent { event ->
                            when {
                                event.type != KeyEventType.Down -> false
                                event.key == Key.Escape -> { flipped = false; true }
                                event.key == Key.Right -> { held = 60f; true }
                                event.key == Key.Left -> { held = 120f; true }
                                event.key == Key.Delete -> { shown = false; true }
                                else -> false
                            }
                        },
                ) {
                    Text(if (angle < 90f) "front" else "back", Modifier.testTag("face"))
                }
            }
        }
    }

    /** One frame drawn from scratch into the headless canvas. */
    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    private fun RecordingCanvas.tilt(): DrawCall.TiltedLayer? = only<DrawCall.TiltedLayer>().singleOrNull()

    private inline fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /**
     * Where a corner of the card is seen once it has turned [degrees] about y, worked out here from
     * first principles rather than through the matrix the toolkit uses.
     */
    private fun seen(cornerX: Float, cornerY: Float, degrees: Float): Pair<Offset, Float> {
        val radians = degrees * PI.toFloat() / 180f
        val across = cornerX - box.centre.x
        val down = cornerY - box.centre.y
        val depth = -sin(radians) * across
        val w = 1f - depth / (DefaultCameraDistance * CameraDistanceUnit)
        return Offset(box.centre.x + cos(radians) * across / w, box.centre.y + down / w) to w
    }

    private fun assertTurned(canvas: RecordingCanvas, degrees: Float, because: String) {
        val tilt = checkNotNull(canvas.tilt()) { "$because: nothing was tilted" }
        assertEquals(box, tilt.bounds, "$because: captured flat at its own size")
        val xs = listOf(box.left, box.right, box.right, box.left)
        val ys = listOf(box.top, box.top, box.bottom, box.bottom)
        for (index in 0 until 4) {
            val (expected, w) = seen(xs[index], ys[index], degrees)
            assertEquals(expected.x, tilt.corners[index].x, 0.01f, "$because: corner $index x")
            assertEquals(expected.y, tilt.corners[index].y, 0.01f, "$because: corner $index y")
            assertEquals(w, tilt.depths[index], 0.0001f, "$because: corner $index depth")
        }
    }

    @Test
    fun `a card lying flat draws with no picture`() = card().using { ui ->
        val canvas = ui.drawn()

        assertNull(canvas.tilt(), "nothing to tilt yet")
        assertEquals(box, canvas.only<DrawCall.Rectangle>().single { it.colour == red }.rect)
        ui.assertText("face", "front")
    }

    @Test
    fun `clicking the card turns it all the way over`() = card().using { ui ->
        ui.click("card")

        assertEquals(1, clicks, "the click reached the card")
        assertEquals(180f, ui.node("card").resolved.rotation3dY, 0.001f)
        val canvas = ui.drawn()
        assertTurned(canvas, 180f, "turned over")
        val corners = canvas.tilt()!!.corners
        assertEquals(box.right, corners[0].x, 0.01f, "its top-left corner is now on the right")
        assertEquals(box.left, corners[1].x, 0.01f, "and its top-right on the left")
        ui.assertText("face", "back")
    }

    @Test
    fun `south on the pad flips the focused card the same way`() = card().using { ui ->
        ui.assertFocused("card")

        ui.pad(GamepadButton.South)

        assertTurned(ui.drawn(), 180f, "after South")
        ui.assertText("face", "back")
    }

    @Test
    fun `past half way the card shows its back and has swapped faces`() = card().using { ui ->
        ui.key(Key.Left)

        val canvas = ui.drawn()
        assertTurned(canvas, 120f, "held at a hundred and twenty degrees")
        val corners = canvas.tilt()!!.corners
        assertTrue(corners[0].x > corners[1].x, "the picture is seen from behind, so it reads right to left")
        ui.assertText("face", "back")
    }

    @Test
    fun `a card turned part way shows its near edge taller than its far edge`() = card().using { ui ->
        ui.key(Key.Right)

        val canvas = ui.drawn()
        assertTurned(canvas, 60f, "peeking sixty degrees")
        val corners = canvas.tilt()!!.corners
        val nearHeight = corners[3].y - corners[0].y
        val farHeight = corners[2].y - corners[1].y
        assertTrue(nearHeight > box.height, "the left edge swings towards the camera and grows: $nearHeight")
        assertTrue(farHeight < box.height, "the right edge swings away and shrinks: $farHeight")
        ui.assertText("face", "front")
    }

    @Test
    fun `escape on the flipped card turns it back and it stops costing a picture`() = card().using { ui ->
        ui.click("card")
        assertTurned(ui.drawn(), 180f, "turned over first")

        ui.key(Key.Escape)

        val canvas = ui.drawn()
        assertEquals(0f, ui.node("card").resolved.rotation3dY, 0.001f)
        assertNull(canvas.tilt(), "flat again")
        ui.assertText("face", "front")
    }

    @Test
    fun `a card that has finished turning over leaves a still screen still`() = card().using { ui ->
        ui.click("card")
        assertTurned(ui.drawn(), 180f, "turned over")

        assertEquals(false, ui.render(), "nothing changed, so nothing is drawn again")
        assertEquals(false, ui.render(), "nor on the frame after")
    }

    @Test
    fun `clicks on a tilted card land in its flat box not the shape it is drawn as`() = card().using { ui ->
        ui.key(Key.Right)
        val corners = checkNotNull(ui.drawn().tilt()).corners
        assertTrue(corners[1].x < 200f, "the far edge is drawn well inside the box: ${corners[1].x}")

        // Inside the laid-out box, right of where the turned card is drawn.
        assertTrue(ui.click(Offset(215f, 100f)), "the flat box still owns its right side")
        assertEquals(1, clicks)

        // Above the box, but under the near edge, which swells up past the box's top to about 56.
        ui.click(Offset(130f, 57f))
        assertEquals(1, clicks, "what swells past the box is not clickable")
    }

    @Test
    fun `a card taken off the screen leaves no tilt behind`() = card().using { ui ->
        ui.click("card")
        assertTurned(ui.drawn(), 180f, "turned first")

        ui.key(Key.Delete)

        ui.assertDoesNotExist("card")
        val canvas = ui.drawn()
        assertNull(canvas.tilt(), "no picture of a card that is not there")
        assertTrue(canvas.only<DrawCall.Rectangle>().none { it.colour == red }, "and nothing red at all")
    }

    @Test
    fun `a tilt inside a tilt is a picture inside a picture`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.testTag("outer").size(100f).rotate3d(x = 30f)) {
            Box(Modifier.testTag("inner").size(40f).rotate3d(y = -30f).background(red))
        }
    }.using { ui ->
        val tilts = ui.drawn().only<DrawCall.TiltedLayer>()

        assertEquals(
            listOf(Rect.of(0f, 0f, 40f, 40f), Rect.of(0f, 0f, 100f, 100f)),
            tilts.map { it.bounds },
            "the inner one is put down inside the outer picture, before the outer one is",
        )
        assertTrue(tilts[0].depths[0] > 1f, "the inner card's left edge goes away")
        assertTrue(tilts[1].depths[0] > 1f, "the outer card's top edge goes away")
    }

    @Test
    fun `a tilted node with no size draws without failing`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.testTag("empty").size(0f).rotate3d(x = 40f, y = 70f).background(red))
        }
    }.using { ui ->
        val canvas = ui.drawn()

        assertEquals(1, canvas.frames, "a whole frame came out")
        assertTrue(canvas.only<DrawCall.Rectangle>().none { !it.rect.isEmpty }, "and nothing with any area")
    }
}
