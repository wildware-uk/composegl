package dev.wildware.composegl.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of a bag, with nothing drawn.
 *
 * Every fiddly thing the issue asks for — stacks that merge and split, items bigger than one
 * square, a sort that repacks, the swap a drop onto one other item makes — is arithmetic over a
 * grid, and this is where it is held to its word. What a player does with a mouse and a pad is
 * [InventoryGridUiTest].
 */
class InventoryStateTest {

    private var ids = 0

    private fun bag(columns: Int = 4, rows: Int = 4, items: List<InventoryItem> = emptyList()) =
        InventoryState(columns, rows, items, newId = { "split${++ids}" })

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

    // --- what fits where -------------------------------------------------------------------------

    @Test
    fun `an item covers every square of its footprint`() {
        val bow = item("bow", width = 1, height = 3, x = 1, y = 1)
        val bag = bag(items = listOf(bow))

        assertEquals(bow, bag.itemAt(InventoryCell(1, 3)))
        assertNull(bag.itemAt(InventoryCell(2, 3)))
        assertNull(bag.itemAt(InventoryCell(1, 0)))
    }

    @Test
    fun `a turned item covers its footprint the other way round`() {
        val rifle = item("rifle", width = 3, height = 1)
        val bag = bag(items = listOf(rifle.copy(rotated = true)))

        assertEquals(1, bag.itemAt(InventoryCell(0, 2))?.across)
        assertNull(bag.itemAt(InventoryCell(2, 0)))
    }

    @Test
    fun `nothing hangs over the edge`() {
        val bag = bag(columns = 4, rows = 4)
        val crate = item("crate", width = 2, height = 2)

        assertTrue(bag.fits(crate, InventoryCell(2, 2)))
        assertFalse(bag.fits(crate, InventoryCell(3, 2)))
        assertFalse(bag.fits(crate, InventoryCell(0, -1)))
    }

    @Test
    fun `an occupied square is not a free one`() {
        val bag = bag(items = listOf(item("sword", y = 1)))

        assertFalse(bag.fits(item("shield"), InventoryCell(0, 1)))
        assertEquals(InventoryCell(0, 0), bag.firstFree(item("shield")))
        assertEquals(15, bag.freeCells)
    }

    // --- moving ----------------------------------------------------------------------------------

    @Test
    fun `an item moves onto free squares`() {
        val sword = item("sword")
        val bag = bag(items = listOf(sword))

        assertTrue(bag.move(sword, InventoryCell(3, 3)))
        assertEquals(InventoryCell(3, 3), bag.item("sword")?.at)
    }

    @Test
    fun `a move that would land on two items does nothing`() {
        val bag = bag(items = listOf(item("a", x = 0, y = 2), item("b", x = 1, y = 2)))
        val crate = item("crate", width = 2, height = 2)
        bag.add(crate)

        assertFalse(bag.move(crate, InventoryCell(0, 1)))
        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
    }

    @Test
    fun `dropping one item on another swaps them`() {
        val sword = item("sword", x = 0, y = 0)
        val shield = item("shield", x = 2, y = 2)
        val bag = bag(items = listOf(sword, shield))

        assertTrue(bag.move(sword, InventoryCell(2, 2)))
        assertEquals(InventoryCell(2, 2), bag.item("sword")?.at)
        assertEquals(InventoryCell(0, 0), bag.item("shield")?.at)
    }

    @Test
    fun `a swap that leaves the other item nowhere to go is refused`() {
        // A coin dropped on a crate would push a two-by-two into the one square the coin came out
        // of, which is not a square it fits in.
        val crate = item("crate", width = 2, height = 2, x = 0, y = 0)
        val coin = item("coin", x = 3, y = 3)
        val bag = bag(items = listOf(crate, coin))

        assertFalse(bag.move(coin, InventoryCell(0, 0)))
        assertEquals(InventoryCell(3, 3), bag.item("coin")?.at)
        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
    }

    @Test
    fun `a move onto the square it is already on is no move at all`() {
        val sword = item("sword")
        val bag = bag(items = listOf(sword))

        assertFalse(bag.move(sword, InventoryCell(0, 0)))
    }

    // --- stacks ----------------------------------------------------------------------------------

