package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InputWatcher
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.KeyShortcut
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.BoxPolicy
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LinearPolicy
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.paddingRelative
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.UiTree
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.TextDecoration
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun

// --- what a menu is made of --------------------------------------------------------------------

/** Keeps a menu's builders from reaching the scope outside them: no `Menu` inside an `Item`. */
@DslMarker
annotation class MenuDsl

/**
 * What goes in a menu: items, ticks, choices, submenus and the lines between them.
 *
 * One scope for every menu there is, so one definition works in all of them. A `MenuBar`'s menus and
 * a `Modifier.contextMenu` are written in it, and a game that wants the same Edit items in both
 * writes them once:
 *
 * ```kotlin
 * fun MenuScope.editItems() {
 *     Item("Cu&t", shortcut = Modifiers.Primary + Key.X) { cut() }
 *     Item("&Copy", shortcut = Modifiers.Primary + Key.C) { copy() }
 * }
 * ```
 *
 * It is not composable. It runs every time the menu's owner is composed, which is what lets a
 * shortcut fire while its menu is closed, and whatever state it reads recomposes the owner when it
 * changes — so `enabled = dirty` greys Save out the moment nothing needs saving.
 *
 * **Labels** may mark a letter with `&`: `"&File"` is File with F underlined, and F — or Alt+F from a
 * menu bar — picks it. `"&&"` is an ampersand. A translated label marks its own letter, so `"&Fichier"`
 * and `"&Datei"` each pick theirs.
 */
@MenuDsl
class MenuScope internal constructor() {

    internal val entries = mutableListOf<MenuEntry>()

    /**
     * Something to do.
     *
     * @param shortcut the keys that do it without opening the menu, shown right-aligned beside the label.
     *   In a `MenuBar` they work from anywhere on the screen; in a context menu they are only shown.
     * @param enabled false greys it out, and it cannot be chosen, reached or fired by its shortcut.
     * @param icon drawn in the column before the label.
     */
    fun Item(
        label: String,
        shortcut: KeyShortcut? = null,
        enabled: Boolean = true,
        icon: (@Composable () -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        entries += MenuEntry.Item(Mnemonic.of(label), shortcut, enabled, icon, MenuMark.None, onClick)
    }

    /** Something that is on or off, with a tick when it is on. Choosing it calls [onCheckedChange] with the other one. */
    fun CheckItem(
        label: String,
        checked: Boolean,
        shortcut: KeyShortcut? = null,
        enabled: Boolean = true,
        icon: (@Composable () -> Unit)? = null,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        val mark = if (checked) MenuMark.Checked else MenuMark.Unchecked
        entries += MenuEntry.Item(Mnemonic.of(label), shortcut, enabled, icon, mark) { onCheckedChange(!checked) }
    }

    /**
     * One of several, with a dot on the chosen one.
     *
     * Like `RadioButton`, which ones belong together is the game's business: the items next to each
     * other with a `selected` that reads the same state are a group.
     */
    fun RadioItem(
        label: String,
        selected: Boolean,
        shortcut: KeyShortcut? = null,
        enabled: Boolean = true,
        icon: (@Composable () -> Unit)? = null,
        onSelect: () -> Unit,
    ) {
        val mark = if (selected) MenuMark.Selected else MenuMark.Unselected
        entries += MenuEntry.Item(Mnemonic.of(label), shortcut, enabled, icon, mark, onSelect)
    }

    /**
     * A menu inside this one, opened beside its row by resting the pointer on it, by pressing it, or
     * by the arrow pointing into it.
     */
    fun Submenu(
        label: String,
        enabled: Boolean = true,
        icon: (@Composable () -> Unit)? = null,
        content: MenuScope.() -> Unit,
    ) {
        entries += MenuEntry.Submenu(Mnemonic.of(label), enabled, icon, buildMenu(content))
    }

    /** A line between two groups of items. */
    fun Separator() {
        entries += MenuEntry.Separator
    }
}

/**
 * The menus along a `MenuBar`.
 *
 * ```kotlin
 * MenuBar {
 *     Menu("&File") { Item("&New") { newLevel() } }
 *     Menu("&View") { CheckItem("&Grid", checked = showGrid) { showGrid = it } }
 * }
 * ```
 */
@MenuDsl
class MenuBarScope internal constructor() {

    internal val menus = mutableListOf<BarMenu>()

    /** A title on the bar and the menu that drops from it. Disabled, it cannot be opened and its shortcuts do not fire. */
    fun Menu(label: String, enabled: Boolean = true, content: MenuScope.() -> Unit) {
        menus += BarMenu(Mnemonic.of(label), enabled, buildMenu(content))
    }
}

internal fun buildMenu(content: MenuScope.() -> Unit): List<MenuEntry> = MenuScope().apply(content).entries.toList()

internal class BarMenu(val label: Mnemonic, val enabled: Boolean, val entries: List<MenuEntry>)

internal sealed class MenuEntry {

