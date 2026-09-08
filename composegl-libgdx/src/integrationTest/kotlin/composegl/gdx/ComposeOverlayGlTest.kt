package composegl.gdx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import composegl.CursorShape
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The adapter against a real driver. Everything here is invisible to the headless core tests:
 * whether the framebuffer comes back upright, whether the game's own drawing survives Skia, and
 * whether the driver complains.
 */
class ComposeOverlayGlTest {

    @Test
    fun `the overlay blits upright over the game, and the game survives it`() {
        lateinit var ui: ComposeOverlay
        var primary = 0
        var topLeft = 0
        var underHud = 0
        var glError = -1
        var rendersAfterSettling = -1L

        runGl(frames = 15, onCreate = {
            ui = ComposeOverlay()
            ui.setContent {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(80.dp).background(Color.Red).align(Alignment.TopStart))
                    Button(onClick = {}, modifier = Modifier.align(Alignment.Center)) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }) { frame ->
            Gdx.gl.glClearColor(0f, 0f, 0.5f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
            ui.update()
            ui.draw()

            if (frame == 3) {
                topLeft = screenPixel(5, 5)
                underHud = screenPixel(Gdx.graphics.backBufferWidth - 5, Gdx.graphics.backBufferHeight - 5)
                glError = Gdx.gl.glGetError()
            }
            if (frame == 15) {
                rendersAfterSettling = ui.stats.composeRenders
                ui.dispose()
                ComposeGdx.dispose()
            }
        }

        assertEquals(0xFFFF0000.toInt(), topLeft, "Compose's top-left marker must land at the screen top-left, got ${hex(topLeft)}")
        assertEquals(0xFF000080.toInt(), underHud, "the game's clear colour must survive the HUD, got ${hex(underHud)}")
        assertEquals(GL20.GL_NO_ERROR, glError, "the driver reported an error after rendering the HUD")
        assertEquals(1L, rendersAfterSettling, "a static HUD must render once over 15 frames")
        assertTrue(primary != 0)
    }

    @Test
    fun `the state firewall leaves the game free to draw as it likes`() {
        lateinit var ui: ComposeOverlay
        var afterCompose = 0
        var glError = -1

        runGl(frames = 6, onCreate = {
            ui = ComposeOverlay()
            ui.setContent { Box(Modifier.fillMaxSize().background(Color(0x40FFFFFF))) }
        }) { frame ->
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            ui.update()
            ui.draw()

            // Now draw *after* Compose, using plain GL: a scissored clear to a known colour.
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(0, 0, 20, 20)
            Gdx.gl.glClearColor(0f, 1f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)

            if (frame == 4) {
                afterCompose = readPixel(5, 5)
                glError = Gdx.gl.glGetError()
            }
            if (frame == 6) {
                ui.dispose()
                ComposeGdx.dispose()
            }
        }

        assertEquals(0xFF00FF00.toInt(), afterCompose, "raw GL after a Compose render must still work, got ${hex(afterCompose)}")
        assertEquals(GL20.GL_NO_ERROR, glError)
    }

    /**
     * The host services against a real LibGDX application, where Gdx.app and Gdx.graphics exist.
     * Copy and paste is the user-visible half of this, and it goes through Gdx.app.clipboard.
     */
    @Test
    fun `host services reach the real LibGDX clipboard, cursor and density`() {
        var roundTrip: String? = null
        var density = 0f
        var cursorThrew: Throwable? = null

        runGl(width = 320, height = 240, frames = 3) {
            val host = ComposeGdx.hostServices
            host.setClipboard("copied out of the HUD")
            roundTrip = host.getClipboard()
            density = host.density
            cursorThrew = runCatching { CursorShape.entries.forEach { host.setCursor(it) } }.exceptionOrNull()
        }

        assertEquals("copied out of the HUD", roundTrip, "clipboard must round-trip through Gdx.app")
        assertTrue(density > 0f, "density must be a real ratio, got $density")
        assertNull(cursorThrew, "every CursorShape must map to a system cursor LibGDX accepts")
    }

    @Test
    fun `resizing through zero and back leaves a correct frame`() {
        lateinit var ui: ComposeOverlay
        var afterResize = 0
        var glError = -1

        runGl(width = 640, height = 480, frames = 12, onCreate = {
            ui = ComposeOverlay()
            ui.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
        }) { frame ->
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            ui.update()
            ui.draw()

            when (frame) {
                3 -> ui.resize(1024, 768)
                5 -> ui.resize(0, 0)
                7 -> ui.resize(640, 480)
                10 -> {
                    afterResize = screenPixel(5, 5)
                    glError = Gdx.gl.glGetError()
                }
                12 -> { ui.dispose(); ComposeGdx.dispose() }
            }
        }

        assertEquals(0xFFFF0000.toInt(), afterResize, "after resizing back the HUD must draw again, got ${hex(afterResize)}")
        assertEquals(GL20.GL_NO_ERROR, glError)
    }
}
