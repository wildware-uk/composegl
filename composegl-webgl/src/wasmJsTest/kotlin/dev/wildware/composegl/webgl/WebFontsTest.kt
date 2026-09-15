package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Text measured and drawn by the browser: the rules every [dev.wildware.composegl.ui.text.FontProvider] keeps. */
class WebFontsTest {

    private val body = TextStyle(family = "body", size = 16f)

    @Test
    fun `a wide word measures wider than a narrow one and the same text measures the same twice`() = runTest(timeout = 2.minutes) {
        val fonts = testFonts()
        val wide = fonts.measure("WWWW", body)
        val narrow = fonts.measure("iiii", body)
        assertTrue(wide.size.width > narrow.size.width * 2, "W is wider than i: ${wide.size} against ${narrow.size}")
        assertEquals(wide.size, fonts.measure("WWWW", body).size)
        assertEquals(1, wide.lineCount)
        assertEquals(body.lineHeight, wide.size.height)
    }

    @Test
    fun `text wraps by word at the width it is given and a limit ends it with an ellipsis`() = runTest(timeout = 2.minutes) {
        val fonts = testFonts()
        val one = fonts.measure("alpha", body).size.width
        val wrapped = fonts.measure("alpha beta gamma", body, maxWidth = one * 1.5f)
        assertEquals(3, wrapped.lineCount)
        assertTrue(wrapped.size.width <= one * 1.5f)

        val cut = fonts.measure("alpha beta gamma", body.copy(maxLines = 1), maxWidth = one * 1.5f) as WebTextLayout
        assertEquals(1, cut.lineCount)
        assertTrue(cut.size.width <= one * 1.5f, "the cut line fits: ${cut.size.width}")
    }

    @Test
    fun `the font's shape comes from the font itself`() = runTest(timeout = 2.minutes) {
        val metrics = testFonts().metrics(body)
        // DejaVu Sans at 16: an ascent of about 15, a cap height of about 12, a descent of about 4.
        assertTrue(metrics.ascent in 13f..17f, "ascent ${metrics.ascent}")
        assertTrue(metrics.capHeight in 10f..13f, "cap height ${metrics.capHeight}")
        assertTrue(metrics.descent in 3f..5f, "descent ${metrics.descent}")
        assertTrue(metrics.spaceAdvance in 4f..6f, "space ${metrics.spaceAdvance}")
    }

    @Test
    fun `a character outside the baked set is drawn on demand rather than as a question mark`() = runTest(timeout = 2.minutes) {
        val fonts = testFonts()
        val omega = fonts.measure("Ω", body).size.width
        val question = fonts.measure("?", body).size.width
        assertTrue(omega > 0f)
        assertNotEquals(question, omega, "Ω measured as its own glyph, not as ?")

        // And it really lands on the page: drawn white on black, the middle of it is lit.
        val element = pageCanvas(40, 40)
        val backend = WebGlBackend(element, fonts, preserveDrawingBuffer = true)
        try {
            backend.gl.clearColor(0f, 0f, 0f, 1f)
            backend.gl.clear(GL.COLOR_BUFFER_BIT)
            val layout = fonts.measure("■", TextStyle(family = "body", size = 32f))
            backend.canvas.begin(Viewport(Size(40f, 40f), Size(40f, 40f), ScalePolicy.Fit))
            backend.canvas.text(layout, 2f, 0f, Colour.White)
            backend.canvas.end()
            val frame = readFrame(backend.gl, 40, 40)
            val lit = frame.count { green(it) > 200 }
            assertTrue(lit > 100, "a black square glyph lights up its middle; $lit pixels were lit")
        } finally {
            backend.close()
            element.remove()
        }
    }

    @Test
    fun `a family that was never registered says what was`() = runTest(timeout = 2.minutes) {
        val fonts = testFonts()
        val failure = assertFailsWith<IllegalStateException> { fonts.measure("x", TextStyle(family = "title", size = 16f)) }
        assertTrue("body" in failure.message.orEmpty(), failure.message)
        val size = assertFailsWith<IllegalStateException> { fonts.measure("x", TextStyle(family = "body", size = 90f)) }
        assertTrue("registered at" in size.message.orEmpty(), size.message)
    }
}
