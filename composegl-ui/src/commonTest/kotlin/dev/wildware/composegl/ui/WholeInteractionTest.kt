package dev.wildware.composegl.ui

import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onTextEvent
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One player, one screen, every way in — with no engine present at all.
 *
 * This is the test the input model exists for. It presses, drags, types, tabs and drives a pad
 * across the same three widgets, using events written by hand. Nothing here mentions LibGDX, GLFW
 * or AWT, because nothing in the toolkit can: a backend translates into these events and that is
 * the whole of its involvement.
 *
 * It lives in the common source set, so it runs on a JVM and on Linux native. Passing on a target
 * with no JVM at all is the strongest form of the claim: there is no engine underneath, because
 * there could not be one.
 */
class WholeInteractionTest {

    private val tree = UiTree()

    private val pressed = mutableListOf<String>()
    private val typed = StringBuilder()
    private val dragged = mutableListOf<Offset>()

    private val okState = InteractionState()
    private val cancelState = InteractionState()

    /** A dialogue: two buttons side by side, a text field under them, a map beside it. */
    private fun screen(): Triple<UiNode, UiNode, UiNode> {
        val ok = place("ok", 0f, 0f, 60f, 20f) {
            Modifier.interaction(okState).focusable(okState, initial = true).clickable { pressed += "ok" }
        }
        val cancel = place("cancel", 80f, 0f, 60f, 20f) {
            Modifier.interaction(cancelState).focusable(cancelState).clickable { pressed += "cancel" }
        }
        val field = place("field", 0f, 40f, 140f, 20f) {
            Modifier.focusable().onTextEvent { typed.append(it.text); true }
        }
        place("map", 0f, 80f, 140f, 60f) {
            Modifier.onPointer { event ->
                // The escape hatch: a widget that wants raw pointers, not clicks.
                if (event !is PointerEvent.Move) event is PointerEvent.Press else {
                    dragged += event.position
                    true
                }
            }
        }
        return Triple(ok, cancel, field)
    }

    private fun place(
        name: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        modifier: () -> Modifier,
    ): UiNode = UiNode(name).also {
        it.modifier = modifier()
        tree.root.insertAt(tree.root.children.size, it)
        it.x = x
        it.y = y
        it.width = width
        it.height = height
    }

    @Test
    fun `one screen driven by a mouse and a keyboard and a pad`() {
        val (ok, cancel, field) = screen()
        tree.root.width = 140f
        tree.root.height = 140f

        val focus = FocusManager(tree.root)
        focus.refresh()
        val pointer = PointerRouter(tree.root, focus)
        val keyRouter = KeyRouter(focus, tree.root)
        val keys = KeyNavigator(focus)
        val pad = GamepadNavigator(focus)

        assertEquals(ok, focus.focused, "the screen said where focus starts")

        // --- the mouse ---------------------------------------------------------------------
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(100f, 10f)))
        assertTrue(cancelState.isHovered, "the pointer is over Cancel")

        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(100f, 10f)))
        assertTrue(cancelState.isPressed)
        assertEquals(cancel, focus.focused, "pressing a button is also how focus gets there")

        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(100f, 10f)))
        assertEquals(listOf("cancel"), pressed)
        assertFalse(cancelState.isPressed)

        // --- a drag, on something that wanted raw pointers ------------------------------------
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(20f, 100f)))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(40f, 110f)))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(60f, 120f)))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(60f, 120f)))
        assertEquals(2, dragged.size, "the map saw the whole drag and nothing else did")
        assertEquals(listOf("cancel"), pressed, "and a drag on the map is not a click on a button")

        // --- the keyboard ---------------------------------------------------------------------
        focus.focusOn(field)
        keyRouter.onText(TextEvent("h"))
        keyRouter.onText(TextEvent("i"))
        assertEquals("hi", typed.toString())

        // Tab is nobody's key here, so the router declines and the navigator walks focus on.
        val tab = KeyEvent(Key.Tab, KeyEventType.Down)
        assertFalse(keyRouter.onKey(tab), "no widget claimed Tab")
        assertTrue(keys.onKey(tab))
        assertEquals(ok, focus.focused, "Tab wrapped round to the first button")

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        assertTrue(okState.isPressed, "a key holds a button down exactly as a finger does")
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        assertEquals(listOf("cancel", "ok"), pressed)

        // --- the pad ----------------------------------------------------------------------------
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.DpadRight))
        assertEquals(cancel, focus.focused, "right went to the thing on the right")

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        assertTrue(cancelState.isPressed)
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))

        assertEquals(
            listOf("cancel", "ok", "cancel"),
            pressed,
            "three presses, one from each device, and every one of them the same kind of press",
        )
    }
}
