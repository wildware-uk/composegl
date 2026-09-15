package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.fadeIn
import dev.wildware.composegl.ui.animation.fadeOut
import dev.wildware.composegl.ui.animation.scaleIn
import dev.wildware.composegl.ui.animation.scaleOut
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
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * A panel leaving, in real pixels.
 *
 * The recording canvas already says the panel is drawn through a picture at a fraction of its size
 * and opacity while it leaves. What only a GPU can say is that the picture comes out that way: the
 * blue panel shrunk about its middle and blended half way into the black behind it, and then gone.
 */
class GdxAnimatedVisibilityTest {

    private val viewport = Viewport(
        design = Size(Scene.toFloat(), Scene.toFloat()),
        physical = Size(Scene.toFloat(), Scene.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val blue = Colour.rgb(0x3366CC)

    @Test
    fun `a panel half way through its exit is drawn smaller and fainter and then not at all`() {
        val (open, halfway, gone) = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            val renderer = UiRenderer(host, canvas)
            var visible by mutableStateOf(true)
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
                        val out = Tween(200, easing = Easings.Linear)
                        AnimatedVisibility(
                            visible,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut(spec = out) + scaleOut(to = 0.5f, spec = out),
                        ) {
                            Box(Modifier.size(80f).background(blue).padding(20f)) {
                                Box(Modifier.size(40f).background(Colour.White))
                            }
                        }
                    }
                }
                repeat(3) { frame() }
                val open = frame()

                visible = false
                // Two frames to start it, then a hundred milliseconds of a two hundred millisecond exit.
                frame(0)
                frame(0)
                frame(100)
                val halfway = frame(0)

                repeat(20) { frame() }
                Triple(open, halfway, frame())
            } finally {
                host.dispose()
                canvas.dispose()
            }
        }

        // Open: the full 80-pixel panel, centred, so it spans 10 to 90 across the 100-pixel scene.
        assertClose(0x3366CC, open.getRGB(15, 50) and 0xFFFFFF, "open, the panel's edge is blue")
        assertClose(0xFFFFFF, open.getRGB(50, 50) and 0xFFFFFF, "and its middle is white")

        // Half way: three quarters of the size about the middle, 20 to 80, so that edge is now black,
        // and what is left is blended half way to the black behind it.
        assertClose(0x000000, halfway.getRGB(15, 50) and 0xFFFFFF, "the scale pulled the edge in")
        val middle = halfway.getRGB(50, 50) and 0xFF
        assertTrue(middle in 90..170, "the fade left the white middle at about half, and it was $middle")

        assertClose(0x000000, gone.getRGB(50, 50) and 0xFFFFFF, "gone, nothing is drawn")

        Goldens.assertMatches("animated-visibility-exit", halfway)
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
