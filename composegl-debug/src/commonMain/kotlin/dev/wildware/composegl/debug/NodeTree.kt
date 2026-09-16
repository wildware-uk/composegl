package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.debug.isDebugOverlay
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.node.NeverChanged
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.describeNumber
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.LocalContentStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.TreeState
import dev.wildware.composegl.ui.widget.TreeView

/**
 * The whole screen as a tree you can browse, with what each node costs beside it.
 *
 * ```kotlin
 * val inspection = rememberInspectorState()
 * Inspector(enabled = debug, state = inspection) { Game() }
 * NodeTree(inspection, Modifier.width(280f).height(320f))
 * ```
 *
 * `Inspector` answers "what is *this*?" for whatever the pointer is over. This answers "what is on
 * this screen at all?" — which is the only way to reach a node you cannot point at: one that is
 * invisible, zero-sized, clipped away, or sitting under something else.
 *
 * Each row says what the node is (`button #play`), how big it is, and how many frames have changed
 * it since the tree came up: `c` for the node itself being rebuilt — a new chain, a new drawing, a
 * child added or taken away — and `r` for frames that only redrew it, a marquee sliding or a spinner
 * turning. The numbers go red on the frame they tick and fade back, so a node that is quietly
 * costing a frame every frame is the one still glowing. They are `RedrawOverlay`'s numbers, read off
 * the nodes rather than flashed over them; counting is turned on while this is composed and off
 * again when it goes.
 *
 * - **The filter** above the tree keeps every node whose name or tag has that text in it, and the
 *   nodes above them so there is a way down to them. `#play` finds it by tag, `button` by name. The
 *   rows open up so every match is showing, and close back to how you had them when it is cleared.
 * - **`0x0`** leaves out nodes with no width or no height — the wrappers a layout is full of —
 *   except where a node that is showing sits under one.
 * - **Choosing a row** hands the node to [onSelect]. Linked to an inspector, that pins it: outlined
 *   in orange on the screen, with the inspector's panel filled in.
 * - **Following the pointer.** [expandTo] opens the rows down to a node and scrolls to it. Linked to
 *   an inspector, that is whatever the pointer is over, so the tree follows the pointer around.
 * - **Keyboard and pad** are the tree's own: up and down move, right opens or goes in, left closes
 *   or goes out, Enter or South chooses. It mirrors in a right-to-left screen.
 *
 * Only the rows that can be seen are built, so a screen of ten thousand nodes costs a screenful. The
 * rows are worked out again every frame and written only when they differ, so a still screen builds
 * nothing. Unlike the inspector this is skinned like any other tree, under `style`.
 *
 * Its own subtree is left out of the walk, so pointing it at the root of the whole host does not
 * show — or flash on — the tree itself.
 *
 * @param root the node whose children are the top rows. Null shows an empty tree.
 * @param state the filter, the switch and which rows are open.
 * @param selected the row drawn as chosen.
 * @param expandTo a node to open the rows down to and scroll to, each time it changes.
 * @param holdMillis how long a count stays red after it ticks.
 * @param style the skin style the rows are drawn with, as [TreeView] means it.
 */
