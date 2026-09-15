package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.focus.RevealHandler
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.ActivateHandler
import dev.wildware.composegl.ui.input.BackHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.modifier.FocusableElement
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onActivate
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.node.UiNode

/**
 * The one thing a screen can be carrying, and everywhere it could be put down.
 *
 * One per screen, for the same reason there is one tooltip: a player has one hand. It is what a
 * [dragSource] hands its payload to and what a [dropTarget] is asked about, so a slot in the
 * backpack and a slot on the hotbar never need to know each other exists.
 *
 * Read [payload] from a composition to draw something while a drag is on — dim the slot the item
 * came out of, say — and it recomposes when the drag starts and ends.
 */
@Stable
class DragAndDrop internal constructor(
    /** Where the picture sits over a focused slot, while a pad or keyboard carries it. */
    internal val carryOffset: Offset,
) {

    /** What is being carried, or null when nothing is. */
    val payload: Any? get() = carrying?.payload

    /** Whether anything is being carried, by a pointer or by the pad. */
    val isDragging: Boolean get() = carrying != null

    internal var carrying by mutableStateOf<Carry?>(null)
        private set

    /** The top left of the picture, in the host's own coordinates. */
    internal var pictureAt by mutableStateOf(Offset.Zero)
        private set

    /** The host's box, which everything here is searched from and measured against. */
    internal var host: UiNode? = null

    /** The drag layer's box, which a search skips: the picture of a slot is not a slot. */
    internal var layer: UiNode? = null

    internal val targets = mutableListOf<TargetEntry>()

    private var hovered: TargetEntry? = null

    /** Where the pointer is, in the root's coordinates, while a pointer carries something. */
    private var pointer = Offset.Zero

    /** Where in the picture the pointer holds it, in the host's units. */
    private var grab = Offset.Zero

    // --- the pointer -----------------------------------------------------------------------------

    internal fun start(source: SourceEntry, grabbedAt: Offset) {
        val node = source.node ?: return
        cancelAny()
        val scale = node.scaleInRoot
        pointer = node.boundsInRoot.topLeft + grabbedAt * scale
        grab = grabbedAt * (scale / hostScale())
        begin(Carry(source.payload, source.picture, source, byPointer = true))
        pictureAt = inHost(pointer) - grab
        hover(targetAt(pointer))
    }

    internal fun moveBy(source: SourceEntry, delta: Offset) {
        if (carrying?.source !== source) return
        // Deltas arrive in the source's own units; the pointer is kept in the root's.
        pointer += delta * (source.node?.scaleInRoot ?: 1f)
        pictureAt = inHost(pointer) - grab
        hover(targetAt(pointer))
    }

    internal fun end(source: SourceEntry) {
        val carry = carrying ?: return
        if (carry.source !== source || !carry.byPointer) return
        // Searched again rather than trusting the last move: the screen may have moved under a
        // pointer that has not.
        finish(targetAt(pointer))
    }

    internal fun cancel(source: SourceEntry) {
        if (carrying?.source === source) finish(null)
    }

    // --- the pad and the keyboard ----------------------------------------------------------------

    /**
     * South or Enter on a focused [node]: pick [source] up if nothing is carried, or put what is
     * carried down here.
     *
     * A target that refuses keeps the press — the item stays in hand rather than the slot being
     * clicked as well. South on the slot it came out of, when that slot is not a target, puts it
     * back. Anywhere else is not this system's business, and the press is a click as usual.
     */
    internal fun activate(node: UiNode?, source: SourceEntry?): Boolean {
        val carry = carrying
        if (carry == null) {
            if (source == null || source.node == null || !source.enabled) return false
            begin(Carry(source.payload, source.picture, source, byPointer = false))
            over(source.node)
            return true
        }
        // A pointer is already carrying it, and the pad has no say in where a hand puts it.
        if (carry.byPointer) return false
        val target = targets.firstOrNull { it.node === node && it.enabled }
        if (target != null) {
            if (target.accepts(carry.payload)) finish(target)
            return true
        }
        if (source != null && carry.source === source) {
            finish(null)
            return true
        }
        return false
    }

    /**
     * Focus landed on something inside the host, anything at all, whose box is [area] in the host's
     * own units. The picture goes with it; a source or a target then says which it is, in
     * [focusedOn], once its focus state has been read.
     */
    internal fun revealed(area: Rect) {
        val carry = carrying ?: return
        if (carry.byPointer) return
        // Remembered as a node rather than a rectangle: a list that scrolls to show it moves it on
        // the next layout, and the picture follows it there in [follow].
        val focused = host?.let { focusedIn(it, it, area) }
        if (focused != null) {
            over(focused)
        } else {
            carry.over = null
            pictureAt = area.topLeft + carryOffset
            hover(null)
        }
    }

    /**
     * Once a frame while a pad carries something: the picture keeps up with whatever it is over,
     * which may have moved without being a source or a target to say so — a button in a list that
     * just scrolled. Writing the same place again is not a change, so a still screen stays still.
     */
    internal fun follow() {
        val carry = carrying ?: return
        if (carry.byPointer) return
        val node = carry.over ?: return
        pictureAt = inHost(node.boundsInRoot.topLeft) + carryOffset
    }

    /**
     * The node focus just landed on, under [node]: the deepest focusable one whose box is [area],
     * worked out in [host]'s units the way focus worked the area out. A plain `focusable()` keeps
     * no state to ask, so its box is how it is recognised. The drag layer is skipped.
     */
    private fun focusedIn(node: UiNode, host: UiNode, area: Rect): UiNode? {
        if (node === layer) return null
        for (child in node.children) focusedIn(child, host, area)?.let { return it }
        if (node.resolved.focusable == null) return null
        val bounds = node.boundsInRoot
        val a = host.toLocal(bounds.topLeft)
        val b = host.toLocal(Offset(bounds.right, bounds.bottom))
        val local = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        return if (local == area) node else null
    }

    /** Focus landed on [node], which is a source or a target. The picture goes with it. */
    internal fun focusedOn(node: UiNode?) {
        val carry = carrying ?: return
        if (carry.byPointer || node == null) return
        over(node)
    }

    /** A source or target moved. If the pad is carrying something over it, the picture follows. */
    internal fun placed(node: UiNode) {
        val carry = carrying ?: return
        if (!carry.byPointer && carry.over === node) over(node)
    }

    private fun over(node: UiNode?) {
        val carry = carrying ?: return
        carry.over = node
        if (node == null) return
        pictureAt = inHost(node.boundsInRoot.topLeft) + carryOffset
        hover(targets.firstOrNull { it.node === node && it.enabled })
    }

    // --- both ------------------------------------------------------------------------------------

    /** Escape or the pad's East: whatever is carried goes back where it came from. */
    internal val back = BackHandler {
        if (carrying == null) false else {
            finish(null)
            true
        }
    }

    internal fun register(target: TargetEntry) {
        targets += target
        carrying?.let { target.state?.isOffered = target.enabled && target.accepts(it.payload) }
    }

    /** [target] was turned on or off. Off, it is neither offered nor lit, and on, it may be again. */
    internal fun enabledChanged(target: TargetEntry) {
        val carry = carrying ?: return
        target.state?.isOffered = target.enabled && target.accepts(carry.payload)
        if (!target.enabled && hovered === target) hover(null)
    }

    internal fun forget(target: TargetEntry) {
        targets -= target
        if (hovered === target) hover(null)
        target.state?.clear()
    }

    /** A source left the screen. What it was carrying cannot go back into it, so the drag is off. */
    internal fun forget(source: SourceEntry) {
        if (carrying?.source === source) finish(null)
    }

    private fun begin(carry: Carry) {
        carrying = carry
        targets.forEach { it.state?.isOffered = it.enabled && it.accepts(carry.payload) }
    }

    private fun cancelAny() {
        if (carrying != null) finish(null)
    }

    /**
     * Ends the drag on [target], or nowhere. The carry is cleared before anyone is told, so an
     * `onDrop` that starts the next drag is starting it on a clean slate.
     */
    private fun finish(target: TargetEntry?) {
        val carry = carrying ?: return
        val dropped = target != null && target.enabled && target.accepts(carry.payload)
        hover(null)
        targets.forEach { it.state?.clear() }
        carrying = null
        if (dropped) target.onDrop(carry.payload)
        carry.source.onDragEnd(dropped)
    }

    /** Moves the hover to [target], or off everything. Only the one left and the one entered change. */
    private fun hover(target: TargetEntry?) {
        if (target === hovered) return
        hovered?.state?.let {
            it.isHovered = false
            it.isRefusing = false
        }
        hovered = target
        val carry = carrying ?: return
        val state = target?.state ?: return
        val takes = target.accepts(carry.payload)
        state.isHovered = takes
        state.isRefusing = !takes
    }

    /**
     * The target on top under [point], in the root's coordinates, searched the way the pointer
     * searches: the last drawn first, children before their parent, nothing inside a clip that the
     * point is outside, and nothing that cannot be seen.
     */
    private fun targetAt(point: Offset): TargetEntry? {
        val from = host ?: return null
        val live = targets.filter { it.enabled && it.node != null }
        if (live.isEmpty()) return null
        return search(from, point, live)
    }

    private fun search(node: UiNode, point: Offset, live: List<TargetEntry>): TargetEntry? {
        if (node === layer) return null
        val resolved = node.resolved
        if (resolved.alpha <= 0f || node.drawnScaleIsZero()) return null
        val inside = point in node.boundsInRoot
        if (resolved.clip != null && !inside) return null
        val children = node.drawOrder
        for (index in children.indices.reversed()) {
            search(children[index], point, live)?.let { return it }
        }
        return if (inside) live.firstOrNull { it.node === node } else null
    }

    private fun UiNode.drawnScaleIsZero() = resolved.scale <= 0f

    private fun hostScale(): Float = host?.scaleInRoot?.takeIf { it > 0f } ?: 1f

    private fun inHost(point: Offset): Offset = host?.toLocal(point) ?: point
}

