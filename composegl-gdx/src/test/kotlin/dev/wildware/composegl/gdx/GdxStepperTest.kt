package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Stepper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * A stepper, drawn by the real renderer through the stock skin.
 *
 * The recording canvas says which colour each arrow was asked for. This says the arrows and the
 * words actually came out in the pixels, that the dimmed arrow is visibly dimmer, and that a key
 * press changes what is on the screen — and keeps a golden of it for the rest.
 */
class GdxStepperTest {

    private val width = 240
    private val height = 48

    @Test
    fun `a stepper draws its arrows and value and a key press changes the picture`() {
        val (low, medium, arrows) = Gl.render {
            val fonts = GdxFonts()
            fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
            val canvas = GdxCanvas(atlas = fonts.atlas)
            val host = UiHost()
            val focus = FocusManager(host.root)
            val keys = KeyNavigator(focus)
            var quality by mutableStateOf("Low")
            var clock = 0L

            fun draw(): BufferedImage {
                repeat(2) {
                    host.frame(clock)
                    clock += 16_666_667L
                    MeasurePass().run(host.root, Constraints.atMost(width.toFloat(), height.toFloat()))
                    focus.refresh()
                }
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                DrawPass(canvas).draw(host.root)
                canvas.end()
                val frame = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
                // OpenGL hands back the bottom row first, and the design's top-left is the window's.
                return imageOf(width, height) { x, y -> frame.getPixel(x, Gl.size - 1 - y) ushr 8 }
                    .also { frame.dispose() }
            }

            try {
                host.setContent {
                    ProvideFonts(fonts) {
                        Stepper(
                            options = listOf("Low", "Medium", "High"),
                            selected = quality,
                            onSelect = { quality = it },
                            modifier = Modifier.testTag("stepper"),
                            initialFocus = true,
                        )
                    }
                }
                val low = draw()
                val pieces = host.root.find("stepper").children.map { it.boundsInRoot }

                keys.onKey(KeyEvent(Key.Right, KeyEventType.Down))
                keys.onKey(KeyEvent(Key.Right, KeyEventType.Up))
                val medium = draw()
                Triple(low, medium, pieces)
            } finally {
                host.dispose()
                canvas.dispose()
                fonts.dispose()
            }
        }

        val (left, value, right) = arrows
        assertTrue(brightest(low, right) > 120, "the right arrow is drawn: ${brightest(low, right)}")
        assertTrue(
            brightest(low, left) + 40 < brightest(low, right),
            "at Low the left arrow is dimmed: ${brightest(low, left)} against ${brightest(low, right)}",
        )
        assertTrue(inked(low, value) > 20, "the word is drawn in the middle: ${inked(low, value)} pixels")
        assertTrue(inked(medium, value) > inked(low, value), "Medium is more ink than Low")
        assertTrue(
            kotlin.math.abs(brightest(medium, left) - brightest(medium, right)) <= 30,
            "in the middle both arrows are live: ${brightest(medium, left)} and ${brightest(medium, right)}",
        )

        Goldens.assertMatches("stepper", medium)
    }

    /** The brightest channel of the brightest pixel inside [area]. */
    private fun brightest(image: BufferedImage, area: Rect): Int = pixels(image, area).maxOf { rgb ->
        maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
    }

    /** How many pixels inside [area] are text-bright rather than background-dark. */
    private fun inked(image: BufferedImage, area: Rect): Int = pixels(image, area).count { rgb ->
        (rgb shr 16 and 0xFF) > 150 && (rgb shr 8 and 0xFF) > 150 && (rgb and 0xFF) > 150
    }

    private fun pixels(image: BufferedImage, area: Rect): List<Int> = buildList {
        for (y in area.top.toInt() until area.bottom.toInt().coerceAtMost(image.height)) {
            for (x in area.left.toInt() until area.right.toInt().coerceAtMost(image.width)) {
                add(image.getRGB(x, y) and 0xFFFFFF)
            }
        }
    }

    /** The whole shared window, one design unit to one pixel, so the stepper sits in its top-left. */
    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )
}
