package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadHandler
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.BoxPolicy
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animatePlacement
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onGamepadEvent
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled

/**
 * How a table is sorted, how wide its columns have been dragged, and where its body is scrolled.
 *
 * Kept outside the table so a screen can read it — "sorted by ping" in a status line — set it from a
 * saved preference, and have it back when the screen returns under a
 * [dev.wildware.composegl.ui.saveable.SaveableStateHolder]; see [rememberTableState].
 *
 * Columns are counted in the order the table declares them, from 0.
 *
 * @param sortColumn the column sorted by to begin with, or -1 for the rows in the order they came.
 * @param descending whether that sort starts the other way round.
 */
class TableState(sortColumn: Int = -1, descending: Boolean = false) {

    /** The body's scroll: `list.scrollToItem(0)` goes back to the top of the table. */
    val list = LazyListState()

    /** The column the rows are sorted by, or -1 when they are in the order they were handed over. */
    var sortColumn: Int by mutableStateOf(sortColumn)
        private set

    /** Whether the sort is highest first. Means nothing while [sortColumn] is -1. */
    var descending: Boolean by mutableStateOf(descending)
        private set

    private val widths = mutableStateMapOf<Int, Float>()

    /** Sorts by [column], lowest first unless [descending]. A column with no `sortBy` leaves the rows as they came. */
    fun sortBy(column: Int, descending: Boolean = false) {
        sortColumn = column
        this.descending = descending
    }

    /** Back to the rows in the order they were handed over. */
    fun clearSort() {
        sortColumn = -1
        descending = false
    }

    /** The width [column] was dragged to, or null while it is the width the table declared. */
    fun columnWidth(column: Int): Float? = widths[column]

    /** Every dragged width, by column. What to write to a settings file to keep a layout between runs. */
    val columnWidths: Map<Int, Float> get() = widths.toMap()

    /** Sets [column]'s width as if it had been dragged there. Null goes back to the declared width. */
    fun setColumnWidth(column: Int, width: Float?) {
        if (width == null) {
            widths.remove(column)
        } else {
            require(width >= 0f) { "a column cannot be $width wide" }
            if (widths[column] != width) widths[column] = width
        }
    }

    /** A header clicked: sort by it, or turn the sort round when it already is the one. */
    internal fun toggleSort(column: Int) {
        if (sortColumn == column) descending = !descending else sortBy(column)
    }

    /** The pad's sort button: on to the next column that sorts, lowest first, and round to the first. */
    internal fun cycleSort(sortable: List<Int>): Boolean {
        if (sortable.isEmpty()) return false
        val at = sortable.indexOf(sortColumn)
        sortBy(sortable[(at + 1) % sortable.size])
        return true
    }
}

/**
 * A [TableState] that keeps its sort, its dragged widths and its scroll when the screen is left and
 * come back to, under a [dev.wildware.composegl.ui.saveable.SaveableStateHolder]. Outside one it is
 * plain `remember`.
 */
@Composable
fun rememberTableState(sortColumn: Int = -1, descending: Boolean = false): TableState =
    rememberSaveable { TableState(sortColumn, descending) }

/** The columns of a [Table], in the order they are shown. */
interface TableScope<T> {

    /**
     * One column.
     *
     * A column is either [width] wide or takes a share of what the fixed ones leave, by [weight]. With
     * neither it has a weight of 1. Either way it is never narrower than [minWidth].
     *
     * @param title what its header says.
     * @param sortBy what a click on its header sorts the rows by. Null for a column that does not sort,
     *   whose header cannot be clicked or focused.
     * @param align where a cell's contents and the header's title sit across the column. `Start` and
     *   `End` follow the layout direction.
     * @param resizable whether the dividers on either side of it can be dragged. A divider is only there
     *   when both the columns it sits between can be resized.
     * @param cell what one row shows in this column.
     */
    fun column(
        title: String,
        width: Float? = null,
        weight: Float? = null,
        minWidth: Float = DefaultMinColumnWidth,
        sortBy: ((T) -> Comparable<*>?)? = null,
        align: HorizontalAlignment = HorizontalAlignment.Start,
        resizable: Boolean = true,
        cell: @Composable (T) -> Unit,
    )
}

