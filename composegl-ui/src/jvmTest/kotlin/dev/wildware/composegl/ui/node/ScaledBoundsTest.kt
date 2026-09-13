package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a node says it is once something above it has been scaled.
 *
 * The arithmetic on its own, away from any drawing. Everything that asks where a node is — hit
 * testing, focus scoring, a game doing its own sums — asks `boundsInRoot`, so this is the one
 * place the answer is pinned to numbers worked out by hand.
 */
class ScaledBoundsTest {

    private val screen = TestTree()

    @Test
    fun `a tree with no scale in it is the sum it always was`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f)
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        assertEquals(Rect.of(110f, 70f, 40f, 20f), label.boundsInRoot)
        assertEquals(label.layoutBoundsInRoot, label.boundsInRoot)
        assertEquals(1f, label.scaleInRoot)
    }

    @Test
    fun `a scale of one changes nothing at all`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.scale(1f))
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        assertEquals(label.layoutBoundsInRoot, label.boundsInRoot)
    }

    @Test
    fun `a child of a scaled node moves and grows with it`() {
        // The panel is 200 by 100 at 100,50, drawn at half size about its centre: 150,75 stays
        // where it is, so the panel is drawn 150..250 across and 75..125 down.
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.scale(0.5f))
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        assertEquals(Rect.of(150f, 75f, 100f, 50f), panel.boundsInRoot)
        // The label is at 110,70 with nothing scaled; half the distance from the same centre.
        assertEquals(Rect.of(155f, 85f, 20f, 10f), label.boundsInRoot)
        assertEquals(0.5f, label.scaleInRoot)

        // And layout still says what layout said, which is what a measurement wants.
        assertEquals(Rect.of(110f, 70f, 40f, 20f), label.layoutBoundsInRoot)
        assertEquals(Rect.of(10f, 20f, 40f, 20f), label.bounds)
    }

    @Test
    fun `the origin picks the point that does not move`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.scale(2f, Alignment.TopStart))

        assertEquals(Rect.of(100f, 50f, 400f, 200f), panel.boundsInRoot)
    }

    @Test
    fun `scales compose down the tree`() {
        val outer = screen.box("outer", 0f, 0f, 100f, 100f, Modifier.scale(0.5f))
        val inner = screen.box("inner", 40f, 40f, 20f, 20f, Modifier.scale(2f), parent = outer)

        // Doubled about its own centre is 30..70 inside the outer box; the outer box halved about
        // its own centre puts that at 40..60 on screen.
        assertEquals(Rect.of(40f, 40f, 20f, 20f), inner.boundsInRoot)
        assertEquals(1f, inner.scaleInRoot, "half of twice is the size it was laid out")
    }

    @Test
    fun `a point on screen comes back in the node's own units`() {
        val panel = screen.box("panel", 0f, 0f, 100f, 100f, Modifier.scale(0.5f))

        // Drawn 25..75, so its own middle is the middle of the screen rectangle, and a quarter of
        // the way across what is drawn is a quarter of the way across the node.
        assertEquals(Offset(50f, 50f), panel.toLocal(Offset(50f, 50f)))
        assertEquals(Offset(25f, 25f), panel.toLocal(Offset(37.5f, 37.5f)))
    }

    @Test
    fun `a node scaled to nothing is drawn nowhere`() {
        val panel = screen.box("panel", 0f, 0f, 100f, 100f, Modifier.scale(0f))

        assertEquals(true, panel.boundsInRoot.isEmpty)
        // Nothing divides by zero on the way out, either.
        assertEquals(Offset.Zero, panel.toLocal(Offset(50f, 50f)))
    }

    @Test
    fun `a camera is a scaled viewport with the world moved inside it`() {
        // The recipe for a pan-and-zoom screen, and the reason panning needs nothing new: an
        // offset lands in the node's own position before anything reads it, so moving the world
        // inside a scaled viewport is screen = zoom x (world - camera), which is what a camera is.
        val viewport = screen.box("viewport", 0f, 0f, 200f, 200f, Modifier.clip().scale(2f))
        val world = screen.box("world", -250f, -150f, 1000f, 1000f, parent = viewport)
        val cell = screen.box("cell", 300f, 200f, 40f, 40f, parent = world)

        // 300 across the world, less a camera at 250, is 50 in the viewport; twice the size about
        // the viewport's centre puts it at the corner.
        assertEquals(Rect.of(0f, 0f, 80f, 80f), cell.boundsInRoot)
        assertEquals(2f, cell.scaleInRoot)
    }
}
