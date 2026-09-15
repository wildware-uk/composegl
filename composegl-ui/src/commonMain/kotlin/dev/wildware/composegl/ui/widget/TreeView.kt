package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * Which rows of a [TreeView] are open, and where its list has scrolled to.
 *
 * Kept by key rather than by position, so a node that is open stays open when its siblings are
 * sorted, or when something above it is removed. A key that is not in the tree any more is simply
 * never asked about; it costs a slot in a set and nothing else.
 *
 * @param initiallyExpanded the keys that start open: the root of a scene, the chapter being read.
 */
class TreeState(initiallyExpanded: Iterable<Any> = emptyList()) {

    /** Snapshot state, so a row that opens or closes rebuilds the rows under it. */
    private val open = mutableStateMapOf<Any, Unit>().apply { initiallyExpanded.forEach { put(it, Unit) } }

    /** The list the rows are shown in: its position, and scrolling it from outside. */
    val listState = LazyListState()

    /** The keys that are open now. A copy, so it does not change under a caller walking it. */
    val expandedKeys: Set<Any> get() = open.keys.toSet()

    fun isExpanded(key: Any): Boolean = key in open

    fun expand(key: Any) {
        open[key] = Unit
    }

    fun collapse(key: Any) {
        open.remove(key)
    }

    fun toggle(key: Any) = if (isExpanded(key)) collapse(key) else expand(key)

    fun setExpanded(key: Any, expanded: Boolean) = if (expanded) expand(key) else collapse(key)

    /** Opens every one of [keys]: the path down to something a search found, say. */
    fun expandAll(keys: Iterable<Any>) = keys.forEach { open[it] = Unit }

    /** Closes everything, back to the roots. */
    fun collapseAll() = open.clear()

    /**
     * Scrolls the row for [key] to the top of the list.
     *
     * Done on the next frame, once the rows have been worked out again, so opening a path and
     * scrolling to its end can be two lines one after the other — and so can closing something
     * above a row that is already showing and scrolling to that row, which has moved. A key that is
     * not showing even then is forgotten, so a row opened by hand much later does not make the list
     * jump.
     */
    fun scrollTo(key: Any) {
        // Never from the rows last shown: whatever was opened or closed just before this, or a new
        // list of roots, has not reached them yet, and a position found there can be another row's.
        pendingScroll = key
        // The tree is composed again next frame whether or not anything else changed, which is
        // where the wanted row is either scrolled to or forgotten.
        scrollAsks++
    }

    // --- what the widget keeps here -------------------------------------------------------------
    //
    // None of it is snapshot state: it is written after composition and read by input between
    // frames, and a recomposition for any of it would be a frame for nothing.

    /** The rows as they were last shown. */
    internal var shown: TreeRows<*> = TreeRows.Empty

    /** Each composed row's node, by key, for focus to be put straight onto. */
    internal val handles = HashMap<Any, RowHandle>()

    /** A row [scrollTo] asked for, scrolled to once the tree has been composed again. */
    internal var pendingScroll: Any? = null

    /** Counted up by [scrollTo], which waits a frame: the one thing here that is snapshot state. */
    internal var scrollAsks by mutableStateOf(0)

    /** A row focus should land on as soon as it has been built: one scrolled to from far away. */
    internal var pendingFocus: Any? = null

    /**
     * Whether [pendingFocus] was asked for in rows the list has not been told about yet, so the list
     * is scrolled to it after the next composition rather than straight away.
     */
    internal var pendingFocusScroll = false

    /**
     * The row focus was last on, its node, and the manager that put it there. Not cleared when focus
     * leaves: whether it is still there is asked of [focusManager] when it matters. The node and the
     * manager are let go once that row has gone from the tree, or its focus has gone from the row,
     * and when the tree itself leaves the screen — a saved state must not keep a dead screen alive.
     */
    internal var focusedKey: Any? = null
    internal var focusedNode: UiNode? = null
    internal var focusManager: FocusManager? = null
}

