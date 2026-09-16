package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.focus.focusOnNode
import dev.wildware.composegl.ui.input.ActivateHandler
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onActivate
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.paddingRelative
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.LocalLocale
import dev.wildware.composegl.ui.text.LocalStrings
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.DropTargetState
import dev.wildware.composegl.ui.widget.LocalDragAndDrop
import dev.wildware.composegl.ui.widget.MenuScope
import dev.wildware.composegl.ui.widget.NumberStepper
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.widget.dragSource
import dev.wildware.composegl.ui.widget.dropTarget
import dev.wildware.composegl.ui.widget.rememberScrollState
import kotlin.math.floor

/**
 * An item on its way from one square to another: what is in hand, where it came from, and how it
 * is being held.
 *
 * One of these is the payload a grid's squares are offered, so a chest can tell an item dragged
 * out of the backpack from whatever else a screen is dragging around. It is made by the grid the
 * item is in and lives as long as that pile is on screen, so reading [taking] or [rotated] in a
 * composition follows the hand.
 */
@Stable
class InventoryDrag internal constructor(

    /** The grid the pile is in. The same grid it lands in for a move, another one for a transfer. */
    val from: InventoryState,
) {

    /** The whole pile, as it sits in [from] now. */
    var item: InventoryItem by mutableStateOf(EmptyItem)
        internal set

    /** Which square of the pile the hand took hold of, so a long item hangs where it was grabbed. */
    var grab: InventoryCell by mutableStateOf(InventoryCell(0, 0))
        internal set

    /** How many of the pile are in hand: all of it, or half when it was picked up with a split held. */
    var taking: Int by mutableStateOf(1)
        internal set

    /** Which way up it is being carried, which the rotate key turns while it is in the air. */
    var rotated: Boolean by mutableStateOf(false)
        internal set

    /** What is actually in hand: that many, turned that way. */
    val carried: InventoryItem get() = item.copy(count = taking.coerceAtMost(item.count), rotated = rotated)

    /** Whether the whole pile was picked up rather than part of it. */
    val isWhole: Boolean get() = taking >= item.count

    /** How the source grid gives up what is being carried. Set by the grid the pile is in. */
    internal var take: (InventoryItem, Int) -> InventoryItem? = { _, _ -> null }

    /** And how it takes back what would not fit. */
    internal var putBack: (InventoryItem) -> Unit = {}

    /**
     * What is in hand as the grid it is over sees it.
     *
     * Part of a pile is a new pile, and so is never the item a swap could push out of the way:
     * that is what the [InventoryItem.id] here says. The whole pile keeps its name, which is what
     * makes a drop back into its own grid a move rather than a stranger arriving.
     */
    internal fun arriving(): InventoryItem = if (isWhole) carried else carried.copy(id = this)

    internal fun begin(grab: InventoryCell, taking: Int) {
        this.grab = grab
        this.taking = taking
        this.rotated = item.rotated
    }
}

/** What an [InventoryDrag] holds before a grid has told it about a pile. Never seen by a game. */
private val EmptyItem = InventoryItem(id = Unit, kind = Unit)

/**
 * The bag: squares, the things in them, and every way a player moves one.
 *
 * ```kotlin
 * DragAndDropHost {
 *     InventoryGrid(
 *         state = bag,
 *         onMove = { item, to -> bag.move(item, to) },
 *         canPlace = { item, at -> bag.canPlace(item, at) },
 *     ) { item -> Image(art(item.kind)) }
 * }
 * ```
 *
 * The rules are [InventoryState]'s and can be tested without a screen. This is the part a player
 * touches, and it is the toolkit's own drag and drop underneath, so everything that already works
 * for a hotbar works here:
 *
 * - **A mouse** picks a pile up and drops it. A long item hangs from the square it was grabbed by,
 *   and the squares it would land on are drawn ahead of it — lit when it fits, barred when it does
 *   not.
 * - **A pad or a keyboard** moves focus from square to square. South or Enter picks up, South or
 *   Enter puts down, East or Escape puts it back. Focus stops on empty squares too, because an
 *   empty square is where a player wants to put something.
 * - **Two grids** — a bag and a chest — need nothing of each other. Whichever one the pile lands on
 *   asks its own [canPlace], and the one it came from is told through its own `onTake`.
 * - **Stacks** show their count, merge when one is dropped on another of the same kind, and split
 *   in half when [splitKey] or [splitButton] is held as the pile is picked up. The menu's split
 *   opens a stepper for any other number.
 * - **Turning** a long item while it is in the air is [rotateKey] or [rotateButton]; a pile
 *   standing still is turned from its menu.
 * - **In Arabic** the first column is on the right. Nothing else changes: a bag is still eight
 *   across, and the square a pile is grabbed by is still the one under the hand.
 *
 * @param onMove a pile moving inside this grid: dropped on free squares, merged into another pile,
 *   or swapped with the one item in the way. Refuse a move by doing nothing.
 * @param onAccept a pile arriving from somewhere else, or half of one split off. Hand back what
 *   would not fit and it goes home.
 * @param onTake this grid giving [count] of a pile up to another grid. What comes back is what the
 *   other grid receives.
 * @param onPutBack what would not fit, coming home to this grid.
 * @param canPlace whether a drop would do anything. The same question the squares light up by, so
 *   a game's own rule — no weapons in the fridge — shows before the player lets go.
 * @param matches the filter: a pile this says no to is drawn faded. It is still there and can
 *   still be moved, because a filter is about finding something, not about hiding it.
 * @param menu what a right-click, a long press or the pad's menu button offers for one pile. The
 *   grid adds what it can do itself — split half, split a number, turn — below whatever is written
 *   here, and those go out through [canPlace], [onTake], [onAccept] and [onMove] like a drag does,
 *   so a game's own rule about where things may sit holds for the menu as well. A grid that is not
 *   [enabled] drops its own three, because they rearrange the bag; what is written here still
 *   shows, since a read-only bag may still have an "Examine" worth offering.
 * @param lazy builds only the rows in view, for a stash of a thousand squares. It scrolls, so the
 *   grid takes the height it is given rather than the height of all its rows.
 * @param slot what one pile looks like: the picture, the name, whatever the game draws. The count
 *   and the frame are the grid's.
 */
