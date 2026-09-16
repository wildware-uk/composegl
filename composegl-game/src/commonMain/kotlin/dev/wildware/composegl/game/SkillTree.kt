package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.focus.FocusRequester
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.boxSweep
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.focusOrder
import dev.wildware.composegl.ui.modifier.focusRequester
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.widget.DisableSelection
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.PanZoomCanvas
import dev.wildware.composegl.ui.widget.PanZoomReset
import dev.wildware.composegl.ui.widget.PanZoomState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Tooltip
import dev.wildware.composegl.ui.widget.rememberPanZoomState
import dev.wildware.composegl.ui.widget.worldPosition
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What a [SkillNode] is to a player looking at it.
 *
 * Worked out by the tree from the graph rather than set by the game, because "can I take this yet"
 * is a question about the edges leading into a node and nothing else. What the game decides is how
 * many ranks a node has, how many of them are bought, and whether it is [SkillNode.enabled] at all.
 */
enum class SkillState {
    /** Its way in has not been opened yet, or the game has switched it off. Read it, do not take it. */
    Locked,

    /** Nothing is bought yet and everything it needs is. The next press spends a point here. */
    Available,

    /** Bought, with ranks still to buy. Both a thing that is owned and a thing that can be taken again. */
    Owned,

    /** Every rank is bought. Nothing more to spend here. */
    Maxed,
}

/** When a node with more than one way in opens. See [SkillTree]. */
enum class SkillUnlock {
    /** Every node leading into it must be owned: the usual tech tree, where a branch is a prerequisite. */
    All,

    /** One is enough: the usual talent grid, where two paths meet at the same skill. */
    Any,
}

/**
 * One node of a [SkillTree]: where it is, how many ranks it has, and how many are bought.
 *
 * Its place is in the tree's own world units, the ones the camera pans and zooms over, and it is
 * the **middle** of the node rather than a corner — a graph is drawn from its nodes' centres, and
 * the lines between them would otherwise all miss.
 *
 * A plain value the game rebuilds as it composes, like a [HotbarSlot]: the skills stay wherever the
 * game keeps them, and this is what the tree is shown.
 *
 * @param id what the [SkillEdge]s name it by, and what keeps its state as the list is rebuilt.
 *   Anything with sensible equality — a string, an enum entry, an int.
 * @param ranks how many times it can be taken. One is an ordinary on-or-off skill.
 * @param rank how many of those are bought. Zero is untaken, [ranks] is maxed.
 * @param icon a region in the skin's atlas.
 * @param label what to draw when there is no art — a letter, an abbreviation.
 * @param tooltip what a hover or a pad landing on it says. Needs a
 *   [dev.wildware.composegl.ui.widget.TooltipHost] round the screen, as every tooltip does; null
 *   asks for none and needs no host.
 * @param enabled whether the game will allow it at all right now — enough points, high enough level,
 *   the right class. A node that is switched off never becomes [SkillState.Available], and is drawn
 *   as the skin's disabled. Ranks already bought stay bought.
 */
data class SkillNode(
    val id: Any,
    val x: Float,
    val y: Float,
    val ranks: Int = 1,
    val rank: Int = 0,
    val icon: String? = null,
    val label: String? = null,
    val tooltip: String? = null,
    val enabled: Boolean = true,
) {

    init {
        require(ranks >= 1) { "a skill has to have a rank to buy: $id has $ranks" }
        require(rank in 0..ranks) { "$id is at rank $rank of $ranks" }
    }
}

/**
 * A line from one node to another: "this one opens that one".
 *
 * Directed, because a tree is: [from] is the prerequisite and [to] is what it opens. The pad walks
 * it both ways all the same — a player going back up a branch presses the other direction.
 *
 * An edge naming a node that is not in the list is ignored, so a tree can be filtered down to one
 * branch without also filtering its edges.
 */
data class SkillEdge(val from: Any, val to: Any)

/**
 * A box round every node, [margin] world units clear of the outermost ones.
 *
 * What a [PanZoomState] wants for its `bounds`, so the player cannot drag the tree off into empty
 * space. An empty list gives a box of [margin] round the origin, because a camera needs some world
 * to look at.
 *
 * ```kotlin
 * val camera = rememberPanZoomState(bounds = remember(skills) { skillTreeBounds(skills) })
 * ```
 */