/** One composed row's node, written once it has been placed. */
internal class RowHandle {
    var node: UiNode? = null
}

/**
 * A [TreeState] that is still how the player left it — the same rows open, scrolled to the same
 * place — when its screen comes back, under a
 * [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is plain `remember`.
 */
@Composable
fun rememberTreeState(vararg initiallyExpanded: Any): TreeState =
    rememberSaveable { TreeState(initiallyExpanded.toList()) }

/** Lets go of the node and manager focus was on, which only a composed row can use. */
private fun TreeState.forgetFocusedNode() {
    focusedNode = null
    focusManager = null
}

/**
 * Nested rows that open and close: a scene's hierarchy, a quest log, a codex, a file picker.
 *
 * ```kotlin
 * TreeView(
 *     roots = scene.roots,
 *     children = { it.children },
 *     key = { it.id },
 *     selected = selection,
 *     onSelect = { selection = it },
 * ) { node, expanded ->
 *     Text(node.name)
 * }
 * ```
 *
 * Every open row's children are laid out under it, indented a step, and the whole thing scrolls as
 * one [LazyColumn]: only the rows that can be seen are built, so a tree of ten thousand nodes costs
 * what a screenful does. Nothing is asked for its children until it is opened, except by
 * [hasChildren], which is asked only for the rows that are built, and for the focused row when an
 * arrow is pressed — so even the default, which asks [children], costs a screenful of calls.
 *
 * - **Mouse.** A click selects a row. A click on its arrow opens or closes it; a double click on
 *   the row does the same, or calls [onActivate] when there is one.
 * - **Keyboard and pad.** Up and down move from row to row, and past either end leave the tree.
 *   Right opens a closed row, and on an open one goes down into its first child. Left closes an open
 *   row, and on a closed one or a leaf goes up to its parent. Enter or the pad's South selects. In a
 *   right-to-left screen Left and Right swap, the indent comes in from the right and the arrow of a
 *   closed row points left.
 *
 * What is open lives in [state], by key, and is saved the way [rememberSaveable] saves anything.
 * Closing a row with focus somewhere under it brings focus up to the nearest row still showing,
 * rather than dropping it.
 *
 * The look is the skin's: `"<style>.row"` for a row in each of its states and `"<style>.row.selected"`
 * for the chosen one, `"<style>.toggle"` and `"<style>.toggle.open"` for the arrow — a triangle in
 * its text colour, or its picture when the style gives one — and `"<style>.guide"` for the indent
 * lines, which a background of `"none"` turns off.
 *
 * @param key what identifies a node. It has to be unique across the whole tree, because it is what
 *   open state, row state and focus follow when the tree changes.
 * @param children a node's children, in order. Only called for a node that is open.
 * @param hasChildren whether a node can be opened, asked only for the rows that are built and for the
 *   focused row when Left or Right is pressed. The default asks [children]; a tree whose children
 *   are expensive to find — a directory, a server — passes something cheaper.
 * @param onActivate a double click on a row, instead of opening it: opening a file, jumping to a
 *   quest's marker.
 * @param rowModifier added to each row, for what a row needs as a whole: a `contextMenu`, a drag.
 * @param indent how far each level steps in, which is also the width of the arrow's column.
 * @param content what a row shows, handed its node and whether it is open.
 */
