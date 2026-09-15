package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.ShakeState
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.rememberShake
import dev.wildware.composegl.ui.modifier.shake
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `Modifier.shake`, in pixels, through the real renderer.
 *
 * The recording canvas says the rectangle was asked for somewhere else. This says it came out
 * there: the red panel's pixels move by the shake's offset, stay the same size, and land back on
 * exactly the pixels they started on, while the button beside it never moves at all.
 */
class ShakePixelsTest {

    private data class Box4(val left: Int, val top: Int, val right: Int, val bottom: Int)

    @Test
    fun `a clicked shake moves the panel pixels and puts them back`() = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        val focus = FocusManager(host.root)
        val router = PointerRouter(host.root, focus)
        lateinit var shake: ShakeState
        try {
            host.setContent {
                val state = rememberShake(maxOffset = 16f).also { shake = it }
                Box(Modifier.fillMaxSize().background(Colour.Black)) {
                    // A gap wider than the shake can reach, so the panel never slides under the
                    // button and has its edge hidden by it.
                    Row(Modifier.padding(left = 60f, top = 60f), horizontalArrangement = Arrangement.spacedBy(40f)) {
                        Box(Modifier.testTag("panel").shake(state).size(80f, 50f).background(Red))
                        Box(Modifier.testTag("submit").size(50f, 50f).background(Blue).clickable { state.trigger() })
                    }
                }
            }
            val ui = UiRenderer(host, canvas)
            val viewport = Viewport(
                design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
                physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
                policy = ScalePolicy.Fit,
            )
            var nanos = 0L
            fun draw(): Pair<Box4, Box4> {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                ui.render(viewport, nanos)
                nanos += 16_666_667L
                return read()
            }

            repeat(3) { draw() }
            val (still, button) = draw()
            // Within a pixel: the rectangle's edges are antialiased, so the outermost row is not
            // fully red and the colour test can miss it.
            assertNear(Box4(60, 60, 140, 110), still, "the panel before anything happens")
            assertNear(Box4(180, 60, 230, 110), button, "the button beside it")

            val centre = host.root.find("submit").boundsInRoot.centre
            router.onPointer(PointerEvent.Press(PointerId.Mouse, centre))
            router.onPointer(PointerEvent.Release(PointerId.Mouse, centre))

            var furthest = 0
            repeat(20) {
                val (panel, beside) = draw()
                val offset: Offset = shake.offset
                val moved = host.root.find("panel").boundsInRoot
                assertTrue(abs((panel.right - panel.left) - (still.right - still.left)) <= 1, "shaking never resizes it: $panel")
                assertTrue(abs((panel.bottom - panel.top) - (still.bottom - still.top)) <= 1, "shaking never resizes it: $panel")
                assertTrue(abs(panel.left - moved.left.roundToInt()) <= 2, "pixels at ${panel.left}, box at ${moved.left} ($offset)")
                assertTrue(abs(panel.top - moved.top.roundToInt()) <= 2, "pixels at ${panel.top}, box at ${moved.top} ($offset)")
                assertEquals(button, beside, "the button beside it never moves")
                furthest = maxOf(furthest, abs(panel.left - still.left), abs(panel.top - still.top))
            }
            assertTrue(furthest >= 4, "a full knock moves real pixels, and moved $furthest")

            repeat(60) { draw() }
            val (after, _) = draw()
            assertNotEquals(0, furthest)
            assertEquals(still, after, "and lands back on exactly the pixels it started on")
        } finally {
            host.dispose()
            canvas.dispose()
        }
    }

    private fun assertNear(expected: Box4, actual: Box4, what: String) {
        val near = abs(expected.left - actual.left) <= 1 && abs(expected.top - actual.top) <= 1 &&
            abs(expected.right - actual.right) <= 1 && abs(expected.bottom - actual.bottom) <= 1
        assertTrue(near, "$what: expected about $expected but was $actual")
    }

    /** The bounding boxes of the red and the blue, in top-down window pixels. */
    private fun read(): Pair<Box4, Box4> {
        val size = Gl.size
        val frame = Pixmap.createFromFrameBuffer(0, 0, size, size)
        try {
            return bounds(size) { x, y -> isRed(frame.getPixel(x, size - 1 - y)) } to
                bounds(size) { x, y -> isBlue(frame.getPixel(x, size - 1 - y)) }
        } finally {
            frame.dispose()
        }
    }

    private inline fun bounds(size: Int, hit: (Int, Int) -> Boolean): Box4 {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (!hit(x, y)) continue
            if (x < left) left = x
            if (y < top) top = y
            if (x > right) right = x
            if (y > bottom) bottom = y
        }
        return Box4(left, top, right + 1, bottom + 1)
    }

    private fun isRed(rgba: Int) = (rgba ushr 24) and 0xFF > 200 && (rgba ushr 16) and 0xFF < 100 && (rgba ushr 8) and 0xFF < 100

    private fun isBlue(rgba: Int) = (rgba ushr 24) and 0xFF < 100 && (rgba ushr 8) and 0xFF > 200

    private companion object {
        val Red = Colour.rgb(0xE83030)
        val Blue = Colour.rgb(0x3050F0)
    }
}
