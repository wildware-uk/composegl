package composegl.lwjgl3

import composegl.ui.geometry.Rect
import composegl.ui.graphics.Colour
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import composegl.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import kotlin.math.abs

/**
 * Drawing the interface into a texture instead of into the window.
 *
 * The two questions only a GPU can answer: whether a panel in the world is the same pixels as the
 * same panel on the HUD, and whether resizing one leaves framebuffers lying about. The third —
 * that it is drawn only when something changed — is the toolkit's and is asserted without a GPU.
 */
class GlRenderTargetTest {

    private val size = 128

    private val panel = Rect.of(20f, 16f, 88f, 72f)
    private val fill = Colour.rgb(0x3366CC)
    private val stripe = Colour.rgb(0xCC5522)

    /** The same drawing either way: something with an edge, a corner and an overlap. */
    private fun GlCanvas.scene() {
        rect(panel, fill, corner = 8f)
        rect(Rect.of(40f, 30f, 108f, 44f), stripe, corner = 0f)
        border(panel, Colour.rgb(0xFFFFFF), width = 2f, corner = 8f)
    }

    private fun readPixels(width: Int, height: Int): IntArray {
        val bytes = BufferUtils.createByteBuffer(width * height * 4)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
        return IntArray(width * height) { at ->
            val byte = at * 4
            (bytes.get(byte).toInt() and 0xFF shl 24) or
                (bytes.get(byte + 1).toInt() and 0xFF shl 16) or
                (bytes.get(byte + 2).toInt() and 0xFF shl 8) or
                (bytes.get(byte + 3).toInt() and 0xFF)
        }
    }

    @Test
    fun `an effect inside a panel in the world draws into the panel, not the window`() {
        // A layer binds a framebuffer of its own and has to put back the one it found. What it
        // found here is the panel's, not the window's — and nothing can ask OpenGL which, because
        // LibGDX answers that question wrongly and asking costs a stall. So the canvas is told.
        val canvas = GlCanvas()
        val target = GlRenderTarget(size, size)
        try {
            Gl.render {
                GL11.glClearColor(0f, 0f, 0f, 1f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

                target.draw(canvas, clear = Colour.rgb(0x000000)) {
                    val bounds = Rect.of(0f, 0f, size.toFloat(), size.toFloat())
                    val picture = canvas.layer(bounds) { canvas.rect(panel, fill) }
                    canvas.drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
                }

                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebufferName)
                val inside = readPixels(size, size)
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
                val window = readPixels(Gl.size, Gl.size)

                // The panel's own pixels have the rectangle; the window behind it is still the
                // black it was cleared to. Put the framebuffer back wrongly and the two swap.
                assertEquals(
                    0x3366CCFF.toInt(),
                    colour(inside, size, 60, 50),
                    "the rectangle should have landed in the panel",
                )
                assertEquals(
                    0x000000FF.toInt(),
                    colour(window, Gl.size, 60, 50),
                    "and nothing should have landed on the window",
                )
            }
        } finally {
            target.close()
            canvas.close()
        }
    }

    /** One pixel of a frame read back, with y counted down from the top like the toolkit's. */
    private fun colour(pixels: IntArray, width: Int, x: Int, y: Int): Int {
        val height = pixels.size / width
        return pixels[(height - 1 - y) * width + x]
    }

    @Test
    fun `a panel in the world is the same pixels as the same panel on the hud`() = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(size, size)
        try {
            // On the HUD: straight into the window, on an opaque background.
            GL11.glViewport(0, 0, Gl.size, Gl.size)
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(Viewport(Size(size.toFloat(), size.toFloat()), Size(size.toFloat(), size.toFloat()), ScalePolicy.Stretch))
            canvas.scene()
            canvas.end()
            val onScreen = readPixels(size, size)

            // In the world: the same drawing, into a texture, on the same opaque background so
            // that the two are comparable without thinking about premultiplied alpha.
            target.draw(canvas, clear = Colour.rgb(0x000000)) { canvas.scene() }

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebufferName)
            val inTheWorld = readPixels(size, size)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)

            assertArrayEquals(onScreen, inTheWorld, "a panel in the world has to be the same panel")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `what comes out is premultiplied`(): Unit = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(32, 32)
        try {
            // Half transparent white over nothing. Premultiplied, that is a grey with half alpha;
            // with straight alpha it would come out white, and additive blending would show every
            // transparent pixel as a haze.
            target.draw(canvas) {
                canvas.rect(Rect.of(0f, 0f, 32f, 32f), Colour.White.scaleAlpha(0.5f), corner = 0f)
            }

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebufferName)
            val pixel = readPixels(32, 32)[16 * 32 + 16]
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)

            val red = pixel ushr 24 and 0xFF
            val alpha = pixel and 0xFF
            assertTrue(abs(alpha - 128) <= 4, "half transparent should stay half transparent: $alpha")
            assertTrue(
                abs(red - 128) <= 6,
                "premultiplied means the colour is already scaled by the alpha: $red",
            )
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `resizing does not leak framebuffers`() = Gl.render {
        val target = GlRenderTarget(64, 64)
        try {
            val firstFramebuffer = target.framebufferName
            val firstTexture = target.textureName

            // A window being dragged by a corner, fifty frames of it.
            repeat(50) { target.resize(64 + it, 48 + it) }

            // A deleted name goes back in the pool and is handed out again, so a target that has
            // been resized fifty times still has a name from the bottom of it. One that leaked
            // would be fifty names further up — which is the whole of the bug, in one number.
            assertTrue(
                target.framebufferName <= firstFramebuffer + 1,
                "fifty resizes left framebuffers behind: ${target.framebufferName} against $firstFramebuffer",
            )
            assertTrue(
                target.textureName <= firstTexture + 1,
                "fifty resizes left textures behind: ${target.textureName} against $firstTexture",
            )
            assertTrue(GL30.glIsFramebuffer(target.framebufferName), "and the one it has now is real")
            assertEquals(113, target.width)
            assertEquals(97, target.height)
            assertEquals(113, target.texture.width, "the handle the game holds follows the resize")
        } finally {
            target.close()
        }
    }

    @Test
    fun `closing gives everything back and can be done twice`() = Gl.render {
        val target = GlRenderTarget(32, 32)
        val framebuffer = target.framebufferName

        target.close()
        target.close()

        assertFalse(GL30.glIsFramebuffer(framebuffer), "the framebuffer outlived the target")
        assertEquals(0, target.framebufferName, "and the target knows it has nothing")
    }

    @Test
    fun `it puts back the framebuffer and viewport it found`() = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(32, 32)
        try {
            GL11.glViewport(0, 0, Gl.size, Gl.size)
            target.draw(canvas) { canvas.rect(Rect.of(0f, 0f, 8f, 8f), Colour.White, corner = 0f) }

            assertEquals(0, GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING), "the window was left unbound")
            val viewport = IntArray(4)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
            assertArrayEquals(intArrayOf(0, 0, Gl.size, Gl.size), viewport, "the viewport was left moved")
        } finally {
            target.close()
            canvas.close()
        }
    }
}
