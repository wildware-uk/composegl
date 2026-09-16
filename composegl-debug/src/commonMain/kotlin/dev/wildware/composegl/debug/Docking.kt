package dev.wildware.composegl.debug

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.widget.Orientation
import kotlin.math.max
import kotlin.math.min

// --- what a dock layout is -------------------------------------------------------------------------

/**
 * Which way a window goes in when it is docked: against that edge of the screen, or of the window it
 * is dropped on.
 *
 * @see DebugWindowsState.dockToScreen
 * @see DebugWindowsState.dockWith
 */
enum class DockSide { Left, Right, Top, Bottom }

/**
 * Where the docked windows are: panes either side of a divider, all the way down to a group of
 * windows sharing one pane as tabs.
 *
 * The whole thing is a value: every change builds a new tree rather than editing this one, so the
 * one field on [DebugWindowsState] that holds it is the only snapshot state there is, and anything
 * that read the layout is recomposed when it changes.
 *
 * Exactly one [DockEmpty] is in the tree whenever anything is docked at all: the hole the game shows
 * through. Docking to the screen's left edge is a split of "the new window" against "everything that
 * was there before", and what was there before is at least that hole, so the game keeps a place
 * however many windows are docked around it.
 */
internal sealed interface DockNode

/** The part of the screen no window is docked in, which the game shows through. */
internal data object DockEmpty : DockNode

/** One pane of windows, tabbed together, [selected] being the one showing. */
internal data class DockTabs(val windows: List<String>, val selected: String) : DockNode

/** Two panes sharing a space, [fraction] of it, less the divider, going to [first]. */
internal data class DockSplit(
    val orientation: Orientation,
    val fraction: Float,
    val first: DockNode,
    val second: DockNode,
) : DockNode

/** How much of the screen a window docked against one of its edges takes to begin with. */
internal const val DockShare = 0.25f

/** How thick the divider between two docked panes is, which is also how wide a target it is. */
internal const val DockDividerThickness = 6f

/** The smallest a docked pane is ever made, so a divider dragged to the end leaves something to grab. */
internal const val MinDockPane = 60f

// --- reading a layout ------------------------------------------------------------------------------

/** Every window the layout holds, first pane first. */
internal fun DockNode.windows(): List<String> = when (this) {
    is DockTabs -> windows
    is DockSplit -> first.windows() + second.windows()
    DockEmpty -> emptyList()
}

/** The pane [id] is tabbed into, or null when it is not docked. */
internal fun DockNode.tabsOf(id: String): DockTabs? = when (this) {
    is DockTabs -> this.takeIf { id in windows }
    is DockSplit -> first.tabsOf(id) ?: second.tabsOf(id)
    DockEmpty -> null
}

/** Whether anything at all is docked. */
internal fun DockNode.isEmpty(): Boolean = this === DockEmpty

/**
 * The way down to the pane [id] is in, or null when it is not docked.
 *
 * A path rather than a rectangle, because the screen's size is only known when the windows are
 * measured: what composition can work out is which splits a pane is under, and layout turns that
 * into pixels with [dockRect].
 */
internal fun DockNode.stepsTo(id: String): List<DockStep>? = stepsTo(id, ArrayList())

private fun DockNode.stepsTo(id: String, walked: MutableList<DockStep>): List<DockStep>? = when (this) {
    is DockTabs -> walked.toList().takeIf { id in windows }
    is DockSplit -> {
        walked += DockStep(orientation, fraction, first = true)
        val found = first.stepsTo(id, walked)
        if (found != null) found else {
            walked[walked.lastIndex] = DockStep(orientation, fraction, first = false)
            val other = second.stepsTo(id, walked)
            if (other == null) walked.removeAt(walked.lastIndex)
            other
        }
    }
    DockEmpty -> null
}

/** One split on the way down to a pane: which way it divides, where, and which side the pane is on. */
internal data class DockStep(val orientation: Orientation, val fraction: Float, val first: Boolean)

// --- changing a layout -----------------------------------------------------------------------------

