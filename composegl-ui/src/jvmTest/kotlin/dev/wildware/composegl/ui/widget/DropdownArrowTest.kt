package dev.wildware.composegl.ui.widget

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Tag
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A dropdown's arrow, drawn the way every frame draws it: a downward triangle across the top of its
 * box, from points it keeps rather than makes each time (#252).
 */
class DropdownArrowTest {

    /** Keeps a copy of the last fan's points, made only when asked to, and draws nothing else. */
    private class FanCanvas : UiCanvas {
        var copying = true
        var points = FloatArray(0)
        var fans = 0

        override fun fan(points: FloatArray, colour: Colour) {
            fans++
            if (copying) this.points = points.copyOf()
        }

        override fun rect(rect: Rect, colour: Colour, corner: Float) = Unit
        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun text(layout: dev.wildware.composegl.ui.text.TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }

    private fun arrowDraw(): UiCanvas.(Rect) -> Unit {
        val ui = uiTest(Size(400f, 200f)) {
            PopupHost {
                Dropdown(
                    options = listOf("Low", "High"),
                    selected = "Low",
                    onSelect = {},
                    modifier = Modifier.width(200f).testTag("quality"),
                ) { Text(it) }
            }
        }
        try {
            val arrow = checkNotNull(ui.node("quality").children.firstOrNull { it.name == "dropdown.arrow" })
            return checkNotNull(arrow.content) { "the arrow draws itself" }
        } finally {
            ui.close()
        }
    }

    @Test
    fun `the arrow is a downward triangle across the top of its box`() {
        val draw = arrowDraw()
        val canvas = FanCanvas()

        canvas.draw(Rect(10f, 20f, 20f, 26f))
        assertContentEquals(floatArrayOf(10f, 20f, 20f, 20f, 15f, 26f), canvas.points)

        // Drawn again somewhere else, the same points are written afresh rather than kept stale.
        canvas.draw(Rect(100f, 50f, 110f, 56f))
        assertContentEquals(floatArrayOf(100f, 50f, 110f, 50f, 105f, 56f), canvas.points)
    }

    @Test
    @Tag("allocation")
    fun `drawing the arrow allocates nothing`() {
        val draw = arrowDraw()
        val canvas = FanCanvas().apply { copying = false }
        val box = Rect(10f, 20f, 20f, 26f)
        repeat(100) { canvas.draw(box) }

        var least = Long.MAX_VALUE
        repeat(5) {
            val before = threads.currentThreadAllocatedBytes
            repeat(1_000) { canvas.draw(box) }
            least = minOf(least, threads.currentThreadAllocatedBytes - before)
        }

        assertEquals(5_100, canvas.fans)
        assertEquals(0L, least, "a thousand arrows allocated $least bytes")
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
}
