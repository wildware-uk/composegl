package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.zIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Modifier.zIndex` through the real renderer: a composed tree, drawn by the draw pass onto a
 * GdxCanvas, read back as pixels.
 *
 * The recording canvas already says the calls go out in the right order. What it cannot say is
 * that the order survives the batch — a renderer that sorted by texture, or flushed rectangles
 * and pictures separately, would keep the call order and still paint the wrong card on top.
 */
class GdxZIndexTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xE0303A)
    private val green = Colour.rgb(0x30C060)
    private val blue = Colour.rgb(0x3070E0)

    /** Which card is lifted. Changed from the test, the way a click handler changes it. */
    private var lifted by mutableStateOf(-1)

    /** Three cards fanned out like a hand, each overlapping the next by half. */
    @Composable
    private fun Hand(clicked: MutableList<Int>, table: Modifier = Modifier) {
        Box(Modifier.size(240f, 240f).background(Colour.rgb(0x12161D)).then(table)) {
            listOf(red, green, blue).forEachIndexed { index, colour ->
                LeafLayout(
                    Modifier
                        .offset(20f + index * 50f, 40f + index * 20f)
                        .size(100f, 140f)
                        .zIndex(if (lifted == index) 1f else 0f)
                        .background(colour)
                        .clickable { clicked += index },
                )
            }
        }
    }

    /** One frame on the GL thread, handed back as the top-left 240 pixels, y down. */
    private fun frame(host: UiHost, nanos: Long): Pixmap = Gl.render {
        val batch = SpriteBatch()
        val canvas = GdxCanvas(batch)
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            UiRenderer(host, canvas).render(viewport, nanos)
            Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        } finally {
            canvas.dispose()
            batch.dispose()
        }
    }

    private fun Pixmap.rgbAt(x: Int, y: Int) = getPixel(x, Gl.size - 1 - y) ushr 8

    private fun assertNear(expected: Colour, actual: Int, because: String) {
        val want = expected.argb and 0xFFFFFF
        val channels = listOf(16, 8, 0).map { shift -> kotlin.math.abs((want shr shift and 0xFF) - (actual shr shift and 0xFF)) }
        assertTrue(channels.all { it <= 6 }, "$because: expected #%06X, got #%06X".format(want, actual))
    }

    @Test
    fun `a lifted card is painted over its neighbours and takes the click there`() {
        val host = UiHost()
        val clicked = mutableListOf<Int>()
        try {
            host.setContent { Hand(clicked) }

            // Where red and green overlap, and where green and blue overlap.
            val redGreen = 95 to 100
            val greenBlue = 145 to 120

            val plain = frame(host, 0L)
            try {
                assertNear(green, plain.rgbAt(redGreen.first, redGreen.second), "source order: green is written after red")
                assertNear(blue, plain.rgbAt(greenBlue.first, greenBlue.second), "and blue after green")
            } finally {
                plain.dispose()
            }

            lifted = 1
            val raised = frame(host, 16_666_667L)
            try {
                assertNear(green, raised.rgbAt(redGreen.first, redGreen.second), "green is still over red")
                assertNear(green, raised.rgbAt(greenBlue.first, greenBlue.second), "and now over blue as well")
                Goldens.assertMatches("z-index", imageOf(240, 240) { x, y -> raised.rgbAt(x, y) })
            } finally {
                raised.dispose()
            }

            // The press lands on the pixel the player can see.
            val pointer = PointerRouter(host.root)
            pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(145f, 120f)))
            pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(145f, 120f)))
            assertEquals(listOf(1), clicked, "the card on top is the card that was clicked")
        } finally {
            host.dispose()
        }
    }

    @Test
    fun `a lifted card inside a shaped clip is still painted on top`() {
        val host = UiHost()
        val clicked = mutableListOf<Int>()
        try {
            // A shaped clip draws its children into a picture by a path of its own, which has to
            // keep the lift as well.
            host.setContent { Hand(clicked, Modifier.clipShape(Shapes.roundedRect(16f))) }
            val greenBlue = 145 to 120

            lifted = 1
            val raised = frame(host, 0L)
            try {
                assertNear(green, raised.rgbAt(greenBlue.first, greenBlue.second), "green is lifted over blue")
            } finally {
                raised.dispose()
            }

            val pointer = PointerRouter(host.root)
            pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(145f, 120f)))
            pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(145f, 120f)))
            assertEquals(listOf(1), clicked, "and the green pixel is the green card's click")
        } finally {
            host.dispose()
        }
    }
}
