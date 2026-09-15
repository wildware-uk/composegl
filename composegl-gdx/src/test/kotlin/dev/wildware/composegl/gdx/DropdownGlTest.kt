package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A dropdown's list on a real GPU: drawn over the screen underneath it, and gone again once closed.
 *
 * The headless tests prove the list is drawn after what it covers. This proves that order comes out
 * of the renderer as pixels on top, with nothing underneath bleeding through.
 */
class DropdownGlTest {

    private val red = Colour.rgb(0xFF0000)

    /** A dropdown at the top left with a solid red square straight under it, where the list opens. */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        PopupHost {
            var chosen by remember { mutableStateOf("Low") }
            Column(Modifier.offset(20f, 20f)) {
                Dropdown(
                    listOf("Low", "Medium", "High", "Ultra"),
                    chosen,
                    onSelect = { chosen = it },
                    modifier = Modifier.width(160f).testTag("quality"),
                    initialFocus = true,
                ) { Text(it) }
                Box(Modifier.size(160f, 100f).background(red))
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

    private fun isRed(colour: Color) = abs(colour.r - 1f) < 0.02f && colour.g < 0.02f && colour.b < 0.02f

    @Test
    fun `the open list covers the red square and closing it shows the square again`() = Gl.render {
        // Drawable fonts under the name the stock skin asks for: the options are text, and a
        // measuring-only font cannot be drawn.
        val fonts = GdxFonts()
        fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(14, 16, 18))
        val backend = GdxBackend(fonts)
        val ui = screen(backend)
        try {
            val square = ui.node("quality").boundsInRoot
            // Well inside the square: 60 across and 40 down from its corner, under the field.
            val x = (square.left + 60f).toInt()
            val y = (square.bottom + 40f).toInt()

            frame(ui).use { assertTrue(isRed(it.at(x, y)), "the square shows before the list opens: ${it.at(x, y)}") }

            ui.pad(GamepadButton.South)
            frame(ui).use {
                val covered = it.at(x, y)
                assertTrue(!isRed(covered), "the list is drawn over the square: $covered")
                assertTrue(covered.r < 0.5f, "and it is the list's dark panel rather than a blend with red: $covered")
            }

            ui.pad(GamepadButton.East)
            frame(ui).use { assertTrue(isRed(it.at(x, y)), "closed, the square shows again: ${it.at(x, y)}") }
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
