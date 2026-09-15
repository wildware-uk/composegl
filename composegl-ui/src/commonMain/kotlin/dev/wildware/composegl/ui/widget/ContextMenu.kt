package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.node.UiNode

/**
 * Gives this node a menu that opens on it: use, split, drop.
 *
 * ```kotlin
 * Box(Modifier.contextMenu {
 *     Item("Use") { use(item) }
 *     Item("Split stack", enabled = item.count > 1) { split(item) }
 *     Separator()
 *     Item("Drop") { drop(item) }
 * })
 * ```
 *
 * The menu is written in the same [MenuScope] a `MenuBar`'s menus are, so `Item`, `CheckItem`,
 * `RadioItem`, `Submenu` and `Separator` all work, and a list of items written once can go in both.
 *
 * It opens four ways, and where it opens follows how:
 *
 * - **A right-click** opens it at the pointer. It wins over a button inside this node that only clicks,
 *   so a right-click on a slot's label still opens the slot's menu; something that asks for the right
 *   button itself with `onPointer` keeps it, and so does a button that is not inside this node, a HUD
 *   button drawn over a map.
 * - **A long press** — the same hold `clickable`'s `onLongPress` uses — opens it at the finger, and
 *   the release after it is not a click. Like a right-click it reaches past a button inside that
 *   only clicks, which on a touch screen is the only way to the menu. A node's own `onLongPress`,
 *   if it has one, wins. Only a pointer's hold counts — a held Enter or South is a click — and a
 *   press that moves, a slider's thumb or a list scrolling, stops being one. A short press still
 *   goes to a `clickable` or a scroll round this node; this only takes a press nothing else wants.
 * - **Shift+F10** on the focused node, or on anything inside this one, opens it under the focused node.
 * - **[padButton]** on the pad does the same.
 *
 * It goes below and towards the end of where it was opened, and flips back over when that would go
 * off the screen; on a right-to-left screen it hangs the other way. Opened from the keyboard or the
 * pad it hangs off the focused widget, which may be deep inside this node, rather than off this node.
 * While it is open focus is trapped in it. Escape, Back and the pad's East button close it, and so
 * does a click anywhere outside it; choosing an item closes it too.
 *
 * Neither Shift+F10 nor [padButton] reaches past a focus trap: a dialog open over this node keeps
 * both keys to itself, and a right-click on a button in that dialog is the button's.
 *
 * The menu is drawn by the screen's [PopupHost], so one has to be round the screen. Unlike a
 * `Dropdown` a modifier cannot check for one while it is composed, so without one the menu simply
 * does not open and the press goes on to whatever else wants it.
 *
 * Shortcuts on its items are shown but not fired — a context menu is about the thing it opened on,
 * and nothing is that thing until it opens. Put the same shortcut on a `MenuBar` item to make it work.
 *
 * [content] written inline is a new object every recomposition and so never compares equal, exactly
 * as with `clickable`. It only costs the node a redraw when its composable recomposes.
 *
 * @param enabled false and nothing opens it.
 * @param padButton the pad button that opens it on the focused node, or null for none.
 * @param style the skin style the menu is drawn in; its parts are named after it, as a `MenuBar`'s are.
 */
fun Modifier.contextMenu(
    enabled: Boolean = true,
    padButton: GamepadButton? = GamepadButton.North,
    style: String = "menu",
    content: MenuScope.() -> Unit,
) = then(ContextMenuElement(enabled, padButton, style, content))

/** @see contextMenu */
data class ContextMenuElement(
    val enabled: Boolean,
    val padButton: GamepadButton?,
    val style: String,
    val content: MenuScope.() -> Unit,
) : Modifier.Element

/** Marks the node a [PopupHost] fills, so a node inside it can find its popup layer by walking up. */
internal data class PopupLayerElement(val popups: Popups) : Modifier.Element

/**
 * The context menu a node asked for: [target]'s menu, at [at] inside it, or at [edgeOf]'s edge when
 * null. [edgeOf] is the focused node for Shift+F10 and the pad, which may be deep inside [target].
 */
internal class ContextMenuRequest(val target: UiNode, val at: Offset?, val edgeOf: UiNode = target) {
    val anchor = PopupAnchor().also { it.node = if (at == null) edgeOf else target }

    /**
     * Counts every change to [target] while the menu is open — a new modifier, or leaving the
     * screen, its own or [edgeOf]'s. Neither is snapshot state, so this is what the layer reads to hear about them.
     */
    private var changes by mutableIntStateOf(0)

    fun changed() {
        changes++
    }

    /**
     * The target's menu as it is now, or null once it has left the screen. Read in composition, it
     * recomposes the reader on either.
     */
    fun current(): ContextMenuElement? =
        target.resolved.contextMenu.takeIf { changes >= 0 && target.tree != null && edgeOf.tree != null }
}

/**
 * Opens this node's context menu, at [at] in its own coordinates or, when null, at the edge of
 * [edgeOf]: this node, or the focused node inside it that the keyboard or the pad opened it from.
 *
 * False when it has none, it is turned off, or there is no [PopupHost] above it to draw it in.
 */
internal fun UiNode.openContextMenu(at: Offset?, edgeOf: UiNode = this): Boolean {
    if (resolved.contextMenu?.enabled != true) return false
    val popups = popupsAbove() ?: return false
    popups.contextMenu = ContextMenuRequest(this, at, edgeOf)
    return true
}

/** Whether [openContextMenu] would open something: a menu that is turned on, and a [PopupHost] to draw it in. */
internal val UiNode.canOpenContextMenu: Boolean
    get() = resolved.contextMenu?.enabled == true && popupsAbove() != null

/** The popups of the nearest [PopupHost] this node is inside, or null when there is none. */
private fun UiNode.popupsAbove(): Popups? {
    var walk = parent
    while (walk != null) {
        val popups = walk.modifier.fold<Popups?>(null) { found, element -> found ?: (element as? PopupLayerElement)?.popups }
        if (popups != null) return popups
        walk = walk.parent
    }
    return null
}

/** The one context menu a [PopupHost] has open, composed as a popup like any other. */
@Composable
internal fun ContextMenuLayer(popups: Popups) {
    val request = popups.contextMenu ?: return
    DisposableEffect(request) {
        val watch = request::changed
        request.target.watcher = watch
        request.edgeOf.watcher = watch
        // Gone between opening and now, with nobody watching yet to hear it go.
        if (request.target.tree == null || request.edgeOf.tree == null) request.changed()
        onDispose {
            if (request.target.watcher === watch) request.target.watcher = null
            if (request.edgeOf.watcher === watch) request.edgeOf.watcher = null
        }
    }
    // Through the request, so this recomposes when the target changes: its items rebuilt from what
    // the owner last composed rather than what it said when the menu opened, and a close when it goes.
    val element = request.current()
    val close = { if (popups.contextMenu === request) popups.contextMenu = null }
    if (element == null || !element.enabled) {
        // Turned off, or gone from the screen, while it was open: a menu about nothing closes.
        SideEffect { close() }
        return
    }
    val entries = buildMenu(element.content)

    key(request) {
        // The direction of the thing it opened on, which is not always the host's.
        ProvideLayoutDirection(request.target.layoutDirection) {
            MenuPopup(
                entries = entries,
                anchor = request.anchor,
                position = request.at?.let { PopupPosition.At(it) } ?: PopupPosition.BelowStart,
                style = element.style,
                // Opened from a keyboard or a pad, which is who reads the underlined letters.
                mnemonics = request.at == null,
                onChosen = close,
                onDismiss = close,
                onOutside = close,
            )
        }
    }
}