/** The layout without [id] in it, panes that empty taken out and the hole left where it is. */
internal fun DockNode.without(id: String): DockNode = removing { it == id } ?: DockEmpty

/** The layout with only the windows [live] says are composed, for drawing a layout saved last run. */
internal fun DockNode.retaining(live: (String) -> Boolean): DockNode = removing { !live(it) } ?: DockEmpty

/**
 * The layout without every window [gone] names, or null when nothing of it is left.
 *
 * Null rather than [DockEmpty] so that a pane that empties is taken out of its split while the hole
 * the game shows through — which is never taken out — keeps its place.
 */
private fun DockNode.removing(gone: (String) -> Boolean): DockNode? = when (this) {
    is DockTabs -> {
        val left = windows.filterNot(gone)
        when {
            left.isEmpty() -> null
            left == windows -> this
            else -> DockTabs(left, if (selected in left) selected else left.first())
        }
    }
    is DockSplit -> {
        val a = first.removing(gone)
        val b = second.removing(gone)
        when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            a === first && b === second -> this
            else -> DockSplit(orientation, fraction, a, b)
        }
    }
    DockEmpty -> this
}

/** Whether [retaining] would leave anything of this node: a pane with a live window in it, or the hole. */
private fun DockNode.survives(live: (String) -> Boolean): Boolean = when (this) {
    is DockTabs -> windows.any(live)
    is DockSplit -> first.survives(live) || second.survives(live)
    DockEmpty -> true
}

/** [id] docked against the screen's [side], taking [fraction] of it, everything else beside it. */
internal fun DockNode.dockedToScreen(id: String, side: DockSide, fraction: Float = DockShare): DockNode =
    splitOff(side, DockTabs(listOf(id), id), without(id), fraction)

/**
 * [id] put in with [target]: tabbed with it when [side] is null, or taking half of its pane on that
 * side.
 *
 * A [target] that is floating has no pane to put anything in, so it is given one: it docks against
 * [anchor] of the screen first, taking a quarter of it as any window docked to an edge does, and [id]
 * then tabs or splits inside that. The two land together, which is what dropping one window on
 * another asks for, and the first drop of the run is no different from the rest.
 */
internal fun DockNode.dockedWith(
    id: String,
    target: String,
    side: DockSide?,
    anchor: DockSide = DockSide.Left,
): DockNode {
    if (id == target) return this
    val rest = without(id)
    val anchored = if (rest.tabsOf(target) == null) rest.dockedToScreen(target, anchor) else rest
    val pane = anchored.tabsOf(target) ?: return this
    return anchored.replacing(pane) {
        if (side == null) DockTabs(pane.windows + id, id)
        else splitOff(side, DockTabs(listOf(id), id), pane, 0.5f)
    }
}

/** [id] the tab showing in its own pane. The layout unchanged when it is not docked. */
internal fun DockNode.selecting(id: String): DockNode {
    val pane = tabsOf(id) ?: return this
    if (pane.selected == id) return this
    return replacing(pane) { DockTabs(pane.windows, id) }
}

/** The same layout with the split at [path] — first pane or second, all the way down — moved to [fraction]. */
internal fun DockNode.withFraction(path: List<Boolean>, fraction: Float): DockNode = withFraction(path, 0, fraction)

private fun DockNode.withFraction(path: List<Boolean>, at: Int, fraction: Float): DockNode {
    if (this !is DockSplit) return this
    if (at == path.size) return DockSplit(orientation, fraction.coerceIn(0f, 1f), first, second)
    return if (path[at]) {
        DockSplit(orientation, this.fraction, first.withFraction(path, at + 1, fraction), second)
    } else {
        DockSplit(orientation, this.fraction, first, second.withFraction(path, at + 1, fraction))
    }
}

/**
 * The way down to the split that [drawn] names in the layout [retaining] makes of this one, or null
 * when it names no split there.
 *
 * Dividers are drawn from the layout with the windows this run does not compose taken out, and moved
 * in the whole layout, which keeps them. The two are different shapes: a split that loses a whole
 * side is gone from the drawn one, so the same path names a different split in each. The way down is
 * translated on the way across — every split only one side of which survives is stepped over, and the
 * rest line up — so hauling a divider moves the split under the player's hand and no other.
 */