@Composable
fun InventoryGrid(
    state: InventoryState,
    modifier: Modifier = Modifier,
    onMove: (InventoryItem, InventoryCell) -> Unit = { item, to -> state.move(item, to) },
    onAccept: (InventoryItem, InventoryCell) -> InventoryItem? = { item, to -> state.accept(item, to) },
    onTake: (InventoryItem, Int) -> InventoryItem? = { item, count -> state.take(item, count) },
    onPutBack: (InventoryItem) -> Unit = { state.addAnywhere(it) },
    canPlace: (InventoryItem, InventoryCell) -> Boolean = { item, at -> state.canPlace(item, at) },
    matches: (InventoryItem) -> Boolean = { true },
    enabled: Boolean = true,
    cellSize: Float = 48f,
    spacing: Float = 4f,
    style: String = "inventory",
    splitKey: Key? = Key.Shift,
    splitButton: GamepadButton? = GamepadButton.West,
    rotating: Boolean = true,
    rotateKey: Key? = Key.R,
    rotateButton: GamepadButton? = GamepadButton.RightBumper,
    lazy: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    overscan: Int = 2,
    menu: (MenuScope.(InventoryItem) -> Unit)? = null,
    slot: @Composable (InventoryItem) -> Unit,
) {
    val dnd = LocalDragAndDrop.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pitch = cellSize + spacing
    val labels = rememberInventoryLabels()

    // Held down as a pile is picked up, which is what makes the pickup half a pile rather than all
    // of it. Kept here rather than on the pile, because the player holds it before choosing one.
    //
    // It heals itself rather than waiting for the key to come up, because the key coming up can be
    // swallowed: a shortcut only reaches inside the innermost focus trap, and a pile's own menu is
    // one, so Shift let go of over an open menu never arrives here. Three things put it right —
    // every other key that does arrive says which modifiers are held with it; the grid drops it
    // itself whenever it opens a menu or a prompt, which are the traps it knows about; and a pad
    // unplugged mid-drag is nobody holding anything.
    var splitting by remember { mutableStateOf(false) }
    // The modifier the split key is, when it is one. A held Shift is reported with every other key,
    // so any key at all is a chance to notice it is no longer held.
    val splitModifier = modifierOf(splitKey)
    // The pile whose "split a number" prompt is open, and the number the stepper is on.
    var prompt by remember { mutableStateOf<InventoryItem?>(null) }
    var promptCount by remember { mutableStateOf(1) }

    // Whether the ring is anywhere in this grid, which is what says a drop may move it. Read at the
    // moment of the drop rather than composed with, so the grid asks for the answer of the day.
    var ringHere by remember { mutableStateOf(false) }

    val drag = dnd.payload as? InventoryDrag
    val grid = remember { InventoryTargets() }
    grid.state = state
    grid.canPlace = canPlace
    grid.onMove = onMove
    grid.onAccept = onAccept
    grid.enabled = enabled
    grid.ringHere = { ringHere }

    val splitKeys = remember(splitKey, rotateKey, rotating) {
        KeyHandler { event ->
            if (splitKey != null && event.key == splitKey) {
                splitting = event.type == KeyEventType.Down
                return@KeyHandler false
            }
            // Any other key carries the modifiers held with it, which is how a Shift let go of
            // behind a menu is noticed: the release never arrived, but the next key says it is gone.
            if (splitModifier != null) splitting = (splitModifier.bits and event.modifiers.bits) != 0
            if (rotateKey != null && event.key == rotateKey && event.type == KeyEventType.Down && !event.repeat) {
                return@KeyHandler rotateCarried(dnd.payload as? InventoryDrag, rotating)
            }
            false
        }
    }
    val splitButtons = remember(splitButton, rotateButton, rotating) {
        GamepadHandler { event ->
            when {
                splitButton != null && event is GamepadEvent.ButtonDown && event.button == splitButton ->
                    splitting = true

                splitButton != null && event is GamepadEvent.ButtonUp && event.button == splitButton ->
                    splitting = false

                rotateButton != null && event is GamepadEvent.ButtonDown && event.button == rotateButton ->
                    return@GamepadHandler rotateCarried(dnd.payload as? InventoryDrag, rotating)

                // A pad unplugged mid-drag is a split key nobody is holding any more.
                event is GamepadEvent.Disconnected -> splitting = false
            }
            false
        }
    }

    // The window, for a lazy grid: the room it is seen through, which only its own box can say.
    var window by remember { mutableStateOf(0f) }
    val firstRow: Int
    val lastRow: Int
    if (lazy && window > 0f) {
        firstRow = (floor(scroll.y / pitch).toInt() - overscan).coerceAtLeast(0)
        lastRow = (firstRow + (window / pitch).toInt() + 1 + overscan * 2).coerceAtMost(state.rows - 1)
    } else {
        firstRow = 0
        lastRow = state.rows - 1
    }

    val board: @Composable () -> Unit = {
        Board(
            state = state,
            grid = grid,
            drag = drag,
            slots = slotsOf(state, firstRow, lastRow),
            mirrored = mirrored,
            pitch = pitch,
            spacing = spacing,
            style = style,
            enabled = enabled,
            matches = matches,
            splitting = { splitting },
            rotating = rotating,
            labels = labels,
            menu = menu,
            onTake = onTake,
            onPutBack = onPutBack,
            // A menu and a prompt both trap focus, so the split key coming up while either is open
            // would never reach this grid. The latch goes now rather than waiting for a release
            // that cannot arrive.
            onMenuOpen = { splitting = false },
            onSplitPrompt = { splitting = false; prompt = it; promptCount = (it.count + 1) / 2 },
            slot = slot,
        )
    }

    // Only a lazy grid has any use for its own height, and a grid that measures itself recomposes
    // once more than one that does not.
    val measured = remember { SizeChangedHandler { window = it.height } }
    val ring = remember { FocusWithinHandler { ringHere = it } }
    Box(
        modifier = modifier
            .onShortcutKey(splitKeys)
            .onShortcutGamepad(splitButtons)
            .onFocusWithin(ring)
            .then(if (lazy) Modifier.onSizeChanged(measured) else Modifier),
    ) {
        if (lazy) ScrollArea(state = scroll, bars = true) { board() } else board()

        prompt?.let { pile ->
            SplitPrompt(
                item = pile,
                count = promptCount,
                labels = labels,
                style = style,
                onCount = { promptCount = it },
                onSplit = { grid.split(pile, promptCount, onTake, onPutBack); prompt = null },
                onCancel = { prompt = null },
            )
        }
    }
}

