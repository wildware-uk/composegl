package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.KeyShortcut
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.layout.layoutId
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.focusableByPointer
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.LocalPopups
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.MenuBarScope
import dev.wildware.composegl.ui.widget.Orientation
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Popups
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.contextMenu
import kotlin.math.max
import kotlin.math.min

// --- the host ------------------------------------------------------------------------------------

/**
 * Makes debug windows work for everything inside it, and draws them over it. Put it round the whole
 * game, outside everything else, once.
 *
 * ```kotlin
 * DebugWindowHost {
 *     Game()
 *     DebugWindow("Physics") { tweak("Gravity", physics::gravity, 0f..50f) }
 * }
 * ```
 *
 * A [DebugWindow] can be written anywhere inside it — beside the system it tunes, deep in a screen —
 * and is still drawn here, over the whole game, where it can be dragged anywhere. What is in a window
 * is composed as if it were where it was written, so it reads the skin, the fonts and every other
 * composition local in force there.
 *
 * It is a [PopupHost] as well, so a dropdown or a menu in a window drops over the windows, even when
 * the screen inside has a popup layer of its own.
 *
 * - **Hiding them all.** [hideShortcut] from the keyboard, or every button of [hideChord] held at once
 *   on a pad, puts every window away and brings them back. Hidden windows keep their state.
 * - **Getting to them from a pad.** [cycleButton], or [cycleShortcut] from the keyboard, puts focus in
 *   the next window along and brings it to the front. After the last window it goes back to wherever
 *   focus was in the game.
 * - **Docking them.** The windows under one host share one dock layout, drawn here: panes down the
 *   edges of the screen, split and tabbed as the player drags them, with the game showing through
 *   whatever is left. The dividers between the panes are drawn here too, over the game and under the
 *   windows.
 *
 * Both are shortcuts, so they are only heard when nothing focused used the key or the button first:
 * a field keeps its F6, and a dialogue open over the game keeps the pad. [cycleShortcut] and
 * [cycleButton] are not used either when there is nothing to cycle — no windows, or every one of them
 * hidden — so a game that reads that key or that button from its own loop keeps it, press and release
 * both, until there is a window to go to.
 *
 * @param state where the windows are and which is in front. [rememberDebugWindowsState] by default,
 *   which remembers them in [defaultDebugWindowStore].
 * @param hideShortcut hides every window, and shows them again. Null for none.
 * @param hideChord the pad buttons that do the same when all are held together. Empty for none; best
 *   made of buttons the game does nothing with on their own.
 * @param cycleShortcut moves focus to the next window. Null for none.
 * @param cycleButton the pad button that does the same, on its release, so a chord that uses it does
 *   not also cycle. Null for none.
 */
@Composable
fun DebugWindowHost(
    modifier: Modifier = Modifier,
    state: DebugWindowsState = rememberDebugWindowsState(),
    hideShortcut: KeyShortcut? = KeyShortcut(Key.F9),
    hideChord: Set<GamepadButton> = setOf(GamepadButton.LeftStick, GamepadButton.RightStick),
    cycleShortcut: KeyShortcut? = KeyShortcut(Key.F6),
    cycleButton: GamepadButton? = GamepadButton.RightStick,
    content: @Composable () -> Unit,
) {
    state.hideShortcut = hideShortcut
    state.hideChord = hideChord
    state.cycleShortcut = cycleShortcut
    state.cycleButton = cycleButton
    val keys = remember(state) { KeyHandler { state.onShortcutKey(it) } }
    val pad = remember(state) { GamepadHandler { state.onShortcutPad(it) } }
    // A pad pulled out is never offered as a shortcut, only told to the focused node and the nodes
    // outside it, so the host listens for that plainly too. It takes nothing: it only forgets the
    // buttons the pad was holding, which are no longer held by anything.
    val unplugged = remember(state) {
        GamepadHandler { event -> if (event is GamepadEvent.Disconnected) state.onShortcutPad(event) else false }
    }
    val placed = remember(state) { PlacedHandler { state.hostNode = it } }
    // How much room there is, which is what a dock layout is shared out of. Read from the host
    // rather than from each window, so the dividers and the drop squares agree with the panes.
    val sized = remember(state) { SizeChangedHandler { state.screen = it } }
    // The host's node belongs to the host. A state made outside the composition — which
    // rememberDebugWindowsState invites — would otherwise hold a tree nobody draws any more, and
    // focusNextWindow would walk it.
    DisposableEffect(state) { onDispose { state.hostLeft() } }

    PopupHost {
        // Read here, inside the host's own popup layer, and handed to every window: a window written
        // inside a screen with a popup layer of its own still drops its lists over the windows.
        state.popups = LocalPopups.current
        CompositionLocalProvider(LocalDebugWindows provides state) {
            Box(
                Modifier.fillMaxSize().then(modifier)
                    .onShortcutKey(keys)
                    .onShortcutGamepad(pad)
                    .onGamepadEvent(unplugged)
                    .onPlaced(placed)
                    .onSizeChanged(sized),
            ) {
                content()
                if (!state.hidden) DebugWindowLayer(state)
            }
        }
    }
}

/** The host a [DebugWindow] puts itself in, or null outside any [DebugWindowHost]. */
internal val LocalDebugWindows = staticCompositionLocalOf<DebugWindowsState?> { null }

/** A [DebugWindowsState] that lives as long as the composition, loading from [store] once. */
@Composable
fun rememberDebugWindowsState(store: DebugWindowStore = remember { defaultDebugWindowStore() }): DebugWindowsState =
    remember(store) { DebugWindowsState(store) }

/**
 * Every debug window under one [DebugWindowHost]: where each one is, how big, whether it is folded to
 * its title bar, which is in front, whether they are all hidden, and which of them are docked into
 * panes rather than floating.
 *
 * Snapshot state, so a game can read it to draw a Windows menu, and change it from anywhere. What it
 * holds is written to [store] whenever a window stops moving or changes size, when a window or a
 * section in one is folded or unfolded, and whenever the dock layout changes.
 */
class DebugWindowsState(private val store: DebugWindowStore) {

    private var away by mutableStateOf(false)

    /**
     * Whether every window is put away. [DebugWindowHost]'s hide shortcut and chord flip it.
     *
     * Putting them away sends focus back where it was in the game, so the keyboard is never left on
     * a window nobody can see.
     */
    var hidden: Boolean
        get() = away
        set(value) {
            if (away == value) return
            away = value
            if (value) hostNode?.findFocusManager()?.let { focus -> if (isInAWindow(focus.focused)) handBack(focus) }
        }

    /** Every window there is, in the order they were first shown. */
    internal val entries = mutableStateListOf<WindowEntry>()

    /** The same windows from the back to the front. */
    internal val order = mutableStateListOf<WindowEntry>()

    private val placements = HashMap<String, WindowPlacement>()
    private val sections = mutableStateMapOf<String, Boolean>()
    private val saved: MutableMap<String, String> = store.load().toMutableMap()

    /**
     * Where the docked windows are, as one value: panes either side of a divider, down to the windows
     * tabbed together in each one. A change is a whole new tree, so this one field is all the
     * snapshot state a layout needs and everything that read it is composed again.
     */
    private var dockLayout by mutableStateOf(DockLayoutText.read(saved[DockKey]))

    /** How big the host is. Written by its layout, read by the dividers and the drop squares. */
    internal var screen by mutableStateOf(Size.Zero)

    /** The window being dragged by its title bar or its tab, or null while nothing is being dragged. */
    internal var dockDrag by mutableStateOf<DockDrag?>(null)

    internal var hostNode: UiNode? = null
    internal var popups: Popups? = null
    internal var hideShortcut: KeyShortcut? = null
    internal var hideChord: Set<GamepadButton> = emptySet()
    internal var cycleShortcut: KeyShortcut? = null
    internal var cycleButton: GamepadButton? = null

    /** Where focus was in the game before [focusNextWindow] took it into a window, so it can go back. */
    private var returnTo: UiNode? = null

    /** The ids of the windows showing now, from the back to the front. */
    val windows: List<String> get() = order.map { it.id }

    /** Where the window [id] is — its top-left corner, or top-right on a right-to-left screen — or null for no such window. */
    fun position(id: String): Offset? = placements[id]?.let { Offset(it.x, it.y) }

    /** How big the player made the window [id], or null while it is still the size of what is in it. */
    fun size(id: String): Size? = placements[id]?.let { if (it.width.isNaN() || it.height.isNaN()) null else Size(it.width, it.height) }

    /** Whether the window [id] is folded to its title bar. */
    fun isCollapsed(id: String): Boolean = placements[id]?.collapsed == true

    fun setCollapsed(id: String, collapsed: Boolean) {
        val placement = placements[id] ?: return
        if (placement.collapsed == collapsed) return
        placement.collapsed = collapsed
        save()
    }

    /**
     * Draws the window [id] over all the others, and takes focus into it.
     *
     * Raising a window takes focus with it, the way a desktop window does, so the window in front is
     * always the one drawn lit. Focus already inside it stays exactly where it is.
     */
    fun bringToFront(id: String) {
        order.firstOrNull { it.id == id }?.let(::raise)
    }

    /**
     * Every window back where the code puts it, floating, at the size of what is in it and unfolded,
     * and every section in them back to how it starts. What was saved is forgotten too.
     */
    fun resetLayout() {
        placements.values.forEach { it.reset() }
        sections.clear()
        saved.clear()
        dockLayout = DockEmpty
        save()
    }

    // --- docking -----------------------------------------------------------------------------------

    /** The windows docked now, in the order their panes read: first pane first, first tab first. */
    val dockedWindows: List<String> get() = dockLayout.windows()

