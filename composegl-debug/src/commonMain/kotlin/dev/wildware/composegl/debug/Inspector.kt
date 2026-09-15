package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.elements
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusableByPointer
import dev.wildware.composegl.ui.modifier.heightIn
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.node.describe
import dev.wildware.composegl.ui.node.describeConstraints
import dev.wildware.composegl.ui.node.describeNumber
import dev.wildware.composegl.ui.node.describePadding
import dev.wildware.composegl.ui.node.describePolicy
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.max
import kotlin.math.min

/**
 * Point at a widget and ask why it is that size.
 *
 * ```kotlin
 * Inspector(enabled = debug) { Game() }
 * ```
 *
 * While it is on, the screen under it stops taking the mouse and the inspector takes it instead:
 *
 * - **Hovering** outlines the node under the pointer in blue and shades its padding green. The
 *   deepest node wins, and where two overlap the one drawn on top, as a click would pick.
 * - **A panel** in the corner says what that node is: its name and tag, where it is, its size, the
 *   room its parent gave it, its padding, what arranges its children, and its modifier chain in the
 *   order it was written.
 * - **Clicking pins** the node, outlined in orange, so the pointer can go away while it is read.
 *   Clicking it again, or Escape, lets it go. While one is pinned, Up goes to its parent, Down to
 *   its first child, Left and Right to its siblings — the arrow keys or the d-pad, and East lets go
 *   too.
 * - **A tree** of the whole screen sits under it, one line a node, with `-` and `+` to fold a
 *   branch away. Pointing at a line outlines that node without changing the panel, so the tree holds
 *   still under the pointer; clicking it pins it. Moving onto the panel leaves the last node hovered
 *   in it for the same reason. `tree` above it hides
 *   the lot, and `<>` moves the panel to the other side when it is in the way.
 *
 * Off, it is a plain box round the content that costs nothing, and turning it on or off rebuilds
 * nothing inside: the screen keeps its state, its scroll and its focus. Everything it shows is read
 * off the nodes themselves, and read again every frame while it is on, so a pinned node that grows
 * shows its new size without being picked again. It only redraws when what it shows has changed.
 *
 * Deliberately unskinnable, like [FrameBudgetOverlay]: its own dark panel and its own colours, and
 * only the font from the skin. It costs a walk of the tree a frame while it is on. Take it off before
 * shipping.
 *
 * @param enabled whether the inspector is there. Off composes only [content].
 * @param content the screen to inspect.
 */
@Composable
fun Inspector(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = remember { InspectorState() }
    val keys = remember(state) { KeyHandler { state.onKey(it) } }
    val pad = remember(state) { GamepadHandler { state.onGamepad(it) } }
    Layout(
        modifier = if (enabled) modifier.onKeyEvent(keys).onGamepadEvent(pad) else modifier,
        name = "inspector",
        measurePolicy = InspectorPolicy,
        content = {
            // Always the first child, whether the inspector is on or not, so switching it on adds
            // nodes after the screen rather than moving the screen somewhere new in the composition.
            Layout(name = InspectedName, measurePolicy = MeasurePolicy.Stack, content = content)
            if (enabled) {
                InspectorLayer(state)
                InspectorPanel(state)
                LaunchedEffect(state) {
                    // Layout moves things without telling anyone. Looking again each frame and only
                    // writing what differs keeps the panel true and a still screen still.
                    while (true) {
                        withFrameNanos { }
                        state.refresh()
                    }
                }
                DisposableEffect(state) { onDispose { state.clear() } }
            }
        },
    )
}

/** What the node holding the inspected screen is called. */
internal const val InspectedName = "inspected"

/** Test tags on the inspector's own nodes, prefixed so they never meet a game's. */
internal object InspectorTags {
    const val Layer = "inspector:layer"
    const val Panel = "inspector:panel"
    const val Heading = "inspector:heading"
    const val Title = "inspector:title"
    const val Facts = "inspector:facts"
    const val Modifiers = "inspector:modifiers"
    const val Side = "inspector:side"
    const val TreeToggle = "inspector:tree-toggle"
    const val Tree = "inspector:tree"

    /** The tree's line for a node, by the node's own tag. Untagged nodes have untagged lines. */
    fun row(tag: String) = "inspector:row:$tag"

    /** The fold button on that line. */
    fun fold(tag: String) = "inspector:fold:$tag"
}

