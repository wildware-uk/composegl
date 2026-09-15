package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Where a node says it is once something above it has been mirrored.
 *
 * The arithmetic on its own, pinned to numbers worked out by hand, the same way
 * [ScaledBoundsTest] pins a scale. The pointer router works the same rectangles out on its own way
 * down the tree, so the last test holds the two together.
 */
class MirroredBoundsTest {

    private val screen = TestTree()

    @Test
    fun `a mirror resolves per axis and two of them cancel`() {
        assertTrue(Modifier.mirror().resolve().mirrorX)
        assertFalse(Modifier.mirror().resolve().mirrorY)
        assertTrue(Modifier.mirror(horizontal = false, vertical = true).resolve().mirrorY)
        assertFalse(Modifier.mirror().mirror().resolve().mirrorX, "flipped twice is the way it was")
        assertFalse(Modifier.mirror(horizontal = false).resolve().mirrorX, "a flag that says no")
    }

    @Test
    fun `a negative scale points at the mirror instead of flipping`() {
        val error = assertThrows<IllegalArgumentException> { Modifier.scale(-1f) }
        assertTrue("mirror" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a child of a mirrored node is drawn the other side of its middle`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.mirror())
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        // 10..50 across a 200-wide node is 150..190 read from the other side.
        assertEquals(Rect.of(250f, 70f, 40f, 20f), label.boundsInRoot)
        assertEquals(Rect.of(100f, 50f, 200f, 100f), panel.boundsInRoot, "its own rectangle does not move")
        assertEquals(Rect.of(110f, 70f, 40f, 20f), label.layoutBoundsInRoot, "and nor does layout")
        assertEquals(1f, label.scaleInRoot, "a mirror is not a size")
    }

    @Test
    fun `a vertical mirror swaps top for bottom`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.mirror(horizontal = false, vertical = true))
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        assertEquals(Rect.of(110f, 110f, 40f, 20f), label.boundsInRoot)
    }

    @Test
    fun `a mirror flips in place and then the scale grows it`() {
        val panel = screen.box("panel", 100f, 50f, 200f, 100f, Modifier.mirror().scale(0.5f))
        val label = screen.box("label", 10f, 20f, 40f, 20f, parent = panel)

        // Flipped to 150..190 inside the panel, then halved about its centre (100, 50): 125..145
        // across and 35..45 down, then moved to where the panel is.
        assertEquals(Rect.of(225f, 85f, 20f, 10f), label.boundsInRoot)
    }

    @Test
    fun `the scale's origin is still a place in the node`() {
        val panel = screen.box("panel", 0f, 0f, 200f, 100f, Modifier.mirror().scale(0.5f, Alignment.TopStart))
        val label = screen.box("label", 0f, 0f, 40f, 20f, parent = panel)

        // Flipped to 160..200, then halved from the top-left corner.
        assertEquals(Rect.of(80f, 0f, 20f, 10f), label.boundsInRoot)
    }

    @Test
    fun `a mirror inside a narrower mirror lands where both put it`() {
        val outer = screen.box("outer", 0f, 0f, 100f, 100f, Modifier.mirror())
        val inner = screen.box("inner", 0f, 0f, 50f, 100f, Modifier.mirror(), parent = outer)
        val cell = screen.box("cell", 10f, 0f, 20f, 20f, parent = inner)

        // 10..30 in a 50-wide node flips to 20..40; that in a 100-wide node flips to 60..80.
        assertEquals(Rect.of(60f, 0f, 20f, 20f), cell.boundsInRoot)
    }

    @Test
    fun `a point on screen comes back in the node's own mirrored units`() {
        val panel = screen.box("panel", 0f, 0f, 200f, 100f, Modifier.mirror())
        assertEquals(Offset(50f, 20f), panel.toLocal(Offset(150f, 20f)))

        val shrunk = screen.box("shrunk", 0f, 200f, 200f, 100f, Modifier.mirror(vertical = true).scale(0.5f))
        // Drawn 50..150 across and 225..275 down, both axes read from the far side, at half size.
        assertEquals(Offset(50f, 50f), shrunk.toLocal(Offset(125f, 250f)))
        assertEquals(Offset(200f, 100f), shrunk.toLocal(Offset(50f, 225f)))
    }

    @Test
    fun `the pointer router finds what boundsInRoot says is there`() {
        // Every combination the router has its own copy of the arithmetic for: a mirror above a
        // scale above a mirror, with the leaf off-centre on both axes. It is clicked in the middle
        // of where it says it is drawn, and just outside each edge of it.
        var clicks = 0
        val outer = screen.box("outer", 20f, 10f, 300f, 200f, Modifier.mirror())
        val middle = screen.box("middle", 30f, 20f, 200f, 160f, Modifier.scale(0.5f, Alignment.TopStart), parent = outer)
        val inner = screen.box("inner", 10f, 10f, 150f, 100f, Modifier.mirror(horizontal = true, vertical = true), parent = middle)
        val leaf = screen.box("leaf", 5f, 15f, 40f, 30f, Modifier.clickable { clicks++ }, parent = inner)
        val pointer = PointerRouter(screen.root, FocusManager(screen.root))

        fun clickAt(x: Float, y: Float) {
            pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))
            pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))
        }

        val drawn = leaf.boundsInRoot
        clickAt(drawn.centre.x, drawn.centre.y)
        assertEquals(1, clicks, "the middle of $drawn")
        clickAt(drawn.left - 1f, drawn.centre.y)
        clickAt(drawn.right + 1f, drawn.centre.y)
        clickAt(drawn.centre.x, drawn.top - 1f)
        clickAt(drawn.centre.x, drawn.bottom + 1f)
        assertEquals(1, clicks, "nothing just outside $drawn")
    }
}