    class Item(
        val label: Mnemonic,
        val shortcut: KeyShortcut?,
        val enabled: Boolean,
        val icon: (@Composable () -> Unit)?,
        val mark: MenuMark,
        val activate: () -> Unit,
    ) : MenuEntry()

    class Submenu(
        val label: Mnemonic,
        val enabled: Boolean,
        val icon: (@Composable () -> Unit)?,
        val entries: List<MenuEntry>,
    ) : MenuEntry()

    data object Separator : MenuEntry()

    val mnemonic: Mnemonic? get() = (this as? Item)?.label ?: (this as? Submenu)?.label

    val usable: Boolean get() = (this as? Item)?.enabled ?: (this as? Submenu)?.enabled ?: false
}

internal enum class MenuMark { None, Unchecked, Checked, Unselected, Selected }

/**
 * A label with its `&` taken out, and which letter it marked.
 *
 * @param index where the marked letter is in [text], or -1 for a label that marks none.
 */
internal data class Mnemonic(val text: String, val index: Int) {

    /** Whether [key] is the marked letter. Case is not the player's problem. */
    fun matches(key: Key): Boolean {
        if (index !in text.indices) return false
        val letter = key.letter() ?: return false
        return text[index].uppercaseChar() == letter
    }

    /** The underline under the marked letter, for a menu showing its letters. */
    val runs: List<TextRun>
        get() = if (index in text.indices) listOf(TextRun(TextRange(index, index + 1), decoration = TextDecoration.Underline)) else emptyList()

