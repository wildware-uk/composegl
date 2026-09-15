package dev.wildware.composegl.korge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.TextMetricsOverlay
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
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * `TextMetricsOverlay` through KorGE with a real font, as the LibGDX backend's
 * `TextMetricsOverlayGlTest` checks it: the lines land where DejaVu Sans at 32 says, and those are
 * the numbers the glyphs themselves are placed with.
 */
class KorgeTextMetricsOverlayTest {

    private val size = KorgeGl.size.toFloat()
    private val style = TextStyle(family = "test", size = 32f)

    private fun backend() = KorgeBackend(KorgeFonts().also { it.registerTrueType("test", TestFonts.dejaVu(), listOf(32)) })

    /** "HxH" at 100, 100, in [colour], that turns the overlay on when clicked. */
    private fun screen(backend: KorgeBackend, colour: Colour = Colour.rgb(0x000000)): UiTest = uiTest(Size(size, size), backend) {
        var debug by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Text("HxH", Modifier.offset(100f, 100f).clickable { debug = true }.testTag("label"), textStyle = style, colour = colour)
            TextMetricsOverlay(debug)
        }
    }

    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    /** The brightest pixel in the rows a one-unit line starting at [y] can have landed on. */
    private fun Bitmap32.lineAt(x: Int, y: Float): Rgb {
        val row = floor(y).toInt()
        return (row - 1..row + 1).map { at(x, it) }.maxBy { it.r + it.g + it.b }
    }

    @Test
    fun `a click draws the real face's lines through its text`() {
        val backend = backend()
        val ui = screen(backend)
        try {
            val metrics = backend.fonts.metrics(style)
            val box = ui.node("label").layoutBoundsInRoot
            val baseline = box.top + ui.node("label").firstBaseline
            assertTrue(metrics.ascent - metrics.capHeight >= 3f, "a real face has room above its capitals: $metrics")
            assertTrue(metrics.descent >= 3f, "and below its baseline: $metrics")
            val middle = ((box.left + box.right) / 2f).toInt()

            frame(ui, backend).let {
                listOf(baseline - metrics.ascent, baseline - metrics.capHeight, baseline, baseline + metrics.descent).forEach { y ->
                    val pixel = it.lineAt(middle, y)
                    assertTrue(pixel.r + pixel.g + pixel.b < 0.1f, "nothing at $y before the click: $pixel")
                }
            }

            ui.click("label")

            frame(ui, backend).let {
                assertEquals(100f, ui.node("label").boundsInRoot.left, "nothing moved")

                val ascent = it.lineAt(middle, baseline - metrics.ascent)
                assertTrue(ascent.r > 0.6f && ascent.g < 0.3f && ascent.b < 0.3f, "red on the ascent: $ascent")

                // KorGE's cap height is DejaVu's own fraction, not a whole pixel as FreeType rounds it
                // for LibGDX, so the line shares two rows: judged by its hue rather than its strength.
                val cap = it.lineAt(middle, baseline - metrics.capHeight)
                assertTrue(cap.r > 0.45f && cap.g in cap.r * 0.35f..cap.r * 0.85f && cap.b < 0.2f, "orange on the capitals: $cap")

                val green = it.lineAt(middle, baseline)
                assertTrue(green.g > 0.6f && green.r < 0.3f && green.b < 0.3f, "green on the baseline: $green")

                val descent = it.lineAt(middle, baseline + metrics.descent)
                assertTrue(descent.b > 0.6f && descent.r < 0.35f, "blue on the descent: $descent")

                val edge = it.at(box.left.toInt(), ((baseline - metrics.capHeight + baseline) / 2f).toInt())
                assertTrue(edge.g > 0.35f && edge.b > 0.35f && edge.r < 0.2f, "cyan down the line box's left edge: $edge")
            }
        } finally {
            ui.close()
            backend.close()
        }
    }

    @Test
    fun `the capitals the overlay marks are the capitals that are drawn`() {
        val backend = backend()
        val ui = screen(backend, Colour.rgb(0xFFFFFF))
        try {
            val metrics = backend.fonts.metrics(style)
            val box = ui.node("label").layoutBoundsInRoot
            val baseline = box.top + ui.node("label").firstBaseline
            val image = frame(ui, backend)

            // The first H's left stem: where its ink starts and stops is the cap line and the baseline.
            val columns = box.left.toInt() until box.left.toInt() + 12
            val lit = (box.top.toInt() until box.bottom.toInt()).filter { y -> columns.any { x -> image.at(x, y).r > 0.5f } }
            assertTrue(abs(lit.last() + 1 - baseline) <= 1.5f, "the H stands on the baseline: ink ends at ${lit.last() + 1}, baseline $baseline")
            assertTrue(abs(lit.first() - (baseline - metrics.capHeight)) <= 1.5f, "and reaches the cap height: ink starts at ${lit.first()}, cap line ${baseline - metrics.capHeight}")
        } finally {
            ui.close()
            backend.close()
        }
    }
}