    @Test
    fun `dropping arrows on arrows makes one pile`() {
        val first = item("a", kind = "arrow", count = 10, stackLimit = 20)
        val second = item("b", kind = "arrow", x = 2, count = 5, stackLimit = 20)
        val bag = bag(items = listOf(first, second))

        assertTrue(bag.move(second, InventoryCell(0, 0)))
        assertEquals(15, bag.item("a")?.count)
        assertNull(bag.item("b"))
    }

    @Test
    fun `what will not fit in a full stack stays where it was`() {
        val first = item("a", kind = "arrow", count = 18, stackLimit = 20)
        val second = item("b", kind = "arrow", x = 2, count = 5, stackLimit = 20)
        val bag = bag(items = listOf(first, second))

        assertTrue(bag.move(second, InventoryCell(0, 0)))
        assertEquals(20, bag.item("a")?.count)
        assertEquals(3, bag.item("b")?.count)
        assertEquals(InventoryCell(2, 0), bag.item("b")?.at)
    }

    @Test
    fun `two full stacks of the same kind change places`() {
        // Nothing can move between two full quivers, so the drop is a swap like any other and the
        // footprint that lit up green was telling the truth.
        val first = item("a", kind = "arrow", count = 20, stackLimit = 20)
        val second = item("b", kind = "arrow", x = 2, count = 20, stackLimit = 20)
        val bag = bag(items = listOf(first, second))

        assertTrue(bag.canPlace(bag.item("b")!!, InventoryCell(0, 0)))
        assertTrue(bag.move(bag.item("b")!!, InventoryCell(0, 0)))
        assertEquals(InventoryCell(0, 0), bag.item("b")?.at)
        assertEquals(InventoryCell(2, 0), bag.item("a")?.at)
        assertEquals(20, bag.item("a")?.count)
        assertEquals(20, bag.item("b")?.count)
    }

    @Test
    fun `a full stack arriving from another bag has nowhere to go`() {
        // A swap needs somewhere to put what comes out, and a stranger's old square is not this
        // bag's to fill — so accept and canPlace both say no where move would have swapped.
        val bag = bag(items = listOf(item("a", kind = "arrow", count = 20, stackLimit = 20)))
        val theirs = item("b", kind = "arrow", count = 5, stackLimit = 20)

        assertFalse(bag.canPlace(theirs, InventoryCell(0, 0)))
        assertEquals(theirs, bag.accept(theirs, InventoryCell(0, 0)))
    }

    @Test
    fun `things that do not stack never merge`() {
        val bag = bag(items = listOf(item("a", kind = "sword"), item("b", kind = "sword", x = 2)))

        // Two swords are the same kind and stack to one: dropping one on the other is a swap.
        assertTrue(bag.move(bag.item("b")!!, InventoryCell(0, 0)))
        assertEquals(1, bag.item("a")?.count)
        assertEquals(1, bag.item("b")?.count)
    }

    @Test
    fun `a stack splits in half and the halves are two piles`() {
        val arrows = item("arrows", count = 7, stackLimit = 20)
        val bag = bag(items = listOf(arrows))

        val half = bag.splitHalf(arrows)
        assertNotNull(half)
        assertEquals(4, half.count)
        assertEquals(3, bag.item("arrows")?.count)
        assertEquals(2, bag.items.size)
    }

    @Test
    fun `a pile of one does not split`() {
        val bag = bag(items = listOf(item("arrows", count = 1, stackLimit = 20)))

        assertNull(bag.split(bag.item("arrows")!!, 1))
    }

    @Test
    fun `taking part of a pile leaves the rest behind`() {
        val bag = bag(items = listOf(item("arrows", count = 9, stackLimit = 20)))

        val taken = bag.take(bag.item("arrows")!!, 4)
        assertEquals(4, taken?.count)
        assertEquals(5, bag.item("arrows")?.count)
    }

    @Test
    fun `taking all of a pile takes the pile`() {
        val bag = bag(items = listOf(item("arrows", count = 9, stackLimit = 20)))

        val taken = bag.take(bag.item("arrows")!!, 9)
        assertEquals("arrows", taken?.id)
        assertTrue(bag.items.isEmpty())
    }

    // --- arriving from somewhere else --------------------------------------------------------------

    @Test
    fun `an item from another bag lands on free squares`() {
        val chest = bag()
        val arriving = item("sword")

        assertNull(chest.accept(arriving, InventoryCell(1, 1)))
        assertEquals(InventoryCell(1, 1), chest.item("sword")?.at)
    }

