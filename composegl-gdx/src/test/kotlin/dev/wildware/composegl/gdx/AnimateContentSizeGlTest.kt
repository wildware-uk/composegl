package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `animateContentSize` on a real GPU: a red panel whose green contents widen on a click. Mid-way,
 * the panel is part-way across and the green that does not fit yet is cut off at its edge, in the
 * pixels that come out; once it lands, all of it shows.
 */
class AnimateContentSizeGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val resize = Clock("resize")

    /** 100 by 100 of red with 80 by 80 of green inside it, the green going to 280 wide on a click. */
    private fun panel(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var grown by remember { mutableStateOf(false) }
        Box(
            Modifier.animateContentSize(Tween(160, easing = Easings.Linear), clock = resize)
                .background(red)
                .padding(10f)
                .clickable { grown = true }
                .testTag("panel"),
        ) {
            Box(Modifier.size(if (grown) 280f else 80f, 80f).background(green))
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
    fun `the panel grows across and cuts off what does not fit yet`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = panel(backend)
        try {
            frame(ui).use {
                assertColour(Color.GREEN, it.at(50, 50), "the contents")
                assertColour(Color.RED, it.at(95, 50), "the panel's padding")
                assertColour(Color.BLACK, it.at(150, 50), "nothing past the panel")
            }

            ui.host.clocks.stop(resize)
            ui.click("panel")

            frame(ui).use {
                assertColour(Color.GREEN, it.at(95, 50), "the widened contents reach the panel's edge")
                assertColour(Color.BLACK, it.at(150, 50), "and are cut off there, not drawn past it")
            }

            // Four frames of a 160ms tween: about 40% of the way from 100 wide to 300.
            ui.host.clocks.start(resize)
            repeat(3) { ui.render() }
            frame(ui).use {
                val width = ui.node("panel").width
                assertTrue(width > 160f && width < 200f, "part-way across, at $width")
                assertColour(Color.GREEN, it.at(150, 50), "the panel has grown past 150")
                assertColour(Color.BLACK, it.at(250, 50), "but not yet to 250, so the green there is still cut off")
            }

            ui.settle()

            frame(ui).use {
                assertColour(Color.GREEN, it.at(250, 50), "all of the contents, once it lands")
                assertColour(Color.RED, it.at(295, 50), "inside its padding on the far side")
                assertColour(Color.BLACK, it.at(310, 50), "and no further")
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
