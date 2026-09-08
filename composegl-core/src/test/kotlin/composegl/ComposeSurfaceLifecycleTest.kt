package composegl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** JUnit's assertNotNull returns Unit; this one hands the value back. */
private fun <T : Any> notNull(value: T?): T = value ?: error("expected a value, got null")

/** A host with nothing behind it: enough to drive a raster surface in a test. */
private class TestHost(override val density: Float = 1f) : HostServices {
    var frameRequests = 0
    override fun requestFrame() { frameRequests++ }
}

class ComposeSurfaceLifecycleTest {

    private val context = ComposeGlContext.createRaster(ContextConfig(debugChecks = true))
    private val host = TestHost()
    private var nanos = 0L

    private fun surface(config: SurfaceConfig = SurfaceConfig()) = ComposeSurface(context, host, config)

    private fun ComposeSurface.frame() {
        nanos += 16_666_667
        update(nanos)
        if (needsRedraw) render(nanos)
    }

    @AfterEach
    fun tearDown() = context.dispose()

    @Test
    fun `renders content into a raster target`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        surface.setRenderTarget(RenderTarget.Raster(32, 16))
        surface.frame()

        val pixels = notNull(surface.readPixels())
        assertEquals(32 * 16, pixels.size)
        assertEquals(0xFFFF0000.toInt(), pixels[16 * 32 / 2 + 16], "centre pixel should be opaque red")
        assertEquals(1L, surface.stats.composeRenders)
        assertTrue(surface.stats.lastRenderNanos > 0)
    }

    @Test
    fun `static content costs no renders after the first`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
        surface.setRenderTarget(RenderTarget.Raster(16, 16))
        surface.frame()
        assertEquals(1L, surface.stats.composeRenders)

        repeat(100) { surface.frame() }

        assertEquals(1L, surface.stats.composeRenders, "static content must not redraw")
        assertEquals(101L, surface.stats.frames)
        assertFalse(surface.needsRedraw)
    }

    @Test
    fun `a state change causes exactly one more render`() {
        var color by mutableStateOf(Color.Red)
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize().background(color)) }
        surface.setRenderTarget(RenderTarget.Raster(16, 16))
        repeat(3) { surface.frame() }
        assertEquals(1L, surface.stats.composeRenders)

        color = Color.Green
        surface.frame()
        assertEquals(2L, surface.stats.composeRenders)
        assertEquals(0xFF00FF00.toInt(), notNull(surface.readPixels())[0])

        repeat(5) { surface.frame() }
        assertEquals(2L, surface.stats.composeRenders, "it must settle again")
    }

    @Test
    fun `resizing recreates the surface and redraws`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        surface.setRenderTarget(RenderTarget.Raster(8, 8))
        surface.frame()
        assertEquals(64, notNull(surface.readPixels()).size)

        surface.setRenderTarget(RenderTarget.Raster(20, 10))
        assertTrue(surface.needsRedraw, "a new target is always stale")
        surface.frame()
        assertEquals(200, notNull(surface.readPixels()).size)
    }

    @Test
    fun `a zero-sized target is legal and renders nothing`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        surface.setRenderTarget(RenderTarget.Raster(0, 0))

        assertFalse(surface.needsRedraw)
        surface.frame()
        assertEquals(0L, surface.stats.composeRenders)
        assertNull(surface.readPixels())

        // and it comes back
        surface.setRenderTarget(RenderTarget.Raster(8, 8))
        surface.frame()
        assertEquals(1L, surface.stats.composeRenders)
    }

    @Test
    fun `two surfaces share one context and render independently`() {
        val a = surface()
        val b = surface()
        a.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        b.setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
        a.setRenderTarget(RenderTarget.Raster(4, 4))
        b.setRenderTarget(RenderTarget.Raster(4, 4))
        a.frame(); b.frame()

        assertEquals(0xFFFF0000.toInt(), notNull(a.readPixels())[0])
        assertEquals(0xFF0000FF.toInt(), notNull(b.readPixels())[0])

        a.dispose()
        b.frame()
        assertEquals(1L, b.stats.composeRenders, "disposing one surface must not disturb the other")
    }

    @Test
    fun `dispose is idempotent, and so is the context`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        surface.frame()
        surface.dispose()
        surface.dispose()
        context.dispose()
        context.dispose()
        assertTrue(context.isDisposed)
    }

    @Test
    fun `disposing the context disposes its surfaces`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        context.dispose()
        assertThrows<IllegalStateException> { surface.setRenderTarget(RenderTarget.Raster(8, 8)) }
    }

    @Test
    fun `a raster context refuses a GL target with a useful message`() {
        val surface = surface()
        val thrown = assertThrows<ComposeGlUnsupportedException> {
            surface.setRenderTarget(RenderTarget.Gl(framebufferId = 1, width = 8, height = 8))
        }
        assertTrue(thrown.message!!.contains("createRaster()"), thrown.message)
    }

    @Test
    fun `debug checks catch a call from the wrong thread`() {
        val surface = surface()
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))

        var thrown: Throwable? = null
        val t = Thread { thrown = runCatching { surface.update(1) }.exceptionOrNull() }
        t.start(); t.join()

        val message = notNull(thrown).message.orEmpty()
        assertTrue(message.startsWith("ComposeGL must be used on the thread"), message)
    }

    @Test
    fun `content that throws is reported and does not keep throwing`() {
        val seen = mutableListOf<Throwable>()
        val surface = surface(SurfaceConfig(onError = { seen += it }))
        surface.setContent { error("bad hud") }
        surface.setRenderTarget(RenderTarget.Raster(8, 8))
        surface.frame()

        assertEquals(1, seen.size)
        assertTrue(surface.isFailed)
        assertFalse(surface.needsRedraw)
        repeat(5) { surface.frame() }
        assertEquals(1, seen.size, "a failed surface must go quiet, not throw every frame")

        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        assertFalse(surface.isFailed, "setContent clears the failed state")
        surface.frame()
        assertEquals(0xFFFF0000.toInt(), notNull(surface.readPixels())[0])
    }
}