/** How narrow a column can be dragged, unless it says otherwise. */
const val DefaultMinColumnWidth = 32f

/**
 * Rows with columns that line up, sort and resize: a scoreboard, a server browser, a list of items
 * and their stats.
 *
 * ```kotlin
 * Table(rows = players, key = { it.id }, selected = picked, onSelect = { picked = it }) {
 *     column("Name", weight = 1f) { Text(it.name) }
 *     column("Kills", width = 64f, sortBy = { it.kills }) { Text("${it.kills}") }
 *     column("Ping", width = 64f, sortBy = { it.ping }, align = HorizontalAlignment.End) { Text("${it.ping}") }
 * }
 * ```
 *
 * The header stays where it is while the rows scroll under it, and only the rows that can be seen
 * are built, so a server list of thousands costs what a screenful does.
 *
 * - **Sorting.** A click on a sortable column's header sorts by it, lowest first; another click
 *   turns it round. Enter or the pad's South on a focused header does the same, and Up from the
 *   first row reaches the headers. [sortButton] — the pad's North by default — cycles through the
 *   sortable columns from anywhere in the table. The sort is stable, so rows that tie keep their
 *   order, and the rows slide to their new places rather than jumping, which needs a [key].
 * - **Resizing.** The divider between two columns drags the edge they share, and stays under the
 *   pointer: the column before it grows as the one after it shrinks, so the last column is resized
 *   by the divider at its start. A double click on it puts both columns back to their declared
 *   widths. Dragged widths live in [state] and are saveable.
 * - **Selection.** Rows take focus, so the arrows and the d-pad move from row to row. A click, Enter
 *   or South on one calls [onSelect]; the screen holds the answer and hands it back as [selected],
 *   like a [Dropdown].
 * - **Right to left.** The first column is on the right, and dragging a divider left widens its column.
 *
 * A row's sorted value is read inside a snapshot, so a column sorted by a live number — a distance
 * in `mutableStateOf` — re-sorts as the number changes.
 *
 * Everything it looks like is the skin's: `"<style>"` round the whole thing, `"<style>.header"`
 * behind the titles and `"<style>.header.cell"` for each one — `"<style>.header.cell.sorted"` for
 * the column sorted by — `"<style>.divider"` for the drag handles, whose padding says how far in the
 * line is drawn, `"<style>.row"` and `"<style>.row.alt"` for alternating rows, `"<style>.row.selected"`
 * for the chosen one, `"<style>.cell"` for the padding round every cell, and `"<style>.empty"` for
 * [empty]. A row and the header should share their horizontal padding, which is none in the skins
 * that ship, or the cells and the titles do not line up.
 *
 * @param key what identifies a row, so its focus and state follow it when the table is sorted, and
 *   so it slides. Without one a sort changes what each row says rather than moving any.
 * @param sortButton what cycles the sort column: a pad button or a key, while focus is in the table,
 *   or a mouse button over it. Null for none. Not the primary mouse button, which is a click.
 * @param scrollbar whether the body has a scroll bar. The header keeps the same room at its end, so
 *   the last column lines up with the titles.
 * @param empty what the body shows while there are no rows at all: "No servers found".
 * @param columns the columns, in order. It may read state; the table is rebuilt when that state changes.
 */