    companion object {
        fun of(label: String): Mnemonic {
            val text = StringBuilder(label.length)
            var marked = -1
            var at = 0
            while (at < label.length) {
                val char = label[at]
                if (char == '&' && at + 1 < label.length) {
                    val next = label[at + 1]
                    if (next != '&' && marked < 0) marked = text.length
                    text.append(next)
                    at += 2
                } else {
                    text.append(char)
                    at++
                }
            }
            return Mnemonic(text.toString(), marked)
        }
    }
}

/** The letter or digit a key types, upper case, or null for any other key. */
private fun Key.letter(): Char? = when (code) {
    in Key.A.code..Key.Z.code -> 'A' + (code - Key.A.code)
    in Key.Digit0.code..Key.Digit9.code -> '0' + (code - Key.Digit0.code)
    else -> null
}

/** Fires the first usable item anywhere in these entries whose shortcut is [event]. */
internal fun List<MenuEntry>.fireShortcut(event: KeyEvent): Boolean {
    for (entry in this) {
        when (entry) {
            is MenuEntry.Item -> if (entry.enabled && entry.shortcut?.matches(event) == true) {
                entry.activate()
                return true
            }
            is MenuEntry.Submenu -> if (entry.enabled && entry.entries.fireShortcut(event)) return true
            MenuEntry.Separator -> Unit
        }
    }
    return false
}

/** The focus manager over the tree [this] is in, or null for a tree nobody built one over. */
internal fun UiNode.focusManagerOrNull(): FocusManager? {
    var walk: UiNode? = this
    while (walk != null) {
        walk.focusManager?.let { return it }
        walk = walk.parent
    }
    return null
}

/** Focus onto [node], if it is there and can take it. */
internal fun focusOnNode(node: UiNode?) {
    node?.focusManagerOrNull()?.focusOn(node)
}

// --- the menu bar -------------------------------------------------------------------------------

/**
 * A row of menus: File, Edit, View.
 *
 * ```kotlin
 * MenuBar {
 *     Menu("&File") {
 *         Item("&New", shortcut = Modifiers.Primary + Key.N) { newLevel() }
 *         Item("&Save", shortcut = Modifiers.Primary + Key.S, enabled = dirty) { save() }
 *         Submenu("&Recent") {
 *             recent.forEach { level -> Item(level.name) { open(level) } }
 *         }
 *         Separator()
 *         Item("&Quit") { quit() }
 *     }
 *     Menu("&View") {
 *         CheckItem("&Grid", checked = showGrid) { showGrid = it }
 *         Separator()
 *         RadioItem("&Wireframe", selected = mode == Wire) { mode = Wire }
 *         RadioItem("S&haded", selected = mode == Shaded) { mode = Shaded }
 *     }
 * }
 * ```
 *
 * It fills the width it is given: across the top of a screen, or across the top of a window. The
 * menus drop through the screen's [PopupHost], so one has to be round the screen.
 *
 * - **Mouse.** A click on a title opens its menu and a second click closes it. While one is open,
 *   resting on another title switches to it. A submenu opens when the pointer rests on its row, and
 *   moving diagonally from that row towards the submenu does not close it on the way, even across
 *   the rows in between.
 * - **Keyboard.** Alt on its own or F10 puts focus on the bar; the arrows move along it and Down,
 *   Enter or a title's letter opens a menu. Alt with a title's letter opens that menu from anywhere.
 *   Inside a menu the arrows move, Right opens a submenu and Left closes it, Left and Right on a menu
 *   with nowhere to go move to the next menu along, a letter chooses its item, and Escape closes one
 *   level at a time — the last one gives focus back to wherever it was before the bar had it.
 * - **Shortcuts** fire while every menu is closed, from anywhere on the screen, as long as no dialogue
 *   is open over the bar. Only a key nothing focused wanted reaches them, so a field keeps its Ctrl+A.
 * - **Pad.** [padButton] puts focus on the bar and takes it off again. The d-pad moves, South opens and
 *   chooses, the shoulders switch menus, and East or Back closes one level through `OnBack`.
 * - **Right to left**, the bar reads from the right, menus hang from a title's right edge and
 *   submenus open to the left; Left opens a submenu and Right closes it.
 *
 * Every look is the skin's: `"menubar"` behind the titles, `"menubar.title"` for a title in each state
 * and `"menubar.title.open"` for the one whose menu is open, then the menu's own — `"menu"` for the
 * panel, `"menu.item"` for a row (`"menu.item.open"` while its submenu is open), `"menu.shortcut"`,
 * `"menu.separator"`, and `"menu.check"` and `"menu.radio"` for the tick and the dot.
 *
 * @param style the bar's skin style.
 * @param titleStyle a title's skin style.
 * @param menuStyle the menus' skin style; their parts are named after it.
 * @param padButton the pad button that puts focus on the bar, or null for a bar a pad cannot reach.
 */
@Composable
fun MenuBar(
    modifier: Modifier = Modifier,
    style: String = "menubar",
    titleStyle: String = "$style.title",
    menuStyle: String = "menu",
    padButton: GamepadButton? = null,
    content: MenuBarScope.() -> Unit,
) {
    requirePopups()
    val menus = MenuBarScope().apply(content).menus.toList()
    val latestMenus = rememberUpdatedState(menus)
    val latestPad = rememberUpdatedState(padButton)
    val bar = remember { MenuBarState() }
    val anchors = remember(menus.size) { List(menus.size) { PopupAnchor() } }
    val interactions = remember(menus.size) { List(menus.size) { InteractionState() } }
    val latestAnchors = rememberUpdatedState(anchors)
    val latestInteractions = rememberUpdatedState(interactions)
    val barNode = remember { PopupAnchor() }
    val direction = LocalLayoutDirection.current

    // A menu that has gone away, or been turned off while open, is closed rather than left pointing at nothing.
    val open = bar.open
    if (open >= 0 && (open !in menus.indices || !menus[open].enabled)) SideEffect { bar.close() }

    val keys = remember(bar) {
        KeyHandler { event -> bar.onKey(event, latestMenus.value) }
    }
    val pad = remember(bar) {
        GamepadHandler { event ->
            bar.onPad(event, latestPad.value, latestMenus.value, latestAnchors.value, latestInteractions.value)
        }
    }
    val within = remember(bar) {
        FocusWithinHandler { inside ->
            bar.focusInside = inside
            if (inside && bar.active) bar.focusArrived = true
        }
    }
    // Focus went somewhere else while the bar had it — a click on the screen below — so the bar is not
    // the thing being driven any more. Decided here rather than in the handler: a step from one title
    // to the next leaves the first before it reaches the second, and only the end of that is news.
    if (bar.active && bar.focusArrived && !bar.focusInside && bar.open < 0) SideEffect { bar.deactivate() }
    // Every key and press on the screen, including the ones that never reach the bar: a field that
    // takes Alt+Left, or a click anywhere, means a lone Alt is not lone any more, and a click below
    // the bar while it has focus is the player going back to the screen.
    val watcher = remember(bar) { MenuBarWatcher(bar, barNode) }
    DisposableEffect(watcher) { onDispose { watcher.watch(null) } }
    val placed = remember(barNode, watcher) {
        PlacedHandler {
            barNode.node = it
            watcher.watch(it.tree)
        }
    }

    OnBack(enabled = bar.active && bar.open < 0) { bar.deactivate() }

    val policy = remember { LinearPolicy(true, Arrangement.Start, Alignment(vertical = VerticalAlignment.Centre)) }
    NamedLayout(
        modifier = modifier
            .fillMaxWidth()
            .styled(style)
            .focusTrap(bar.active)
            .onShortcutKey(keys)
            .onShortcutGamepad(pad)
            .onFocusWithin(within)
            .onPlaced(placed),
        name = "menubar",
        measurePolicy = policy,
    ) {
        menus.forEachIndexed { index, menu ->
            MenuTitle(
                menu = menu,
                index = index,
                bar = bar,
                anchor = anchors[index],
                interaction = interactions[index],
                style = titleStyle,
                direction = direction,
                steps = { from, by -> bar.step(latestMenus.value, from, by) },
                onFocusTitle = { focusOnNode(latestAnchors.value.getOrNull(it)?.node) },
            )
        }
    }

    if (open !in menus.indices || !menus[open].enabled) return

    key(open) {
        MenuPopup(
            entries = menus[open].entries,
            anchor = anchors[open],
            position = PopupPosition.BelowStart,
            style = menuStyle,
            mnemonics = bar.active,
            onChosen = { bar.deactivate() },
            onDismiss = {
                bar.close()
                // Back on the title it came from: the player moved along the bar to get here.
                if (bar.active) focusOnNode(anchors[open].node)
            },
            onOutside = { bar.deactivate() },
            passes = { point -> barNode.node?.boundsInRoot?.contains(point) == true },
            onSwitch = { by -> bar.step(latestMenus.value, open, by)?.let { bar.openMenu(it) } },
            keys = remember(bar) { KeyHandler { event -> bar.onKeyWhileOpen(event, latestMenus.value) } },
            pad = pad,
        )
    }
}

/** What a [MenuBar] hears of the input that goes elsewhere. See [InputWatcher]. */
private class MenuBarWatcher(private val bar: MenuBarState, private val barNode: PopupAnchor) : InputWatcher {