@Composable
fun <T> TreeView(
    roots: List<T>,
    children: (T) -> List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    selected: T? = null,
    onSelect: (T) -> Unit = {},
    state: TreeState = rememberTreeState(),
    hasChildren: (T) -> Boolean = { children(it).isNotEmpty() },
    onActivate: ((T) -> Unit)? = null,
    rowModifier: (T) -> Modifier = { Modifier },
    style: String = "tree",
    indent: Float = 18f,
    glyphSize: Float = 10f,
    spacing: Float = 0f,
    overscan: Int = 2,
    bars: Boolean = true,
    content: @Composable (node: T, expanded: Boolean) -> Unit,
) {
    val latestRoots = rememberUpdatedState(roots)
    val latestChildren = rememberUpdatedState(children)
    val latestKey = rememberUpdatedState(key)

    // Worked out again only when something it reads has changed: the roots, a list of children that
    // is itself state, or which rows are open. Scrolling reads none of them, and neither does
    // whether a row has children, which is asked only of the rows that are built.
    val flattened = remember(state) {
        derivedStateOf {
            flattenTree(latestRoots.value, latestChildren.value, latestKey.value, state::isExpanded)
        }
    }
    val rows = flattened.value

    val direction = LocalLayoutDirection.current
    val tree = remember(state) { TreeLogic(state) }
    tree.rows = flattened
    tree.rtl = direction == LayoutDirection.Rtl
    // Only ever handed this tree's own nodes, so the cast is never tested against anything else.
    @Suppress("UNCHECKED_CAST")
    val anyHasChildren = hasChildren as (Any?) -> Boolean
    tree.hasChildren = anyHasChildren

    // Read here so that a scrollTo waiting on a row brings this function round again next frame,
    // where the SideEffect below scrolls to that row or forgets it.
    @Suppress("UNUSED_VARIABLE")
    val scrollAsks = state.scrollAsks

    DisposableEffect(state) {
        // After the rows' own, so nothing is put back once this has gone.
        onDispose { state.forgetFocusedNode() }
    }

    // A scroll asked for since the last frame is done here, before the list below picks which rows
    // to build, so the frame builds the rows it scrolls to. Done in a SideEffect, the list would
    // build a window worked out from the old position in the new rows for a frame: rows nobody
    // asked for, which focus that had lost its row could land on and scroll the list back to.
    val wantedScroll = state.pendingScroll
    val focusScroll = state.pendingFocusScroll
    if (wantedScroll != null || focusScroll) {
        val lines = state.listState.lines
        // The list is told how many rows there are now, and how long that makes it, before it has
        // been laid out to learn it; otherwise the scroll is clamped to the old length.
        lines.describe(rows.rows.size, spacing)
        lines.refreshTotal()
        wantedScroll?.let { rows.index[it] }?.let { state.listState.scrollToItem(it) }
        // An arrow pressed before this frame wanted a row these rows have only just made — the first
        // child of a row opened a press earlier. Scrolled to only when it would be off screen, since
        // it is nearly always the row right under the one focus is on.
        if (focusScroll) {
            state.pendingFocus?.let { rows.index[it] }?.let { at ->
                if (at !in lines.window(0)) state.listState.scrollToItem(at)
            }
        }
    }

    SideEffect {
        val before = state.shown
        state.shown = rows
        // Forgotten once a composition that did them has gone through, whether the row was there or not.
        if (wantedScroll != null && state.pendingScroll === wantedScroll) state.pendingScroll = null
        if (focusScroll) state.pendingFocusScroll = false
        // Focus was on a row that has gone — closed away under a parent, or removed — so it moves
        // up to the nearest row that is still showing, rather than being dropped on the floor.
        val focused = state.focusedKey ?: return@SideEffect
        if (focused in rows.index) return@SideEffect
        // Only while focus is still on that row's node. Focus that had already gone somewhere else —
        // a button beside the tree — is left where the player put it. Either way the row has gone,
        // so its node is let go of here.
        val node = state.focusedNode
        val manager = state.focusManager
        state.forgetFocusedNode()
        if (node == null || manager?.focused !== node) return@SideEffect
        var at = before.index[focused] ?: return@SideEffect
        while (at >= 0) {
            val ancestor = before.rows[at]
            if (ancestor.key in rows.index) {
                tree.focus(ancestor.key)
                return@SideEffect
            }
            at = ancestor.parent
        }
    }

    val chosen = selected?.let(key)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestActivate = rememberUpdatedState(onActivate)

    LazyColumn(
        count = rows.rows.size,
        modifier = modifier,
        state = state.listState,
        key = { rows.rows[it].key },
        spacing = spacing,
        overscan = overscan,
        bars = bars,
    ) { index ->
        val row = rows.rows[index]
        // Asked here, for a row that is being built, rather than for every row in the tree.
        val expandable = hasChildren(row.node)
        TreeRowItem(
            row = row,
            expandable = expandable,
            tree = tree,
            selected = row.key == chosen,
            style = style,
            indent = indent,
            glyphSize = glyphSize,
            spacing = spacing,
            direction = direction,
            rowModifier = rowModifier(row.node),
            onSelect = { latestSelect.value(row.node) },
            onDoubleClick = {
                val activate = latestActivate.value
                if (activate != null) activate(row.node) else if (expandable) state.toggle(row.key)
            },
            content = content,
        )
    }
}

