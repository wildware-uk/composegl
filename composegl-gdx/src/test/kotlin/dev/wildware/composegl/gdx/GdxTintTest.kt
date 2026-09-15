package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.tint
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Modifier.tint` on a real screen, through the whole frame: composed in a host, clicked with a
 * pointer, laid out and drawn by the renderer into a GL canvas, and read back as pixels.
 */
class GdxTintTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private fun Pixmap.at(x: Float, y: Float) = Color(getPixel(x.toInt(), Gl.size - 1 - y.toInt()))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = kotlin.math.abs(expected.r - actual.r) < 0.03f &&
            kotlin.math.abs(expected.g - actual.g) < 0.03f &&
            kotlin.math.abs(expected.b - actual.b) < 0.03f
        assertTrue(close, "$because expected about $expected, got $actual")
    }

    @Test
    fun `clicking a slot locks it and the lock dims it on screen and nothing beside it`() = Gl.render {
        val canvas = GdxCanvas()
        val host = UiHost()
        val renderer = UiRenderer(host, canvas)
        val focus = FocusManager(host.root)
        var locked by mutableStateOf(false)
        var clock = 0L

        fun frame(): Pixmap {
            clock += 16_666_667L
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            renderer.render(viewport, clock)
            return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        }

        try {
            host.setContent {
                Row(Modifier.padding(10f)) {
                    LeafLayout(
                        Modifier.size(60f, 60f)
                            .tint(if (locked) Colour.Grey else Colour.White)
                            .background(Colour.Orange)
                            .clickable { locked = !locked }
                            .testTag("slot"),
                    )
                    LeafLayout(Modifier.size(60f, 60f).background(Colour.Orange).testTag("neighbour"))
                }
            }

            val orange = Color(1f, 0.5f, 0f, 1f)

            val before = frame()
            val slot = host.root.find("slot")
            val neighbour = host.root.find("neighbour")
            assertColour(orange, before.at(slot.boundsInRoot.centre.x, slot.boundsInRoot.centre.y), "unlocked:")
            before.dispose()

            val pointer = PointerRouter(host.root, focus)
            val centre = host.root.find("slot").boundsInRoot.centre
            pointer.onPointer(PointerEvent.Press(PointerId.Mouse, centre))
            pointer.onPointer(PointerEvent.Release(PointerId.Mouse, centre))

            val after = frame()
            assertColour(Color(0.5f, 0.25f, 0f, 1f), after.at(centre.x, centre.y), "locked, the orange is halved:")
            val beside = neighbour.boundsInRoot.centre
            assertColour(orange, after.at(beside.x, beside.y), "the slot next to it is untouched:")
            after.dispose()
        } finally {
            host.dispose()
            canvas.dispose()
        }
    }

    @Test
    fun `a tint on a scaled panel comes out tinted once`() = Gl.render {
        val canvas = GdxCanvas()
        val host = UiHost()
        val renderer = UiRenderer(host, canvas)
        try {
            host.setContent {
                Box(Modifier.padding(40f)) {
                    Box(Modifier.size(80f, 80f).tint(Colour.rgb(0x808080)).scale(1.25f)) {
                        LeafLayout(Modifier.size(80f, 80f).background(Colour.White))
                    }
                }
            }

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            renderer.render(viewport, 16_666_667L)
            val pixels = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)

            // Drawn through a picture because of the scale: tinted going in, and not again coming out.
            assertColour(Color(0.5f, 0.5f, 0.5f, 1f), pixels.at(80f, 80f), "half grey, not a quarter:")
            pixels.dispose()
        } finally {
            host.dispose()
            canvas.dispose()
        }
    }
}