    private var tree: UiTree? = null

    /** Watches [tree] from now on, and whatever it watched before no longer. */
    fun watch(tree: UiTree?) {
        if (tree === this.tree) return
        this.tree?.stopWatchingInput(this)
        this.tree = tree
        tree?.watchInput(this)
    }

    override fun onKey(event: KeyEvent) = bar.heard(event)

    override fun onPress(event: PointerEvent.Press) {
        bar.pressed()
        // A press outside the bar while it has focus and nothing is open is the player going back to
        // the screen. Let go now, before focus follows the press: a bar still trapping focus would pull
        // it straight back onto a title. An open menu's popup already watches its own outside.
        if (bar.active && bar.open < 0 && barNode.node?.boundsInRoot?.contains(event.position) != true) {
            bar.deactivate()
        }
    }
}

/**
 * Everything a menu bar remembers: which menu is open, whether focus is on the bar, and which title
 * focus goes to when it arrives there.
 */
internal class MenuBarState {

    /** The open menu's index, or -1. */
    var open by mutableStateOf(-1)

    /** Whether focus is on the bar — reached with Alt, F10 or the pad — rather than on the screen. */
    var active by mutableStateOf(false)

    /** The title focus lands on when the bar is reached: the last one opened. */
    var wanted by mutableStateOf(0)

    /** Whether focus is on something in the bar now. */
    var focusInside by mutableStateOf(false)

    /** Whether focus has reached the bar since it was last made active, so its leaving means something. */
    var focusArrived = false

    /** Alt went down and nothing else has yet, so its coming up means "the bar". */
    private var altAlone = false

    fun openMenu(index: Int) {
        open = index
        wanted = index
    }

    fun close() {
        open = -1
    }

    fun deactivate() {
        open = -1
        active = false
        focusArrived = false
    }

    fun activate() {
        active = true
        focusArrived = false
    }

    private fun toggle() {
        if (active || open >= 0) deactivate() else activate()
    }

    /** The usable menu [by] steps along from [from], coming round at the ends, or null when there is none. */
    fun step(menus: List<BarMenu>, from: Int, by: Int): Int? {
        if (menus.isEmpty()) return null
        var at = from
        repeat(menus.size) {
            at = (at + by + menus.size) % menus.size
            if (menus[at].enabled) return at
        }
        return null
    }

    private fun titleFor(menus: List<BarMenu>, key: Key): Int =
        menus.indexOfFirst { it.enabled && it.label.matches(key) }

    /** A key on its way anywhere at all. Any other key going down spoils a lone Alt, whoever takes it. */
    fun heard(event: KeyEvent) {
        if (event.type == KeyEventType.Down && event.key != Key.Alt) altAlone = false
    }

    /** A pointer went down somewhere: Alt held for a click or a drag was not Alt on its own. */
    fun pressed() {
        altAlone = false
    }

    /**
     * Alt coming up on its own; true when it was. Every other key spoils it — here, and through [heard]
     * for the keys the bar never gets — and so does a press, through [pressed].
     */
    private fun alt(event: KeyEvent): Boolean? {
        if (event.key == Key.Alt) {
            if (event.type == KeyEventType.Down) {
                if (!event.repeat) altAlone = true
                return false
            }
            val alone = altAlone
            altAlone = false
            return alone
        }
        if (event.type == KeyEventType.Down) altAlone = false
        return null
    }

    /** A key nothing focused took, while no menu is open. */
    fun onKey(event: KeyEvent, menus: List<BarMenu>): Boolean {
        alt(event)?.let { alone ->
            if (alone) toggle()
            return alone
        }
        if (event.type != KeyEventType.Down) return false
        if (event.key == Key.F10 && event.modifiers.none && !event.repeat) {
            toggle()
            return true
        }
        if (event.modifiers == Modifiers.Alt) {
            val index = titleFor(menus, event.key)
            if (index >= 0) {
                activate()
                openMenu(index)
                return true
            }
        }
        if (active && event.modifiers.none) {
            if (event.key == Key.Escape) {
                deactivate()
                return true
            }
            val index = titleFor(menus, event.key)
            if (index >= 0) {
                openMenu(index)
                return true
            }
        }
        if (menus.any { it.enabled && it.entries.fireShortcut(event) }) {
            deactivate()
            return true
        }
        return false
    }

