package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11

/**
 * Draw calls blamed on nodes by the raw OpenGL canvas: the LibGDX backend's test asked again of a
 * renderer that shares none of its code, so the overlay means the same thing on either.
 */
class GlDrawCallTraceTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    private val white = Colour.rgb(0xFFFFFF)
    private val green = Colour.rgb(0x00FF00)
    private val red = Colour.rgb(0xFF0000)
    private val box = Rect.of(0f, 0f, 10f, 10f)

    @Test
    fun `each flush says why it happened and the reasons add up to the draw calls`() = Gl.render {
        val canvas = GlCanvas()
        val other = GlTexture.rgba(1, 1, byteArrayOf(-1, -1, -1, -1), smooth = false)
        val trace = DrawCallTrace()
        try {
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.traceDrawCalls(trace)
            canvas.begin(viewport)

            trace.node = UiNode("panel")
            canvas.rect(box, white)
            canvas.pushClip(Rect.of(0f, 0f, 200f, 200f))
            canvas.rect(box, white)
            canvas.popClip()

            // Red, then green added onto it: a traced frame is still drawn properly.
            trace.node = UiNode("glow")
            canvas.rect(Rect.of(100f, 100f, 150f, 150f), red)
            canvas.pushBlend(BlendMode.Additive)
            canvas.rect(Rect.of(100f, 100f, 150f, 150f), green)
            canvas.popBlend()

            // A box first, so the queue has something in it for the picture's texture to cut off.
            trace.node = UiNode("slot")
            canvas.rect(box, white)
            trace.node = UiNode("icon")
            canvas.image(other, Rect.of(20f, 20f, 40f, 40f))

            trace.node = UiNode("card")
            val picture = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) { canvas.rect(box, white) }!!
            canvas.drawLayer(picture, Rect.of(0f, 0f, 50f, 50f))

            trace.node = UiNode("blur")
            canvas.rect(box, white)
            val blurred = canvas.layer(Rect.of(0f, 0f, 50f, 50f)) { canvas.rect(box, white) }!!
            canvas.rect(box, white)
            canvas.drawLayer(blurred, Rect.of(0f, 0f, 50f, 50f), passThrough)

            trace.node = UiNode("sprites")
            canvas.rect(box, white)
            canvas.raw { }

            trace.node = null
            canvas.rect(box, white)
            canvas.end()

            val blamed = trace.culprits(withEnd = true).map { "${it.name} ${it.reason} ${it.calls}" }
            assertEquals(canvas.drawCalls, trace.total, "one record per call: $blamed")
            assertTrue("panel Clip 2" in blamed, "$blamed")
            assertTrue("glow Blend 2" in blamed, "$blamed")
            assertTrue("icon Texture 1" in blamed, "$blamed")
            assertTrue(blamed.any { it.startsWith("card Layer") }, "$blamed")
            assertTrue("blur Shader 1" in blamed, "$blamed")
            assertTrue("sprites Raw 1" in blamed, "$blamed")
            assertTrue("outside the tree End 1" in blamed, "$blamed")
            assertTrue(canvas.tracesDrawCalls)

            // Read back with the top row first, as the toolkit counts y.
            val pixel = Gl.readPixels(Gl.size, Gl.size)[125 * Gl.size + 125]
            assertTrue(pixel shr 16 and 0xFF > 230 && pixel shr 8 and 0xFF > 230, "red plus green: %06X".format(pixel))

            // Switched off, the same kind of frame blames nothing.
            canvas.traceDrawCalls(null)
            trace.clear()
            canvas.begin(viewport)
            canvas.rect(box, white)
            canvas.pushBlend(BlendMode.Additive)
            canvas.rect(box, white)
            canvas.popBlend()
            canvas.end()
            assertEquals(0, trace.total)
            assertEquals(2, canvas.drawCalls)
        } finally {
            canvas.close()
            other.close()
        }
    }
}
