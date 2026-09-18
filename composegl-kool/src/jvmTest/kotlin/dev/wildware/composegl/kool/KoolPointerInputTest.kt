package dev.wildware.composegl.kool

import de.fabmax.kool.input.PointerInput
import dev.wildware.composegl.kool.KoolPointerInput.Sample
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kool's once-a-frame pointer state, read back as the toolkit's events. No window: a frame of pointers
 * is written out as the values Kool reports.
 */
class KoolPointerInputTest {

    private val events = mutableListOf<PointerEvent>()

    /** Whether the sink uses an event; every one, unless a test says otherwise. */
    private var uses: (PointerEvent) -> Boolean = { true }

    private val sink = object : InputSink {
        override fun onPointer(event: PointerEvent): Boolean {
            events += event
            return uses(event)
        }

        override fun onKey(event: KeyEvent) = false

        override fun onText(event: TextEvent) = false

        override fun onGamepad(event: GamepadEvent) = false
    }

    /** A 400 by 300 design shown twice its size, so a framebuffer pixel is half a design unit. */
    private val input = KoolPointerInput(
        sink,
        viewport = { Viewport(Size(400f, 300f), Size(800f, 600f), ScalePolicy.Fit) },
        clock = { 1234L },
    )

    private val mouse = PointerInput.MOUSE_POINTER_ID
    private val left = PointerInput.LEFT_BUTTON_MASK

    private fun describe() = events.map { event ->
        when (event) {
            is PointerEvent.Move -> "move ${event.position} ${event.pressed}"
            is PointerEvent.Press -> "press ${event.button} ${event.position}"
            is PointerEvent.Release -> "release ${event.button} ${event.position}"
            is PointerEvent.Scroll -> "scroll ${event.delta}"
            is PointerEvent.Exit -> "exit"
            is PointerEvent.Cancel -> "cancel"
        }
    }.also { events.clear() }

    @Test
    fun `a mouse seen for the first time moves to where it is in design units`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, buttons = 0, changed = 0)))
        val event = events.single() as PointerEvent.Move
        assertEquals(Offset(100f, 50f), event.position)
        assertEquals(PointerId.Mouse, event.pointerId)
        assertEquals(PointerType.Mouse, event.type)
        assertEquals(1234L, event.timeMillis)
    }

    @Test
    fun `a pointer that did not move and changed nothing says nothing`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)))
        events.clear()
        input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)))
        assertEquals(emptyList<String>(), describe())
    }

    @Test
    fun `a press a drag and a release across frames`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)))
        input.onFrame(listOf(Sample(mouse, 200f, 100f, buttons = left, changed = left)))
        input.onFrame(listOf(Sample(mouse, 220f, 100f, buttons = left, changed = 0)))
        input.onFrame(listOf(Sample(mouse, 220f, 100f, buttons = 0, changed = left)))
        assertEquals(
            listOf(
                "move Offset(x=100.0, y=50.0) []",
                "press Primary Offset(x=100.0, y=50.0)",
                "move Offset(x=110.0, y=50.0) [Primary]",
                "release Primary Offset(x=110.0, y=50.0)",
            ),
            describe(),
        )
    }

    @Test
    fun `a press and a release inside one frame is still a click`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)))
        events.clear()
        // Kool leaves the button up and marks it changed.
        input.onFrame(listOf(Sample(mouse, 200f, 100f, buttons = 0, changed = left)))
        assertEquals(listOf("press Primary Offset(x=100.0, y=50.0)", "release Primary Offset(x=100.0, y=50.0)"), describe())
    }

    @Test
    fun `right and middle buttons are the toolkit's secondary and tertiary`() {
        val right = PointerInput.RIGHT_BUTTON_MASK
        val middle = PointerInput.MIDDLE_BUTTON_MASK
        input.onFrame(listOf(Sample(mouse, 0f, 0f, buttons = right or middle, changed = right or middle)))
        assertEquals(listOf("move Offset(x=0.0, y=0.0) []", "press Secondary Offset(x=0.0, y=0.0)", "press Tertiary Offset(x=0.0, y=0.0)"), describe())
    }

    @Test
    fun `the wheel is turned round into which way the content moves`() {
        input.onFrame(listOf(Sample(mouse, 0f, 0f, 0, 0, scrollX = 0f, scrollY = 2f)))
        assertEquals(listOf("move Offset(x=0.0, y=0.0) []", "scroll Offset(x=0.0, y=-2.0)"), describe())
    }

    @Test
    fun `a mouse that leaves ends hover and abandons a drag without a release`() {
        input.onFrame(listOf(Sample(mouse, 0f, 0f, 0, 0)))
        input.onFrame(emptyList())
        assertEquals(listOf("move Offset(x=0.0, y=0.0) []", "exit"), describe())

        input.onFrame(listOf(Sample(mouse, 0f, 0f, left, left)))
        events.clear()
        input.onFrame(emptyList())
        assertEquals(listOf("cancel", "exit"), describe())
    }

    @Test
    fun `a finger is a touch pointer of its own and lifting it is a release`() {
        input.onFrame(listOf(Sample(mouse, 0f, 0f, 0, 0), Sample(0, 40f, 40f, left, left)))
        val press = events.filterIsInstance<PointerEvent.Press>().single()
        assertEquals(PointerId(1L), press.pointerId)
        assertEquals(PointerType.Touch, press.type)
        events.clear()
        input.onFrame(listOf(Sample(mouse, 0f, 0f, 0, 0)))
        assertEquals(listOf("release Primary Offset(x=20.0, y=20.0)"), describe())
    }

    @Test
    fun `each pointer is reported with whether the interface used it and the frame it was read in`() {
        // The interface covers the left half of the design: anything at x under 200 is used.
        uses = { it.position.x < 200f }
        val report = input.onFrame(listOf(Sample(mouse, 600f, 100f, left, left), Sample(3, 40f, 40f, left, left)), frame = 7)
        assertEquals(listOf(PointerUse(mouse, 7, false), PointerUse(3, 7, true)), report)
    }

    @Test
    fun `a pointer that did nothing is reported as not used`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)), frame = 1)
        assertEquals(listOf(PointerUse(mouse, 2, false)), input.onFrame(listOf(Sample(mouse, 200f, 100f, 0, 0)), frame = 2))
    }

    @Test
    fun `a finger that lifted is reported in the frame it went`() {
        input.onFrame(listOf(Sample(5, 40f, 40f, left, left)), frame = 1)
        assertEquals(listOf(PointerUse(5, 2, true)), input.onFrame(emptyList(), frame = 2))
        uses = { false }
        input.onFrame(listOf(Sample(5, 40f, 40f, left, left)), frame = 3)
        assertEquals(listOf(PointerUse(5, 4, false)), input.onFrame(emptyList(), frame = 4))
    }

    @Test
    fun `a mouse coming back with a stale changed bit does not click`() {
        input.onFrame(listOf(Sample(mouse, 0f, 0f, left, left)))
        input.onFrame(emptyList())
        events.clear()
        // Kool's reused slot still says the left button changed, though it was let go outside.
        input.onFrame(listOf(Sample(mouse, 200f, 100f, buttons = 0, changed = left)))
        assertEquals(listOf("move Offset(x=100.0, y=50.0) []"), describe())
    }

    @Test
    fun `a pointer that arrives with a button down is pressed once`() {
        input.onFrame(listOf(Sample(mouse, 200f, 100f, buttons = left, changed = left)))
        assertEquals(listOf("move Offset(x=100.0, y=50.0) []", "press Primary Offset(x=100.0, y=50.0)"), describe())
    }
}
