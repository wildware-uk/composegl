package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.ParallaxAware
import dev.wildware.composegl.ui.modifier.PointerParallax
import dev.wildware.composegl.ui.modifier.StickParallax
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.parallax
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `Modifier.parallax` on the real renderer: a composed screen, a real pointer or stick sent through
 * a sink wrapped in `ParallaxAware`, and the pixels read back.
 *
 * The toolkit's own tests can say the rectangle moved. Only a GPU can say the paint moved with it —
 * that nothing between the layout and the batch kept drawing the layer where layout first put it.
 */
class GdxParallaxTest {

    private val size = Size(Gl.size.toFloat(), Gl.size.toFloat())

    /** Far: a big red slab that barely moves. Near: a small blue one that runs. */
    private fun scene(backend: GdxBackend, pointer: PointerParallax?, stick: StickParallax?): UiTest =
        uiTest(size, backend, input = { ParallaxAware(it, pointer, stick) }) {
            Box(Modifier.fillMaxSize()) {
                if (pointer != null) {
                    Box(Modifier.size(100f, 100f).parallax(pointer, 0.1f).background(Colour.rgb(0xFF0000)))
                    Box(Modifier.size(20f, 20f).parallax(pointer, 1f).background(Colour.rgb(0x0000FF)))
                }
                if (stick != null) {
                    Box(Modifier.size(20f, 20f).parallax(stick, 1f).background(Colour.rgb(0x0000FF)))
                }
            }
        }

    /** Clears, draws one frame of [ui] through the GL canvas, and reads the frame back. */
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
    fun `a far layer and a near one are painted where the pointer drifted them`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val pointer = PointerParallax(centre = Offset(200f, 200f))
        val ui = scene(backend, pointer, stick = null)
        try {
            frame(ui).use {
                assertColour(Color.BLUE, it.at(10, 10), "at rest the near layer sits in the corner")
                assertColour(Color.RED, it.at(95, 95), "and the far one fills to 100")
                assertColour(Color.BLACK, it.at(105, 105), "and no further")
            }

            ui.moveTo(Offset(300f, 300f))

            frame(ui).use {
                assertColour(Color.RED, it.at(15, 15), "the near layer ran off the corner, uncovering the far one")
                assertColour(Color.BLUE, it.at(110, 110), "a hundred along, the whole of the pointer's move")
                assertColour(Color.RED, it.at(105, 50), "the far layer crept ten, a tenth of it")
                assertColour(Color.BLACK, it.at(5, 105), "and left the edge it came from")
            }
        } finally {
            ui.close()
            backend.dispose()
        }
    }

    @Test
    fun `the right stick paints the layer where it pushed it`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val stick = StickParallax(reach = 100f)
        val ui = scene(backend, pointer = null, stick)
        try {
            ui.stick(1f, 0.5f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

            frame(ui).use {
                assertColour(Color.BLACK, it.at(10, 10), "the corner it started in is empty")
                assertColour(Color.BLUE, it.at(110, 60), "a full push right and half a push down")
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
