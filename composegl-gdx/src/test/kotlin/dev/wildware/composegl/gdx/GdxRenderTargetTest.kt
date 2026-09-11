package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.BufferUtils
import kotlin.math.abs

/**
 * Drawing the interface into a texture instead of into the window, through LibGDX.
 *
 * The same two questions the raw-GL backend answers, asked again of a renderer that shares none of
 * its code: is a panel in the world the same pixels as the same panel on the HUD, and does
 * resizing one leave framebuffers behind.
 */
class GdxRenderTargetTest {

    private val size = 128

    private val panel = Rect.of(20f, 16f, 88f, 72f)

    /** The same drawing either way: an edge, a corner and an overlap. */
    private fun GdxCanvas.scene() {
        rect(panel, Colour.rgb(0x3366CC), corner = 8f)
        rect(Rect.of(40f, 30f, 108f, 44f), Colour.rgb(0xCC5522), corner = 0f)
        border(panel, Colour.rgb(0xFFFFFF), width = 2f, corner = 8f)
    }

    private fun readPixels(width: Int, height: Int): IntArray {
        val bytes = BufferUtils.createByteBuffer(width * height * 4)
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, bytes)
        return IntArray(width * height) { at ->
            val byte = at * 4
            (bytes.get(byte).toInt() and 0xFF shl 24) or
                (bytes.get(byte + 1).toInt() and 0xFF shl 16) or
                (bytes.get(byte + 2).toInt() and 0xFF shl 8) or
                (bytes.get(byte + 3).toInt() and 0xFF)
        }
    }

    @Test
    fun `an effect inside a panel in the world draws into the panel, not the window`(): Unit = Gl.render {
        // A layer binds a framebuffer of its own and has to put back the one it found. What it
        // found here is the panel's, not the window's — and it cannot ask, because LibGDX's
        // glGetIntegerv(GL_FRAMEBUFFER_BINDING) answers zero with a framebuffer bound. So the
        // canvas is told, and this is the test that says so.
        val canvas = GdxCanvas()
        val target = GdxRenderTarget(size, size)
        try {
            Gdx.gl.glViewport(0, 0, Gl.size, Gl.size)
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            target.draw(canvas, clear = Colour.rgb(0x000000)) {
                val bounds = Rect.of(0f, 0f, size.toFloat(), size.toFloat())
                val picture = canvas.layer(bounds) { canvas.rect(panel, Colour.rgb(0x3366CC)) }
                canvas.drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
            }

            val inside = target.read { readPixels(size, size) }
            val window = readPixels(Gl.size, Gl.size)

            // The panel's own pixels have the rectangle; the window behind it is still the black it
            // was cleared to. Put the framebuffer back wrongly and the two swap.
            assertClose(
                0x3366CCFF.toInt(),
                colour(inside, size, 60, 50),
                "the rectangle should have landed in the panel",
            )
            assertClose(
                0x000000FF.toInt(),
                colour(window, Gl.size, 60, 50),
                "and nothing should have landed on the window",
            )
        } finally {
            target.dispose()
            canvas.dispose()
        }
    }

    /**
     * Colours are compared with a tolerance: LibGDX squeezes one into a float on the way to the
     * shader and a channel comes back a bit light.
     */
    private fun assertClose(expected: Int, actual: Int, because: String) {
        val off = (0..3).maxOf { channel ->
            val shift = channel * 8
            abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
        }
        assertTrue(off <= 2, "$because expected about ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}")
    }

    /** One pixel of a frame read back, with y counted down from the top like the toolkit's. */
    private fun colour(pixels: IntArray, width: Int, x: Int, y: Int): Int {
        val height = pixels.size / width
        return pixels[(height - 1 - y) * width + x]
    }

    @Test
    fun `a panel in the world is the same pixels as the same panel on the hud`(): Unit = Gl.render {
        val canvas = GdxCanvas()
        val target = GdxRenderTarget(size, size)
        try {
            Gdx.gl.glViewport(0, 0, Gl.size, Gl.size)
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(
                Viewport(
                    Size(size.toFloat(), size.toFloat()),
                    Size(size.toFloat(), size.toFloat()),
                    ScalePolicy.Stretch,
                ),
            )
            canvas.scene()
            canvas.end()
            val onScreen = readPixels(size, size)

            // The same drawing, into a texture, on the same opaque background so that the two are
            // comparable without thinking about premultiplied alpha.
            target.draw(canvas, clear = Colour.rgb(0x000000)) { canvas.scene() }
            val inTheWorld = target.read { readPixels(size, size) }

            assertArrayEquals(onScreen, inTheWorld, "a panel in the world has to be the same panel")
        } finally {
            target.dispose()
            canvas.dispose()
        }
    }

    @Test
    fun `what comes out is premultiplied`(): Unit = Gl.render {
        val canvas = GdxCanvas()
        val target = GdxRenderTarget(32, 32)
        try {
            // Half transparent white over nothing: premultiplied that is a grey at half alpha,
            // and with straight alpha it would come out white.
            target.draw(canvas) {
                canvas.rect(Rect.of(0f, 0f, 32f, 32f), Colour.White.scaleAlpha(0.5f), corner = 0f)
            }
            val pixel = target.read { readPixels(32, 32)[16 * 32 + 16] }

            val red = pixel ushr 24 and 0xFF
            val alpha = pixel and 0xFF
            assertTrue(abs(alpha - 128) <= 4, "half transparent should stay half transparent: $alpha")
            assertTrue(abs(red - 128) <= 6, "the colour should already be scaled by the alpha: $red")
        } finally {
            target.dispose()
            canvas.dispose()
        }
    }

    @Test
    fun `resizing does not leak framebuffers`(): Unit = Gl.render {
        val target = GdxRenderTarget(64, 64)
        try {
            val first = target.textureName

            // A window being dragged by a corner, fifty frames of it.
            repeat(50) { target.resize(64 + it, 48 + it) }

            // A deleted name goes back in the pool and is handed out again; one that leaked would
            // be fifty names further up, which is the whole of the bug in one number.
            assertTrue(
                target.textureName <= first + 2,
                "fifty resizes left textures behind: ${target.textureName} against $first",
            )
            assertEquals(113, target.width)
            assertEquals(113, target.texture.width, "the handle the game holds follows the resize")
        } finally {
            target.dispose()
        }
    }
}