/** Something being carried: what it is, what it looks like, and where it came from. */
internal class Carry(
    val payload: Any,
    val picture: @Composable () -> Unit,
    val source: SourceEntry,
    val byPointer: Boolean,
) {
    /** The source or target a pad carry is over. */
    var over: UiNode? = null
}

/** One [dragSource], as the drag sees it. Rewritten every composition, kept for the node's life. */
internal class SourceEntry {
    lateinit var payload: Any
    var picture: @Composable () -> Unit = {}
    var enabled = true
    var onDragEnd: (Boolean) -> Unit = {}

    /** The node it is on, from its last layout. Null until it has had one. */
    var node: UiNode? = null
}

/** One [dropTarget], as the drag sees it. */
@PublishedApi
internal class TargetEntry {
    var accepts: (Any) -> Boolean = { false }
    var onDrop: (Any) -> Unit = {}
    var enabled = true
    var state: DropTargetState? = null
    var node: UiNode? = null
}

/**
 * What a [dropTarget] should look like right now. All three are Compose state, so a slot that reads
 * them recomposes as a drag passes over it.
 */
@Stable
class DropTargetState {

    /** Something this target would take is over it now: the moment to light up. */
    var isHovered by mutableStateOf(false)
        internal set

    /** Something this target would refuse is over it now: the moment to show a red edge. */
    var isRefusing by mutableStateOf(false)
        internal set

