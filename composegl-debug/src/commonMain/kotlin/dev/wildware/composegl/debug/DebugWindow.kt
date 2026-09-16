package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
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
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.layout.layoutId
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.focusableByPointer
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
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
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Popups
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text
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
                    .onPlaced(placed),
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
 * its title bar, which is in front, and whether they are all hidden.
 *
 * Snapshot state, so a game can read it to draw a Windows menu, and change it from anywhere. What it
 * holds is written to [store] whenever a window stops moving or changes size, and when a window or a
 * section in one is folded or unfolded.
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
     * Every window back where the code puts it, at the size of what is in it and unfolded, and every
     * section in them back to how it starts. What was saved is forgotten too.
     */
    fun resetLayout() {
        placements.values.forEach { it.reset() }
        sections.clear()
        saved.clear()
        save()
    }

    /**
     * Focus into the next window along, first shown first, and that window to the front. From the last
     * window focus goes back to where it was in the game. True when focus moved.
     */
    fun focusNextWindow(): Boolean {
        val focus = hostNode?.findFocusManager() ?: return false
        val showing = if (hidden) emptyList() else entries.filter { it.root.parent != null }
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
        val next = if (hidden) null else order.lastOrNull { it.root.parent != null }
        if (next != null) focusInto(next, focus, keepReturn = false) else handBack(focus)
    }

    /** Draws [entry] over the others, and takes focus into it. See [bringToFront]. */
    internal fun raise(entry: WindowEntry) {
        bringToFront(entry)
        hostNode?.findFocusManager()?.let { focusInto(entry, it) }
    }

    internal fun bringToFront(entry: WindowEntry) {
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
        hostNode?.findFocusManager() != null && !hidden && entries.any { it.root.parent != null }

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
}

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
 * - **Keyboard.** The triangle and the cross are focusable, like every control in it. With focus
 *   anywhere inside, Ctrl and an arrow (Command on a Mac) moves it, and Ctrl, Shift and an arrow makes
 *   it bigger or smaller. Focus arriving inside brings it to the front.
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
 * front; `"<style>.title"` and `"<style>.title.active"` for the title bar; `"<style>.button"` for the
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

    /** Ctrl and an arrow moves; Ctrl, Shift and an arrow resizes from the bottom and end edges. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return false
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

    val collapsed = placement.collapsed
    val fixedWidth = !placement.width.isNaN()
    val fixedHeight = !placement.height.isNaN() && !collapsed
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
        measurePolicy = WindowPlacementPolicy(
            placement,
            placement.x,
            placement.y,
            placement.width,
            if (fixedHeight) placement.height else Float.NaN,
            chrome.minSize.width,
        ),
        content = {
            Layout(
                modifier = chrome.modifier
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
                        TitleBar(entry, chrome, active, collapsed, mover, front, toggleCollapse)
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
                    if (!collapsed) {
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
    val start = remember(mover) { { _: Offset -> mover.moveStart() } }
    val drag = remember(mover) { { delta: Offset -> mover.move(delta) } }
    val end = remember(mover) { { mover.end() } }
    val direction = LocalLayoutDirection.current
    val placedCollapse = remember(entry) { PlacedHandler { entry.collapseButton = it } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPointer(front)
            .clickable(onDoubleClick = toggleCollapse, onClick = NoClick)
            .draggable(onDragStart = start, onDragEnd = end, onDrag = drag)
            .pointerHoverIcon(PointerIcon.Move)
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