    /** Whether the window [id] is in the dock layout rather than floating over the game. */
    fun isDocked(id: String): Boolean = dockLayout.tabsOf(id) != null

    /** The windows tabbed together with [id], itself among them, or empty when it is not docked. */
    fun tabsWith(id: String): List<String> = dockLayout.tabsOf(id)?.windows.orEmpty()

    /**
     * Docks the window [id] against one edge of the screen, taking a quarter of it, with everything
     * already docked — and the game — beside it.
     *
     * A window already docked somewhere else moves here. What was saved for it as a floating window
     * is kept, so undocking puts it back where it was.
     */
    fun dockToScreen(id: String, side: DockSide) {
        applyDock(dockLayout.dockedToScreen(id, side))
    }

    /**
     * Docks the window [id] into the pane the window [window] is in: tabbed with it when [side] is
     * null, or taking half of its pane on that side.
     *
     * [window] floating rather than docked is the ordinary way a run starts, so it is given a pane
     * first: it docks against the edge of the screen it is nearest, and [id] goes in beside it. The
     * two land together either way.
     *
     * Nothing happens when there is no such window as [window] — nothing composed and nothing docked
     * under that id — since there is nothing there to dock against.
     */
    fun dockWith(id: String, window: String, side: DockSide? = null) {
        if (!isDocked(window) && entries.none { it.id == window }) return
        applyDock(dockLayout.dockedWith(id, window, side, anchorFor(window)))
    }

    /**
     * Which edge of the screen the floating window [id] docks against when another is dropped on it:
     * the edge it is nearest, so the pane opens where the player was already looking. The left while
     * there is nothing measured to go on.
     */
    private fun anchorFor(id: String): DockSide {
        val screen = screenRect()
        if (screen.isEmpty) return DockSide.Left
        val frame = entries.firstOrNull { it.id == id }?.frame?.takeIf { it.everMeasured } ?: return DockSide.Left
        return nearestSide(frame.layoutBoundsInRoot, screen)
    }

    /** Floats the window [id] again, where it was before it was docked. Nothing happens when it is not docked. */
    fun undock(id: String) {
        applyDock(dockLayout.without(id))
    }

    /**
     * Brings the window [id] forward in its pane, as clicking its tab does. Nothing happens when it
     * is not docked: a floating window comes forward with [bringToFront].
     */
    fun showTab(id: String) {
        applyDock(dockLayout.selecting(id))
    }

    /**
     * The layout as it is on the screen: only the windows composed this run.
     *
     * A layout saved last time names windows this run may not show at all, and a pane held open for
     * one of those would be a hole nothing fills. They stay in [dockLayout] — a window composed again
     * later goes back where it was — and are left out of everything that is drawn or measured.
     */
    private fun showingLayout(): DockNode = dockLayout.retaining(::isComposed)

    /** Whether the window [id] is one this run puts on the screen at all. */
    private fun isComposed(id: String): Boolean = entries.any { it.id == id }

    /** Where the window [id] is docked and who it shares its pane with, or null while it floats. */
    internal fun dockSlotOf(id: String): DockSlot? {
        val layout = showingLayout()
        val pane = layout.tabsOf(id) ?: return null
        val steps = layout.stepsTo(id) ?: return null
        return DockSlot(steps, pane.windows, pane.selected)
    }

    /** Every divider between two docked panes, where the host last measured them. */
    internal fun dockDividers(): List<DockDividerAt> {
        if (screen.width <= 0f || screen.height <= 0f) return emptyList()
        return showingLayout().dividers(Rect.of(0f, 0f, screen.width, screen.height))
    }

    /**
     * Moves the divider at [path] to [fraction] of its space, true when the layout really changed.
     *
     * [path] names the split in the layout as it is drawn, which leaves out the windows this run does
     * not compose; the whole layout keeps them, so the way down is translated before the split is
     * moved.
     */
    internal fun moveDivider(path: List<Boolean>, fraction: Float): Boolean {
        val inLayout = dockLayout.pathRetaining(path, ::isComposed) ?: return false
        val next = dockLayout.withFraction(inLayout, fraction)
        if (next == dockLayout) return false
        dockLayout = next
        return true
    }

    /** What the window [id]'s title bar says, for the tab drawn for it in somebody else's window. */
    internal fun titleOf(id: String): String = entries.firstOrNull { it.id == id }?.title ?: id

    /** Whether the window [id] is on the screen at all: floating, or the tab showing in its pane. */
    internal fun isShowing(id: String): Boolean {
        val pane = showingLayout().tabsOf(id) ?: return true
        return pane.selected == id
    }

    /**
     * The layout changed: kept, written to the store, and focus taken off a window the change has
     * just hidden behind another tab.
     */
    private fun applyDock(next: DockNode) {
        if (next == dockLayout) return
        dockLayout = next
        save()
        val focus = hostNode?.findFocusManager() ?: return
        val current = focus.focused ?: return
        val behind = entries.firstOrNull { current.isInside(it.root) && !isShowing(it.id) } ?: return
        // The window focus was in is behind another tab now, so focus goes where the player is
        // looking: the tab that came forward in its pane.
        val pane = showingLayout().tabsOf(behind.id)
        val showing = entries.firstOrNull { it.id == pane?.selected }
        if (showing != null) focusInto(showing, focus, keepReturn = false) else handBack(focus)
    }

    /** A window dragged by its title bar or its tab: which one, and where the pointer has got to. */
    internal class DockDrag(val id: String, val tab: Boolean, val rtl: Boolean, start: Offset) {

        /** Where the pointer is now, in the host's own coordinates. */
        var pointer by mutableStateOf(start)
    }

    internal fun startDockDrag(id: String, at: Offset, tab: Boolean, rtl: Boolean) {
        dockDrag = DockDrag(id, tab, rtl, at)
    }

    internal fun moveDockDrag(by: Offset) {
        dockDrag?.let { it.pointer += by }
    }

    /** The drag was let go: docked where it was dropped, or left floating when it was dropped on nothing. */
    internal fun endDockDrag() {
        val drag = dockDrag ?: return
        dockDrag = null
        val drop = dropTargetAt(drag.pointer, screenRect(), dropTargets(drag.id))
        when {
            drop == null -> if (drag.tab) floatAt(drag.id, drag.pointer, drag.rtl)
            drop.window == null -> drop.side?.let { dockToScreen(drag.id, it) }
            else -> dockWith(drag.id, drop.window, drop.side)
        }
    }

    /** The drag was taken away — the window closed under it, the pad pulled out — so nothing happens. */
    internal fun cancelDockDrag() {
        dockDrag = null
    }

    internal fun screenRect(): Rect = Rect.of(0f, 0f, screen.width, screen.height)

    /**
     * The space the window [dragged] would take if it were let go on [drop]: the pane the layout that
     * drop would make gives it.
     *
     * Worked out from the layout itself rather than drawn round the window under the pointer, so the
     * patch can never promise a place the drop would not really put it — dropping on a floating
     * window docks that window against an edge, and the patch says so before the player lets go.
     */
    internal fun dropPreview(dragged: String, drop: DockDrop): Rect {
        val screen = screenRect()
        val next = if (drop.window == null) {
            showingLayout().dockedToScreen(dragged, drop.side ?: return screen)
        } else {
            showingLayout().dockedWith(dragged, drop.window, drop.side, anchorFor(drop.window))
        }
        return dockRect(next.stepsTo(dragged) ?: return screen, screen)
    }

    /**
     * The windows the drop squares can be shown over: the ones on the screen, front first, without
     * the one being dragged.
     */
    internal fun dropTargets(dragged: String): List<Pair<String, Rect>> =
        order.asReversed()
            .filter { it.id != dragged && isShowing(it.id) }
            .mapNotNull { entry -> entry.frame?.takeIf { it.everMeasured }?.let { entry.id to it.layoutBoundsInRoot } }

    /** Floats a docked window again, its title bar landing under the pointer that pulled it out. */
    private fun floatAt(id: String, at: Offset, rtl: Boolean) {
        val placement = placements[id]
        if (placement != null) {
            // The position is measured from the screen's start edge, which is the right on a
            // right-to-left screen, so the corner the pointer is near is the same corner either way.
            val across = if (rtl) screen.width - at.x else at.x
            placement.x = across - TornGrab
            placement.y = max(at.y - TornGrab, 0f)
        }
        undock(id)
    }

    /**
     * Focus into the next window along, first shown first, and that window to the front. From the last
     * window focus goes back to where it was in the game. True when focus moved.
     *
     * A window behind another tab is not one of them: there is nothing of it on the screen to land
     * on, and its own tab is focusable, which is how the keyboard and the pad reach it.
     */
    fun focusNextWindow(): Boolean {
        val focus = hostNode?.findFocusManager() ?: return false
        val showing = if (hidden) emptyList() else entries.filter { it.root.parent != null && isShowing(it.id) }
        if (showing.isEmpty()) return false
        val current = focus.focused
        val index = showing.indexOfFirst { current != null && current.isInside(it.root) }
        if (index < 0) returnTo = current
        if (index == showing.lastIndex) {
            handBack(focus)
            return true
        }
        val next = showing[index + 1]
        val reachable = focus.reachable()
        val body = next.body
        val target = reachable.firstOrNull { body != null && it.isInside(body) }
            ?: reachable.firstOrNull { it.isInside(next.root) }
            ?: return false
        bringToFront(next)
        return focus.focusOn(target)
    }

    // --- used by the windows ---------------------------------------------------------------------

    internal fun entryFor(id: String, initialPosition: Offset, initialSize: Size?): WindowEntry {
        val placement = placements.getOrPut(id) {
            WindowPlacement(initialPosition, initialSize).also { it.restore(saved[windowKey(id)]) }
        }
        return WindowEntry(id, placement)
    }

