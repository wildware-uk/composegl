package composegl.lwjgl3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import composegl.GameTexture
import composegl.GameView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL32C

/**
 * Editor mode: Compose owns the window and the game renders into a node in the layout.
 *
 * The claim under test is that the viewport is *live* — the game redraws its framebuffer and the
 * Compose node shows the new picture, with no copy and no new texture.
 */
class EditorModeGlTest {

    private val window = GlfwTestWindow(400, 300)
    private var overlay: ComposeOverlay? = null
    private var viewport: GameFrameBuffer? = null

    @AfterEach
    fun tearDown() {
        viewport?.close()
        overlay?.close()
        ComposeLwjgl.dispose()
        window.close()
    }

    /** What a game does: bind its viewport and draw a frame. */
    private fun GameFrameBuffer.paint(red: Float, green: Float, blue: Float) {
        bind()
        GL32C.glClearColor(red, green, blue, 1f)
        GL32C.glClear(GL32C.GL_COLOR_BUFFER_BIT or GL32C.GL_DEPTH_BUFFER_BIT)
        unbind(window.width, window.height)
    }

    @Test
    fun `the game renders into a node of the Compose layout, and stays live`() {
        window.open()
        val game = GameFrameBuffer(128, 128).also { viewport = it }
        var texture by mutableStateOf<GameTexture>(game.texture)

        val ui = ComposeOverlay(window.handle).also { overlay = it }
        ui.setContent {
            Row(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                // The tool palette an editor would have down one side.
                Box(Modifier.width(100.dp).fillMaxHeight().background(Color(0xFF404040)))
                GameView(texture, Modifier.fillMaxSize())
            }
        }

        fun frame() {
            window.clear(0f, 0f, 0f)
            ui.update()
            ui.draw()
        }

        game.paint(1f, 0f, 0f)
        frame()
        assertEquals(0xFF404040.toInt(), window.pixelAt(50, 150), "the palette is on the left")
        assertEquals(0xFFFF0000.toInt(), window.pixelAt(250, 150), "the viewport shows the game's frame")

        // Redraw the game with no new texture and no setContent. A cached, adopted Skia image
        // has to notice; if it did not, an editor viewport would freeze on its first frame.
        game.paint(0f, 1f, 0f)
        frame()
        assertEquals(0xFF00FF00.toInt(), window.pixelAt(250, 150), "the viewport is live, not a snapshot")

        game.paint(0f, 0f, 1f)
        frame()
        assertEquals(0xFF0000FF.toInt(), window.pixelAt(250, 150))
        assertEquals(GL32C.GL_NO_ERROR, window.glError())
    }

    @Test
    fun `resizing the viewport gives a new texture and keeps working`() {
        window.open()
        val game = GameFrameBuffer(64, 64).also { viewport = it }
        var texture by mutableStateOf<GameTexture>(game.texture)

        val ui = ComposeOverlay(window.handle).also { overlay = it }
        ui.setContent { GameView(texture, Modifier.fillMaxSize()) }

        fun frame() {
            window.clear(0f, 0f, 0f)
            ui.update()
            ui.draw()
        }

        game.paint(1f, 0f, 0f)
        frame()
        assertEquals(0xFFFF0000.toInt(), window.pixelAt(200, 150))

        texture = game.resize(256, 192)
        assertEquals(256, texture.width)
        game.paint(0f, 1f, 0f)
        frame()

        assertEquals(0xFF00FF00.toInt(), window.pixelAt(200, 150), "after a resize the viewport still draws")
        assertEquals(GL32C.GL_NO_ERROR, window.glError())
    }
}
