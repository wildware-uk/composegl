package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import kotlin.math.abs

/**
 * The LibGDX backend's mirror questions asked again of the raw-GL renderer, whose layers come back
 * with their rows already turned over — which is exactly the kind of thing a swapped texture
 * coordinate gets wrong on one backend and right on the other.
 */
class GlMirrorTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** A frame read back, with y counted down from the top like the toolkit's. */
    private class Frame(private val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * Gl.size + x]
    }

    private fun draw(content: GlCanvas.() -> Unit): Frame = Gl.render {
        val canvas = GlCanvas()
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.content()
            canvas.end()
            Frame(Gl.readPixels(Gl.size, Gl.size))
        } finally {
            canvas.close()
        }
    }

    private fun assertColour(expected: Colour, actual: Int, message: String) {
        val close = abs(expected.red - (actual shr 16 and 0xFF)) < 24 &&
            abs(expected.green - (actual shr 8 and 0xFF)) < 24 &&
            abs(expected.blue - (actual and 0xFF)) < 24
        assertTrue(close, "$message: expected %06X, got %06X".format(expected.argb and 0xFFFFFF, actual))
    }

    private fun GlCanvas.strip(bounds: Rect, put: GlCanvas.(TextureHandle) -> Unit) {
        val picture = layer(bounds) {
            rect(Rect(bounds.left, bounds.top, bounds.centre.x, bounds.bottom), red)
            rect(Rect(bounds.centre.x, bounds.top, bounds.right, bounds.bottom), blue)
        }
        put(checkNotNull(picture) { "this driver gave us no layer" })
    }

    @Test
    fun `a mirrored layer swaps its left and right`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val frame = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = true, mirrorY = false) } }

        assertColour(blue, frame.at(60, 80), "the picture's right half is drawn on the left")
        assertColour(red, frame.at(140, 80), "and its left half on the right")
        assertColour(Colour.Black, frame.at(30, 80), "nothing spills past the rectangle's left")
        assertColour(Colour.Black, frame.at(170, 80), "or its right")
    }

    @Test
    fun `a layer mirrored neither way is the plain composite`() {
        val bounds = Rect.of(40f, 40f, 120f, 80f)
        val frame = draw { strip(bounds) { drawLayer(it, bounds, mirrorX = false, mirrorY = false) } }

        assertColour(red, frame.at(60, 80), "left is left")
        assertColour(blue, frame.at(140, 80), "right is right")
    }

    @Test
    fun `a vertically mirrored layer swaps its top and bottom`() {
        val bounds = Rect.of(40f, 40f, 80f, 120f)
        val frame = draw {
            val picture = layer(bounds) {
                rect(Rect(40f, 40f, 120f, 100f), red)
                rect(Rect(40f, 100f, 120f, 160f), blue)
            }
            drawLayer(checkNotNull(picture), bounds, mirrorX = false, mirrorY = true)
        }

        assertColour(blue, frame.at(80, 60), "the picture's bottom half is drawn on top")
        assertColour(red, frame.at(80, 140), "and its top half underneath")
    }

    @Test
    fun `a mirrored node in a tree draws its art facing the other way`() {
        val screen = TestTree()
        val sprite = screen.box("sprite", 20f, 20f, 160f, 60f, Modifier.mirror())
        screen.box("face", 0f, 0f, 40f, 60f, Modifier.background(red), parent = sprite)
        screen.box("tail", 40f, 0f, 120f, 60f, Modifier.background(blue), parent = sprite)

        val frame = draw { DrawPass(this).draw(screen.root) }

        assertColour(red, frame.at(160, 50), "the face, laid out on the left, is on the right")
        assertColour(blue, frame.at(40, 50), "and the tail on the left")
    }
}