    /** A key the open menu did not use itself. */
    fun onKeyWhileOpen(event: KeyEvent, menus: List<BarMenu>): Boolean {
        alt(event)?.let { alone ->
            if (alone) deactivate()
            return alone
        }
        if (event.type != KeyEventType.Down) return false
        if (event.key == Key.F10 && event.modifiers.none && !event.repeat) {
            deactivate()
            return true
        }
        if (event.modifiers == Modifiers.Alt) {
            val index = titleFor(menus, event.key)
            if (index >= 0) {
                openMenu(index)
                return true
            }
        }
        return false
    }

    fun onPad(
        event: GamepadEvent,
        button: GamepadButton?,
        menus: List<BarMenu>,
        anchors: List<PopupAnchor>,
        interactions: List<InteractionState>,
    ): Boolean {
        if (event !is GamepadEvent.ButtonDown) return false
        if (button != null && event.button == button) {
            toggle()
            return true
        }
        val by = when (event.button) {
            GamepadButton.LeftBumper -> -1
            GamepadButton.RightBumper -> 1
            else -> return false
        }
        // The shoulders are where they are on the pad: the left one goes to the title on the left,
        // which on a right-to-left bar is the next one along rather than the one before.
        val rtl = anchors.firstNotNullOfOrNull { it.node }?.layoutDirection == LayoutDirection.Rtl
        val along = if (rtl) -by else by
        if (open >= 0) {
            step(menus, open, along)?.let { openMenu(it) }
            return true
        }
        if (!active) return false
        val from = interactions.indexOfFirst { it.isFocused }.takeIf { it >= 0 } ?: wanted
        step(menus, from, along)?.let { focusOnNode(anchors.getOrNull(it)?.node) }
        return true
    }
}

/** One title on the bar. */
@Composable
private fun MenuTitle(
    menu: BarMenu,
    index: Int,
    bar: MenuBarState,
    anchor: PopupAnchor,
    interaction: InteractionState,
    style: String,
    direction: LayoutDirection,
    steps: (Int, Int) -> Int?,
    onFocusTitle: (Int) -> Unit,
) {
    val isOpen = bar.open == index
    val states = rememberStates(interaction, menu.enabled)
    val resolved = rememberStyle(if (isOpen) "$style.open" else style, states)
    val latest = rememberUpdatedState(menu)
    val latestSteps = rememberUpdatedState(steps)
    val latestFocusTitle = rememberUpdatedState(onFocusTitle)

    // While a menu is open, the pointer arriving on another title is a switch to it. The popup's
    // slot lies over the title and lets moves through, so this still hears them.
    val switch = remember(bar, index) {
        PointerHandler { event ->
            if (event is PointerEvent.Move && bar.open >= 0 && bar.open != index && latest.value.enabled) bar.openMenu(index)
            false
        }
    }
    val directions = remember(bar, index, direction) {
        DirectionHandler { pressed ->
            when (pressed) {
                FocusDirection.Down -> {
                    if (latest.value.enabled) bar.openMenu(index)
                    true
                }
                FocusDirection.Up -> true
                FocusDirection.Left, FocusDirection.Right -> {
                    val forwards = (pressed == FocusDirection.Right) == (direction == LayoutDirection.Ltr)
                    latestSteps.value(index, if (forwards) 1 else -1)?.let(latestFocusTitle.value)
                    true
                }
                else -> false
            }
        }
    }
    val placed = remember(anchor) { PlacedHandler { anchor.node = it } }
    val tapped = rememberTapped { if (bar.open == index) bar.close() else bar.openMenu(index) }

    NamedLayout(
        modifier = Modifier
            .interaction(interaction)
            // Only a stop for focus while the bar is being driven: a pad walking the screen below
            // must not wander up into the bar, and a click on a title must not take focus off a field.
            .focusable(interaction, enabled = bar.active && menu.enabled, initial = index == bar.wanted)
            .clickable(enabled = menu.enabled, onClick = tapped)
            .onPointer(switch)
            .onFocusDirection(directions)
            .onPlaced(placed)
            .styled(resolved),
        name = "menubar.title",
        measurePolicy = remember { BoxPolicy(Alignment.Centre) },
    ) {
        ProvideContentStyle(resolved) {
            DisableSelection {
                Text(menu.label.text, softWrap = false, runs = if (bar.active) menu.label.runs else emptyList())
            }
        }
    }
}

// --- a menu -------------------------------------------------------------------------------------

/** How long the pointer rests before a submenu opens, or before a diagonal move towards one gives up. */
private const val HoverMillis = 180

/**
 * One open menu, and what its submenus are doing.
 *
 * Not composed: the hover arithmetic runs on every pointer move, and only its answers — which
 * submenu is open, which row is waiting — are state.
 */
internal class MenuLevel {

    /** The row whose submenu is open, or -1. */
    var openSub by mutableStateOf(-1)

    /** A row the pointer is resting on that will be opened, or switched to, once it has rested. */
    var pending by mutableStateOf(-1)

    /** Where the pointer was last seen over this menu, in the root's coordinates. */
    var lastPoint: Offset? = null

