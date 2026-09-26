package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11

/**
 * A box lit as a surface with a shape, judged by its pixels.
 *
 * The claim this makes over stacked bands is that the surface really has a normal: the light
 * belongs to the whole box at once, so turning it moves the bright side, and a corner is lit by
 * how far it has turned away rather than by which band it happens to fall in. These read the
 * pixels and say whether that is what happened.
 */
class ReliefGlTest {

    @BeforeEach
    fun requireGl() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val face = Colour.rgb(0x808080)
    private val box = Rect.of(40f, 40f, 140f, 100f)

    private fun draw(
        shape: Relief = Relief.Chamfer,
        light: Float = 90f,
        depth: Float = 24f,
        gloss: Float = 0f,
    ): IntArray = Gl.render {
        val canvas = GlCanvas(null)
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.rect(box, face, corner = 16f)
            canvas.relief(box, Corners.all(16f), shape, depth = depth, light = light, gloss = gloss)
            canvas.end()
            Gl.readPixels(Gl.size, Gl.size)
        } finally {
            canvas.close()
        }
    }

    private fun IntArray.grey(x: Int, y: Int) = this[y * Gl.size + x] shr 8 and 0xFF

    @Test
    fun `the edge facing the light is brighter than the one facing away`() {
        val frame = draw(light = 90f)

        val top = frame.grey(110, 48)
        val bottom = frame.grey(110, 132)
        assertTrue(top > bottom + 30, "lit from above: top $top, bottom $bottom")
    }

    @Test
    fun `turning the light turns which side is bright`() {
        val fromLeft = draw(light = 0f)
        val fromRight = draw(light = 180f)

        assertTrue(fromLeft.grey(48, 90) > fromLeft.grey(172, 90) + 30, "lit from the left")
        assertTrue(fromRight.grey(172, 90) > fromRight.grey(48, 90) + 30, "and from the right")
    }

    @Test
    fun `a corner is lit by how far it has turned, not by which edge it is near`() {
        // Lit from the top-left, so the top-left corner faces the light and the bottom-right does not.
        val frame = draw(light = 45f, depth = 30f)

        val nearCorner = frame.grey(58, 58)
        val farCorner = frame.grey(162, 122)
        assertTrue(nearCorner > farCorner + 40, "the corner turns with the light: $nearCorner against $farCorner")
    }

    @Test
    fun `the middle of a face is left alone while its edges are not`() {
        val frame = draw(depth = 20f)

        val middle = frame.grey(110, 90)
        val climb = (42..60).maxOf { frame.grey(110, it) }
        val fall = (120..138).minOf { frame.grey(110, it) }
        assertTrue(kotlin.math.abs(middle - 0x80) < 12, "the flat middle is left as the fill: $middle")
        assertTrue(climb > middle + 10, "the climb at the lit edge is lighter: $climb against $middle")
        assertTrue(fall < middle - 10, "and the far one darker: $fall against $middle")
    }

    @Test
    fun `a dome curves the whole way across where a chamfer stops`() {
        val chamfer = draw(shape = Relief.Chamfer, depth = 50f)
        val dome = draw(shape = Relief.Dome, depth = 50f)

        // Halfway between the lit edge and the middle: a chamfer is flat by now, a dome is not.
        val chamferHalf = chamfer.grey(110, 70)
        val domeHalf = dome.grey(110, 70)
        assertTrue(domeHalf != chamferHalf, "the two shapes light differently: $domeHalf against $chamferHalf")
    }

    @Test
    fun `gloss adds a highlight where the surface faces the light squarely`() {
        val matte = draw(shape = Relief.Fillet, depth = 30f, gloss = 0f)
        val shiny = draw(shape = Relief.Fillet, depth = 30f, gloss = 1f)

        val brightestMatte = (44..136).maxOf { matte.grey(110, it) }
        val brightestShiny = (44..136).maxOf { shiny.grey(110, it) }
        assertTrue(brightestShiny > brightestMatte + 15, "a shine: $brightestShiny against $brightestMatte")
    }
}
