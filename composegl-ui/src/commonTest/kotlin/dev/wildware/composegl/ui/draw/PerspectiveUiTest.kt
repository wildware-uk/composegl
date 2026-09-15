package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.DefaultCameraDistance
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.perspective
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A row of cards tilting together under one camera, on a composed screen driven through `uiTest` —
 * keys, the pad, clicks — and judged by where the next frame put each card's corners.
 */
class PerspectiveUiTest {

    private val red = Colour.rgb(0xE5484D)

    /** The shared camera: in front of the row's middle, 400 pixels away. */
    private val cameraX = 300f
    private val cameraY = 150f
    private val distance = 400f

    /** The cards' middles. The row is at (60, 110), three 120 by 80 cards with 60 between them. */
    private val middles = listOf(120f, 300f, 480f)

    /**
     * The row. Right tilts every card forty degrees, Left the other way, Escape lays them flat, and
     * a press on the row (or South on the pad) tilts them too, and Up brings the camera in to 250. The small box in the corner switches
     * the shared camera off and on again.
     */
    private fun row(): UiTest = uiTest(Size(600f, 300f)) {
        var tilt by remember { mutableStateOf(0f) }
        var shared by remember { mutableStateOf(true) }
        var near by remember { mutableStateOf(distance) }
        val angle by animateFloatAsState(tilt, Tween(durationMillis = 300))
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.testTag("toggle").size(40f).clickable { shared = !shared })
            Row(
                modifier = Modifier.testTag("row")
                    .offset(60f, 110f)
                    .let { if (shared) it.perspective(near) else it }
                    .focusable(initial = true)
                    .clickable { tilt = 40f }
                    .onKeyEvent { event ->
                        when {
                            event.type != KeyEventType.Down -> false
                            event.key == Key.Right -> { tilt = 40f; true }
                            event.key == Key.Left -> { tilt = -40f; true }
                            event.key == Key.Escape -> { tilt = 0f; true }
                            event.key == Key.Up -> { near = 250f; true }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(60f),
            ) {
                repeat(3) { index ->
                    Box(Modifier.testTag("card$index").size(120f, 80f).rotate3d(y = angle).background(red))
                }
            }
        }
    }

    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    /** The tilted cards, left to right. */
    private fun RecordingCanvas.cards(): List<DrawCall.TiltedLayer> =
        only<DrawCall.TiltedLayer>().sortedBy { it.bounds.left }

    private inline fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /**
     * Where a card's corner is seen, worked out from first principles: turned [degrees] about y
     * around the card's middle, then looked at by a camera at ([camX], [camY]) [camDistance] away.
     */
    private fun seen(
        cornerX: Float,
        cornerY: Float,
        middleX: Float,
        degrees: Float,
        camX: Float,
        camY: Float,
        camDistance: Float,
    ): Offset {
        val radians = degrees * PI.toFloat() / 180f
        val across = cornerX - middleX
        val turnedX = middleX + cos(radians) * across
        val depth = -sin(radians) * across
        val w = 1f - depth / camDistance
        return Offset(camX + (turnedX - camX) / w, camY + (cornerY - camY) / w)
    }

    /** Checks all three cards against [seen], with the camera for the card whose middle is given. */
    private fun assertSeen(
        canvas: RecordingCanvas,
        degrees: Float,
        because: String,
        camera: (middleX: Float) -> Triple<Float, Float, Float>,
    ) {
        val cards = canvas.cards()
        assertEquals(3, cards.size, "$because: three cards tilted")
        for ((card, middle) in cards.zip(middles)) {
            val (camX, camY, camDistance) = camera(middle)
            val xs = listOf(middle - 60f, middle + 60f, middle + 60f, middle - 60f)
            val ys = listOf(110f, 110f, 190f, 190f)
            for (index in 0 until 4) {
                val expected = seen(xs[index], ys[index], middle, degrees, camX, camY, camDistance)
                assertEquals(expected.x, card.corners[index].x, 0.02f, "$because: card at $middle corner $index x")
                assertEquals(expected.y, card.corners[index].y, 0.02f, "$because: card at $middle corner $index y")
            }
        }
    }

    private fun sharedCamera(@Suppress("UNUSED_PARAMETER") middle: Float) = Triple(cameraX, cameraY, distance)

    private fun ownCamera(middle: Float) = Triple(middle, cameraY, DefaultCameraDistance * CameraDistanceUnit)

    private fun DrawCall.TiltedLayer.width() = corners[1].x - corners[0].x

    /** The card's corners relative to its first one: its shape, wherever it is. */
    private fun DrawCall.TiltedLayer.shape() = corners.map { Offset(it.x - corners[0].x, it.y - corners[0].y) }

