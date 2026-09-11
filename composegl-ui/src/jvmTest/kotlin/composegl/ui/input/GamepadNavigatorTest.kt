package composegl.ui.input

import composegl.ui.focus.FocusDirection
import composegl.ui.focus.FocusManager
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clickable
import composegl.ui.modifier.focusable
import composegl.ui.modifier.interaction
import composegl.ui.node.UiNode
import composegl.ui.node.UiTree
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

    private val tree = UiTree()
    private val states = mutableListOf<InteractionState>()
    private var clicks = 0
    private var backs = 0

    /** [count] buttons side by side, each with the one state a widget would read. */
    private fun row(count: Int) {
        repeat(count) { index ->
            val state = InteractionState().also { states += it }
            UiNode("$index").also {
                it.modifier = Modifier.interaction(state).focusable(state).clickable { clicks += 1 }
                tree.root.insertAt(index, it)
                it.x = index * 50f
                it.width = 40f
                it.height = 20f
            }
        }
    }

    private val focus by lazy { FocusManager(tree.root) }
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
        tree.root.removeAt(0, tree.root.children.size)
        repeat(3) { index ->
            UiNode("$index").also {
                it.modifier = Modifier.focusable()
                tree.root.insertAt(index, it)
                it.y = index * 30f
                it.width = 40f
                it.height = 20f
            }
        }
        focus.refresh()
        focus.focusOn(tree.root.children[2])

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
}
