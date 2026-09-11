package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The keyboard walking the interface.
 *
 * The same widgets the pad drives, and the same press and release underneath, so the tests here
 * ask the same questions as the pad's: does the right thing light up, and does one keystroke
 * produce exactly one click.
 */
class KeyNavigatorTest {

    private val tree = UiTree()
    private val states = mutableListOf<InteractionState>()
    private val clicks = mutableListOf<String>()

    /** A row of buttons, each with its own interaction state, laid out left to right. */
    private fun row(count: Int) {
        repeat(count) { index ->
            val state = InteractionState()
            states += state
            val node = UiNode("b$index")
            node.modifier = Modifier
                .interaction(state)
                .focusable(state)
                .clickable { clicks += "b$index" }
            tree.root.insertAt(index, node)
            node.x = index * 50f
            node.y = 0f
            node.width = 40f
            node.height = 20f
        }
    }

    /** A manager as a frame leaves it: refreshed, so auto-focus has settled somewhere. */
    private fun manager(): FocusManager = FocusManager(tree.root).also { it.refresh() }

    private fun down(key: Key, modifiers: Modifiers = Modifiers.None, repeat: Boolean = false) =
        KeyEvent(key, KeyEventType.Down, modifiers, repeat)

    private fun up(key: Key) = KeyEvent(key, KeyEventType.Up)

    @Test
    fun `Tab walks forwards and Shift-Tab walks back`() {
        row(3)
        val focus = manager()
        val keys = KeyNavigator(focus)

        assertEquals("b0", focus.focused?.name, "focus starts on the first thing that can hold it")

        assertTrue(keys.onKey(down(Key.Tab)))
        assertEquals("b1", focus.focused?.name)

        assertTrue(keys.onKey(down(Key.Tab, Modifiers.Shift)))
        assertEquals("b0", focus.focused?.name)
    }

    @Test
    fun `the arrows move focus by geometry`() {
        row(3)
        val focus = manager()
        val keys = KeyNavigator(focus)

        assertTrue(keys.onKey(down(Key.Right)))
        assertEquals("b1", focus.focused?.name)

        assertTrue(keys.onKey(down(Key.Left)))
        assertEquals("b0", focus.focused?.name)

        assertFalse(keys.onKey(down(Key.Up)), "nothing is above, so nothing moves and the game may have it")
    }

    @Test
    fun `arrows can be left to the game`() {
        row(3)
        val focus = manager()
        val keys = KeyNavigator(focus, arrowsMoveFocus = false)

        assertFalse(keys.onKey(down(Key.Right)), "on a map screen the arrows are the map's")
        assertEquals("b0", focus.focused?.name)

        assertTrue(keys.onKey(down(Key.Tab)), "Tab still walks the interface")
        assertEquals("b1", focus.focused?.name)
    }

    @Test
    fun `Enter presses on the way down and clicks on the way up`() {
        row(2)
        val focus = manager()
        val keys = KeyNavigator(focus)

        assertTrue(keys.onKey(down(Key.Enter)))
        assertTrue(states[0].isPressed, "the button is held down while the key is")
        assertTrue(clicks.isEmpty(), "and has not fired yet")

        assertTrue(keys.onKey(up(Key.Enter)))
        assertFalse(states[0].isPressed)
        assertEquals(listOf("b0"), clicks)
    }

    @Test
    fun `Space does the same as Enter`() {
        row(1)
        val focus = manager()
        val keys = KeyNavigator(focus)

        keys.onKey(down(Key.Space))
        keys.onKey(up(Key.Space))

        assertEquals(listOf("b0"), clicks)
    }

    @Test
    fun `a held key does not click twice`() {
        row(1)
        val focus = manager()
        val keys = KeyNavigator(focus)

        keys.onKey(down(Key.Enter))
        repeat(5) { keys.onKey(down(Key.Enter, repeat = true)) }
        keys.onKey(up(Key.Enter))

        assertEquals(
            listOf("b0"),
            clicks,
            "the platform repeating a held key is it saying the key is still down, not pressed again",
        )
    }

    @Test
    fun `moving focus while a key is held does not click the thing focus left`() {
        row(3)
        val focus = manager()
        val keys = KeyNavigator(focus)

        keys.onKey(down(Key.Enter))
        keys.onKey(down(Key.Tab))
        keys.onKey(up(Key.Enter))

        assertTrue(clicks.isEmpty(), "a press that ends somewhere else is a press that was abandoned")
        assertFalse(states[0].isPressed)
    }

    @Test
    fun `Escape goes back`() {
        row(1)
        var backs = 0
        val keys = KeyNavigator(manager(), onBack = { backs++ })

        assertTrue(keys.onKey(down(Key.Escape)))
        assertTrue(keys.onKey(down(Key.Back)), "the hardware back key means the same thing")
        assertEquals(2, backs)
    }

    @Test
    fun `a key the interface has no use for is left for the game`() {
        row(1)
        val keys = KeyNavigator(manager())

        assertFalse(keys.onKey(down(Key.W)))
        assertFalse(keys.onKey(up(Key.W)))
    }

    @Test
    fun `a field asked first keeps the arrows it needs`() {
        // The whole point of asking the router before the navigator, in one test: the field's own
        // handler runs first and takes Left, so the caret moves and focus stays where it is.
        val field = UiNode("field")
        val taken = mutableListOf<Key>()
        field.modifier = Modifier
            .focusable()
            .onKeyEvent { event ->
                if (event.key != Key.Left && event.key != Key.Right) false else {
                    taken += event.key
                    true
                }
            }
        tree.root.insertAt(0, field)
        field.width = 100f
        field.height = 20f
        row(1)
        tree.root.children[1].x = 200f

        val focus = manager()
        focus.focusOn(field)
        val router = KeyRouter(focus, tree.root)
        val keys = KeyNavigator(focus)

        fun send(event: KeyEvent) = router.onKey(event) || keys.onKey(event)

        assertTrue(send(down(Key.Right)))
        assertEquals(listOf(Key.Right), taken)
        assertEquals("field", focus.focused?.name, "the caret moved, not the focus")

        assertTrue(send(down(Key.Tab)), "a key the field did not want reaches the navigator")
        assertEquals("b0", focus.focused?.name)
    }
}