    internal fun register(entry: WindowEntry) {
        entries += entry
        order += entry
    }

    internal fun unregister(entry: WindowEntry) {
        val focus = hostNode?.findFocusManager()
        val hadFocus = focus != null && focus.focused?.isInside(entry.root) == true
        entries -= entry
        order -= entry
        if (focus == null || !hadFocus) return
        // Focus is going with the window, so it is not somewhere to come back to either.
        returnTo = returnTo?.takeIf { !it.isInside(entry.root) }
        // A window that closes with focus in it hands it on, the way a desktop does: to the window
        // now in front, or back to the game when that was the last one.
        val next = if (hidden) null else order.lastOrNull { it.root.parent != null && isShowing(it.id) }
        if (next != null) focusInto(next, focus, keepReturn = false) else handBack(focus)
    }

    /** Draws [entry] over the others, and takes focus into it. See [bringToFront]. */
    internal fun raise(entry: WindowEntry) {
        bringToFront(entry)
        hostNode?.findFocusManager()?.let { focusInto(entry, it) }
    }

    internal fun bringToFront(entry: WindowEntry) {
        // A docked window has nothing to come out from under: it comes forward in its own pane
        // instead, which is what "in front" means for a tab.
        applyDock(dockLayout.selecting(entry.id))
        if (order.lastOrNull() === entry || entry !in order) return
        order -= entry
        order += entry
    }

    /** Whether [node] is in any window at all, rather than out in the game. */
    private fun isInAWindow(node: UiNode?): Boolean = node != null && entries.any { node.isInside(it.root) }

    /**
     * Focus into [entry], leaving it alone when it is already there.
     *
     * The frame itself is what it lands on: pressing a title bar should light the window without
     * arming a control in it, and the frame takes focus from a pointer only, so it is never a place
     * Tab or the pad stops at.
     *
     * @param keepReturn whether to keep where focus was, so hiding the windows or closing the last
     *   one can put it back. False when focus is being moved off a window that is going away.
     */
    private fun focusInto(entry: WindowEntry, focus: FocusManager, keepReturn: Boolean = true) {
        val current = focus.focused
        if (current != null && current.isInside(entry.root)) return
        if (keepReturn && !isInAWindow(current)) returnTo = current
        val frame = entry.frame
        if (frame != null && focus.focusOn(frame)) return
        val target = focus.reachable().firstOrNull { it.isInside(entry.root) } ?: return
        focus.focusOn(target)
    }

    /** Focus out of the windows: back where it was in the game, or nowhere at all. */
    private fun handBack(focus: FocusManager) {
        val back = returnTo?.takeIf { it.tree != null }
        returnTo = null
        if (back == null || !focus.focusOn(back)) focus.clearFocus()
    }

    internal fun isSectionOpen(window: String, title: String, initially: Boolean): Boolean {
        val key = sectionKey(window, title)
        sections[key]?.let { return it }
        return when (saved[key]) {
            "open" -> true
            "closed" -> false
            else -> initially
        }
    }

    internal fun setSectionOpen(window: String, title: String, open: Boolean) {
        sections[sectionKey(window, title)] = open
        save()
    }

    /** Writes everything to the store, keeping what was saved for windows not showing this run. */
    internal fun save() {
        placements.forEach { (id, placement) -> saved[windowKey(id)] = placement.describe() }
        sections.forEach { (key, open) -> saved[key] = if (open) "open" else "closed" }
        saved[DockKey] = DockLayoutText.write(dockLayout)
        store.save(saved.toMap())
    }

    internal fun onShortcutKey(event: KeyEvent): Boolean {
        if (hideShortcut?.matches(event) == true) {
            hidden = !hidden
            return true
        }
        // A cycle with nowhere to go — no windows, or every one of them put away — is not used, so a
        // game that reads the key from its own loop still gets it while there is nothing to cycle.
        if (cycleShortcut?.matches(event) == true) return focusNextWindow()
        return false
    }

    private val held = HashMap<Int, MutableSet<GamepadButton>>()
    private var chordSpent = false

    /** The pads whose cycle button was taken on the way down, so its release is taken too. */
    private val cycleTaken = HashSet<Int>()

    /** Where focus was when the buttons now held went down. See [forgetHeld]. */
    private var heldFocus: UiNode? = null

    /**
     * Forgets which buttons are down, because what is remembered can no longer be trusted.
     *
     * A shortcut only hears a button nothing focused took first, so a widget that swallows a
     * release — a slider still turning while a shoulder is held — leaves that button held here for
     * ever, and the other button of the chord on its own would then read as the whole chord. So the
     * memory is dropped whenever focus has moved since, and whenever the pad is pulled out.
     */
    private fun forgetHeld() {
        held.clear()
        chordSpent = false
        heldFocus = null
    }

    /** The host has left the composition, and nothing it showed us may outlive it. */
    internal fun hostLeft() {
        hostNode = null
        returnTo = null
        forgetHeld()
        cycleTaken.clear()
    }

    /**
     * Whether [focusNextWindow] has anywhere to go: something to move focus with, and a window that
     * is both composed and not put away. The cheap half of what [focusNextWindow] checks, asked on
     * the way down so a button that cannot cycle is left to the game.
     */
    private fun canCycle(): Boolean =
        hostNode?.findFocusManager() != null && !hidden && entries.any { it.root.parent != null && isShowing(it.id) }

    internal fun onShortcutPad(event: GamepadEvent): Boolean {
        when (event) {
            is GamepadEvent.ButtonDown -> {
                val focused = hostNode?.findFocusManager()?.focused
                if (focused !== heldFocus) forgetHeld()
                heldFocus = focused
                val buttons = held.getOrPut(event.gamepadId.value) { mutableSetOf() }
                buttons += event.button
                if (hideChord.isNotEmpty() && event.button in hideChord && buttons.containsAll(hideChord)) {
                    hidden = !hidden
                    chordSpent = true
                    return true
                }
                // A cycle with nowhere to go — no windows, or every one of them put away — is not
                // used, the way the cycle key is not, so a game that reads the button from its own
                // loop still gets it while there is nothing to cycle.
                if (event.button == cycleButton && canCycle()) {
                    cycleTaken += event.gamepadId.value
                    return true
                }
                return false
            }
            is GamepadEvent.ButtonUp -> {
                val buttons = held[event.gamepadId.value]
                buttons?.remove(event.button)
                if (event.button == cycleButton) {
                    // A chord this button was part of has already been answered; its release is not a cycle too.
                    val cycled = !chordSpent && focusNextWindow()
                    if (buttons.isNullOrEmpty()) chordSpent = false
                    // The release goes with its press: taken when the press was, so the game never
                    // sees half of a press it never saw the start of.
                    return cycleTaken.remove(event.gamepadId.value) || cycled
                }
                if (buttons.isNullOrEmpty()) chordSpent = false
                return false
            }
            is GamepadEvent.Disconnected -> {
                // Nothing is holding anything any more, and the releases will never arrive.
                forgetHeld()
                cycleTaken.clear()
                return false
            }
            else -> return false
        }
    }

    private fun windowKey(id: String) = "window:$id"

    private fun sectionKey(window: String, title: String) = "section:$window:$title"
}

/** What the store keeps the dock layout under. One line for the lot, beside the windows' own. */
private const val DockKey = "dock"

/** How far inside its title bar a window torn out of a dock lands under the pointer. */
private const val TornGrab = 24f

/** Where one window sits in the dock layout: its pane, and who else is in it. */
internal class DockSlot(val steps: List<DockStep>, val tabs: List<String>, val selected: String)

/** One window's place on the screen, as the player left it. */
internal class WindowPlacement(private val initialPosition: Offset, private val initialSize: Size?) {

    /** From the screen's start edge: the left, or the right on a right-to-left screen. */
    var x by mutableStateOf(initialPosition.x)
    var y by mutableStateOf(initialPosition.y)

    /** NaN for as wide as what is in it. */
    var width by mutableStateOf(initialSize?.width ?: Float.NaN)

    /** NaN for as tall as what is in it. */
    var height by mutableStateOf(initialSize?.height ?: Float.NaN)
    var collapsed by mutableStateOf(false)

    /** The room the window was last laid out in. Written by layout, read by the drags. */
    var screenWidth = 0f
    var screenHeight = 0f

    fun reset() {
        x = initialPosition.x
        y = initialPosition.y
        width = initialSize?.width ?: Float.NaN
        height = initialSize?.height ?: Float.NaN
        collapsed = false
    }

    fun describe(): String = listOf(number(x), number(y), number(width), number(height), if (collapsed) "collapsed" else "open")
        .joinToString(";")

    /** What [describe] wrote. Anything that does not read as that is ignored, and the code's placement stands. */
    fun restore(text: String?) {
        val parts = text?.split(';') ?: return
        if (parts.size != 5) return
        val numbers = parts.take(4).map { if (it == "auto") Float.NaN else it.toFloatOrNull() ?: return }
        if (numbers[0].isNaN() || numbers[1].isNaN()) return
        x = numbers[0]
        y = numbers[1]
        width = numbers[2]
        height = numbers[3]
        collapsed = parts[4] == "collapsed"
    }

    private fun number(value: Float) = if (value.isNaN()) "auto" else value.toString()
}

/** One window shown on the host: its nodes, and where it lands. */
internal class WindowEntry(val id: String, val placement: WindowPlacement) {

    /** What the window's own composition builds into, and what the host puts over the game. */
    val root = UiNode("debugwindow.slot")

    /** What its title bar says, so the window drawing the tabs of a pane can letter this one's. */
    var title by mutableStateOf(id)

