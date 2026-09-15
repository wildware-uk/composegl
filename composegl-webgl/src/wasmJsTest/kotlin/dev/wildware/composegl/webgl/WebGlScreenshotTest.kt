package dev.wildware.composegl.webgl

import dev.wildware.composegl.testing.Scene
import dev.wildware.composegl.testing.SceneArt
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.bevel
import dev.wildware.composegl.testing.scenes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The same scenes both desktop backends draw, drawn with WebGL in a real browser.
 *
 * Two checks. Every scene against this backend's own goldens, as the desktop backends are held to
 * theirs. And the scenes with no text in them against the raw OpenGL backend's goldens — the same
 * pictures, by the same rule — because shapes, clips, layers and effects have no reason to differ
 * between OpenGL and WebGL, and a scene that does has found an assumption one backend leaked. Text is
 * left out of that second check only because stb_truetype and a browser will never agree on a glyph.
 */
class WebGlScreenshotTest {

    @Test
    fun `every scene matches its golden`() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        drawEach(scenes()) { scene, pixels ->
            runCatching { assertMatchesGolden(scene.name, SceneSize, SceneSize, pixels) }
                .onFailure { failures += it.message.orEmpty() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the scenes without text match the raw OpenGL backend's goldens`() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        drawEach(scenes().filter { it.name in WithoutText }) { scene, pixels ->
            runCatching { assertMatchesGolden(scene.name, SceneSize, SceneSize, pixels, desktop = true) }
                .onFailure { failures += it.message.orEmpty() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private suspend fun drawEach(list: List<Scene>, check: suspend (Scene, IntArray) -> Unit) {
        val fonts = testFonts()
        val element = pageCanvas(SceneSize, SceneSize)
        val backend = WebGlBackend(element, fonts, preserveDrawingBuffer = true)
        val gl = backend.gl
        val bevel = bevel()
        val art = WebGlTexture.rgba(gl, bevel.width, bevel.height, bevel.pixels, smooth = false)
        val viewport = Viewport(Size(SceneSize.toFloat(), SceneSize.toFloat()), Size(SceneSize.toFloat(), SceneSize.toFloat()), ScalePolicy.Fit)
        try {
            for (scene in list) {
                gl.clearColor(0f, 0f, 0f, 1f)
                gl.clear(GL.COLOR_BUFFER_BIT)
                backend.canvas.begin(viewport)
                scene.draw(backend.canvas, SceneArt(fonts = fonts, panel = NinePatch(art, slice = Padding.all(8f))))
                backend.canvas.end()
                check(scene, readFrame(gl, SceneSize, SceneSize))
            }
        } finally {
            art.close()
            backend.close()
            element.remove()
        }
    }

    private companion object {
        /** Filled in from what the scenes draw: the ones that never call `text`. */
        val WithoutText = setOf(
            "borders", "corners", "gradients", "nine-patch", "particles", "per-corner", "rotation-and-glow", "shadow",
        )
    }
}
