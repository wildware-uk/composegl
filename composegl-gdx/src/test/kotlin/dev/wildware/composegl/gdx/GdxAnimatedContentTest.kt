package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.animation.AnimatedContent
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.slideLeft
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * One card sliding out as the next slides in, in real pixels.
 *
 * The recording canvas says where each card was placed and what rectangle it was cut off by. What
 * only a GPU can say is that the cut really happens: a carousel 60 wide between two grey strips,
 * half way through a slide, has the red card's right half on its left, the blue card's left half on
 * its right, and neither card spilling onto the grey either side.
 */
class GdxAnimatedContentTest {

    private val viewport = Viewport(
        design = Size(Scene.toFloat(), Scene.toFloat()),
        physical = Size(Scene.toFloat(), Scene.toFloat()),
        policy = ScalePolicy.Fit,
    )

    @Test
    fun `half way through a slide both cards share the carousel and neither spills past its edges`() {
        val (before, halfway, after) = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            val renderer = UiRenderer(host, canvas)
            var card by mutableStateOf(0)
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
                    Row {
                        Box(Modifier.size(20f, 100f).background(Grey))
                        AnimatedContent(card, transition = { _, _ -> slideLeft(Tween(200, easing = Easings.Linear)) }) { shown ->
                            Box(Modifier.size(60f, 100f).background(if (shown == 0) Red else Blue))
                        }
                        Box(Modifier.size(20f, 100f).background(Grey))
                    }
                }
                repeat(3) { frame() }
                val before = frame()

                card = 1
                // Two frames to start it, then a hundred milliseconds of a two hundred millisecond slide.
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

        // Before: grey 0-20, red 20-80, grey 80-100.
        assertClose(0x808080, before.getRGB(10, 50), "before, the left strip is grey")
        assertClose(0xFF0000, before.getRGB(50, 50), "the carousel red")
        assertClose(0x808080, before.getRGB(90, 50), "and the right strip grey")

        // Half way: red has slid half a card left, so it fills 20-50 and blue fills 50-80.
        assertClose(0xFF0000, halfway.getRGB(30, 50), "the old card's right half on the left of the carousel")
        assertClose(0x0000FF, halfway.getRGB(70, 50), "the new card's left half on the right")
        assertClose(0x808080, halfway.getRGB(10, 50), "the red card is cut off rather than drawn over the left strip")
        assertClose(0x808080, halfway.getRGB(90, 50), "and the blue card over the right one")

        // After: blue alone in the carousel.
        assertClose(0x0000FF, after.getRGB(30, 50), "after, blue across the carousel")
        assertClose(0x0000FF, after.getRGB(70, 50), "all of it")
        assertClose(0x808080, after.getRGB(90, 50), "and the strips untouched")

        Goldens.assertMatches("animated-content-halfway", halfway)
    }

    private fun assertClose(expected: Int, actual: Int, message: String) {
        for (shift in intArrayOf(16, 8, 0)) {
            val want = expected shr shift and 0xFF
            val got = actual shr shift and 0xFF
            assertTrue(kotlin.math.abs(want - got) <= 24, "$message: expected ${hex(expected)}, got ${hex(actual)}")
        }
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

    /** The scene's square in the bottom-left corner of the shared window, the right way up. */
    private fun read(): BufferedImage =
        Pixmap.createFromFrameBuffer(0, 0, Scene, Scene).let { frame ->
            imageOf(Scene, Scene) { x, y -> frame.getPixel(x, Scene - 1 - y) ushr 8 }.also { frame.dispose() }
        }

    private companion object {
        const val Scene = 100
        val Grey = Colour.rgb(0x808080)
        val Red = Colour.rgb(0xFF0000)
        val Blue = Colour.rgb(0x0000FF)
    }
}