    var frame: UiNode? = null
    var body: UiNode? = null
    var collapseButton: UiNode? = null
}

/** Where the host keeps the windows: over the game, the one in front drawn last. */
@Composable
private fun DebugWindowLayer(state: DebugWindowsState) {
    val order = state.order
    // In the order they were first shown, so bringing one to the front changes a number rather than
    // moving nodes about; the z-index is what puts it on top, for drawing and for the pointer alike.
    state.entries.forEach { entry ->
        key(entry) {
            val z = 1f + order.indexOf(entry)
            ComposeNode<UiNode, UiApplier>(
                factory = { entry.root },
                update = {
                    set(z) { this.modifier = Modifier.fillMaxSize().zIndex(it) }
                },
            )
        }
    }
    // The dividers and the drop squares belong to the layout rather than to any one window, so the
    // host draws them: a divider in the gap between two panes, under the windows that may float over
    // it, and the squares a drag is aimed at over everything.
    DockDividers(state)
    DockDropTargets(state)
}

/**
 * Over the game and under every window. A divider is in the gap the panes leave between them, so
 * nothing docked is ever under one; a floating window over one is the window the player is using, and
 * it keeps its own presses rather than losing a six pixel strip of them to the divider.
 */
private const val DividerZ = 0.5f

/** Over everything, windows and dividers alike: while a drag is in the air, the squares are the screen. */
private const val DropTargetZ = 1_000_001f

// --- a window ------------------------------------------------------------------------------------

/**
 * A floating window of tools over the game: the thing imgui is reached for, for tuning gravity,
 * spawning a wave or turning on god mode without writing a screen for it.
 *
 * ```kotlin
 * DebugWindow("Physics", initialPosition = Offset(20f, 20f)) {
 *     tweak("Gravity", physics::gravity, 0f..50f)
 *     tweak("Friction", physics::friction, 0f..1f, step = 0.05f)
 *     toggle("God mode", cheats::godMode)
 *     choice("Difficulty", game::difficulty, Difficulty.entries)
 *     colour("Fog", world::fogColour)
 *     button("Spawn wave") { spawnWave() }
 *     CollapsingHeader("Advanced") { tweak("Air", physics::air, 0f..1f) }
 * }
 * ```
 *
 * It goes anywhere inside a [DebugWindowHost], and is drawn by the host over everything. Each line of
 * [content] is a label and a control, the labels in a column [labelWidth] wide so the controls line
 * up; see [DebugWindowScope] for the lines there are. Ordinary composables go in it too.
 *
 * - **Mouse.** Dragging the title bar moves it. Dragging an edge or a corner resizes it, down to
 *   [minSize]; the cursor says which way. A press anywhere on it brings it to the front. The triangle,
 *   or a double click on the title bar, folds it to its title bar and back. The cross closes it.
 * - **Docking.** While the title bar is being dragged, squares appear round the edges of the screen
 *   and in a cross over whatever window is under the pointer. Let go on one and the window becomes a
 *   pane down that edge, a pane taking half of that window's, or — the middle square — a tab beside
 *   it. A window dropped on one that is still floating takes it along: that window docks against the
 *   edge of the screen it was nearest, and the two land there together. A docked window wears a strip
 *   of tabs instead of a title bar; dragging a tab out floats that window again, and the dividers
 *   between the panes are dragged to share out the room. See [DebugWindowsState.dockToScreen].
 * - **Keyboard.** The triangle and the cross are focusable, like every control in it. With focus
 *   anywhere inside, Ctrl and an arrow (Command on a Mac) moves it, Ctrl, Shift and an arrow makes
 *   it bigger or smaller, Ctrl, Alt and an arrow docks it against that edge of the screen and Ctrl,
 *   Alt and F floats it again. Focus arriving inside brings it to the front.
 * - **Its own menu.** A right click on the title bar or the tabs — Shift+F10 from the keyboard, North
 *   on a pad — opens where to dock it, how to float it again, and how to close it.
 * - **Which one is lit.** Raising a window takes focus with it, and focus arriving in one raises it,
 *   so there is only ever one answer: the window in front is the one drawn lit. A press on the title
 *   bar lands focus on the frame rather than on a control, so it lights the window without arming
 *   anything. A window that is closed or put away hands focus to the window now in front, or back to
 *   where it was in the game.
 * - **Pad.** The host's cycle button brings focus into it. Inside, the right stick moves it.
 * - **Right to left**, [initialPosition] is measured from the top-right corner, the title bar reads
 *   from the right, and the triangle of a folded window points left.
 *
 * Where it is, how big it is and whether it is folded are remembered by [DebugWindowsState] under
 * [id], and kept across runs by its store. Until the player resizes it, it is as big as what is in it,
 * up to the room on the screen under it, so its bottom edge is always somewhere they can grab; after
 * that it keeps its size and what is in it scrolls.
 *
 * Every look is the skin's: `"<style>"` for the frame, and `"<style>.active"` while it is the window in
 * front; `"<style>.title"` and `"<style>.title.active"` for the title bar, which is also what a strip
 * of tabs wears; `"<style>.tab"` and `"<style>.tab.selected"` for the tabs on it; `"<style>.button"` for the
 * triangle and the cross, in its states, drawn in its text colour; `"<style>.body"` round the contents;
 * `"<style>.label"` and `"<style>.value"` for a line's label and its readout; and `"<style>.grip"` for
 * the corner a window is resized from. A colour line is the toolkit's own colour button, so it reads
 * `"colourswatch"` and `"colourpicker"` like one anywhere else.
 *
 * @param title what the title bar says. Also the [id], unless one is given.
 * @param initialPosition where it first appears, before the player has moved it.
 * @param initialSize how big it first is. Null for the size of what is in it.
 * @param minSize the smallest the player can make it.
 * @param id what it is remembered by. Two windows with the same title need different ids.
 * @param onClose called when the cross is pressed. Null for a window with no cross. Like a dialogue,
 *   the window does not close itself: the game stops composing it.
 * @param menuBar the menus along the top of the window, under the title bar, as for a `MenuBar`. Null
 *   for none.
 * @param labelWidth how wide the column of labels is.
 */
@Composable
fun DebugWindow(
    title: String,
    modifier: Modifier = Modifier,
    initialPosition: Offset = Offset(20f, 20f),
    initialSize: Size? = null,
    minSize: Size = Size(140f, 60f),
    id: String = title,
    onClose: (() -> Unit)? = null,
    menuBar: (MenuBarScope.() -> Unit)? = null,
    labelWidth: Float = 110f,
    style: String = "debugwindow",
    content: @Composable DebugWindowScope.() -> Unit,
) {
    val state = checkNotNull(LocalDebugWindows.current) {
        "no debug window host: put a DebugWindowHost round the whole game, outside everything else"
    }
    val context = rememberCompositionContext()
    val entry = remember(state, id) { state.entryFor(id, initialPosition, initialSize) }
    val chrome = rememberUpdatedState(WindowChrome(title, modifier, minSize, onClose, menuBar, labelWidth, style))
    val latest = rememberUpdatedState(content)

    DisposableEffect(state, entry) {
        val composition = Composition(UiApplier(entry.root), context)
        val popups = state.popups
        composition.setContent {
            CompositionLocalProvider(LocalPopups provides popups) {
                WindowFrame(state, entry, chrome.value, latest.value)
            }
        }
        state.register(entry)
        onDispose {
            state.unregister(entry)
            composition.dispose()
        }
    }
}

/** Everything about a window that is not what is in it, as one value, so a change to any is one update. */
internal data class WindowChrome(
    val title: String,
    val modifier: Modifier,
    val minSize: Size,
    val onClose: (() -> Unit)?,
    val menuBar: (MenuBarScope.() -> Unit)?,
    val labelWidth: Float,
    val style: String,
)

/** Test tags on a window's own nodes, prefixed so they never meet a game's. */
internal object DebugWindowTags {
    fun window(id: String) = "debugwindow:$id"
    fun title(id: String) = "debugwindow:$id:title"
    fun collapse(id: String) = "debugwindow:$id:collapse"
    fun close(id: String) = "debugwindow:$id:close"
    fun body(id: String) = "debugwindow:$id:body"
    fun edge(id: String, edge: WindowEdge) = "debugwindow:$id:edge:${edge.name.lowercase()}"

    /** The control on the line labelled [label]. */
    fun control(id: String, label: String) = "debugwindow:$id:control:$label"

    /** One option of the choice on the line labelled [label], in the field and again in its open list. */
    fun option(id: String, label: String, option: String) = "debugwindow:$id:option:$label:$option"

    /** The line labelled [label]. */
    fun row(id: String, label: String) = "debugwindow:$id:row:$label"

    /** The strip of tabs a docked pane wears instead of a title bar, on the window drawing it. */
    fun tabs(id: String) = "debugwindow:$id:tabs"

    /** The tab for the window [id], wherever in a pane's strip it is drawn. */
    fun tab(id: String) = "debugwindow:$id:tab"

    /** The divider between two docked panes, outermost first. */
    fun divider(index: Int) = "debugwindow:divider:$index"

    /** The square that docks a dragged window against one edge of the screen. */
    fun screenDrop(side: DockSide) = "debugwindow:drop:${side.name.lowercase()}"

    /** One square of the cross over the window [id]; a null [side] is the middle one, which tabs them together. */
    fun windowDrop(id: String, side: DockSide?) = "debugwindow:drop:$id:${side?.name?.lowercase() ?: "centre"}"

    /** The patch showing where a dragged window would land. */
    const val DropPreview = "debugwindow:drop:preview"
}

