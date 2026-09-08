package composegl.gdx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * In-world UI: a Compose panel as a plain texture the game draws wherever it likes, and input
 * the game hands it after working out where the player pointed.
 */
class ComposeTextureGlTest {

    @Test
    fun `a panel renders to a texture the game can draw, and takes clicks in its own pixels`() {
        lateinit var panel: ComposeTexture
        lateinit var batch: SpriteBatch
        var clicks = 0
        var pressOnButton = false
        var pressOnEmptySpace = true
        var drawn = 0
        var glError = -1
        var rendersAtFrame2 = -1L
        var rendersAtFrame4 = -1L

        runGl(width = 400, height = 400, frames = 15, onCreate = {
            batch = SpriteBatch()
            panel = ComposeTexture(200, 200)
            panel.setContent {
                Box(Modifier.fillMaxSize().background(Color(0xFF102030))) {
                    Button(
                        onClick = { clicks++ },
                        modifier = Modifier.align(Alignment.TopStart),
                    ) { Box(Modifier.size(60.dp, 20.dp)) }
                }
            }
        }) { frame ->
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            panel.update()
            panel.render()

            // The game draws the texture itself — here, into the bottom-left 200x200 of the window.
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
            batch.projectionMatrix.setToOrtho2D(0f, 0f, 400f, 400f)
            batch.begin()
            batch.draw(TextureRegion(panel.texture), 0f, 0f, 200f, 200f)
            batch.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)

            when (frame) {
                2 -> rendersAtFrame2 = panel.stats.composeRenders
                3 -> {
                    // The panel's own background, somewhere the button is not.
                    drawn = readPixel(150, 50)
                    glError = Gdx.gl.glGetError()
                }
                4 -> rendersAtFrame4 = panel.stats.composeRenders
                5 -> {
                    pressOnButton = panel.sendPointer(PointerEventType.Press, 30f, 20f, PointerButton.Primary)
                    panel.sendPointer(PointerEventType.Release, 30f, 20f, PointerButton.Primary)
                }
                7 -> {
                    pressOnEmptySpace = panel.sendPointer(PointerEventType.Press, 190f, 190f, PointerButton.Primary)
                    panel.sendPointer(PointerEventType.Release, 190f, 190f, PointerButton.Primary)
                }
                15 -> {
                    panel.dispose()
                    batch.dispose()
                    ComposeGdx.dispose()
                }
            }
        }

        assertEquals(0xFF102030.toInt(), drawn, "the panel's own background should be on screen, got ${hex(drawn)}")
        assertEquals(GL20.GL_NO_ERROR, glError)
        assertTrue(pressOnButton, "a press inside the panel's button is Compose's")
        assertEquals(1, clicks)
        assertFalse(pressOnEmptySpace, "a press on empty panel space belongs to the game")
        assertEquals(1L, rendersAtFrame2, "the first frame draws the panel")
        assertEquals(
            rendersAtFrame2,
            rendersAtFrame4,
            "an untouched panel must cost nothing per frame — this is the whole reason it is a texture",
        )
    }

    @Test
    fun `a panel and an overlay share one context`() {
        lateinit var overlay: ComposeOverlay
        lateinit var panel: ComposeTexture
        var overlayPixel = 0
        var panelRenders = -1L
        var glError = -1

        runGl(width = 300, height = 300, frames = 10, onCreate = {
            overlay = ComposeOverlay()
            overlay.setContent { Box(Modifier.size(60.dp).background(Color.Red)) }
            panel = ComposeTexture(64, 64)
            panel.setContent { Box(Modifier.fillMaxSize().background(Color.Green)) }
        }) { frame ->
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            panel.update()
            panel.render()
            overlay.update()
            overlay.draw()

            if (frame == 5) {
                overlayPixel = screenPixel(5, 5)
                glError = Gdx.gl.glGetError()
            }
            if (frame == 10) {
                panelRenders = panel.stats.composeRenders
                panel.dispose()
                overlay.dispose()
                ComposeGdx.dispose()
            }
        }

        assertEquals(0xFFFF0000.toInt(), overlayPixel, "the overlay still draws with a panel alongside it")
        assertEquals(GL20.GL_NO_ERROR, glError)
        assertTrue(panelRenders >= 1, "and the panel rendered too")
    }
}
