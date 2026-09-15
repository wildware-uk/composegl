package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.perspective
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the draw pass hands a canvas for tilted nodes under a `Modifier.perspective`. */
class PerspectiveDrawTest {

    private val red = Colour.rgb(0xFF0000)

    private class Untilting(canvas: RecordingCanvas) : UiCanvas by canvas {
        override val tiltsLayers: Boolean get() = false
    }

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

    private fun recorded(content: @Composable () -> Unit): RecordingCanvas =
        RecordingCanvas().also { drawInto(it, content) }

    /** Turned [degrees] about y around ([pivotX], [pivotY]), seen from a camera at ([cameraX], [cameraY]). */
    private fun shared(pivotX: Float, pivotY: Float, cameraX: Float, cameraY: Float, distance: Float, degrees: Float) =
        Matrix4.translation(cameraX, cameraY) * Matrix4.perspective(distance) *
            Matrix4.translation(pivotX - cameraX, pivotY - cameraY) * Matrix4.rotationY(degrees) *
            Matrix4.translation(-pivotX, -pivotY)

    /** The same turn, seen from the node's own default camera. */
    private fun own(pivotX: Float, pivotY: Float, degrees: Float) =
        Matrix4.translation(pivotX, pivotY) * Matrix4.perspective(8f * CameraDistanceUnit) *
            Matrix4.rotationY(degrees) * Matrix4.translation(-pivotX, -pivotY)

    @Test
    fun `a tilted child is seen from the camera its parent put in front of the parent's middle`() {
        val canvas = recorded {
            Box(Modifier.size(300f, 100f).perspective(500f)) {
                Box(Modifier.offset(20f, 30f).size(40f).rotate3d(y = 50f).background(red))
            }
        }

        val tilt = canvas.only<DrawCall.TiltedLayer>().single()
        assertEquals(shared(40f, 50f, 150f, 50f, 500f, 50f), tilt.transform)
    }

    @Test
    fun `a child's own cameraDistance is ignored in favour of the shared camera`() {
        // A child asking for a camera of its own is still in the parent's scene.
        val canvas = recorded {
            Box(Modifier.size(100f).perspective(300f)) {
                Box(Modifier.size(100f).rotate3d(y = 30f, cameraDistance = 2f).background(red))
            }
        }

        assertEquals(shared(50f, 50f, 50f, 50f, 300f, 30f), canvas.only<DrawCall.TiltedLayer>().single().transform)
    }

    @Test
    fun `a grandchild joins the scene too`() {
        val canvas = recorded {
            Box(Modifier.size(200f).perspective(400f)) {
                Box(Modifier.offset(100f, 0f).size(100f)) {
                    Box(Modifier.size(50f).rotate3d(y = 20f).background(red))
                }
            }
        }

        assertEquals(shared(125f, 25f, 100f, 100f, 400f, 20f), canvas.only<DrawCall.TiltedLayer>().single().transform)
    }

    @Test
    fun `the origin moves the camera to that point of the parent`() {
        val canvas = recorded {
            Box(Modifier.size(300f, 100f).perspective(500f, origin = Alignment.CentreStart)) {
                Box(Modifier.size(40f).rotate3d(y = 50f).background(red))
            }
        }

        assertEquals(shared(20f, 20f, 0f, 50f, 500f, 50f), canvas.only<DrawCall.TiltedLayer>().single().transform)
    }

    @Test
    fun `a nearer perspective wins over an outer one`() {
        val canvas = recorded {
            Box(Modifier.size(400f).perspective(900f)) {
                Box(Modifier.offset(200f, 200f).size(100f).perspective(250f, origin = Alignment.TopStart)) {
                    Box(Modifier.size(40f).rotate3d(y = 45f).background(red))
                }
            }
        }

        assertEquals(
            shared(220f, 220f, 200f, 200f, 250f, 45f),
            canvas.only<DrawCall.TiltedLayer>().single().transform,
        )
    }

    @Test
    fun `a sibling drawn after the scene goes back to its own camera`() {
        val canvas = recorded {
            Column {
                Box(Modifier.size(100f).perspective(200f)) {
                    Box(Modifier.size(100f).rotate3d(y = 30f).background(red))
                }
                Box(Modifier.size(100f).rotate3d(y = 30f).background(red))
            }
        }

        val tilts = canvas.only<DrawCall.TiltedLayer>()
        assertEquals(2, tilts.size)
        assertEquals(shared(50f, 50f, 50f, 50f, 200f, 30f), tilts[0].transform, "inside the scene")
        assertEquals(own(50f, 150f, 30f), tilts[1].transform, "after it, the camera was handed back")
    }

