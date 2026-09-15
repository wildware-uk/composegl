package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.SaveableStateHolder
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A scoreboard, driven by a mouse, a keyboard and a pad.
 *
 * Every test composes a real table and does what a player does — clicks a title, drags a divider,
 * walks the rows with the d-pad, presses the sort button — and reads the answer off the screen:
 * which row is above which, how wide a column is drawn, where focus is.
 */
class TableTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size = Size(600f, 400f),
        backend: HeadlessBackend = HeadlessBackend(),
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, backend = backend, content = content).also { opened += it }

    private class Player(val id: Int, val name: String, val kills: Int, ping: Int) {
        var ping by mutableStateOf(ping)
    }

    private val players = listOf(
        Player(1, "Ada", kills = 12, ping = 40),
        Player(2, "Bo", kills = 30, ping = 120),
        Player(3, "Cy", kills = 7, ping = 15),
        Player(4, "Di", kills = 30, ping = 60),
    )

    private var picked by mutableStateOf<Player?>(null)

    @Composable
    private fun Scoreboard(
        rows: List<Player> = players,
        state: TableState = rememberTableState(),
        sortButton: InputBinding? = InputBinding.Gamepad(GamepadButton.North),
        width: Float = 400f,
        empty: (@Composable () -> Unit)? = null,
    ) {
        Table(
            rows = rows,
            modifier = Modifier.size(width, 200f).testTag("table"),
            key = { it.id },
            state = state,
            selected = picked,
            onSelect = { picked = it },
            sortButton = sortButton,
            empty = empty,
        ) {
            column("Name", weight = 1f) { Text(it.name, Modifier.testTag("name-${it.id}")) }
            column("Kills", width = 64f, sortBy = { it.kills }) { Text("${it.kills}", Modifier.testTag("kills-${it.id}")) }
            column("Ping", width = 64f, sortBy = { it.ping }, align = HorizontalAlignment.End) {
                Text("${it.ping}", Modifier.testTag("ping-${it.id}"))
            }
        }
    }

    // --- reading the screen ----------------------------------------------------------------------

    private fun UiTest.header(): UiNode = named("table.header").single()

    private fun UiTest.titles(): List<UiNode> = named("table.header.cell")

    private fun UiTest.title(text: String): UiNode = titles().single { text in textsOf(it) }

    private fun UiTest.dividers(): List<UiNode> = named("table.divider")

    /** The row a tagged cell's contents are in. */
    private fun UiTest.rowOf(tag: String): UiNode {
        var walk: UiNode? = node(tag)
        while (walk != null && walk.name != "table.row") walk = walk.parent
        return checkNotNull(walk) { "$tag is not inside a row:\n" + dump() }
    }

    /** The players' ids from the top of the table down, by where their rows are drawn. */
    private fun UiTest.order(): List<Int> = players.map { it.id }.sortedBy { rowOf("name-$it").boundsInRoot.top }

    /** The cell box a tagged value sits in: exactly its column's width. */
    private fun UiTest.cellOf(tag: String): UiNode = node(tag).parent!!

    private fun UiTest.drag(node: UiNode, by: Float) {
        val from = node.boundsInRoot.centre
        press(from)
        dragTo(Offset(from.x + by / 2f, from.y))
        dragTo(Offset(from.x + by, from.y))
        release()
    }

    // --- layout ----------------------------------------------------------------------------------

    @Test
    fun `every title is exactly as wide as the cells under it`() {
        val ui = open { Scoreboard() }

        val titles = ui.titles()
        val cells = listOf("name-1", "kills-1", "ping-1").map { ui.cellOf(it).boundsInRoot }
        titles.zip(cells).forEach { (title, cell) ->
            assertEquals(title.boundsInRoot.left, cell.left, 0.01f, "a title and its column start together")
            assertEquals(title.boundsInRoot.right, cell.right, 0.01f, "and end together")
        }
        assertEquals(64f, cells[1].width, 0.01f)
        // 400 less the table's two-unit edge either side, the scroll bar's gutter and the two fixed columns.
        assertEquals(400f - 4f - 8f - 64f - 64f, cells[0].width, 0.01f, "the weighted column takes what is left")
    }

    @Test
    fun `an end aligned column puts its values against the end of the column`() {
        val ui = open { Scoreboard() }

        val cell = ui.cellOf("ping-1").boundsInRoot
        val value = ui.node("ping-1").boundsInRoot
        assertTrue(cell.right - value.right < 10f, "the value sits at the end, less the cell's padding: $value in $cell")
        assertTrue(value.left - cell.left > 20f, "and not at the start: $value in $cell")
    }

    @Test
    fun `the header stays put while the body scrolls under it`() {
        val many = List(60) { Player(100 + it, "P$it", it, it) }
        val ui = open { Scoreboard(rows = many) }
        val header = ui.header().boundsInRoot
        val first = ui.rowOf("name-100").boundsInRoot

        ui.scroll("name-101", Offset(0f, 120f))

        assertEquals(header, ui.header().boundsInRoot, "the header did not move")
        val moved = ui.named("table.row").minOf { it.boundsInRoot.top }
        assertTrue(moved < first.top, "the rows did: the first was at ${first.top}, the top row is now at $moved")
        assertTrue(ui.textsOf(ui.header()).containsAll(listOf("Name", "Kills", "Ping")), "and still says what the columns are")
    }

    @Test
    fun `only the rows that can be seen are built`() {
        val many = List(2_000) { Player(it, "P$it", it, it) }
        val ui = open { Scoreboard(rows = many) }

        val built = ui.named("table.row").size
        assertTrue(built in 1..30, "two thousand players and $built rows built")
    }

    @Test
    fun `a table with no rows shows what it was given for that`() {
        val ui = open { Scoreboard(rows = emptyList(), empty = { Text("No servers found", Modifier.testTag("none")) }) }

        ui.assertText("none", "No servers found")
        assertTrue(ui.node("none").boundsInRoot.top >= ui.header().boundsInRoot.bottom, "under the header")
    }

    // --- sorting with the mouse --------------------------------------------------------------------

    @Test
    fun `a click on a title sorts lowest first and another turns it round`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        assertEquals(listOf(1, 2, 3, 4), ui.order(), "as they came before anything is clicked")

        ui.click(ui.title("Kills").boundsInRoot.centre)
        // Bo and Di tie on thirty, and keep the order they came in.
        assertEquals(listOf(3, 1, 2, 4), ui.order())
        assertEquals(1, state.sortColumn)
        assertTrue(ui.named("table.sort.up").isNotEmpty(), "the title says which way")

        ui.click(ui.title("Kills").boundsInRoot.centre)
        assertEquals(listOf(2, 4, 1, 3), ui.order(), "highest first, and the tie still in the order it came")
        assertTrue(state.descending)
        assertTrue(ui.named("table.sort.down").isNotEmpty())
    }

    @Test
    fun `a click on another title sorts by that one instead`() {
        val ui = open { Scoreboard() }

        ui.click(ui.title("Kills").boundsInRoot.centre)
        ui.click(ui.title("Kills").boundsInRoot.centre)
        ui.click(ui.title("Ping").boundsInRoot.centre)

        assertEquals(listOf(3, 1, 4, 2), ui.order(), "by ping, lowest first, whatever way kills was")
        assertEquals(1, ui.named("table.sort.up").size, "and only one column says it is sorted")
    }

    @Test
    fun `a title that does not sort does nothing when clicked`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }

        ui.click(ui.title("Name").boundsInRoot.centre)

        assertEquals(-1, state.sortColumn)
        assertEquals(listOf(1, 2, 3, 4), ui.order())
    }

    @Test
    fun `rows slide to their new places rather than jumping`() {
        val ui = open { Scoreboard() }
        val before = ui.rowOf("name-3").boundsInRoot.top
        val kills = ui.title("Kills").boundsInRoot.centre

        // Delivered without settling, and then a frame or two by hand, to look in the middle of it.
        ui.input.onPointer(PointerEvent.Press(PointerId.Mouse, kills))
        ui.input.onPointer(PointerEvent.Release(PointerId.Mouse, kills))
        var nanos = ui.nanos
        repeat(3) {
            nanos += 16_666_667L
            ui.host.settle(ui.viewport, ui.focus, nanos = nanos)
        }
        val during = ui.rowOf("name-3").boundsInRoot.top

        ui.advanceBy(2_000)
        val after = ui.rowOf("name-3").boundsInRoot.top
        assertTrue(after < before, "Cy has the fewest kills and ends up at the top: $before to $after")
        assertTrue(during < before && during > after, "and on the way it was between the two: $before, $during, $after")
    }

    @Test
    fun `a value that changes while sorted by it moves its row`() {
        val state = TableState(sortColumn = 2)
        val ui = open { Scoreboard(state = state) }
        assertEquals(listOf(3, 1, 4, 2), ui.order())

        players[1].ping = 5
        ui.settle()

        assertEquals(listOf(2, 3, 1, 4), ui.order(), "Bo's ping dropped, and Bo went to the top")
        ui.assertText("ping-2", "5")
    }

    // --- resizing ----------------------------------------------------------------------------------

    @Test
    fun `dragging a divider widens its column and the rows follow`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        val name = ui.cellOf("name-1").boundsInRoot.width
        // Dividers are between columns: after Name, and after Kills.
        assertEquals(2, ui.dividers().size)

        ui.drag(ui.dividers()[1], 30f)

        assertEquals(94f, ui.cellOf("kills-1").boundsInRoot.width, 0.5f, "Kills is thirty wider")
        assertEquals(94f, ui.title("Kills").boundsInRoot.width, 0.5f, "and so is its title")
        assertEquals(34f, ui.cellOf("ping-1").boundsInRoot.width, 0.5f, "Ping, after the divider, gives up the room")
        assertEquals(name, ui.cellOf("name-1").boundsInRoot.width, 0.5f, "Name, before it, does not move")
        assertEquals(94f, state.columnWidth(1)!!, 0.5f, "which the state remembers")
        assertEquals(34f, state.columnWidth(2)!!, 0.5f)
    }

    @Test
    fun `the dragged edge and its divider stay under the pointer`() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val ui = open { ProvideLayoutDirection(direction) { Scoreboard() } }
            val rtl = direction == LayoutDirection.Rtl
            // Name then Kills: a weighted column before a fixed one. Kills then Ping: two fixed ones.
            listOf("kills-1" to 15f, "name-1" to 20f, "kills-1" to -20f, "name-1" to -50f).forEach { (tag, by) ->
                val slot = if (tag == "name-1") 0 else 1
                val pointer = ui.dividers()[slot].boundsInRoot.centre.x + (if (rtl) -by else by)

                ui.drag(ui.dividers()[slot], if (rtl) -by else by)

                val cell = ui.cellOf(tag).boundsInRoot
                val end = if (rtl) cell.left else cell.right
                assertEquals(pointer, end, 0.5f, "$direction: $tag's end edge is under the pointer after $by")
                assertEquals(pointer, ui.dividers()[slot].boundsInRoot.centre.x, 0.5f, "$direction: and so is its divider")
                val title = ui.title(if (slot == 0) "Name" else "Kills").boundsInRoot
                assertEquals(pointer, if (rtl) title.left else title.right, 0.5f, "$direction: and its title's edge")
            }
        }
    }

    @Test
    fun `the last column is resized by the divider at its start`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        val right = ui.cellOf("ping-1").boundsInRoot.right

        ui.drag(ui.dividers()[1], -20f)

        assertEquals(84f, ui.cellOf("ping-1").boundsInRoot.width, 0.5f, "Ping is twenty wider")
        assertEquals(right, ui.cellOf("ping-1").boundsInRoot.right, 0.5f, "and still ends at the edge of the table")
        assertEquals(84f, state.columnWidth(2)!!, 0.5f)
    }

    @Test
    fun `a column cannot be dragged narrower than its minimum or wider than the table`() {
        val ui = open { Scoreboard() }

        ui.drag(ui.dividers()[1], -300f)
        assertEquals(DefaultMinColumnWidth, ui.cellOf("kills-1").boundsInRoot.width, 0.5f)

        ui.drag(ui.dividers()[1], 900f)
        assertEquals(DefaultMinColumnWidth, ui.cellOf("ping-1").boundsInRoot.width, 0.5f, "Ping at its narrowest stops it")

        ui.drag(ui.dividers()[0], 900f)
        val table = ui.node("table").boundsInRoot
        assertTrue(ui.cellOf("ping-1").boundsInRoot.right <= table.right, "the last column is still inside the table")
        assertEquals(DefaultMinColumnWidth, ui.cellOf("kills-1").boundsInRoot.width, 0.5f, "with Kills at its narrowest")

        ui.drag(ui.dividers()[0], -900f)
        assertEquals(DefaultMinColumnWidth, ui.cellOf("name-1").boundsInRoot.width, 0.5f, "and Name cannot go under its own")
        assertTrue(ui.cellOf("ping-1").boundsInRoot.right <= table.right, "nor push the last column out")
    }

    @Test
    fun `a double click on a divider puts its column back`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        ui.drag(ui.dividers()[1], 40f)

        val divider = ui.dividers()[1].boundsInRoot.centre
        ui.click(divider)
        ui.click(divider)

        assertNull(state.columnWidth(1))
        assertNull(state.columnWidth(2), "and the column after it too")
        assertEquals(64f, ui.cellOf("kills-1").boundsInRoot.width, 0.5f)
        assertEquals(64f, ui.cellOf("ping-1").boundsInRoot.width, 0.5f)
    }

    @Test
    fun `dragged widths are still there when the screen comes back`() {
        var screen by mutableStateOf("scores")
        val ui = open {
            SaveableStateHolder(screen) { current ->
                if (current == "scores") Scoreboard() else Text("elsewhere")
            }
        }
        ui.drag(ui.dividers()[1], 30f)

        screen = "options"
        ui.settle()
        screen = "scores"
        ui.settle()

        assertEquals(94f, ui.cellOf("kills-1").boundsInRoot.width, 0.5f)
    }

    @Test
    fun `the pointer over a divider is the resize arrow`() {
        val ui = open { Scoreboard() }

        ui.moveTo(ui.dividers()[0].boundsInRoot.centre)

        assertEquals(dev.wildware.composegl.ui.input.PointerIcon.ResizeHorizontal, ui.pointerIcon)
    }

    // --- right to left -----------------------------------------------------------------------------

    @Test
    fun `right to left puts the first column on the right and dragging left widens`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Scoreboard() } }
        val name = ui.cellOf("name-1").boundsInRoot
        val kills = ui.cellOf("kills-1").boundsInRoot
        val ping = ui.cellOf("ping-1").boundsInRoot
        assertTrue(name.left > kills.left && kills.left > ping.left, "Name, Kills, Ping from the right: $name $kills $ping")
        assertEquals(ui.title("Kills").boundsInRoot.left, kills.left, 0.01f, "the titles are mirrored with them")

        ui.drag(ui.dividers()[1], -30f)

        assertEquals(94f, ui.cellOf("kills-1").boundsInRoot.width, 0.5f, "Kills' end edge is its left one")
    }

    // --- selection --------------------------------------------------------------------------------

    @Test
    fun `a click on a row selects it and focuses it`() {
        val ui = open { Scoreboard() }

        ui.click("name-2")

        assertSame(players[1], picked)
        assertSame(ui.rowOf("name-2"), ui.focus.focused)
    }

    @Test
    fun `the d-pad walks the rows in the order they are shown and south selects`() {
        val state = TableState(sortColumn = 1)
        val ui = open { Scoreboard(state = state) }
        ui.click("name-3")
        picked = null

        ui.pad(GamepadButton.DpadDown)
        assertSame(ui.rowOf("name-1"), ui.focus.focused, "Ada is under Cy when sorted by kills")
        ui.pad(GamepadButton.DpadDown)
        assertSame(ui.rowOf("name-2"), ui.focus.focused)

        ui.pad(GamepadButton.South)
        assertSame(players[1], picked)
    }

    @Test
    fun `the arrow keys walk the rows and enter selects`() {
        val ui = open { Scoreboard() }
        ui.click("name-1")

        ui.key(Key.Down)
        ui.key(Key.Down)
        ui.key(Key.Enter)

        assertSame(players[2], picked)
    }

    @Test
    fun `walking down past the window scrolls the body`() {
        val many = List(40) { Player(100 + it, "P$it", it, it) }
        val ui = open { Scoreboard(rows = many) }
        ui.click("name-100")

        repeat(20) { ui.pad(GamepadButton.DpadDown) }

        val row = ui.rowOf("name-120").boundsInRoot
        assertSame(ui.rowOf("name-120"), ui.focus.focused)
        assertTrue(row.bottom <= ui.node("table").boundsInRoot.bottom + 0.5f, "the focused row was scrolled into view: $row")
        assertTrue(row.top >= ui.header().boundsInRoot.bottom - 0.5f, "and not under the header: $row")
    }

    @Test
    fun `up from the first row reaches a title and south on it sorts`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        ui.click("name-1")

        ui.pad(GamepadButton.DpadUp)
        val title = ui.focus.focused
        assertEquals("table.header.cell", title?.name, "focus went up into the header:\n" + ui.dump())

        ui.pad(GamepadButton.South)
        val column = ui.titles().indexOf(title)
        assertEquals(column, state.sortColumn)
        assertTrue(column > 0, "and not onto Name, which does not sort")

        ui.pad(GamepadButton.South)
        assertTrue(state.descending, "a second press turns it round, like a second click")
    }

    // --- the sort button ----------------------------------------------------------------------------

    @Test
    fun `the pad's north cycles the sort column from anywhere in the table`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state) }
        ui.click("name-1")

        ui.pad(GamepadButton.North)
        assertEquals(1, state.sortColumn)
        assertEquals(listOf(3, 1, 2, 4), ui.order())

        ui.pad(GamepadButton.North)
        assertEquals(2, state.sortColumn, "Name does not sort, so it goes on to Ping")

        ui.pad(GamepadButton.North)
        assertEquals(1, state.sortColumn, "and round to the first that sorts")
        assertTrue(!state.descending)
    }

    @Test
    fun `the sort button can be rebound to a key`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state, sortButton = InputBinding.Keyboard(Key.S)) }
        ui.click("name-1")

        ui.pad(GamepadButton.North)
        assertEquals(-1, state.sortColumn, "North is nothing to it now")

        ui.keyDown(Key.S)
        ui.keyDown(Key.S, repeat = true)
        ui.keyUp(Key.S)
        assertEquals(1, state.sortColumn, "one press, however long it is held")
    }

    @Test
    fun `the sort button can be a mouse button pressed over the table`() {
        val state = TableState()
        val ui = open { Scoreboard(state = state, sortButton = InputBinding.Mouse(PointerButton.Tertiary)) }

        ui.click("name-2", PointerButton.Tertiary)

        assertEquals(1, state.sortColumn)
        assertNull(picked, "and it did not select the row it was over")
    }

    @Test
    fun `the primary mouse button cannot be the sort button`() {
        assertFailsWith<IllegalArgumentException> {
            open { Scoreboard(sortButton = InputBinding.Mouse(PointerButton.Primary)) }
        }
    }

    @Test
    fun `the sort button does nothing when focus is outside the table`() {
        val state = TableState()
        val ui = open {
            dev.wildware.composegl.ui.layout.Column {
                Button("PLAY", onClick = {}, initialFocus = true, modifier = Modifier.testTag("play"))
                Scoreboard(state = state)
            }
        }

        ui.pad(GamepadButton.North)

        assertEquals(-1, state.sortColumn)
    }

    // --- how it looks -------------------------------------------------------------------------------

    @Test
    fun `every other row and the chosen row wear their own styles`() {
        val backend = HeadlessBackend(dev.wildware.composegl.ui.geometry.Rect.of(0f, 0f, 600f, 400f))
        val skin = SkinFormat.read(
            """{ "styles": {
                "table.row": { "background": { "fill": "#101010" } },
                "table.row.alt": { "background": { "fill": "#202020" } },
                "table.row.selected": { "background": { "fill": "#00FF00" } }
            } }""",
        )
        picked = players[2]
        val ui = open(backend = backend) { SkinOverride(skin) { Scoreboard() } }

        ui.render()

        fun fillOf(id: Int): Colour? {
            val row = ui.rowOf("name-$id").boundsInRoot
            return backend.canvas.calls.filterIsInstance<DrawCall.Rectangle>().lastOrNull { it.rect == row }?.colour
        }
        assertEquals(Colour.rgb(0x101010), fillOf(1))
        assertEquals(Colour.rgb(0x202020), fillOf(2), "the second row is an alternate one")
        assertEquals(Colour.rgb(0x00FF00), fillOf(3), "the chosen row wins over being the third")
        assertEquals(Colour.rgb(0x202020), fillOf(4))
    }

    // --- the parts on their own ----------------------------------------------------------------------

    @Test
    fun `sorting is stable in both directions and puts nothing first`() {
        val columns = TableColumns<Pair<String, Int?>>().apply {
            column("n") { }
            column("v", sortBy = { it.second }) { }
        }.columns
        val rows = listOf("a" to 2, "b" to null, "c" to 1, "d" to 2)

        assertEquals(listOf("b", "c", "a", "d"), sortRows(rows, columns, 1, descending = false).map { it.first })
        assertEquals(listOf("a", "d", "c", "b"), sortRows(rows, columns, 1, descending = true).map { it.first })
        assertSame(rows, sortRows(rows, columns, 0, descending = false), "a column that does not sort leaves them alone")
        assertSame(rows, sortRows(rows, columns, -1, descending = false))
    }

    @Test
    fun `fixed columns take their width and weighted ones share the rest`() {
        val sizing = TableSizing(widths = listOf(-1f, 100f, -1f), weights = listOf(1f, 1f, 3f), mins = listOf(10f, 10f, 10f), gutter = 20f)

        val widths = sizing.resolve(520f)

        assertEquals(listOf(100f, 100f, 300f), widths.toList())
        assertEquals(listOf(10f, 100f, 10f), sizing.resolve(Float.POSITIVE_INFINITY).toList(), "no room to share is each at its minimum")
        assertEquals(480f, sizing.widest(1, 500f), "as wide as the others at their narrowest allow")
    }

    @Test
    fun `a dragged edge lands where it was dragged whatever takes a share`() {
        // Shares on both sides of the edge between the fixed column 1 and the weighted column 2, which
        // move as the fixed widths change, so the width set has to allow for them.
        val sizing = TableSizing(widths = listOf(-1f, 100f, -1f, 50f), weights = listOf(1f, 1f, 1f, 1f), mins = listOf(10f, 10f, 10f, 10f), gutter = 0f)
        val from = sizing.resolve(500f)
        fun edge(sizing: TableSizing, column: Int) = sizing.resolve(500f).take(column + 1).sum()

        for ((column, by) in listOf(1 to 40f, 1 to -40f, 0 to 25f, 2 to -30f)) {
            val set = sizing.moveEdge(column, from, by, 500f)
            val moved = sizing.copy(widths = sizing.widths.mapIndexed { index, width -> set[index] ?: width })
            assertEquals(from.take(column + 1).sum() + by, edge(moved, column), 0.05f, "edge $column moved $by")
        }
        val fixed = TableSizing(widths = listOf(100f, 60f), weights = listOf(1f, 1f), mins = listOf(10f, 30f), gutter = 0f)
        assertEquals(mapOf(0 to 130f, 1 to 30f), fixed.moveEdge(0, floatArrayOf(100f, 60f), 50f, 160f), "two fixed columns trade, down to a minimum")
    }

    @Test
    fun `a column with a width and a weight is refused`() {
        assertFailsWith<IllegalArgumentException> {
            TableColumns<Int>().column("both", width = 10f, weight = 1f) { }
        }
    }

    @Test
    fun `cycling the sort goes round the columns that sort`() {
        val state = TableState()

        state.cycleSort(listOf(1, 3))
        assertEquals(1, state.sortColumn)
        state.toggleSort(1)
        assertTrue(state.descending)
        state.cycleSort(listOf(1, 3))
        assertEquals(3, state.sortColumn)
        assertTrue(!state.descending, "a new column starts lowest first")
        state.cycleSort(listOf(1, 3))
        assertEquals(1, state.sortColumn)
    }
}
