package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.DragAndDropHost
import dev.wildware.composegl.ui.widget.MenuScope
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bag, composed for real and played with a mouse, a keyboard and a pad.
 *
 * The rules are [InventoryStateTest]'s. This is the half a player touches: a pile dragged onto an
 * empty square, a long item hanging from the square it was grabbed by, the footprint drawn ahead of
 * a drop, half a stack picked up with a modifier held, a chest on the other side of the screen, and
 * all of it mirrored for a screen that reads right to left.
 */
class InventoryGridUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size = Size(700f, 500f),
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, content = content).also { opened += it }

    private val cell = 40f
    private val spacing = 4f
    private val pitch = cell + spacing

    private fun item(
        id: String,
        kind: String = id,
        x: Int = 0,
        y: Int = 0,
        width: Int = 1,
        height: Int = 1,
        count: Int = 1,
        stackLimit: Int = 1,
    ) = InventoryItem(id, kind, InventoryCell(x, y), width, height, count, stackLimit)

    private fun bag(vararg items: InventoryItem, columns: Int = 4, rows: Int = 4): InventoryState {
        var ids = 0
        return InventoryState(columns, rows, items.toList(), newId = { "part${++ids}" })
    }

    /** One grid on a screen, with everything it needs around it. */
    @Composable
    private fun Screen(
        state: InventoryState,
        tag: String = "bag",
        rtl: Boolean = false,
        lazy: Boolean = false,
        enabled: Boolean = true,
        matches: (InventoryItem) -> Boolean = { true },
        menu: (MenuScope.(InventoryItem) -> Unit)? = null,
        focus: InventoryFocus? = null,
    ) {
        ProvideLayoutDirection(if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            PopupHost {
                DragAndDropHost {
                    Box(Modifier.fillMaxSize()) {
                        InventoryGrid(
                            state = state,
                            modifier = Modifier.testTag(tag).size(
                                state.columns * pitch - spacing,
                                if (lazy) 3 * pitch - spacing else state.rows * pitch - spacing,
                            ),
                            cellSize = cell,
                            spacing = spacing,
                            enabled = enabled,
                            matches = matches,
                            lazy = lazy,
                            focus = focus ?: rememberInventoryFocus(),
                            menu = menu,
                            slot = { Art(it) },
                        )
                    }
                }
            }
        }
    }

    /** What the game draws in a square: the kind, which is enough to read a bag off a screen. */
    @Composable
    private fun Art(item: InventoryItem) {
        Text(item.kind.toString())
    }

    private fun UiTest.square(x: Int, y: Int): Offset = node("inventory.cell.$x,$y").boundsInRoot.centre

    private fun UiTest.pile(id: String): Offset = node("inventory.item.$id").boundsInRoot.centre

    /**
     * The middle of one square of a pile bigger than one, counted from the corner its row starts at.
     *
     * The middle of the pile itself is on a gap between two of its squares, so which square that is
     * is a coin toss; a test about where a pile lands says which square the hand took hold of.
     */
    private fun UiTest.pileSquare(id: String, x: Int, y: Int): Offset =
        node("inventory.item.$id").boundsInRoot.topLeft + Offset(x * pitch + cell / 2f, y * pitch + cell / 2f)

    /** Press, move past the drag slop, move onto the target and let go: one drag with a mouse. */
    private fun UiTest.drag(from: Offset, to: Offset) {
        press(from)
        moveTo(from + Offset(20f, 0f))
        moveTo(to)
        release()
    }

    /** Every run of text [node] and everything inside it draws. Menus carry no tags of their own. */
    private fun UiTest.wordsOf(node: UiNode): String {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.texts().joinToString("")
    }

    private fun UiTest.nodesNamed(name: String): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.name == name) found += it }
        return found
    }

    private fun UiTest.menuRows(): List<UiNode> = nodesNamed("menu.item")

    private fun UiTest.clickRow(label: String) {
        val row = menuRows().lastOrNull { wordsOf(it).startsWith(label) }
            ?: throw AssertionError("no open menu has a row \"$label\":\n" + dump())
        click(row.boundsInRoot.centre)
    }

    // --- a mouse ---------------------------------------------------------------------------------

    @Test
    fun `a pile dragged onto an empty square moves there`() {
        val bag = bag(item("sword"))
        val ui = open { Screen(bag) }

        ui.drag(ui.pile("sword"), ui.square(2, 1))

        assertEquals(InventoryCell(2, 1), bag.item("sword")?.at)
        ui.assertDoesNotExist("inventory.carried")
    }

    @Test
    fun `a long item hangs from the square it was grabbed by`() {
        val bag = bag(item("bow", width = 1, height = 3))
        val ui = open { Screen(bag) }

        // Taken hold of by its bottom square, so its top lands two rows above the square dropped on.
        val bottom = ui.node("inventory.item.bow").boundsInRoot.topLeft + Offset(cell / 2f, pitch * 2 + cell / 2f)
        ui.drag(bottom, ui.square(2, 3))

        assertEquals(InventoryCell(2, 1), bag.item("bow")?.at)
    }

    @Test
    fun `the squares a drop would land on are drawn ahead of it`() {
        val bag = bag(item("crate", width = 2, height = 2), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag) }

        // Held by its top-left square, so its corner lands on whatever square it is let go over.
        val corner = ui.pileSquare("crate", 0, 0)
        ui.press(corner)
        ui.moveTo(corner + Offset(20f, 0f))
        ui.moveTo(ui.square(2, 0))

        val preview = ui.node("inventory.preview").boundsInRoot
        assertEquals(2 * pitch, preview.left, 0.5f, "over the two columns it would land in")
        assertEquals(2 * cell + spacing, preview.width, 0.5f)

        // Over the corner it would hang over the edge of the bag, and the answer changes with it.
        ui.moveTo(ui.node("inventory.item.coin").boundsInRoot.centre)
        ui.assertExists("inventory.preview.invalid")
        ui.assertDoesNotExist("inventory.preview")

        ui.release()
        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
    }

    @Test
    fun `a pile dropped on the same kind merges and shows the new count`() {
        val bag = bag(
            item("here", kind = "arrow", count = 10, stackLimit = 20),
            item("there", kind = "arrow", x = 2, count = 5, stackLimit = 20),
        )
        val ui = open { Screen(bag) }

        ui.drag(ui.pile("there"), ui.pile("here"))

        assertEquals(15, bag.item("here")?.count)
        assertTrue("15" in ui.text("inventory.item.here"), "the badge says what is in the pile now")
        ui.assertDoesNotExist("inventory.item.there")
    }

    @Test
    fun `holding the split key picks up half a pile`() {
        val bag = bag(item("arrows", kind = "arrow", count = 7, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.keyDown(Key.Shift)
        ui.press(ui.pile("arrows"))
        ui.moveTo(ui.pile("arrows") + Offset(20f, 0f))
        assertTrue("4" in ui.text("inventory.carried"), "half of it, rounded up, is in hand")
        assertTrue("3" in ui.text("inventory.item.arrows"), "and the pile shows what is left")

        ui.moveTo(ui.square(2, 2))
        ui.release()
        ui.keyUp(Key.Shift)

        assertEquals(3, bag.item("arrows")?.count)
        assertEquals(4, bag.itemAt(InventoryCell(2, 2))?.count)
    }

    @Test
    fun `half a pile dropped back on the rest of it is one pile again`() {
        val bag = bag(item("arrows", kind = "arrow", count = 6, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.keyDown(Key.Shift)
        ui.press(ui.pile("arrows"))
        ui.moveTo(ui.pile("arrows") + Offset(20f, 0f))
        ui.moveTo(ui.pile("arrows"))
        ui.release()
        ui.keyUp(Key.Shift)

        assertEquals(1, bag.items.size)
        assertEquals(6, bag.countOf("arrow"))
    }

    @Test
    fun `a split key let go of over an open menu does not go on splitting`() {
        // A menu traps focus and a shortcut only reaches inside the innermost trap, so the Shift
        // coming up never arrives at the grid. The grid lets go of it as the menu opens instead.
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.keyDown(Key.Shift)
        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.keyUp(Key.Shift)
        ui.key(Key.Escape)

        ui.drag(ui.pile("arrows"), ui.square(2, 2))

        assertEquals(1, bag.items.size, "nobody was holding the split key:\n" + ui.dump())
        assertEquals(9, bag.itemAt(InventoryCell(2, 2))?.count)
    }

    @Test
    fun `a split key let go of behind a prompt does not go on splitting`() {
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.keyDown(Key.Shift)
        ui.clickRow("Split…")
        ui.keyUp(Key.Shift)
        ui.key(Key.Escape)

        ui.drag(ui.pile("arrows"), ui.square(2, 2))

        assertEquals(1, bag.items.size, "nobody was holding the split key:\n" + ui.dump())
    }

    @Test
    fun `the next key says whether the split key is still held`() {
        // Every key carries the modifiers held with it. Whatever swallowed the release, the key
        // after it puts the grid right.
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.keyDown(Key.Shift)
        ui.key(Key.Right, Modifiers.Shift)
        ui.press(ui.pile("arrows"))
        ui.moveTo(ui.pile("arrows") + Offset(20f, 0f))
        assertTrue("5" in ui.text("inventory.carried"), "Shift is still held, so half of it is in hand")
        ui.moveTo(ui.pile("arrows"))
        ui.release()

        ui.key(Key.Right)
        ui.drag(ui.pile("arrows"), ui.square(2, 2))

        assertEquals(1, bag.items.size, "the last key said Shift had gone:\n" + ui.dump())
        assertEquals(9, bag.itemAt(InventoryCell(2, 2))?.count)
    }

    @Test
    fun `two full stacks of the same kind change places`() {
        val bag = bag(
            item("here", kind = "arrow", count = 20, stackLimit = 20),
            item("there", kind = "arrow", x = 2, count = 20, stackLimit = 20),
        )
        val ui = open { Screen(bag) }

        ui.press(ui.pile("there"))
        ui.moveTo(ui.pile("there") + Offset(20f, 0f))
        ui.moveTo(ui.pile("here"))
        ui.assertExists("inventory.preview")

        ui.release()

        // Neither has room for the other, so the green footprint meant a swap rather than a merge.
        assertEquals(InventoryCell(0, 0), bag.item("there")?.at)
        assertEquals(InventoryCell(2, 0), bag.item("here")?.at)
        assertEquals(20, bag.item("here")?.count)
        assertEquals(20, bag.item("there")?.count)
    }

    @Test
    fun `the footprint stays lit as the hand crosses the gap between two squares`() {
        val bag = bag(item("sword"))
        val ui = open { Screen(bag) }

        ui.press(ui.pile("sword"))
        ui.moveTo(ui.pile("sword") + Offset(20f, 0f))

        // The four pixels between one square and the next belong to the square before them, so the
        // footprint does not blink off as the hand crosses.
        val gap = Offset(ui.node("bag").boundsInRoot.left + pitch + cell + spacing / 2f, ui.square(1, 1).y)
        ui.moveTo(gap)
        ui.assertExists("inventory.preview")

        ui.release()
        assertEquals(InventoryCell(1, 1), bag.item("sword")?.at)
    }

    @Test
    fun `a drop the grid refuses leaves everything where it was`() {
        val bag = bag(item("crate", width = 2, height = 2), item("coin", x = 3, y = 0))
        val ui = open { Screen(bag) }

        // Held by its top-left square, the crate would hang over the right-hand edge from there.
        ui.drag(ui.pileSquare("crate", 0, 0), ui.square(3, 2))

        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
    }

    // --- the focus ring --------------------------------------------------------------------------
    //
    // A player on a pad who nudges a pile across the bag with a mouse has to be left standing on
    // that pile. The ring is drawn on whichever node the focus manager holds, so these are all one
    // question: does the node a square is drawn by still mean the same square after the bag moved?

    @Test
    fun `the focus ring follows a pile dragged to another square`() {
        val bag = bag(item("sword"), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag) }
        ui.assertFocused("inventory.item.sword")

        ui.drag(ui.pile("sword"), ui.square(2, 1))

        assertEquals(InventoryCell(2, 1), bag.item("sword")?.at)
        ui.assertFocused("inventory.item.sword")
    }

    @Test
    fun `the focus ring follows a crate that takes up four squares`() {
        val bag = bag(item("crate", width = 2, height = 2), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag) }
        ui.assertFocused("inventory.item.crate")

        ui.drag(ui.pileSquare("crate", 0, 0), ui.square(2, 2))

        assertEquals(InventoryCell(2, 2), bag.item("crate")?.at)
        ui.assertFocused("inventory.item.crate")
    }

    @Test
    fun `the focus ring lands on the stack a dropped pile merged into`() {
        val bag = bag(
            item("here", kind = "arrow", x = 0, y = 0, count = 3, stackLimit = 20),
            item("there", kind = "arrow", x = 2, y = 1, count = 4, stackLimit = 20),
        )
        val ui = open { Screen(bag) }

        // Start on the pile being moved rather than on whatever the bag focused first.
        ui.click(ui.pile("there"))
        ui.assertFocused("inventory.item.there")

        ui.drag(ui.pile("there"), ui.pile("here"))

        assertEquals(7, bag.item("here")?.count)
        assertNull(bag.item("there"), "it was poured into the other pile")
        ui.assertFocused("inventory.item.here")
    }

    @Test
    fun `a drop the grid refuses leaves the focus ring where it was`() {
        val bag = bag(item("crate", width = 2, height = 2), item("coin", x = 3, y = 0))
        val ui = open { Screen(bag) }
        ui.assertFocused("inventory.item.crate")

        // Held by its top-left square, the crate would hang over the right-hand edge from there.
        ui.drag(ui.pileSquare("crate", 0, 0), ui.square(3, 2))

        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
        ui.assertFocused("inventory.item.crate")
    }

    @Test
    fun `the focus ring follows a pile a pad carried`() {
        val bag = bag(item("sword"), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag) }
        ui.assertFocused("inventory.item.sword")

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        assertEquals(InventoryCell(1, 1), bag.item("sword")?.at)
        ui.assertFocused("inventory.item.sword")
    }

    // --- which square has the ring ----------------------------------------------------------------
    //
    // The pad's half of an item card. Nothing on a console is ever hovered, so a game that hangs a
    // card off the focused square can only do it if the grid says which square that is and where it
    // is — which is the rectangle `ItemTooltip`'s `anchor` takes.

    @Test
    fun `the grid says which pile the ring is on and where that square is`() {
        val focus = InventoryFocus()
        val bag = bag(item("sword"), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag, focus = focus) }
        ui.assertFocused("inventory.item.sword")

        assertEquals(InventoryCell(0, 0), focus.cell)
        assertEquals("sword", focus.item?.id)
        assertEquals(ui.node("inventory.item.sword").boundsInRoot, focus.bounds)
    }

    @Test
    fun `it says so for an empty square too with no pile on it`() {
        val focus = InventoryFocus()
        val bag = bag(item("sword"))
        val ui = open { Screen(bag, focus = focus) }
        ui.assertFocused("inventory.item.sword")

        ui.pad(GamepadButton.DpadRight)

        ui.assertFocused("inventory.cell.1,0")
        assertEquals(InventoryCell(1, 0), focus.cell)
        assertNull(focus.item, "an empty square has nothing on it")
        assertEquals(ui.node("inventory.cell.1,0").boundsInRoot, focus.bounds)
    }

    @Test
    fun `the answer follows the ring rather than going blank as it crosses the bag`() {
        val focus = InventoryFocus()
        val bag = bag(item("sword"), item("coin", x = 1, y = 0))
        val ui = open { Screen(bag, focus = focus) }
        ui.assertFocused("inventory.item.sword")

        // One square along is the other pile. The square being left and the square being arrived at
        // are told in whichever order they recompose in, and a card must not flicker off in between.
        ui.pad(GamepadButton.DpadRight)

        assertEquals("coin", focus.item?.id)
        assertEquals(InventoryCell(1, 0), focus.cell)
    }

    @Test
    fun `the rectangle follows a pile that moved rather than where it used to be`() {
        val focus = InventoryFocus()
        val bag = bag(item("sword"), item("coin", x = 3, y = 3))
        val ui = open { Screen(bag, focus = focus) }
        val before = focus.bounds

        ui.drag(ui.pile("sword"), ui.square(2, 1))

        ui.assertFocused("inventory.item.sword")
        assertEquals(InventoryCell(2, 1), focus.cell)
        assertEquals(ui.node("inventory.item.sword").boundsInRoot, focus.bounds)
        assertTrue(focus.bounds != before, "the pile is somewhere else now")
    }

    // --- a bag with an item card over it -----------------------------------------------------------
    //
    // The two widgets a looter puts together, at their own defaults. They each listen for a held key
    // wherever focus is, so a key both of them wanted would be one key doing two jobs: the player
    // holds it to read the arrows and walks off with half their arrows as well.

    /** A bag with a card hanging off whichever square has the ring: the pad's whole story. */
    @Composable
    private fun BagWithCard(state: InventoryState, focus: InventoryFocus) {
        PopupHost {
            DragAndDropHost {
                Box(Modifier.fillMaxSize()) {
                    InventoryGrid(
                        state = state,
                        modifier = Modifier.testTag("bag").size(
                            state.columns * pitch - spacing,
                            state.rows * pitch - spacing,
                        ),
                        cellSize = cell,
                        spacing = spacing,
                        focus = focus,
                        slot = { Art(it) },
                    )
                    // Compared against a pile of its own, so the second card — the one captioned
                    // "Equipped" — is what says whether the comparison is on.
                    ItemTooltip(
                        item = focus.item,
                        anchor = focus.bounds,
                        compareWith = item("worn", kind = "arrow"),
                    ) {
                        stat("Damage", 4f)
                    }
                }
            }
        }
    }

    /** Whether the card's second half is on screen, which is the comparison being switched on. */
    private fun UiTest.comparing(): Boolean =
        nodesNamed("itemtooltip").any { "Equipped" in wordsOf(it) }

    @Test
    fun `the bag's split key does not also turn the card's comparison on`() {
        val focus = InventoryFocus()
        val bag = bag(item("arrow", count = 6, stackLimit = 20))
        val ui = open { BagWithCard(bag, focus) }
        ui.assertFocused("inventory.item.arrow")

        ui.keyDown(Key.Shift)

        assertFalse(ui.comparing(), "Shift splits a stack; it must not be the compare key as well")
        ui.keyUp(Key.Shift)
    }

    @Test
    fun `the card's own key does turn it on`() {
        val focus = InventoryFocus()
        val bag = bag(item("arrow", count = 6, stackLimit = 20))
        val ui = open { BagWithCard(bag, focus) }
        ui.assertFocused("inventory.item.arrow")

        ui.keyDown(Key.Control)

        assertTrue(ui.comparing(), "Ctrl is what the card compares with:\n" + ui.dump())
        ui.keyUp(Key.Control)
    }

    @Test
    fun `and the bag's split key still splits one while a card is up`() {
        val focus = InventoryFocus()
        val bag = bag(item("arrow", count = 6, stackLimit = 20))
        val ui = open { BagWithCard(bag, focus) }
        ui.assertFocused("inventory.item.arrow")

        ui.keyDown(Key.Shift)
        ui.pad(GamepadButton.South)

        assertTrue("3" in ui.text("inventory.carried"), "half of it:\n" + ui.dump())
        ui.keyUp(Key.Shift)
    }

    // --- turning ---------------------------------------------------------------------------------

    @Test
    fun `the rotate key turns what is in hand and it lands the way it was turned`() {
        val bag = bag(item("rifle", width = 3, height = 1), columns = 4, rows = 4)
        val ui = open { Screen(bag) }

        // Held by its first square, so turning it leaves the hand on the top of the standing rifle.
        val muzzle = ui.node("inventory.item.rifle").boundsInRoot.topLeft + Offset(cell / 2f, cell / 2f)
        ui.press(muzzle)
        ui.moveTo(muzzle + Offset(20f, 0f))
        ui.key(Key.R)
        assertEquals(cell, ui.node("inventory.carried").boundsInRoot.width, 0.5f, "one square across now")

        ui.moveTo(ui.square(3, 1))
        ui.release()

        val rifle = bag.item("rifle")
        assertEquals(InventoryCell(3, 1), rifle?.at)
        assertEquals(1, rifle?.across)
        assertEquals(3, rifle?.down)
    }

    @Test
    fun `turning switched off at runtime switches the key and the bumper off with it`() {
        val bag = bag(item("rifle", width = 3, height = 1), columns = 4, rows = 4)
        var rotating by mutableStateOf(true)
        val ui = open {
            PopupHost {
                DragAndDropHost {
                    Box(Modifier.fillMaxSize()) {
                        InventoryGrid(
                            state = bag,
                            modifier = Modifier.size(4 * pitch - spacing, 4 * pitch - spacing),
                            cellSize = cell,
                            spacing = spacing,
                            rotating = rotating,
                            slot = { Art(it) },
                        )
                    }
                }
            }
        }

        rotating = false
        ui.settle()

        val muzzle = ui.node("inventory.item.rifle").boundsInRoot.topLeft + Offset(cell / 2f, cell / 2f)
        ui.press(muzzle)
        ui.moveTo(muzzle + Offset(20f, 0f))
        ui.key(Key.R)
        assertEquals(3 * cell + 2 * spacing, ui.node("inventory.carried").boundsInRoot.width, 0.5f, "still lying down")

        ui.pad(GamepadButton.RightBumper)
        assertEquals(3 * cell + 2 * spacing, ui.node("inventory.carried").boundsInRoot.width, 0.5f, "and the pad agrees")
        ui.release()
    }

    // --- a pad and a keyboard ----------------------------------------------------------------------

    @Test
    fun `a pad picks a pile up carries it with the d-pad and puts it down`() {
        val bag = bag(item("sword"))
        val ui = open { Screen(bag) }
        ui.assertFocused("inventory.item.sword")

        ui.pad(GamepadButton.South)
        ui.assertExists("inventory.carried")

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        assertEquals(InventoryCell(1, 1), bag.item("sword")?.at)
        ui.assertDoesNotExist("inventory.carried")
    }

    @Test
    fun `East puts a carried pile back where it came from`() {
        val bag = bag(item("sword"))
        val ui = open { Screen(bag) }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.East)

        assertEquals(InventoryCell(0, 0), bag.item("sword")?.at)
        ui.assertDoesNotExist("inventory.carried")
    }

    @Test
    fun `holding the split button splits what a pad picks up`() {
        val bag = bag(item("arrows", kind = "arrow", count = 8, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.padDown(GamepadButton.West)
        ui.pad(GamepadButton.South)
        ui.padUp(GamepadButton.West)
        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.South)

        assertEquals(4, bag.item("arrows")?.count)
        assertEquals(4, bag.itemAt(InventoryCell(1, 0))?.count)
    }

    @Test
    fun `the keyboard carries a pile with Enter and the arrows`() {
        val bag = bag(item("sword"))
        val ui = open { Screen(bag) }

        ui.key(Key.Enter)
        ui.key(Key.Down)
        ui.key(Key.Enter)

        assertEquals(InventoryCell(0, 1), bag.item("sword")?.at)
    }

    // --- two grids ---------------------------------------------------------------------------------

    @Composable
    private fun TwoGrids(bag: InventoryState, chest: InventoryState) {
        PopupHost {
            DragAndDropHost {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(60f)) {
                    InventoryGrid(
                        state = bag,
                        modifier = Modifier.testTag("bag"),
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                    InventoryGrid(
                        state = chest,
                        modifier = Modifier.testTag("chest"),
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                }
            }
        }
    }

    /** The middle of one square of the grid tagged [grid], worked out rather than looked up. */
    private fun UiTest.squareOf(grid: String, x: Int, y: Int): Offset {
        val bounds = node(grid).boundsInRoot
        return Offset(bounds.left + x * pitch + cell / 2f, bounds.top + y * pitch + cell / 2f)
    }

    @Test
    fun `a pile dragged out of the bag lands in the chest`() {
        val bag = bag(item("sword"))
        val chest = bag(columns = 4, rows = 4)
        val ui = open { TwoGrids(bag, chest) }

        ui.drag(ui.squareOf("bag", 0, 0), ui.squareOf("chest", 1, 2))

        assertNull(bag.item("sword"))
        assertEquals(InventoryCell(1, 2), chest.item("sword")?.at)
    }

    @Test
    fun `what will not fit in the chest's stack comes home to the bag`() {
        val bag = bag(item("mine", kind = "arrow", count = 9, stackLimit = 20))
        val chest = bag(item("theirs", kind = "arrow", count = 18, stackLimit = 20))
        val ui = open { TwoGrids(bag, chest) }

        ui.drag(ui.squareOf("bag", 0, 0), ui.squareOf("chest", 0, 0))

        assertEquals(20, chest.item("theirs")?.count)
        assertEquals(7, bag.countOf("arrow"), "the overflow went back rather than vanishing")
    }

    @Test
    fun `a pad carries a pile from one grid to the other`() {
        val bag = bag(item("sword"), columns = 2, rows = 2)
        val chest = bag(columns = 2, rows = 2)
        val ui = open { TwoGrids(bag, chest) }

        ui.pad(GamepadButton.South)
        repeat(2) { ui.pad(GamepadButton.DpadRight) }
        ui.pad(GamepadButton.South)

        assertNull(bag.item("sword"))
        assertEquals(InventoryCell(0, 0), chest.item("sword")?.at)
    }

    // --- the menu on a pile --------------------------------------------------------------------------

    @Test
    fun `a right-click on a pile offers to split it in half`() {
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.clickRow("Split half")

        assertEquals(2, bag.items.size)
        assertEquals(9, bag.countOf("arrow"))
    }

    @Test
    fun `a right-click on a long item offers to turn it`() {
        val bag = bag(item("rifle", width = 3, height = 1))
        val ui = open { Screen(bag) }

        ui.click(ui.pile("rifle"), PointerButton.Secondary)
        ui.clickRow("Rotate")

        assertEquals(3, bag.item("rifle")?.down)
    }

    @Test
    fun `the split prompt takes the number the stepper is on`() {
        val bag = bag(item("arrows", kind = "arrow", count = 10, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.clickRow("Split")
        ui.assertExists("inventory.split")

        // It opens on half, and the stepper's right arrow is one more each press.
        ui.key(Key.Right)
        ui.click("inventory.split.ok")

        assertEquals(4, bag.item("arrows")?.count)
        assertEquals(6, bag.matching { it.id != "arrows" }.single().count)
        ui.assertDoesNotExist("inventory.split")
    }

    @Test
    fun `Escape closes the split prompt without splitting anything`() {
        val bag = bag(item("arrows", kind = "arrow", count = 10, stackLimit = 20))
        val ui = open { Screen(bag) }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.clickRow("Split half")
        ui.assertDoesNotExist("inventory.split")

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.clickRow("Split…")
        ui.key(Key.Escape)

        ui.assertDoesNotExist("inventory.split")
        assertEquals(2, bag.items.size, "only the half from the first menu")
    }

    @Test
    fun `the game's own menu entries come first and the grid's own come under a line`() {
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val examined = mutableListOf<String>()
        val ui = open {
            Screen(bag, menu = { pile -> Item("Examine") { examined += pile.id.toString() } })
        }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)

        assertEquals(
            listOf("Examine", "Split half", "Split…"),
            ui.menuRows().map { ui.wordsOf(it) },
            "the game's own first, then the grid's",
        )
        assertTrue(ui.nodesNamed("divider").isNotEmpty(), "with a line between the two groups")

        ui.clickRow("Examine")
        assertEquals(listOf("arrows"), examined, "and the game's own entry fires on the pile it was opened on")
    }

    @Test
    fun `a switched-off bag offers nothing in its menu that would rearrange it`() {
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val ui = open { Screen(bag, enabled = false) }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)

        assertEquals(emptyList(), ui.menuRows(), "a read-only bag has nothing of its own to offer")
        assertEquals(1, bag.items.size)
        assertEquals(9, bag.item("arrows")?.count)
    }

    @Test
    fun `a switched-off bag still offers what the game wrote itself`() {
        val bag = bag(item("arrows", kind = "arrow", count = 9, stackLimit = 20))
        val examined = mutableListOf<String>()
        val ui = open {
            Screen(bag, enabled = false, menu = { pile -> Item("Examine") { examined += pile.id.toString() } })
        }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)

        assertEquals(listOf("Examine"), ui.menuRows().map { ui.wordsOf(it) }, "reading it is not rearranging it")
        ui.clickRow("Examine")
        assertEquals(listOf("arrows"), examined)
        assertEquals(1, bag.items.size, "and nothing in the bag moved")
    }

    // --- the game's own rules ------------------------------------------------------------------------

    @Test
    fun `a rule of the game's own is asked before the square lights up and before the drop`() {
        val bag = bag(item("sword"))
        val moves = mutableListOf<Pair<String, InventoryCell>>()
        val ui = open {
            PopupHost {
                DragAndDropHost {
                    InventoryGrid(
                        state = bag,
                        modifier = Modifier.testTag("bag"),
                        // Nothing in the bottom row: the game says so, not the grid.
                        canPlace = { _, at -> at.y < 3 && bag.canPlace(item("sword"), at) },
                        onMove = { moved, to -> moves += moved.id.toString() to to; bag.move(moved, to) },
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                }
            }
        }

        ui.press(ui.pile("sword"))
        ui.moveTo(ui.pile("sword") + Offset(20f, 0f))
        ui.moveTo(ui.square(1, 3))
        ui.assertExists("inventory.preview.invalid")
        ui.release()

        assertEquals(emptyList(), moves)
        assertEquals(InventoryCell(0, 0), bag.item("sword")?.at)

        ui.drag(ui.pile("sword"), ui.square(1, 2))
        assertEquals(listOf("sword" to InventoryCell(1, 2)), moves)
    }

    @Test
    fun `the grid's own split asks the game where the new pile may go`() {
        // The bag is two across and the game allows only its first row, which is already full: so
        // there is nowhere the half may go, and rather than land somewhere forbidden it is not split.
        val bag = bag(item("arrows", kind = "arrow", count = 8, stackLimit = 20), item("sword", x = 1), columns = 2)
        val ui = open {
            PopupHost {
                DragAndDropHost {
                    InventoryGrid(
                        state = bag,
                        modifier = Modifier.testTag("bag"),
                        canPlace = { moved, at -> at.y < 1 && bag.canPlace(moved, at) },
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                }
            }
        }

        ui.click(ui.pile("arrows"), PointerButton.Secondary)
        ui.clickRow("Split half")

        assertEquals(2, bag.items.size)
        assertEquals(8, bag.item("arrows")?.count, "the pile is whole, rather than half of it in a forbidden square")
    }

    @Test
    fun `the grid's own turn asks the game about the squares it would cover`() {
        // Nothing may reach past the second row, and a rifle stood on end reaches the third.
        val bag = bag(item("rifle", width = 3, height = 1))
        val ui = open {
            PopupHost {
                DragAndDropHost {
                    InventoryGrid(
                        state = bag,
                        modifier = Modifier.testTag("bag"),
                        canPlace = { moved, at -> at.y + moved.down <= 2 && bag.canPlace(moved, at) },
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                }
            }
        }

        ui.click(ui.pile("rifle"), PointerButton.Secondary)
        ui.clickRow("Rotate")

        assertEquals(3, bag.item("rifle")?.across, "still lying down")
    }

    @Test
    fun `a bag that is switched off cannot be rearranged or reached`() {
        val bag = bag(item("sword"))
        val ui = open {
            PopupHost {
                DragAndDropHost {
                    InventoryGrid(
                        state = bag,
                        modifier = Modifier.testTag("bag"),
                        enabled = false,
                        cellSize = cell,
                        spacing = spacing,
                        slot = { Art(it) },
                    )
                }
            }
        }

        ui.drag(ui.pile("sword"), ui.square(2, 2))

        assertEquals(InventoryCell(0, 0), bag.item("sword")?.at)
        ui.assertDoesNotExist("inventory.carried")
        assertNull(ui.focus.focused, "nothing in it is a place a pad can stand")
    }

    // --- reading the bag ---------------------------------------------------------------------------

    @Test
    fun `a pile the filter says no to is still there and still says what it is`() {
        val bag = bag(item("sword"), item("potion", x = 1))
        val ui = open { Screen(bag, matches = { it.kind == "sword" }) }

        assertEquals("potion", ui.text("inventory.item.potion"))
        assertTrue(ui.node("inventory.item.potion").resolved.alpha < 1f, "and it is faded")
        assertEquals(1f, ui.node("inventory.item.sword").resolved.alpha)
    }

    @Test
    fun `in a right-to-left screen the first column is on the right`() {
        val bag = bag(item("sword"), item("shield", x = 3))
        val ui = open { Screen(bag, rtl = true) }

        val first = ui.node("inventory.item.sword").boundsInRoot
        val last = ui.node("inventory.item.shield").boundsInRoot
        assertTrue(first.left > last.left, "the first column is the right-hand one")

        ui.drag(ui.pile("sword"), ui.square(2, 0))
        assertEquals(InventoryCell(2, 0), bag.item("sword")?.at)
    }

    @Test
    fun `a long item in a right-to-left screen still hangs from the square it was grabbed by`() {
        val bag = bag(item("rifle", width = 3, height = 1))
        val ui = open { Screen(bag, rtl = true) }

        // The rifle's first column is its right-hand one, so its last column is the leftmost pixels.
        val bounds = ui.node("inventory.item.rifle").boundsInRoot
        val lastColumn = Offset(bounds.left + cell / 2f, bounds.centre.y)
        ui.drag(lastColumn, ui.square(2, 2))

        assertEquals(InventoryCell(0, 2), bag.item("rifle")?.at)
    }

    @Test
    fun `a lazy grid builds only the rows in view`() {
        val bag = bag(item("sword"), item("deep", y = 19), columns = 4, rows = 20)
        val ui = open { Screen(bag, lazy = true) }

        ui.assertExists("inventory.cell.0,1")
        ui.assertDoesNotExist("inventory.cell.0,19")
        ui.assertDoesNotExist("inventory.item.deep")

        ui.scroll("bag", Offset(0f, pitch * 16))
        ui.assertExists("inventory.item.deep")
        ui.assertDoesNotExist("inventory.cell.0,0")
    }
}
