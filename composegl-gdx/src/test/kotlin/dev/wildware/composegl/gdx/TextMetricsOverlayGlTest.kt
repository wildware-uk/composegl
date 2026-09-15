package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.debug.TextMetricsOverlay
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor

/**
 * `TextMetricsOverlay` on a real GPU, with a real font: the five lines in the pixels.
 *
 * The headless tests prove which lines are asked for, off made-up metrics. This proves that off a
 * real face — DejaVu Sans at 32, whose ascent, capitals and descent are its own — the GL canvas puts
 * a red, an orange, a green and a blue line where that face says, inside a cyan line box. The label
 * is drawn black on black, so the only thing on the screen is the overlay.
 */
class TextMetricsOverlayGlTest {

    private val style = TextStyle(family = "test", size = 32f)

    private fun fonts(): GdxFonts = GdxFonts().also {
        it.registerTrueType("test", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(32))
    }

    /** "HxH" at 100, 100, black, that turns the overlay on when clicked. */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Text(
                "HxH",
                Modifier.offset(100f, 100f).clickable { debug = true }.testTag("label"),
                textStyle = style,
                colour = Colour.rgb(0x000000),
            )
            TextMetricsOverlay(debug)
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    /**
     * The brightest pixel in the rows a one-unit line starting at [y] can have landed on.
     *
     * A face's metrics are fractions, so the line covers parts of two rows; which one holds the
     * most of it depends on the fraction and not on anything under test.
     */
    private fun Pixmap.lineAt(x: Int, y: Float): Color {
        val row = floor(y).toInt()
        return (row - 1..row + 1).map { at(x, it) }.maxBy { it.r + it.g + it.b }
    }

    @Test
    fun `a click draws the real face's lines through its text`() = Gl.render {
        val fonts = fonts()
        val backend = GdxBackend(fonts)
        val ui = screen(backend)
        try {
            val metrics = backend.fonts.metrics(style)
            val box = ui.node("label").layoutBoundsInRoot
            val baseline = box.top + ui.node("label").firstBaseline
            // Rows kept apart, so no two lines are read off the same pixels.
            assertTrue(metrics.ascent - metrics.capHeight >= 3f, "a real face has room above its capitals: $metrics")
            assertTrue(metrics.descent >= 3f, "and below its baseline: $metrics")
            val middle = ((box.left + box.right) / 2f).toInt()

            frame(ui).use {
                listOf(baseline - metrics.ascent, baseline - metrics.capHeight, baseline, baseline + metrics.descent).forEach { y ->
                    val pixel = it.lineAt(middle, y)
                    assertTrue(pixel.r + pixel.g + pixel.b < 0.1f, "nothing at $y before the click: $pixel")
                }
            }

            ui.click("label")

            frame(ui).use {
                assertEquals(100f, ui.node("label").boundsInRoot.left, "nothing moved")

                val ascent = it.lineAt(middle, baseline - metrics.ascent)
                assertTrue(ascent.r > 0.6f && ascent.g < 0.3f && ascent.b < 0.3f, "red on the ascent: $ascent")

                val cap = it.lineAt(middle, baseline - metrics.capHeight)
                assertTrue(cap.r > 0.6f && cap.g in 0.25f..0.75f && cap.b < 0.3f, "orange on the capitals: $cap")

                val green = it.lineAt(middle, baseline)
                assertTrue(green.g > 0.6f && green.r < 0.3f && green.b < 0.3f, "green on the baseline: $green")

                val descent = it.lineAt(middle, baseline + metrics.descent)
                assertTrue(descent.b > 0.6f && descent.r < 0.35f, "blue on the descent: $descent")

                val edge = it.at(box.left.toInt(), ((baseline - metrics.capHeight + baseline) / 2f).toInt())
                assertTrue(edge.g > 0.35f && edge.b > 0.35f && edge.r < 0.2f, "cyan down the line box's left edge: $edge")

                val between = it.at(middle, ((baseline - metrics.capHeight + baseline) / 2f).toInt())
                assertTrue(between.r + between.g + between.b < 0.1f, "and nothing between the lines: $between")
            }
        } finally {
            ui.close()
            backend.dispose()
            fonts.dispose()
        }
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }
}
