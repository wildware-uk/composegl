package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.animation.Crossfade
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
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
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * One page fading into another, in real pixels.
 *
 * The recording canvas says both pages are drawn at about half opacity half way through. What only a
 * GPU can say is that they blend: a wide red page and a narrow blue one over black, where the blue
 * overlaps the red the pixel is both at once, and where only the red is it is half dark.
 */
class GdxCrossfadeTest {

    private val viewport = Viewport(
        design = Size(Scene.toFloat(), Scene.toFloat()),
        physical = Size(Scene.toFloat(), Scene.toFloat()),
        policy = ScalePolicy.Fit,
    )

    @Test
    fun `half way through a crossfade both pages are blended together and then only the new one is left`() {
        val (before, halfway, after) = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            val renderer = UiRenderer(host, canvas)
            var blue by mutableStateOf(false)
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
                    Crossfade(
                        blue,
                        Modifier.fillMaxSize(),
                        spec = Tween(200, easing = Easings.Linear),
                        contentAlignment = Alignment.Centre,
                    ) { isBlue ->
                        if (isBlue) {
                            Box(Modifier.size(40f, 80f).background(Colour.rgb(0x0000FF)))
                        } else {
                            Box(Modifier.size(80f).background(Colour.rgb(0xFF0000)))
                        }
                    }
                }
                repeat(3) { frame() }
                val before = frame()

                blue = true
                // Two frames to start it, then a hundred milliseconds of a two hundred millisecond fade.
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

        // Before: the red page alone, 10 to 90 each way across the 100-pixel scene.
        assertClose(0xFF0000, before.getRGB(50, 50) and 0xFFFFFF, "before, the middle is red")
        assertClose(0xFF0000, before.getRGB(15, 50) and 0xFFFFFF, "and so is the edge")

        // Half way: the red edge, where the narrow blue page does not reach, is red at half strength.
        val edge = halfway.getRGB(15, 50)
        assertTrue(edge shr 16 and 0xFF in 90..170, "the leaving page is half faded at its edge: ${hex(edge)}")
        assertTrue(edge and 0xFF < 24, "and nothing blue is there: ${hex(edge)}")
        // In the middle the blue page is half way in over a half faded red one: some of each.
        val middle = halfway.getRGB(50, 50)
        assertTrue(middle shr 16 and 0xFF in 30..140, "the red still shows through the middle: ${hex(middle)}")
        assertTrue(middle and 0xFF in 90..170, "under half-strength blue: ${hex(middle)}")

        // After: the blue page alone, and the red edge gone to black.
        assertClose(0x0000FF, after.getRGB(50, 50) and 0xFFFFFF, "after, the middle is blue")
        assertClose(0x000000, after.getRGB(15, 50) and 0xFFFFFF, "and the old page is gone from the edge")

        Goldens.assertMatches("crossfade-halfway", halfway)
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
    }
}