@Composable
fun NodeTree(
    root: UiNode?,
    modifier: Modifier = Modifier,
    state: NodeTreeState = rememberNodeTreeState(),
    selected: UiNode? = null,
    onSelect: (UiNode) -> Unit = {},
    expandTo: UiNode? = null,
    holdMillis: Int = 500,
    style: String = "tree",
) {
    val latestRoot = rememberUpdatedState(root)
    val latestExpandTo = rememberUpdatedState(expandTo)
    val hold = holdMillis.toLong() * 1_000_000L

    // Counting is off until something asks for it. Asked for by this rather than switched on, so
    // two tools and a game's own setting never turn each other's counting off.
    val tree = root?.tree
    DisposableEffect(tree) {
        tree?.watchChanges()
        onDispose { tree?.stopWatchingChanges() }
    }

    LaunchedEffect(state, hold) {
        // Layout moves things without telling anyone, and a count ticks between frames. Looking
        // again each frame and writing only what differs keeps the rows true and a still screen still.
        while (true) {
            withFrameNanos { }
            val at = latestRoot.value
            state.refresh(at, latestExpandTo.value, at?.tree?.clocks?.frameNanos ?: NeverChanged, hold)
        }
    }
    DisposableEffect(state) { onDispose { state.clear() } }

    // Moves over the tree belong to the tree. Without this they reach whatever is under it — the
    // inspector's own layer, most of the time — and the rows would open somewhere else and scroll
    // away from under the pointer on its way to the row it was going for.
    val swallow = remember { PointerHandler { it is PointerEvent.Move } }

    val view = state.view
    Column(
        modifier.onPointer(swallow).testTag(NodeTreeTags.Root),
        verticalArrangement = Arrangement.spacedBy(4f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6f),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            TextField(
                value = state.filter,
                onValueChange = { state.filter = it },
                modifier = Modifier.weight(1f).testTag(NodeTreeTags.Filter),
                placeholder = "name or #tag",
            )
            Checkbox(
                checked = state.hideEmpty,
                onCheckedChange = { state.hideEmpty = it },
                modifier = Modifier.testTag(NodeTreeTags.HideEmpty),
                label = "0x0",
            )
        }
        if (view.roots.isEmpty()) {
            Text(
                if (root == null) "nothing on screen" else "nothing matches",
                modifier = Modifier.fillMaxWidth().testTag(NodeTreeTags.Nothing),
            )
        } else {
            TreeView(
                roots = view.roots,
                children = { view.children[it] ?: emptyList() },
                key = { it },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(NodeTreeTags.Tree),
                selected = selected,
                onSelect = onSelect,
                state = state.rows,
                hasChildren = { !view.children[it].isNullOrEmpty() },
                style = style,
            ) { node, _ -> NodeTreeRow(node, state, hold) }
        }
    }
}

/**
 * The same tree, wired to an [Inspector] sharing [inspector]: rows follow what the pointer is over,
 * and choosing one pins it, which outlines it on the screen and fills the inspector's panel.
 */
@Composable
fun NodeTree(
    inspector: InspectorState,
    modifier: Modifier = Modifier,
    state: NodeTreeState = rememberNodeTreeState(),
    holdMillis: Int = 500,
    style: String = "tree",
) = NodeTree(
    root = inspector.screen,
    modifier = modifier,
    state = state,
    selected = inspector.selected,
    onSelect = { inspector.pin(it) },
    // What the pointer is over, not what a row is over: the tree must not chase its own pointer.
    expandTo = inspector.hovered,
    holdMillis = holdMillis,
    style = style,
)

/** Test tags on the tree's own nodes, prefixed so they never meet a game's. */
object NodeTreeTags {
    const val Root = "nodetree:root"
    const val Tree = "nodetree:tree"
    const val Filter = "nodetree:filter"
    const val HideEmpty = "nodetree:hide-empty"
    const val Nothing = "nodetree:nothing"

    /** A row, by the node's own tag. Untagged nodes have untagged rows. */
    fun row(tag: String) = "nodetree:row:$tag"

    /** The counts on that row. */
    fun counts(tag: String) = "nodetree:counts:$tag"
}

/**
 * A [NodeTreeState] that lives as long as the screen it is on.
 *
 * Not saved: what it holds is the nodes themselves, and a node from a screen that has gone is not
 * something to bring back.
 */
@Composable
fun rememberNodeTreeState(): NodeTreeState = remember { NodeTreeState() }

/**
 * What a [NodeTree] is showing: the filter, whether zero-sized nodes are left out, and which rows
 * are open.
 *
 * Read and written from composition, and refilled from the frame loop between frames. Everything a
 * row is drawn from is worked out there and written only when it differs, so a screen standing still
 * builds no rows.
 */
class NodeTreeState {

    /** Keeps the nodes whose name or tag has this text in them, and the nodes above them. */
    var filter by mutableStateOf("")

    /** Leaves out nodes with no width or no height, unless a node that is showing is under one. */
    var hideEmpty by mutableStateOf(false)

