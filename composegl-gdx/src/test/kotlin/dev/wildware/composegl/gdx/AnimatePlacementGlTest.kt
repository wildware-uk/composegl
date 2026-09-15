package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animatePlacement
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Two squares swapped by a click on a real GPU, judged by where the red pixels are on the way. */
class AnimatePlacementGlTest {

    private fun frame(render: () -> Unit): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        render()
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
    fun `a swapped square is drawn on its way down and then where it landed`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        var order by mutableStateOf(listOf("red", "blue"))
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            Column {
                order.forEach { name ->
                    key(name) {
                        Box(
                            Modifier.size(100f, 100f)
                                .animatePlacement(Tween(durationMillis = 400, easing = Easings.Linear))
                                .background(Colour.rgb(if (name == "red") 0xFF0000 else 0x0000FF))
                                .clickable { order = order.reversed() }
                                .testTag(name),
                        )
                    }
                }
            }
        }
        try {
            frame { ui.render() }.also {
                assertColour(Color.RED, it.at(50, 50), "red starts on top")
                assertColour(Color.BLUE, it.at(50, 150), "blue starts underneath")
                it.dispose()
            }

            // Held still so the click's own settling cannot play the slide out before we look.
            ui.host.clocks.stop(Clock.Ui)
            ui.click("red")
            frame { ui.render() }.also {
                assertColour(Color.RED, it.at(50, 50), "the frame the swap happens, red is still where it was")
                it.dispose()
            }

            ui.host.clocks.start(Clock.Ui)
            // Twelve frames of a 400ms linear slide of 100 pixels: about half way.
            repeat(11) { ui.render() }
            frame { ui.render() }.also {
                // Both squares are half way, so they cover the same 50..150 band; red is drawn
                // second now it comes second in the column, so red is what shows there.
                assertColour(Color.RED, it.at(50, 95), "red is half way down")
                assertColour(Color.RED, it.at(50, 60), "its top edge is near 50")
                assertColour(Color.BLACK, it.at(50, 40), "both have left the top slot's upper part")
                assertColour(Color.BLACK, it.at(50, 165), "and neither has reached the bottom of the lower slot")
                it.dispose()
            }

            ui.settle()
            frame { ui.render() }.also {
                assertColour(Color.BLUE, it.at(50, 50), "blue landed on top")
                assertColour(Color.RED, it.at(50, 150), "red landed underneath")
                assertColour(Color.RED, it.at(50, 101), "red's top edge is exactly on its slot")
                it.dispose()
            }
            Unit
        } finally {
            ui.close()
            backend.dispose()
        }
    }
}