/**
 * The modifier [key] is, for the four keys that are one, and null for every other key.
 *
 * A modifier is reported with every key pressed alongside it, so knowing which one the split key is
 * turns any key at all into a fresh answer about whether it is still held.
 */
private fun modifierOf(key: Key?): Modifiers? = when (key) {
    Key.Shift -> Modifiers.Shift
    Key.Control -> Modifiers.Control
    Key.Alt -> Modifiers.Alt
    Key.Meta -> Modifiers.Meta
    else -> null
}

/**
 * Turns what is in hand over, if there is anything in hand and turning it is anything.
 *
 * The square it is held by turns with it — a rifle held by its muzzle is still held by its muzzle
 * once it is standing up — so the part of it under the hand does not jump as it swings round.
 */
private fun rotateCarried(drag: InventoryDrag?, rotating: Boolean): Boolean {
    if (!rotating || drag == null || !drag.item.canTurn) return false
    drag.rotated = !drag.rotated
    drag.grab = InventoryCell(
        drag.grab.y.coerceIn(0, drag.carried.across - 1),
        drag.grab.x.coerceIn(0, drag.carried.down - 1),
    )
    return true
}

/** Where one pile's square ended up, kept out of the composition because nothing draws it. */
private class KnownNode {
    var node: UiNode? = null
}

/** The squares a drop would land on, drawn under the hand: where they are, and whether it fits. */
private class InventoryPreview(val slot: InventorySlot, val fits: Boolean)

