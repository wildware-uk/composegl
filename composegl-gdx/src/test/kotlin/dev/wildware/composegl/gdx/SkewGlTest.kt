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
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `Modifier.skew` on a real GPU, driven by `uiTest` and judged by the pixels that came out.
 *
 * The recording tests say which corners were asked for; these say the screen agrees.
 */
class SkewGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /**
     * A red square 120 across, 60 in from the top left, that leans forty-five degrees top forward
     * about its middle when clicked and stands up again on Escape. Leaning, its top edge runs from
     * 120 to 240 and its bottom edge from 0 to 120.
     */
    private fun banner(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var leaning by remember { mutableStateOf(false) }
        Box(
            Modifier.offset(60f, 60f).size(120f)
                .skew(x = if (leaning) -45f else 0f)
                .background(red)
                .focusable(initial = true)
                .clickable { leaning = true }
                .onKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.Down) {
                        leaning = false
                        true
                    } else {
                        false
                    }
                }
                .testTag("banner"),
        )
    }

    /** Clears, draws one frame of [ui] through the GL canvas, and reads the frame back. */
    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // Anything an earlier test or the backend's own setup left behind is not this frame's.
        do {
            val leftOver = Gdx.gl.glGetError()
        } while (leftOver != GL20.GL_NO_ERROR)
        ui.render()
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "the frame drew without a GL error")
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

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }

    private inline fun withBackend(block: (GdxBackend) -> Unit) {
        val backend = GdxBackend(HeadlessFonts.registry())
        try {
            block(backend)
        } finally {
            backend.dispose()
        }
    }

    @Test
    fun `clicking the banner slants it on the screen and escape stands it back up`() = Gl.render {
        withBackend { backend ->
            val ui = banner(backend)
            try {
                frame(ui).use {
                    assertColour(Color.RED, it.at(65, 65), "upright, the top-left corner is covered")
                    assertColour(Color.BLACK, it.at(225, 65), "and nothing reaches past the right edge")
                }

                ui.click("banner")

                frame(ui).use {
                    assertColour(Color.RED, it.at(120, 120), "the middle does not move")
                    assertColour(Color.RED, it.at(225, 65), "the top leans out past the laid-out box")
                    assertColour(Color.BLACK, it.at(65, 65), "leaving its top-left corner bare")
                    assertColour(Color.RED, it.at(15, 175), "the bottom leans out the other way")
                    assertColour(Color.BLACK, it.at(170, 175), "leaving its bottom-right corner bare")
                }

                ui.key(Key.Escape)

                frame(ui).use {
                    assertColour(Color.RED, it.at(65, 65), "upright again")
                    assertColour(Color.BLACK, it.at(225, 65), "with nothing leaning out")
                }
            } finally {
                ui.close()
            }
        }
    }

    @Test
    fun `a slant inside a slant and a slant with no size both draw`() = Gl.render {
        withBackend { backend ->
            // A blue square leaning forward holding a red one sliding down, and next to them a
            // slanted node of no size, which must neither fail nor paint anything.
            val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
                Box(Modifier.offset(100f, 100f).size(200f).skew(x = -20f).background(blue)) {
                    Box(Modifier.offset(50f, 50f).size(100f).skew(y = 20f).background(red))
                }
                Box(Modifier.offset(20f, 20f).size(0f).skew(x = -12f).background(red))
            }
            try {
                frame(ui).use {
                    assertColour(Color.RED, it.at(200, 200), "the inner square is drawn at the shared middle")
                    assertColour(Color.BLUE, it.at(200, 110), "inside the outer one")
                    assertColour(Color.BLACK, it.at(104, 104), "whose top-left corner has leant away")
                    assertColour(Color.BLACK, it.at(20, 20), "and the node with no size paints nothing")
                }
            } finally {
                ui.close()
            }
        }
    }
}