// --- the rows --------------------------------------------------------------------------------------

/**
 * One row as it is shown: the node, how deep, and what the guides beside it need.
 *
 * Whether it has children to show an arrow for is not here: that is asked only of a row that is built.
 *
 * @param parent the index of the row above it at one level up, or -1 for a root.
 * @param expanded whether its key is open. A node that is open and has no children shows nothing
 *   under it, and is drawn as the leaf it is.
 * @param lines for each level it is indented by, whether that level's guide carries on below this
 *   row. The last entry is this row's own: whether a sibling comes after it.
 */
internal class TreeRow<out T>(
    val node: T,
    val key: Any,
    val depth: Int,
    val parent: Int,
    val expanded: Boolean,
    val lines: BooleanArray,
)

/** Every row showing, top to bottom, and where each key is among them. */
internal class TreeRows<out T>(val rows: List<TreeRow<T>>, val index: Map<Any, Int>) {
    companion object {
        val Empty: TreeRows<Nothing> = TreeRows(emptyList(), emptyMap())
    }
}

/**
 * The tree laid end to end, as the rows a list shows.
 *
 * Walked with a stack of its own rather than by recursion, so a tree a thousand levels deep — a
 * linked list somebody opened all the way down — is a long list rather than a crash. Only an open
 * node is asked for its children, and nothing is asked whether it has any.
 */
internal fun <T> flattenTree(
    roots: List<T>,
    children: (T) -> List<T>,
    key: (T) -> Any,
    isExpanded: (Any) -> Boolean,
): TreeRows<T> {
    class Level(val nodes: List<T>, val parent: Int, val lines: BooleanArray) {
        var next = 0
    }

    val rows = ArrayList<TreeRow<T>>()
    val index = HashMap<Any, Int>()
    val stack = ArrayDeque<Level>()
    if (roots.isNotEmpty()) stack.addLast(Level(roots, -1, NoLines))

    while (stack.isNotEmpty()) {
        val level = stack.last()
        if (level.next >= level.nodes.size) {
            stack.removeLast()
            continue
        }
        val node = level.nodes[level.next++]
        val depth = stack.size - 1
        val last = level.next == level.nodes.size
        val lines = if (depth == 0) NoLines else level.lines.copyOf(depth).also { it[depth - 1] = !last }

        val rowKey = key(node)
        val at = rows.size
        // Two rows with one key would share their open state and their focus, and the list under
        // them would refuse the second. A key that comes round again is also how a cycle shows.
        require(index.put(rowKey, at) == null) { "two nodes in the tree have the key $rowKey; keys must be unique" }

        val expanded = isExpanded(rowKey)
        rows += TreeRow(node, rowKey, depth, level.parent, expanded, lines)
        if (expanded) {
            val kids = children(node)
            if (kids.isNotEmpty()) stack.addLast(Level(kids, at, lines))
        }
    }
    return TreeRows(rows, index)
}