/** One square of the board that is a node: a free square, or a whole pile. */
private class InventorySlot(
    val cell: InventoryCell,
    val across: Int,
    val down: Int,
    val item: InventoryItem?,
)

/**
 * The squares to draw, in the rows from [firstRow] to [lastRow]: every pile whose rows are in
 * there, and every square left over.
 *
 * A pile is one node covering all of its squares rather than a node per square, which is what makes
 * focus stop on it once and a click on the middle of a two-by-two crate pick the crate up.
 */
private fun slotsOf(state: InventoryState, firstRow: Int, lastRow: Int): List<InventorySlot> {
    val taken = Array(state.rows) { BooleanArray(state.columns) }
    val slots = mutableListOf<InventorySlot>()
    for (item in state.items) {
        for (y in item.at.y until item.at.y + item.down) {
            for (x in item.at.x until item.at.x + item.across) {
                if (y in 0 until state.rows && x in 0 until state.columns) taken[y][x] = true
            }
        }
        if (item.at.y + item.down > firstRow && item.at.y <= lastRow) {
            slots += InventorySlot(item.at, item.across, item.down, item)
        }
    }
    for (y in firstRow..lastRow) {
        for (x in 0 until state.columns) {
            if (!taken[y][x]) slots += InventorySlot(InventoryCell(x, y), 1, 1, null)
        }
    }
    // Reading order, so that Tab and the first focus of a screen walk the bag the way a player
    // reads it rather than the order the items happen to be stored in.
    return slots.sortedBy { it.cell.y * state.columns + it.cell.x }
}

/** What every square of one grid shares, so a square does not carry six parameters about the grid. */
private class InventoryTargets {
    lateinit var state: InventoryState
    var canPlace: (InventoryItem, InventoryCell) -> Boolean = { _, _ -> false }
    var onMove: (InventoryItem, InventoryCell) -> Unit = { _, _ -> }
    var onAccept: (InventoryItem, InventoryCell) -> InventoryItem? = { item, _ -> item }
    var enabled = true

    /** Whether the ring is anywhere in this grid now, which the grid answers from `onFocusWithin`. */
    var ringHere: () -> Boolean = { false }

    /** The node each pile is drawn by, so the ring can be put back on one by name. */
    private val nodes = mutableMapOf<Any, UiNode>()

    /** The pile the ring is on its way to, while that pile is waiting to be placed. */
    private var chasing: Any? = null

    /** A pile saying where it is now. Also the moment a ring that was waiting for it can land. */
    fun placed(id: Any, node: UiNode) {
        nodes[id] = node
        if (chasing == id && focusOnNode(node)) chasing = null
    }

    /** And a pile going away. Without this a long session in a big stash keeps every pile it ever had. */
    fun forget(id: Any, node: UiNode?) {
        if (nodes[id] === node) nodes -= id
    }

    /**
     * The ring goes to the pile a drop landed on.
     *
     * A pile moved inside its own grid keeps its node and is focused here and now. A pile that
     * merged into another, or arrived from a chest, has a node that either belongs to a different
     * pile or does not exist yet — so the name is written down and [placed] finishes the job on the
     * frame the new square appears.
     */
    private fun chase(id: Any) {
        val node = nodes[id]
        chasing = if (node != null && focusOnNode(node)) null else id
    }

    /** Where a pile's corner lands when it is dropped on [over], given where it was grabbed. */
    fun anchor(drag: InventoryDrag, over: InventoryCell) =
        InventoryCell(over.x - drag.grab.x, over.y - drag.grab.y)

    /** Whether letting go over [over] would do anything. What a square lights up by, and drops by. */
    fun accepts(drag: InventoryDrag, over: InventoryCell): Boolean =
        enabled && canPlace(drag.arriving(), anchor(drag, over))

    /**
     * Letting go over [over].
     *
     * A whole pile coming home to its own grid is a move, which is the one that can swap. Anything
     * else is a pile arriving: taken out of wherever it was first, so the squares it is leaving are
     * free by the time it lands on them, and handed back if it turns out it will not all fit.
     */
    fun drop(drag: InventoryDrag, over: InventoryCell) {
        val to = anchor(drag, over)
        if (!accepts(drag, over)) return
        if (drag.from === state && drag.isWhole) {
            onMove(drag.carried, to)
            landedOn(to)
            return
        }
        val taken = drag.take(drag.item, drag.taking) ?: return
        val left = onAccept(taken.copy(rotated = drag.rotated), to)
        if (left != null) drag.putBack(left)
        landedOn(to)
    }

