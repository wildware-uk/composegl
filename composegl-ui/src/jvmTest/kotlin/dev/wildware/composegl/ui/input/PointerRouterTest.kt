package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.hitShape
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a pointer event goes, and what it means when it gets there.
 *
 * Every test here is a [TestTree]: rectangles where this test put them, because hit testing is
 * about rectangles and ancestry and nothing else. No layout pass, no canvas, no window.
 */
class PointerRouterTest {

    private val screen = TestTree()
    private val router = PointerRouter(screen.root)

    private var clicks = 0

    private fun press(x: Float, y: Float) =
        router.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun move(x: Float, y: Float, pressed: Set<PointerButton> = emptySet()) =
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y), pressed))

    private fun release(x: Float, y: Float) =
        router.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    private fun cancel(x: Float, y: Float) =
        router.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(x, y)))

    init {
        screen.root.width = 200f
        screen.root.height = 200f
    }

    // --- finding the node ---------------------------------------------------------------------

    @Test
    fun `the deepest node under the pointer is the one that gets it`() {
        val outer = screen.box("outer", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        screen.box("inner", 10f, 10f, 20f, 20f, Modifier.clickable { clicks += 10 }, parent = outer)

        press(15f, 15f)
        release(15f, 15f)

        assertEquals(10, clicks, "the inner node contains the point, so the outer one never sees it")
    }

    @Test
    fun `overlapping siblings resolve to the one drawn on top`() {
        screen.box("under", 0f, 0f, 50f, 50f, Modifier.clickable { clicks += 1 })
        screen.box("over", 0f, 0f, 50f, 50f, Modifier.clickable { clicks += 10 })

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(10, clicks, "the later sibling is drawn last, so it is on top")
    }

    @Test
    fun `a node nobody can interact with is not in the way`() {
        val scenery = screen.box("scenery", 0f, 0f, 100f, 100f)
        screen.box("button", 0f, 0f, 20f, 20f, Modifier.clickable { clicks += 1 }, parent = scenery)

        press(5f, 5f)
        release(5f, 5f)

        assertEquals(1, clicks)
    }

    @Test
    fun `a clip stops the pointer reaching anything outside it`() {
        val panel = screen.box("panel", 0f, 0f, 40f, 40f, Modifier.clip())
        // Placed so that half of it hangs out of the clipping parent.
        screen.box("overflow", 20f, 20f, 40f, 40f, Modifier.clickable { clicks += 1 }, parent = panel)

        press(30f, 30f)
        release(30f, 30f)
        assertEquals(1, clicks, "inside the clip, so it is clickable")

        press(50f, 50f)
        release(50f, 50f)
        assertEquals(1, clicks, "outside the clip, so it is not drawn and cannot be hit")
    }

    @Test
    fun `something invisible cannot be clicked`() {
        screen.box("ghost", 0f, 0f, 50f, 50f, Modifier.alpha(0f).clickable { clicks += 1 })

        assertFalse(press(10f, 10f), "nothing under the pointer, so nothing consumed it")
        release(10f, 10f)
        assertEquals(0, clicks)
    }

    // --- press, capture and the click ---------------------------------------------------------

    @Test
    fun `a press and a release on the node is a click`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        assertTrue(press(10f, 10f))
        assertTrue(state.isPressed)
        assertTrue(release(10f, 10f))

        assertEquals(1, clicks)
        assertFalse(state.isPressed)
    }

    @Test
    fun `dragging off the button and back presses, un-presses and presses again`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        press(10f, 10f)
        assertTrue(state.isPressed)

        move(80f, 80f, setOf(PointerButton.Primary))
        assertFalse(state.isPressed, "the pointer has wandered off, so the button is not pressed")

        move(10f, 10f, setOf(PointerButton.Primary))
        assertTrue(state.isPressed, "and it is pressed again when the pointer comes back")

        release(10f, 10f)
        assertEquals(1, clicks)
    }

    @Test
    fun `letting go somewhere else is a change of mind, not a click`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        press(10f, 10f)
        move(80f, 80f, setOf(PointerButton.Primary))
        release(80f, 80f)

        assertEquals(0, clicks)
        assertFalse(state.isPressed)
    }

    @Test
    fun `a cancelled gesture does not fire a click`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        press(10f, 10f)
        assertTrue(cancel(10f, 10f))

        assertEquals(0, clicks, "the platform took the gesture away")
        assertFalse(state.isPressed)

        // And the capture is gone: a release that arrives afterwards belongs to nobody.
        release(10f, 10f)
        assertEquals(0, clicks)
    }

    @Test
    fun `the node that took the press hears the whole gesture, wherever it goes`() {
        val seen = mutableListOf<PointerEvent>()
        val handler = PointerHandler { seen += it; true }
        screen.box("pad", 0f, 0f, 50f, 50f, Modifier.onPointer(handler))
        screen.box("elsewhere", 100f, 100f, 50f, 50f, Modifier.clickable { clicks += 1 })

        press(10f, 10f)
        move(120f, 120f, setOf(PointerButton.Primary))
        release(120f, 120f)

        assertEquals(3, seen.size, "press, move and release all went to the node that was pressed")
        assertEquals(0, clicks, "and none of them reached what the pointer ended up over")
        // In the pad's own coordinates, which is what a drag handle wants to know.
        assertEquals(Offset(120f, 120f), seen.last().position)
    }

    @Test
    fun `a disabled node still swallows the press`() {
        screen.box("behind", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        screen.box("greyed", 0f, 0f, 50f, 50f, Modifier.clickable(enabled = false) { clicks += 100 })

        assertTrue(press(10f, 10f))
        release(10f, 10f)

        assertEquals(0, clicks, "a click cannot fall through a disabled button to what is under it")
    }

    @Test
    fun `a handler that declines lets the event through to what is behind`() {
        val declining = PointerHandler { false }
        screen.box("behind", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        screen.box("glass", 0f, 0f, 50f, 50f, Modifier.onPointer(declining))

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(1, clicks)
    }

    // --- hover --------------------------------------------------------------------------------

    @Test
    fun `hover follows the pointer, and a node and its ancestors are hovered together`() {
        val panel = InteractionState()
        val button = InteractionState()
        val outer = screen.box("panel", 0f, 0f, 100f, 100f, Modifier.interaction(panel))
        screen.box("button", 10f, 10f, 20f, 20f, Modifier.interaction(button), parent = outer)

        move(15f, 15f)
        assertTrue(button.isHovered)
        assertTrue(panel.isHovered, "the pointer is inside the panel too")

        move(60f, 60f)
        assertFalse(button.isHovered)
        assertTrue(panel.isHovered)

        move(150f, 150f)
        assertFalse(panel.isHovered)
    }

    @Test
    fun `leaving the window ends hover but not a drag`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        move(10f, 10f)
        assertTrue(state.isHovered)

        press(10f, 10f)
        assertFalse(state.isHovered, "a gesture has started; it is pressed, not hovered")

        router.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(-5f, -5f)))
        assertTrue(state.isPressed, "the drag survives the mouse leaving the window")

        release(10f, 10f)
        assertEquals(1, clicks)
    }

    @Test
    fun `hover comes back when the gesture ends`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        move(10f, 10f)
        press(10f, 10f)
        release(10f, 10f)

        assertTrue(state.isHovered, "the pointer is still over it")
        assertFalse(state.isPressed)
    }

    @Test
    fun `cancelling everything lets go of hover and press alike`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        move(10f, 10f)
        press(10f, 10f)
        router.cancelAll()

        assertFalse(state.isPressed)
        assertFalse(state.isHovered)

        release(10f, 10f)
        assertEquals(0, clicks)
    }

    // --- the rest -----------------------------------------------------------------------------

    @Test
    fun `a scroll goes to the deepest node under the pointer and captures nothing`() {
        val seen = mutableListOf<PointerEvent>()
        val outer = screen.box("list", 0f, 0f, 100f, 100f, Modifier.onPointer { seen += it; true })
        screen.box("row", 0f, 0f, 100f, 20f, Modifier.interaction(InteractionState()), parent = outer)

        router.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(10f, 10f), Offset(0f, -3f)))

        assertEquals(1, seen.size, "the row watches but does not handle, so the list gets it")
        press(10f, 10f)
        assertEquals(2, seen.size, "and the scroll captured nothing, so the press is a fresh hit")
    }

    @Test
    fun `two fingers on the same node keep it pressed until both let go`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        router.onPointer(PointerEvent.Press(PointerId(1), Offset(10f, 10f), type = PointerType.Touch))
        router.onPointer(PointerEvent.Press(PointerId(2), Offset(20f, 20f), type = PointerType.Touch))
        assertTrue(state.isPressed)

        router.onPointer(PointerEvent.Release(PointerId(1), Offset(10f, 10f), type = PointerType.Touch))
        assertTrue(state.isPressed, "the second finger is still down")
        assertEquals(1, clicks)

        router.onPointer(PointerEvent.Release(PointerId(2), Offset(20f, 20f), type = PointerType.Touch))
        assertFalse(state.isPressed)
        assertEquals(2, clicks)
    }

    // --- scale ---------------------------------------------------------------------------------
    //
    // The half of Modifier.scale that no screenshot can check. A scale that reached only the
    // drawing would look perfect and take its clicks where the widget used to be.

    /** A canvas that cannot make offscreen pictures, so a scale has nothing to happen in. */
    private class Plain(canvas: RecordingCanvas = RecordingCanvas()) : UiCanvas by canvas {
        override val drawsLayers: Boolean get() = false
        override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? = null
    }

    @Test
    fun `a click lands on a grown button where it is drawn`() {
        // 40 by 20 at the origin, drawn at twice the size about its centre: -20,-10 to 60,30.
        screen.box("button", 0f, 0f, 40f, 20f, Modifier.scale(2f).clickable { clicks += 1 })

        press(50f, 25f)
        release(50f, 25f)

        assertEquals(1, clicks, "well outside the rectangle it was laid out in, well inside the one drawn")
    }

    @Test
    fun `a click on a shrunken button misses the space it no longer fills`() {
        // The other direction, and the one an origin bug hides in: laid out 0..40, drawn 10..30.
        screen.box("button", 0f, 0f, 40f, 20f, Modifier.scale(0.5f).clickable { clicks += 1 })

        press(35f, 10f)
        release(35f, 10f)
        assertEquals(0, clicks, "inside where it was laid out, outside where it is drawn")

        press(20f, 10f)
        release(20f, 10f)
        assertEquals(1, clicks, "and the middle still works")
    }

    @Test
    fun `a handler inside a scaled node is told where the pointer is in its own units`() {
        var seen: Offset? = null
        screen.box(
            "panel", 0f, 0f, 100f, 100f,
            Modifier.scale(0.5f).onPointer { event ->
                seen = event.position
                true
            },
        )

        // Drawn 25..75, so its middle is still 50,50 on screen — and 50,50 to itself, because a
        // node drawn at half size is still a hundred units wide to everything inside it.
        press(50f, 50f)

        assertEquals(Offset(50f, 50f), seen)

        // A quarter of the way across what is drawn is a quarter of the way across the node.
        press(37.5f, 37.5f)
        assertEquals(Offset(25f, 25f), seen)
    }

    @Test
    fun `dragging off what is drawn un-presses, even while inside what was laid out`() {
        val state = InteractionState()
        screen.box("button", 0f, 0f, 40f, 20f, Modifier.scale(0.5f).interaction(state).clickable { clicks += 1 })

        press(20f, 10f)
        assertTrue(state.isPressed)

        move(35f, 10f, setOf(PointerButton.Primary))
        assertFalse(state.isPressed, "the pointer has left the button as drawn")

        release(35f, 10f)
        assertEquals(0, clicks, "and letting go out there is a change of mind, not a click")
    }

    @Test
    fun `a scale inside a scale is hit where the two together put it`() {
        val outer = screen.box("outer", 0f, 0f, 100f, 100f, Modifier.scale(0.5f))
        // 20 wide at 40,40 inside its parent, doubled about its own centre to 30..70 there, then
        // the whole parent halved about its centre: 40..60 on screen.
        screen.box("inner", 40f, 40f, 20f, 20f, Modifier.scale(2f).clickable { clicks += 1 }, parent = outer)

        press(48f, 48f)
        release(48f, 48f)
        assertEquals(1, clicks)

        press(65f, 48f)
        release(65f, 48f)
        assertEquals(1, clicks, "outside the two of them together")
    }

    @Test
    fun `a canvas that refused the picture is clicked where the widget actually is`() {
        screen.box("button", 0f, 0f, 40f, 20f, Modifier.scale(2f).clickable { clicks += 1 })
        DrawPass(Plain()).draw(screen.root)

        press(50f, 25f)
        release(50f, 25f)
        assertEquals(0, clicks, "nothing was drawn out there, so nothing is clickable out there")

        press(20f, 10f)
        release(20f, 10f)
        assertEquals(1, clicks, "it is drawn at its ordinary size, and that is where it is clicked")
    }

    @Test
    fun `a child hanging out of a scaled panel is not clicked where the capture cut it off`() {
        // A scale captures exactly the panel's own rectangle, so the child is chopped at the
        // panel's edge — the same rule as `Modifier.clip`, arriving without anyone asking for it.
        // Laid out, the child runs 90..130 inside the panel; drawn, it stops at 100 with the
        // panel, and the panel is half size about its centre, so 75 is the last drawn pixel.
        val panel = screen.box("panel", 0f, 0f, 100f, 100f, Modifier.scale(0.5f))
        screen.box("child", 90f, 90f, 40f, 40f, Modifier.clickable { clicks += 1 }, parent = panel)

        press(80f, 80f)
        release(80f, 80f)
        assertEquals(0, clicks, "five pixels past anything the panel let out, so nothing to click")

        press(72f, 72f)
        release(72f, 72f)
        assertEquals(1, clicks, "inside the panel, where the child really is drawn")
    }

    @Test
    fun `a node scaled to nothing cannot be clicked`() {
        screen.box("button", 0f, 0f, 40f, 20f, Modifier.scale(0f).clickable { clicks += 1 })

        press(20f, 10f)
        release(20f, 10f)

        assertEquals(0, clicks)
    }

    // --- shapes that are not rectangles -------------------------------------------------------

    @Test
    fun `a shape narrows the rectangle to the part the node really covers`() {
        screen.box(
            "button", 0f, 0f, 40f, 20f,
            Modifier.hitShape { it.x >= 20f }.clickable { clicks += 1 },
        )

        press(10f, 10f)
        release(10f, 10f)
        assertEquals(0, clicks, "the rectangle contains that point but the shape gives it up")

        press(30f, 10f)
        release(30f, 10f)
        assertEquals(1, clicks, "and keeps the half it does claim")
    }

    @Test
    fun `a corner a shape gives up belongs to whatever is underneath`() {
        screen.box("under", 0f, 0f, 50f, 50f, Modifier.clickable { clicks += 1 })
        screen.box(
            "over", 0f, 0f, 50f, 50f,
            Modifier.hitShape { !(it.x < 25f && it.y < 25f) }.clickable { clicks += 10 },
        )

        press(5f, 5f)
        release(5f, 5f)
        assertEquals(1, clicks, "the node on top does not claim its top-left quarter")

        press(40f, 40f)
        release(40f, 40f)
        assertEquals(11, clicks, "and is still on top everywhere else")
    }

    @Test
    fun `a shape is asked in the node's own units, whatever is scaling it`() {
        // 40 wide, drawn at twice that from its top-left corner, so the screen is two node units
        // to the pixel and the shape's own halfway line lands at 40 rather than at 20.
        screen.box(
            "button", 0f, 0f, 40f, 20f,
            Modifier.scale(2f, Alignment.TopStart).hitShape { it.x >= 20f }
                .clickable { clicks += 1 },
        )

        press(30f, 10f)
        release(30f, 10f)
        assertEquals(0, clicks, "15 across in the node's own units, which the shape gives up")

        press(50f, 10f)
        release(50f, 10f)
        assertEquals(1, clicks, "25 across, which it claims")
    }

    @Test
    fun `a shape on a panel does not take its children with it`() {
        val panel = screen.box(
            "panel", 0f, 0f, 100f, 100f,
            Modifier.hitShape { false }.clickable { clicks += 1 },
        )
        screen.box("button", 10f, 10f, 20f, 20f, Modifier.clickable { clicks += 10 }, parent = panel)

        press(15f, 15f)
        release(15f, 15f)
        assertEquals(10, clicks, "a shape is not a clip: it speaks for its own node and no other")

        press(60f, 60f)
        release(60f, 60f)
        assertEquals(10, clicks, "and the panel itself claims nothing anywhere")
    }

    @Test
    fun `two shapes on one node are two answers, so the later one wins`() {
        screen.box(
            "button", 0f, 0f, 40f, 20f,
            Modifier.hitShape { false }.hitShape { true }.clickable { clicks += 1 },
        )

        press(20f, 10f)
        release(20f, 10f)
        assertEquals(1, clicks)
    }

    @Test
    fun `an ancestor is hovered only where it would have been clicked`() {
        val panel = InteractionState()
        val button = InteractionState()
        val outer = screen.box(
            "panel", 0f, 0f, 100f, 100f,
            Modifier.interaction(panel).hitShape { it.x >= 50f },
        )
        screen.box("button", 10f, 10f, 20f, 20f, Modifier.interaction(button), parent = outer)

        move(15f, 15f)
        assertTrue(button.isHovered, "the button is a node of its own and has no shape")
        assertFalse(panel.isHovered, "but the pointer is on a part of the panel it gives up")

        move(60f, 60f)
        assertTrue(panel.isHovered, "and on a part it claims, it is hovered like anything else")
    }
}
