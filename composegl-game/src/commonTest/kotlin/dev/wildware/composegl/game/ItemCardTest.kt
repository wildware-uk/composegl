package dev.wildware.composegl.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind an item card: the lines the game wrote, and how far each number has moved.
 *
 * Worth testing on its own rather than only through the widget, because this is where the one
 * surprising rule lives — the block is run **twice**, once for the item and once for what it would
 * replace, and the two runs are paired up by label. A game that adds a line to one item and not to
 * the other must still get its numbers lined up.
 */
class ItemCardTest {

    private class Gear(val damage: Float, val weight: Float, val sockets: Int)

    private val found = Gear(damage = 42f, weight = 3f, sockets = 2)
    private val worn = Gear(damage = 36f, weight = 4f, sockets = 2)

    private val simple: ItemCardScope.(Gear) -> Unit = {
        title("Repeater")
        stat("Damage", it.damage)
        stat("Weight", it.weight, higherIsBetter = false)
        stat("Sockets", it.sockets)
    }

    private fun stats(entries: List<CardEntry>) = entries.filterIsInstance<CardEntry.Stat>()

    @Test
    fun `the block writes the lines in the order the game wrote them`() {
        val entries = collect(simple, found)

        assertEquals(4, entries.size)
        assertTrue(entries[0] is CardEntry.Title)
        assertEquals(listOf("Damage", "Weight", "Sockets"), stats(entries).map { it.label })
        assertEquals(listOf(42f, 3f, 2f), stats(entries).map { it.value })
    }

    @Test
    fun `a difference is this item's number less the equipped one's`() {
        val deltas = deltasOf(collect(simple, found), collect(simple, worn))

        // The title has no number at all; the three stats follow it.
        assertTrue(deltas[0].isNaN(), "a heading is not a stat")
        assertEquals(6f, deltas[1])
        assertEquals(-1f, deltas[2])
        assertEquals(0f, deltas[3])
    }

    @Test
    fun `a stat the other item does not have has nothing to compare against`() {
        val mine: ItemCardScope.(Gear) -> Unit = {
            stat("Damage", it.damage)
            stat("Set bonus", 3f)
        }
        val theirs: ItemCardScope.(Gear) -> Unit = { stat("Damage", it.damage) }

        val deltas = deltasOf(collect(mine, found), collect(theirs, worn))

        assertEquals(6f, deltas[0])
        assertTrue(deltas[1].isNaN(), "there is nothing on the equipped item called that")
    }

    @Test
    fun `lines are paired by name rather than by position`() {
        val mine: ItemCardScope.(Gear) -> Unit = {
            line("Slot", "Main hand")
            stat("Damage", it.damage)
        }
        val theirs: ItemCardScope.(Gear) -> Unit = {
            stat("Damage", it.damage)
            flavour("An old thing.")
        }

        val deltas = deltasOf(collect(mine, found), collect(theirs, worn))

        assertTrue(deltas[0].isNaN(), "a labelled line is not a number")
        assertEquals(6f, deltas[1], "the damage should have found the damage")
    }

    @Test
    fun `two stats with the same name pair up first with first`() {
        val two: ItemCardScope.(Gear) -> Unit = {
            stat("Damage", it.damage)
            stat("Damage", it.damage / 2f)
        }

        val deltas = deltasOf(collect(two, found), collect(two, worn))

        assertEquals(6f, deltas[0])
        assertEquals(3f, deltas[1], "the second should not have been paired with the first again")
    }

    @Test
    fun `whole numbers are written whole and everything else to one decimal place`() {
        assertEquals("42", PlainNumber(42f))
        assertEquals("1.8", PlainNumber(1.8f))
        assertEquals("0", PlainNumber(0f))
        assertEquals("-2.5", PlainNumber(-2.5f))
        assertEquals("3", PlainNumber(2.96f), "rounded rather than cut")
    }

    @Test
    fun `a difference smaller than the format can show comes out as no difference`() {
        // What the card leans on to avoid writing "+0 ▲": a speed of 1.82 against one of 1.80
        // differs by two hundredths, and to one decimal place that is the same as no difference at
        // all. Anything that writes the way nothing writes is nothing.
        assertEquals(PlainNumber(0f), PlainNumber(0.02f))
        assertNotEquals(PlainNumber(0f), PlainNumber(0.06f), "and six hundredths does round up to something")
    }

    @Test
    fun `a whole-number stat never grows a decimal point`() {
        val entries = collect<Gear>({ stat("Sockets", it.sockets) }, found)
        val sockets = stats(entries).single()

        assertEquals("2", sockets.format(sockets.value))
    }

    @Test
    fun `a separator and plain words are lines of their own`() {
        val entries = collect<Gear>(
            {
                text("Breaks on a critical.")
                separator()
                flavour("Ash and iron.")
            },
            found,
        )

        assertTrue(entries[0] is CardEntry.Words)
        assertEquals(CardEntry.Rule, entries[1])
        assertTrue(entries[2] is CardEntry.Flavour)
    }
}
