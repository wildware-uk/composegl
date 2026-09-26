package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs
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
        polish: Float = 0.5f,
    ): IntArray = Gl.render {
        val canvas = GlCanvas(null)
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.rect(box, face, corner = 16f)
            canvas.relief(box, Corners.all(16f), shape, depth = depth, light = light, gloss = gloss, polish = polish)
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

    @Test
    fun `a wet surface spreads its shine where a glassy one draws it to a point`() {
        fun litRows(polish: Float): List<Int> {
            val frame = draw(shape = Relief.Fillet, depth = 34f, gloss = 1f, polish = polish)
            return (42..90).map { frame.grey(110, it) }
        }

        val wet = litRows(0.05f)
        val glassy = litRows(0.95f)

        // How much of the lit side is brighter than the fill: a wet surface shines over more of it.
        val wetBand = wet.count { it > 0x90 }
        val glassyBand = glassy.count { it > 0x90 }
        assertTrue(wetBand > glassyBand + 3, "the wet shine covers more rows: $wetBand against $glassyBand")
        assertTrue(glassy.max() >= wet.max() - 12, "and the glassy one is no dimmer where it does shine")
    }

    @Test
    fun `a lit face keeps its colour where a light laid over one loses it`() {
        val green = Colour.rgb(0x3AA81E)
        fun brightest(face: Colour?): IntArray {
            val frame = Gl.render {
                val canvas = GlCanvas(null)
                try {
                    Gl.gl.clearColor(0f, 0f, 0f, 1f)
                    Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                    canvas.begin(viewport)
                    if (face == null) canvas.rect(box, green, corner = 16f)
                    canvas.relief(
                        box, Corners.all(16f), Relief.Chamfer, depth = 22f, light = 90f,
                        strength = 0.8f, gloss = 0f, face = face ?: Colour.Transparent,
                    )
                    canvas.end()
                    Gl.readPixels(Gl.size, Gl.size)
                } finally {
                    canvas.close()
                }
            }
            // The brightest row of the lit edge, as red, green and blue.
            val at = (44..64).maxByOrNull { frame[it * Gl.size + 110] shr 8 and 0xFF }!!
            val pixel = frame[at * Gl.size + 110]
            return intArrayOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
        }

        val over = brightest(null)
        val lit = brightest(green)

        fun saturation(rgb: IntArray) = rgb.max() - rgb.min()
        assertTrue(lit[1] > over[1] - 10, "both reach about as bright: ${lit.toList()} against ${over.toList()}")
        assertTrue(
            saturation(lit) > saturation(over) + 20,
            "the lit face keeps its green where the overlay washes it: ${lit.toList()} against ${over.toList()}",
        )
    }

    @Test
    fun `a face given a run of colours grades across the body, and never clips to white`() {
        val top = Colour.rgb(0x6ED23C)
        val bottom = Colour.rgb(0x2E8C18)
        val run = Brush.Ramp(listOf(Brush.Stop(0.2f, top), Brush.Stop(0.8f, bottom)))
        // With an atlas: a run of colours is baked onto it, and without one the face falls back to
        // the single colour, exactly as a many-stop gradient does.
        val frame = Gl.render {
            val canvas = GlCanvas(StbFonts(), Gl.gl)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.relief(
                    box, Corners.all(16f), Relief.Chamfer, depth = 18f, light = 90f,
                    strength = 0.5f, gloss = 0.9f, polish = 0.6f, face = top, faceRun = run,
                )
                canvas.end()
                Gl.readPixels(Gl.size, Gl.size)
            } finally {
                canvas.close()
            }
        }
        fun at(y: Int) = frame[y * Gl.size + 110].let {
            intArrayOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF)
        }

        // Down the middle of the body, clear of the lit edge at either end.
        val high = at(70)
        val low = at(110)
        assertTrue(high[1] > low[1] + 15, "the body grades: ${high.toList()} at the top, ${low.toList()} lower")
        assertTrue(low[0] < high[0], "and in every channel, not just the brightest")

        // The whole shape, including the lit edge and the shine on it: a rolled-off highlight keeps
        // its colour, where one that clips goes flat white and takes the hue with it.
        val white = (40..140).flatMap { y -> (50..170).map { x -> frame[y * Gl.size + x] } }
            .count { it shr 16 and 0xFF > 250 && it shr 8 and 0xFF > 250 && it and 0xFF > 250 }
        assertTrue(white == 0, "nothing burns out to white, but $white pixels did")
    }

    @Test
    fun `a material on the face is multiplied into its colour, and the light still shapes the edge`() {
        // A grain of light and dark stripes, as its own texture: two greys, four rows each.
        val pixels = ByteArray(8 * 8 * 4)
        for (row in 0 until 8) {
            val grey = if (row < 4) 0xFF.toByte() else 0x80.toByte()
            for (column in 0 until 8) {
                val at = (row * 8 + column) * 4
                pixels[at] = grey
                pixels[at + 1] = grey
                pixels[at + 2] = grey
                pixels[at + 3] = 0xFF.toByte()
            }
        }
        val red = Colour.rgb(0xC03020)
        fun draw(material: GlTexture?): IntArray = Gl.render {
            val canvas = GlCanvas(null)
            try {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.relief(
                    box, Corners.all(16f), Relief.Chamfer, depth = 18f, light = 90f,
                    strength = 0.4f, gloss = 0f, face = red, material = material,
                )
                canvas.end()
                Gl.readPixels(Gl.size, Gl.size)
            } finally {
                canvas.close()
            }
        }
        fun rgb(frame: IntArray, y: Int) = frame[y * Gl.size + 110].let {
            intArrayOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF)
        }

        val grain = GlTexture.rgba(8, 8, pixels, smooth = false, gl = Gl.gl)
        val plain = draw(null)
        val textured = try { draw(grain) } finally { grain.close() }

        // The upper half of the face wears the pale stripe and the lower half the dark one, and the
        // plain face has no such step at all.
        val pale = rgb(textured, 70)
        val dark = rgb(textured, 105)
        assertTrue(pale[0] > dark[0] + 30, "the grain shows: ${pale.toList()} against ${dark.toList()}")
        assertTrue(
            abs(rgb(plain, 70)[0] - rgb(plain, 105)[0]) < 20,
            "and it is the grain doing it, not the light: the plain face is even down the middle",
        )

        // Multiplied into the face's colour, not replacing it: a grey grain on red stays red.
        assertTrue(pale[0] > pale[1] + 60 && dark[0] > dark[1] + 30, "still red: ${pale.toList()}, ${dark.toList()}")
    }
}