    /** Which rows are open and where the list has scrolled to: the tree widget's own state. */
    val rows = TreeState()

    /** The rows as they are now, written by [refresh] only when they differ. */
    internal var view by mutableStateOf(NodeTreeView.Empty)

    /**
     * The frame time the rows work their flash out against, or [NeverChanged] when nothing is
     * flashing. Written only while something is, so a still screen recomposes no rows.
     */
    internal var flashNanos by mutableStateOf(NeverChanged)

    /** The filter the rows were last opened for, so a new one opens them and clearing it puts them back. */
    private var lastFilter = ""

    /** Which rows were open before a filter opened everything. Null when no filter is on. */
    private var savedOpen: Set<Any>? = null

    /** The node [refresh] last opened the rows down to, so the same one does not scroll every frame. */
    private var openedTo: UiNode? = null

    /**
     * Reads the tree again: which nodes show, which are flashing, and where the rows should be.
     *
     * @param now the frame time the counts are aged against, [NeverChanged] with no tree.
     * @param holdNanos how long a count stays flashed after it ticks.
     */
    internal fun refresh(root: UiNode?, expandTo: UiNode?, now: Long, holdNanos: Long) {
        if (root == null) {
            clear()
            return
        }
        cleared = false
        val needle = filter.trim()
        val shown = HashMap<UiNode, List<UiNode>>()
        val alive = HashSet<UiNode>()
        // A hold of nothing still wants the frame the count ticked on, or the numbers would stand
        // still: the row is only built again because something wrote here.
        val window = if (now == NeverChanged) 0L else maxOf(holdNanos, OneFrameNanos)
        var flashing = false

        fun visit(node: UiNode): Boolean {
            // Its own subtree, and the overlays, are the tooling looking at itself: left out so that
            // a tree pointed at the whole host neither lists nor flashes on the tooling.
            if (node.testTag == NodeTreeTags.Root || isDebugOverlay(node)) return false
            alive += node
            var kids: MutableList<UiNode>? = null
            node.children.forEach { child ->
                if (visit(child)) (kids ?: ArrayList<UiNode>(node.children.size).also { kids = it }) += child
            }
            val mine = matches(node, needle) && !(hideEmpty && (node.width <= 0f || node.height <= 0f))
            val here = kids
            if (here != null) shown[node] = here
            // A node that does not match itself is still the way down to one that does.
            if (!mine && here == null) return false
            // Only a node with a row to flash on: one filtered away costs nobody a frame.
            val at = node.changedAtNanos
            if (at != NeverChanged && now - at in 0 until window) flashing = true
            return true
        }
        visit(root)

        val next = NodeTreeView(shown[root] ?: emptyList(), shown)
        if (next != view) view = next
        // Written every frame while anything is flashing, which is what builds the rows again so
        // their counts and their colour are this frame's; once, going cold, when the last fade ends.
        if (flashing) flashNanos = now else if (flashNanos != NeverChanged) flashNanos = NeverChanged

        forgetRowsNotIn(alive)
        openForFilter(needle, shown.keys)
        openDownTo(root, expandTo)
    }

    /** Forgets what is open on a node that has left the tree, which would otherwise hold it alive. */
    private fun forgetRowsNotIn(alive: Set<UiNode>) {
        rows.expandedKeys.forEach { if (it !is UiNode || it !in alive) rows.collapse(it) }
        savedOpen?.let { saved ->
            val kept = saved.filterTo(HashSet()) { it !is UiNode || it in alive }
            if (kept.size != saved.size) savedOpen = kept
        }
    }

    /**
     * A filter opens every row that has one under it, so each match is on screen; clearing it puts
     * the rows back the way they were before the first letter was typed.
     */
    private fun openForFilter(needle: String, branches: Set<UiNode>) {
        if (needle == lastFilter) return
        if (lastFilter.isEmpty()) savedOpen = rows.expandedKeys
        lastFilter = needle
        if (needle.isNotEmpty()) {
            rows.expandAll(branches)
            return
        }
        rows.collapseAll()
        savedOpen?.let { rows.expandAll(it) }
        savedOpen = null
    }

