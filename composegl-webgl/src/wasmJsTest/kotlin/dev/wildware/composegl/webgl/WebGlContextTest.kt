package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlslDialect
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The context under the renderer: which WebGL it is, what happens when the browser takes it away and
 * gives it back, and a render target drawn on it.
 */
class WebGlContextTest {

    private val viewport = Viewport(Size(64f, 64f), Size(64f, 64f), ScalePolicy.Fit)

    @Test
    fun `the renderer compiles for the WebGL the run asked for`() = runTest(timeout = 2.minutes) {
        val element = pageCanvas(64, 64)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        try {
            backend.canvas.warmUp()
            val asked = askedForWebGl()
            val device = backend.canvas.device as GlDevice
            assertEquals(asked == 2, backend.isWebGl2, "the run asked for WebGL $asked")
            assertEquals(if (asked == 2) GlslDialect.Es300 else GlslDialect.Es100, device.dialect)
        } finally {
            backend.close()
            element.remove()
        }
    }

    @Test
    fun `a lost context is built again and the next frame draws the same`() = runTest(timeout = 2.minutes) {
        val element = pageCanvas(64, 64)
        val fonts = testFonts()
        val backend = WebGlBackend(element, fonts, preserveDrawingBuffer = true)
        val square = fonts.measure("■", TextStyle(family = "body", size = 32f))
        fun draw(): IntArray {
            backend.gl.clearColor(0f, 0f, 0f, 1f)
            backend.gl.clear(GL.COLOR_BUFFER_BIT)
            backend.canvas.begin(viewport)
            backend.canvas.rect(Rect.of(0f, 0f, 16f, 16f), Colour.rgb(0xFF0000))
            backend.canvas.text(square, 24f, 16f, Colour.rgb(0x00FF00))
            backend.canvas.end()
            return readFrame(backend.gl, 64, 64)
        }
        try {
            val before = draw()
            assertTrue(red(before[8 * 64 + 8]) > 230, "red before")
            val litBefore = before.count { green(it) > 200 }
            assertTrue(litBefore > 100, "the glyph is drawn before: $litBefore")

            loseAndRestore(element).await<JsAny?>()

            val after = draw()
            assertTrue(red(after[8 * 64 + 8]) > 230, "red after the context came back")
            assertEquals(litBefore, after.count { green(it) > 200 }, "the atlas went up again from memory")
        } finally {
            backend.close()
            element.remove()
        }
    }

    @Test
    fun `a render target holds what was drawn into it premultiplied`() = runTest(timeout = 2.minutes) {
        val element = pageCanvas(32, 32)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        val target = WebGlRenderTarget(backend.gl, 16, 16)
        try {
            target.draw(backend.canvas) {
                backend.canvas.rect(Rect.of(0f, 0f, 16f, 16f), Colour(0x80FFFFFF.toInt()))
            }
            val pixels = target.readPixels()
            assertEquals(16 * 16 * 4, pixels.size)
            // The middle, away from the softened edge.
            val middle = (8 * 16 + 8) * 4
            val alpha = pixels[middle + 3].toInt() and 0xFF
            val red = pixels[middle].toInt() and 0xFF
            assertTrue(alpha in 120..136, "half opaque: $alpha")
            assertTrue(red in alpha - 4..alpha + 4, "white at half opacity is premultiplied to its alpha: $red")

            target.resize(16, 8)
            assertEquals(16, target.texture.width)
            assertEquals(16 * 8 * 4, target.readPixels().size)
        } finally {
            target.close()
            backend.close()
            element.remove()
        }
    }
}

/** Which WebGL the Karma run was started for: 2 normally, 1 for `wasmJsBrowserWebGl1Test`. */
private fun askedForWebGl(): Int = js("(() => { const k = window.__karma__ || (window.parent && window.parent.__karma__); return (k && k.config && k.config.composeglWebGl) || 2; })()")

/** Loses [canvas]'s context the way a driver reset would, gives it back, and resolves once it is back. */
private fun loseAndRestore(canvas: HTMLCanvasElement): Promise<JsAny?> = js(
    """new Promise((resolve) => {
        const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
        const lose = gl.getExtension('WEBGL_lose_context');
        canvas.addEventListener('webglcontextlost', () => setTimeout(() => lose.restoreContext(), 0), { once: true });
        canvas.addEventListener('webglcontextrestored', () => setTimeout(() => resolve(null), 0), { once: true });
        lose.loseContext();
    })""",
)
