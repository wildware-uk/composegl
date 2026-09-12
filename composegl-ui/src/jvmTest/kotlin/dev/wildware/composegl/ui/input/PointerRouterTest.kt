package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
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
}