/** The inspector's colours. */
internal object InspectorColours {
    val Hover = Colour.rgb(0x00B4FF)
    val HoverWash = Colour.argb(0x2200B4FF)
    val Pinned = Colour.rgb(0xFF8800)
    val PinnedWash = Colour.argb(0x22FF8800)
    val Padding = Colour.argb(0x5500FF00)
    val Ground = Colour.argb(0xE00A0C10)
    val Edge = Colour.argb(0x40FFFFFF)
    val Bright = Colour.rgb(0xF0F4FF)
    val Dim = Colour.rgb(0x8A94A6)
    val Chosen = Colour.argb(0x55FF8800)
}

/** Everything a node can be asked, written down as the panel shows it. */
internal data class NodeReport(
    val title: String,
    val facts: List<String>,
    val modifiers: List<String>,
) {
    companion object {
        fun of(node: UiNode): NodeReport {
            val box = node.layoutBoundsInRoot
            val drawn = node.boundsInRoot
            val padding = node.resolved.padding
            val facts = buildList {
                add("at ${describeNumber(box.left)},${describeNumber(box.top)}")
                add("size ${describeNumber(node.width)}x${describeNumber(node.height)}")
                if (drawn != box) {
                    add("drawn ${describeNumber(drawn.left)},${describeNumber(drawn.top)} ${describeNumber(drawn.width)}x${describeNumber(drawn.height)}")
                }
                add("given " + (node.givenConstraints?.let(::describeConstraints) ?: "nothing yet"))
                add("padding " + if (padding == Padding.None) "none" else describePadding(padding))
                add("policy " + describePolicy(node.measurePolicy))
                add("children ${node.children.size}")
            }
            return NodeReport(titleOf(node), facts, node.modifier.elements().map(::describe))
        }
    }
}

/** One line of the tree: a node, how deep it is, and whether its branch is folded away. */
internal data class TreeRow(
    val node: UiNode,
    val depth: Int,
    val label: String,
    val hasChildren: Boolean,
    val folded: Boolean,
)

private fun titleOf(node: UiNode) = node.name + (node.testTag?.let { " #$it" } ?: "")

/**
 * The inspector's whole state: what is hovered and pinned, and what the panel shows of it.
 *
 * Snapshot state, so the panel recomposes when any of it changes, and written only when it has
 * changed, so it recomposes only then. Written from input handlers and from the frame loop, both
 * outside composition.
 */
internal class InspectorState {
    var hovered by mutableStateOf<UiNode?>(null)
        private set
    var pinned by mutableStateOf<UiNode?>(null)
        private set

    /**
     * The node whose line in the tree the pointer is on. Outlined, but not what the panel is about:
     * the panel changing size under a pointer on its way to a line would move the line away.
     */
    var pointed by mutableStateOf<UiNode?>(null)
        private set
    var report by mutableStateOf<NodeReport?>(null)
        private set
    var rows by mutableStateOf<List<TreeRow>>(emptyList())
        private set
    var folded by mutableStateOf<Set<UiNode>>(emptySet())
        private set
    var treeShown by mutableStateOf(true)
    var panelAtStart by mutableStateOf(false)

    /** The input layer, set when it is composed. How the state finds the screen. */
    var layer: UiNode? = null

    /** What the panel is about: the pinned node, or else the hovered one. */
    val selected: UiNode? get() = pinned ?: hovered

    /** The node the inspected content is composed into, the layer's first sibling. */
    val screen: UiNode? get() = layer?.parent?.children?.firstOrNull()

    fun hover(node: UiNode?) {
        hovered = node
        pointed = null
        refresh()
    }

    fun point(node: UiNode?) {
        pointed = node
    }

    fun pin(node: UiNode?) {
        pinned = node
        refresh()
    }

    fun fold(node: UiNode) {
        folded = if (node in folded) folded - node else folded + node
        refresh()
    }

    /** Reads everything shown off the tree again. Equal answers write nothing. */
    fun refresh() {
        val screen = screen
        // A node taken off the screen is not something to show.
        if (pinned?.isUnder(screen) == false) pinned = null
        if (hovered?.isUnder(screen) == false) hovered = null
        if (pointed?.isUnder(screen) == false) pointed = null
        report = selected?.let(NodeReport::of)
        rows = if (screen == null) emptyList() else rowsUnder(screen)
        if (folded.any { !it.isUnder(screen) }) folded = folded.filterTo(mutableSetOf()) { it.isUnder(screen) }
    }

    fun clear() {
        hovered = null
        pointed = null
        pinned = null
        report = null
        rows = emptyList()
        // Folded nodes too, so a screen that has gone is not held on to while the inspector is off.
        folded = emptySet()
    }