    /** When it last moved, on the UI clock. What "rested" is measured from. */
    var movedAt = 0L

    /** The open submenu's panel, so a move towards it can be told from a move away. */
    var subPanel: UiNode? = null

    fun hover(index: Int, entry: MenuEntry, point: Offset, now: Long) {
        movedAt = now
        val from = lastPoint
        lastPoint = point
        val open = openSub
        when {
            index == open -> pending = -1
            // On the way to the open submenu: the rows crossed on the diagonal do not count yet.
            open >= 0 && from != null && headingFor(from, point) -> pending = index
            else -> {
                if (open >= 0) openSub = -1
                pending = if (entry is MenuEntry.Submenu && entry.enabled) index else -1
            }
        }
    }

    /**
     * The safe triangle: whether [point] is inside the one between where the pointer was and the near
     * edge of the open submenu. A pointer moving through it is heading for the submenu.
     */
    private fun headingFor(from: Offset, point: Offset): Boolean {
        val panel = subPanel?.boundsInRoot ?: return false
        if (panel.isEmpty) return false
        val nearX = if (panel.left >= from.x) panel.left else panel.right
        return inTriangle(point, from, Offset(nearX, panel.top), Offset(nearX, panel.bottom))
    }
}

/** Whether [p] is inside the triangle [a], [b], [c], edges included. */
internal fun inTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    fun side(p1: Offset, p2: Offset, p3: Offset) = (p1.x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1.y - p3.y)
    val d1 = side(p, a, b)
    val d2 = side(p, b, c)
    val d3 = side(p, c, a)
    val negative = d1 < 0f || d2 < 0f || d3 < 0f
    val positive = d1 > 0f || d2 > 0f || d3 > 0f
    return !(negative && positive)
}

/**
 * One menu, open next to [anchor], with focus trapped in it.
 *
 * Shared by the menu bar, a context menu and every submenu of either.
 *
 * @param onChosen an item was chosen, so every level closes.
 * @param onDismiss this level closes: Escape, Back, Left out of a submenu.
 * @param onOutside a press outside every level. Null for a submenu, whose outside is its parent's.
 * @param passes a press outside at this root point is let through instead of closing it.
 * @param onSwitch for a menu on a bar: Left or Right with nowhere to go moves along the bar, +1 being
 *   the next menu in reading order.
 * @param onPointerInside told when the pointer moves over this menu, so the menu it opened from stops
 *   waiting to close it.
 * @param onPanelPlaced told where this menu's panel is, for the safe triangle in the menu it opened from.
 * @param keys keys the owner wants while this is open, asked after the menu's own letters.
 * @param pad pad events the owner wants while this is open.
 */
@Composable
internal fun MenuPopup(
    entries: List<MenuEntry>,
    anchor: PopupAnchor,
    position: PopupPosition,
    style: String,
    mnemonics: Boolean,
    onChosen: () -> Unit,
    onDismiss: () -> Unit,
    onOutside: (() -> Unit)?,
    passes: (Offset) -> Boolean = { false },
    onSwitch: ((Int) -> Unit)? = null,
    onPointerInside: () -> Unit = {},
    onPanelPlaced: (UiNode) -> Unit = {},
    keys: KeyHandler? = null,
    pad: GamepadHandler? = null,
) {
    Popup(
        anchor = anchor,
        onDismiss = onDismiss,
        position = position,
        closesOutside = onOutside != null,
        onOutside = onOutside,
        passes = passes,
    ) {
        OnBack { onDismiss() }
        MenuPanel(entries, style, mnemonics, onChosen, onDismiss, nested = onOutside == null, onSwitch, onPointerInside, onPanelPlaced, keys, pad)
    }
}