@Composable
fun <T> Table(
    rows: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    state: TableState = rememberTableState(),
    selected: T? = null,
    onSelect: ((T) -> Unit)? = null,
    sortButton: InputBinding? = InputBinding.Gamepad(GamepadButton.North),
    style: String = "table",
    scrollbar: Boolean = true,
    barThickness: Float = 8f,
    empty: (@Composable () -> Unit)? = null,
    columns: TableScope<T>.() -> Unit,
) {
    require((sortButton as? InputBinding.Mouse)?.button != PointerButton.Primary) {
        "a table cannot sort on the primary mouse button, which is what selects a row"
    }
    val declared = rememberColumns(columns)
    val all = declared.value

    val latestRows = rememberUpdatedState(rows)
    // Derived rather than sorted on every composition: the rows are only put in order again when the
    // list, the columns, the sort or a value a column sorts by has changed. Compared by content, so
    // re-sorting into the same order recomposes nothing.
    val sorted by remember(state, declared) {
        derivedStateOf(structuralEqualityPolicy()) {
            sortRows(latestRows.value, declared.value, state.sortColumn, state.descending)
        }
    }

    val gutter = if (scrollbar) barThickness else 0f
    val sizing = TableSizing(
        widths = all.mapIndexed { index, column -> state.columnWidth(index) ?: column.width ?: -1f },
        weights = all.map { it.weight ?: 1f },
        mins = all.map { it.minWidth },
        gutter = gutter,
    )

    val input = remember(state) { TableInput(state) }
    input.binding = sortButton
    input.sortable = all.indices.filter { all[it].sortBy != null }
    input.sounds = LocalUiSounds.current
    val keys = remember(input) { KeyHandler { input.onKey(it) } }
    val pad = remember(input) { GamepadHandler { input.onGamepad(it) } }
    // Only there when the binding is a mouse button: rows and headers are clickable, and a press on
    // one would never reach a handler on the table round them.
    val mouse = if (sortButton is InputBinding.Mouse) {
        Modifier.onPointer(remember(input) { PointerHandler { input.onPointer(it) } })
    } else {
        Modifier
    }

    val cellStyle = rememberStyle("$style.cell")

    Layout(
        modifier = modifier.onKeyEvent(keys).onGamepadEvent(pad).then(mouse).styled(style).clip(),
        name = "table",
        content = {
            TableHeader(all, state, sizing, style, mouse)
            if (sorted.isEmpty() && empty != null) {
                val emptyStyle = rememberStyle("$style.empty")
                val centre = remember { BoxPolicy(Alignment.Centre) }
                Layout(Modifier.styled(emptyStyle), name = "table.empty", content = {
                    ProvideContentStyle(emptyStyle) { empty() }
                }, measurePolicy = centre)
            } else {
                val rowKey = key
                LazyColumn(
                    count = sorted.size,
                    state = state.list,
                    key = if (rowKey == null) null else { index -> rowKey(sorted[index]) },
                    bars = scrollbar,
                    barThickness = barThickness,
                ) { index ->
                    val row = sorted[index]
                    TableRow(row, index, all, sizing, row == selected, onSelect, style, cellStyle, mouse)
                }
            }
        },
        measurePolicy = TablePolicy,
    )
}

/**
 * The column block, run into columns only when something it reads changes, for the same reason as
 * a sectioned lazy list's: running it makes new cell lambdas, and a row handed new ones cannot skip.
 */
@Composable
private fun <T> rememberColumns(content: TableScope<T>.() -> Unit): State<List<TableColumn<T>>> {
    val latest = rememberUpdatedState(content)
    return remember { derivedStateOf { TableColumns<T>().apply(latest.value).columns } }
}

internal class TableColumn<T>(
    val title: String,
    val width: Float?,
    val weight: Float?,
    val minWidth: Float,
    val sortBy: ((T) -> Comparable<*>?)?,
    val align: HorizontalAlignment,
    val resizable: Boolean,
    val cell: @Composable (T) -> Unit,
)

internal class TableColumns<T> : TableScope<T> {

    val columns = ArrayList<TableColumn<T>>()

    override fun column(
        title: String,
        width: Float?,
        weight: Float?,
        minWidth: Float,
        sortBy: ((T) -> Comparable<*>?)?,
        align: HorizontalAlignment,
        resizable: Boolean,
        cell: @Composable (T) -> Unit,
    ) {
        require(width == null || weight == null) { "column $title has a width and a weight; it can only be one" }
        require(width == null || width >= 0f) { "column $title cannot be $width wide" }
        require(weight == null || weight > 0f) { "column $title needs a weight above zero, not $weight" }
        require(minWidth >= 0f) { "column $title cannot be at least $minWidth wide" }
        columns += TableColumn(title, width, weight, minWidth, sortBy, align, resizable, cell)
    }
}

/**
 * [rows] in [column]'s order, or as they came when it does not sort.
 *
 * Stable, and turned round by reversing the comparison rather than the result, so rows that tie keep
 * the order they were handed over in both directions. Nulls go first when lowest is first.
 */
