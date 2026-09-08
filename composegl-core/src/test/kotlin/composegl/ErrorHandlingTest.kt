package composegl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * One test per row of spec §13. The point of that table is that nothing here is a mystery crash
 * three frames later — every failure has a name, a message, and a defined next state.
 */
class ErrorHandlingTest {

    private val context = ComposeGlContext.createRaster(ContextConfig(debugChecks = true))
    private val host = object : HostServices { override val density = 1f }
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun ComposeSurface.frame() {
        nanos += 16_666_667
        update(nanos)
        if (needsRedraw) render(nanos)
    }

    @Test
    fun `a call from the wrong thread names both threads`() {
        val surface = ComposeSurface(context, host)
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))

        var thrown: Throwable? = null
        Thread({ thrown = runCatching { surface.render(0) }.exceptionOrNull() }, "not-the-gl-thread")
            .apply { start(); join() }

        val message = (thrown as IllegalStateException).message.orEmpty()
        assertTrue(message.contains("expected '"), message)
        assertTrue(message.contains("got 'not-the-gl-thread'"), message)
    }

    @Test
    fun `without debug checks the thread is not policed`() {
        val loose = ComposeGlContext.createRaster(ContextConfig(debugChecks = false))
        val surface = ComposeSurface(loose, host)
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))

        var thrown: Throwable? = null
        Thread { thrown = runCatching { surface.update(0) }.exceptionOrNull() }.apply { start(); join() }

        assertNull(thrown, "the check is opt-in; paying for it in shipping builds is the point of the flag")
        loose.dispose()
    }

    @Test
    fun `creating a GL context without one current is a named failure, not a crash`() {
        val thrown = assertThrows<ComposeGlUnsupportedException> { ComposeGlContext.create() }
        val message = thrown.message.orEmpty()
        assertTrue(message.contains("OpenGL 3.0"), message)
        assertTrue(message.contains("useOpenGL3"), message)
    }

    @Test
    fun `by default an exception in content reaches the caller`() {
        val surface = ComposeSurface(context, host)
        assertThrows<IllegalStateException> { surface.setContent { error("bad hud") } }
    }

    @Test
    fun `a handled exception leaves the last good frame on screen`() {
        var explode by mutableStateOf(false)
        val seen = mutableListOf<Throwable>()
        val surface = ComposeSurface(context, host, SurfaceConfig(onError = { seen += it }))
        surface.setContent {
            if (explode) error("bad hud")
            Box(Modifier.fillMaxSize().background(Color.Red))
        }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        surface.frame()
        assertEquals(0xFFFF0000.toInt(), surface.readPixels()!![0])

        explode = true
        repeat(5) { surface.frame() }

        assertEquals(1, seen.size, "reported once, not once per frame")
        assertTrue(surface.isFailed)
        assertFalse(surface.needsRedraw)
        assertEquals(0xFFFF0000.toInt(), surface.readPixels()!![0], "the last good frame is still there")
    }

    @Test
    fun `a failed surface ignores input instead of throwing again`() {
        val surface = ComposeSurface(context, host, SurfaceConfig(onError = {}))
        surface.setContent { error("bad hud") }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        surface.frame()

        assertFalse(surface.sendPointerEvent(PointerEventType.Press, 1f, 1f))
        assertFalse(surface.sendKeyEvent(Key.A, down = true))
        assertFalse(surface.sendChar('a'.code))
        assertFalse(surface.hasKeyboardFocus)
    }

    @Test
    fun `setContent clears the failed state`() {
        val surface = ComposeSurface(context, host, SurfaceConfig(onError = {}))
        surface.setContent { error("bad hud") }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        surface.frame()
        assertTrue(surface.isFailed)

        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Green)) }
        assertFalse(surface.isFailed)
        surface.frame()
        assertEquals(0xFF00FF00.toInt(), surface.readPixels()!![0])
    }

    @Test
    fun `a zero-sized target renders nothing and throws nothing`() {
        val surface = ComposeSurface(context, host)
        surface.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        surface.setRenderTarget(RenderTarget.Raster(0, 0))
        repeat(3) { surface.frame() }
        assertEquals(0L, surface.stats.composeRenders)
        assertNull(surface.readPixels())
    }

    @Test
    fun `a negative target size is rejected at the call, not at the driver`() {
        val surface = ComposeSurface(context, host)
        assertThrows<IllegalArgumentException> { surface.setRenderTarget(RenderTarget.Raster(-1, 8)) }
    }

    @Test
    fun `input before setContent is refused`() {
        val surface = ComposeSurface(context, host)
        surface.setRenderTarget(RenderTarget.Raster(8, 8))
        assertFalse(surface.sendPointerEvent(PointerEventType.Press, 1f, 1f))
        assertFalse(surface.sendKeyEvent(Key.A, down = true))
        assertFalse(surface.sendChar('a'.code))
    }

    @Test
    fun `dispose twice, and using a disposed surface`() {
        val surface = ComposeSurface(context, host)
        surface.setContent { Box(Modifier.fillMaxSize()) }
        surface.setRenderTarget(RenderTarget.Raster(4, 4))
        surface.dispose()
        surface.dispose()

        surface.update(1)
        surface.render(1)
        assertFalse(surface.needsRedraw)
        assertFalse(surface.sendPointerEvent(PointerEventType.Press, 1f, 1f))
        assertThrows<IllegalStateException> { surface.setContent { Box(Modifier.fillMaxSize()) } }
    }
}
