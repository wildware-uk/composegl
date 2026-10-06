package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.TextZoom
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A pan-and-zoom canvas that has gone to sleep still moves on the very first frame after something
 * starts it, by a whole frame, and every frame after plays as it always has.
 *
 * Each case is checked frame by frame against a reference: a camera of the same shape, started the
 * same way and advanced by exactly one frame each time — which is what the loop that waited on
 * every frame did. A frame at a time, so these drive a host by hand rather than through `uiTest`,
 * whose helpers settle after every event.
 *
 * The canvas is 400 by 300 onto a world of 0, 0 to 1000, 800, looking at the middle of it.
 */
class PanZoomFrameTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val pad = GamepadNavigator(focus)

    private val world = Rect(0f, 0f, 1000f, 800f)

    private fun camera() = PanZoomState(1f, 0.25f, 3f, world, Offset(500f, 400f))

    /** A camera nobody draws, measured as the canvas is: what one frame of the old loop did to it. */
    private fun reference() = camera().apply { measured(400f, 300f) }

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun frame() {
        host.settle(Constraints.atMost(400f, 300f), focus, nanos = clock)
        clock += FrameNanos
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    @Composable
    private fun Plane(camera: PanZoomState) {
        PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane")) {
            Box(Modifier.size(40f, 20f).worldPosition(500f, 400f, anchor = Alignment.Centre))
        }
    }

    /** Shows [camera], focuses its canvas, and runs long enough for anything idle to be asleep. */
    private fun show(camera: PanZoomState) {
        host.setContent { Plane(camera) }
        frames(3)
        assertTrue(focus.focusOn(host.root.find("plane")), "the canvas takes focus")
        frames(30)
        assertFalse(host.hasPendingWork, "the canvas asks for frames before anything has started")
    }

    /**
     * Steps a frame at a time while [reference] is still moving itself, checking every frame — the
     * first one included — that [camera] is where the reference is.
     */
    private fun assertAt(reference: PanZoomState, camera: PanZoomState, what: String) {
        assertEquals(reference.zoom, camera.zoom, 0.0001f, "zoom $what")
        assertEquals(reference.pan.x, camera.pan.x, 0.01f, "pan across $what")
        assertEquals(reference.pan.y, camera.pan.y, 0.01f, "pan down $what")
    }

    private fun assertPlaysLike(reference: PanZoomState, camera: PanZoomState, moving: () -> Boolean, frames: Int = 600) {
        var frame = 0
        while (moving()) {
            frame++
            frame()
            reference.advance(FrameNanos)
            assertAt(reference, camera, "on frame $frame")
            if (frame == frames) return
        }
        assertTrue(frame > 0, "the reference never moved")
    }

    @Test
    fun `a flick on a still canvas moves on the very next frame and plays as before`() {
        val camera = camera()
        show(camera)

        // 60 left every 20 milliseconds, twice: smoothed, the hand was going 1920 a second.
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(300f, 150f), timeMillis = 0L))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(240f, 150f), setOf(PointerButton.Primary), timeMillis = 20L))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(180f, 150f), setOf(PointerButton.Primary), timeMillis = 40L))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(180f, 150f), timeMillis = 40L))
        assertEquals(620f, camera.centre.x, 0.01f, "the world followed the hand")

        // It carries on into the right edge of the world, stops there and springs back.
        val reference = reference().apply {
            snapTo(camera.centre)
            fling(-1920f, 0f)
        }
        assertPlaysLike(reference, camera, moving = { reference.wantsFrames })
        frame()
        assertEquals(800f, camera.centre.x, 0.01f, "it came to rest against the edge")
        assertFalse(host.hasPendingWork, "and went back to sleep")
    }

    @Test
    fun `a stick pushed on a still canvas moves on the very next frame and plays as before`() {
        val camera = camera()
        show(camera)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 1f))
        val reference = reference().apply { stickX = 1f }
        assertPlaysLike(reference, camera, moving = { true }, frames = 10)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 0f))
        val stopped = camera.pan
        frames(2)
        assertEquals(stopped, camera.pan, "it stops when the stick is let go")
        assertFalse(host.hasPendingWork, "and goes back to sleep")
    }

    @Test
    fun `a trigger pulled on a still canvas zooms on the very next frame and plays as before`() {
        val camera = camera()
        show(camera)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 1f))
        val reference = reference().apply { zoomingIn = 1f }
        assertPlaysLike(reference, camera, moving = { true }, frames = 10)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 0f))
        val stopped = camera.zoom
        frames(2)
        assertEquals(stopped, camera.zoom, "it stops when the trigger is let go")
        assertFalse(host.hasPendingWork, "and goes back to sleep")
    }

    @Test
    fun `animateTo on a still canvas moves on the very next frame and plays as before`() {
        val camera = camera()
        show(camera)

        camera.animateTo(Offset(300f, 250f), zoom = 2f)
        val reference = reference().apply { animateTo(Offset(300f, 250f), zoom = 2f) }
        assertPlaysLike(reference, camera, moving = { reference.wantsFrames })

        frame()
        assertFalse(camera.isAnimating, "it arrived when the reference did")
        assertFalse(host.hasPendingWork, "and went back to sleep")
    }

    @Test
    fun `a camera still moving when its canvas leaves carries on a frame at a time when it comes back`() {
        // A state outlives its canvas: a tab put away mid-animation and brought back later.
        val camera = camera()
        var shown by mutableStateOf(true)
        host.setContent { if (shown) Plane(camera) }
        frames(3)

        camera.animateTo(Offset(300f, 250f), zoom = 2f, durationMillis = 2000)
        val reference = reference().apply { animateTo(Offset(300f, 250f), zoom = 2f, durationMillis = 2000) }
        assertPlaysLike(reference, camera, moving = { true }, frames = 10)

        // The frame it is put away on still steps it, before the canvas goes.
        shown = false
        frame()
        reference.advance(FrameNanos)
        assertAt(reference, camera, "on the frame the canvas went")
        frames(60)
        assertAt(reference, camera, "a second after the canvas went")
        assertTrue(camera.isAnimating, "it was put away part way")

        // The frame it comes back on composes it; the next one is its first step, a frame long, not
        // the second it was away.
        shown = true
        frame()
        assertAt(reference, camera, "on the frame the canvas came back")
        assertPlaysLike(reference, camera, moving = { reference.wantsFrames })
        frame()
        assertFalse(camera.isAnimating, "it arrived when the reference did")
        assertFalse(host.hasPendingWork, "and went back to sleep")
    }

    @Test
    fun `a camera moving when its canvas moves to another place in the tree carries on a frame at a time`() {
        // A minimap opening into the full map: the canvas is composed in its new place and disposed
        // in its old one on the same frame, and the camera carries on as if nothing happened.
        val camera = camera()
        var place by mutableStateOf(0)
        host.setContent { key(place) { Plane(camera) } }
        frames(3)

        camera.animateTo(Offset(300f, 250f), zoom = 2f, durationMillis = 2000)
        val reference = reference().apply { animateTo(Offset(300f, 250f), zoom = 2f, durationMillis = 2000) }
        assertPlaysLike(reference, camera, moving = { true }, frames = 10)

        place = 1
        assertPlaysLike(reference, camera, moving = { true }, frames = 1)
        assertSame(host.clocks, camera.clocks, "the canvas in its new place kept the camera's clocks")
        assertPlaysLike(reference, camera, moving = { reference.wantsFrames })
        frame()
        assertFalse(camera.isAnimating, "it arrived when the reference did")
        assertFalse(host.hasPendingWork, "and went back to sleep")
    }

    @Test
    fun `a still canvas moved to another place in the tree still moves on the very next frame`() {
        val camera = camera()
        var place by mutableStateOf(0)
        host.setContent { key(place) { Plane(camera) } }
        frames(3)
        place = 1
        frames(3)
        assertFalse(host.hasPendingWork, "the moved canvas asks for frames before anything has started")

        camera.animateTo(Offset(300f, 250f), zoom = 2f)
        val reference = reference().apply { animateTo(Offset(300f, 250f), zoom = 2f) }
        assertPlaysLike(reference, camera, moving = { reference.wantsFrames })
        frame()
        assertFalse(host.hasPendingWork, "and went back to sleep")
    }

    @Test
    fun `a trigger let go while interface time is stopped still leaves the text sharp`() {
        val camera = camera()
        show(camera)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 1f))
        frames(20)
        host.clocks.stop(Clock.Ui)
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 0f))
        frames(3)
        host.clocks.start(Clock.Ui)
        frames(10)

        assertTrue(camera.zoom > 1.2f, "the trigger zoomed in: ${camera.zoom}")
        assertEquals(TextZoom.snap(camera.zoom), camera.textZoom, "text is made again for where the zoom stopped")
        assertFalse(host.hasPendingWork, "and the canvas went back to sleep")
    }

    private companion object {
        const val FrameNanos = 16_666_667L
    }
}