internal fun DockNode.pathRetaining(drawn: List<Boolean>, live: (String) -> Boolean): List<Boolean>? {
    val walked = mutableListOf<Boolean>()
    var node = this
    var at = 0
    while (true) {
        val split = node as? DockSplit ?: return null
        val first = split.first.survives(live)
        val second = split.second.survives(live)
        val takeFirst = when {
            !first && !second -> return null
            !first -> false
            !second -> true
            at == drawn.size -> return walked
            else -> drawn[at].also { at++ }
        }
        walked += takeFirst
        node = if (takeFirst) split.first else split.second
    }
}

/** [pane] swapped for what [replace] makes of it, wherever it is in the tree. */
private fun DockNode.replacing(pane: DockTabs, replace: () -> DockNode): DockNode = when {
    this === pane -> replace()
    this is DockSplit -> DockSplit(orientation, fraction, first.replacing(pane, replace), second.replacing(pane, replace))
    else -> this
}

/** [pane] against [side] of [rest], taking [fraction] of the space between them. */
private fun splitOff(side: DockSide, pane: DockNode, rest: DockNode, fraction: Float): DockNode {
    val orientation = if (side == DockSide.Left || side == DockSide.Right) Orientation.Horizontal else Orientation.Vertical
    val near = side == DockSide.Left || side == DockSide.Top
    return if (near) DockSplit(orientation, fraction, pane, rest) else DockSplit(orientation, 1f - fraction, rest, pane)
}

// --- where the panes land --------------------------------------------------------------------------

/** The space a pane gets: [screen], divided by every split on the way down to it. */
internal fun dockRect(steps: List<DockStep>, screen: Rect): Rect {
    var area = screen
    steps.forEach { step ->
        val (near, far) = panesOf(area, step.orientation, step.fraction)
        area = if (step.first) near else far
    }
    return area
}

/** The two panes either side of a divider across [area]. */
internal fun panesOf(area: Rect, orientation: Orientation, fraction: Float): Pair<Rect, Rect> {
    val horizontal = orientation == Orientation.Horizontal
    val total = if (horizontal) area.width else area.height
    val near = nearSpan(total, fraction)
    return if (horizontal) {
        Rect(area.left, area.top, area.left + near, area.bottom) to
            Rect(area.left + near + DockDividerThickness, area.top, area.right, area.bottom)
    } else {
        Rect(area.left, area.top, area.right, area.top + near) to
            Rect(area.left, area.top + near + DockDividerThickness, area.right, area.bottom)
    }
}

/** The divider itself, between the two panes of [panesOf]. */
internal fun dividerOf(area: Rect, orientation: Orientation, fraction: Float): Rect {
    val horizontal = orientation == Orientation.Horizontal
    val total = if (horizontal) area.width else area.height
    val near = nearSpan(total, fraction)
    return if (horizontal) {
        Rect(area.left + near, area.top, area.left + near + DockDividerThickness, area.bottom)
    } else {
        Rect(area.left, area.top + near, area.right, area.top + near + DockDividerThickness)
    }
}

/**
 * How much of [total] the first pane gets, both panes kept at [MinDockPane] where there is room for
 * it. With less room than that the space is halved, and the divider cannot be moved.
 */
internal fun nearSpan(total: Float, fraction: Float): Float {
    val room = max(total - DockDividerThickness, 0f)
    val least = min(MinDockPane, room / 2f)
    val most = max(room - MinDockPane, room / 2f)
    return (room * fraction.coerceIn(0f, 1f)).coerceIn(least, most)
}

/**
 * One divider in a layout: which split it belongs to, where it is, and what it divides.
 *
 * [path] is the way down in the layout it was collected from. That is the layout as it is drawn,
 * which is not the one that is kept, so moving the split means [pathRetaining] first.
 */
internal class DockDividerAt(
    val path: List<Boolean>,
    val orientation: Orientation,
    val fraction: Float,
    val area: Rect,
    val rect: Rect,
)