    /**
     * The ring follows what was just put down, when the ring was in this grid to begin with.
     *
     * A pile poured into another one stops existing, and the square it came from is not where the
     * player put it — so the pile it went into is the answer, which is whatever is standing on [to]
     * now. Asked of the state rather than of the drag, because a merge, a swap and a plain move all
     * answer it the same way.
     *
     * The ring being elsewhere is left alone: a screen driven by a mouse that shows no ring at all
     * must not grow one because something was dragged across it.
     */
    private fun landedOn(to: InventoryCell) {
        if (!ringHere()) return
        state.itemAt(to)?.let { chase(it.id) }
    }

    /**
     * The first square a pile of its own could stand in that this grid and the game both allow.
     *
     * Free squares only, the way [InventoryState.split] chooses one: a square that would merge it
     * straight back into the pile it came off is no answer at all. The probe is given a name of its
     * own so that the pile being split counts as something in the way of itself.
     */
    private fun firstFreeFor(item: InventoryItem): InventoryCell? {
        val probe = item.copy(id = Any())
        for (y in 0..state.rows - probe.down) {
            for (x in 0..state.columns - probe.across) {
                val cell = InventoryCell(x, y)
                if (state.fits(probe, cell) && canPlace(probe, cell)) return cell
            }
        }
        return null
    }

    /**
     * Splitting [count] off [item] from the menu, through the callbacks a drag of half a pile uses.
     *
     * A square is found first, then the part is taken out of this grid and accepted back into it,
     * so a game whose [canPlace] keeps the bottom row clear keeps it clear here too. Nowhere to put
     * it means nothing is split, rather than half a pile with no home.
     */
    fun split(
        item: InventoryItem,
        count: Int,
        onTake: (InventoryItem, Int) -> InventoryItem?,
        onPutBack: (InventoryItem) -> Unit,
    ) {
        // The pile as it is now, rather than as it was when the menu was written: a click is later
        // than a composition, and half of what a pile used to hold is not half of it.
        val here = state.item(item.id) ?: return
        if (!enabled || count < 1 || count >= here.count) return
        val cell = firstFreeFor(here.copy(count = count)) ?: return
        val taken = onTake(here, count) ?: return
        val left = onAccept(taken, cell)
        if (left != null) onPutBack(left)
    }

    /**
     * Turning a pile where it stands, from the menu: a move that changes only which way up it is.
     *
     * Free squares only again — a turn is not a merge and not a swap — and the game is asked, since
     * standing a rifle up puts it in squares it was not in.
     */
    fun turn(item: InventoryItem) {
        val here = state.item(item.id) ?: return
        if (!enabled || !here.canTurn) return
        val turned = here.turned()
        if (!state.fits(turned, here.at) || !canPlace(turned, here.at)) return
        onMove(turned, here.at)
    }
}

/** The squares themselves, laid out and placed. Everything above this is about what they mean. */
@Composable
private fun Board(
    state: InventoryState,
    grid: InventoryTargets,
    drag: InventoryDrag?,
    slots: List<InventorySlot>,
    mirrored: Boolean,
    pitch: Float,
    spacing: Float,
    style: String,
    enabled: Boolean,
    matches: (InventoryItem) -> Boolean,
    splitting: () -> Boolean,
    rotating: Boolean,
    labels: InventoryLabels,
    menu: (MenuScope.(InventoryItem) -> Unit)?,
    onTake: (InventoryItem, Int) -> InventoryItem?,
    onPutBack: (InventoryItem) -> Unit,
    onMenuOpen: () -> Unit,
    onSplitPrompt: (InventoryItem) -> Unit,
    slot: @Composable (InventoryItem) -> Unit,
) {
    // One per square, kept between compositions so that a square knows whether the drag is over it.
    // Read here, before the squares are composed, so that the whole board recomposes when the hand
    // moves and the preview underneath it can be drawn as a square of its own.
    val targets = remember { mutableMapOf<InventoryCell, DropTargetState>() }
    val states = slots.map { targets.getOrPut(it.cell) { DropTargetState() } }
    val over = slots.indices.firstOrNull { states[it].isHovered || states[it].isRefusing }?.let { slots[it].cell }

    val preview = if (drag == null || over == null) null else {
        val carried = drag.carried
        InventoryPreview(
            slot = InventorySlot(grid.anchor(drag, over), carried.across, carried.down, null),
            fits = grid.accepts(drag, over),
        )
    }
    val places = buildList {
        slots.forEach { add(it.placedIn(state.columns, state.rows, pitch, spacing, mirrored, gutters = true)) }
        preview?.let {
            add(it.slot.placedIn(state.columns, state.rows, pitch, spacing, mirrored, gutters = false))
        }
    }

    Layout(
        name = "inventory",
        measurePolicy = InventoryPolicy(
            places = places,
            width = state.columns * pitch - spacing,
            height = state.rows * pitch - spacing,
        ),
        content = {
            slots.forEachIndexed { index, item ->
                val gutter = item.gutterIn(state.columns, state.rows, spacing, mirrored)
                // A pile is known by its name and a free square by where it is, so that moving a
                // pile moves its node instead of leaving the node where it was and pouring a
                // different square into it. Without this the board is matched up by position in
                // this list, and the list is re-sorted every time anything moves — so the node the
                // focus ring is drawn on would go on being the third one along while the pile that
                // had focus went somewhere else entirely. The flag keeps the two kinds of key
                // apart, in case a game names an item after a cell.
                key(item.item != null, item.item?.id ?: item.cell) {
                    if (item.item == null) {
                        FreeSquare(item.cell, states[index], grid, style, enabled, gutter)
                    } else {
                        ItemSquare(
                            item = item.item,
                            cell = item.cell,
                            target = states[index],
                            grid = grid,
                            pitch = pitch,
                            spacing = spacing,
                            mirrored = mirrored,
                            gutter = gutter,
                            style = style,
                            enabled = enabled,
                            faded = !matches(item.item),
                            splitting = splitting,
                            rotating = rotating,
                            labels = labels,
                            menu = menu,
                            onTake = onTake,
                            onPutBack = onPutBack,
                            onMenuOpen = onMenuOpen,
                            onSplitPrompt = onSplitPrompt,
                            slot = slot,
                        )
                    }
                }
            }
            if (preview != null) {
                Box(
                    Modifier
                        .testTag(if (preview.fits) "inventory.preview" else "inventory.preview.invalid")
                        .zIndex(PreviewZ)
                        .styled(if (preview.fits) "$style.footprint" else "$style.footprint.invalid"),
                )
            }
        },
    )
}

