package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onTextEvent
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a key goes.
 *
 * The shape being tested is the one the issue asks for: Escape closes the *innermost* dialogue, a
 * field takes the arrows it needs and lets the rest past, and what nobody wanted comes back to the
 * game. All three are the same rule — start at focus, walk outwards, stop at the first taker — so
 * the tests are mostly about proving the walk goes the right way.
 */
class KeyRouterTest {

    private val tree = UiTree()

    /** What each handler saw, in the order it saw it. Proves the walk, not just the answer. */
    private val seen = mutableListOf<String>()

    private fun node(
        name: String,
        parent: UiNode = tree.root,
        modifier: Modifier = Modifier,
    ): UiNode = UiNode(name).also {
        it.modifier = modifier
        parent.insertAt(parent.children.size, it)
    }

    /** A node that records the key and then either takes it or does not. */
    private fun watcher(
        name: String,
        parent: UiNode = tree.root,
        focusable: Boolean = false,
        takes: (KeyEvent) -> Boolean = { false },
    ): UiNode {
        val base = if (focusable) Modifier.focusable() else Modifier
        return node(
            name,
            parent,
            base.onKeyEvent { event ->
                seen += name
                takes(event)
            },
        )
    }

    private fun down(key: Key, modifiers: Modifiers = Modifiers.None) =
        KeyEvent(key, KeyEventType.Down, modifiers)

    private fun manager() = FocusManager(tree.root, autoFocus = false)

    @Test
    fun `a key starts at the focused node and walks outwards`() {
        val outer = watcher("outer")
        val middle = watcher("middle", outer)
        val inner = watcher("inner", middle, focusable = true)

        val focus = manager()
        focus.focusOn(inner)
        val router = KeyRouter(focus, tree.root)

        assertFalse(router.onKey(down(Key.Escape)), "nobody took it")
        assertEquals(listOf("inner", "middle", "outer"), seen)
    }

    @Test
    fun `Escape closes the innermost dialogue`() {
        val closed = mutableListOf<String>()
        val outerDialogue = node("outer", modifier = Modifier.onKeyEvent { event ->
            if (event.key != Key.Escape) false else { closed += "outer"; true }
        })
        val innerDialogue = node("inner", outerDialogue, modifier = Modifier.onKeyEvent { event ->
            if (event.key != Key.Escape) false else { closed += "inner"; true }
        })
        val button = node("button", innerDialogue, modifier = Modifier.focusable())

        val focus = manager()
        focus.focusOn(button)
        val router = KeyRouter(focus, tree.root)

        assertTrue(router.onKey(down(Key.Escape)))
        assertEquals(
            listOf("inner"),
            closed,
            "the dialogue focus is inside is the one that closes, and the outer one never hears it",
        )
    }

    @Test
    fun `a field swallows the arrows it needs and lets the rest through`() {
        val screen = watcher("screen")
        val field = watcher("field", screen, focusable = true) { event ->
            // Exactly what a text field does: the caret keys are mine, everything else is not.
            event.key == Key.Left || event.key == Key.Right
        }

        val focus = manager()
        focus.focusOn(field)
        val router = KeyRouter(focus, tree.root)

        assertTrue(router.onKey(down(Key.Left)), "the caret moves")
        assertEquals(listOf("field"), seen, "the screen never sees a key the field wanted")

        seen.clear()
        assertFalse(router.onKey(down(Key.Escape)), "Escape is not the field's")
        assertEquals(listOf("field", "screen"), seen)
    }

    @Test
    fun `a key nobody wants comes back to the game`() {
        watcher("screen", focusable = true).let { manager().focusOn(it) }
        val focus = manager()
        val router = KeyRouter(focus, tree.root)

        assertFalse(
            router.onKey(down(Key.W)),
            "the interface gets first refusal, not a monopoly — W still walks the player forwards",
        )
    }

    @Test
    fun `with nothing focused the named node keeps its shortcuts`() {
        val screen = watcher("screen") { it.key == Key.F1 }
        node("child", screen, modifier = Modifier.focusable())

        val focus = manager()
        val router = KeyRouter(focus, whenNothingFocused = screen)

        assertTrue(router.onKey(down(Key.F1)), "a help key works before anything is touched")
        assertEquals(listOf("screen"), seen)
    }

    @Test
    fun `with nothing focused and nowhere named there is nothing to ask`() {
        watcher("screen") { true }
        val router = KeyRouter(manager())

        assertFalse(router.onKey(down(Key.F1)))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a node with two handlers asks them in chain order`() {
        val order = mutableListOf<String>()
        val node = node(
            "both",
            modifier = Modifier
                .onKeyEvent { order += "first"; false }
                .onKeyEvent { order += "second"; true }
                .focusable(),
        )

        val focus = manager()
        focus.focusOn(node)

        assertTrue(KeyRouter(focus, tree.root).onKey(down(Key.Enter)))
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `modifiers come through untouched`() {
        var held = Modifiers.None
        val node = node("node", modifier = Modifier.onKeyEvent { held = it.modifiers; true }.focusable())

        val focus = manager()
        focus.focusOn(node)

        KeyRouter(focus, tree.root).onKey(down(Key.S, Modifiers.Control + Modifiers.Shift))

        assertTrue(held.control && held.shift, "a shortcut cannot be read without them")
    }

    @Test
    fun `a handler on a node that cannot hold focus still hears its children's keys`() {
        val panel = watcher("panel") { it.key == Key.Escape }
        val button = node("button", panel, modifier = Modifier.focusable())

        val focus = manager()
        focus.focusOn(button)

        assertTrue(KeyRouter(focus, tree.root).onKey(down(Key.Escape)))
        assertEquals(
            listOf("panel"),
            seen,
            "a panel that handles Escape for what is inside it never wants focus of its own",
        )
    }

    @Test
    fun `text goes to the focused node and no further`() {
        val typed = mutableListOf<String>()
        val parent = node("parent", modifier = Modifier.onTextEvent { typed += "parent:${it.text}"; true })
        val field = node(
            "field",
            parent,
            Modifier.focusable().onTextEvent { typed += "field:${it.text}"; true },
        )

        val focus = manager()
        focus.focusOn(field)

        assertTrue(KeyRouter(focus, tree.root).onText(TextEvent("a")))
        assertEquals(listOf("field:a"), typed, "a parent must never collect what was typed into a child")
    }

    @Test
    fun `text nobody is focused for goes nowhere`() {
        watcher("screen") { true }
        val router = KeyRouter(manager(), tree.root)

        assertFalse(
            router.onText(TextEvent("a")),
            "committed text belongs to the thing being typed into, and nothing else may collect it",
        )
    }
}