    /** Something is being carried that this target would take, wherever it is: a hint to look here. */
    var isOffered by mutableStateOf(false)
        internal set

    internal fun clear() {
        isHovered = false
        isRefusing = false
        isOffered = false
    }

    override fun toString(): String = "DropTargetState(hovered=$isHovered, refusing=$isRefusing, offered=$isOffered)"
}

/** The screen's drag and drop, for sources and targets under it. */
val LocalDragAndDrop = staticCompositionLocalOf<DragAndDrop> {
    error("no drag and drop: wrap the screen in a DragAndDropHost")
}

/** Far above anything a screen lifts with `zIndex` itself. Finite, because a zIndex has to be. */
private const val LayerZ = 1_000_000f

/**
 * Makes drag and drop work for everything inside it. Put it round a screen, once.
 *
 * A host rather than a modifier for the tooltip's reason: the thing being carried is drawn **over**
 * everything — the panel it came out of, the panel it is going to, whatever lies between — so it is
 * composed last, above the whole screen, in the screen's own coordinates.
 *
 * @param carryOffset where the picture sits against the top left of a focused slot while a pad or a
 *   keyboard carries it. Up and to the right by default, so the slot underneath still shows.
 */
@Composable
fun DragAndDropHost(
    carryOffset: Offset = Offset(16f, -16f),
    content: @Composable () -> Unit,
) {
    val dnd = remember(carryOffset) { DragAndDrop(carryOffset) }
    val hostPlaced = remember(dnd) { PlacedHandler { dnd.host = it } }
    val layerPlaced = remember(dnd) { PlacedHandler { dnd.layer = it } }
    // Every focus move inside the screen is revealed to it, so the picture can follow the pad onto
    // a button that is neither a source nor a target, and not be left behind on the last slot.
    val reveal = remember(dnd) { RevealHandler { area -> dnd.revealed(area); false } }

    // Added when something is picked up and taken off when it is put down, so it is the newest
    // handler on the stack while it matters: Back puts the item back before it closes the screen.
    val backs = LocalBackStack.current
    val carrying = dnd.carrying
    DisposableEffect(backs, carrying) {
        if (carrying != null) backs.add(dnd.back)
        onDispose { if (carrying != null) backs.remove(dnd.back) }
    }
    if (carrying != null && !carrying.byPointer) {
        LaunchedEffect(dnd, carrying) {
            while (true) withFrameNanos { dnd.follow() }
        }
    }

    CompositionLocalProvider(LocalDragAndDrop provides dnd) {
        Box(Modifier.fillMaxSize().onPlaced(hostPlaced).onReveal(reveal)) {
            content()
            if (carrying != null) {
                Box(
                    Modifier.offset(dnd.pictureAt.x, dnd.pictureAt.y).zIndex(LayerZ).onPlaced(layerPlaced),
                ) { carrying.picture() }
            }
        }
    }
}

