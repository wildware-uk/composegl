package composegl.ui.input

import composegl.ui.geometry.Offset
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.alpha
import composegl.ui.modifier.clickable
import composegl.ui.modifier.clip
import composegl.ui.modifier.interaction
import composegl.ui.modifier.onPointer
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a pointer event goes, and what it means when it gets there.
 *
 * Every test here builds the tree by hand and places it by hand, because hit testing is about
 * rectangles and ancestry and nothing else. No layout pass, no canvas, no window.
 */
class PointerRouterTest {

    private val tree = UiTree()
    private val router = PointerRouter(tree.root)

    private var clicks = 0

    private fun node(
        name: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        modifier: Modifier = Modifier,
        parent: UiNode = tree.root,
    ): UiNode = UiNode(name).also {
        it.modifier = modifier
        parent.insertAt(parent.children.size, it)
        it.x = x
        it.y = y
        it.width = width
        it.height = height
    }

    private fun press(x: Float, y: Float) =
        router.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun move(x: Float, y: Float, pressed: Set<PointerButton> = emptySet()) =
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y), pressed))

    private fun release(x: Float, y: Float) =
        router.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    private fun cancel(x: Float, y: Float) =
        router.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(x, y)))

    init {
        tree.root.width = 200f
        tree.root.height = 200f
    }

    // --- finding the node ---------------------------------------------------------------------

    @Test
    fun `the deepest node under the pointer is the one that gets it`() {
        val outer = node("outer", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        node("inner", 10f, 10f, 20f, 20f, Modifier.clickable { clicks += 10 }, parent = outer)

        press(15f, 15f)
        release(15f, 15f)

        assertEquals(10, clicks, "the inner node contains the point, so the outer one never sees it")
    }

    @Test
    fun `overlapping siblings resolve to the one drawn on top`() {
        node("under", 0f, 0f, 50f, 50f, Modifier.clickable { clicks += 1 })
        node("over", 0f, 0f, 50f, 50f, Modifier.clickable { clicks += 10 })

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(10, clicks, "the later sibling is drawn last, so it is on top")
    }

    @Test
    fun `a node nobody can interact with is not in the way`() {
        val scenery = node("scenery", 0f, 0f, 100f, 100f)
        node("button", 0f, 0f, 20f, 20f, Modifier.clickable { clicks += 1 }, parent = scenery)

        press(5f, 5f)
        release(5f, 5f)

        assertEquals(1, clicks)
    }

    @Test
    fun `a clip stops the pointer reaching anything outside it`() {
        val panel = node("panel", 0f, 0f, 40f, 40f, Modifier.clip())
        // Placed so that half of it hangs out of the clipping parent.
        node("overflow", 20f, 20f, 40f, 40f, Modifier.clickable { clicks += 1 }, parent = panel)

        press(30f, 30f)
        release(30f, 30f)
        assertEquals(1, clicks, "inside the clip, so it is clickable")

        press(50f, 50f)
        release(50f, 50f)
        assertEquals(1, clicks, "outside the clip, so it is not drawn and cannot be hit")
    }

    @Test
    fun `something invisible cannot be clicked`() {
        node("ghost", 0f, 0f, 50f, 50f, Modifier.alpha(0f).clickable { clicks += 1 })

        assertFalse(press(10f, 10f), "nothing under the pointer, so nothing consumed it")
        release(10f, 10f)
        assertEquals(0, clicks)
    }

    // --- press, capture and the click ---------------------------------------------------------

    @Test
    fun `a press and a release on the node is a click`() {
        val state = InteractionState()
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        assertTrue(press(10f, 10f))
        assertTrue(state.isPressed)
        assertTrue(release(10f, 10f))

        assertEquals(1, clicks)
        assertFalse(state.isPressed)
    }

    @Test
    fun `dragging off the button and back presses, un-presses and presses again`() {
        val state = InteractionState()
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

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
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        press(10f, 10f)
        move(80f, 80f, setOf(PointerButton.Primary))
        release(80f, 80f)

        assertEquals(0, clicks)
        assertFalse(state.isPressed)
    }

    @Test
    fun `a cancelled gesture does not fire a click`() {
        val state = InteractionState()
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

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
        node("pad", 0f, 0f, 50f, 50f, Modifier.onPointer(handler))
        node("elsewhere", 100f, 100f, 50f, 50f, Modifier.clickable { clicks += 1 })

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
        node("behind", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        node("greyed", 0f, 0f, 50f, 50f, Modifier.clickable(enabled = false) { clicks += 100 })

        assertTrue(press(10f, 10f))
        release(10f, 10f)

        assertEquals(0, clicks, "a click cannot fall through a disabled button to what is under it")
    }

    @Test
    fun `a handler that declines lets the event through to what is behind`() {
        val declining = PointerHandler { false }
        node("behind", 0f, 0f, 100f, 100f, Modifier.clickable { clicks += 1 })
        node("glass", 0f, 0f, 50f, 50f, Modifier.onPointer(declining))

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(1, clicks)
    }

    // --- hover --------------------------------------------------------------------------------

    @Test
    fun `hover follows the pointer, and a node and its ancestors are hovered together`() {
        val panel = InteractionState()
        val button = InteractionState()
        val outer = node("panel", 0f, 0f, 100f, 100f, Modifier.interaction(panel))
        node("button", 10f, 10f, 20f, 20f, Modifier.interaction(button), parent = outer)

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
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

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
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

        move(10f, 10f)
        press(10f, 10f)
        release(10f, 10f)

        assertTrue(state.isHovered, "the pointer is still over it")
        assertFalse(state.isPressed)
    }

    @Test
    fun `cancelling everything lets go of hover and press alike`() {
        val state = InteractionState()
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

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
        val outer = node("list", 0f, 0f, 100f, 100f, Modifier.onPointer { seen += it; true })
        node("row", 0f, 0f, 100f, 20f, Modifier.interaction(InteractionState()), parent = outer)

        router.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(10f, 10f), Offset(0f, -3f)))

        assertEquals(1, seen.size, "the row watches but does not handle, so the list gets it")
        press(10f, 10f)
        assertEquals(2, seen.size, "and the scroll captured nothing, so the press is a fresh hit")
    }

    @Test
    fun `two fingers on the same node keep it pressed until both let go`() {
        val state = InteractionState()
        node("button", 0f, 0f, 50f, 50f, Modifier.interaction(state).clickable { clicks += 1 })

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