internal fun <T> sortRows(rows: List<T>, columns: List<TableColumn<T>>, column: Int, descending: Boolean): List<T> {
    val by = columns.getOrNull(column)?.sortBy ?: return rows
    val ascending = Comparator<T> { a, b ->
        @Suppress("UNCHECKED_CAST")
        compareValues(by(a) as Comparable<Any>?, by(b) as Comparable<Any>?)
    }
    return rows.sortedWith(if (descending) ascending.reversed() else ascending)
}

/**
 * How wide every column is, worked out the same way for the header and for each row, so they line
 * up without either asking the other.
 *
 * A data class, so a row recomposed with the same widths hands its node an equal policy and is not
 * laid out again.
 *
 * @param widths each column's fixed width, dragged or declared, or -1 for a column that takes a share.
 */
internal data class TableSizing(
    val widths: List<Float>,
    val weights: List<Float>,
    val mins: List<Float>,
    val gutter: Float,
) {

    /**
     * Every column's width across [room], which is the whole row including the gutter.
     *
     * The fixed columns take theirs, and the rest share what is left by weight. With no limit to the
     * room there is nothing left to share, and a shared column is its minimum.
     */
    fun resolve(room: Float): FloatArray {
        val count = widths.size
        val out = FloatArray(count)
        var fixed = 0f
        var weight = 0f
        for (index in 0 until count) {
            if (widths[index] >= 0f) {
                out[index] = maxOf(widths[index], mins[index])
                fixed += out[index]
            } else {
                weight += weights[index]
            }
        }
        val spare = if (room.isFinite()) (room - gutter - fixed).coerceAtLeast(0f) else 0f
        for (index in 0 until count) {
            if (widths[index] < 0f) out[index] = maxOf(mins[index], spare * weights[index] / weight)
        }
        return out
    }

    /**
     * The widths to set when the edge between [column] and the column after it is dragged [travelled]
     * along the reading direction, from where it was when the drag began with the columns [from] wide.
     *
     * The edge follows the pointer as far as the columns' minimums let it. Two fixed columns trade
     * width. Where one of the two takes a share, only the other is given a width, chosen so the edge
     * lands under the pointer even when the shares either side move with it. With no limit to the room
     * there is nothing to trade with, and [column] alone grows or shrinks.
     */
    fun moveEdge(column: Int, from: FloatArray, travelled: Float, inside: Float): Map<Int, Float> {
        val next = column + 1
        if (!inside.isFinite()) return mapOf(column to (from[column] + travelled).coerceAtLeast(mins[column]))
        if (widths[column] >= 0f && widths[next] >= 0f) {
            val by = travelled.coerceIn(mins[column] - from[column], from[next] - mins[next])
            return mapOf(column to from[column] + by, next to from[next] - by)
        }
        // The fixed one of the two is given the width, or this column when both take a share. Widening
        // this column moves the edge onwards, widening the next moves it back, so a halving search on
        // that one width finds where the edge is under the pointer, or as close as the minimums allow.
        val pinned = if (widths[next] < 0f) column else next
        val onwards = pinned == column
        val target = from.take(column + 1).sum() + travelled
        var low = mins[pinned]
        var high = widest(pinned, inside)
        repeat(EdgeSearchSteps) {
            val middle = (low + high) / 2f
            if ((edgeWith(pinned, middle, column, inside) < target) == onwards) low = middle else high = middle
        }
        return mapOf(pinned to (low + high) / 2f)
    }

    /** Where [column]'s end edge falls from the start of [inside] with [pinned] given [width]. */
    private fun edgeWith(pinned: Int, width: Float, column: Int, inside: Float): Float {
        val resolved = copy(widths = widths.toMutableList().also { it[pinned] = width }).resolve(inside + gutter)
        var edge = 0f
        for (index in 0..column) edge += resolved[index]
        return edge
    }

    /** How wide [column] may be dragged in [inside]: whatever the other columns leave at their narrowest. */
    fun widest(column: Int, inside: Float): Float {
        if (!inside.isFinite()) return Float.POSITIVE_INFINITY
        var others = 0f
        for (index in widths.indices) {
            if (index == column) continue
            others += if (widths[index] >= 0f) maxOf(widths[index], mins[index]) else mins[index]
        }
        return (inside - others).coerceAtLeast(mins[column])
    }
}

