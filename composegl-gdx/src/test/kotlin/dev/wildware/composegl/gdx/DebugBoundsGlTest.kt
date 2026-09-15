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
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.debugBounds
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `Modifier.debugBounds` on a real GPU: the outline, the wash and the size label in the pixels.
 *
 * The headless tests prove what is asked of the canvas. These prove the GL canvas turns that into a
 * one-pixel red edge exactly on the widget's rectangle, over its child, with digits you can read.
 */
class DebugBoundsGlTest {

    /**
     * A 200 by 100 green box at 100, 100, that shows its debug box once it has been clicked.
     *
     * The green is a child filling the box, so the outline has to land on top of a child to be seen.
     */
    private fun screen(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var debug by remember { mutableStateOf(false) }
        val box = if (debug) Modifier.debugBounds(Colour.Red, label = true) else Modifier
        Box(
            Modifier.offset(100f, 100f).then(box).size(200f, 100f)
                .clickable { debug = true }
                .testTag("box"),
        ) {
            Box(Modifier.fillMaxSize().background(Colour.rgb(0x00FF00)))
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
        val close = abs(expected.r - actual.r) < 0.03f &&
            abs(expected.g - actual.g) < 0.03f &&
            abs(expected.b - actual.b) < 0.03f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    /**
     * Each channel clearly on where [expected] has it on, and clearly off where it is off.
     *
     * For pixels on an edge. The shape shader antialiases every rectangle, so a one-unit outline and
     * a two-unit dot come out softened by about a pixel into whatever is beside them. Still nowhere
     * near confusing the colours told apart here: red, green and white differ by a whole channel.
     */
    private fun assertMostly(expected: Color, actual: Color, because: String) {
        fun agrees(want: Float, got: Float) = if (want > 0.5f) got > 0.6f else got < 0.45f
        val mostly = agrees(expected.r, actual.r) && agrees(expected.g, actual.g) && agrees(expected.b, actual.b)
        assertTrue(mostly, "$because: expected mostly $expected, got $actual")
    }

    private fun Color.isWhitish() = r > 0.6f && g > 0.6f && b > 0.6f

    @Test
    fun `a click turns the debug box on and it is drawn over the child on the widget's edge`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            frame(ui).use {
                assertMostly(Color.GREEN, it.at(200, 100), "the top edge before the debug box is on")
                assertMostly(Color.GREEN, it.at(299, 150), "the right edge before")
                assertTrue((95..120).none { y -> (95..170).any { x -> it.at(x, y).isWhitish() } }, "no label before")
            }

            ui.click("box")

            frame(ui).use {
                // The node itself has not moved: the same box, now with its edges drawn on it.
                assertEquals(100f, ui.node("box").boundsInRoot.left)
                assertMostly(Color.RED, it.at(200, 100), "the top edge")
                assertMostly(Color.RED, it.at(299, 150), "the right edge")
                assertMostly(Color.RED, it.at(200, 199), "the bottom edge")
                assertColour(Color.BLACK, it.at(200, 97), "nothing outside the widget")
                assertColour(Color.BLACK, it.at(302, 150), "nothing past its right edge")

                // Inside, the child shows through a faint red wash rather than being covered.
                val middle = it.at(200, 150)
                assertTrue(middle.g > 0.8f, "the child still shows: $middle")
                assertTrue(middle.r in 0.06f..0.2f, "washed faintly red: $middle")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `the size label is white digits on a red chip in the top left corner`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = screen(backend)
        try {
            ui.click("box")

            frame(ui).use {
                // "200x100": seven glyphs two units a dot, starting 2 in from the corner, so the digits
                // cover 102 to 156 across and 102 to 112 down, on a chip 58 wide and 14 tall.
                var left = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var top = Int.MAX_VALUE
                var bottom = Int.MIN_VALUE
                var lit = 0
                for (y in 90..130) for (x in 90..200) {
                    if (!it.at(x, y).isWhitish()) continue
                    lit++
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
                assertTrue(left in 101..103 && right in 154..157, "digits across $left..$right")
                assertTrue(top in 101..103 && bottom in 110..113, "digits down $top..$bottom")
                assertTrue(lit > 120, "seven glyphs light far more than a stray pixel: $lit")

                // The first glyph is a 2, whose top row is lit; the x in the middle is dark along its top.
                assertTrue(it.at(105, 103).isWhitish(), "the top row of the 2: ${it.at(105, 103)}")
                assertMostly(Color.RED, it.at(129, 103), "above the centre of the x")
                val past = it.at(170, 110)
                assertTrue(past.g > 0.8f && past.r < 0.2f, "past the chip is the washed child: $past")
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