fun skillTreeBounds(nodes: List<SkillNode>, margin: Float = 120f): Rect {
    if (nodes.isEmpty()) return Rect(-margin, -margin, margin, margin)
    var left = nodes[0].x
    var top = nodes[0].y
    var right = left
    var bottom = top
    for (node in nodes) {
        if (node.x < left) left = node.x
        if (node.x > right) right = node.x
        if (node.y < top) top = node.y
        if (node.y > bottom) bottom = node.y
    }
    return Rect(left - margin, top - margin, right + margin, bottom + margin)
}

/**
 * A graph of unlockable nodes joined by lines: a skill tree, a tech tree, a talent grid, a
 * constellation board.
 *
 * ```kotlin
 * val camera = rememberPanZoomState(bounds = remember { skillTreeBounds(skills) })
 *
 * SkillTree(
 *     nodes = skills,
 *     edges = links,
 *     state = camera,
 *     onActivate = { player.unlock(it.id) },
 * )
 *
 * // Fly to whatever just opened.
 * camera.animateTo(centre = Offset(skill.x, skill.y), zoom = 1.5f)
 * ```
 *
 * It is a [PanZoomCanvas] with the graph on it, so everything that plane can do it can do: drag to
 * pan, wheel or pinch to zoom, double click to fit, the pad's stick and triggers. The nodes are
 * ordinary widgets laid out once in world units, which is what makes a node as sharp at three times
 * its size as at its own, and what makes clicks, hover, focus rings and tooltips work inside the
 * plane with nothing special written for them.
 *
 * **The tree works out each node's [SkillState] itself.** A node with every way in owned, and
 * nothing bought, is [SkillState.Available]; one with ranks bought is [SkillState.Owned] until the
 * last, then [SkillState.Maxed]; anything else is [SkillState.Locked]. [unlock] says whether a node
 * with two ways in needs both of them or either. A node with no way in is a root and starts open.
 * The game is left with the one decision that is really its own — [SkillNode.enabled], "will I let
 * you, right now" — and with spending the point when [onActivate] says a node was taken.
 *
 * **The lines are drawn by the canvas's background**, in world units, as one batched pass rather
 * than a node per line, and lines nowhere near the view are skipped — so a tree of a thousand links
 * costs the few dozen on screen. They are tinted by what they join: `"<style>.edge.owned"` for a
 * line into something bought, `"<style>.edge.available"` for the one you could spend on next, and
 * `"<style>.edge.locked"` for the rest. When a node is taken, the lines that opened it fill with
 * `"<style>.edge.fill"` from the old node to the new one over [unlockMillis].
 *
 * **The pad and the arrows follow the edges first.** A direction from a node goes along whichever
 * line leaves it nearest that way; only where no line goes that way does the toolkit's ordinary
 * nearest-in-direction search take over, so a tree with a gap in it still walks. The camera eases to
 * keep the focused node in view, because the canvas already brings a focused child into view.
 *
 * **Taking a node is a hold**, not a click: [holdMillis] of the same long press every other widget
 * uses, so a mis-click does not spend a point. The node fills clockwise while it is held, and the
 * fill is what tells a player how long "held" is. It works the same from a mouse, from Enter and
 * from the pad's South button, because all three are a press and a release. [holdMillis] of zero
 * makes it a plain click for a tree where a point is cheap.
 *
 * **Testing one.** A press starts the hold's animation, and `UiTest`'s helpers settle until nothing
 * is moving — so a press in a `uiTest` runs the whole hold out and buys the node. Drive the frames
 * by hand, the way `composegl-game`'s own `SkillTreeTest` does, to test a hold that is let go early.
 *
 * Everything it looks like is the skin's: `"<style>.node.locked"`, `"<style>.node.available"`,
 * `"<style>.node.owned"`, `"<style>.node.maxed"`, `"<style>.hold"` for the hold's sweep,
 * `"<style>.rank"` for the "2 / 3" in the corner, and the four edge styles above.
 *
 * @param state the camera. One of its own by default, which has no bounds; a tree that should not be
 *   draggable into empty space passes [skillTreeBounds] — which is also what [reset] fits to, so a
 *   camera with no bounds has nothing to fit and a double click does nothing.
 * @param onActivate called with the node when a press finishes on one that can be taken — that is
 *   [SkillState.Available] or [SkillState.Owned] with a rank left. Never called for a locked, maxed
 *   or switched-off node, so a game can spend the point without asking again.
 * @param nodeSize how big a node is, in world units.
 * @param edgeWidth how thick a line is, in world units, so it thins out as the tree is zoomed out.
 * @param unlockMillis how long a line takes to fill when the node it leads to is taken. Zero draws
 *   the new colour at once.
 * @param clock what the hold and the fill are timed on. The interface's, so a tree open over a
 *   paused game still works; a tree that is part of the world passes [Clock.World].
 * @param content what a node is drawn as, inside the skin's frame for its state. The default draws
 *   its icon or its label, and its rank in the corner when it has more than one.
 */
