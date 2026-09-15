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
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `widthIn` on a real GPU: the panel's background covers the range's minimum when its contents are
 * small, and stops at the maximum when they grow, in the pixels that come out.
 */
class SizeInGlTest {

    private val red = Colour.rgb(0xFF0000)

    /** A red panel, 100 tall, whose contents go from 10 wide to 1000 wide when it is clicked. */
    private fun panel(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var grown by remember { mutableStateOf(false) }
        Box(
            Modifier.widthIn(min = 100f, max = 250f).height(100f)
                .background(red)
                .clickable { grown = true }
                .testTag("panel"),
        ) {
            Box(Modifier.width(if (grown) 1000f else 10f).height(10f))
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
    fun `the background covers the minimum and then stops at the maximum`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = panel(backend)
        try {
            frame(ui).use {
                assertColour(Color.RED, it.at(90, 50), "small contents are lifted to 100 wide")
                assertColour(Color.BLACK, it.at(110, 50), "and no further")
            }

            ui.click("panel")

            frame(ui).use {
                assertColour(Color.RED, it.at(240, 50), "grown contents widen it to 250")
                assertColour(Color.BLACK, it.at(260, 50), "and not past the maximum")
                assertColour(Color.BLACK, it.at(240, 150), "the height it was told is kept")
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
