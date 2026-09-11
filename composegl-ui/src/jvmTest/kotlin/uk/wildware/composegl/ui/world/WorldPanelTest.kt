package uk.wildware.composegl.ui.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.skin.styled
import uk.wildware.composegl.ui.widget.ProvideFonts
import uk.wildware.composegl.ui.widget.Text
import uk.wildware.composegl.ui.layout.Box
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An interface that lives in the game's world rather than on top of it.
 *
 * The point of the whole thing is the last test here: a terminal on a wall that nobody has touched
 * is not redrawn. Everything else about rendering into a texture is the backend's, and is tested
 * against a real GL context over in `composegl-lwjgl3`.
 */
class WorldPanelTest {

    private val panel = WorldPanel(320f, 200f)
    private val canvas = RecordingCanvas(Rect(0f, 0f, 320f, 200f))

    private var wall = 0L

    @AfterEach
    fun tearDown() = panel.close()

    /**
     * One turn of a game loop. The canvas is cleared only when the panel actually draws, which is
     * what a framebuffer does: what was drawn into it last is still there.
     */
    private fun frame(): Boolean {
        wall += 16_000_000L
        if (!panel.needsRedraw(wall)) return false
        canvas.clear(Rect(0f, 0f, 320f, 200f))
        panel.draw(canvas)
        canvas.assertBalanced()
        return true
    }

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

    @Test
    fun `it draws its tree at its own size`() {
        panel.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                Box(Modifier.fillMaxSize().styled("panel")) { Text("REACTOR") }
            }
        }
        repeat(2) { frame() }

        assertEquals(listOf("REACTOR"), texts())
        val background = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect
        assertEquals(320f, background.right - background.left, 0.5f, "a panel fills its own size")
        assertEquals(200f, background.bottom - background.top, 0.5f)
    }

    @Test
    fun `it is drawn only when the tree changes`() {
        var reading by mutableStateOf(400)
        panel.setContent {
            ProvideFonts(MonospaceFontProvider()) { Text("$reading MW") }
        }
        repeat(3) { frame() }
        val drawn = panel.draws

        // A minute of a terminal nobody is looking at.
        repeat(60) { assertFalse(frame(), "a panel nobody touched was drawn again") }
        assertEquals(drawn, panel.draws, "nothing changed, so nothing should have been drawn")

        reading = 380
        assertTrue(frame() || frame(), "a changed reading has to reach the wall")
        assertEquals(listOf("380 MW"), texts())
    }

    @Test
    fun `resizing draws it again`() {
        panel.setContent { ProvideFonts(MonospaceFontProvider()) { Box(Modifier.fillMaxSize().styled("panel")) } }
        repeat(3) { frame() }
        assertFalse(frame())

        panel.size = Size(160f, 100f)

        assertTrue(frame(), "a panel laid out at a new size has to be drawn at it")
        val background = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect
        assertEquals(160f, background.right - background.left, 0.5f)
    }

    @Test
    fun `a game can force it to be drawn again`() {
        panel.setContent { ProvideFonts(MonospaceFontProvider()) { Text("READY") } }
        repeat(3) { frame() }
        assertFalse(frame())

        // The texture was lost — the window was recreated, the device reset.
        panel.invalidate()

        assertTrue(frame(), "invalidate() is how a game says the pixels are gone")
        assertEquals(listOf("READY"), texts())
    }

    @Test
    fun `it is laid out exactly as the same tree on the screen is`() {
        val content = @androidx.compose.runtime.Composable {
            ProvideFonts(MonospaceFontProvider()) {
                Box(Modifier.fillMaxSize().styled("panel")) { Text("REACTOR") }
            }
        }

        panel.setContent(content)
        repeat(2) { frame() }
        val inTheWorld = canvas.calls.toList()

        // The same tree, on a host of its own, laid out in a box the same size.
        val host = UiHost()
        val screen = RecordingCanvas(Rect(0f, 0f, 320f, 200f))
        host.setContent(content)
        repeat(2) {
            host.frame(wall)
            screen.clear(Rect(0f, 0f, 320f, 200f))
            MeasurePass().run(host.root, Constraints.fixed(320f, 200f))
            DrawPass(screen).draw(host.root)
        }

        assertEquals(screen.calls, inTheWorld, "a panel in the world is the same panel")
        host.dispose()
    }
}
