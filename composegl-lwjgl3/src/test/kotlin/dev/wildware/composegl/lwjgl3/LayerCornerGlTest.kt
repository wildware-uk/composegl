package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.effects.Axis
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.render.LayerPicture
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * A layer drawn into the corner of a bigger pooled picture, on a real context, against the same
 * layer drawn into a picture exactly its size: plain, through effects, and turned.
 *
 * The layer is 128 by 64, a whole step each way, so a fresh canvas makes it a picture of exactly
 * that size. A canvas that drew a 150 by 100 layer the frame before holds a free 192 by 128 picture,
 * and the same layer goes into its bottom-left corner.
 */
class LayerCornerGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val area = Rect.of(80f, 100f, 128f, 64f)

    /** Colour right up to every edge, and a different colour along each, so a read past an edge shows. */
    private fun GlCanvas.content() {
        rect(area, Colour.Red)
        rect(Rect.of(area.left, area.top, area.width / 2f, area.height / 2f), Colour.Blue)
        rect(Rect.of(area.left, area.top, area.width, 2f), Colour.White)
        rect(Rect.of(area.right - 2f, area.top, 2f, area.height), Colour.Green)
        rect(Rect.of(area.left, area.bottom - 2f, area.width, 2f), Colour.Yellow)
        rect(Rect.of(area.left, area.top, 2f, area.height), Colour.rgb(0x00FFFF))
    }

    /** One frame of the layer, put down by [putDown], over black, read back; and how big its picture was. */
    private fun frame(canvas: GlCanvas, putDown: GlCanvas.(TextureHandle) -> Unit): Pair<IntArray, Int> {
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        canvas.begin(viewport)
        val picture = canvas.layer(area) { canvas.content() }
        assertNotNull(picture)
        canvas.putDown(picture!!)
        canvas.end()
        return Gl.readPixels(Gl.size, Gl.size) to (picture as LayerPicture).target.width
    }

    /** The layer by a fresh canvas, into a picture its own size, and by one whose pool holds a bigger one. */
    private fun exactAndPooled(name: String, putDown: GlCanvas.(TextureHandle) -> Unit): Pair<IntArray, IntArray> = Gl.render {
        val fresh = GlCanvas()
        val (exact, exactWidth) = try {
            frame(fresh, putDown)
        } finally {
            fresh.close()
        }
        val used = GlCanvas()
        val (pooled, pooledWidth) = try {
            used.begin(viewport)
            used.layer(Rect.of(0f, 0f, 150f, 100f)) { used.rect(Rect.of(0f, 0f, 150f, 100f), Colour.White) }
            used.end()
            frame(used, putDown)
        } finally {
            used.close()
        }
        assertEquals(128, exactWidth, "the fresh canvas's picture is the layer's own size")
        assertEquals(192, pooledWidth, "the second canvas drew into the bigger picture it had")
        keep(name, exact, pooled)
        exact to pooled
    }

    /**
     * The two side by side round the layer, twice size, with where they differ at all in magenta on
     * the right: kept in `build/screenshots/layer-corner` for a person to look at.
     */
    private fun keep(name: String, exact: IntArray, pooled: IntArray) {
        val left = 40
        val top = 40
        val across = 220
        val down = 160
        val zoom = 2
        val image = imageOf(across * 3 * zoom, down * zoom) { x, y ->
            val panel = x / (across * zoom)
            val at = (top + y / zoom) * Gl.size + left + (x % (across * zoom)) / zoom
            when (panel) {
                0 -> exact[at]
                1 -> pooled[at]
                else -> if (exact[at] != pooled[at]) 0xFF00FF else (exact[at] shr 2) and 0x3F3F3F
            }
        }
        val directory = File("build/screenshots/layer-corner").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "$name.png"))
    }

    private fun worst(expected: IntArray, actual: IntArray): Int = expected.indices.maxOf { at ->
        val a = expected[at]
        val b = actual[at]
        maxOf(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )
    }

    private fun differing(expected: IntArray, actual: IntArray, by: Int): Int = expected.indices.count { at ->
        val a = expected[at]
        val b = actual[at]
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > by ||
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > by ||
            abs((a and 0xFF) - (b and 0xFF)) > by
    }

    @Test
    fun `two layers taken before either is put down each keep their own picture`() = Gl.render {
        val canvas = GlCanvas()
        try {
            // A first frame leaves a free 128 by 128 picture, which either of the next two would fit.
            canvas.begin(viewport)
            canvas.layer(Rect.of(0f, 0f, 100f, 100f)) {}
            canvas.end()

            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            val red = Rect.of(20f, 20f, 100f, 100f)
            val blue = Rect.of(200f, 20f, 90f, 80f)
            val first = canvas.layer(red) { canvas.rect(red, Colour.Red) }
            val second = canvas.layer(blue) { canvas.rect(blue, Colour.Blue) }
            canvas.drawLayer(first!!, red)
            canvas.drawLayer(second!!, blue)
            canvas.end()
            val pixels = Gl.readPixels(Gl.size, Gl.size)

            assertEquals(0xFF0000, pixels[70 * Gl.size + 70], "the first layer is still red")
            assertEquals(0xFF0000, pixels[22 * Gl.size + 70], "all the way to its top")
            assertEquals(0x0000FF, pixels[60 * Gl.size + 245], "and the second is blue")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a plain layer from a bigger picture is the layer from one its own size`() {
        val (exact, pooled) = exactAndPooled("plain") { drawLayer(it, area) }
        assertEquals(0, worst(exact, pooled), "not one channel of one pixel differs")
    }

    @Test
    fun `a blurred layer from a bigger picture is the layer from one its own size`() {
        val (exact, pooled) = exactAndPooled("blur") { drawLayer(it, area, blur(6f, Axis.Horizontal)) }
        assertTrue(worst(exact, pooled) <= 1, "at most a rounding apart: ${worst(exact, pooled)}")
    }

    /**
     * An effect that reads past its picture's edges with a plain `texture2D`, and shades by where
     * each pixel is: what a vignette or a game's own light pass does.
     */
    private val reachAndPlace = ShaderEffect(
        ShaderSource(
            "reach-and-place",
            """
            void main() {
                vec2 past = v_texCoord * 1.5 - 0.25;
                vec4 picture = texture2D(u_texture, past) * 0.5 + texture2D(u_texture, v_texCoord) * 0.5;
                gl_FragColor = picture * vec4(v_texCoord.x, v_texCoord.y, 1.0, 1.0) * u_alpha;
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `an effect that reads past the edge and by position sees the same picture from a bigger one`() {
        val (exact, pooled) = exactAndPooled("reach-and-place") { drawLayer(it, area, reachAndPlace) }
        assertTrue(worst(exact, pooled) <= 1, "at most a rounding apart: ${worst(exact, pooled)}")
    }

    @Test
    fun `a turned layer from a bigger picture is the layer from one its own size`() {
        val (exact, pooled) = exactAndPooled("turned") { drawLayer(it, area, 17f, 0.5f, 0.5f) }
        println("turned: ${differing(exact, pooled, by = 0)} pixels differ at all, worst ${worst(exact, pooled)}")
        assertTrue(worst(exact, pooled) <= 1, "at most a rounding apart, edges included: ${worst(exact, pooled)}")
    }

    @Test
    fun `a layer stretched off the pixel grid from a bigger picture is the layer from one its own size`() {
        // What a springing scale does: the picture grown a little and put down between pixels.
        val stretched = Rect.of(area.left - 3.3f, area.top - 2.6f, area.width * 1.07f, area.height * 1.09f)
        val (exact, pooled) = exactAndPooled("stretched") { drawLayer(it, stretched) }
        println("stretched: ${differing(exact, pooled, by = 0)} pixels differ at all, worst ${worst(exact, pooled)}")
        assertTrue(worst(exact, pooled) <= 1, "at most a rounding apart, edges included: ${worst(exact, pooled)}")
    }
}
