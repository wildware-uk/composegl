package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.openContextMenu

/**
 * Keys, delivered to whatever has focus and then outwards.
 *
 * A key starts at the focused node and walks up through its parents until something says it used
 * it. Nothing is broadcast and nothing is searched for: the path is exactly the chain of nodes
 * between the focused one and the root, which is what makes "Escape closes the innermost dialogue"
 * true without any dialogue knowing another one exists. The innermost is the one focus is inside,
 * so it is asked first, and it consumes.
 *
 * With nothing focused, the walk starts at [whenNothingFocused] instead — a node the game names,
 * usually the screen's own. It is a starting point, not a broadcast: the walk from there is the
 * same walk outwards, so nothing is ever asked that is not on a path to the root.
 *
 * A key nobody on that walk used is then offered to every node that asked for shortcuts with
 * `Modifier.onShortcutKey`, inside the innermost focus trap — which is how a menu's Ctrl+S works
 * from wherever focus happens to be.
 *
 * Whatever is left over is not consumed, and the caller — a game — gets it back as false. The
 * interface has first refusal on every key; it does not have a monopoly.
 *
 * Text is routed here too, and deliberately does not bubble: committed text belongs to the thing
 * being typed into, and a parent collecting the leftovers would be a parent quietly collecting the
 * player's password. Only the focused node is offered it.
 *
 * @param focus where the key starts. The router holds no focus state of its own.
 * @param whenNothingFocused where to start when nothing has focus. Rare: focus normally settles on
 *   the first focusable node of a screen, and only a screen with nothing focusable at all has none.
 */
class KeyRouter(
    private val focus: FocusManager,
    private val whenNothingFocused: UiNode? = null,
) : InputSink {

    override fun onKey(event: KeyEvent): Boolean {
        // Heard before it is routed, wherever it goes. See InputWatcher.
        focus.tree?.watched(event)
        var node = focus.focused ?: whenNothingFocused
        // A context menu past a focus trap is behind a dialog, and not the focused widget's to open.
        var menus = true
        while (node != null) {
            // Chain order within one node: the outermost modifier in the chain is asked first,
            // which is the same order the pointer uses, so one rule covers both.
            node.resolved.keyHandlers.forEach { handler ->
                if (handler.onKey(event)) return true
            }
            // Shift+F10 is the keyboard's right-click: the nearest context menu opens, under the focused node.
            if (menus && event.type == KeyEventType.Down && event.key == Key.F10 && event.modifiers == Modifiers.Shift &&
                !event.repeat && node.openContextMenu(null, edgeOf = focus.focused ?: node)
            ) {
                return true
            }
            if (node.resolved.focusTrap) menus = false
            node = node.parent
        }
        // Then whatever put itself down for shortcuts, wherever it is: Ctrl+S saves from a field at
        // the other side of the screen. See `Modifier.onShortcutKey`.
        return focus.offerShortcut { candidate -> candidate.resolved.shortcutKeys.any { it.onKey(event) } }
    }

    /** Committed text, to the focused node and nowhere else. */
    override fun onText(event: TextEvent): Boolean {
        val node = focus.focused ?: return false
        return node.resolved.textHandlers.any { it.onText(event) }
    }

    override fun onPointer(event: PointerEvent): Boolean = false

    override fun onGamepad(event: GamepadEvent): Boolean = false
}