    private fun rowsUnder(screen: UiNode): List<TreeRow> {
        val rows = mutableListOf<TreeRow>()
        fun visit(node: UiNode, depth: Int) {
            val isFolded = node in folded
            rows += TreeRow(
                node,
                depth,
                "${titleOf(node)}  ${describeNumber(node.width)}x${describeNumber(node.height)}",
                node.children.isNotEmpty(),
                isFolded,
            )
            if (!isFolded) node.children.forEach { visit(it, depth + 1) }
        }
        screen.children.forEach { visit(it, 0) }
        return rows
    }

    // --- input -----------------------------------------------------------------------------------

    /** The layer's pointer: a move hovers, a press pins. Scrolls go on to the screen. */
    fun onPointer(event: PointerEvent): Boolean {
        val layer = layer ?: return false
        return when (event) {
            is PointerEvent.Move -> {
                hover(nodeAt(toRoot(layer, event.position)))
                true
            }
            is PointerEvent.Press -> {
                val hit = nodeAt(toRoot(layer, event.position))
                pin(if (hit === pinned) null else hit)
                true
            }
            is PointerEvent.Release -> true
            else -> false
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        val direction = when (event.key) {
            Key.Up -> Step.Parent
            Key.Down -> Step.Child
            Key.Left -> Step.Previous
            Key.Right -> Step.Next
            Key.Escape -> Step.LetGo
            else -> return false
        }
        // Only a pinned node takes the keys: a node merely under the pointer leaves them to the screen.
        if (event.type != KeyEventType.Down) return pinned != null && direction != Step.LetGo
        return step(direction)
    }

    fun onGamepad(event: GamepadEvent): Boolean {
        if (event !is GamepadEvent.ButtonDown) return false
        return step(
            when (event.button) {
                GamepadButton.DpadUp -> Step.Parent
                GamepadButton.DpadDown -> Step.Child
                GamepadButton.DpadLeft -> Step.Previous
                GamepadButton.DpadRight -> Step.Next
                GamepadButton.East -> Step.LetGo
                else -> return false
            },
        )
    }

    private enum class Step { Parent, Child, Previous, Next, LetGo }

    /** One step through the tree from the pinned node, pinning where it lands. False with nothing pinned. */
    private fun step(step: Step): Boolean {
        val from = pinned ?: return false
        if (step == Step.LetGo) {
            pin(null)
            return true
        }
        val parent = from.parent
        val to = when (step) {
            // Not up out of the screen: the node holding it is the inspector's, not the game's.
            Step.Parent -> parent?.takeIf { it !== screen && it.isUnder(screen) }
            Step.Child -> from.children.firstOrNull()
            Step.Previous -> parent?.children?.let { it.getOrNull(it.indexOf(from) - 1) }
            Step.Next -> parent?.children?.let { it.getOrNull(it.indexOf(from) + 1) }
            Step.LetGo -> null
        }
        pin(to ?: from)
        return true
    }

    /** A point in the layer's own units, in the root's, as the pointer router handed it over. */
    private fun toRoot(layer: UiNode, local: Offset): Offset {
        val bounds = layer.boundsInRoot
        val scale = layer.scaleInRoot
        return Offset(bounds.left + local.x * scale, bounds.top + local.y * scale)
    }

    /**
     * The node on the screen under [point]: the deepest, and the one drawn on top where siblings
     * overlap, whether or not it takes input. What cannot be seen is not found — a faded or
     * zero-scaled subtree, or the part of a child a clip or a scale cuts off.
     */
    fun nodeAt(point: Offset): UiNode? {
        val screen = screen ?: return null
        fun visit(node: UiNode): UiNode? {
            val resolved = node.resolved
            if (resolved.alpha <= 0f || resolved.scale <= 0f || node.content is DebugOverlay) return null
            val inside = node.everMeasured && point in node.boundsInRoot
            if (!inside && (resolved.clip != null || resolved.scale != 1f)) return null
            val children = node.drawOrder
            for (index in children.indices.reversed()) visit(children[index])?.let { return it }
            return if (inside) node else null
        }
        val children = screen.drawOrder
        for (index in children.indices.reversed()) visit(children[index])?.let { return it }
        return null
    }
}

private fun UiNode.isUnder(ancestor: UiNode?): Boolean {
    if (ancestor == null) return false
    var walk: UiNode? = parent
    while (walk != null) {
        if (walk === ancestor) return true
        walk = walk.parent
    }
    return false
}

/**
 * The screen at its own size, the layer exactly over it, and the panel in a top corner inside it.
 * The panel says which corner with `align`; the layer is the one that does not.
 */
internal object InspectorPolicy : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val count = measurables.size
        if (count == 0) return layout(constraints.minWidth, constraints.minHeight) {}
        val placeables = placeables(count)
        val placements = placements(count)