@Composable
private fun MenuPanel(
    entries: List<MenuEntry>,
    style: String,
    mnemonics: Boolean,
    onChosen: () -> Unit,
    onDismiss: () -> Unit,
    nested: Boolean,
    onSwitch: ((Int) -> Unit)?,
    onPointerInside: () -> Unit,
    onPanelPlaced: (UiNode) -> Unit,
    keys: KeyHandler?,
    pad: GamepadHandler?,
) {
    val level = remember { MenuLevel() }
    val clocks = LocalClocks.current
    val direction = LocalLayoutDirection.current
    val latest = rememberUpdatedState(entries)
    val latestInside = rememberUpdatedState(onPointerInside)
    val latestPlaced = rememberUpdatedState(onPanelPlaced)
    val latestKeys = rememberUpdatedState(keys)
    val latestChosen = rememberUpdatedState(onChosen)
    val anchors = remember(entries.size) { List(entries.size) { PopupAnchor() } }

    WaitForRest(level, clocks, latest.value.size) { target ->
        val entry = latest.value.getOrNull(target)
        if (entry is MenuEntry.Submenu && entry.enabled) {
            level.openSub = target
        } else {
            level.openSub = -1
            focusOnNode(anchors.getOrNull(target)?.node)
        }
    }

    // A press on the panel's own padding or a separator is inside the menu, not a reason to close it.
    val inside = remember(level) {
        PointerHandler { event ->
            if (event is PointerEvent.Move) latestInside.value()
            event is PointerEvent.Press
        }
    }
    val letters = remember(level) {
        KeyHandler { event ->
            if (event.type == KeyEventType.Down && !event.repeat && event.modifiers.none) {
                val index = latest.value.indexOfFirst { it.usable && it.mnemonic?.matches(event.key) == true }
                if (index >= 0) {
                    when (val entry = latest.value[index]) {
                        is MenuEntry.Item -> {
                            latestChosen.value()
                            entry.activate()
                        }
                        is MenuEntry.Submenu -> level.openSub = index
                        MenuEntry.Separator -> Unit
                    }
                    return@KeyHandler true
                }
            }
            latestKeys.value?.onKey(event) == true
        }
    }
    val placed = remember(level) { PlacedHandler { latestPlaced.value(it) } }

    val marks = entries.any { it is MenuEntry.Item && it.mark != MenuMark.None }
    val icons = entries.any { (it as? MenuEntry.Item)?.icon != null || (it as? MenuEntry.Submenu)?.icon != null }
    val shortcuts = entries.any { (it as? MenuEntry.Item)?.shortcut != null }
    val submenus = entries.any { it is MenuEntry.Submenu }
    val columns = MenuColumns(marks, icons, shortcuts, submenus)

    var chrome = Modifier.onPointer(inside).onKeyEvent(letters).onPlaced(placed)
    if (pad != null) chrome = chrome.onGamepadEvent(pad)

    NamedLayout(
        modifier = chrome.styled(style),
        name = "menu",
        measurePolicy = remember { BoxPolicy(Alignment.TopStart) },
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    MenuEntry.Separator -> Box(Modifier.fillMaxWidth().padding(vertical = SeparatorGap)) {
                        Divider(Modifier.fillMaxWidth(), style = "$style.separator")
                    }
                    else -> MenuRow(
                        entry = entry,
                        index = index,
                        level = level,
                        anchor = anchors[index],
                        style = style,
                        columns = columns,
                        mnemonics = mnemonics,
                        direction = direction,
                        clocks = clocks,
                        nested = nested,
                        onChoose = {
                            when (entry) {
                                is MenuEntry.Item -> {
                                    onChosen()
                                    entry.activate()
                                }
                                is MenuEntry.Submenu -> level.openSub = index
                                MenuEntry.Separator -> Unit
                            }
                        },
                        onDismiss = onDismiss,
                        onSwitch = onSwitch,
                    )
                }
            }
        }
    }

    val open = level.openSub
    val sub = entries.getOrNull(open) as? MenuEntry.Submenu
    if (open >= 0 && (sub == null || !sub.enabled)) SideEffect { level.openSub = -1 }
    if (sub == null || !sub.enabled) return

    key(open) {
        MenuPopup(
            entries = sub.entries,
            anchor = anchors[open],
            position = PopupPosition.Beside,
            style = style,
            mnemonics = mnemonics,
            onChosen = onChosen,
            onDismiss = {
                level.openSub = -1
                focusOnNode(anchors.getOrNull(open)?.node)
            },
            onOutside = null,
            onPointerInside = { level.pending = -1 },
            onPanelPlaced = { level.subPanel = it },
            keys = keys,
            pad = pad,
        )
    }
}

/**
 * Waits for the pointer to rest on [MenuLevel.pending], then hands that row to [then].
 *
 * Rest rather than time: a pointer still travelling across the rows towards a submenu keeps putting
 * it off, and the moment it stops is the moment the player has decided.
 */
@Composable
private fun WaitForRest(level: MenuLevel, clocks: Clocks, size: Int, then: (Int) -> Unit) {
    val latest = rememberUpdatedState(then)
    val target = level.pending
    LaunchedEffect(level, target, size) {
        if (target < 0) return@LaunchedEffect
        while (true) {
            clocks.wait(Clock.Ui, HoverMillis)
            if (clocks.time(Clock.Ui) - level.movedAt >= HoverMillis * NanosPerMilli) break
        }
        level.pending = -1
        latest.value(target)
    }
}

private const val NanosPerMilli = 1_000_000L

/** Which of the optional columns a menu has, so every row in it lines up. */
private data class MenuColumns(val marks: Boolean, val icons: Boolean, val shortcuts: Boolean, val submenus: Boolean)

private const val SeparatorGap = 4f
private const val MarkSize = 14f
private const val IconSize = 16f
private const val ColumnGap = 8f
private const val ShortcutGap = 24f
private const val ArrowWidth = 6f
private const val ArrowHeight = 10f