/** A side or a corner of a window, which a drag resizes it from. */
internal enum class WindowEdge(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean, val icon: PointerIcon) {
    Left(true, false, false, false, PointerIcon.ResizeHorizontal),
    Right(false, true, false, false, PointerIcon.ResizeHorizontal),
    Top(false, false, true, false, PointerIcon.ResizeVertical),
    Bottom(false, false, false, true, PointerIcon.ResizeVertical),
    TopLeft(true, false, true, false, PointerIcon.ResizeTopLeftBottomRight),
    TopRight(false, true, true, false, PointerIcon.ResizeTopRightBottomLeft),
    BottomLeft(true, false, false, true, PointerIcon.ResizeTopRightBottomLeft),
    BottomRight(false, true, false, true, PointerIcon.ResizeTopLeftBottomRight),
}

/** How thick the strip along an edge is that a drag resizes from. */
private const val EdgeGrab = 5f

/**
 * How far along each side of a corner its grab reaches.
 *
 * A corner is an L of two strips this long and [EdgeGrab] thick, not a square that deep: a square
 * would reach in over the triangle and the cross on the title bar, and over the corners of the
 * controls in the body, and being invisible it would quietly eat the presses meant for them.
 */
private const val CornerGrab = 12f

/** How much of a window is kept on the screen whatever the player does, so it can always be dragged back. */
private const val KeepOnScreen = 32f

/** How far Ctrl and an arrow moves a window, and Ctrl, Shift and an arrow resizes one. */
private const val KeyStep = 16f

/** How fast the right stick moves a window at full tilt, in pixels a second. */
private const val StickSpeed = 600f

private const val GlyphSize = 10f

/**
 * What one window knows between frames that is not state: the arithmetic of a drag, which needs the
 * size layout measured rather than the one it asked for.
 */
private class WindowMover(private val state: DebugWindowsState, private val entry: WindowEntry) {

    var minSize = Size(0f, 0f)
    var rtl = false

    /** Whether the window is in the dock layout, where the pane decides where it is and how big. */
    var docked = false

    var stickX by mutableStateOf(0f)
    var stickY by mutableStateOf(0f)

    private val placement get() = entry.placement

    // Where a drag started and how far the pointer has gone since, before any limit: dragging past
    // the edge of the screen and back does not move the window until the pointer is back at it.
    private var fromX = 0f
    private var fromY = 0f
    private var fromWidth = 0f
    private var fromHeight = 0f
    private var byX = 0f
    private var byY = 0f

    fun moveStart() {
        fromX = placement.x
        fromY = placement.y
        byX = 0f
        byY = 0f
    }

    fun move(delta: Offset) {
        byX += delta.x
        byY += delta.y
        placement.x = clampX(fromX + if (rtl) -byX else byX, measuredWidth())
        placement.y = clampY(fromY + byY)
    }

    fun end() = state.save()

    fun resizeStart() {
        moveStart()
        fromWidth = measuredWidth()
        fromHeight = measuredHeight()
    }

    fun resize(edge: WindowEdge, delta: Offset) {
        byX += delta.x
        byY += delta.y
        val maxWidth = placement.screenWidth.takeIf { it > 0f } ?: Float.POSITIVE_INFINITY
        val maxHeight = placement.screenHeight.takeIf { it > 0f } ?: Float.POSITIVE_INFINITY
        var width = fromWidth
        var height = fromHeight
        var x = fromX
        var y = fromY
        if (edge.right) width = fromWidth + byX
        if (edge.left) width = fromWidth - byX
        if (edge.bottom) height = fromHeight + byY
        if (edge.top) height = fromHeight - byY
        width = width.coerceIn(min(minSize.width, maxWidth), maxWidth)
        height = height.coerceIn(min(minSize.height, maxHeight), maxHeight)
        // The edge being dragged moves and the one across from it stays put. The position is measured
        // from the start edge, which is the right on a right-to-left screen. A moving start edge is
        // kept on the screen the same way a drag of the whole window is, so what the picture shows is
        // what the placement says; otherwise the edge would stop at the screen while the placement went
        // on past it, and the next drag would spend itself bringing the two back together.
        val startEdgeMoves = if (rtl) edge.right else edge.left
        if (startEdgeMoves) {
            val far = fromX + fromWidth
            x = clampX(far - width, width)
            // The far edge stays put, so what the clamp took off the near edge comes off the size too.
            width = (far - x).coerceIn(min(minSize.width, maxWidth), maxWidth)
        }
        if (edge.top) {
            val bottom = fromY + fromHeight
            y = clampY(bottom - height)
            height = (bottom - y).coerceIn(min(minSize.height, maxHeight), maxHeight)
        }
        placement.width = width
        placement.height = height
        placement.x = x
        placement.y = y
    }

    /**
     * Ctrl and an arrow moves; Ctrl, Shift and an arrow resizes from the bottom and end edges; Ctrl,
     * Alt and an arrow docks the window against that edge of the screen, and Ctrl, Alt and F floats
     * it again. Command stands in for Ctrl on a Mac.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return false
        if (event.modifiers == Modifiers.Primary + Modifiers.Alt) {
            if (event.key == Key.F) {
                state.undock(entry.id)
                return true
            }
            val side = when (event.key) {
                Key.Left -> DockSide.Left
                Key.Right -> DockSide.Right
                Key.Up -> DockSide.Top
                Key.Down -> DockSide.Bottom
                else -> return false
            }
            state.dockToScreen(entry.id, side)
            return true
        }
        // A docked window is where its pane is: the keys that move and resize a floating one have
        // nothing to do, and the dividers are what change a pane.
        if (docked) return false
        val (dx, dy) = when (event.key) {
            Key.Left -> -KeyStep to 0f
            Key.Right -> KeyStep to 0f
            Key.Up -> 0f to -KeyStep
            Key.Down -> 0f to KeyStep
            else -> return false
        }
        val primary = Modifiers.Primary
        when (event.modifiers) {
            primary -> {
                moveStart()
                move(Offset(dx, dy))
            }
            primary + Modifiers.Shift -> {
                if (placement.collapsed) return true
                resizeStart()
                resize(if (rtl) WindowEdge.BottomLeft else WindowEdge.BottomRight, Offset(dx, dy))
            }
            else -> return false
        }
        state.save()
        return true
    }

    fun onPad(event: GamepadEvent): Boolean {
        // A pad pulled out while the stick is over never says the stick came back; this is the word
        // of it, and the only one there will be.
        if (event is GamepadEvent.Disconnected) {
            letGoOfStick()
            return false
        }
        if (event !is GamepadEvent.Axis) return false
        // The stick moves a floating window. A docked one is where its pane is, so the stick is left
        // to whatever else wants it.
        if (docked) return false
        when (event.axis) {
            GamepadAxis.RightX -> stickX = event.value
            GamepadAxis.RightY -> stickY = event.value
            else -> return false
        }
        return true
    }

    /**
     * Forgets the stick, so the window stops.
     *
     * A stick is only heard while focus is inside the window, so any other moment — focus leaving, a
     * pad pulled out, the windows put away — is one where the tilt still remembered is a lie the
     * window would otherwise drift on for ever, pinned to the edge of the screen and undraggable.
     */
    fun letGoOfStick() {
        stickX = 0f
        stickY = 0f
    }

    /** Moves by the stick for [seconds]. */
    fun drift(seconds: Float) {
        placement.x = clampX(placement.x + (if (rtl) -stickX else stickX) * StickSpeed * seconds, measuredWidth())
        placement.y = clampY(placement.y + stickY * StickSpeed * seconds)
    }

    /** How wide the window really is: what layout made it, or what it asked for before it was laid out. */
    private fun measuredWidth(): Float {
        val frame = entry.frame
        if (frame != null && frame.everMeasured) return frame.width
        return placement.width.takeIf { !it.isNaN() } ?: 0f
    }

    private fun measuredHeight(): Float {
        val frame = entry.frame
        if (frame != null && frame.everMeasured) return frame.height
        return placement.height.takeIf { !it.isNaN() } ?: 0f
    }

    private fun clampX(x: Float, width: Float): Float {
        val screen = placement.screenWidth
        if (screen <= 0f) return x
        return x.coerceIn(min(KeepOnScreen - width, 0f), max(screen - KeepOnScreen, 0f))
    }

    private fun clampY(y: Float): Float {
        val screen = placement.screenHeight
        if (screen <= 0f) return y
        return y.coerceIn(0f, max(screen - KeepOnScreen, 0f))
    }
}