        val screen = measurables[0].measure(constraints)
        placeables[0] = screen
        val width = constraints.constrainWidth(screen.width)
        val height = constraints.constrainHeight(screen.height)

        for (index in 1 until count) {
            val measurable = measurables[index]
            val alignment = measurable.layoutData.alignment
            if (alignment == null) {
                placeables[index] = measurable.measure(Constraints.fixed(width, height))
                placements[index * 2] = 0f
                placements[index * 2 + 1] = 0f
            } else {
                val roomWidth = max(width - 2 * PanelMargin, 0f)
                val roomHeight = max(height - 2 * PanelMargin, 0f)
                val panel = measurable.measure(Constraints(0f, roomWidth, 0f, roomHeight))
                placeables[index] = panel
                placements[index * 2] = PanelMargin + alignment.xIn(roomWidth, panel.width)
                placements[index * 2 + 1] = PanelMargin + alignment.yIn(roomHeight, panel.height)
            }
        }
        return layout(width, height, count)
    }
}

private const val PanelMargin = 8f
private const val PanelWidth = 260f
private const val TreeHeight = 220f
private const val Indent = 10f

private val NoInk: (Rect) -> Rect? = { null }

/**
 * A node over the whole screen that takes the pointer and draws the outlines.
 *
 * Focusable by a press only, so a click to pin brings the arrow keys to the inspector without making
 * it somewhere Tab or the pad would stop.
 */
@Composable
private fun InspectorLayer(state: InspectorState) {
    val handler = remember(state) { PointerHandler { state.onPointer(it) } }
    val hovered = state.pointed ?: state.hovered
    val pinned = state.pinned
    // A new painter only when what it outlines changes, which is what tells the tree to redraw.
    val painter = remember(hovered, pinned) { InspectorHighlight(hovered, pinned) }
    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode("inspector layer") },
        update = {
            set(handler) {
                this.modifier = Modifier.focusableByPointer().onPointer(it).testTag(InspectorTags.Layer)
                state.layer = this
            }
            set(MeasurePolicy.Empty) { this.measurePolicy = it }
            set(NoInk) { this.ink = it }
            set(painter) {
                it.node = this
                this.content = it
            }
        },
    )
}

/** The outlines: blue with its padding shaded for the hovered node, orange for the pinned one. */
internal class InspectorHighlight(
    private val hovered: UiNode?,
    private val pinned: UiNode?,
) : DebugOverlay {

    var node: UiNode? = null

    override fun invoke(canvas: UiCanvas, content: Rect) {
        val self = node ?: return
        // As the layout overlay does: the canvas is in the root's coordinates unless a caller drew
        // this into a picture somewhere else, and where the layer's own box landed says which.
        val box = self.layoutBoundsInRoot
        val dx = content.left - box.left
        val dy = content.top - box.top
        if (hovered != null && hovered !== pinned) mark(canvas, hovered, InspectorColours.Hover, InspectorColours.HoverWash, 1f, dx, dy)
        if (pinned != null) mark(canvas, pinned, InspectorColours.Pinned, InspectorColours.PinnedWash, 2f, dx, dy)
    }

    private fun mark(canvas: UiCanvas, node: UiNode, edge: Colour, wash: Colour, width: Float, dx: Float, dy: Float) {
        val drawn = node.boundsInRoot
        val rect = Rect(drawn.left + dx, drawn.top + dy, drawn.right + dx, drawn.bottom + dy)
        if (rect.width < 1f || rect.height < 1f) {
            canvas.rect(Rect(rect.left, rect.top, rect.left + max(rect.width, 1f), rect.top + max(rect.height, 1f)), edge)
            return
        }
        canvas.rect(rect, wash)
        // Padding scales with the node, so the bands are the drawn box's.
        val scale = node.scaleInRoot
        val padding = node.resolved.padding
        val innerTop = min(rect.top + padding.top * scale, rect.bottom)
        val innerBottom = max(rect.bottom - padding.bottom * scale, innerTop)
        val innerLeft = min(rect.left + padding.left * scale, rect.right)
        val innerRight = max(rect.right - padding.right * scale, innerLeft)
        band(canvas, rect.left, rect.top, rect.right, innerTop)
        band(canvas, rect.left, innerBottom, rect.right, rect.bottom)
        band(canvas, rect.left, innerTop, innerLeft, innerBottom)
        band(canvas, innerRight, innerTop, rect.right, innerBottom)
        canvas.border(rect, edge, width)
    }

    private fun band(canvas: UiCanvas, left: Float, top: Float, right: Float, bottom: Float) {
        if (right > left && bottom > top) canvas.rect(Rect(left, top, right, bottom), InspectorColours.Padding)
    }
}