/** One row: the tick or dot, the icon, the label, the shortcut and the arrow, each in its column. */
@Composable
private fun MenuRow(
    entry: MenuEntry,
    index: Int,
    level: MenuLevel,
    anchor: PopupAnchor,
    style: String,
    columns: MenuColumns,
    mnemonics: Boolean,
    direction: LayoutDirection,
    clocks: Clocks,
    nested: Boolean,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
    onSwitch: ((Int) -> Unit)?,
) {
    val interaction = remember { InteractionState() }
    val item = entry as? MenuEntry.Item
    val submenu = entry as? MenuEntry.Submenu
    val enabled = entry.usable
    val label = entry.mnemonic ?: Mnemonic("", -1)
    val states = rememberStates(interaction, enabled)
    val isOpen = level.openSub == index
    val resolved = rememberStyle(if (isOpen) "$style.item.open" else "$style.item", states)

    val latestEntry = rememberUpdatedState(entry)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestSwitch = rememberUpdatedState(onSwitch)

    val hover = remember(level, index, anchor) {
        PointerHandler { event ->
            if (event is PointerEvent.Move) {
                val node = anchor.node
                val point = if (node == null) event.position else {
                    val bounds = node.boundsInRoot
                    val scale = node.scaleInRoot
                    Offset(bounds.left + event.position.x * scale, bounds.top + event.position.y * scale)
                }
                level.hover(index, latestEntry.value, point, clocks.time(Clock.Ui))
                // The highlight follows the pointer, so the pointer and the arrows never light two rows.
                // Not while a submenu is open: focus is in there, and belongs there until it closes.
                if (level.openSub < 0 && node != null && node.focusManagerOrNull()?.focused !== node) focusOnNode(node)
            }
            false
        }
    }
    val directions = remember(level, index, direction, nested) {
        DirectionHandler { pressed ->
            val inwards = if (direction == LayoutDirection.Ltr) FocusDirection.Right else FocusDirection.Left
            val outwards = if (direction == LayoutDirection.Ltr) FocusDirection.Left else FocusDirection.Right
            val current = latestEntry.value
            when (pressed) {
                inwards -> when {
                    current is MenuEntry.Submenu && current.enabled -> {
                        level.openSub = index
                        true
                    }
                    else -> latestSwitch.value?.let { it(1); true } ?: false
                }
                outwards -> when {
                    nested -> {
                        latestDismiss.value()
                        true
                    }
                    else -> latestSwitch.value?.let { it(-1); true } ?: false
                }
                else -> false
            }
        }
    }
    val placed = remember(anchor) { PlacedHandler { anchor.node = it } }
    val tapped = rememberTapped(onChoose)

    NamedLayout(
        modifier = Modifier
            .fillMaxWidth()
            .interaction(interaction)
            .focusable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = tapped)
            .onPointer(hover)
            .onFocusDirection(directions)
            .onPlaced(placed)
            .styled(resolved),
        name = "menu.item",
        measurePolicy = remember { LinearPolicy(true, Arrangement.spacedBy(ColumnGap), Alignment(vertical = VerticalAlignment.Centre)) },
    ) {
        ProvideContentStyle(resolved) {
            DisableSelection {
                if (columns.marks) {
                    Box(Modifier.size(MarkSize)) {
                        when (item?.mark) {
                            MenuMark.Checked -> Box(Modifier.size(MarkSize).styled("$style.check", states))
                            MenuMark.Selected -> Box(Modifier.size(MarkSize).styled("$style.radio", states))
                            else -> Unit
                        }
                    }
                }
                if (columns.icons) {
                    Box(Modifier.size(IconSize), contentAlignment = Alignment.Centre) {
                        (item?.icon ?: submenu?.icon)?.invoke()
                    }
                }
                Box(Modifier.weight(1f)) {
                    Text(label.text, softWrap = false, runs = if (mnemonics) label.runs else emptyList())
                }
                if (columns.shortcuts) {
                    val shortcut = item?.shortcut
                    Box(Modifier.paddingRelative(start = ShortcutGap)) {
                        if (shortcut != null) {
                            Text(
                                shortcut.label,
                                style = "$style.shortcut",
                                colour = if (enabled) null else resolved.textColour,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (columns.submenus) {
                    if (submenu != null) {
                        SubmenuArrow(resolved.textColour, direction)
                    } else {
                        Box(Modifier.size(ArrowWidth, ArrowHeight))
                    }
                }
            }
        }
    }
}

/** The small triangle pointing the way a submenu opens, in the row's text colour. */
@Composable
private fun SubmenuArrow(colour: Colour, direction: LayoutDirection) {
    val draw: UiCanvas.(Rect) -> Unit = remember(colour, direction) {
        if (direction == LayoutDirection.Ltr) {
            { box -> fan(floatArrayOf(box.left, box.top, box.right, (box.top + box.bottom) / 2f, box.left, box.bottom), colour) }
        } else {
            { box -> fan(floatArrayOf(box.right, box.top, box.left, (box.top + box.bottom) / 2f, box.right, box.bottom), colour) }
        }
    }
    LeafLayout(Modifier.size(ArrowWidth, ArrowHeight), name = "menu.arrow", draw = draw)
}

/** A [Layout] with its content last, so the menu's nodes can carry names a test and a dump can read. */
@Composable
private fun NamedLayout(
    modifier: Modifier,
    name: String,
    measurePolicy: MeasurePolicy,
    content: @Composable () -> Unit,
) = Layout(modifier = modifier, name = name, content = content, measurePolicy = measurePolicy)
