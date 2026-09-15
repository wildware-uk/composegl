package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rules [InputRouter] hands a window's devices out by, checked against sinks that only write
 * down what reached them.
 *
 * The screens behind a router are in `SplitScreenUiTest`. This is the bookkeeping: which player an
 * event goes to, in whose coordinates, and what a player is told when a device is taken away.
 */
class InputRouterTest {

    /** Writes down every event, and takes all of them. */
    private class Player(val name: String) : InputSink {
        val events = mutableListOf<Any>()
        override fun onPointer(event: PointerEvent) = events.add(event)
        override fun onKey(event: KeyEvent) = events.add(event)
        override fun onText(event: TextEvent) = events.add(event)
        override fun onGamepad(event: GamepadEvent) = events.add(event)
        override fun toString() = name
    }

    private val one = Player("one")
    private val two = Player("two")
    private val lobby = Player("lobby")

    private val padZero = GamepadId(0)
    private val padOne = GamepadId(1)

    /** A 400x300 design in each half of an 800x300 window: player two's origin is 400 across. */
    private val halves = Viewport.splitScreen(Size(400f, 300f), Size(800f, 300f), players = 2)

    private fun router(unclaimed: InputSink? = lobby) = InputRouter(unclaimed).apply {
        assignGamepad(padZero, one)
        assignGamepad(padOne, two)
        assignKeyboard(one)
        assignPointer(halves[0], one)
        assignPointer(halves[1], two)
    }

    private fun move(x: Float, y: Float) = PointerEvent.Move(PointerId.Mouse, Offset(x, y))
    private fun press(x: Float, y: Float) = PointerEvent.Press(PointerId.Mouse, Offset(x, y))
    private fun release(x: Float, y: Float) = PointerEvent.Release(PointerId.Mouse, Offset(x, y))

    // --- pads ------------------------------------------------------------------------------------

    @Test
    fun `each pad goes to the player it was given to and nobody else`() {
        val router = router()

        router.onGamepad(GamepadEvent.ButtonDown(padOne, GamepadButton.South))
        router.onGamepad(GamepadEvent.ButtonDown(padZero, GamepadButton.DpadDown))

        assertEquals(listOf<Any>(GamepadEvent.ButtonDown(padZero, GamepadButton.DpadDown)), one.events)
        assertEquals(listOf<Any>(GamepadEvent.ButtonDown(padOne, GamepadButton.South)), two.events)
        assertTrue(lobby.events.isEmpty())
    }

    @Test
    fun `a pad nobody holds goes to unclaimed or is refused without one`() {
        val press = GamepadEvent.ButtonDown(GamepadId(2), GamepadButton.Start)

        assertTrue(router().onGamepad(press))
        assertEquals(listOf<Any>(press), lobby.events)

        assertFalse(router(unclaimed = null).onGamepad(press))
    }

    @Test
    fun `a pad changing hands is let go of by its old owner first`() {
        val router = router()

        router.assignGamepad(padZero, two)
        router.onGamepad(GamepadEvent.ButtonDown(padZero, GamepadButton.South))

        assertEquals(listOf<Any>(GamepadEvent.Disconnected(padZero)), one.events)
        assertEquals(listOf<Any>(GamepadEvent.ButtonDown(padZero, GamepadButton.South)), two.events)
        assertEquals(listOf(padZero, padOne), router.gamepadsOf(two))
    }

    @Test
    fun `giving a pad to the player who already has it tells nobody anything`() {
        val router = router()

        router.assignGamepad(padZero, one)

        assertTrue(one.events.isEmpty())
    }

    @Test
    fun `an unassigned pad lets go and goes back to unclaimed`() {
        val router = router()

        router.unassignGamepad(padOne)
        router.onGamepad(GamepadEvent.ButtonDown(padOne, GamepadButton.Start))

        assertEquals(listOf<Any>(GamepadEvent.Disconnected(padOne)), two.events)
        assertEquals(listOf<Any>(GamepadEvent.ButtonDown(padOne, GamepadButton.Start)), lobby.events)
        assertNull(router.ownerOf(padOne))
        assertSame(one, router.ownerOf(padZero))
    }

    @Test
    fun `a pad unplugged by the player keeps its owner for when it comes back`() {
        val router = router()

        router.onGamepad(GamepadEvent.Disconnected(padOne))
        router.onGamepad(GamepadEvent.Connected(padOne))

        assertEquals(listOf<Any>(GamepadEvent.Disconnected(padOne), GamepadEvent.Connected(padOne)), two.events)
        assertSame(two, router.ownerOf(padOne))
    }

    // --- the keyboard ----------------------------------------------------------------------------

    @Test
    fun `keys and text go to the keyboard player only`() {
        val router = router()
        val key = KeyEvent(Key.A, KeyEventType.Down)

        router.onKey(key)
        router.onText(TextEvent("a"))

        assertEquals(listOf(key, TextEvent("a")), one.events)
        assertTrue(two.events.isEmpty())
    }

    @Test
    fun `keys with no keyboard player go to unclaimed`() {
        val router = router()
        router.assignKeyboard(null)

        router.onKey(KeyEvent(Key.Enter, KeyEventType.Down))

        assertTrue(one.events.isEmpty())
        assertEquals(1, lobby.events.size)
        assertFalse(router(unclaimed = null).apply { assignKeyboard(null) }.onText(TextEvent("a")))
    }

    // --- the pointer -----------------------------------------------------------------------------