@Composable
fun SkillTree(
    nodes: List<SkillNode>,
    edges: List<SkillEdge>,
    modifier: Modifier = Modifier,
    state: PanZoomState = rememberPanZoomState(),
    onActivate: (SkillNode) -> Unit = {},
    unlock: SkillUnlock = SkillUnlock.All,
    style: String = "skilltree",
    nodeSize: Float = 48f,
    edgeWidth: Float = 3f,
    holdMillis: Int = DefaultHoldMillis,
    unlockMillis: Int = DefaultUnlockMillis,
    reset: PanZoomReset = PanZoomReset.Fit,
    focusable: Boolean = true,
    clock: Clock = Clock.Ui,
    content: @Composable (node: SkillNode, state: SkillState) -> Unit = { node, nodeState ->
        SkillNodeIcon(node, nodeState, style, nodeSize)
    },
) {
    val graph = remember(nodes, edges, unlock) { SkillGraph(nodes, edges, unlock) }

    // One per node, so a direction can name the node an edge leads to instead of being scored by
    // geometry. Made with the graph, because a node leaving the list takes its requester with it.
    val requesters = remember(graph) { List(nodes.size) { FocusRequester() } }

    val locked = rememberStyle("$style.edge.locked").fill()
    val available = rememberStyle("$style.edge.available").fill()
    val owned = rememberStyle("$style.edge.owned").fill()
    val fillColour = rememberStyle("$style.edge.fill").fill()

    // What has just been bought, and how far the lines into it have filled. One run at a time: a
    // player taking two nodes in quick succession sees the second start from the beginning, which
    // is what they are looking at anyway.
    val clocks = LocalClocks.current
    val filling = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }
    var justTaken by remember { mutableStateOf(emptySet<Any>()) }
    val ranks = remember(graph) { nodes.associate { it.id to it.rank } }
    var previousRanks by remember { mutableStateOf<Map<Any, Int>?>(null) }

    LaunchedEffect(ranks) {
        val before = previousRanks
        previousRanks = ranks
        // The first look is not an unlock: a tree opened on a half-spent character must not play
        // every line it has ever filled.
        val gained =
            if (before == null) emptySet() else ranks.keys.filter { ranks.getValue(it) > (before[it] ?: 0) }.toSet()
        if (gained.isEmpty() || unlockMillis <= 0) {
            // Nothing new — a respec, a node switched off — and whatever was filling is taken down
            // with it, rather than left halfway along its line for ever.
            justTaken = emptySet()
            filling.snapTo(1f)
            return@LaunchedEffect
        }
        justTaken = gained
        filling.snapTo(0f)
        filling.animateTo(1f, Tween(unlockMillis, easing = Easings.EaseOut))
        justTaken = emptySet()
    }

    // Remembered on everything it draws, as every widget's own draw is: the canvas asks for a redraw
    // when the background it was handed changes, and while a line is filling that is every frame.
    val painter = remember(graph, locked, available, owned, fillColour, edgeWidth, justTaken, filling.value) {
        SkillEdgePainter(graph, locked, available, owned, fillColour, edgeWidth, justTaken, filling.value)
    }
    val background: UiCanvas.(Rect) -> Unit = remember(painter) { { visible -> painter.draw(this, visible) } }

    PanZoomCanvas(
        state = state,
        modifier = modifier,
        background = background,
        reset = reset,
        focusable = focusable,
        style = "$style.plane",
    ) {
        nodes.forEachIndexed { index, node ->
            key(node.id) {
                SkillNodeView(
                    node = node,
                    nodeState = graph.states[index],
                    style = style,
                    size = nodeSize,
                    holdMillis = holdMillis,
                    clock = clock,
                    requester = requesters[index],
                    up = requesters.getOrNull(graph.neighbour(index, NodeUp)),
                    down = requesters.getOrNull(graph.neighbour(index, NodeDown)),
                    left = requesters.getOrNull(graph.neighbour(index, NodeLeft)),
                    right = requesters.getOrNull(graph.neighbour(index, NodeRight)),
                    onActivate = { onActivate(node) },
                    content = content,
                )
            }
        }
    }
}