/** How many halvings find a dragged edge: well under a hundredth of a pixel across any screen. */
private const val EdgeSearchSteps = 24

/**
 * The x of every column's start edge, with [inner] the room the columns are laid across.
 *
 * [gutter] is the room the scroll bar keeps for itself, at the end of the row: the right of an
 * ordinary row, and the left of a mirrored one, which is why the mirrored columns are counted back
 * from the far edge rather than from [inner].
 */
private fun columnStarts(widths: FloatArray, inner: Float, gutter: Float, direction: LayoutDirection): FloatArray {
    val starts = FloatArray(widths.size)
    var along = 0f
    for (index in widths.indices) {
        starts[index] = if (direction == LayoutDirection.Rtl) inner + gutter - along - widths[index] else along
        along += widths[index]
    }
    return starts
}

/** The header, then the body under it with whatever height is left. */
private object TablePolicy : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val header = measurables[0].measure(Constraints(minWidth = constraints.minWidth, maxWidth = constraints.maxWidth))
        val width = header.width
        val left = if (constraints.hasBoundedHeight) (constraints.maxHeight - header.height).coerceAtLeast(0f) else Float.POSITIVE_INFINITY
        val body = measurables[1].measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                minHeight = (constraints.minHeight - header.height).coerceIn(0f, left),
                maxHeight = left,
            ),
        )
        return layout(width, constraints.constrainHeight(header.height + body.height)) {
            header.at(0f, 0f)
            body.at(0f, header.height)
        }
    }
}

/** What the header learned at its last layout, for a divider drag to start from. */
private class HeaderInfo {
    var widths = FloatArray(0)

    /** The room the columns are laid across, less the gutter. Infinite when the table has no width. */
    var inner = Float.POSITIVE_INFINITY
}

@Composable
private fun <T> TableHeader(
    columns: List<TableColumn<T>>,
    state: TableState,
    sizing: TableSizing,
    style: String,
    mouse: Modifier,
) {
    val info = remember { HeaderInfo() }
    // A divider between every two neighbouring columns that can both be resized, at the end of the
    // first. The last column's end is the edge of the table, which does not move.
    val dividers = columns.indices.filter { it < columns.size - 1 && columns[it].resizable && columns[it + 1].resizable }
    Layout(
        modifier = Modifier.styled("$style.header"),
        name = "table.header",
        content = {
            columns.forEachIndexed { index, column -> HeaderCell(column, index, state, style, mouse) }
            dividers.forEach { index -> ColumnDivider(index, state, sizing, info, style) }
        },
        measurePolicy = HeaderPolicy(sizing, dividers, info),
    )
}

@Composable
private fun <T> HeaderCell(column: TableColumn<T>, index: Int, state: TableState, style: String, mouse: Modifier) {
    val sortable = column.sortBy != null
    val sorted = sortable && state.sortColumn == index
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val resolved = rememberStyle(if (sorted) "$style.header.cell.sorted" else "$style.header.cell", states)
    val policy = remember(column.align) { BoxPolicy(Alignment(column.align, VerticalAlignment.Centre)) }
    val input = if (sortable) {
        mouse.interaction(interaction).focusable(interaction).clickable { state.toggleSort(index) }
    } else {
        Modifier
    }
    Layout(
        modifier = input.styled(resolved),
        name = "table.header.cell",
        content = {
            ProvideContentStyle(resolved) {
                // Unselectable inside a SelectionContainer, so a press on a title still sorts.
                DisableSelection {
                    Row(horizontalArrangement = Arrangement.spacedBy(SortArrowGap), verticalAlignment = VerticalAlignment.Centre) {
                        Text(column.title, maxLines = 1)
                        if (sorted) SortArrow(state.descending, resolved.textColour)
                    }
                }
            }
        },
        measurePolicy = policy,
    )
}

private const val SortArrowWidth = 8f
private const val SortArrowHeight = 5f
private const val SortArrowGap = 6f