/** Every divider in the layout, laid out over [screen], outermost first. */
internal fun DockNode.dividers(screen: Rect): List<DockDividerAt> {
    val found = mutableListOf<DockDividerAt>()
    collectDividers(screen, ArrayList(), found)
    return found
}

private fun DockNode.collectDividers(area: Rect, path: MutableList<Boolean>, into: MutableList<DockDividerAt>) {
    if (this !is DockSplit) return
    into += DockDividerAt(path.toList(), orientation, fraction, area, dividerOf(area, orientation, fraction))
    val (near, far) = panesOf(area, orientation, fraction)
    path += true
    first.collectDividers(near, path, into)
    path[path.lastIndex] = false
    second.collectDividers(far, path, into)
    path.removeAt(path.lastIndex)
}

// --- where a dragged window would land ---------------------------------------------------------------

/**
 * A place a dragged window can be dropped: a side of the screen, or of the window [window], [side]
 * being null for "tabbed with it".
 */
internal data class DockDrop(val window: String?, val side: DockSide?)

/** How big the squares are that a drag is dropped on. */
internal const val DropMarker = 26f

/** Between the middle square of a window's cross and the four around it. */
internal const val DropMarkerGap = 4f

/** How far in from the edge of the screen the four edge squares sit. */
private const val ScreenMarkerInset = 10f

/** The four squares along the edges of the screen, which dock a window against that edge. */
internal fun screenMarkers(screen: Rect): List<Pair<DockSide, Rect>> {
    val centre = screen.centre
    val half = DropMarker / 2f
    return listOf(
        DockSide.Left to marker(screen.left + ScreenMarkerInset + half, centre.y),
        DockSide.Right to marker(screen.right - ScreenMarkerInset - half, centre.y),
        DockSide.Top to marker(centre.x, screen.top + ScreenMarkerInset + half),
        DockSide.Bottom to marker(centre.x, screen.bottom - ScreenMarkerInset - half),
    )
}

/** The cross of five squares over a window: its four sides, and the middle one that tabs them together. */
internal fun windowMarkers(window: Rect): List<Pair<DockSide?, Rect>> {
    val centre = window.centre
    val step = DropMarker + DropMarkerGap
    return listOf(
        null to marker(centre.x, centre.y),
        DockSide.Left to marker(centre.x - step, centre.y),
        DockSide.Right to marker(centre.x + step, centre.y),
        DockSide.Top to marker(centre.x, centre.y - step),
        DockSide.Bottom to marker(centre.x, centre.y + step),
    )
}

private fun marker(x: Float, y: Float) = Rect(x - DropMarker / 2f, y - DropMarker / 2f, x + DropMarker / 2f, y + DropMarker / 2f)

/**
 * The window the drop squares are shown over: the front-most one [pointer] is inside, or null out on
 * the game. [windows] is front to back, the dragged window already left out.
 *
 * The squares of its own cross count as part of it. A window smaller than the cross — one folded to
 * its title bar, or a narrow one — wears squares that stick out past its edges, and a square the
 * player can see is one they can drop on: the pointer reaching one keeps the cross up, and docks.
 */
internal fun hoveredWindow(pointer: Offset, windows: List<Pair<String, Rect>>): Pair<String, Rect>? =
    windows.firstOrNull { (_, rect) -> pointer in rect || windowMarkers(rect).any { (_, square) -> pointer in square } }

/**
 * Where a drag released at [pointer] would land, or null for nowhere — which is a window left
 * floating where it was dropped.
 *
 * Only the squares count. A window dragged across the top of the screen is a window being moved, not
 * one being docked; it docks when it is let go on a square, and the squares are the only thing drawn
 * that says it will.
 */
internal fun dropTargetAt(pointer: Offset, screen: Rect, windows: List<Pair<String, Rect>>): DockDrop? {
    val over = hoveredWindow(pointer, windows)
    if (over != null) {
        windowMarkers(over.second).forEach { (side, rect) -> if (pointer in rect) return DockDrop(over.first, side) }
    }
    screenMarkers(screen).forEach { (side, rect) -> if (pointer in rect) return DockDrop(null, side) }
    return null
}