/** An empty square: somewhere to put something, and somewhere a pad can stand while it decides. */
@Composable
private fun FreeSquare(
    cell: InventoryCell,
    target: DropTargetState,
    grid: InventoryTargets,
    style: String,
    enabled: Boolean,
    gutter: Padding,
) {
    val interaction = remember { InteractionState() }
    val resolved = rememberStyle("$style.cell", rememberStates(interaction, enabled))
    Box(
        Modifier
            .testTag("inventory.cell.${cell.x},${cell.y}")
            .interaction(interaction)
            .dropTarget<InventoryDrag>(
                state = target,
                enabled = enabled,
                interaction = interaction,
                accepts = { grid.accepts(it, cell) },
                onDrop = { grid.drop(it, cell) },
            )
            // Before the style, so the square takes the gap after it but does not draw on it.
            .padding(gutter)
            .styled(resolved),
    )
}

/** A pile: the frame, the game's picture, the count, and every way of picking it up. */
@Composable
private fun ItemSquare(
    item: InventoryItem,
    cell: InventoryCell,
    target: DropTargetState,
    grid: InventoryTargets,
    pitch: Float,
    spacing: Float,
    mirrored: Boolean,
    gutter: Padding,
    style: String,
    enabled: Boolean,
    faded: Boolean,
    splitting: () -> Boolean,
    rotating: Boolean,
    labels: InventoryLabels,
    menu: (MenuScope.(InventoryItem) -> Unit)?,
    onTake: (InventoryItem, Int) -> InventoryItem?,
    onPutBack: (InventoryItem) -> Unit,
    onMenuOpen: () -> Unit,
    onSplitPrompt: (InventoryItem) -> Unit,
    slot: @Composable (InventoryItem) -> Unit,
) {
    val dnd = LocalDragAndDrop.current
    val interaction = remember { InteractionState() }
    val resolved = rememberStyle("$style.item", rememberStates(interaction, enabled))

    val drag = remember(grid.state, item.id) { InventoryDrag(grid.state) }
    drag.item = item
    drag.take = onTake
    drag.putBack = onPutBack
    val inHand = dnd.payload === drag

    // The grid is told which node this pile is, so that a drop can put the ring back on the pile it
    // landed on — which for a merge is a pile that was somewhere else entirely. Nothing here is
    // composed with: it is a note passed to the grid from the layout pass, and a pile whose node
    // changed has not changed what it looks like.
    val known = remember(grid, item.id) { KnownNode() }
    val placed = remember(grid, item.id) { PlacedHandler { known.node = it; grid.placed(item.id, it) } }
    DisposableEffect(grid, item.id) { onDispose { grid.forget(item.id, known.node) } }

    // What the hand takes hold of is settled as the pile is pressed, not while it is in the air:
    // letting go of the split key halfway across the bag does not change what is being carried.
    val grabbed = remember(drag, pitch, mirrored) {
        PointerHandler { event ->
            if (event is PointerEvent.Press && dnd.payload == null) {
                val column = floor(event.position.x / pitch).toInt().coerceIn(0, drag.item.across - 1)
                val row = floor(event.position.y / pitch).toInt().coerceIn(0, drag.item.down - 1)
                drag.begin(InventoryCell(if (mirrored) drag.item.across - 1 - column else column, row), taken(drag, splitting))
            }
            false
        }
    }
    // A pad or a keyboard has no position, so it takes hold of the corner.
    val pressed = remember(drag) {
        ActivateHandler {
            if (dnd.payload == null) drag.begin(InventoryCell(0, 0), taken(drag, splitting))
            false
        }
    }

    // Everything the grid offers itself rearranges the bag, so a grid that is switched off offers
    // none of it. The game's own entries stay: a bag that is only readable may still be examined.
    val half = enabled && item.count > 1
    val turn = enabled && rotating && item.canTurn
    val contextMenu = if (menu == null && !half && !turn) Modifier else Modifier.contextMenu {
        // Building the entries is the menu opening, however it was opened — a right-click, a long
        // press, Shift+F10 or the pad. It traps focus from here on, so the grid lets go of the
        // split key now rather than waiting for a release that can no longer reach it.
        onMenuOpen()
        menu?.invoke(this, item)
        if (menu != null && (half || turn)) Separator()
        if (half) {
            Item(labels.splitHalf) { grid.split(item, (item.count + 1) / 2, onTake, onPutBack) }
            Item(labels.splitSome) { onSplitPrompt(item) }
        }
        if (turn) Item(labels.rotate) { grid.turn(item) }
    }

    Box(
        modifier = Modifier
            .testTag("inventory.item.${item.id}")
            .onPlaced(placed)
            .interaction(interaction)
            .onPointer(grabbed)
            .onActivate(pressed)
            .dragSource(payload = drag, enabled = enabled, interaction = interaction) {
                Carried(drag, pitch, spacing, style, slot)
            }
            .dropTarget<InventoryDrag>(
                state = target,
                enabled = enabled,
                interaction = interaction,
                accepts = { grid.accepts(it, cell) },
                onDrop = { grid.drop(it, cell) },
            )
            .then(contextMenu)
            // Before the style, so the pile takes the gap after it but does not draw on it.
            .padding(gutter)
            .alpha(if (inHand) CarriedAlpha else if (faded) FadedAlpha else 1f)
            .styled(resolved),
        contentAlignment = Alignment.Centre,
    ) {
        DisableSelection {
            slot(item)
            // While part of a pile is in hand, the pile shows what is left of it rather than what
            // it was: the count a player is looking at is the one they would still have.
            val shown = if (inHand) item.count - drag.taking else item.count
            Count(shown, style, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** The picture under the hand: the pile at the size it will take up, with the count in hand on it. */
@Composable
private fun Carried(
    drag: InventoryDrag,
    pitch: Float,
    spacing: Float,
    style: String,
    slot: @Composable (InventoryItem) -> Unit,
) {
    val carried = drag.carried
    Box(
        modifier = Modifier
            .testTag("inventory.carried")
            .size(carried.across * pitch - spacing, carried.down * pitch - spacing)
            .alpha(CarriedPictureAlpha)
            .styled("$style.item"),
        contentAlignment = Alignment.Centre,
    ) {
        DisableSelection {
            slot(carried)
            Count(carried.count, style, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** How many are in a pile, in its corner. Nothing is drawn for one of something. */
@Composable
private fun Count(count: Int, style: String, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val resolved = rememberStyle("$style.count")
    Text(
        count.toString(),
        modifier = modifier.testTag("inventory.count").paddingRelative(end = 3f, bottom = 1f),
        textStyle = resolved.textStyle,
        colour = resolved.textColour,
    )
}

/** How many of a pile a press takes: all of it, or half when the split key or button is held. */
private fun taken(drag: InventoryDrag, splitting: () -> Boolean): Int =
    if (splitting() && drag.item.count > 1) (drag.item.count + 1) / 2 else drag.item.count

/**
 * Where one square sits on the board, mirrored for a screen that reads the other way.
 *
 * With [gutters] the square also takes the gaps between it and the squares after it, so the board
 * is covered corner to corner and a hand crossing from one square to the next is never over
 * neither — which is what used to make the footprint blink off in the four pixels between them. The
 * picture is padded back off those gaps by [gutterIn], so a bag still looks like squares with gaps.
 *
 * The footprint under the hand is placed without them: it is a picture of where a pile would land
 * rather than somewhere to land on.
 */
private fun InventorySlot.placedIn(
    columns: Int,
    rows: Int,
    pitch: Float,
    spacing: Float,
    mirrored: Boolean,
    gutters: Boolean,
): Placement {
    val after = if (gutters) gutterAfter(columns, spacing) else 0f
    val below = if (gutters) gutterBelow(rows, spacing) else 0f
    val x = (if (mirrored) columns - cell.x - across else cell.x) * pitch
    return Placement(
        // Mirrored, the gap after a square in the bag's own columns is the one on its left.
        x = if (mirrored) x - after else x,
        y = cell.y * pitch,
        width = across * pitch - spacing + after,
        height = down * pitch - spacing + below,
    )
}

/** The gap after this square along a row, which is nothing at all at the far edge of the bag. */
private fun InventorySlot.gutterAfter(columns: Int, spacing: Float) =
    if (cell.x + across < columns) spacing else 0f

/** And the gap below it. */
private fun InventorySlot.gutterBelow(rows: Int, spacing: Float) =
    if (cell.y + down < rows) spacing else 0f

/** Those two as padding, on the sides of the screen they fall on once a row has been mirrored. */
private fun InventorySlot.gutterIn(columns: Int, rows: Int, spacing: Float, mirrored: Boolean): Padding {
    val after = gutterAfter(columns, spacing)
    return Padding(
        left = if (mirrored) after else 0f,
        right = if (mirrored) 0f else after,
        bottom = gutterBelow(rows, spacing),
    )
}

/** One child's corner and size, worked out while the board is composed rather than while it lays out. */
private data class Placement(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Every square is exactly where its column and row say, so measuring is arithmetic rather than a
 * search: each child is offered its own square and placed at its own corner.
 *
 * A data class so that composing the same board again produces an equal policy and the node
 * reports nothing changed.
 */
private data class InventoryPolicy(
    val places: List<Placement>,
    val width: Float,
    val height: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val count = minOf(measurables.size, places.size)
        val placeables = placeables(count)
        val placements = placements(count)
        for (index in 0 until count) {
            val place = places[index]
            placeables[index] = measurables[index].measure(Constraints.fixed(place.width, place.height))
            placements[index * 2] = place.x
            placements[index * 2 + 1] = place.y
        }
        return layout(constraints.constrainWidth(width), constraints.constrainHeight(height), count)
    }
}

/** The panel that asks how many to split off: a stepper and two buttons, over the bag. */
@Composable
private fun SplitPrompt(
    item: InventoryItem,
    count: Int,
    labels: InventoryLabels,
    style: String,
    onCount: (Int) -> Unit,
    onSplit: () -> Unit,
    onCancel: () -> Unit,
) {
    OnBack(onBack = onCancel)
    Column(
        modifier = Modifier
            .testTag("inventory.split")
            .align(Alignment.Centre)
            .zIndex(PromptZ)
            .focusTrap()
            .styled("$style.split")
            .padding(12f),
        horizontalAlignment = HorizontalAlignment.Centre,
        verticalArrangement = Arrangement.spacedBy(8f),
    ) {
        Text(labels.splitTitle)
        NumberStepper(
            value = count,
            onValueChange = onCount,
            range = 1..(item.count - 1).coerceAtLeast(1),
            initialFocus = true,
            modifier = Modifier.testTag("inventory.split.count"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
            Button(labels.splitConfirm, onClick = onSplit, modifier = Modifier.testTag("inventory.split.ok"))
            Button(labels.cancel, onClick = onCancel, modifier = Modifier.testTag("inventory.split.cancel"))
        }
    }
}

/** What the grid's own menu entries are called, in the player's language where a game has one. */
private class InventoryLabels(
    val splitHalf: String,
    val splitSome: String,
    val rotate: String,
    val splitTitle: String,
    val splitConfirm: String,
    val cancel: String,
)

/**
 * The grid's own words, looked up as the compass's points are: a key nothing has translated comes
 * back as itself, which is the cue to use the English one rather than print `inventory.rotate` in
 * a menu.
 */
@Composable
private fun rememberInventoryLabels(): InventoryLabels {
    val strings = LocalStrings.current
    val locale = LocalLocale.current
    return remember(strings, locale) {
        fun of(key: String, english: String) = strings.get(locale, key).takeIf { it != key } ?: english
        InventoryLabels(
            splitHalf = of("inventory.split.half", "Split half"),
            splitSome = of("inventory.split.some", "Split…"),
            rotate = of("inventory.rotate", "Rotate"),
            splitTitle = of("inventory.split.title", "How many?"),
            splitConfirm = of("inventory.split.confirm", "Split"),
            cancel = of("inventory.cancel", "Cancel"),
        )
    }
}

/** How faded a pile is while it is in the player's hand. Still there, plainly not where it is. */
private const val CarriedAlpha = 0.3f

/** And how solid the picture under the hand is: not quite, so the squares under it still read. */
private const val CarriedPictureAlpha = 0.9f

/** A pile the filter says no to. Readable, and plainly not what was asked for. */
private const val FadedAlpha = 0.35f

/** Over the squares, under the thing being carried, which the drag layer draws far above everything. */
private const val PreviewZ = 1f

private const val PromptZ = 2f
