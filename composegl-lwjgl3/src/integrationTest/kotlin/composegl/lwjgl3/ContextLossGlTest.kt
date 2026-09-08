package composegl.lwjgl3

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
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL32C

/**
 * A dry run of Android's context loss on resume.
 *
 * Android throws the GL context away when an app is backgrounded, so everything on it — Skia's
 * context, every framebuffer, every texture — is gone and has to be built again. That platform is
 * not supported yet, but the shape of the recovery is the same everywhere, and it is worth knowing
 * the API can express it before someone finds out on a phone.
 *
 * The recovery is: dispose the overlays, dispose the context, then build both again. State the UI
 * held lives in the game, not in ComposeGL, so it survives.
 */
class ContextLossGlTest {

    private val window = GlfwTestWindow(300, 200)
    private var overlay: ComposeOverlay? = null

    @AfterEach
    fun tearDown() {
        overlay?.close()
        ComposeLwjgl.dispose()
        window.close()
    }

    @Test
    fun `everything can be thrown away and rebuilt, and the UI comes back with its state`() {
        window.open()

        // The game owns the state, which is why it survives.
        var clicks by mutableStateOf(0)
        val content = @androidx.compose.runtime.Composable {
            Box(Modifier.fillMaxSize().background(if (clicks == 0) Color.Red else Color.Green))
        }

        fun buildOverlay(): ComposeOverlay = ComposeOverlay(window.handle).also {
            overlay?.close()
            overlay = it
            it.setContent(content)
        }

        fun frame(ui: ComposeOverlay) {
            window.clear(0f, 0f, 0f)
            ui.update()
            ui.draw()
        }

        var ui = buildOverlay()
        frame(ui)
        assertEquals(0xFFFF0000.toInt(), window.pixelAt(150, 100))

        // Everything on the GL context goes away.
        ui.close()
        ComposeLwjgl.dispose()

        // ...and comes back. A new Skia context, a new framebuffer, a new scene.
        clicks = 1
        ui = buildOverlay()
        frame(ui)

        assertEquals(0xFF00FF00.toInt(), window.pixelAt(150, 100), "the rebuilt UI shows the state the game kept")
        assertEquals(GL32C.GL_NO_ERROR, window.glError())

        repeat(10) { frame(ui) }
        assertEquals(1L, ui.stats.composeRenders, "and it settles again, so nothing is stuck redrawing")
    }
}
