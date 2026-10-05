package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import kotlin.math.abs

/**
 * A shrunk panel on a real context, drawn through a transform: where the picture used to put it,
 * the same colours, and fewer draw calls than the picture took.
 */
class ScaleInPlaceGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** A 160 by 80 panel at (40, 40), red on the left and blue on the right, drawn at half size from its corner. */
    private val screen = TestTree().also { screen ->
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("left", 0f, 0f, 80f, 80f, Modifier.background(red), parent = panel)
        screen.box("right", 80f, 0f, 80f, 80f, Modifier.background(blue), parent = panel)
    }

    /** One frame of [screen] through [canvas], read back, and how many draw calls it took. */
    private fun frame(canvas: UiCanvas): Pair<IntArray, Int> {
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        canvas.begin(viewport)
        DrawPass(canvas).draw(screen.root)
        canvas.end()
        return Gl.readPixels(Gl.size, Gl.size) to canvas.drawCalls
    }

    private fun IntArray.at(x: Int, y: Int): Int = this[y * Gl.size + x]

    private fun near(expected: Int, actual: Int): Boolean =
        abs((expected shr 16 and 0xFF) - (actual shr 16 and 0xFF)) < 24 &&
            abs((expected shr 8 and 0xFF) - (actual shr 8 and 0xFF)) < 24 &&
            abs((expected and 0xFF) - (actual and 0xFF)) < 24

    private fun assertColour(expected: Colour, actual: Int, message: String) {
        assertTrue(near(expected.argb and 0xFFFFFF, actual), "$message: expected %06X, got %06X".format(expected.argb and 0xFFFFFF, actual))
    }

    @Test
    fun `a shrunk panel is drawn where the picture put it with fewer draw calls`() = Gl.render {
        val canvas = GlCanvas()
        try {
            val (grown, grownCalls) = frame(canvas)
            // The same canvas, told it cannot transform: the way every scale was drawn before.
            val (pictured, picturedCalls) = frame(object : UiCanvas by canvas {
                override val transforms: Boolean get() = false
            })

            assertColour(red, grown.at(60, 60), "the left half, at half size")
            assertColour(blue, grown.at(100, 60), "the right half, at half size")
            assertColour(Colour.Black, grown.at(125, 60), "nothing past the shrunk panel")
            assertColour(Colour.Black, grown.at(60, 85), "or below it")

            // A picture is shrunk by sampling between its pixels, so where two colours meet — the
            // panel's edges and the seam down its middle — it blends a pixel the straight drawing
            // does not. Anywhere else the two must agree.
            val edges = setOf(39, 40, 79, 80, 119, 120)
            val elsewhere = grown.indices.filter { index ->
                !near(pictured[index], grown[index]) && index % Gl.size !in edges && index / Gl.size !in edges
            }
            assertTrue(
                elsewhere.isEmpty(),
                "${elsewhere.size} pixels away from an edge differ from the picture, first at " +
                    elsewhere.take(3).map { "(${it % Gl.size}, ${it / Gl.size})" },
            )
            assertTrue(grownCalls < picturedCalls, "no picture, fewer calls: $grownCalls against $picturedCalls")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a rounded clip inside a still scale is trimmed at the scaled corner as the picture trimmed it`() = Gl.render {
        // A 160 by 80 card with 40-unit corners, at half size from its corner: 80 by 40 at (40, 40),
        // corners of 20 pixels. The rounding is drawn in place, through the transform.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("card", 0f, 0f, 160f, 80f, Modifier.clip(corner = 40f).background(red), parent = panel)

        val canvas = GlCanvas()
        try {
            fun draw(on: UiCanvas): Pair<IntArray, Int> {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                on.begin(viewport)
                DrawPass(on).draw(screen.root)
                on.end()
                return Gl.readPixels(Gl.size, Gl.size) to on.drawCalls
            }
            val (grown, grownCalls) = draw(canvas)
            val (pictured, picturedCalls) = draw(object : UiCanvas by canvas {
                override val transforms: Boolean get() = false
            })

            assertColour(red, grown.at(60, 60), "the middle of the half-size card")
            assertColour(red, grown.at(80, 42), "its top edge between the corners")
            // 20-pixel corners: (43, 43) is about 24 pixels from the top-left corner's centre at (60, 60).
            assertColour(Colour.Black, grown.at(43, 43), "the top-left corner, cut")
            assertColour(Colour.Black, grown.at(116, 76), "the bottom-right corner, cut")
            assertColour(Colour.Black, grown.at(125, 60), "nothing past the card")

            // Only along the curves, where both soften the edge over a pixel, may the two differ.
            val differ = grown.indices.count { !near(pictured[it], grown[it]) }
            assertTrue(differ < 40, "$differ pixels differ from the picture")
            assertTrue(grownCalls < picturedCalls, "no picture, fewer calls: $grownCalls against $picturedCalls")
        } finally {
            canvas.close()
        }
    }

    /** An effect that paints its first 20 design units green and leaves the rest alone: how far it reaches. */
    private val band = ShaderEffect(
        ShaderSource(
            "band",
            """
            void main() {
                float inside = step(v_texCoord.x * u_size.x, 20.0);
                gl_FragColor = vec4(0.0, inside, 0.0, inside) * u_alpha;
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `an effect inside a still scale reaches as far as it did in the picture`() = Gl.render {
        // The same panel with an effect on a child: at half size the band is 10 pixels, not 20.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("banded", 0f, 0f, 160f, 80f, Modifier.effect(band).background(red), parent = panel)

        val canvas = GlCanvas()
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(screen.root)
            canvas.end()
            val pixels = Gl.readPixels(Gl.size, Gl.size)

            assertColour(Colour.rgb(0x00FF00), pixels.at(45, 60), "inside the band")
            assertColour(Colour.Black, pixels.at(55, 60), "past 20 design units of the half-size panel")
        } finally {
            canvas.close()
        }
    }
}
