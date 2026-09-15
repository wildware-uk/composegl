package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tabs rounded along their top, composed for real, clicked, and judged by the pixels on a GPU.
 *
 * The canvas tests prove the shader cuts the right corner of one box. This is the proof that a
 * modifier written in a screen gets there: the corners survive layout and the draw pass, and a
 * click that changes which tab is lit shows up on screen with the tab still square underneath.
 */
class PerCornerGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)

    /** Three 60 by 40 tabs from (40, 40), each round only along its top; the chosen one is green. */
    private fun tabs(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var chosen by remember { mutableStateOf(0) }
        Row(Modifier.offset(40f, 40f)) {
            repeat(3) { index ->
                Box(
                    Modifier.size(60f, 40f)
                        .background(if (chosen == index) green else red, Corners.top(16f))
                        .clickable { chosen = index }
                        .testTag("tab$index"),
                )
            }
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
    fun `a clicked tab lights up rounded along its top and square along its bottom`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = tabs(backend)
        try {
            frame(ui).use {
                assertColour(Color.RED, it.at(162, 77), "the third tab starts red")
            }

            ui.click("tab2")

            frame(ui).use {
                // The third tab runs from x 160 to 220 and y 40 to 80.
                assertColour(Color.GREEN, it.at(190, 60), "the clicked tab is lit")
                assertColour(Color.BLACK, it.at(162, 42), "its top-left is cut away")
                assertColour(Color.BLACK, it.at(217, 42), "and its top-right")
                assertColour(Color.GREEN, it.at(162, 77), "its bottom-left is square")
                assertColour(Color.GREEN, it.at(217, 77), "and its bottom-right")
                assertColour(Color.RED, it.at(42, 77), "the first tab went back to red, still square below")
                assertColour(Color.BLACK, it.at(42, 42), "and still cut above")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `a clicked gradient tab keeps its top corners cut and its bottom square`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            var chosen by remember { mutableStateOf(0) }
            Row(Modifier.offset(40f, 40f)) {
                repeat(2) { index ->
                    Box(
                        Modifier.size(60f, 40f)
                            .background(if (chosen == index) Brush.vertical(green, green) else Brush.vertical(red, red), Corners.top(16f))
                            .clickable { chosen = index }
                            .testTag("tab$index"),
                    )
                }
            }
        }
        try {
            ui.click("tab1")

            frame(ui).use {
                // The second tab runs from x 100 to 160 and y 40 to 80.
                assertColour(Color.GREEN, it.at(130, 60), "the clicked gradient tab is lit")
                assertColour(Color.BLACK, it.at(102, 42), "its top-left is cut away")
                assertColour(Color.BLACK, it.at(157, 42), "and its top-right")
                assertColour(Color.GREEN, it.at(102, 77), "its bottom-left is square")
                assertColour(Color.GREEN, it.at(157, 77), "and its bottom-right")
                assertColour(Color.RED, it.at(42, 77), "the first tab is red again and square below")
                assertColour(Color.BLACK, it.at(42, 42), "and cut above")
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
