package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.ScrollArea
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A scrolled list, left and come back to, judged by the pixels on a real GPU.
 *
 * The headless tests read where rows were laid out. This is the proof that what comes back is what
 * a player sees: the list is drawn scrolled, not only laid out scrolled.
 */
class SaveableGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val blue = Colour.rgb(0x0000FF)

    /**
     * A 100x100 window at (100, 100) onto three 96-tall bands, red, green, blue, and a button in
     * the corner that opens a blank map. Two wheel steps is 96, which puts green in the window.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var current by remember { mutableStateOf("inventory") }
        Box(Modifier.size(Gl.size.toFloat(), Gl.size.toFloat())) {
            Box(Modifier.size(40f, 40f).clickable { current = "map" }.testTag("to-map"))
            SaveableStateHolder(current) { key ->
                if (key == "inventory") {
                    ScrollArea(Modifier.offset(100f, 100f).size(100f, 100f).testTag("inventory"), bars = false) {
                        Column {
                            listOf(red, green, blue).forEach { Box(Modifier.size(100f, 96f).background(it)) }
                        }
                    }
                } else {
                    OnBack { current = "inventory" }
                }
            }
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    @Test
    fun `a list scrolled to green is still drawn green after the map was opened and closed`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use { assertColour(Color.RED, it.at(150, 150), "unscrolled") }
            ui.scroll("inventory", Offset(0f, 2f))
            frame(ui).use { assertColour(Color.GREEN, it.at(150, 150), "scrolled two steps") }

            ui.click("to-map")
            frame(ui).use { assertColour(Color.BLACK, it.at(150, 150), "the list is gone while the map is open") }
            ui.pad(GamepadButton.East)

            frame(ui).use { assertColour(Color.GREEN, it.at(150, 150), "back from the map, still scrolled") }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }
}
