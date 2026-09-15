package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules for when the routers ask for a sound, on bare rectangles with no composition at all.
 *
 * The composed menus in `UiSoundsTest` show what a player hears. These pin the rules underneath one
 * at a time — which kind of focus move sounds, which node a hover belongs to, what a cancel does —
 * against a [PointerRouter] and a [FocusManager] a game could have built by hand.
 */
class UiSoundsRoutingTest {

    private val screen = TestTree()
    private val log = mutableListOf<String>()

    private val heard = object : UiSounds {
        override fun hover() { log += "hover" }
        override fun press() { log += "press" }
        override fun focusMove() { log += "focusMove" }
        override fun change() { log += "change" }
    }

    private fun usable() = Modifier.focusable().clickable {}

    private fun centreOf(name: String) = screen[name].boundsInRoot.centre

    @Test
    fun `a node with no sounds of its own is silent and nothing breaks`() {
        screen.box("a", modifier = usable())
        val focus = FocusManager(screen.root)
        val pointer = PointerRouter(screen.root, focus)

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(5f, 5f)))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(5f, 5f)))

        assertEquals(UiSounds.None, screen["a"].sounds)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `focus moves by direction sound and moves by name or by refresh do not`() {
        screen.column("a", "b", "c", modifier = usable()).forEach { it.sounds = heard }
        val focus = FocusManager(screen.root)

        focus.refresh()
        focus.focusOn(screen["c"])
        assertEquals(emptyList<String>(), log, "auto-focus and focusOn are the screen's doing, not the player's")

        assertTrue(focus.moveFocus(FocusDirection.Up))
        assertTrue(focus.moveFocus(FocusDirection.Next))
        assertEquals(listOf("focusMove", "focusMove"), log)

        focus.clearFocus()
        assertTrue(focus.moveFocus(FocusDirection.Down), "the first press starts focus")
        assertEquals(listOf("focusMove", "focusMove", "focusMove"), log)
    }

    @Test
    fun `a direction the focused node uses itself makes no move sound`() {
        screen.box("slider", modifier = usable().then(Modifier.onFocusDirection { true })).sounds = heard
        screen.box("below", y = 40f, modifier = usable()).sounds = heard
        val focus = FocusManager(screen.root)
        focus.focusOn(screen["slider"])

        assertTrue(focus.moveFocus(FocusDirection.Down))

        assertEquals("slider", focus.focused?.name)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `holding activate presses once and a disabled node cannot be pressed at all`() {
        screen.box("go", modifier = usable()).sounds = heard
        screen.box("locked", y = 40f, modifier = Modifier.focusable().clickable(enabled = false) {}).sounds = heard
        val focus = FocusManager(screen.root)
        focus.focusOn(screen["go"])

        focus.pressFocused()
        focus.pressFocused()
        focus.releaseFocused()
        assertEquals(listOf("press"), log)

        focus.focusOn(screen["locked"])
        focus.pressFocused()
        assertEquals(listOf("press"), log)
    }

    @Test
    fun `a hover belongs to the deepest usable node and a watcher under it stays quiet`() {
        val card = screen.box("card", width = 200f, height = 200f, modifier = Modifier.clickable {})
        card.sounds = heard
        val watcher = screen.box("watcher", width = 100f, height = 100f, parent = card, modifier = Modifier.onPointer { false })
        watcher.sounds = heard
        val pointer = PointerRouter(screen.root)

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(50f, 50f)))
        assertEquals(listOf("hover"), log, "the card ticks, once, though the pointer is over its watcher")

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(150f, 150f)))
        pointer.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(-1f, -1f)))
        assertEquals(listOf("hover"), log)
    }

    @Test
    fun `a press taken by a plain drag handler is silent and a cancel adds nothing`() {
        screen.box("map", width = 100f, height = 100f, modifier = Modifier.onPointer { true }).sounds = heard
        screen.box("button", x = 150f, modifier = usable()).sounds = heard
        val pointer = PointerRouter(screen.root)

        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(50f, 50f)))
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(50f, 50f)))
        assertEquals(emptyList<String>(), log)

        val at = centreOf("button")
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, at))
        assertEquals(listOf("press"), log)
    }
}
