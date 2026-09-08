package composegl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A host that records what ComposeGL asked it to do. */
private class RecordingHost(override val density: Float = 1f) : HostServices {
    var clipboardText: String? = null
    var frameRequests = 0
    val cursors = mutableListOf<CursorShape>()
    var softKeyboardVisible: Boolean? = null

    override fun requestFrame() { frameRequests++ }
    override fun setCursor(cursor: CursorShape) { cursors += cursor }
    override fun getClipboard(): String? = clipboardText
    override fun setClipboard(text: String) { clipboardText = text }
    override fun showSoftKeyboard(visible: Boolean) { softKeyboardVisible = visible }
}

@OptIn(ExperimentalComposeUiApi::class)
class PlatformServicesTest {

    private val context = ComposeGlContext.createRaster()
    private val host = RecordingHost(density = 2f)
    private var nanos = 0L

    @AfterEach
    fun tearDown() = context.dispose()

    private fun renderOnce(content: @Composable () -> Unit): ComposeSurface {
        val surface = ComposeSurface(context, host)
        surface.setContent(content)
        surface.setRenderTarget(RenderTarget.Raster(8, 8))
        nanos += 16_666_667
        surface.update(nanos)
        if (surface.needsRedraw) surface.render(nanos)
        return surface
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the clipboard Compose sees is the host's`() {
        var manager: ClipboardManager? = null
        var clipboard: Clipboard? = null
        renderOnce {
            manager = LocalClipboardManager.current
            clipboard = LocalClipboard.current
            Box(Modifier.fillMaxSize())
        }

        val m = requireNotNull(manager)
        assertNull(m.getText(), "an empty host clipboard reads as empty")

        m.setText(AnnotatedString("copied from compose"))
        assertEquals("copied from compose", host.clipboardText)
        assertEquals("copied from compose", m.getText()?.text)
        assertTrue(m.hasText())

        // and the suspending shape, which is what BasicTextField uses
        runBlocking {
            val c = requireNotNull(clipboard)
            host.clipboardText = "pasted from the game"
            val entry = requireNotNull(c.getClipEntry())
            c.setClipEntry(entry)
        }
        assertEquals("pasted from the game", host.clipboardText, "a round trip must not lose the text")
    }

    @Test
    fun `touch slop follows the host density`() {
        var config: ViewConfiguration? = null
        renderOnce {
            config = LocalViewConfiguration.current
            Box(Modifier.fillMaxSize())
        }
        val c = requireNotNull(config)
        assertEquals(16f, c.touchSlop, "8dp at density 2")
        assertEquals(500L, c.longPressTimeoutMillis)
        assertEquals(300L, c.doubleTapTimeoutMillis)
    }

    @Test
    fun `the surface always reports itself focused`() {
        var focused: Boolean? = null
        renderOnce {
            focused = LocalWindowInfo.current.isWindowFocused
            Box(Modifier.fillMaxSize())
        }
        assertEquals(true, focused, "reporting false would hide the text caret forever")
    }

    @Test
    fun `Compose asks the host for a frame when it has work to do`() {
        var color by mutableStateOf(Color.Red)
        val surface = renderOnce { Box(Modifier.fillMaxSize().background(color)) }
        val before = host.frameRequests
        color = Color.Blue
        surface.update(nanos + 1)
        assertTrue(host.frameRequests > before, "a state write must reach HostServices.requestFrame")
    }
}