/**
 * This node can be picked up and carried to a [dropTarget], holding [payload].
 *
 * ```kotlin
 * Slot(Modifier.dragSource(payload = item) { ItemIcon(item) })
 * ```
 *
 * With a pointer it is a [draggable]: the slop, the capture and the cancel all work the same way,
 * and [picture] is drawn under the pointer, over everything, held where it was grabbed. Let go over
 * a target that accepts it and that target's `onDrop` gets [payload].
 *
 * With a pad or a keyboard, South or Enter on it picks it up. Focus then moves as usual, [picture]
 * floating over whichever slot has it, and South on a target puts it down. East or Escape puts it
 * back, and so does South on the slot it came out of. The node is made focusable for that; hand in
 * [interaction] if the widget already reads its focus from a state of its own.
 *
 * [onDragEnd] hears how it went: true when a target took it, false for everything else — let go
 * over nothing, over a target that refused, or cancelled. Moving the item is the target's job, in
 * `onDrop`; this is for the source to stop looking picked up.
 *
 * Needs a [DragAndDropHost] somewhere above it.
 */
@Composable
fun Modifier.dragSource(
    payload: Any,
    enabled: Boolean = true,
    interaction: InteractionState? = null,
    onDragEnd: (dropped: Boolean) -> Unit = {},
    picture: @Composable () -> Unit,
): Modifier {
    val dnd = LocalDragAndDrop.current
    val entry = remember(dnd) { SourceEntry() }
    entry.payload = payload
    entry.picture = picture
    entry.enabled = enabled
    entry.onDragEnd = onDragEnd

    DisposableEffect(dnd, entry) { onDispose { dnd.forget(entry) } }

    val own = remember { InteractionState() }
    val earlier = focusableIn(this)
    val focus = interaction ?: earlier?.state ?: own
    val focused = focus.isFocused
    DisposableEffect(dnd, entry, focused) {
        if (focused) dnd.focusedOn(entry.node)
        onDispose { }
    }

    val placed = remember(dnd, entry) {
        PlacedHandler { node ->
            entry.node = node
            dnd.placed(node)
        }
    }
    val activate = remember(dnd, entry) { ActivateHandler { entry.enabled && dnd.activate(entry.node, entry) } }
    val start = remember(dnd, entry) { { at: Offset -> dnd.start(entry, at) } }
    val move = remember(dnd, entry) { { delta: Offset -> dnd.moveBy(entry, delta) } }
    val end = remember(dnd, entry) { { dnd.end(entry) } }
    val cancel = remember(dnd, entry) { { dnd.cancel(entry) } }

    return this
        .onPlaced(placed)
        .focusable(state = focus, enabled = enabled || earlier?.enabled == true, initial = earlier?.initial == true)
        .onActivate(activate)
        .draggable(enabled = enabled, onDragStart = start, onDragEnd = end, onDragCancel = cancel, onDrag = move)
}