@Composable
private fun WindowFrame(
    state: DebugWindowsState,
    entry: WindowEntry,
    chrome: WindowChrome,
    content: @Composable DebugWindowScope.() -> Unit,
) {
    val placement = entry.placement
    val style = chrome.style
    val direction = LocalLayoutDirection.current
    val mover = remember(entry) { WindowMover(state, entry) }
    mover.minSize = chrome.minSize
    mover.rtl = direction == LayoutDirection.Rtl

    // Where it is docked, or null while it floats. A window behind another tab in its pane is still
    // composed, at no size at all, so what is in it — a scroll position, a half-typed number — is
    // still there when its tab is chosen again.
    val slot = state.dockSlotOf(entry.id)
    val docked = slot != null
    val showing = slot == null || slot.selected == entry.id
    mover.docked = docked
    SideEffect { entry.title = chrome.title }

    // The window is lit while focus is anywhere in it, including on the frame itself — which is where
    // a press on the title bar puts it. A focus-within handler is only told about the nodes inside,
    // so the frame's own focus is read from its interaction state.
    var withinBody by remember(entry) { mutableStateOf(false) }
    val frameFocus = remember(entry) { InteractionState() }
    val active = withinBody || frameFocus.isFocused
    val within = remember(entry) {
        FocusWithinHandler { inside ->
            withinBody = inside
            // Focus leaving takes the pad with it: the stick's return to centre goes wherever focus
            // went, so what is remembered here stops being true the moment focus is elsewhere.
            if (inside) state.bringToFront(entry) else mover.letGoOfStick()
        }
    }
    // The frame takes whatever reaches it — a press, a move, a scroll — so nothing goes through a
    // window to the game underneath. A press raises it on the way.
    val frameInput = remember(entry) {
        PointerHandler { event ->
            if (event is PointerEvent.Press) state.raise(entry)
            true
        }
    }
    // On the title bar and the edges, which take a press themselves to drag: raised, and let through.
    val front = remember(entry) {
        PointerHandler { event ->
            if (event is PointerEvent.Press) state.raise(entry)
            false
        }
    }
    val keys = remember(mover) { KeyHandler { mover.onKey(it) } }
    val pad = remember(mover) { GamepadHandler { mover.onPad(it) } }
    val placedFrame = remember(entry) { PlacedHandler { entry.frame = it } }
    val placedBody = remember(entry) { PlacedHandler { entry.body = it } }

    // Docking while the stick is over — Ctrl, Alt and an arrow, or the window's own menu — leaves a
    // tilt nothing will take back: the stick is not heard by a docked window, so it would go on
    // drifting the floating placement nobody can see, and undocking later would put the window
    // somewhere the player never left it.
    LaunchedEffect(mover, docked) { if (docked) mover.letGoOfStick() }

    // A window put away is not on the screen to be moved, and hears nothing more from the pad.
    val hidden = state.hidden
    val drifting = mover.stickX != 0f || mover.stickY != 0f
    LaunchedEffect(mover, drifting, hidden) {
        if (!drifting) return@LaunchedEffect
        if (hidden) {
            mover.letGoOfStick()
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        try {
            while (true) {
                withFrameNanos { now ->
                    mover.drift((now - last) / 1_000_000_000f)
                    last = now
                }
            }
        } finally {
            state.save()
        }
    }

    // A docked window is the size of its pane, and folding means nothing there: the pane is what it
    // is, and the tab is what puts it away.
    val collapsed = placement.collapsed && !docked
    val fixedWidth = docked || !placement.width.isNaN()
    val fixedHeight = docked || (!placement.height.isNaN() && !collapsed)
    val scope = remember(state, entry.id, chrome.labelWidth, style) { DebugWindowScope(state, entry.id, chrome.labelWidth, style) }
    val frameStyle = rememberStyle(if (active) "$style.active" else style)

    val toggleCollapse = remember(entry) {
        {
            val folding = !entry.placement.collapsed
            // Focus in the part about to go comes back to the triangle rather than being lost with it.
            val focus = entry.root.findFocusManager()
            val body = entry.body
            if (folding && focus != null && body != null && focus.focused?.isInside(body) == true) {
                entry.collapseButton?.let { focus.focusOn(it) }
            }
            state.setCollapsed(entry.id, folding)
        }
    }

    Layout(
        modifier = Modifier.fillMaxSize(),
        name = "debugwindow.place",
        measurePolicy = if (slot != null) {
            DockPlacementPolicy(placement, slot.steps, showing)
        } else {
            WindowPlacementPolicy(
                placement,
                placement.x,
                placement.y,
                placement.width,
                if (fixedHeight) placement.height else Float.NaN,
                chrome.minSize.width,
            )
        },
        content = {
            Layout(
                modifier = chrome.modifier
                    // Behind another tab: drawn at nothing and, being transparent, out of reach of
                    // the keyboard and the pad as well, the way a `Tabs` page that is not showing is.
                    .then(if (showing) Modifier else Modifier.alpha(0f))
                    .focusableByPointer(frameFocus)
                    .onPointer(frameInput)
                    .onFocusWithin(within)
                    .onKeyEvent(keys)
                    .onGamepadEvent(pad)
                    .onPlaced(placedFrame)
                    .styled(frameStyle)
                    .testTag(DebugWindowTags.window(entry.id)),
                name = "debugwindow",
                measurePolicy = FramePolicy,
                content = {
                    Column(
                        (if (fixedWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max))
                            .then(if (fixedHeight) Modifier.fillMaxHeight() else Modifier),
                    ) {
                        // A docked pane wears one strip of tabs, drawn by the window showing in it,
                        // so the windows behind it draw no second strip over the first.
                        if (slot == null) {
                            TitleBar(state, entry, chrome, active, collapsed, mover, front, toggleCollapse)
                        } else if (showing) {
                            TabStrip(state, entry, chrome, slot, active)
                        }
                        if (!collapsed) {
                            chrome.menuBar?.let { menus -> MenuBar(Modifier.fillMaxWidth(), content = menus) }
                            ScrollArea(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (fixedHeight) Modifier.weight(1f) else Modifier)
                                    .onPlaced(placedBody)
                                    .testTag(DebugWindowTags.body(entry.id)),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().styled("$style.body"),
                                    verticalArrangement = Arrangement.spacedBy(RowGap),
                                ) {
                                    scope.content()
                                }
                            }
                        }
                    }
                    // A docked window is resized by the dividers between the panes, not by its own
                    // edges: an edge of it is an edge of the pane, and the pane is what moves.
                    if (!collapsed && !docked) {
                        WindowEdge.entries.forEach { edge -> EdgeHandle(entry, edge, mover, front, style, direction) }
                    }
                },
            )
        },
    )
}

/** Between one line of a window and the next. */
internal const val RowGap = 4f

private val NoClick: () -> Unit = {}

@Composable
private fun TitleBar(
    state: DebugWindowsState,
    entry: WindowEntry,
    chrome: WindowChrome,
    active: Boolean,
    collapsed: Boolean,
    mover: WindowMover,
    front: PointerHandler,
    toggleCollapse: () -> Unit,
) {
    val style = chrome.style
    val bar = rememberStyle(if (active) "$style.title.active" else "$style.title")
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    val placedCollapse = remember(entry) { PlacedHandler { entry.collapseButton = it } }
    // Where the bar is on the screen, so a drag of it knows where the pointer is and not only how
    // far it has come: the drop squares are aimed at, and aiming needs a place.
    val here = remember(entry) { NodeHere() }
    val placedBar = remember(here) { PlacedHandler { here.node = it } }
    val start = remember(mover, here, rtl) {
        { at: Offset ->
            mover.moveStart()
            state.startDockDrag(entry.id, here.inRoot(at), tab = false, rtl = rtl)
        }
    }
    val drag = remember(mover) {
        { delta: Offset ->
            mover.move(delta)
            state.moveDockDrag(delta)
        }
    }
    val end = remember(mover) {
        {
            mover.end()
            state.endDockDrag()
        }
    }
    val cancel = remember(mover) {
        {
            mover.end()
            state.cancelDockDrag()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPointer(front)
            .clickable(onDoubleClick = toggleCollapse, onClick = NoClick)
            .draggable(onDragStart = start, onDragEnd = end, onDragCancel = cancel, onDrag = drag)
            .pointerHoverIcon(PointerIcon.Move)
            .onPlaced(placedBar)
            .dockMenu(state, entry.id, docked = false, onClose = chrome.onClose)
            .styled(bar)
            .testTag(DebugWindowTags.title(entry.id)),
        horizontalArrangement = Arrangement.spacedBy(TitleGap),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        WindowButton("$style.button", Modifier.onPlaced(placedCollapse).testTag(DebugWindowTags.collapse(entry.id)), toggleCollapse) { colour ->
            remember(colour, collapsed, direction) { collapseGlyph(colour, collapsed, direction) }
        }
        Text(
            chrome.title,
            Modifier.weight(1f),
            style = if (active) "$style.title.active" else "$style.title",
            softWrap = false,
            maxLines = 1,
        )
        val close = chrome.onClose
        if (close != null) {
            WindowButton("$style.button", Modifier.testTag(DebugWindowTags.close(entry.id)), close) { colour ->
                remember(colour) { crossGlyph(colour) }
            }
        }
    }
}

private const val TitleGap = 6f

/** The triangle and the cross: a small focusable button drawing [glyph] in its style's text colour. */
@Composable
private fun WindowButton(
    style: String,
    modifier: Modifier,
    onClick: () -> Unit,
    glyph: @Composable (Colour) -> (UiCanvas.(Rect) -> Unit),
) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val resolved = rememberStyle(style, states)
    Box(
        modifier
            .interaction(interaction)
            .focusable(interaction)
            .clickable(onClick = onClick)
            .styled(resolved),
    ) {
        LeafLayout(Modifier.size(GlyphSize), name = "debugwindow.glyph", draw = glyph(resolved.textColour))
    }
}

/** Pointing down while open; along the line while folded — right, or left on a right-to-left screen. */
private fun collapseGlyph(colour: Colour, collapsed: Boolean, direction: LayoutDirection): UiCanvas.(Rect) -> Unit = when {
    !collapsed -> { box ->
        val top = box.top + box.height * 0.2f
        val bottom = box.bottom - box.height * 0.2f
        fan(floatArrayOf(box.left, top, box.right, top, (box.left + box.right) / 2f, bottom), colour)
    }
    direction == LayoutDirection.Ltr -> { box ->
        val left = box.left + box.width * 0.2f
        val right = box.right - box.width * 0.2f
        fan(floatArrayOf(left, box.top, right, (box.top + box.bottom) / 2f, left, box.bottom), colour)
    }
    else -> { box ->
        val left = box.left + box.width * 0.2f
        val right = box.right - box.width * 0.2f
        fan(floatArrayOf(right, box.top, left, (box.top + box.bottom) / 2f, right, box.bottom), colour)
    }
}