    @Test
    fun `a pointer goes to the half it is in and arrives in that player's coordinates`() {
        val router = router()

        router.onPointer(press(500f, 120f))

        assertTrue(one.events.isEmpty())
        // 500 across the window is 100 into player two's half.
        assertEquals(listOf<Any>(press(100f, 120f)), two.events)
    }

    @Test
    fun `positions are scaled into the design when a half is bigger than it`() {
        val big = Viewport.splitScreen(Size(400f, 300f), Size(1600f, 600f), players = 2)
        val router = InputRouter().apply {
            assignPointer(big[0], one)
            assignPointer(big[1], two)
        }

        router.onPointer(move(1200f, 300f))

        assertEquals(listOf<Any>(move(200f, 150f)), two.events)
    }

    @Test
    fun `moving from one half into the other ends the hover in the first`() {
        val router = router()

        router.onPointer(move(100f, 100f))
        router.onPointer(move(700f, 100f))

        assertEquals(listOf<Any>(move(100f, 100f), PointerEvent.Exit(PointerId.Mouse, Offset(700f, 100f))), one.events)
        assertEquals(listOf<Any>(move(300f, 100f)), two.events)
    }

    @Test
    fun `a press keeps the pointer until release even across the divider`() {
        val router = router()

        router.onPointer(press(380f, 100f))
        router.onPointer(move(600f, 100f))
        router.onPointer(release(600f, 100f))

        // Still in player one's coordinates, off their right-hand edge.
        assertEquals(listOf<Any>(press(380f, 100f), move(600f, 100f), release(600f, 100f)), one.events)
        assertTrue(two.events.isEmpty(), "player two heard nothing of a drag they did not start")
    }

    @Test
    fun `after a drag across the divider the next move hovers the other half`() {
        val router = router()
        router.onPointer(press(380f, 100f))
        router.onPointer(move(600f, 100f))
        router.onPointer(release(600f, 100f))

        router.onPointer(move(610f, 100f))

        assertEquals(PointerEvent.Exit(PointerId.Mouse, Offset(610f, 100f)), one.events.last())
        assertEquals(listOf<Any>(move(210f, 100f)), two.events)
    }

    @Test
    fun `a pointer outside every area goes to unclaimed in window coordinates`() {
        val router = InputRouter(lobby).apply { assignPointer(halves[0], one) }

        router.onPointer(move(100f, 100f))
        router.onPointer(press(500f, 100f))

        assertEquals(listOf<Any>(move(100f, 100f), PointerEvent.Exit(PointerId.Mouse, Offset(500f, 100f))), one.events)
        assertEquals(listOf<Any>(press(500f, 100f)), lobby.events)
        assertFalse(InputRouter().onPointer(press(1f, 1f)))
    }

    @Test
    fun `leaving the window ends the hover of whoever had it`() {
        val router = router()
        router.onPointer(move(700f, 100f))

        router.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(900f, 100f)))

        assertEquals(PointerEvent.Exit(PointerId.Mouse, Offset(500f, 100f)), two.events.last())
        assertTrue(one.events.isEmpty())
    }

    @Test
    fun `a scroll goes to the half under it`() {
        val router = router()

        router.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(100f, 50f), Offset(0f, 1f)))

        assertEquals(listOf<Any>(PointerEvent.Scroll(PointerId.Mouse, Offset(100f, 50f), Offset(0f, 1f))), one.events)
    }

    @Test
    fun `two fingers in two halves are two players' presses at once`() {
        val router = router()
        val left = PointerEvent.Press(PointerId(1), Offset(100f, 100f), type = PointerType.Touch)
        val right = PointerEvent.Press(PointerId(2), Offset(500f, 100f), type = PointerType.Touch)

        router.onPointer(left)
        router.onPointer(right)
        router.onPointer(PointerEvent.Cancel(PointerId(2), Offset(500f, 100f), PointerType.Touch))

        assertEquals(listOf<Any>(left), one.events)
        assertEquals(listOf<Any>(right.copy(position = Offset(100f, 100f)), PointerEvent.Cancel(PointerId(2), Offset(100f, 100f), PointerType.Touch)), two.events)
    }

    @Test
    fun `reassigning a player's area cancels what they held under the old one`() {
        val router = router()
        router.onPointer(press(100f, 100f))

        // The window was resized: the same two players, new halves.
        val wider = Viewport.splitScreen(Size(400f, 300f), Size(1600f, 600f), players = 2)
        router.assignPointer(wider[0], one)
        router.onPointer(release(100f, 100f))

        assertEquals(PointerEvent.Cancel(PointerId.Mouse, Offset(200f, 150f)), one.events[1])
        assertEquals(PointerEvent.Exit(PointerId.Mouse, Offset(200f, 150f)), one.events[2])
        // The release is not held by anybody any more, so it goes by where it is: the new half.
        assertEquals(release(50f, 50f), one.events.last())
    }

    @Test
    fun `a player who leaves gives every device back and lets go of them`() {
        val router = router()
        router.onPointer(press(100f, 100f))

        router.unassign(one)
        router.onGamepad(GamepadEvent.ButtonDown(padZero, GamepadButton.South))
        router.onKey(KeyEvent(Key.A, KeyEventType.Down))
        router.onPointer(move(100f, 100f))

        assertEquals(
            listOf(
                press(100f, 100f),
                GamepadEvent.Disconnected(padZero),
                PointerEvent.Cancel(PointerId.Mouse, Offset(200f, 150f)),
                PointerEvent.Exit(PointerId.Mouse, Offset(200f, 150f)),
            ),
            one.events,
        )
        assertEquals(3, lobby.events.size, "the pad, the key and the pointer all belong to nobody now")
    }
}