/**
 * This node takes things of type [T] dropped on it.
 *
 * ```kotlin
 * val slotState = remember { DropTargetState() }
 * Slot(
 *     Modifier.dropTarget<Item>(
 *         state = slotState,
 *         accepts = { it.fits(slot) },
 *         onDrop = { move(it, slot) },
 *     ),
 * )
 * ```
 *
 * Anything carried that is not a [T] is refused without [accepts] being asked, so a slot for
 * swords and a slot for spells can sit side by side on one screen.
 *
 * - **Found by where it is drawn.** The target under the pointer is the one on top, the same one a
 *   click there would reach, so a target inside a scrolled-away part of a list is not dropped on.
 * - **Lit as it is passed over.** [state] says whether what is over it now would be taken or
 *   refused, and whether what is being carried would be taken at all.
 * - **Reached by a pad.** It is made focusable, so focus can walk onto it while carrying, and South
 *   drops there. Hand in [interaction] if the widget already reads its focus from its own state.
 *
 * Needs a [DragAndDropHost] somewhere above it.
 */
@Composable
inline fun <reified T : Any> Modifier.dropTarget(
    state: DropTargetState? = null,
    enabled: Boolean = true,
    interaction: InteractionState? = null,
    noinline accepts: (T) -> Boolean = { true },
    noinline onDrop: (T) -> Unit,
): Modifier = dropTargetOf(
    state = state,
    enabled = enabled,
    interaction = interaction,
    accepts = { it is T && accepts(it) },
    onDrop = { onDrop(it as T) },
)

@PublishedApi
@Composable
internal fun Modifier.dropTargetOf(
    state: DropTargetState?,
    enabled: Boolean,
    interaction: InteractionState?,
    accepts: (Any) -> Boolean,
    onDrop: (Any) -> Unit,
): Modifier {
    val dnd = LocalDragAndDrop.current
    val entry = remember(dnd) { TargetEntry() }
    entry.accepts = accepts
    entry.onDrop = onDrop
    entry.state = state
    // Turned off mid-drag, it stops being offered and lit as well as stopping taking.
    if (entry.enabled != enabled) {
        entry.enabled = enabled
        dnd.enabledChanged(entry)
    }

    DisposableEffect(dnd, entry) {
        dnd.register(entry)
        onDispose { dnd.forget(entry) }
    }

    val own = remember { InteractionState() }
    val earlier = focusableIn(this)
    val focus = interaction ?: earlier?.state ?: own
    val focused = focus.isFocused
    DisposableEffect(dnd, entry, focused) {
        if (focused) dnd.focusedOn(entry.node)
        onDispose { }
    }

    val placed = remember(dnd, entry) {
        PlacedHandler { node ->
            entry.node = node
            dnd.placed(node)
        }
    }
    val activate = remember(dnd, entry) { ActivateHandler { dnd.activate(entry.node, null) } }

    return this
        .onPlaced(placed)
        .focusable(state = focus, enabled = enabled || earlier?.enabled == true, initial = earlier?.initial == true)
        .onActivate(activate)
}

/**
 * The last `focusable` already in [chain], which a source or target joins rather than replaces.
 *
 * A node keeps only one: without this, a slot that is a source and a target would be focusable only
 * if the one written last was on, and a state handed to the first would never hear about focus.
 */
private fun focusableIn(chain: Modifier): FocusableElement? =
    chain.fold<FocusableElement?>(null) { found, element -> element as? FocusableElement ?: found }
