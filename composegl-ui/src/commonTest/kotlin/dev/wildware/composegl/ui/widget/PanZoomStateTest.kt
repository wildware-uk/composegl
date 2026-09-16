package dev.wildware.composegl.ui.widget

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The camera's own arithmetic, with no screen: where a world point is seen, what a zoom about a
 * point keeps still, where the edges hold it, and what a flick, a spring and an ease do to it over
 * time.
 *
 * The view is 400 by 300 onto a world of 0, 0 to 1000, 800 throughout.
 */
class PanZoomStateTest {

    private fun camera(
        zoom: Float = 1f,
        minZoom: Float = 0.25f,
        maxZoom: Float = 3f,
        bounds: Rect? = Rect(0f, 0f, 1000f, 800f),
        centre: Offset? = null,
    ) = PanZoomState(zoom, minZoom, maxZoom, bounds, centre).also { it.measured(400f, 300f) }

    private fun near(expected: Float, actual: Float, message: String? = null) =
        assertTrue(abs(expected - actual) < 0.5f, message ?: "expected $expected but was $actual")

    /** A second of frames at sixty a second. */
    private fun PanZoomState.seconds(count: Float) = repeat((count * 60f).toInt()) { advance(16_666_667L) }

    @Test
    fun `a world point and where it is seen are two ways round of the same line`() {
        val camera = camera(zoom = 2f, centre = Offset(500f, 400f))

        val seen = camera.worldToScreen(Offset(500f, 400f))
        assertEquals(Offset(200f, 150f), seen, "the centre is in the middle of the view")
        assertEquals(Offset(500f, 400f), camera.screenToWorld(seen))
    }

    @Test
    fun `it starts looking at the middle of the world`() {
        assertEquals(Offset(500f, 400f), camera().centre)
    }

    @Test
    fun `zooming about a point keeps what is under it under it`() {
        val camera = camera(centre = Offset(500f, 400f))
        val at = Offset(320f, 80f)
        val under = camera.screenToWorld(at)

        camera.zoomAbout(at, 2.5f)

        assertEquals(2.5f, camera.zoom)
        near(under.x, camera.screenToWorld(at).x)
        near(under.y, camera.screenToWorld(at).y)
    }

    @Test
    fun `the zoom stays between its two ends`() {
        val camera = camera()
        camera.zoomAbout(Offset.Zero, 99f)
        assertEquals(3f, camera.zoom)
        camera.zoomAbout(Offset.Zero, 0.001f)
        assertEquals(0.25f, camera.zoom)
    }

    @Test
    fun `panning stops where the edge of the world meets the edge of the view`() {
        val camera = camera()

        camera.panBy(10_000f, 10_000f)
        near(0f, camera.pan.x, "the world's corner cannot come inside the view's")
        near(0f, camera.pan.y)
        near(0f, camera.visibleWorld.left)
        near(400f, camera.visibleWorld.right)

        camera.panBy(-10_000f, -10_000f)
        near(1000f, camera.visibleWorld.right, "and stops at the far corner")
        near(800f, camera.visibleWorld.bottom)
    }

    @Test
    fun `a world smaller than the view is held in the middle of it`() {
        val camera = PanZoomState(zoom = 1f, bounds = Rect(0f, 0f, 100f, 100f)).also { it.measured(400f, 300f) }

        camera.panBy(50f, 50f)

        assertEquals(Offset(150f, 100f), camera.pan)
    }

    @Test
    fun `a world with no bounds pans as far as it is asked`() {
        val camera = PanZoomState(bounds = null).also { it.measured(400f, 300f) }

        camera.panBy(-5000f, 0f)

        assertEquals(-5000f, camera.pan.x)
    }

    @Test
    fun `a drag past the edge gives a little and springs back when it is let go`() {
        val camera = camera()
        camera.holding(true)

        repeat(20) { camera.dragBy(40f, 0f) }
        assertTrue(camera.pan.x > 0f, "it should have pulled past the edge")
        assertTrue(camera.pan.x <= PanZoomState.Stretch, "but no further than the stretch: ${camera.pan.x}")

        camera.holding(false)
        camera.seconds(1f)

        near(0f, camera.pan.x, "it should have come back to the edge, was ${camera.pan.x}")
    }

    @Test
    fun `a flick carries on and slows to a stop`() {
        val camera = camera(centre = Offset(500f, 400f))
        val from = camera.pan.x

        camera.fling(-600f, 0f)
        assertTrue(camera.isFlinging)
        camera.seconds(0.25f)
        assertTrue(camera.pan.x < from - 50f, "it should have carried on, was ${camera.pan.x}")

        camera.seconds(2f)
        assertFalse(camera.isFlinging, "and stopped by now")
    }

