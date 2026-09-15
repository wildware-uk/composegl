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
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** A square dragged by a mouse on a real GPU, judged by where its pixels end up. */
class DraggableGlTest {

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
    fun `a dragged square is drawn where it was let go and nowhere else`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            var at by remember { mutableStateOf(Offset(20f, 20f)) }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.offset(at.x, at.y).size(60f, 60f)
                        .background(Colour.rgb(0xFF0000))
                        .draggable { at += it }
                        .testTag("square"),
                )
            }
        }
        try {
            frame { ui.render() }.also {
                assertColour(Color.RED, it.at(50, 50), "before the drag")
                it.dispose()
            }

            ui.press(Offset(30f, 30f))
            ui.moveTo(Offset(80f, 60f))
            ui.moveTo(Offset(130f, 110f))
            ui.release()

            frame { ui.render() }.also {
                assertColour(Color.BLACK, it.at(40, 40), "where it was")
                assertColour(Color.RED, it.at(150, 150), "where it was dragged to")
                assertColour(Color.RED, it.at(121, 121), "its top left corner")
                assertColour(Color.BLACK, it.at(118, 118), "just outside that corner")
                it.dispose()
            }
            Unit
        } finally {
            ui.close()
            backend.dispose()
        }
    }
}
