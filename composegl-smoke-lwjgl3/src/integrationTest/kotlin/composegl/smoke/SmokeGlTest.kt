package composegl.smoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import composegl.ComposeGlContext
import composegl.ComposeSurface
import composegl.ContextConfig
import composegl.RenderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL32C

/**
 * ComposeGL on a raw LWJGL3 host: no LibGDX, no engine, nothing but GLFW and OpenGL.
 *
 * These are the tests the headless suite cannot write, because they need a driver to answer.
 */
class SmokeGlTest {

    private fun host(width: Int = 800, height: Int = 600, block: (GlHost) -> Unit) {
        val host = GlHost(width, height)
        host.start()
        try {
            block(host)
        } finally {
            host.close()
        }
    }

    private fun ComposeSurface.frame(nanos: Long) {
        update(nanos)
        if (needsRedraw) render(nanos)
    }

    @Test
    fun `a Compose button rendered into a framebuffer matches its golden image`() {
        host(320, 200) { host ->
            val context = ComposeGlContext.create(ContextConfig(debugChecks = true))
            val surface = ComposeSurface(context, host.hostServices)
            surface.setContent {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    // No text: glyph rasterisation differs enough between font stacks that a
                    // labelled button would make this a font test, not a rendering test.
                    Button(onClick = {}, modifier = Modifier.align(Alignment.Center)) {
                        Box(Modifier.size(80.dp, 24.dp))
                    }
                }
            }
            surface.setRenderTarget(RenderTarget.Gl(host.frameBufferId, 320, 200))
            surface.frame(0)
            host.resetGlState()

            val pixels = host.readFrameBuffer()
            assertEquals(GlHost.NO_ERROR, host.glError())
            Golden.assertMatches("button", pixels, 320, 200)

            surface.dispose()
            context.dispose()
        }
    }

    @Test
    fun `raw GL still works after a Compose render and the state reset`() {
        host(200, 200) { host ->
            val context = ComposeGlContext.create()
            val surface = ComposeSurface(context, host.hostServices)
            surface.setContent { Box(Modifier.fillMaxSize().background(Color(0x80FF0000))) }
            surface.setRenderTarget(RenderTarget.Gl(host.frameBufferId, 200, 200))

            host.clear(0f, 0f, 0f, 1f)
            surface.frame(0)
            host.resetGlState()
            host.blit()

            // Now draw with plain GL, after Compose. If Skia left anything bound, scissored or
            // masked, this lands in the wrong place or not at all.
            GL32C.glEnable(GL32C.GL_SCISSOR_TEST)
            GL32C.glScissor(0, 0, 40, 40)
            GL32C.glClearColor(0f, 1f, 0f, 1f)
            GL32C.glClear(GL32C.GL_COLOR_BUFFER_BIT)
            GL32C.glDisable(GL32C.GL_SCISSOR_TEST)

            val window = host.readWindow()
            fun at(x: Int, y: Int) = window[y * 200 + x]

            assertEquals(0xFF00FF00.toInt(), at(10, 190), "the raw GL square is missing or misplaced")
            assertEquals(GlHost.NO_ERROR, host.glError())

            val hudPixel = at(100, 100)
            assertTrue(((hudPixel shr 16) and 0xff) > 100, "the HUD should still be over the middle")

            surface.dispose()
            context.dispose()
        }
    }

    @Test
    fun `resizing up, down, to nothing and back leaves a correct frame`() {
        host(800, 600) { host ->
            val context = ComposeGlContext.create()
            val surface = ComposeSurface(context, host.hostServices)
            surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }

            var nanos = 0L
            fun sizeTo(width: Int, height: Int) {
                host.resize(width, height)
                surface.setRenderTarget(RenderTarget.Gl(host.frameBufferId, width, height))
                nanos += 16_666_667
                surface.frame(nanos)
                host.resetGlState()
                assertEquals(GlHost.NO_ERROR, host.glError(), "GL error after resizing to ${width}x$height")
            }

            sizeTo(800, 600)
            sizeTo(1024, 768)

            val beforeMinimising = surface.stats.composeRenders
            sizeTo(0, 0)
            assertEquals(beforeMinimising, surface.stats.composeRenders, "a minimised window draws nothing")

            sizeTo(800, 600)

            val pixels = host.readFrameBuffer()
            assertEquals(800 * 600, pixels.size)
            assertEquals(0xFFFF0000.toInt(), pixels[0], "the corner should be red again")
            assertEquals(0xFFFF0000.toInt(), pixels[pixels.size - 1])

            surface.dispose()
            context.dispose()
        }
    }
}
