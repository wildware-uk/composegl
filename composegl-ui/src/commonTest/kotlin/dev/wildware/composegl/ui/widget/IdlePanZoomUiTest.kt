package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.TextZoom
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A screen with a pan-and-zoom canvas on it that nobody is touching asks for no frames.
 *
 * The canvas has one loop for everything that moves the camera on its own: a flick, the spring back
 * from past an edge, a held stick, held triggers and `animateTo`. A loop that waited on every frame
 * in case one of those started kept the Compose recomposer awake on every frame of a still screen.
 * These pin that it sleeps, that each of them wakes it, and that it sleeps again once they stop.
 *
 * The screen is 400 by 300 onto a world of 0, 0 to 1000, 800, looking at the middle of it.
 */
class IdlePanZoomUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val world = Rect(0f, 0f, 1000f, 800f)

    private fun state(centre: Offset = Offset(500f, 400f)) = PanZoomState(1f, 0.25f, 3f, world, centre)

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    @Composable
    private fun Plane(camera: PanZoomState) {
        PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane")) {
            Box(Modifier.size(40f, 20f).worldPosition(500f, 400f, anchor = Alignment.Centre).testTag("node"))
        }
    }

    private fun UiTest.assertAsleep(what: String) =
        assertFalse(host.hasPendingWork, "$what still asks for frames:\n" + dump())

    private fun UiTest.trigger(axis: GamepadAxis, value: Float) =
        input.onGamepad(GamepadEvent.Axis(GamepadId.First, axis, value))

    private fun near(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 0.5f, "$message: expected $expected but was $actual")

    @Test
    fun `a still canvas asks for no frames`() {
        val ui = open { Plane(state()) }

        ui.assertAsleep("a canvas nobody has touched")
        ui.advanceBy(500)
        ui.assertAsleep("half a second later it")
    }

    @Test
    fun `a still lazy canvas asks for no frames`() {
        val tiles = List(100) { Rect.of((it % 10) * 100f, (it / 10) * 80f, 90f, 70f) }
        val ui = open {
            LazyPanZoomCanvas(tiles, area = { it }, state = state(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize())
            }
        }

        ui.assertAsleep("a lazy canvas nobody has touched")
    }

    @Test
    fun `a flick wakes it and it sleeps again once the camera stops`() {
        val camera = state()
        val ui = open { Plane(camera) }

        // 60 left and up, flicked: the world carries on well past where the hand let go.
        ui.flick(from = Offset(300f, 220f), to = Offset(240f, 160f))

        assertTrue(camera.centre.x > 600f, "the flick carried on past the hand: ${camera.centre}")
        assertFalse(camera.isFlinging, "and has come to rest")
        ui.assertAsleep("a canvas whose fling has played out")
    }

    @Test
    fun `a drag past an edge springs back and then sleeps`() {
        // Looking at the left edge of the world, so a drag to the right pulls past it.
        val camera = state(centre = Offset(200f, 400f))
        val ui = open { Plane(camera) }
        near(0f, camera.visibleWorld.left, "the view starts at the left edge")

        ui.press(Offset(100f, 150f))
        ui.dragTo(Offset(180f, 150f))
        assertTrue(camera.visibleWorld.left < -10f, "the hand pulled it past the edge: ${camera.visibleWorld}")
        ui.release()

        near(0f, camera.visibleWorld.left, "it sprang back to the edge")
        ui.assertAsleep("a canvas back inside its edges")
    }

    @Test
    fun `a held stick pans and it sleeps again once let go`() {
        val camera = state()
        val ui = open { Plane(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()
        ui.assertAsleep("a focused canvas with the stick at rest")

        ui.holdStick(1f, 0f, millis = 300)

        assertTrue(camera.centre.x > 560f, "the stick panned: ${camera.centre}")
        ui.assertAsleep("a canvas whose stick has been let go")
    }

    @Test
    fun `a held trigger zooms and once let go the text settles and it sleeps`() {
        val camera = state()
        val ui = open { Plane(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()

        ui.trigger(GamepadAxis.RightTrigger, 0.6f)
        ui.advanceBy(300)
        ui.trigger(GamepadAxis.RightTrigger, 0f)
        ui.settle()

        assertTrue(camera.zoom > 1.1f, "the trigger zoomed in: ${camera.zoom}")
        assertEquals(TextZoom.snap(camera.zoom), camera.textZoom, "text is made again for where the zoom stopped")
        ui.assertAsleep("a canvas whose trigger has been let go")
    }

    @Test
    fun `animateTo wakes it and it sleeps again once it arrives`() {
        val camera = state()
        val ui = open { Plane(camera) }

        camera.animateTo(Offset(300f, 300f), zoom = 2f)
        ui.advanceBy(600)

        near(300f, camera.centre.x, "it arrived across")
        near(300f, camera.centre.y, "and down")
        assertEquals(2f, camera.zoom)
        assertFalse(camera.isAnimating, "and says it has arrived")
        ui.assertAsleep("a canvas whose animation has arrived")
    }

    @Test
    fun `a canvas handed a new state moves the new one`() {
        val first = state()
        val second = state()
        var current by mutableStateOf(first)
        val ui = open { Plane(current) }

        current = second
        ui.settle()
        second.animateTo(Offset(300f, 300f))
        ui.advanceBy(600)

        near(300f, second.centre.x, "the new state arrived")
        assertEquals(Offset(500f, 400f), first.centre, "the old state was not moved")
        ui.assertAsleep("after the new state's animation the screen")
    }
}
