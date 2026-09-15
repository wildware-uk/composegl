package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.DefaultDragSlop
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.TestTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A press that becomes a drag, and everything a hand-rolled one gets slightly wrong: the slop, the
 * capture, the cancel, a node that moves under the pointer, and a button inside a draggable panel.
 *
 * Rectangles where this test put them, and events written by hand. Common, so it runs on a JVM and
 * on Linux native alike.
 */
class DraggableTest {

    private val screen = TestTree()
    private val router = PointerRouter(screen.root)

    /** Everything the callbacks were told, in order, so a test can assert about the sequence. */
    private val heard = mutableListOf<String>()
    private val deltas = mutableListOf<Offset>()
    private var clicks = 0

    init {
        screen.root.width = 400f
        screen.root.height = 400f
    }

    private fun press(x: Float, y: Float, button: PointerButton = PointerButton.Primary, id: Long = 0L) =
        router.onPointer(PointerEvent.Press(PointerId(id), Offset(x, y), button))

    private fun move(x: Float, y: Float, button: PointerButton = PointerButton.Primary, id: Long = 0L) =
        router.onPointer(PointerEvent.Move(PointerId(id), Offset(x, y), setOf(button)))

    private fun release(x: Float, y: Float, button: PointerButton = PointerButton.Primary, id: Long = 0L) =
        router.onPointer(PointerEvent.Release(PointerId(id), Offset(x, y), button))

    private fun cancel(x: Float, y: Float, id: Long = 0L) =
        router.onPointer(PointerEvent.Cancel(PointerId(id), Offset(x, y)))

    private fun dragging(tag: String = "") = Modifier.draggable(
        onDragStart = { heard += "start$tag $it" },
        onDragEnd = { heard += "end$tag" },
        onDragCancel = { heard += "cancel$tag" },
        onDrag = { deltas += it },
    )

    private fun total() = deltas.fold(Offset.Zero) { sum, it -> sum + it }

    // --- slop ---------------------------------------------------------------------------------