/** Up for lowest first, down for highest first, in the title's colour. */
@Composable
private fun SortArrow(descending: Boolean, colour: Colour) {
    val draw: UiCanvas.(Rect) -> Unit = remember(descending, colour) {
        { box ->
            val middle = (box.left + box.right) / 2f
            val points = if (descending) {
                floatArrayOf(box.left, box.top, box.right, box.top, middle, box.bottom)
            } else {
                floatArrayOf(box.left, box.bottom, middle, box.top, box.right, box.bottom)
            }
            fan(points, colour)
        }
    }
    LeafLayout(Modifier.size(SortArrowWidth, SortArrowHeight), name = if (descending) "table.sort.down" else "table.sort.up", draw = draw)
}

/** How wide the grab area of a divider is. The line drawn in it is as thin as the skin's padding leaves it. */
private const val DividerGrab = 9f

/** What a divider being dragged remembers, and the latest of what it needs, outside composition. */
private class DividerDrag {
    var column = 0
    lateinit var state: TableState
    lateinit var sizing: TableSizing
    lateinit var info: HeaderInfo
    var rtl = false

    private var from = FloatArray(0)
    private var travelled = 0f

    fun start() {
        from = info.widths.copyOf()
        travelled = 0f
    }

    fun drag(delta: Offset) {
        // A press the header has not laid out for yet has nothing to start from.
        if (from.size != sizing.widths.size) return
        // In a right-to-left table a column's end edge is its left one, so leftwards is wider.
        travelled += if (rtl) -delta.x else delta.x
        sizing.moveEdge(column, from, travelled, info.inner).forEach { (index, width) -> state.setColumnWidth(index, width) }
    }

    /** Both columns either side of the divider back to their declared widths. */
    fun reset() {
        state.setColumnWidth(column, null)
        state.setColumnWidth(column + 1, null)
    }
}

@Composable
private fun ColumnDivider(column: Int, state: TableState, sizing: TableSizing, info: HeaderInfo, style: String) {
    val interaction = remember { InteractionState() }
    val line = rememberStyle("$style.divider", rememberStates(interaction))
    val drag = remember { DividerDrag() }
    drag.column = column
    drag.state = state
    drag.sizing = sizing
    drag.info = info
    drag.rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val handle = remember(drag) {
        Modifier.draggable(slop = 1f, onDragStart = { drag.start() }, onDrag = { drag.drag(it) })
            .clickable(onDoubleClick = { drag.reset() }) {}
    }
    val draw = remember(line) { dividerLine(line) }
    LeafLayout(
        modifier = Modifier.interaction(interaction).pointerHoverIcon(PointerIcon.ResizeHorizontal).then(handle),
        name = "table.divider",
        draw = draw,
    )
}

/** The style's background drawn inside the grab area, in from its edges by the style's padding. */
private fun dividerLine(style: ResolvedStyle): UiCanvas.(Rect) -> Unit = { box ->
    val padding = style.padding
    val inside = Rect(box.left + padding.left, box.top + padding.top, box.right - padding.right, box.bottom - padding.bottom)
    if (!inside.isEmpty) style.background.drawInto(this, inside, style.tint)
}

/**
 * The titles across the columns, and a divider over each column's end edge.
 *
 * Its children are every column's cell, then the dividers, in the order of [dividers].
 */
private data class HeaderPolicy(
    val sizing: TableSizing,
    val dividers: List<Int>,
    val info: HeaderInfo,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val count = sizing.widths.size
        val widths = sizing.resolve(constraints.maxWidth)
        val span = widths.sum()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(span + sizing.gutter)
        val inner = if (constraints.hasBoundedWidth) width - sizing.gutter else span

        val cells = List(count) { index ->
            measurables[index].measure(Constraints(widths[index], widths[index], 0f, constraints.maxHeight))
        }
        val height = constraints.constrainHeight(cells.maxOfOrNull { it.height } ?: 0f)
        val handles = List(dividers.size) { slot -> measurables[count + slot].measure(Constraints.fixed(DividerGrab, height)) }
        val starts = columnStarts(widths, inner, sizing.gutter, layoutDirection)
        val rtl = layoutDirection == LayoutDirection.Rtl

        return layout(width, height) {
            // Written while placing, which only a real layout does: a question about intrinsic size
            // asked with made-up room must not leave made-up widths for a drag to start from.
            info.widths = widths
            info.inner = inner
            cells.forEachIndexed { index, cell -> cell.at(starts[index], (height - cell.height) / 2f) }
            handles.forEachIndexed { slot, handle ->
                val column = dividers[slot]
                val edge = if (rtl) starts[column] else starts[column] + widths[column]
                handle.at(edge - DividerGrab / 2f, 0f)
            }
        }
    }
}