/** How long a node has to be held before it is taken. The toolkit's own long press. */
const val DefaultHoldMillis = 500

/** How long a line takes to fill when what it leads to is taken. */
const val DefaultUnlockMillis = 420

/**
 * What a [SkillTree] draws inside a node by default: its picture, or its letters.
 *
 * Public so a game that wants one node drawn its own way can still draw every other one the plain
 * way — `content = { node, state -> if (node.id == "ultimate") Ultimate(node) else
 * SkillNodeIcon(node, state) }`.
 */
@Composable
fun SkillNodeIcon(
    node: SkillNode,
    state: SkillState,
    style: String = "skilltree",
    size: Float = 48f,
) {
    val frame = rememberStyle("$style.node.${state.skinName}")

    // Unselectable inside a SelectionContainer, so a drag across the letters pans the tree rather
    // than selecting them.
    DisableSelection {
        when {
            node.icon != null -> Image(node.icon, Modifier.size(size * IconFraction), tint = frame.textColour)
            node.label != null -> Text(node.label, textStyle = frame.textStyle, colour = frame.textColour)
        }
    }
}

/** How much of a node the picture in it takes up. The rest is the frame round it. */
private const val IconFraction = 0.55f

/** How far under a node its "2 / 3" sits, in world units. */
private const val RankGap = 3f

/** The four directions a neighbour is looked up by, as indices into the graph's table. */
internal const val NodeUp = 0
internal const val NodeDown = 1
internal const val NodeLeft = 2
internal const val NodeRight = 3

/** The skin's name for a state: `"skilltree.node.available"`. */
private val SkillState.skinName: String
    get() = when (this) {
        SkillState.Locked -> "locked"
        SkillState.Available -> "available"
        SkillState.Owned -> "owned"
        SkillState.Maxed -> "maxed"
    }

/**
 * The colour a style fills with.
 *
 * A line is a shape rather than a box, so it takes a colour and cannot take a nine-patch. A skin
 * that puts art here gets nothing drawn, which is visible, rather than a stretched picture in the
 * shape of a line, which is not what anybody meant.
 */
private fun ResolvedStyle.fill(): Colour = background.flatColour ?: Colour.Transparent

/**
 * One node: the skin's frame for its state, the hold filling it while a player leans on it, and
 * whatever the tree was told to draw inside.
 */