    @Test
    fun `a flick into the edge stops there`() {
        val camera = camera()
        camera.fling(4000f, 0f)
        camera.seconds(1f)

        near(0f, camera.pan.x)
        assertFalse(camera.isFlinging)
    }

    @Test
    fun `animateTo eases to where it was asked and stops there`() {
        val camera = camera(centre = Offset(500f, 400f))

        camera.animateTo(Offset(800f, 600f), zoom = 2f, durationMillis = 300)
        assertTrue(camera.isAnimating)
        camera.seconds(0.1f)
        assertTrue(camera.centre.x > 500f && camera.centre.x < 800f, "part way there: ${camera.centre}")

        camera.seconds(0.5f)
        assertFalse(camera.isAnimating)
        assertEquals(2f, camera.zoom)
        near(800f, camera.centre.x)
        near(600f, camera.centre.y)
    }

    @Test
    fun `a drag takes over from an animation rather than fighting it`() {
        val camera = camera(centre = Offset(500f, 400f))
        camera.animateTo(Offset(900f, 700f))
        camera.seconds(0.1f)

        camera.holding(true)
        camera.dragBy(-10f, 0f)

        assertFalse(camera.isAnimating)
    }

    @Test
    fun `fit shows the whole world and reset puts it back`() {
        val camera = camera(zoom = 2f, minZoom = 0.1f, centre = Offset(100f, 100f))

        camera.fit(animate = false)

        assertEquals(0.375f, camera.zoom, "as far out as the taller side needs: 300 ÷ 800")
        assertTrue(camera.visibleWorld.left <= 0f && camera.visibleWorld.right >= 1000f, "${camera.visibleWorld}")

        camera.reset(animate = false)

        assertEquals(2f, camera.zoom)
        assertEquals(Offset(100f, 100f), camera.centre)
    }

    @Test
    fun `text is made again at a step once the camera settles and not while a hand is on it`() {
        val camera = camera()

        camera.holding(true)
        camera.zoomAbout(Offset(200f, 150f), 2f)
        assertEquals(1f, camera.textZoom, "glyphs are stretched while the pinch is moving")

        camera.holding(false)
        assertEquals(2f, camera.textZoom)

        camera.zoomAbout(Offset(200f, 150f), 2.2f)
        assertEquals(2f, camera.textZoom, "2.2 is nearest the two step")
    }

    @Test
    fun `a view that changes size keeps looking at the same place`() {
        val camera = camera(centre = Offset(400f, 400f))

        camera.measured(600f, 400f)

        assertEquals(Offset(400f, 400f), camera.centre)
    }

    @Test
    fun `the stick pans and the triggers zoom`() {
        val camera = camera(centre = Offset(500f, 400f))

        camera.stickX = 1f
        camera.seconds(0.5f)
        assertTrue(camera.centre.x > 600f, "a stick held right should have crossed the map: ${camera.centre}")

        camera.stickX = 0f
        camera.zoomingIn = 1f
        camera.seconds(0.5f)
        assertTrue(camera.zoom > 1.3f, "the right trigger should have zoomed in: ${camera.zoom}")

        camera.zoomingIn = 0f
        camera.zoomingOut = 1f
        camera.seconds(1f)
        assertTrue(camera.zoom < 1.1f, "and the left one back out: ${camera.zoom}")
    }

    @Test
    fun `revealing a node off the edge eases it into view`() {
        val camera = camera(centre = Offset(500f, 400f))

        val corner = camera.screenToWorld(Offset(460f, 140f))
        assertTrue(camera.reveal(Rect(380f, 100f, 460f, 140f)), "it hangs off the right of the view")
        camera.seconds(0.5f)

        val seen = camera.worldToScreen(corner)
        assertTrue(seen.x <= 400f, "the far corner should be on the screen now: $seen")
        assertFalse(camera.reveal(Rect(50f, 50f, 100f, 100f)), "one already in view moves nothing")
    }

    @Test
    fun `two fingers zoom about their middle and pan with it`() {
        val camera = camera(centre = Offset(500f, 400f))
        camera.holding(true)
        val under = camera.screenToWorld(Offset(200f, 150f))

        camera.pinch(200f, 150f, 220f, 150f, factor = 2f)

        assertEquals(2f, camera.zoom)
        val now = camera.worldToScreen(under)
        near(220f, now.x, "what was between the fingers followed them: $now")
        near(150f, now.y)
    }
}