/**
 * The edge of [screen] that [rect] is nearest, measured from the middle of it.
 *
 * Which edge a floating window docks against when another one is dropped on it: the pane opens where
 * the player was already looking rather than across the screen from it.
 */
internal fun nearestSide(rect: Rect, screen: Rect): DockSide {
    val centre = rect.centre
    return listOf(
        DockSide.Left to centre.x - screen.left,
        DockSide.Right to screen.right - centre.x,
        DockSide.Top to centre.y - screen.top,
        DockSide.Bottom to screen.bottom - centre.y,
    ).minBy { (_, gap) -> gap }.first
}

// --- keeping a layout ------------------------------------------------------------------------------

/**
 * A dock layout as one line of text, so it goes in a [DebugWindowStore] beside the window positions.
 *
 * Written the way it is read: `s` for a split and the way it divides, `t` for a pane of tabs, `e` for
 * the hole, each with its own parts after it, and the two panes of a split written straight after it.
 * Anything that does not read as that is ignored, and the windows come back floating.
 */
internal object DockLayoutText {

    fun write(node: DockNode): String {
        val tokens = mutableListOf<String>()
        node.tokens(tokens)
        return tokens.joinToString("|")
    }

    fun read(text: String?): DockNode {
        if (text.isNullOrEmpty()) return DockEmpty
        val tokens = split(text)
        val reader = Reader(tokens)
        val node = reader.node() ?: return DockEmpty
        return if (reader.done()) node else DockEmpty
    }

    private fun DockNode.tokens(into: MutableList<String>) {
        when (this) {
            DockEmpty -> into += "e"
            is DockTabs -> {
                into += "t"
                into += windows.indexOf(selected).toString()
                into += windows.size.toString()
                windows.forEach { into += escape(it) }
            }
            is DockSplit -> {
                into += "s"
                into += if (orientation == Orientation.Horizontal) "h" else "v"
                into += fraction.toString()
                first.tokens(into)
                second.tokens(into)
            }
        }
    }

    /** One pass over the tokens, in the order they were written. Null at the first thing that is not a layout. */
    private class Reader(private val tokens: List<String>) {

        private var at = 0

        fun done(): Boolean = at == tokens.size

        fun node(): DockNode? {
            val kind = next() ?: return null
            return when (kind) {
                "e" -> DockEmpty
                "t" -> tabs()
                "s" -> split()
                else -> null
            }
        }

        private fun tabs(): DockNode? {
            val selected = next()?.toIntOrNull() ?: return null
            val count = next()?.toIntOrNull() ?: return null
            if (count <= 0 || count > tokens.size) return null
            val windows = (0 until count).map { unescape(next() ?: return null) }
            if (windows.distinct().size != windows.size) return null
            return DockTabs(windows, windows[selected.coerceIn(0, windows.lastIndex)])
        }

        private fun split(): DockNode? {
            val way = next() ?: return null
            val orientation = when (way) {
                "h" -> Orientation.Horizontal
                "v" -> Orientation.Vertical
                else -> return null
            }
            val fraction = next()?.toFloatOrNull() ?: return null
            if (fraction.isNaN()) return null
            val first = node() ?: return null
            val second = node() ?: return null
            return DockSplit(orientation, fraction.coerceIn(0f, 1f), first, second)
        }

        private fun next(): String? = tokens.getOrNull(at)?.also { at++ }
    }

    /** The text at every `|` that is not part of a window's own name. */
    private fun split(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var at = 0
        while (at < text.length) {
            val char = text[at]
            when {
                char == '\\' && at + 1 < text.length -> {
                    token.append(char).append(text[at + 1])
                    at += 2
                }
                char == '|' -> {
                    tokens += token.toString()
                    token.clear()
                    at++
                }
                else -> {
                    token.append(char)
                    at++
                }
            }
        }
        tokens += token.toString()
        return tokens
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                else -> append(char)
            }
        }
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '\\' && at + 1 < text.length) {
                append(if (text[at + 1] == 'p') '|' else text[at + 1])
                at += 2
            } else {
                append(char)
                at++
            }
        }
    }
}