@Composable
private fun SkillNodeView(
    node: SkillNode,
    nodeState: SkillState,
    style: String,
    size: Float,
    holdMillis: Int,
    clock: Clock,
    requester: FocusRequester,
    up: FocusRequester?,
    down: FocusRequester?,
    left: FocusRequester?,
    right: FocusRequester?,
    onActivate: () -> Unit,
    content: @Composable (SkillNode, SkillState) -> Unit,
) {
    val interaction = remember { InteractionState() }
    val resolved = rememberStyle(
        "$style.node.${nodeState.skinName}",
        rememberStates(interaction, node.enabled),
    )
    val holdStyle = rememberStyle("$style.hold")

    // A switched-off node keeps whatever was bought, so a half-bought one is still Owned — but it
    // cannot be taken any further. The state alone is not enough to answer that; the switch is.
    val takeable =
        node.enabled && (nodeState == SkillState.Available || nodeState == SkillState.Owned)

    // Filling clockwise from twelve while it is held, and gone the instant it is let go: a player
    // who changes their mind halfway must not be shown a node that is still half taken. The press
    // is the toolkit's, so a finger sliding off the node ends the hold like every other widget.
    val clocks = LocalClocks.current
    val hold = remember(clocks, clock) { Animatable(0f, FloatVectoriser, clock, clocks) }
    val holding = takeable && holdMillis > 0 && interaction.isPressed
    LaunchedEffect(holding, holdMillis) {
        if (holding) {
            hold.snapTo(0f)
            hold.animateTo(1f, Tween(holdMillis, easing = Easings.Linear))
        } else {
            hold.snapTo(0f)
        }
    }

    val painter = remember(holdStyle, hold.value) { SkillHoldPainter(hold.value, holdStyle.fill()) }

    val place = Modifier.worldPosition(node.x, node.y, anchor = Alignment.Centre)
    val body: @Composable (Modifier) -> Unit = { outer ->
        Box(
            modifier = outer
                .size(size)
                .interaction(interaction)
                .focusable(interaction)
                .focusRequester(requester)
                .focusOrder(up = up, down = down, left = left, right = right)
                .clickable(
                    enabled = takeable,
                    onLongPress = if (holdMillis > 0) onActivate else null,
                    clock = clock,
                    longPressMillis = if (holdMillis > 0) holdMillis else DefaultHoldMillis,
                    onClick = if (holdMillis > 0) NoClick else onActivate,
                )
                .styled(resolved)
                .drawInFront(painter::draw),
            contentAlignment = Alignment.Centre,
        ) {
            content(node, nodeState)
        }
    }

    // Only wrapped when there is something to say, so a tree with no tooltips needs no TooltipHost.
    if (node.tooltip != null) Tooltip(node.tooltip, place) { body(Modifier) } else body(place)

    // Under the node rather than in it. A node is barely wider than its icon, and "2 / 3" written
    // across the icon of a skill that is half bought is the one number a player actually reads
    // covering the one picture they recognise it by. Drawn only when there is more than one rank,
    // because "1 / 1" on every square of a tree is a tree nobody can read.
    if (node.ranks > 1) {
        val rank = rememberStyle("$style.rank")
        Text(
            "${node.rank} / ${node.ranks}",
            modifier = Modifier.worldPosition(node.x, node.y + size / 2f + RankGap, anchor = Alignment.TopCentre),
            textStyle = rank.textStyle,
            colour = rank.textColour,
        )
    }
}

private val NoClick: () -> Unit = {}

/** The hold filling a node, held apart from the composable so it is a value that compares. */
internal class SkillHoldPainter(private val fraction: Float, private val colour: Colour) {

    fun draw(canvas: UiCanvas, bounds: Rect) {
        if (fraction <= 0f) return
        canvas.fan(boxSweep(bounds, 0f, fraction), colour)
    }
}

/**
 * The lines, drawn in world units under the nodes.
 *
 * One pass over the edges rather than a node each: a node per line would be a thousand things to
 * compose, lay out and place for a tree that draws a few dozen of them. Lines with no part in view
 * are skipped, which is what the canvas hands the visible world over for.
 */
internal class SkillEdgePainter(
    private val graph: SkillGraph,
    private val locked: Colour,
    private val available: Colour,
    private val owned: Colour,
    private val fill: Colour,
    private val width: Float,
    private val justTaken: Set<Any>,
    private val progress: Float,
) {

    fun draw(canvas: UiCanvas, visible: Rect) {
        val links = graph.links
        var at = 0
        while (at < links.size) {
            val from = graph.nodes[links[at]]
            val to = graph.nodes[links[at + 1]]
            at += 2

            val box = Rect(
                minOf(from.x, to.x) - width,
                minOf(from.y, to.y) - width,
                maxOf(from.x, to.x) + width,
                maxOf(from.y, to.y) + width,
            )
            if (!box.overlaps(visible)) continue

            val start = Offset(from.x, from.y)
            val end = Offset(to.x, to.y)
            val fromState = graph.states[links[at - 2]]
            val toState = graph.states[links[at - 1]]
            canvas.line(start, end, width, tint(fromState, toState))

            // The fill runs from the node that opened it to the node that was just taken, so the
            // player's eye is carried to what they bought rather than away from it.
            if (progress < 1f && to.id in justTaken && fromState.isBought) {
                val head = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                canvas.line(start, head, width * FillWidth, fill)
            }
        }
    }

    private fun tint(from: SkillState, to: SkillState): Colour = when {
        to.isBought -> owned
        from.isBought && to == SkillState.Available -> available
        else -> locked
    }

    private companion object {
        /** The fill is a little fatter than the line it runs along, so it reads as a light going on. */
        const val FillWidth = 1.6f
    }
}

/** Whether a rank has been spent here: an owned node and a maxed one both open what they lead to. */
private val SkillState.isBought: Boolean
    get() = this == SkillState.Owned || this == SkillState.Maxed

