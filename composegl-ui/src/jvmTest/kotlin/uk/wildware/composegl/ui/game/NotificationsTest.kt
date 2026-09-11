package uk.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.padding
import uk.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The queue of things the game is telling the player about.
 *
 * The issue's three: a burst of twenty does not fill the screen, dismissing one early lets the
 * next in, and an empty queue costs nothing.
 */
class NotificationsTest {

    private val host = UiHost()
    private val bounds = Rect(0f, 0f, 400f, 400f)
    private val canvas = RecordingCanvas(bounds)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    /** A state change takes two frames to start an effect. */
    private fun begin() = repeat(2) { frame(0) }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(queue: NotificationQueue, content: @Composable () -> Unit = { Notifications(queue) }) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) { content() }
        }
        repeat(2) { frame() }
    }

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

    /** Long enough for one to arrive, hold and leave. */
    private fun runOut(queue: NotificationQueue) = frames(queue.holdMillis / 20 + 30, millis = 20)

    // --- the issue's three ----------------------------------------------------------------------

    @Test
    fun `a burst of twenty does not fill the screen`() {
        val queue = NotificationQueue(capacity = 3, clock = Clock.Ui)
        show(queue)

        repeat(20) { queue.show("pickup $it") }
        frames(4)

        assertEquals(3, queue.shown.size, "three at a time is three at a time")
        assertEquals(17, queue.waiting)
        assertEquals(
            listOf("pickup 0", "pickup 1", "pickup 2", "and 17 more"),
            texts(),
            "the rest are counted rather than drawn",
        )
    }

    @Test
    fun `dismissing one early lets the next in`() {
        val queue = NotificationQueue(capacity = 1, clock = Clock.Ui)
        show(queue)

        queue.show("first")
        queue.show("second")
        frames(4)
        assertEquals(listOf("first", "and 1 more"), texts())

        queue.dismiss(queue.shown.first())
        frames(20)

        assertEquals(listOf("second"), texts(), "the queue should have moved on")
        assertEquals(0, queue.waiting)
    }

    @Test
    fun `an empty queue costs nothing`() {
        val queue = NotificationQueue(clock = Clock.Ui)
        show(queue)

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it redrew an empty queue")
        }
    }

    // --- how one behaves -----------------------------------------------------------------------

    @Test
    fun `one arrives holds and goes away by itself`() {
        val queue = NotificationQueue(holdMillis = 400, clock = Clock.Ui)
        show(queue)

        queue.show("Quest updated", "Find the relay")
        frames(4)
        assertEquals(listOf("Quest updated", "Find the relay"), texts(), "both lines are drawn")

        // Half way through the hold it is still up.
        frames(10, millis = 20)
        assertEquals(1, queue.shown.size)

        runOut(queue)
        assertTrue(queue.isIdle, "it was still there long after its hold ran out")
        assertTrue(texts().isEmpty())
    }

    @Test
    fun `it slides in rather than appearing`() {
        val queue = NotificationQueue(clock = Clock.Ui)
        show(queue)

        queue.show("Relay online")
        // Far enough in to be drawn — it is still invisible on the first frame or two — and far
        // short of arriving.
        begin()
        frames(3, millis = 20)
        val arriving = canvas.calls.filterIsInstance<DrawCall.Text>().first().at.x

        frames(20, millis = 20)
        val settled = canvas.calls.filterIsInstance<DrawCall.Text>().first().at.x

        assertTrue(arriving > settled, "it should travel as it arrives: $arriving then $settled")
    }

    @Test
    fun `clicking one takes it away`() {
        val queue = NotificationQueue(holdMillis = 100_000, clock = Clock.Ui)
        show(queue)

        queue.show("Trophy")
        frames(20, millis = 20)
        val card = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect
        val middle = Offset((card.left + card.right) / 2f, (card.top + card.bottom) / 2f)

        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, middle))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, middle))
        frames(20, millis = 20)

        assertTrue(queue.isIdle, "a click on a notification should take it away")
    }

    @Test
    fun `the oldest waiting one is dropped rather than the newest`() {
        val queue = NotificationQueue(capacity = 1, backlog = 2, clock = Clock.Ui)
        show(queue)

        queue.show("on screen")
        repeat(4) { queue.show("waiting $it") }
        frames(4)

        assertEquals(2, queue.waiting, "the backlog holds two")

        queue.dismiss(queue.shown.first())
        frames(20)
        assertEquals(
            listOf("waiting 2", "and 1 more"),
            texts(),
            "the newest waiting ones are the ones kept",
        )
    }

    @Test
    fun `a queue on the world's clock holds behind a pause`() {
        val queue = NotificationQueue(holdMillis = 400, clock = Clock.World)
        show(queue)
        host.clocks.register(Clock.World)

        queue.show("Boss down")
        frames(4)
        host.clocks.stop(Clock.World)
        frames(60, millis = 20)

        assertEquals(1, queue.shown.size, "it ran out behind a pause menu")
    }

    @Test
    fun `clearing takes everything away at once`() {
        val queue = NotificationQueue(capacity = 2, clock = Clock.Ui)
        show(queue)
        repeat(6) { queue.show("$it") }
        frames(4)

        queue.clear()
        frames(4)

        assertTrue(queue.isIdle)
        assertTrue(texts().isEmpty())
    }

    @Test
    fun `a game can put it where it likes`() {
        val queue = NotificationQueue(clock = Clock.Ui)
        show(queue) { Notifications(queue, Modifier.padding(left = 40f, top = 24f)) }

        queue.show("Relay online")
        frames(20, millis = 20)

        val card = canvas.calls.filterIsInstance<DrawCall.Rectangle>().first().rect
        assertEquals(40f, card.left, 0.5f)
        assertEquals(24f, card.top, 0.5f)
    }
}