private fun crossGlyph(colour: Colour): UiCanvas.(Rect) -> Unit = { box ->
    line(Offset(box.left, box.top), Offset(box.right, box.bottom), 1.5f, colour)
    line(Offset(box.right, box.top), Offset(box.left, box.bottom), 1.5f, colour)
}

/** The second arm of a corner's L: the one down its side, the first being the one along its top. */
private data class CornerArm(val edge: WindowEdge)

/** A strip along one edge, or the L at one corner, that a drag resizes the window from. */
@Composable
private fun EdgeHandle(
    entry: WindowEntry,
    edge: WindowEdge,
    mover: WindowMover,
    front: PointerHandler,
    style: String,
    direction: LayoutDirection,
) {
    val start = remember(mover) { { _: Offset -> mover.resizeStart() } }
    val drag = remember(mover, edge) { { delta: Offset -> mover.resize(edge, delta) } }
    val end = remember(mover) { { mover.end() } }
    // The grip is drawn in the bottom corner on the end side, where a player looks for it.
    val grip = edge == if (direction == LayoutDirection.Rtl) WindowEdge.BottomLeft else WindowEdge.BottomRight
    val corner = (edge.left || edge.right) && (edge.top || edge.bottom)

    GrabHandle(edge, edge.icon, start, drag, end, front, grip, style, DebugWindowTags.edge(entry.id, edge))
    // Both arms resize the same way, so which one a press lands on makes no difference.
    if (corner) GrabHandle(CornerArm(edge), edge.icon, start, drag, end, front, grip, style, tag = null)
}

/** One grab area on the frame: a whole side, or one arm of a corner. [FramePolicy] sizes it by its id. */
@Composable
private fun GrabHandle(
    id: Any,
    icon: PointerIcon,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    front: PointerHandler,
    grip: Boolean,
    style: String,
    tag: String?,
) {
    var modifier = Modifier
        .onPointer(front)
        .draggable(slop = 0f, onDragStart = onDragStart, onDragEnd = onDragEnd, onDrag = onDrag)
        .pointerHoverIcon(icon)
        .layoutId(id)
    if (grip) modifier = modifier.styled("$style.grip")
    if (tag != null) modifier = modifier.testTag(tag)
    LeafLayout(modifier, name = "debugwindow.edge")
}

/**
 * Where a window goes on the host: at its position, kept on the screen, at its size or the size of
 * what is in it. Everything it reads is in the policy, so a window that moves is a new policy.
 */
private data class WindowPlacementPolicy(
    private val placement: WindowPlacement,
    private val x: Float,
    private val y: Float,
    private val width: Float,
    private val height: Float,
    private val minWidth: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val screenWidth = constraints.maxWidth.takeIf { it.isFinite() } ?: constraints.minWidth
        val screenHeight = constraints.maxHeight.takeIf { it.isFinite() } ?: constraints.minHeight
        placement.screenWidth = screenWidth
        placement.screenHeight = screenHeight
        if (measurables.isEmpty()) return layout(screenWidth, screenHeight) {}

        // The top is known before the frame is measured — it is only the position, kept on the screen —
        // so a window still the size of what is in it is given the room below it rather than the whole
        // screen, and its bottom edge, with the grip on it, stays somewhere the player can reach.
        val top = y.coerceIn(0f, max(screenHeight - KeepOnScreen, 0f))
        val below = max(screenHeight - top, 0f)
        val across = if (width.isNaN()) {
            Constraints(min(minWidth, screenWidth), screenWidth, 0f, below)
        } else {
            val w = width.coerceIn(0f, screenWidth)
            Constraints(w, w, 0f, below)
        }
        val room = if (height.isNaN()) across else {
            // A size the player chose is theirs, wherever they then drag the window to.
            val h = height.coerceIn(0f, screenHeight)
            across.copy(minHeight = h, maxHeight = h)
        }
        val frame = measurables[0].measure(room)
        val left = x.coerceIn(min(KeepOnScreen - frame.width, 0f), max(screenWidth - KeepOnScreen, 0f))
        val placedX = if (layoutDirection == LayoutDirection.Rtl) screenWidth - left - frame.width else left
        return layout(screenWidth, screenHeight) { frame.at(placedX, top) }
    }
}

/** The window's contents at their size, and the resize handles laid along its edges over them. */
private object FramePolicy : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (measurables.isEmpty()) return layout(constraints.minWidth, constraints.minHeight) {}
        val contents = measurables[0].measure(constraints)
        val width = constraints.constrainWidth(contents.width)
        val height = constraints.constrainHeight(contents.height)
        val handles = measurables.drop(1).map { measurable ->
            val id = measurable.layoutId
            val edge = id as? WindowEdge ?: (id as? CornerArm)?.edge
            val corner = edge != null && (edge.left || edge.right) && (edge.top || edge.bottom)
            // A corner's two arms: the one along its top or bottom is long across and thin down, the
            // one down its side the other way about.
            val down = id is CornerArm
            val w = when {
                edge == null -> 0f
                corner -> if (down) EdgeGrab else CornerGrab
                edge.left || edge.right -> EdgeGrab
                else -> width
            }
            val h = when {
                edge == null -> 0f
                corner -> if (down) CornerGrab else EdgeGrab
                edge.top || edge.bottom -> EdgeGrab
                else -> height
            }
            val placeable = measurable.measure(Constraints.fixed(min(w, width), min(h, height)))
            val x = if (edge?.right == true) width - placeable.width else 0f
            val y = if (edge?.bottom == true) height - placeable.height else 0f
            Triple(placeable, x, y)
        }
        return layout(width, height) {
            contents.at(0f, 0f)
            handles.forEach { (placeable, x, y) -> placeable.at(x, y) }
        }
    }
}

// --- docking -------------------------------------------------------------------------------------

/**
 * Where a node ended up, kept between frames so a drag started on it can say where the pointer is.
 *
 * A drag is told how far the pointer went, not where it went, which is all a window being moved
 * needs; a window being docked needs the point itself, to say which square it is over.
 */
private class NodeHere {

    var node: UiNode? = null

    /** A point in the node's own coordinates, in the host's. */
    fun inRoot(at: Offset): Offset {
        val corner = node?.layoutBoundsInRoot?.topLeft ?: Offset.Zero
        return corner + at
    }
}

/**
 * The menu on a title bar or a strip of tabs: where to dock this window, and how to float it again.
 *
 * The same commands the drags do, reachable without one: a right click, a long press, Shift+F10 from
 * the keyboard and the pad's North button all open it, so docking is not a mouse-only feature.
 */
private fun Modifier.dockMenu(
    state: DebugWindowsState,
    id: String,
    docked: Boolean,
    onClose: (() -> Unit)?,
): Modifier = contextMenu {
    Submenu("&Dock") {
        DockSide.entries.forEach { side ->
            Item(side.label, shortcut = KeyShortcut(side.key, DockModifiers)) { state.dockToScreen(id, side) }
        }
    }
    Item("&Float", shortcut = KeyShortcut(Key.F, DockModifiers), enabled = docked) { state.undock(id) }
    if (onClose != null) {
        Separator()
        Item("&Close") { onClose() }
    }
}

/** Ctrl and Alt, or Command and Alt on a Mac: the window's own keys, clear of anything in it. */
private val DockModifiers: Modifiers get() = Modifiers.Primary + Modifiers.Alt

/** What the Dock menu calls each side, with the letter that chooses it. */
private val DockSide.label: String
    get() = when (this) {
        DockSide.Left -> "&Left"
        DockSide.Right -> "&Right"
        DockSide.Top -> "&Top"
        DockSide.Bottom -> "&Bottom"
    }

/** The arrow that docks a window that way. */
private val DockSide.key: Key
    get() = when (this) {
        DockSide.Left -> Key.Left
        DockSide.Right -> Key.Right
        DockSide.Top -> Key.Up
        DockSide.Bottom -> Key.Down
    }

/**
 * The strip a docked pane wears in place of a title bar: one tab per window in the pane, the one
 * showing drawn chosen.
 *
 * Drawn by the window showing, for all of them, because a pane is one strip however many windows are
 * tabbed into it. A click on a tab brings that window forward; a drag of one pulls that window out,
 * wherever it is dropped.
 */
@Composable
private fun TabStrip(
    state: DebugWindowsState,
    entry: WindowEntry,
    chrome: WindowChrome,
    slot: DockSlot,
    active: Boolean,
) {
    val style = chrome.style
    val bar = rememberStyle(if (active) "$style.title.active" else "$style.title")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dockMenu(state, entry.id, docked = true, onClose = chrome.onClose)
            .styled(bar)
            .testTag(DebugWindowTags.tabs(entry.id)),
        horizontalArrangement = Arrangement.spacedBy(TitleGap),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        slot.tabs.forEach { id ->
            key(id) { Tab(state, id, selected = id == slot.selected, style = style) }
        }
        Spacer(Modifier.weight(1f))
        val close = chrome.onClose
        if (close != null) {
            WindowButton("$style.button", Modifier.testTag(DebugWindowTags.close(entry.id)), close) { colour ->
                remember(colour) { crossGlyph(colour) }
            }
        }
    }
}

