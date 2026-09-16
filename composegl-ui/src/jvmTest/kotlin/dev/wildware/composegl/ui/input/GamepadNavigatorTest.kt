package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A pad driving a menu on its own.
 *
 * Time is a number this test hands over, so a four-second hold takes four lines and no waiting.
 */
class GamepadNavigatorTest {

    private val screen = TestTree()
    private val states = mutableListOf<InteractionState>()
    private var clicks = 0
    private var backs = 0

    /** [count] buttons side by side, each with the one state a widget would read. */
    private fun row(count: Int) {
        repeat(count) { index ->
            val state = InteractionState().also { states += it }
            screen.box(
                "$index",
                x = index * 50f,
                modifier = Modifier.interaction(state).focusable(state).clickable { clicks += 1 },
            )
        }
    }

    private val focus by lazy { FocusManager(screen.root) }
    private val pad by lazy { GamepadNavigator(focus, onBack = { backs += 1 }) }

    private fun stick(x: Float = 0f, y: Float = 0f) {
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, x))
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, y))
    }

    private fun button(button: GamepadButton, down: Boolean) = pad.onGamepad(
        if (down) GamepadEvent.ButtonDown(GamepadId.First, button)
        else GamepadEvent.ButtonUp(GamepadId.First, button),
    )

    private fun focused() = focus.focused?.name

    @Test
    fun `a stick at rest moves nothing`() {
        row(3)
        focus.refresh()

        stick(x = 0.3f, y = 0.2f)
        pad.frame(1_000L)

        assertEquals("0", focused(), "inside the dead zone is not a direction")
        assertNull(pad.direction)
    }

    @Test
    fun `a push moves once, waits, and then steps`() {
        row(5)
        focus.refresh()
        pad.frame(0L)

        stick(x = 1f)
        assertEquals("1", focused(), "a fresh direction moves at once; waiting for it feels like lag")

        pad.frame(200L)
        assertEquals("1", focused(), "still inside the first-repeat pause")

        pad.frame(400L)
        assertEquals("2", focused(), "the pause is over, so it steps")

        pad.frame(450L)
        assertEquals("2", focused(), "and then waits the shorter repeat")

        pad.frame(510L)
        assertEquals("3", focused())
    }

    @Test
    fun `letting go stops the repeat`() {
        row(5)
        focus.refresh()
        pad.frame(0L)

        stick(x = 1f)
        stick(x = 0f)
        assertNull(pad.direction)

        pad.frame(10_000L)
        assertEquals("1", focused(), "one move, from the push, and nothing since")
    }

    @Test
    fun `up and slightly right is up, not both`() {
        // A column, so a diagonal read would go nowhere and a correct one goes up.
        screen.clear()
        screen.column("0", "1", "2", modifier = Modifier.focusable())
        focus.refresh()
        focus.focusOn(screen["2"])

        stick(x = 0.55f, y = -0.9f)
        assertEquals(FocusDirection.Up, pad.direction)
        assertEquals("1", focused())
    }

    @Test
    fun `the d-pad wins over a stick somebody is resting a thumb on`() {
        row(5)
        focus.refresh()
        pad.frame(0L)

        stick(x = 1f)
        assertEquals("1", focused())

        button(GamepadButton.DpadLeft, down = true)
        assertEquals(FocusDirection.Left, pad.direction)
        assertEquals("0", focused())
    }

    @Test
    fun `South holds the focused thing down and clicks it on the way up`() {
        row(3)
        focus.refresh()

        assertTrue(button(GamepadButton.South, down = true))
        assertTrue(states[0].isPressed, "a widget's pressed state means the same thing whatever drives it")
        assertEquals(0, clicks)

        assertTrue(button(GamepadButton.South, down = false))
        assertFalse(states[0].isPressed)
        assertEquals(1, clicks)
    }

    @Test
    fun `focus moving out from under a held button fires no click`() {
        row(3)
        focus.refresh()

        button(GamepadButton.South, down = true)
        pad.frame(0L)
        stick(x = 1f)
        assertEquals("1", focused())

        button(GamepadButton.South, down = false)
        assertEquals(0, clicks, "the button that was pressed is not the one that is focused now")
        assertFalse(states[0].isPressed)
    }

    @Test
    fun `East and Back leave the screen`() {
        row(3)
        focus.refresh()

        assertTrue(button(GamepadButton.East, down = true))
        assertTrue(button(GamepadButton.Back, down = true))

        assertEquals(2, backs)
        assertEquals(0, clicks)
    }

    @Test
    fun `unplugging the pad mid-press lets go of everything`() {
        row(5)
        focus.refresh()
        pad.frame(0L)

        stick(x = 1f)
        button(GamepadButton.South, down = true)
        assertTrue(states[1].isPressed)

        pad.onGamepad(GamepadEvent.Disconnected(GamepadId.First))

        assertNull(pad.direction, "no direction left repeating forever")
        assertFalse(states[1].isPressed)

        pad.frame(10_000L)
        assertEquals("1", focused(), "and nothing moved after the cable came out")
        assertEquals(0, clicks)
    }

    @Test
    fun `a pad can drive a menu nobody has touched`() {
        row(3)
        // No refresh, so nothing has focus yet: the pad is the first thing to happen.
        pad.frame(0L)
        stick(y = 1f)

        assertEquals("0", focused(), "the first press selects rather than doing nothing")
    }

    @Test
    fun `the focused node hears a pad button before the navigator does`() {
        val heard = mutableListOf<GamepadEvent>()
        val taker = GamepadHandler { event ->
            heard += event
            event is GamepadEvent.ButtonDown || event is GamepadEvent.ButtonUp
        }
        val state = InteractionState().also { states += it }
        screen.box("0", modifier = Modifier.interaction(state).focusable(state).clickable { clicks += 1 }.onGamepadEvent(taker))
        val other = InteractionState()
        screen.box("1", x = 50f, modifier = Modifier.interaction(other).focusable(other).clickable { clicks += 1 })
        focus.refresh()

        assertTrue(button(GamepadButton.South, down = true))
        assertTrue(button(GamepadButton.South, down = false))
        assertTrue(button(GamepadButton.DpadRight, down = true))
        button(GamepadButton.East, down = true)

        assertEquals(0, clicks, "South was the node's, so nothing was pressed")
        assertEquals("0", focused(), "the d-pad was the node's, so focus stayed")
        assertEquals(0, backs, "East was the node's, so nothing went back")
        assertEquals(4, heard.size)
    }

    @Test
    fun `a node that takes the stick coming back to centre still stops the repeat`() {
        // A push the navigator heard moves focus onto a node that takes every stick event, a camera
        // say. The stick coming back is that node's to take, and the navigator must still let go.
        val heard = mutableListOf<GamepadEvent>()
        val first = InteractionState().also { states += it }
        screen.box("0", modifier = Modifier.interaction(first).focusable(first))
        val camera = InteractionState().also { states += it }
        screen.box(
            "1",
            x = 50f,
            modifier = Modifier.interaction(camera).focusable(camera)
                .onGamepadEvent(GamepadHandler { event -> heard += event; event is GamepadEvent.Axis }),
        )
        val last = InteractionState().also { states += it }
        screen.box("2", x = 100f, modifier = Modifier.interaction(last).focusable(last))
        focus.refresh()
        pad.frame(0L)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 1f))
        assertEquals("1", focused())

        assertTrue(pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 0f)), "the node took it")
        assertEquals(listOf<GamepadEvent>(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 0f)), heard)
        assertNull(pad.direction, "the stick is back, whoever took the news")

        pad.frame(10_000L)
        assertEquals("1", focused(), "focus stays where the push put it")
    }

    @Test
    fun `a node that takes a push past the dead zone keeps it from the navigator`() {
        val state = InteractionState().also { states += it }
        screen.box("0", modifier = Modifier.interaction(state).focusable(state).onGamepadEvent(GamepadHandler { it is GamepadEvent.Axis }))
        val other = InteractionState().also { states += it }
        screen.box("1", x = 50f, modifier = Modifier.interaction(other).focusable(other))
        focus.refresh()

        stick(x = 1f)
        pad.frame(10_000L)

        assertEquals("0", focused(), "the push was the node's")
        assertNull(pad.direction)
    }

    @Test
    fun `a node that declines leaves the pad to navigate as before`() {
        val declines = GamepadHandler { false }
        val state = InteractionState().also { states += it }
        screen.box("0", modifier = Modifier.interaction(state).focusable(state).clickable { clicks += 1 }.onGamepadEvent(declines))
        focus.refresh()

        button(GamepadButton.South, down = true)
        button(GamepadButton.South, down = false)

        assertEquals(1, clicks)
    }

    @Test
    fun `plugging in is never offered to the screen and unplugging is told but cannot be taken`() {
        val heard = mutableListOf<GamepadEvent>()
        val state = InteractionState().also { states += it }
        screen.box("0", modifier = Modifier.interaction(state).focusable(state).onGamepadEvent { heard += it; it is GamepadEvent.Disconnected })
        focus.refresh()
        pad.frame(0L)
        stick(x = 1f)
        assertEquals(FocusDirection.Right, pad.direction)
        heard.clear()

        assertFalse(pad.onGamepad(GamepadEvent.Connected(GamepadId.First)))
        assertEquals(emptyList<GamepadEvent>(), heard, "a pad arriving is nothing to the screen")

        pad.onGamepad(GamepadEvent.Disconnected(GamepadId.First))

        assertEquals(listOf<GamepadEvent>(GamepadEvent.Disconnected(GamepadId.First)), heard, "told, so a held button can be let go of")
        assertNull(pad.direction, "and taking it did not stop the navigator letting go")
    }
}
