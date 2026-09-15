package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateColour
import dev.wildware.composegl.ui.animation.animateFloat
import dev.wildware.composegl.ui.animation.updateTransition
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * One state moving a scale and a colour, in real pixels.
 *
 * The recording canvas says both values are half way on the same frame. What only a GPU can say is
 * that the picture comes out that way: the square shrunk to three quarters about its middle, and
 * filled with the colour half way between its two ends.
 */
class GdxTransitionTest {

    private val viewport = Viewport(
        design = Size(Scene.toFloat(), Scene.toFloat()),
        physical = Size(Scene.toFloat(), Scene.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xCC2020)
    private val blue = Colour.rgb(0x2020CC)

    @Test
    fun `half way through a transition the square is drawn smaller and in the colour between`() {
        val (before, halfway, after) = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            val renderer = UiRenderer(host, canvas)
            var on by mutableStateOf(false)
            var wall = 0L
            fun frame(millis: Long = 16L): BufferedImage {
                wall += millis * 1_000_000L
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                renderer.render(viewport, wall)
                return read()
            }

            try {
                host.setContent {
                    val t = updateTransition(on)
                    val spec = Tween(200, easing = Easings.Linear)
                    val scale by t.animateFloat({ spec }) { if (it) 0.5f else 1f }
                    val colour by t.animateColour({ spec }) { if (it) blue else red }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                        Box(Modifier.size(80f).scale(scale).background(colour))
                    }
                }
                repeat(3) { frame() }
                val before = frame()

                on = true
                // Two frames to start it, then a hundred milliseconds of a two hundred millisecond movement.
                frame(0)
                frame(0)
                frame(100)
                val halfway = frame(0)

                repeat(20) { frame() }
                Triple(before, halfway, frame())
            } finally {
                host.dispose()
                canvas.dispose()
            }
        }

        // Before: the full 80-pixel square, 10 to 90, red.
        assertClose(0xCC2020, before.getRGB(15, 50) and 0xFFFFFF, "before, the edge is red")

        // Half way: three quarters of the size, 20 to 80, so that edge is black, and the middle is the
        // colour half way from red to blue — both values on the same frame.
        assertClose(0x000000, halfway.getRGB(15, 50) and 0xFFFFFF, "the scale pulled the edge in")
        assertClose(0x762076, halfway.getRGB(50, 50) and 0xFFFFFF, "and the colour is half way")

        // After: half the size, 30 to 70, blue.
        assertClose(0x000000, after.getRGB(25, 50) and 0xFFFFFF, "after, half size")
        assertClose(0x2020CC, after.getRGB(50, 50) and 0xFFFFFF, "and blue")

        Goldens.assertMatches("transition-halfway", halfway)
    }

    private fun assertClose(expected: Int, actual: Int, message: String) {
        for (shift in intArrayOf(16, 8, 0)) {
            val want = expected shr shift and 0xFF
            val got = actual shr shift and 0xFF
            assertTrue(kotlin.math.abs(want - got) <= 24, "$message: expected ${hex(expected)}, got ${hex(actual)}")
        }
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb)

    /** The scene's square in the bottom-left corner of the shared window, the right way up. */
    private fun read(): BufferedImage =
        Pixmap.createFromFrameBuffer(0, 0, Scene, Scene).let { frame ->
            imageOf(Scene, Scene) { x, y -> frame.getPixel(x, Scene - 1 - y) ushr 8 }.also { frame.dispose() }
        }

    private companion object {
        const val Scene = 100
    }
}