    @Test
    fun `a tilted node is seen by the camera outside it not by its own perspective`() {
        val canvas = recorded {
            Box(Modifier.size(100f).rotate3d(y = 30f).perspective(200f).background(red))
        }

        assertEquals(own(50f, 50f, 30f), canvas.only<DrawCall.TiltedLayer>().single().transform)
    }

    @Test
    fun `a tilted node flattens its inside so a tilt in there keeps its own camera`() {
        val canvas = recorded {
            Box(Modifier.size(300f).perspective(400f)) {
                Box(Modifier.size(200f).rotate3d(x = 20f)) {
                    Box(Modifier.size(40f).rotate3d(y = -30f).background(red))
                }
            }
        }

        val (inner, outer) = canvas.only<DrawCall.TiltedLayer>()
        assertEquals(Rect.of(0f, 0f, 40f, 40f), inner.bounds)
        assertEquals(own(20f, 20f, -30f), inner.transform, "not the outer scene's camera")
        val outerExpected = Matrix4.translation(150f, 150f) * Matrix4.perspective(400f) *
            Matrix4.translation(-50f, -50f) * Matrix4.rotationX(20f) * Matrix4.translation(-100f, -100f)
        assertEquals(outerExpected, outer.transform, "while the outer card is in it")
    }

    @Test
    fun `a perspective on a tilted node applies inside its own picture`() {
        val canvas = recorded {
            Box(Modifier.size(200f).rotate3d(x = 20f).perspective(150f, origin = Alignment.TopStart)) {
                Box(Modifier.offset(60f, 60f).size(40f).rotate3d(y = 40f).background(red))
            }
        }

        val (inner, _) = canvas.only<DrawCall.TiltedLayer>()
        assertEquals(shared(80f, 80f, 0f, 0f, 150f, 40f), inner.transform)
    }

    @Test
    fun `a perspective on a tilted node away from the corner puts its camera on that node`() {
        val canvas = recorded {
            Box(Modifier.offset(100f, 50f).size(200f).rotate3d(x = 20f).perspective(150f)) {
                Box(Modifier.offset(60f, 60f).size(40f).rotate3d(y = 40f).background(red))
            }
        }

        val (inner, _) = canvas.only<DrawCall.TiltedLayer>()
        assertEquals(shared(180f, 130f, 200f, 150f, 150f, 40f), inner.transform)
    }

    @Test
    fun `a scaled box between the camera and the card keeps the card in the scene`() {
        val canvas = recorded {
            Box(Modifier.size(300f).perspective(400f)) {
                Box(Modifier.offset(150f, 0f).size(100f).scale(0.5f)) {
                    Box(Modifier.size(100f).rotate3d(y = 30f).background(red))
                }
            }
        }

        // The card is drawn into the scaled box's picture at its laid-out place, seen by the camera.
        assertEquals(shared(200f, 50f, 150f, 150f, 400f, 30f), canvas.only<DrawCall.TiltedLayer>().single().transform)
    }

    @Test
    fun `an untilted child under a camera draws plainly`() {
        val canvas = recorded {
            Box(Modifier.size(100f).perspective(200f)) { Box(Modifier.size(50f).background(red)) }
        }

        assertTrue(canvas.only<DrawCall.TiltedLayer>().isEmpty())
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "a camera takes no picture")
        assertEquals(Rect.of(0f, 0f, 50f, 50f), canvas.only<DrawCall.Rectangle>().single().rect)
    }

    @Test
    fun `a canvas that cannot tilt draws the scene flat`() {
        val recording = RecordingCanvas()
        drawInto(Untilting(recording)) {
            Box(Modifier.size(200f).perspective(300f)) {
                Box(Modifier.size(60f).rotate3d(y = 50f).background(red))
                Box(Modifier.offset(100f, 0f).size(60f).rotate3d(y = 50f).background(red))
            }
        }

        assertTrue(recording.only<DrawCall.TiltedLayer>().isEmpty())
        assertEquals(
            listOf(Rect.of(0f, 0f, 60f, 60f), Rect.of(100f, 0f, 60f, 60f)),
            recording.only<DrawCall.Rectangle>().map { it.rect },
        )
    }
}