    @Test
    fun `an item from another bag merges into a stack and hands back the overflow`() {
        val chest = bag(items = listOf(item("here", kind = "arrow", count = 18, stackLimit = 20)))

        val left = chest.accept(item("there", kind = "arrow", count = 5, stackLimit = 20), InventoryCell(0, 0))
        assertEquals(3, left?.count)
        assertEquals(20, chest.item("here")?.count)
    }

    @Test
    fun `an item from another bag never pushes one out of the way`() {
        val chest = bag(items = listOf(item("sword")))

        val left = chest.accept(item("shield"), InventoryCell(0, 0))
        assertEquals("shield", left?.id)
        assertNull(chest.item("shield"))
    }

    @Test
    fun `loot fills the stacks it can and then takes a square of its own`() {
        val bag = bag(items = listOf(item("here", kind = "arrow", count = 18, stackLimit = 20)))

        assertNull(bag.addAnywhere(item("loot", kind = "arrow", count = 25, stackLimit = 20)))
        assertEquals(43, bag.countOf("arrow"))
        assertEquals(3, bag.items.size)
    }

    @Test
    fun `loot a full bag cannot hold comes back`() {
        val bag = bag(columns = 1, rows = 1, items = listOf(item("sword")))

        assertEquals(2, bag.addAnywhere(item("coin", count = 2))?.count)
    }

    // --- turning and tidying -----------------------------------------------------------------------

    @Test
    fun `a long item turns on the spot when there is room`() {
        val rifle = item("rifle", width = 3, height = 1)
        val bag = bag(items = listOf(rifle))

        assertTrue(bag.rotate(rifle))
        assertEquals(1, bag.item("rifle")?.across)
        assertEquals(3, bag.item("rifle")?.down)
    }

    @Test
    fun `a square item has nothing to turn`() {
        val bag = bag(items = listOf(item("crate", width = 2, height = 2)))

        assertFalse(bag.rotate(bag.item("crate")!!))
    }

    @Test
    fun `a turn with something in the way is refused`() {
        val bag = bag(items = listOf(item("rifle", width = 3, height = 1), item("coin", x = 0, y = 1)))

        assertFalse(bag.rotate(bag.item("rifle")!!))
    }

    @Test
    fun `a sort merges the stacks and packs the big things in first`() {
        val bag = bag(
            items = listOf(
                item("a", kind = "arrow", x = 3, y = 3, count = 9, stackLimit = 20),
                item("b", kind = "arrow", x = 0, y = 2, count = 9, stackLimit = 20),
                item("crate", kind = "crate", x = 2, y = 0, width = 2, height = 2),
            ),
        )

        assertTrue(bag.sort())
        assertEquals(InventoryCell(0, 0), bag.item("crate")?.at)
        assertEquals(1, bag.matching { it.kind == "arrow" }.size)
        assertEquals(18, bag.countOf("arrow"))
    }

    @Test
    fun `a sort that cannot lay everything out again moves nothing`() {
        // Two two-by-ones in a two-by-two bag fit; a sort that had to put them both in one row
        // would not, and the bag is left exactly as it was rather than half done.
        val bag = bag(columns = 2, rows = 2, items = listOf(item("a", width = 2), item("b", width = 2, y = 1)))
        bag.columns = 1

        assertFalse(bag.sort())
        assertEquals(InventoryCell(0, 0), bag.item("a")?.at)
        assertEquals(InventoryCell(0, 1), bag.item("b")?.at)
    }

    // --- the questions a drop asks -----------------------------------------------------------------

    @Test
    fun `canPlace says yes to free squares to a stack with room and to a swap`() {
        val bag = bag(
            items = listOf(
                item("sword"),
                item("arrows", kind = "arrow", x = 2, count = 5, stackLimit = 20),
                item("more", kind = "arrow", x = 3, count = 5, stackLimit = 20),
            ),
        )

        assertTrue(bag.canPlace(bag.item("sword")!!, InventoryCell(1, 1)))
        assertTrue(bag.canPlace(bag.item("more")!!, InventoryCell(2, 0)))
        assertTrue(bag.canPlace(bag.item("sword")!!, InventoryCell(2, 0)))
    }

    @Test
    fun `canPlace says no to a stranger that would push something out`() {
        val bag = bag(items = listOf(item("sword")))

        assertFalse(bag.canPlace(item("shield"), InventoryCell(0, 0)))
    }
}
