package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.marquee
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `Modifier.marquee`, in pixels, through the real renderer.
 *
 * The recording canvas says the strip was asked to draw somewhere else and cut to its slot. This
 * says that is what came out: the strip's colours slide through the slot, nothing of it shows
 * outside the slot at any point, and the box under it is exactly where it was.
 */
class MarqueePixelsTest {

    private val black = Color(0f, 0f, 0f, 1f)

    /**
     * Three 100-wide bands, red, green and blue, in a 120-wide slot 100 in from the left. Sixty
     * units a second after half a second's rest, so the arithmetic below is easy to follow.
     */
    private fun strip(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        Column(Modifier.offset(100f, 100f)) {
            Row(Modifier.testTag("strip").width(120f).marquee(speed = 60f, delayMillis = 500)) {
                Box(Modifier.size(100f, 40f).background(Colour.rgb(0xFF0000)))
                Box(Modifier.size(100f, 40f).background(Colour.rgb(0x00FF00)))
                Box(Modifier.size(100f, 40f).background(Colour.rgb(0x0000FF)))
            }
            Box(Modifier.testTag("neighbour").size(60f, 20f).background(Colour.rgb(0xFFFF00)))
        }
    }

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    /** Nothing but black either side of the slot, all along the strip's row. */
    private fun assertNothingOutside(pixels: Pixmap, because: String) {
        for (x in listOf(50, 90, 98, 222, 260, 330, 390)) assertColour(black, pixels.at(x, 120), "$because, at x $x")
    }

    /** Which columns the yellow neighbour covers, so it can be checked for not having moved. */
    private fun neighbour(pixels: Pixmap): Set<Int> =
        (0 until Gl.size).filter { x -> pixels.at(x, 150).let { it.r > 0.9f && it.g > 0.9f && it.b < 0.1f } }.toSet()

    @Test
    fun `the strip slides through its slot and never shows outside it`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = strip(backend)
        try {
            var label = emptySet<Int>()
            frame(ui).use {
                assertColour(Color.RED, it.at(110, 120), "resting: red at the start of the slot")
                assertColour(Color.RED, it.at(195, 120), "red fills the first hundred")
                assertColour(Color.GREEN, it.at(210, 120), "and green starts after it")
                assertNothingOutside(it, "resting")
                label = neighbour(it)
            }
            assertTrue(label.size in 58..62, "the neighbour is 60 wide: $label")

            // Half a second of rest, then two seconds at sixty: about 120 along, so the slot's 120
            // shows the strip from about 120 to 240 — green, then blue.
            ui.advanceBy(500 + 2_000)
            frame(ui).use {
                assertColour(Color.GREEN, it.at(130, 120), "green has slid to the start of the slot")
                assertColour(Color.BLUE, it.at(210, 120), "with blue behind it")
                assertNothingOutside(it, "scrolling")
                assertTrue(neighbour(it) == label, "the box under the strip never moved")
            }

            // Past the end of the blue: the red copy coming round behind the gap is in the slot.
            ui.advanceBy(3_000)
            frame(ui).use {
                val colours = (100 until 220 step 4).map { x -> it.at(x, 120) }
                assertTrue(colours.any { c -> c.r > 0.9f && c.g < 0.1f }, "the red copy has come round: $colours")
                assertNothingOutside(it, "coming round")
            }

            ui.key(Key.Tab)
            frame(ui).use { assertNothingOutside(it, "after a key") }
        } finally {
            ui.close()
        }
    }
}
