package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Which framebuffer a frame on the window lands in, on a real driver.
 *
 * The canvas asks the driver which framebuffer is the game's own once and remembers it, because
 * asking every frame makes the CPU wait for the driver. A game that binds a framebuffer of its own
 * around the interface's frames — a whole-screen colour grade — says so, and the next frame asks.
 */
class GlHostFramebufferTest {

    /** A [GlRenderTarget] reaches OpenGL when it is built; with no context that aborts the JVM. */
    @BeforeEach
    fun requireDisplay() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val size = 64
    private val design = Viewport(Size(size.toFloat(), size.toFloat()), Size(size.toFloat(), size.toFloat()), ScalePolicy.Stretch)
    private val whole = Rect.of(0f, 0f, size.toFloat(), size.toFloat())
    private val blue = Colour.rgb(0x3366CC)
    private val orange = Colour.rgb(0xCC5522)

    /** A frame with a layer in it, and something drawn after the layer, as a graded HUD would be. */
    private fun GlCanvas.frame() {
        begin(design)
        val picture = checkNotNull(layer(whole) { rect(Rect.of(0f, 0f, 32f, 64f), blue) }) { "this driver gave us no layer" }
        drawLayer(picture, whole)
        rect(Rect.of(32f, 0f, 32f, 64f), orange)
        end()
    }

    private fun clearBlack() {
        Gl.gl.viewport(0, 0, size, size)
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
    }

    /** The left and right halves of the bound framebuffer, as `0xRRGGBB`. */
    private fun halves(): Pair<Int, Int> {
        val pixels = Gl.readPixels(size, size)
        return pixels[32 * size + 16] to pixels[32 * size + 48]
    }

    @Test
    fun `a frame lands in the game's own framebuffer once the canvas is told and in the window once told again`() {
        Gl.render {
            val canvas = GlCanvas()
            // Only for its framebuffer: the game's own, made the way any game makes one.
            val own = GlRenderTarget(size, size)
            try {
                // The first frame learns which framebuffer is the window's.
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                clearBlack()
                canvas.frame()
                assertEquals(0x3366CC to 0xCC5522, halves(), "the first frame, in the window")

                // A grade switched on: the game binds its own framebuffer around the frame and says so.
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                clearBlack()
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, own.framebufferName)
                clearBlack()
                canvas.hostTargetChanged()
                canvas.frame()
                assertEquals(0x3366CC to 0xCC5522, halves(), "both sides of the layer in the game's framebuffer")
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                assertEquals(0x000000 to 0x000000, halves(), "and nothing in the window")

                // Switched off again, and said so: back to the window.
                canvas.hostTargetChanged()
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, own.framebufferName)
                clearBlack()
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                clearBlack()
                canvas.frame()
                assertEquals(0x3366CC to 0xCC5522, halves(), "in the window again")
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, own.framebufferName)
                assertEquals(0x000000 to 0x000000, halves(), "and nothing in the game's framebuffer")
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            } finally {
                own.close()
                canvas.close()
            }
        }
    }

    @Test
    fun `a frame on the window keeps to the window it remembers when the game binds another without a word`() {
        Gl.render {
            val canvas = GlCanvas()
            val own = GlRenderTarget(size, size)
            try {
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                clearBlack()
                canvas.frame()

                // Not asked again: the frame goes where the first one went.
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                clearBlack()
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, own.framebufferName)
                clearBlack()
                canvas.frame()
                Gl.gl.bindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
                assertEquals(0x3366CC to 0xCC5522, halves(), "the window it remembers")
            } finally {
                own.close()
                canvas.close()
            }
        }
    }
}