/** One tab in a pane's strip: a click brings its window forward, a drag pulls it out of the dock. */
@Composable
private fun Tab(state: DebugWindowsState, id: String, selected: Boolean, style: String) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val name = if (selected) "$style.tab.selected" else "$style.tab"
    val resolved = rememberStyle(name, states)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val here = remember { NodeHere() }
    val placed = remember(here) { PlacedHandler { here.node = it } }
    val choose = remember(state, id) { { state.bringToFront(id) } }
    val start = remember(state, id, here, rtl) {
        { at: Offset -> state.startDockDrag(id, here.inRoot(at), tab = true, rtl = rtl) }
    }
    val drag = remember(state) { { delta: Offset -> state.moveDockDrag(delta) } }
    val end = remember(state) { { state.endDockDrag() } }
    val cancel = remember(state) { { state.cancelDockDrag() } }

    Box(
        Modifier
            .interaction(interaction)
            .focusable(interaction)
            .clickable(onClick = choose)
            .draggable(onDragStart = start, onDragEnd = end, onDragCancel = cancel, onDrag = drag)
            .pointerHoverIcon(PointerIcon.Move)
            .onPlaced(placed)
            .styled(resolved)
            .testTag(DebugWindowTags.tab(id)),
    ) {
        Text(state.titleOf(id), style = name, softWrap = false, maxLines = 1)
    }
}

/**
 * Where a docked window goes: the pane its steps lead to, out of the whole host.
 *
 * The steps come from composition and the room from layout, because only layout knows how big the
 * screen is. A window behind another tab is measured at nothing at all: it keeps everything it
 * remembers, and takes up none of the pane its neighbour is showing in.
 */
private data class DockPlacementPolicy(
    private val placement: WindowPlacement,
    private val steps: List<DockStep>,
    private val showing: Boolean,
) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val screenWidth = constraints.maxWidth.takeIf { it.isFinite() } ?: constraints.minWidth
        val screenHeight = constraints.maxHeight.takeIf { it.isFinite() } ?: constraints.minHeight
        placement.screenWidth = screenWidth
        placement.screenHeight = screenHeight
        if (measurables.isEmpty()) return layout(screenWidth, screenHeight) {}
        if (!showing) {
            val behind = measurables[0].measure(Constraints.fixed(0f, 0f))
            return layout(screenWidth, screenHeight) { behind.at(0f, 0f) }
        }
        // The rectangle is already the one on the screen, mirrored screens included: a pane docked
        // left is on the left whichever way the text reads.
        val pane = dockRect(steps, Rect.of(0f, 0f, screenWidth, screenHeight))
        val frame = measurables[0].measure(Constraints.fixed(max(pane.width, 0f), max(pane.height, 0f)))
        return layout(screenWidth, screenHeight) { frame.at(pane.left, pane.top) }
    }
}

/** The dividers between docked panes: one grab per split, in the gap between the two sides of it. */
@Composable
private fun DockDividers(state: DebugWindowsState) {
    val dividers = state.dockDividers()
    if (dividers.isEmpty()) return
    Layout(
        modifier = Modifier.fillMaxSize().zIndex(DividerZ),
        name = "debugwindow.dividers",
        measurePolicy = AtRects,
        content = {
            dividers.forEachIndexed { index, divider ->
                key(divider.path) { DockDivider(state, divider, index) }
            }
        },
    )
}

@Composable
private fun DockDivider(state: DebugWindowsState, divider: DockDividerAt, index: Int) {
    val horizontal = divider.orientation == Orientation.Horizontal
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val resolved = rememberStyle(DividerStyle, states)
    val drag = remember(state) { DividerDrag(state) }
    drag.divider = divider
    val directions = remember(drag) { DirectionHandler { drag.nudge(it) } }
    val start = remember(drag) { { _: Offset -> drag.start() } }
    val move = remember(drag) { { delta: Offset -> drag.move(delta) } }
    val end = remember(state) { { state.save() } }
    val halve = remember(drag) { { drag.halve() } }

    LeafLayout(
        Modifier
            .layoutId(divider.rect)
            .interaction(interaction)
            .focusable(interaction)
            .onFocusDirection(directions)
            // Clickable for its double click, as a splitter's divider is, and so that a press that
            // never moves is the divider's rather than falling through to the game under it.
            .clickable(onDoubleClick = halve, onClick = NoClick)
            .draggable(slop = 0f, onDragStart = start, onDragEnd = end, onDrag = move)
            .pointerHoverIcon(if (horizontal) PointerIcon.ResizeHorizontal else PointerIcon.ResizeVertical)
            .styled(resolved)
            .testTag(DebugWindowTags.divider(index)),
        name = "debugwindow.divider",
    )
}

/** A divider is drawn in the toolkit's own splitter style: between two panes, it is one. */
private const val DividerStyle = "splitter"

/**
 * The arithmetic of moving a divider, in pixels, from what layout last measured — a window's drag
 * written for a split.
 */
private class DividerDrag(private val state: DebugWindowsState) {

    /** The divider as it is now, written every recomposition, so a drag reads the pane it is in today. */
    var divider: DockDividerAt? = null

    private var from = 0f
    private var by = 0f

    fun start() {
        val divider = this.divider ?: return
        from = nearSpan(span(divider), divider.fraction)
        by = 0f
    }

    fun move(delta: Offset) {
        val divider = this.divider ?: return
        by += if (divider.orientation == Orientation.Horizontal) delta.x else delta.y
        moveTo(divider, from + by)
    }

    /**
     * An arrow or the pad moves it a step the way it points. At the end of its travel it takes
     * nothing, so one more press carries focus off it rather than grinding against the end.
     */
    fun nudge(direction: FocusDirection): Boolean {
        val divider = this.divider ?: return false
        val horizontal = divider.orientation == Orientation.Horizontal
        val step = when (direction) {
            FocusDirection.Left -> if (horizontal) -KeyStep else return false
            FocusDirection.Right -> if (horizontal) KeyStep else return false
            FocusDirection.Up -> if (horizontal) return false else -KeyStep
            FocusDirection.Down -> if (horizontal) return false else KeyStep
            else -> return false
        }
        if (!moveTo(divider, nearSpan(span(divider), divider.fraction) + step)) return false
        state.save()
        return true
    }

    /** A double click shares the space out evenly, as a double click on a splitter's divider does. */
    fun halve() {
        val divider = this.divider ?: return
        if (state.moveDivider(divider.path, 0.5f)) state.save()
    }

    /** True when the divider really moved; at either end it does not, and says so. */
    private fun moveTo(divider: DockDividerAt, near: Float): Boolean {
        val total = span(divider)
        val room = max(total - DockDividerThickness, 0f)
        if (room <= 0f) return false
        val wanted = (near / room).coerceIn(0f, 1f)
        if (nearSpan(total, wanted) == nearSpan(total, divider.fraction)) return false
        return state.moveDivider(divider.path, wanted)
    }

    private fun span(divider: DockDividerAt): Float =
        if (divider.orientation == Orientation.Horizontal) divider.area.width else divider.area.height
}

/**
 * The squares a dragged window is dropped on, and the patch showing where it would land.
 *
 * Only while something is being dragged, and only the squares dock: a window dragged across the top
 * of the screen is being moved, and docks when it is let go on a square. The four round the edges
 * dock against the screen; the cross over a window docks into that window's pane, its middle square
 * tabbing the two together.
 */
@Composable
private fun DockDropTargets(state: DebugWindowsState) {
    val drag = state.dockDrag ?: return
    val screen = state.screenRect()
    if (screen.isEmpty) return
    val windows = state.dropTargets(drag.id)
    val pointer = drag.pointer
    val over = hoveredWindow(pointer, windows)
    val drop = dropTargetAt(pointer, screen, windows)

    Layout(
        modifier = Modifier.fillMaxSize().zIndex(DropTargetZ),
        name = "debugwindow.dock",
        measurePolicy = AtRects,
        content = {
            if (drop != null) {
                LeafLayout(
                    Modifier
                        .layoutId(state.dropPreview(drag.id, drop))
                        .styled(PreviewStyle)
                        .testTag(DebugWindowTags.DropPreview),
                    name = "debugwindow.dock.preview",
                )
            }
            screenMarkers(screen).forEach { (side, rect) ->
                DropSquare(rect, drop == DockDrop(null, side), DebugWindowTags.screenDrop(side))
            }
            if (over != null) {
                windowMarkers(over.second).forEach { (side, rect) ->
                    DropSquare(rect, drop == DockDrop(over.first, side), DebugWindowTags.windowDrop(over.first, side))
                }
            }
        },
    )
}

@Composable
private fun DropSquare(rect: Rect, under: Boolean, tag: String) {
    LeafLayout(
        Modifier
            .layoutId(rect)
            .styled(if (under) "$TargetStyle.active" else TargetStyle)
            .testTag(tag),
        name = "debugwindow.dock.target",
    )
}

private const val PreviewStyle = "debugwindow.dock"
private const val TargetStyle = "debugwindow.dock.target"

/** Every child at the rectangle its layout id names, over the whole host. */
private object AtRects : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth.takeIf { it.isFinite() } ?: constraints.minWidth
        val height = constraints.maxHeight.takeIf { it.isFinite() } ?: constraints.minHeight
        val placed = measurables.map { measurable ->
            val rect = measurable.layoutId as? Rect ?: Rect.Zero
            measurable.measure(Constraints.fixed(max(rect.width, 0f), max(rect.height, 0f))) to rect
        }
        return layout(width, height) { placed.forEach { (placeable, rect) -> placeable.at(rect.left, rect.top) } }
    }
}

// --- finding things ------------------------------------------------------------------------------

/** The focus manager over the tree [this] is in, or null for a tree nobody built one over. */
internal fun UiNode.findFocusManager(): FocusManager? {
    var walk: UiNode? = this
    while (walk != null) {
        walk.focusManager?.let { return it }
        walk = walk.parent
    }
    return null
}

/** Whether [this] is [ancestor] or somewhere inside it. */
internal fun UiNode.isInside(ancestor: UiNode): Boolean {
    var walk: UiNode? = this
    while (walk != null) {
        if (walk === ancestor) return true
        walk = walk.parent
    }
    return false
}