@Composable
private fun <T> TableRow(
    row: T,
    index: Int,
    columns: List<TableColumn<T>>,
    sizing: TableSizing,
    chosen: Boolean,
    onSelect: ((T) -> Unit)?,
    style: String,
    cellStyle: ResolvedStyle,
    mouse: Modifier,
) {
    val interaction = remember { InteractionState() }
    val states = rememberStates(interaction)
    val name = when {
        chosen -> "$style.row.selected"
        index % 2 == 1 -> "$style.row.alt"
        else -> "$style.row"
    }
    val resolved = rememberStyle(name, states)
    val click = if (onSelect == null) Modifier else Modifier.clickable { onSelect(row) }
    Layout(
        modifier = Modifier
            .animatePlacement()
            .then(mouse)
            .interaction(interaction)
            .focusable(interaction)
            .then(click)
            .styled(resolved),
        name = "table.row",
        content = {
            ProvideContentStyle(resolved) {
                columns.forEach { column -> TableCell(column, row, cellStyle) }
            }
        },
        measurePolicy = RowPolicy(sizing),
    )
}

@Composable
private fun <T> TableCell(column: TableColumn<T>, row: T, cellStyle: ResolvedStyle) {
    val policy = remember(column.align) { BoxPolicy(Alignment(column.align, VerticalAlignment.Centre)) }
    Layout(Modifier.styled(cellStyle), name = "table.cell", content = { column.cell(row) }, measurePolicy = policy)
}

/** One row's cells, each exactly its column's width and centred down the row. */
private data class RowPolicy(val sizing: TableSizing) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val widths = sizing.resolve(constraints.maxWidth)
        val span = widths.sum()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(span + sizing.gutter)
        val inner = if (constraints.hasBoundedWidth) width - sizing.gutter else span

        val cells = List(measurables.size) { index ->
            measurables[index].measure(Constraints(widths[index], widths[index], 0f, constraints.maxHeight))
        }
        val height = constraints.constrainHeight(cells.maxOfOrNull { it.height } ?: 0f)
        val starts = columnStarts(widths, inner, sizing.gutter, layoutDirection)
        return layout(width, height) {
            cells.forEachIndexed { index, cell -> cell.at(starts[index], (height - cell.height) / 2f) }
        }
    }
}

/** The sort button, from whichever device it is bound to. */
private class TableInput(private val state: TableState) {
    var binding: InputBinding? = null
    var sortable: List<Int> = emptyList()
    var sounds: UiSounds = UiSounds.None

    private fun cycle(): Boolean {
        if (!state.cycleSort(sortable)) return false
        sounds.change()
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        val bound = binding as? InputBinding.Keyboard ?: return false
        if (event.key != bound.key) return false
        // A held key sorts once, not once per repeat the platform sends.
        return if (event.type == KeyEventType.Down && !event.repeat) cycle() else sortable.isNotEmpty()
    }

    fun onGamepad(event: GamepadEvent): Boolean {
        val bound = binding as? InputBinding.Gamepad ?: return false
        return when (event) {
            is GamepadEvent.ButtonDown -> event.button == bound.button && cycle()
            // The button's release is used up with its press, so nothing else sees half of it.
            is GamepadEvent.ButtonUp -> event.button == bound.button && sortable.isNotEmpty()
            else -> false
        }
    }

    fun onPointer(event: PointerEvent): Boolean {
        val bound = binding as? InputBinding.Mouse ?: return false
        return event is PointerEvent.Press && event.button == bound.button && cycle()
    }
}