    @Test
    fun `a press that stays inside the slop is not a drag and is still a click`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging().clickable { clicks++ })

        press(50f, 50f)
        move(50f + DefaultDragSlop, 50f)
        release(50f + DefaultDragSlop, 50f)

        assertEquals(emptyList(), heard, "exactly the slop away is still a hand being unsteady")
        assertEquals(emptyList(), deltas)
        assertEquals(1, clicks)
    }

    @Test
    fun `past the slop it starts where the press was and the first delta covers the whole way`() {
        screen.box("card", 20f, 20f, 100f, 100f, dragging())

        press(30f, 40f)
        move(32f, 40f)
        assertEquals(emptyList(), heard, "two pixels is nothing yet")

        move(45f, 40f)
        assertEquals(listOf("start ${Offset(10f, 20f)}"), heard, "told where the press was, in the card's own coordinates")
        assertEquals(listOf(Offset(15f, 0f)), deltas, "so the card is not the slop behind the pointer")

        move(45f, 60f)
        assertEquals(Offset(0f, 20f), deltas.last())

        release(45f, 60f)
        assertEquals("end", heard.last())
        assertEquals(Offset(15f, 20f), total(), "every delta together is where the pointer went")
    }

    @Test
    fun `a drag is never a click even when let go where it started`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging().clickable { clicks++ })

        press(50f, 50f)
        move(80f, 50f)
        move(50f, 50f)
        release(50f, 50f)

        assertEquals(0, clicks)
        assertEquals(listOf("start ${Offset(50f, 50f)}", "end"), heard)
    }

    @Test
    fun `a slop of its own is honoured`() {
        screen.box("card", 0f, 0f, 100f, 100f, Modifier.draggable(slop = 30f, onDragStart = { heard += "start" }) {})

        press(10f, 10f)
        move(35f, 10f)
        assertEquals(emptyList(), heard, "25 is inside a slop of 30")

        move(41f, 10f)
        assertEquals(listOf("start"), heard)
    }

    @Test
    fun `a slop of zero drags on the first pixel`() {
        screen.box("card", 0f, 0f, 100f, 100f, Modifier.draggable(slop = 0f) { deltas += it })

        press(10f, 10f)
        move(11f, 10f)

        assertEquals(listOf(Offset(1f, 0f)), deltas)
    }

    @Test
    fun `a negative slop is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.draggable(slop = -1f) {} }
    }

    // --- capture ------------------------------------------------------------------------------

    @Test
    fun `the drag carries on outside the node and outside the window`() {
        screen.box("card", 0f, 0f, 50f, 50f, dragging())
        screen.box("other", 100f, 0f, 50f, 50f, Modifier.draggable(onDragStart = { heard += "other" }) {})

        press(10f, 10f)
        move(120f, 10f)
        move(-500f, 900f)
        release(-500f, 900f)

        assertEquals(listOf("start ${Offset(10f, 10f)}", "end"), heard, "the node under the pointer never got a look in")
        assertEquals(Offset(-510f, 890f), total())
    }

    @Test
    fun `a release somewhere new is followed before the drag ends`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging())

        press(10f, 10f)
        move(30f, 10f)
        release(60f, 10f)

        assertEquals(Offset(50f, 0f), total(), "the last 30 pixels only arrived with the release")
        assertEquals("end", heard.last())
    }

    @Test
    fun `a node that follows the pointer still moves as far as the pointer`() {
        lateinit var window: UiNode
        window = screen.box(
            "window", 100f, 100f, 80f, 60f,
            Modifier.draggable {
                deltas += it
                window.x += it.x
                window.y += it.y
            },
        )

        press(110f, 110f)
        move(130f, 110f)
        move(160f, 140f)
        move(170f, 150f)
        release(170f, 150f)

        assertEquals(Offset(60f, 40f), total(), "the window moving itself did not cancel the pointer out")
        assertEquals(160f, window.x)
        assertEquals(140f, window.y)
    }

    @Test
    fun `a node drawn at half size reports a drag in its own units`() {
        screen.box("map", 0f, 0f, 200f, 200f, Modifier.scale(0.5f, Alignment.TopStart).draggable { deltas += it })

        press(10f, 10f)
        move(30f, 10f)

        assertEquals(Offset(40f, 0f), total(), "20 screen pixels across a half-size map is 40 of its own")
    }

    @Test
    fun `slop is measured in the node's own units too`() {
        screen.box("map", 0f, 0f, 200f, 200f, Modifier.scale(0.5f, Alignment.TopStart).draggable { deltas += it })

        press(10f, 10f)
        move(15f, 10f)

        assertEquals(Offset(10f, 0f), total(), "5 screen pixels is 10 of its own, which is past a slop of 8")
    }

    // --- cancel -------------------------------------------------------------------------------

    @Test
    fun `a cancelled drag is told it was cancelled and not that it ended`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging().clickable { clicks++ })

        press(10f, 10f)
        move(40f, 10f)
        cancel(40f, 10f)

        assertEquals(listOf("start ${Offset(10f, 10f)}", "cancel"), heard)
        assertEquals(0, clicks)

        move(80f, 10f)
        release(80f, 10f)
        assertEquals(2, heard.size, "and the gesture is over, so nothing after it reaches the drag")
    }

    @Test
    fun `cancel defaults to end`() {
        screen.box("card", 0f, 0f, 100f, 100f, Modifier.draggable(onDragEnd = { heard += "end" }) {})

        press(10f, 10f)
        move(40f, 10f)
        cancel(40f, 10f)

        assertEquals(listOf("end"), heard)
    }

    @Test
    fun `a press that is cancelled before it became a drag tells the drag nothing`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging())

        press(10f, 10f)
        move(12f, 10f)
        cancel(12f, 10f)

        assertEquals(emptyList(), heard)
    }

    @Test
    fun `losing the window cancels a drag`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging())

        press(10f, 10f)
        move(40f, 10f)
        router.cancelAll()

        assertEquals("cancel", heard.last())
    }

    @Test
    fun `turning it off mid-drag cancels the drag`() {
        val card = screen.box("card", 0f, 0f, 100f, 100f, dragging())

        press(10f, 10f)
        move(40f, 10f)
        card.modifier = Modifier.draggable(enabled = false) { deltas += Offset(-1f, -1f) }
        move(60f, 10f)

        assertEquals("cancel", heard.last())
        assertEquals(listOf(Offset(30f, 0f)), deltas, "and nothing moved after it was turned off")
    }

    @Test
    fun `a drag turned off and on again is over and is neither a new drag nor a click`() {
        val panel = screen.box("panel", 0f, 0f, 300f, 300f, dragging("-panel"))
        val card = screen.box("card", 0f, 0f, 100f, 100f, dragging().clickable { clicks++ }, parent = panel)

        press(10f, 10f)
        move(40f, 10f)
        card.modifier = Modifier.draggable(enabled = false) {}.clickable { clicks++ }
        move(60f, 10f)
        assertEquals(listOf("start ${Offset(10f, 10f)}", "cancel"), heard, "the panel did not get the gesture")

        card.modifier = dragging().clickable { clicks++ }
        move(90f, 10f)
        release(20f, 10f)

        assertEquals(listOf("start ${Offset(10f, 10f)}", "cancel"), heard, "no second start, no end")
        assertEquals(listOf(Offset(30f, 0f)), deltas)
        assertEquals(0, clicks, "let go on the card, but it was a drag")
    }

    @Test
    fun `a node taken off the screen mid-drag cancels the drag`() {
        val panel = screen.box("panel", 0f, 0f, 300f, 300f)
        screen.box("card", 0f, 0f, 100f, 100f, dragging(), parent = panel)

        press(10f, 10f)
        move(40f, 10f)
        screen.remove("panel")
        move(60f, 10f)
        release(60f, 10f)

        assertEquals(listOf("start ${Offset(10f, 10f)}", "cancel"), heard, "cancelled, and never ended")
        assertEquals(listOf(Offset(30f, 0f)), deltas, "nothing after it went")
    }

    @Test
    fun `a new modifier mid-drag carries the drag on with its own callbacks`() {
        val card = screen.box("card", 0f, 0f, 100f, 100f, dragging("-old"))

        press(10f, 10f)
        move(40f, 10f)
        // What a recomposition does to a node whose onDrag moved the state it is composed from.
        card.modifier = dragging("-new")
        move(60f, 10f)
        release(60f, 10f)

        assertEquals(listOf("start-old ${Offset(10f, 10f)}", "end-new"), heard, "one drag, never restarted")
        assertEquals(listOf(Offset(30f, 0f), Offset(20f, 0f)), deltas)
    }

    // --- what takes the press -----------------------------------------------------------------

    @Test
    fun `a disabled draggable does not take the press`() {
        screen.box("under", 0f, 0f, 100f, 100f, Modifier.clickable { clicks++ })
        screen.box("over", 0f, 0f, 100f, 100f, Modifier.draggable(enabled = false) { deltas += it })

        press(10f, 10f)
        move(50f, 10f)
        release(12f, 10f)

        assertEquals(emptyList(), deltas)
        assertEquals(1, clicks, "the press fell through to the node underneath, which was clicked")
        assertNull(Modifier.draggable(enabled = false) {}.resolve().drag, "disabled resolves to absent")
    }

    @Test
    fun `the secondary button does not drag`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging())

        assertFalse(press(10f, 10f, PointerButton.Secondary), "a right click on a draggable is not taken")
        move(60f, 10f, PointerButton.Secondary)

        assertEquals(emptyList(), heard)
    }

    @Test
    fun `a second button during a drag does not end it`() {
        screen.box("card", 0f, 0f, 100f, 100f, dragging())

        press(10f, 10f)
        move(40f, 10f)
        press(40f, 10f, PointerButton.Secondary)
        release(40f, 10f, PointerButton.Secondary)
        move(50f, 10f)

        assertEquals(listOf("start ${Offset(10f, 10f)}"), heard)
        assertEquals(Offset(40f, 0f), total())
    }

    @Test
    fun `two fingers drag two things at once`() {
        screen.box("left", 0f, 0f, 100f, 100f, dragging("-left"))
        screen.box("right", 200f, 0f, 100f, 100f, dragging("-right"))

        press(10f, 10f, id = 1)
        press(210f, 10f, id = 2)
        move(10f, 50f, id = 1)
        move(250f, 10f, id = 2)
        release(10f, 50f, id = 1)
        cancel(250f, 10f, id = 2)

        assertEquals(
            listOf("start-left ${Offset(10f, 10f)}", "start-right ${Offset(10f, 10f)}", "end-left", "cancel-right"),
            heard,
        )
    }

    // --- things inside a draggable ------------------------------------------------------------

    @Test
    fun `a button inside a draggable panel is still clicked by a steady press`() {
        val panel = screen.box("panel", 0f, 0f, 200f, 200f, dragging())
        screen.box("button", 20f, 20f, 60f, 30f, Modifier.clickable { clicks++ }, parent = panel)

        press(30f, 30f)
        move(33f, 31f)
        release(33f, 31f)

        assertEquals(1, clicks)
        assertEquals(emptyList(), heard)
    }

    @Test
    fun `dragging from a button inside a draggable panel hands the gesture to the panel`() {
        val buttonState = InteractionState()
        val panelState = InteractionState()
        val told = mutableListOf<String>()
        val panel = screen.box("panel", 0f, 0f, 200f, 200f, Modifier.interaction(panelState).then(dragging()))
        screen.box(
            "button", 20f, 20f, 60f, 30f,
            Modifier.interaction(buttonState).clickable { clicks++ }.onPointer {
                told += it::class.simpleName.orEmpty()
                false
            },
            parent = panel,
        )

        press(30f, 30f)
        assertTrue(buttonState.isPressed)

        move(60f, 30f)
        assertFalse(buttonState.isPressed, "the button let go the moment the panel took over")
        assertTrue(panelState.isPressed, "and the panel is the one being held")
        assertEquals("Cancel", told.last(), "the button was told, so a handler of its own lets go too")
        assertEquals(listOf("start ${Offset(30f, 30f)}"), heard, "in the panel's coordinates")
        assertEquals(Offset(30f, 0f), total())

        release(35f, 35f)
        assertEquals(0, clicks, "released back over the button, and still not a click")
        assertEquals("end", heard.last())
    }

    @Test
    fun `a child using the moves itself keeps them`() {
        val panel = screen.box("panel", 0f, 0f, 200f, 200f, dragging())
        val slid = mutableListOf<Offset>()
        screen.box(
            "slider", 20f, 20f, 100f, 20f,
            Modifier.onPointer { event ->
                when (event) {
                    is PointerEvent.Press -> true
                    is PointerEvent.Move -> { slid += event.position; true }
                    else -> false
                }
            },
            parent = panel,
        )

        press(30f, 30f)
        move(90f, 30f)
        move(150f, 30f)
        release(150f, 30f)

        assertEquals(emptyList(), heard, "the slider was using the pointer, so the panel never moved")
        assertEquals(2, slid.size)
    }

    @Test
    fun `a draggable child is dragged rather than its draggable parent`() {
        val panel = screen.box("panel", 0f, 0f, 200f, 200f, dragging("-panel"))
        screen.box("item", 20f, 20f, 40f, 40f, dragging("-item"), parent = panel)

        press(30f, 30f)
        move(90f, 30f)
        release(90f, 30f)

        assertEquals(listOf("start-item ${Offset(10f, 10f)}", "end-item"), heard)
    }
}