/**
 * The tree worked out once: what each node's state is, which lines really join two nodes, and which
 * node each direction leaves a node by.
 *
 * Built in a `remember` on the nodes and the edges, so a pan, a zoom or a hold costs none of it, and
 * taking a node — which changes a rank, and so the list — builds it again, which is exactly when the
 * answers change.
 */
internal class SkillGraph(val nodes: List<SkillNode>, edges: List<SkillEdge>, unlock: SkillUnlock) {

    /** Every edge whose two ends are both in the list, as pairs of indices: from, to, from, to… */
    val links: IntArray

    /** What each node is, in the same order as [nodes]. */
    val states: List<SkillState>

    /** Four entries per node — up, down, left, right — each a node index or -1 for no line that way. */
    private val neighbours: IntArray

    init {
        val index = HashMap<Any, Int>(nodes.size)
        for (at in nodes.indices) index.getOrPut(nodes[at].id) { at }

        val pairs = ArrayList<Int>(edges.size * 2)
        for (edge in edges) {
            val from = index[edge.from] ?: continue
            val to = index[edge.to] ?: continue
            if (from == to) continue
            pairs += from
            pairs += to
        }
        links = IntArray(pairs.size) { pairs[it] }

        states = buildStates(unlock)
        neighbours = buildNeighbours()
    }

    /** The node a direction leaves [node] by along a line, or -1 when no line goes that way. */
    fun neighbour(node: Int, direction: Int): Int = neighbours[node * 4 + direction]

    private fun buildStates(unlock: SkillUnlock): List<SkillState> {
        // How many ways into each node there are, and how many of those are bought.
        val ways = IntArray(nodes.size)
        val open = IntArray(nodes.size)
        var at = 0
        while (at < links.size) {
            val to = links[at + 1]
            ways[to]++
            if (nodes[links[at]].rank > 0) open[to]++
            at += 2
        }
        return List(nodes.size) { node ->
            val skill = nodes[node]
            val reachable = when {
                ways[node] == 0 -> true
                unlock == SkillUnlock.Any -> open[node] > 0
                else -> open[node] == ways[node]
            }
            when {
                skill.rank >= skill.ranks -> SkillState.Maxed
                skill.rank > 0 -> SkillState.Owned
                skill.enabled && reachable -> SkillState.Available
                else -> SkillState.Locked
            }
        }
    }

    /**
     * Which line each direction follows, both ways along every edge — a player walking back up a
     * branch presses the opposite direction and expects to arrive where they came from.
     *
     * A line is put in the direction it mostly goes, and where two lines leave a node the same way
     * the straighter one wins, then the shorter. That is the rule a player already believes: down
     * from a node with two children below it goes to the one more nearly underneath.
     */
    private fun buildNeighbours(): IntArray {
        val best = IntArray(nodes.size * 4) { -1 }
        val bestSlant = FloatArray(nodes.size * 4)
        val bestLength = FloatArray(nodes.size * 4)

        var at = 0
        while (at < links.size) {
            offer(best, bestSlant, bestLength, links[at], links[at + 1])
            offer(best, bestSlant, bestLength, links[at + 1], links[at])
            at += 2
        }
        return best
    }

    private fun offer(best: IntArray, bestSlant: FloatArray, bestLength: FloatArray, from: Int, to: Int) {
        val dx = nodes[to].x - nodes[from].x
        val dy = nodes[to].y - nodes[from].y
        val across = minOf(abs(dx), abs(dy))
        val along = maxOf(abs(dx), abs(dy))
        // Two nodes in the same place: no direction leads from one to the other.
        if (along <= 0f) return

        val direction = when {
            abs(dx) >= abs(dy) -> if (dx > 0f) NodeRight else NodeLeft
            else -> if (dy > 0f) NodeDown else NodeUp
        }
        val slot = from * 4 + direction
        val slant = across / along
        val length = sqrt(dx * dx + dy * dy)
        val better = best[slot] < 0 ||
            slant < bestSlant[slot] - Tolerance ||
            (slant <= bestSlant[slot] + Tolerance && length < bestLength[slot])
        if (!better) return
        best[slot] = to
        bestSlant[slot] = slant
        bestLength[slot] = length
    }

    private companion object {
        /** Two lines this close to equally straight are judged by length instead. */
        const val Tolerance = 1e-4f
    }
}