    /** Opens the rows down to [wanted] and scrolls to it, once each time it changes. */
    private fun openDownTo(root: UiNode, wanted: UiNode?) {
        if (wanted == null) {
            openedTo = null
            return
        }
        if (wanted === openedTo) return
        openedTo = wanted
        val path = ArrayList<UiNode>()
        var walk = wanted.parent
        while (walk != null && walk !== root) {
            path += walk
            walk = walk.parent
        }
        // Not under this tree's root at all — another screen's node, or one already taken off.
        if (walk !== root) return
        rows.expandAll(path)
        rows.scrollTo(wanted)
    }

    /** Whether there is nothing left to let go of, so a tree with no root costs one branch a frame. */
    private var cleared = false

    /** Lets go of every node it was holding: what a tree leaving the screen does. */
    internal fun clear() {
        if (cleared) return
        cleared = true
        if (view !== NodeTreeView.Empty) view = NodeTreeView.Empty
        if (flashNanos != NeverChanged) flashNanos = NeverChanged
        rows.collapseAll()
        savedOpen = null
        openedTo = null
        lastFilter = ""
    }
}

/**
 * Whether a node is what the filter asked for, matched against the name the rows show — so `button`
 * finds it by name and `#play` by tag. Empty matches everything.
 */
private fun matches(node: UiNode, needle: String): Boolean =
    needle.isEmpty() || titleOf(node).contains(needle, ignoreCase = true)

/** The rows as they stand: the top ones, and each showing node's showing children. */
internal data class NodeTreeView(
    val roots: List<UiNode>,
    val children: Map<UiNode, List<UiNode>>,
) {
    companion object {
        val Empty = NodeTreeView(emptyList(), emptyMap())
    }
}

/** How faded the size beside a row's name is against the row's own text colour. */
private const val Faded = 0.6f

/** A frame at sixty a second: the shortest a count can be watched over and still be seen. */
private const val OneFrameNanos = 16_666_667L

/** One row: what the node is, how big, and what it has cost. */
@Composable
private fun NodeTreeRow(node: UiNode, state: NodeTreeState, holdNanos: Long) {
    // Read here, so a count ticking builds this row again on that frame. Written only while
    // something is flashing, so a screen standing still builds nothing.
    val now = state.flashNanos
    val colour = LocalContentStyle.current?.textColour ?: InspectorColours.Bright
    val counts = countsOf(node)
    val tag = node.testTag
    var row = Modifier.fillMaxWidth()
    if (tag != null) row = row.testTag(NodeTreeTags.row(tag))
    Row(
        row,
        horizontalArrangement = Arrangement.spacedBy(6f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text(titleOf(node), maxLines = 1, softWrap = false)
        Text(
            "${describeNumber(node.width)}x${describeNumber(node.height)}",
            colour = colour.scaleAlpha(Faded),
            maxLines = 1,
            softWrap = false,
        )
        if (counts != null) {
            val numbers = if (tag == null) Modifier else Modifier.testTag(NodeTreeTags.counts(tag))
            Text(
                counts,
                modifier = numbers,
                colour = colour.lerp(RedrawColour, heatOf(node, now, holdNanos)),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** How red a row's counts are: full on the frame they ticked, fading to none over the hold. */
private fun heatOf(node: UiNode, now: Long, holdNanos: Long): Float {
    val at = node.changedAtNanos
    if (at == NeverChanged || now == NeverChanged || holdNanos <= 0L) return 0f
    val age = now - at
    if (age < 0L || age >= holdNanos) return 0f
    return 1f - age.toFloat() / holdNanos
}

/**
 * The counts as a row shows them — `c12` for frames that rebuilt the node, `r300` for frames that
 * only redrew it — leaving out a half that is nothing, and the lot for a node nothing has touched.
 */
private fun countsOf(node: UiNode): String? {
    val built = node.composeChanges
    val drawn = node.redrawChanges
    return when {
        built > 0 && drawn > 0 -> "c$built r$drawn"
        built > 0 -> "c$built"
        drawn > 0 -> "r$drawn"
        else -> null
    }
}
