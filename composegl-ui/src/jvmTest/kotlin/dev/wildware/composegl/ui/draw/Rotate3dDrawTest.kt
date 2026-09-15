package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the draw pass hands a canvas for a node turned in depth, and what it does when it cannot. */
class Rotate3dDrawTest {

    private val red = Colour.rgb(0xFF0000)

    /** A backend that makes, turns and slants pictures, but has never been taught depth. */
    private class Untilting(canvas: RecordingCanvas) : UiCanvas by canvas {
        override val tiltsLayers: Boolean get() = false
    }

    /** Composes [content], lays it out in 500 square, and draws one frame into [canvas]. */
    private fun drawInto(canvas: UiCanvas, content: @Composable () -> Unit) {
        val host = UiHost()
        try {
            host.setContent(content)
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.atMost(500f, 500f))
            DrawPass(canvas).draw(host.root)
        } finally {
            host.dispose()
        }
    }

    private fun assertCorners(expected: List<Offset>, actual: List<Offset>, because: String) {
        for (index in 0 until 4) {
            assertEquals(expected[index].x, actual[index].x, 0.01f, "$because: corner $index x")
            assertEquals(expected[index].y, actual[index].y, 0.01f, "$because: corner $index y")
        }
    }

    @Test
    fun `a tilted node is one picture put down through its transform`() {
        val canvas = RecordingCanvas()
        drawInto(canvas) { Box(Modifier.size(40f).rotate3d(y = 50f).background(red)) }

        val tilt = canvas.only<DrawCall.TiltedLayer>().single()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), tilt.bounds)
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "no flat composite as well")
        assertTrue(canvas.only<DrawCall.LayerOnto>().isEmpty(), "and no four corners either")
        val expected = Matrix4.translation(20f, 20f) * Matrix4.perspective(8f * CameraDistanceUnit) *
            Matrix4.rotationY(50f) * Matrix4.translation(-20f, -20f)
        assertEquals(expected, tilt.transform, "about the middle, seen from the default camera")
    }

    @Test
    fun `a z turn alone lands on the same corners as a flat rotate`() {
        val canvas = RecordingCanvas()
        drawInto(canvas) { Box(Modifier.size(40f).rotate3d(z = 90f).background(red)) }

        assertCorners(
            listOf(Offset(40f, 0f), Offset(40f, 40f), Offset(0f, 40f), Offset(0f, 0f)),
            canvas.only<DrawCall.TiltedLayer>().single().corners,
            "a quarter clockwise about the middle",
        )
    }

    @Test
    fun `the origin is the edge a panel swings on`() {
        val canvas = RecordingCanvas()
        drawInto(canvas) {
            Box(Modifier.size(100f, 60f).rotate3d(y = 60f, origin = Alignment.CentreStart).background(red))
        }

        val tilt = canvas.only<DrawCall.TiltedLayer>().single()
        assertEquals(0f, tilt.corners[0].x, 0.001f, "the hinge edge does not move")
        assertEquals(0f, tilt.corners[0].y, 0.001f)
        assertEquals(1f, tilt.depths[0], 0.0001f, "and stays on its own plane")
        assertTrue(tilt.corners[1].x < 50f, "the free edge swings away and in: ${tilt.corners[1].x}")
    }

    @Test
    fun `a closer camera makes the near edge bigger`() {
        val far = RecordingCanvas()
        drawInto(far) { Box(Modifier.size(100f).rotate3d(y = 40f, cameraDistance = 50f).background(red)) }
        val near = RecordingCanvas()
        drawInto(near) { Box(Modifier.size(100f).rotate3d(y = 40f, cameraDistance = 2f).background(red)) }

        fun nearEdge(canvas: RecordingCanvas) = canvas.only<DrawCall.TiltedLayer>().single().corners
            .let { it[3].y - it[0].y }
        assertTrue(nearEdge(near) > nearEdge(far) + 5f, "near ${nearEdge(near)} against far ${nearEdge(far)}")
    }

    @Test
    fun `a slant and a flat turn ride the same matrix as the tilt`() {
        val canvas = RecordingCanvas()
        drawInto(canvas) {
            Box(Modifier.size(40f).rotate(30f).skew(x = -10f).rotate3d(x = 20f).background(red))
        }

        assertTrue(canvas.only<DrawCall.LayerOnto>().isEmpty(), "no separate slant")
        val tilt = canvas.only<DrawCall.TiltedLayer>().single()
        val turn = Matrix4.translation(20f, 20f) * Matrix4.rotationZ(30f) * Matrix4.translation(-20f, -20f)
        val depth = Matrix4.translation(20f, 20f) * Matrix4.perspective(8f * CameraDistanceUnit) *
            Matrix4.rotationX(20f) * Matrix4.translation(-20f, -20f)
        val slope = tan(-10f * kotlin.math.PI.toFloat() / 180f)
        val slant = Matrix4.translation(20f, 20f) * Matrix4.shear(slope, 0f) * Matrix4.translation(-20f, -20f)
        val expected = turn * depth * slant
        for (index in 0 until 4) {
            val corner = listOf(Offset(0f, 0f), Offset(40f, 0f), Offset(40f, 40f), Offset(0f, 40f))[index]
            val point = expected.map(corner.x, corner.y)
            assertEquals(point.x, tilt.corners[index].x, 0.01f, "slanted, then tilted, then turned: corner $index x")
            assertEquals(point.y, tilt.corners[index].y, 0.01f, "corner $index y")
        }
    }

    @Test
    fun `a glow reaching past the node is tilted with it about the node's own hinge`() {
        val glow = ShaderEffect(ShaderSource("glow", "void main() { }"), bleed = 6f)
        val canvas = RecordingCanvas()
        drawInto(canvas) {
            Box(Modifier.size(40f).effect(glow).rotate3d(y = 30f, origin = Alignment.CentreStart).background(red))
        }

        val tilt = canvas.only<DrawCall.TiltedLayer>().single()
        assertEquals(Rect(-6f, -6f, 46f, 46f), tilt.bounds, "the capture grows by the bleed")
        // The node's left edge, not the wider capture's: a bleed must not move the hinge.
        val expected = Matrix4.translation(0f, 20f) * Matrix4.perspective(8f * CameraDistanceUnit) *
            Matrix4.rotationY(30f) * Matrix4.translation(0f, -20f)
        assertEquals(expected, tilt.transform, "but the camera still looks at the node's own hinge")
    }

    @Test
    fun `a canvas that cannot tilt draws the node flat with no picture`() {
        val recording = RecordingCanvas()
        drawInto(Untilting(recording)) { Box(Modifier.size(40f).rotate3d(y = 50f).background(red)) }

        assertTrue(recording.only<DrawCall.TiltedLayer>().isEmpty(), "no tilt")
        assertTrue(recording.only<DrawCall.Layer>().isEmpty(), "and no picture taken for nothing")
        assertEquals(
            Rect.of(0f, 0f, 40f, 40f),
            recording.only<DrawCall.Rectangle>().single { it.colour == red }.rect,
            "present, the right size and flat",
        )
    }

    @Test
    fun `a canvas that cannot tilt still slants a node that also asks for a slant`() {
        val recording = RecordingCanvas()
        drawInto(Untilting(recording)) { Box(Modifier.size(40f).skew(x = 45f).rotate3d(y = 50f).background(red)) }

        assertTrue(recording.only<DrawCall.TiltedLayer>().isEmpty(), "no tilt")
        assertEquals(1, recording.only<DrawCall.LayerOnto>().size, "but the slant it can draw is drawn")
    }
}
