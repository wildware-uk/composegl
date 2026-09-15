package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A selectable label on a real GPU, with a real font: the highlight a drag leaves is on the screen,
 * behind the letters it covers and nowhere else, and Ctrl+C reaches the backend's clipboard.
 */
class SelectionContainerGlTest {

    private val seed = "Seed: 8F3A-22C1"

    /** The skin's selection blue, `#3F6BD6`. */
    private val blue = Color(0x3F / 255f, 0x6B / 255f, 0xD6 / 255f, 1f)

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun isBlue(colour: Color) =
        abs(colour.r - blue.r) < 0.03f && abs(colour.g - blue.g) < 0.03f && abs(colour.b - blue.b) < 0.03f

    /** How many pixels of [area] are the selection blue. */
    private fun Pixmap.blueIn(area: Rect): Int {
        var count = 0
        for (y in area.top.toInt() until area.bottom.toInt()) {
            for (x in area.left.toInt() until area.right.toInt()) if (isBlue(at(x, y))) count++
        }
        return count
    }

    @Test
    fun `a drag across a label paints the selection behind exactly the dragged characters`() = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
        val backend = GdxBackend(fonts)
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            SelectionContainer { Text(seed, Modifier.offset(40f, 60f).testTag("seed")) }
        }
        try {
            val box = ui.node("seed").boundsInRoot
            frame(ui).use { assertEquals(0, it.blueIn(box), "nothing is highlighted before the drag") }

            // "Seed: " is six characters of a proportional font, so find where "8" starts by
            // measuring the prefix with the same fonts the label was laid out with.
            val style = dev.wildware.composegl.ui.text.TextStyle(family = "default", size = 16f)
            val prefix = fonts.measure("Seed: ", style).size.width
            val codeStart = box.left + prefix
            ui.press(Offset(codeStart + 1f, box.centre.y))
            ui.moveTo(Offset(Gl.size - 5f, box.centre.y))
            ui.release()

            frame(ui).use { pixels ->
                val code = Rect(codeStart + 2f, box.top, box.right - 1f, box.bottom)
                val label = Rect(box.left, box.top, codeStart - 2f, box.bottom)
                val lit = pixels.blueIn(code)
                assertTrue(lit > code.width * code.height / 3, "the code is highlighted: $lit blue pixels")
                assertEquals(0, pixels.blueIn(label), "the unselected \"Seed:\" stays unhighlighted")
                assertTrue(
                    (0 until Gl.size).none { isBlue(pixels.at(it, (box.bottom + 4f).toInt())) },
                    "nothing below the line is highlighted",
                )
            }

            ui.key(Key.C, Modifiers.Control)
            assertEquals("8F3A-22C1", backend.clipboard.read())
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
