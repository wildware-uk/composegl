package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The camera the renderer draws a pan-and-zoom canvas through: positions and sizes multiplied as
 * each quad is queued, so a zoomed plane is sharp, costs no extra draw call, and still clips, layers
 * and lends its projection correctly.
 */
class RenderCanvasTransformTest {

    private val device = RecordingDevice()
    private val design = Viewport.oneToOne(Size(400f, 300f))

    private fun canvas() = RenderCanvas(device)

    private fun frame(canvas: RenderCanvas = canvas(), viewport: Viewport = design, block: RenderCanvas.() -> Unit): RenderCanvas {
        canvas.begin(viewport)
        canvas.block()
        canvas.end()
        return canvas
    }

    private fun near(expected: Float, actual: Float) = abs(expected - actual) < 0.01f

    @Test
    fun `a box under a transform is queued where the camera puts it`() {
        frame {
            pushTransform(2f, 40f, 10f)
            rect(Rect(10f, 10f, 20f, 20f), Colour.Red)
            popTransform()
        }

        // 10 × 2 + 40 = 60 across, and 10 × 2 + 10 = 30 down, which is 270 up a 300 tall design.
        // Both grown by the one-pixel soft edge the shader needs.
        val draw = device.draws.single()
        assertTrue(near(59f, draw.at(0, 0)), "left was ${draw.at(0, 0)}")
        assertTrue(near(249f, draw.at(0, 1)), "bottom was ${draw.at(0, 1)}")
        val right = (0 until 4).maxOf { draw.at(it, 0) }
        val top = (0 until 4).maxOf { draw.at(it, 1) }
        assertTrue(near(81f, right), "right was $right")
        assertTrue(near(271f, top), "top was $top")
    }

    @Test
    fun `a plane of nodes each pushing its own transform is still one draw call`() {
        val canvas = frame {
            repeat(50) { at ->
                pushTransform(2f, at.toFloat(), 0f)
                rect(Rect.of(0f, 0f, 4f, 4f), Colour.Red)
                popTransform()
            }
        }

        assertEquals(1, canvas.drawCalls, "a transform is arithmetic, not a batch boundary")
    }

    @Test
    fun `a clip pushed inside the camera is scissored where it lands`() {
        frame {
            pushTransform(2f, 0f, 0f)
            pushClip(Rect(10f, 20f, 50f, 40f))
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popClip()
            popTransform()
        }

        // Twice the size: 20 to 100 across, 40 to 80 down, and the scissor counts up from the bottom.
        assertTrue("scissor(20, 220, 80, 40)" in device.calls, device.calls.toString())
    }

    @Test
    fun `a layer inside the camera is taken at the size it is seen`() {
        var picture: TextureHandleOrNull = null
        frame {
            pushTransform(3f, 0f, 0f)
            picture = layer(Rect.of(0f, 0f, 20f, 10f)) { rect(Rect.of(0f, 0f, 20f, 10f), Colour.Blue) }
            drawLayer(assertNotNull(picture), Rect.of(0f, 0f, 20f, 10f))
            popTransform()
        }

        assertEquals(1, device.named("offscreen").size)
        assertTrue("60x30" in device.named("offscreen").single(), device.named("offscreen").toString())
    }

    @Test
    fun `the projection lent to a game carries the camera`() {
        var lent: RenderFrame? = null
        frame {
            pushTransform(2f, 40f, 10f)
            raw { lent = it as RenderFrame }
            popTransform()
        }

        val projection = assertNotNull(lent).projection
        // A world point at (10, 10) is drawn at (60, 30): in clip space that is x = 60 × 2 ÷ 400 − 1.
        val x = 10f * projection[0] + projection[12]
        val y = (300f - 10f) * projection[5] + projection[13]
        assertTrue(near(60f * 2f / 400f - 1f, x), "x came out $x")
        assertTrue(near((300f - 30f) * 2f / 300f - 1f, y), "y came out $y")
    }

    @Test
    fun `nothing is transformed once the camera is popped`() {
        frame {
            pushTransform(2f, 40f, 0f)
            popTransform()
            rect(Rect(10f, 10f, 20f, 20f), Colour.Red)
        }

        val draw = device.draws.single()
        assertTrue(near(9f, draw.at(0, 0)), "left was ${draw.at(0, 0)}")
    }
}

private typealias TextureHandleOrNull = dev.wildware.composegl.ui.graphics.TextureHandle?