private val NoLines = BooleanArray(0)

/**
 * What the arrows do, and how focus gets to a row that may not have been built.
 *
 * Asked by input between frames, so it reads the rows as they are now — worked out again when a
 * press earlier in the same frame has opened or closed something — rather than [TreeState.shown],
 * the rows as they were last shown. Two presses before the next frame then act one after the other:
 * a second Right goes into the children the first one opened, and a Down after a Left skips the
 * rows that Left closed away.
 */
private class TreeLogic(val state: TreeState) {

    var rtl = false

    /** The tree's own `hasChildren`, asked only of the row an arrow was pressed on. */
    var hasChildren: (Any?) -> Boolean = { false }

    /** The rows as they are now. Derived state, so reading it twice with nothing changed works nothing out. */
    var rows: State<TreeRows<*>> = mutableStateOf(TreeRows.Empty)

    fun direction(key: Any, pressed: FocusDirection): Boolean {
        val rows = rows.value
        val at = rows.index[key] ?: return false
        val row = rows.rows[at]
        val inwards = if (rtl) FocusDirection.Left else FocusDirection.Right
        val outwards = if (rtl) FocusDirection.Right else FocusDirection.Left
        return when (pressed) {
            FocusDirection.Up -> rows.rows.getOrNull(at - 1)?.let { focus(it.key); true } ?: false
            FocusDirection.Down -> rows.rows.getOrNull(at + 1)?.let { focus(it.key); true } ?: false
            inwards -> when {
                !hasChildren(row.node) -> false
                !row.expanded -> {
                    state.expand(key)
                    true
                }
                else -> {
                    // Into the first child. An open row with no children has nowhere to go, and
                    // still uses the press rather than letting it wander off sideways.
                    rows.rows.getOrNull(at + 1)?.takeIf { it.parent == at }?.let { focus(it.key) }
                    true
                }
            }
            outwards -> when {
                // An open key on a node with nothing to open is a leaf, and goes up like one.
                row.expanded && hasChildren(row.node) -> {
                    state.collapse(key)
                    true
                }
                row.parent >= 0 -> {
                    focus(rows.rows[row.parent].key)
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    /**
     * Focus onto the row for [key]: straight away when it has been built, and otherwise as soon as
     * it has, with the list scrolled to it so that it will be.
     */
    fun focus(key: Any) {
        val node = state.handles[key]?.node
        if (node != null) {
            state.pendingFocus = null
            focusOnNode(node)
            return
        }
        state.pendingFocus = key
        val now = rows.value
        val at = now.index[key] ?: return
        if (now === state.shown) {
            state.pendingFocusScroll = false
            state.listState.scrollToItem(at)
        } else {
            // The list still has the old rows, where this position is another row's or past the end,
            // so it is scrolled once the tree has been composed with these.
            state.pendingFocusScroll = true
        }
    }

    fun placed(key: Any, node: UiNode) {
        if (state.pendingFocus != key) return
        state.pendingFocus = null
        focusOnNode(node)
    }
}

@Composable
private fun <T> TreeRowItem(
    row: TreeRow<T>,
    expandable: Boolean,
    tree: TreeLogic,
    selected: Boolean,
    style: String,
    indent: Float,
    glyphSize: Float,
    spacing: Float,
    direction: LayoutDirection,
    rowModifier: Modifier,
    onSelect: () -> Unit,
    onDoubleClick: () -> Unit,
    content: @Composable (node: T, expanded: Boolean) -> Unit,
) {
    val state = tree.state
    val rowKey = row.key
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val resolved = rememberStyle(if (selected) "$style.row.selected" else "$style.row", states)

    val handle = remember(rowKey) { RowHandle() }
    DisposableEffect(rowKey, handle) {
        state.handles[rowKey] = handle
        onDispose {
            if (state.handles[rowKey] === handle) state.handles.remove(rowKey)
            // A row focus has already left is never needed again. One that still has focus is kept
            // until the tree's SideEffect has moved focus up from it, which runs after this.
            val node = handle.node
            if (node != null && state.focusedNode === node && state.focusManager?.focused !== node) {
                state.forgetFocusedNode()
            }
        }
    }
    val placed = remember(rowKey, handle) {
        PlacedHandler { node ->
            handle.node = node
            tree.placed(rowKey, node)
        }
    }
    // Read from the row's own interaction: a focused node tells the nodes round it, not itself.
    if (interaction.isFocused) {
        SideEffect {
            val node = handle.node
            state.focusedKey = rowKey
            state.focusedNode = node
            state.focusManager = node?.focusManagerOrNull()
        }
    }
    val directions = remember(rowKey) { DirectionHandler { tree.direction(rowKey, it) } }
    val tapped = rememberTapped(onSelect)

    val guide = rememberStyle("$style.guide")
    val rtl = direction == LayoutDirection.Rtl
    val lines = row.lines
    // Open and with something to show: an open key on a node with no children is drawn as a leaf.
    val open = expandable && row.expanded
    // The guides reach across the row's own padding and half the gap either side, so a level's line
    // runs unbroken down the rows rather than as a dash beside each one.
    val above = resolved.padding.top + spacing / 2f
    val below = resolved.padding.bottom + spacing / 2f
    val drawGuides: UiCanvas.(Rect) -> Unit = remember(guide, lines, indent, rtl, above, below) {
        { box -> drawGuides(box, guide.background, lines, indent, rtl, above, below) }
    }

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .interaction(interaction)
            .focusable(interaction)
            .clickable(onDoubleClick = onDoubleClick, onClick = tapped)
            .onFocusDirection(directions)
            .onPlaced(placed)
            .styled(resolved),
        name = "tree.row",
        content = {
            LeafLayout(name = "tree.guides", draw = drawGuides)
            if (expandable) {
                TreeToggle(open, "$style.toggle", states, indent, glyphSize, rtl) {
                    state.toggle(rowKey)
                    tree.focus(rowKey)
                }
            } else {
                LeafLayout(Modifier.size(indent, indent), name = "tree.leaf")
            }
            Box {
                ProvideContentStyle(resolved) { DisableSelection { content(row.node, open) } }
            }
        },
        measurePolicy = remember(row.depth, indent) { TreeRowPolicy(row.depth, indent) },
    )
}

/** The arrow: a column [indent] wide, pressed to open or close the row. */
@Composable
private fun TreeToggle(
    expanded: Boolean,
    style: String,
    states: Set<WidgetState>,
    indent: Float,
    glyphSize: Float,
    rtl: Boolean,
    onToggle: () -> Unit,
) {
    val resolved = rememberStyle(if (expanded) "$style.open" else style, states)
    val draw: UiCanvas.(Rect) -> Unit = remember(resolved, expanded, glyphSize, rtl) {
        { box -> drawToggle(box, resolved, expanded, glyphSize, rtl) }
    }
    // A nine-patch has no turned form to draw, so a closed one is flipped as a picture instead. A
    // canvas that cannot flip one draws it pointing the usual way, which is mirror's own bargain.
    val flip = rtl && !expanded && resolved.background is SkinDrawable.Patch
    LeafLayout(
        Modifier.size(indent, indent).mirror(horizontal = flip).clickable(onClick = onToggle),
        name = if (expanded) "tree.toggle.open" else "tree.toggle",
        draw = draw,
    )
}

/**
 * The arrow in the middle of [box]: the style's picture when it has one, and otherwise a triangle in
 * its text colour, pointing down when open and towards the end of the line when closed.
 */
private fun UiCanvas.drawToggle(box: Rect, style: ResolvedStyle, expanded: Boolean, size: Float, rtl: Boolean) {
    val cx = (box.left + box.right) / 2f
    val cy = (box.top + box.bottom) / 2f
    when (val art = style.background) {
        is SkinDrawable.Image, is SkinDrawable.Patch -> {
            val square = Rect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            // A closed row's picture points towards the end of the line, so it is turned round in a
            // right-to-left screen. An open one points down either way. A patch is flipped by the
            // toggle's own mirror, since only a whole picture can be turned here.
            if (art is SkinDrawable.Image && rtl && !expanded) {
                image(art.texture, square, 180f, tint = style.tint)
            } else {
                art.drawInto(this, square, style.tint)
            }
            return
        }
        else -> Unit
    }
    val long = size / 2f
    val short = size * Narrow / 2f
    val colour: Colour = style.textColour
    val points = when {
        expanded -> floatArrayOf(cx - long, cy - short, cx + long, cy - short, cx, cy + short)
        rtl -> floatArrayOf(cx + short, cy - long, cx - short, cy, cx + short, cy + long)
        else -> floatArrayOf(cx - short, cy - long, cx + short, cy, cx - short, cy + long)
    }
    fan(points, colour)
}

/** How wide the arrow is across its point, as a part of how long it is. */
private const val Narrow = 0.6f

/** How thick a guide line is. */
private const val GuideWidth = 1f

/**
 * The indent guides for one row, drawn into the indent in front of it.
 *
 * A level whose next sibling is further down draws straight through. The row's own level draws down
 * to its middle and across towards its arrow, and on down only when a sibling follows — so the last
 * child's line turns the corner and stops.
 */
private fun UiCanvas.drawGuides(
    box: Rect,
    line: SkinDrawable,
    lines: BooleanArray,
    indent: Float,
    rtl: Boolean,
    above: Float,
    below: Float,
) {
    val depth = lines.size
    if (depth == 0 || line == SkinDrawable.Blank) return
    val top = box.top - above
    val bottom = box.bottom + below
    val middle = (box.top + box.bottom) / 2f
    val half = GuideWidth / 2f

    fun at(along: Float) = if (rtl) box.right - along else box.left + along

    for (level in 0 until depth) {
        val x = at(level * indent + indent / 2f)
        val own = level == depth - 1
        if (!own) {
            if (lines[level]) line.drawInto(this, Rect(x - half, top, x + half, bottom), Colour.White)
            continue
        }
        val end = if (lines[level]) bottom else middle + half
        line.drawInto(this, Rect(x - half, top, x + half, end), Colour.White)
        val stub = at(depth * indent + indent * Stub)
        line.drawInto(this, Rect(minOf(x, stub), middle - half, maxOf(x, stub), middle + half), Colour.White)
    }
}

/** How far into the row's own arrow column the guide's corner reaches. */
private const val Stub = 0.2f

/**
 * The guides under the indent, the arrow in its column, and the row's content after both, centred
 * up and down; all of it the other way round in a right-to-left screen.
 */
private class TreeRowPolicy(private val depth: Int, private val indent: Float) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val lead = depth * indent
        val start = lead + indent
        val toggle = measurables[1].measure(Constraints(maxWidth = indent, maxHeight = constraints.maxHeight))
        val content = measurables[2].measure(
            Constraints(maxWidth = (constraints.maxWidth - start).coerceAtLeast(0f), maxHeight = constraints.maxHeight),
        )
        val width = constraints.constrainWidth(start + content.width)
        val height = constraints.constrainHeight(maxOf(toggle.height, content.height))
        val guides = measurables[0].measure(Constraints.fixed(lead, height))

        val mirrored = layoutDirection == LayoutDirection.Rtl
        fun x(at: Float, size: Float) = if (mirrored) width - at - size else at

        return layout(width, height) {
            guides.at(x(0f, lead), 0f)
            toggle.at(x(lead, toggle.width), (height - toggle.height) / 2f)
            content.at(x(start, content.width), (height - content.height) / 2f)
        }
    }
}