    @Test
    fun `a flat row under a camera draws with no pictures`() = row().using { ui ->
        val canvas = ui.drawn()

        assertTrue(canvas.cards().isEmpty(), "a camera alone tilts nothing")
        assertEquals(3, canvas.only<DrawCall.Rectangle>().count { it.colour == red })
    }

    @Test
    fun `pressing right tilts every card as seen from the one camera in front of the row`() = row().using { ui ->
        ui.key(Key.Right)

        assertEquals(40f, ui.node("card0").resolved.rotation3dY, 0.001f)
        assertSeen(ui.drawn(), 40f, "tilted right", ::sharedCamera)
    }

    @Test
    fun `the card left of the camera shows more of its face than the one right of it`() = row().using { ui ->
        ui.key(Key.Right)

        val (left, middle, right) = ui.drawn().cards()
        // About 128, 93 and 58 pixels wide: the right edges recede towards one vanishing point.
        assertTrue(left.width() > middle.width() + 20f, "left ${left.width()} against middle ${middle.width()}")
        assertTrue(middle.width() > right.width() + 20f, "middle ${middle.width()} against right ${right.width()}")
    }

    @Test
    fun `tilting the other way turns the scene round`() = row().using { ui ->
        ui.key(Key.Left)

        val canvas = ui.drawn()
        assertSeen(canvas, -40f, "tilted left", ::sharedCamera)
        val (left, _, right) = canvas.cards()
        assertTrue(right.width() > left.width() + 20f, "now the right card faces the camera more")
    }

    @Test
    fun `south on the pad tilts the row through the shared camera too`() = row().using { ui ->
        ui.assertFocused("row")

        ui.pad(GamepadButton.South)

        assertSeen(ui.drawn(), 40f, "after South", ::sharedCamera)
    }

    @Test
    fun `switching the camera off gives every card its own again`() = row().using { ui ->
        ui.click("row")
        val shared = ui.drawn().cards()
        assertFalse(abs(shared[0].width() - shared[2].width()) < 1f, "under one camera the cards differ")

        ui.click("toggle")

        assertEquals(0f, ui.node("row").resolved.perspective)
        val canvas = ui.drawn()
        assertSeen(canvas, 40f, "each with its own camera", ::ownCamera)
        val cards = canvas.cards()
        for (index in 0 until 4) {
            assertEquals(cards[0].shape()[index].x, cards[2].shape()[index].x, 0.01f, "the same shape, corner $index x")
            assertEquals(cards[0].shape()[index].y, cards[2].shape()[index].y, 0.01f, "the same shape, corner $index y")
        }

        ui.click("toggle")

        assertSeen(ui.drawn(), 40f, "and one camera again", ::sharedCamera)
    }

    @Test
    fun `the card in front of the camera looks as it would with a camera of its own that far away`() =
        row().using { ui ->
            ui.key(Key.Right)

            // The middle card's middle is where the camera is, so the scene changes nothing for it.
            val middle = ui.drawn().cards()[1]
            val xs = listOf(240f, 360f, 360f, 240f)
            val ys = listOf(110f, 110f, 190f, 190f)
            for (index in 0 until 4) {
                val alone = seen(xs[index], ys[index], 300f, 40f, 300f, 150f, distance)
                assertEquals(alone.x, middle.corners[index].x, 0.02f, "corner $index x")
                assertEquals(alone.y, middle.corners[index].y, 0.02f, "corner $index y")
            }
        }

    @Test
    fun `escape lays the row flat and it stops costing pictures`() = row().using { ui ->
        ui.key(Key.Right)
        assertEquals(3, ui.drawn().cards().size, "tilted first")

        ui.key(Key.Escape)

        assertTrue(ui.drawn().cards().isEmpty(), "flat again")
    }

    @Test
    fun `a tilted row that has stopped moving leaves a still screen still`() = row().using { ui ->
        ui.key(Key.Right)
        assertSeen(ui.drawn(), 40f, "tilted", ::sharedCamera)

        assertEquals(false, ui.render(), "nothing changed, so nothing is drawn again")
        assertEquals(false, ui.render(), "nor on the frame after")
    }

    @Test
    fun `pressing up brings the camera nearer and the row recedes more steeply`() = row().using { ui ->
        ui.key(Key.Right)
        val (farLeft, _, farRight) = ui.drawn().cards()

        ui.key(Key.Up)

        assertEquals(250f, ui.node("row").resolved.perspective)
        val canvas = ui.drawn()
        assertSeen(canvas, 40f, "with the camera nearer") { Triple(cameraX, cameraY, 250f) }
        val (nearLeft, _, nearRight) = canvas.cards()
        assertTrue(
            nearLeft.width() - nearRight.width() > farLeft.width() - farRight.width() + 10f,
            "the two ends differ more: ${nearLeft.width()} and ${nearRight.width()}",
        )
        assertEquals(false, ui.render(), "and once it has moved the screen is still again")
    }
}