@Composable
private fun InspectorPanel(state: InspectorState) {
    // Moves over the panel belong to the panel: nothing under it is hovered through it. What was
    // last hovered stays in the panel, so the tree does not jump as the pointer comes to it.
    val swallow = remember(state) {
        PointerHandler {
            if (it is PointerEvent.Move) state.point(null)
            true
        }
    }
    val report = state.report
    val colours = InspectorColours
    Column(
        Modifier
            .align(if (state.panelAtStart) Alignment.TopStart else Alignment.TopEnd)
            .width(PanelWidth)
            .background(colours.Ground, corner = 4f)
            .border(colours.Edge, 1f, corner = 4f)
            .onPointer(swallow)
            .padding(horizontal = 8f, vertical = 6f)
            .testTag(InspectorTags.Panel),
        verticalArrangement = Arrangement.spacedBy(2f),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val heading = when {
                state.pinned != null -> "pinned"
                state.hovered != null -> "hovered"
                else -> "point at something"
            }
            Text(heading, style = "inspector", colour = colours.Dim, modifier = Modifier.testTag(InspectorTags.Heading))
            Text(
                "<>",
                style = "inspector",
                colour = colours.Bright,
                modifier = Modifier.clickable { state.panelAtStart = !state.panelAtStart }.testTag(InspectorTags.Side),
            )
        }
        if (report != null) {
            Text(report.title, style = "inspector", colour = colours.Bright, modifier = Modifier.testTag(InspectorTags.Title))
            Column(Modifier.testTag(InspectorTags.Facts)) {
                report.facts.forEach { Text(it, style = "inspector", colour = colours.Bright) }
            }
            Text("modifiers", style = "inspector", colour = colours.Dim)
            Column(Modifier.testTag(InspectorTags.Modifiers)) {
                if (report.modifiers.isEmpty()) Text("none", style = "inspector", colour = colours.Bright)
                report.modifiers.forEachIndexed { index, element ->
                    Text("${index + 1}. $element", style = "inspector", colour = colours.Bright)
                }
            }
        }
        Text(
            if (state.treeShown) "tree -" else "tree +",
            style = "inspector",
            colour = colours.Dim,
            modifier = Modifier.clickable { state.treeShown = !state.treeShown }.testTag(InspectorTags.TreeToggle),
        )
        if (state.treeShown) {
            ScrollArea(Modifier.fillMaxWidth().heightIn(max = TreeHeight).testTag(InspectorTags.Tree)) {
                Column(Modifier.fillMaxWidth()) {
                    state.rows.forEach { row -> TreeLine(state, row) }
                }
            }
        }
    }
}

@Composable
private fun TreeLine(state: InspectorState, row: TreeRow) {
    val node = row.node
    val tag = node.testTag
    val hover = remember(state, node) {
        PointerHandler {
            if (it is PointerEvent.Move) {
                state.point(node)
                true
            } else {
                false
            }
        }
    }
    var line = Modifier.fillMaxWidth()
    if (node === state.selected) line = line.background(InspectorColours.Chosen)
    line = line.onPointer(hover).clickable { state.pin(node) }
    if (tag != null) line = line.testTag(InspectorTags.row(tag))
    Row(line.padding(left = row.depth * Indent), verticalAlignment = VerticalAlignment.Centre) {
        var fold = Modifier.width(Indent + 4f)
        if (row.hasChildren) {
            fold = fold.clickable { state.fold(node) }
            if (tag != null) fold = fold.testTag(InspectorTags.fold(tag))
        }
        Text(
            when {
                !row.hasChildren -> ""
                row.folded -> "+"
                else -> "-"
            },
            style = "inspector",
            colour = InspectorColours.Dim,
            modifier = fold,
        )
        Text(row.label, style = "inspector", colour = InspectorColours.Bright, maxLines = 1, softWrap = false)
    }
}
