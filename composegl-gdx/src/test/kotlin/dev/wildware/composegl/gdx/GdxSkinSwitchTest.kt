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
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Stepper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * The player picks high contrast from a stepper, and the real renderer draws it in the next frame.
 *
 * The recording canvas says which colour each box was asked for. This says the pixels changed: the
 * button's grey-blue became black, its edge became white, and the stepper that has focus is ringed
 * in yellow — and keeps a golden of the high-contrast screen for the rest.
 */
class GdxSkinSwitchTest {

    private val width = 260
    private val height = 100

    @Test
    fun `picking high contrast from a stepper redraws the screen in it`() {
        val (standard, contrast, boxes) = Gl.render {
            val fonts = GdxFonts()
            fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
            val canvas = GdxCanvas(atlas = fonts.atlas)
            val host = UiHost()
            val focus = FocusManager(host.root)
            val keys = KeyNavigator(focus)
            var skin by mutableStateOf(Skin.Default)
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
                        ProvideSkin(skin) {
                            Column {
                                Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
                                Stepper(
                                    options = listOf(Skin.Default, Skin.HighContrast),
                                    selected = skin,
                                    onSelect = { skin = it },
                                    label = { it.name },
                                    modifier = Modifier.testTag("skin"),
                                    initialFocus = true,
                                )
                            }
                        }
                    }
                }
                val standard = draw()
                val before = listOf("play", "skin").map { host.root.find(it).boundsInRoot }

                keys.onKey(KeyEvent(Key.Right, KeyEventType.Down))
                keys.onKey(KeyEvent(Key.Right, KeyEventType.Up))
                val contrast = draw()
                val after = listOf("play", "skin").map { host.root.find(it).boundsInRoot }
                assertEquals(before, after, "the screen does not move when the skin changes")
                Triple(standard, contrast, after)
            } finally {
                host.dispose()
                canvas.dispose()
                fonts.dispose()
            }
        }

        val (play, stepper) = boxes
        // Just inside the button's left edge, halfway down: background, clear of both border and word.
        val inside = pixel(play.left.toInt() + 5, play.centre.y.toInt())
        assertEquals(0x232A35, standard.rgbAt(inside), "the standard button is its grey-blue")
        assertTrue(close(contrast.rgbAt(inside), 0x000000), "and the high-contrast one is black: ${hex(contrast.rgbAt(inside))}")

        // A row in from the top: the border is two pixels, and its outer row is shared with the edge.
        val edge = pixel(play.centre.x.toInt(), play.top.toInt() + 1)
        // Near white rather than white: the rounded border is smoothed, so no row of it is pure.
        assertTrue(channels(contrast.rgbAt(edge)).all { it >= 0xC0 }, "with a white edge: ${hex(contrast.rgbAt(edge))}")
        assertTrue(channels(standard.rgbAt(edge)).all { it < 0x80 }, "where the standard edge is dark: ${hex(standard.rgbAt(edge))}")

        val ring = pixel(stepper.left.toInt() + 20, stepper.bottom.toInt() - 1)
        assertTrue(yellowish(contrast.rgbAt(ring)), "the focused stepper is ringed in yellow: ${hex(contrast.rgbAt(ring))}")
        assertTrue(!yellowish(standard.rgbAt(ring)), "which it was not before: ${hex(standard.rgbAt(ring))}")

        Goldens.assertMatches("skin-switch-high-contrast", contrast)
    }

    private fun pixel(x: Int, y: Int) = x to y

    private fun BufferedImage.rgbAt(at: Pair<Int, Int>) = getRGB(at.first, at.second) and 0xFFFFFF

    private fun close(actual: Int, expected: Int): Boolean = listOf(16, 8, 0).all { shift ->
        kotlin.math.abs((actual shr shift and 0xFF) - (expected shr shift and 0xFF)) <= 12
    }

    private fun channels(rgb: Int) = listOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

    /** Strong red and green with little blue: the high-contrast focus colour, smoothed or not. */
    private fun yellowish(rgb: Int): Boolean {
        val (r, g, b) = channels(rgb)
        return r >= 0xA0 && g >= 0x80 && b < 0x60
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb)

    /** The whole shared window, one design unit to one pixel, so the screen sits in its top-left. */
    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )
}
