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
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * One-sided and dashed borders written in a screen's modifiers, driven with the mouse, on a real
 * GPU: the underline follows a click and a hovered outline breaks into dashes, in the pixels.
 */
class BorderGlTest {

    private val blue = Colour.rgb(0x0000FF)

    /**
     * Three tabs 80 by 40 along the top, the selected one underlined 4 thick; under them a drop
     * zone 110 by 60 whose outline turns dashed while hovered. 110 across at 10 on and 10 off is
     * six dashes and five gaps exactly, so a dash and a gap can be read off in whole pixels.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var selected by remember { mutableStateOf(0) }
        val hover = remember { InteractionState() }
        Column {
            Row {
                for (index in 0 until 3) {
                    val underline = if (index == selected) Modifier.border(bottom = BorderSide(4f, blue)) else Modifier
                    Box(Modifier.size(80f, 40f).clickable { selected = index }.testTag("tab-$index").then(underline))
                }
            }
            val style = if (hover.isHovered) BorderStyle.Dashed(on = 10f, off = 10f) else BorderStyle.Solid
            Box(
                Modifier.size(110f, 60f).interaction(hover).clickable {}.testTag("drop")
                    .border(blue, width = 4f, style = style),
            )
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    @Test
    fun `clicking a tab moves its underline and hovering the drop zone breaks its outline`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertColour(Color.BLUE, it.at(40, 38), "the first tab is underlined")
                assertColour(Color.BLACK, it.at(40, 20), "on its bottom edge only")
                assertColour(Color.BLACK, it.at(200, 38), "and the third is not")
                assertColour(Color.BLUE, it.at(15, 42), "at rest the drop zone's outline is unbroken")
            }

            ui.click("tab-2")

            frame(ui).use {
                assertColour(Color.BLACK, it.at(40, 38), "the first tab's underline is gone")
                assertColour(Color.BLUE, it.at(200, 38), "and the third tab has it")
                assertColour(Color.BLACK, it.at(200, 2), "still only along the bottom")
            }

            ui.moveTo("drop")

            frame(ui).use {
                assertColour(Color.BLUE, it.at(5, 42), "hovered, the top edge starts on a dash")
                assertColour(Color.BLACK, it.at(15, 42), "and has a gap where the line was unbroken")
                assertColour(Color.BLUE, it.at(25, 42), "then the next dash")
                assertColour(Color.BLACK, it.at(55, 70), "and the zone's middle stays empty")
            }

            ui.moveTo(Offset(Gl.size - 10f, Gl.size - 10f))

            frame(ui).use {
                assertColour(Color.BLUE, it.at(15, 42), "leaving puts the unbroken outline back")
            }
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
