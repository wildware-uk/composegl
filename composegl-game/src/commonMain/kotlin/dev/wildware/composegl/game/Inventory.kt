package dev.wildware.composegl.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A square of an [InventoryState]: [x] columns along a row, [y] rows down.
 *
 * Counted from the corner a row starts at, which is the top left in English and the top right in
 * Arabic. Nothing here knows which — a bag is six across whichever way the screen reads, and
 * [InventoryGrid] is the only thing that turns a column into a side of the screen.
 */
data class InventoryCell(val x: Int, val y: Int)

/**
 * One pile in a bag: what it is, how big it is, and how many of it there are.
 *
 * [id] is this pile and [kind] is what it holds. Two piles of arrows have two ids and one kind:
 * the id is what follows a pile as it is dragged, split and put down again, and the kind is what
 * says the two of them would merge. A game usually makes [kind] its own item type, which is then
 * what the slot's picture is looked up from.
 *
 * A pile bigger than one square — the bow, the rifle case, the two-by-four crate — says so with
 * [width] and [height], and [rotated] turns it on its side. [across] and [down] are the size it
 * actually covers, which is the pair everything else works in.
 *
 * @param at the square its top-left corner is in, in the grid's own columns and rows.
 * @param count how many are in this pile. Never below one: a pile of nothing is no pile.
 * @param stackLimit the most that fit in one pile. One means it does not stack at all, which is
 *   right for a sword and wrong for an arrow. A [count] over the limit is how a game says "fifty
 *   arrows" to [InventoryState.addAnywhere], which breaks it into as many piles as it takes.
 */
data class InventoryItem(
    val id: Any,
    val kind: Any,
    val at: InventoryCell = InventoryCell(0, 0),
    val width: Int = 1,
    val height: Int = 1,
    val count: Int = 1,
    val stackLimit: Int = 1,
    val rotated: Boolean = false,
) {

    init {
        require(width >= 1 && height >= 1) { "an item is at least one square, not $width by $height" }
        require(count >= 1) { "an item is at least one of something, not $count" }
        require(stackLimit >= 1) { "a stack holds at least one, not $stackLimit" }
    }

    /** How many columns it covers as it is turned now. */
    val across: Int get() = if (rotated) height else width

    /** How many rows it covers as it is turned now. */
    val down: Int get() = if (rotated) width else height

    /** Whether turning it would change anything. A two-by-two crate looks the same either way. */
    val canTurn: Boolean get() = width != height

    /** Whether more of the same thing can go in this pile. */
    val isStackable: Boolean get() = stackLimit > 1

    /** How many more would fit in this pile. */
    val room: Int get() = (stackLimit - count).coerceAtLeast(0)

    /** Whether [cell] is one of the squares this pile covers, with its corner at [origin]. */
    fun covers(cell: InventoryCell, origin: InventoryCell = at): Boolean =
        cell.x >= origin.x && cell.x < origin.x + across && cell.y >= origin.y && cell.y < origin.y + down

    /** Whether the two would become one pile: the same thing, and something that stacks at all. */
    fun stacksWith(other: InventoryItem): Boolean = isStackable && other.kind == kind

    /** The same pile lying the other way. */
    fun turned(): InventoryItem = copy(rotated = !rotated)

    /** Whether this pile and [other] cover a square in common, each with its own corner. */
    internal fun overlaps(other: InventoryItem): Boolean =
        at.x < other.at.x + other.across && other.at.x < at.x + across &&
            at.y < other.at.y + other.down && other.at.y < at.y + down
}

/**
 * A bag, a chest, a stash tab: how many squares it has and what is in them.
 *
 * It is the rulebook rather than the picture. Everything a game has to get right about an
 * inventory — where a thing fits, what merges into what, what a split leaves behind, how a sort
 * repacks it — lives here and can be tested without composing anything. [InventoryGrid] draws one
 * and hands the player's pointer, keyboard and pad to it.
 *
 * ```kotlin
 * val bag = remember { InventoryState(columns = 8, rows = 6, items = save.items) }
 * bag.move(bag.item("bow")!!, InventoryCell(2, 0))
 * ```
 *
 * Two grids on one screen are two of these, and an item dragged from one to the other is a [take]
 * on the first and an [accept] on the second. Nothing here knows about the other grid.
 *
 * @param newId what a pile split off another is called. A fresh object by default, which is enough
 *   for a bag that lives as long as the screen; a game that writes its bag to a save file gives
 *   its own, so that the halves of a split still have names when it is read back.
 */
@Stable
class InventoryState(
    columns: Int,
    rows: Int,
    items: List<InventoryItem> = emptyList(),
    val newId: () -> Any = { Any() },
) {

    /** How many squares across. Changing it does not move anything: an item outside is simply lost from view. */
    var columns: Int by mutableStateOf(columns)

    /** How many squares down. */
    var rows: Int by mutableStateOf(rows)

    private val contents = mutableStateListOf<InventoryItem>()

    /** Everything in the bag, in no particular order. Reading it in a composition recomposes on every change. */
    val items: List<InventoryItem> get() = contents

    init {
        require(columns >= 1 && rows >= 1) { "an inventory is at least one square, not $columns by $rows" }
        items.forEach {
            require(add(it)) { "$it does not fit in a $columns by $rows inventory, or something is already there" }
        }
    }

    /** The pile covering [cell], or null when the square is free. */
    fun itemAt(cell: InventoryCell): InventoryItem? = contents.firstOrNull { it.covers(cell) }

    /** The pile called [id], or null when it is not in here. */
    fun item(id: Any): InventoryItem? = contents.firstOrNull { it.id == id }

    /** Whether this bag is the one holding [item]. Judged by its [InventoryItem.id]. */
    operator fun contains(item: InventoryItem): Boolean = contents.any { it.id == item.id }

    /** How many squares nothing is on. */
    val freeCells: Int get() = columns * rows - contents.sumOf { it.across * it.down }

    /** How many of [kind] there are altogether, however many piles they are in. */
    fun countOf(kind: Any): Int = contents.filter { it.kind == kind }.sumOf { it.count }

    /** Every pile [predicate] says yes to: the filter half of sort and filter. */
    fun matching(predicate: (InventoryItem) -> Boolean): List<InventoryItem> = contents.filter(predicate)

    /** Whether [item] would be wholly inside the bag with its corner at [at]. */
    fun inside(item: InventoryItem, at: InventoryCell): Boolean =
        at.x >= 0 && at.y >= 0 && at.x + item.across <= columns && at.y + item.down <= rows

    /** Every other pile [item] would land on with its corner at [at]. Never itself. */
    fun under(item: InventoryItem, at: InventoryCell): List<InventoryItem> {
        val placed = item.copy(at = at)
        return contents.filter { it.id != item.id && it.overlaps(placed) }
    }

    /**
     * Whether [item] would drop into empty squares at [at]: inside the bag, and nothing in the way.
     *
     * The strict question. [canPlace] is the one a drop asks, because a drop onto a pile of the
     * same arrows, or onto a single item that can come out the other way, is also legal.
     */
    fun fits(item: InventoryItem, at: InventoryCell): Boolean = inside(item, at) && under(item, at).isEmpty()

    /**
     * Whether a drop of [item] at [at] would do anything at all.
     *
     * Three ways it can: onto free squares, onto one pile of the same thing with room in it, or —
     * for a pile already in this bag — onto one other item that fits back where this one came from,
     * which is a swap. A pile of the same thing that is already full is no merge, so it is one of
     * those others and swaps. Anything else is two piles at once, or something hanging over the edge.
     */
    fun canPlace(item: InventoryItem, at: InventoryCell): Boolean {
        if (!inside(item, at)) return false
        val under = under(item, at)
        if (under.isEmpty()) return true
        val other = under.singleOrNull() ?: return false
        if (other.stacksWith(item) && other.room > 0) return true
        return contains(item) && swapFits(item, other, at)
    }

    /** The first square [item] fits in, reading along the rows, or null when it does not fit anywhere. */
    fun firstFree(item: InventoryItem): InventoryCell? {
        for (y in 0..rows - item.down) {
            for (x in 0..columns - item.across) {
                val cell = InventoryCell(x, y)
                if (fits(item, cell)) return cell
            }
        }
        return null
    }

    /** Puts [item] in at its own [InventoryItem.at]. False and nothing happens when it does not fit. */
    fun add(item: InventoryItem): Boolean {
        if (!fits(item, item.at)) return false
        contents += item
        return true
    }

    /**
     * Loot: merges [item] into piles of the same thing, then puts whatever is left in the first
     * square it fits in.
     *
     * @return what would not go in — a full bag, or piles all at their limit — or null when all of
     *   it did.
     */
    fun addAnywhere(item: InventoryItem): InventoryItem? {
        var left = item.count
        if (item.isStackable) {
            for (index in contents.indices) {
                if (left == 0) break
                val pile = contents[index]
                if (!pile.stacksWith(item) || pile.room == 0) continue
                val moved = minOf(pile.room, left)
                contents[index] = pile.copy(count = pile.count + moved)
                left -= moved
            }
        }
        while (left > 0) {
            val pile = item.copy(count = minOf(left, item.stackLimit))
            val cell = firstFree(pile) ?: return item.copy(count = left)
            contents += pile.copy(at = cell, id = if (left == item.count) item.id else newId())
            left -= pile.count
        }
        return null
    }

    /** Takes the pile called [item] out altogether. False when it was not in here. */
    fun remove(item: InventoryItem): Boolean = contents.removeAll { it.id == item.id }

    /**
     * Takes [count] out of the pile called [item], as a hand does when it picks some of it up.
     *
     * The pile shrinks, or goes altogether when the whole of it is taken.
     *
     * @return what was taken, with its own [InventoryItem.id] when it is part of a pile and the
     *   pile's own when it is all of it, or null when there is no such pile here.
     */
    fun take(item: InventoryItem, count: Int = item.count): InventoryItem? {
        val index = contents.indexOfFirst { it.id == item.id }
        if (index < 0) return null
        val pile = contents[index]
        val taken = count.coerceIn(1, pile.count)
        if (taken == pile.count) {
            contents.removeAt(index)
            return pile
        }
        contents[index] = pile.copy(count = pile.count - taken)
        return pile.copy(id = newId(), count = taken)
    }

    /**
     * Moves a pile that is already in this bag to [at]: the whole rulebook of a drop inside one grid.
     *
     * Onto free squares it moves. Onto a pile of the same thing it merges, and anything over that
     * pile's limit stays where it was. Onto one other item it swaps, if that item fits back in the
     * squares this one is leaving. The rotation of the [item] handed in is the one it lands in, so
     * a drop that turns it is one call.
     *
     * A full pile of the same thing has no room to merge into, so that drop is a swap like any
     * other: two full quivers of arrows change places rather than doing nothing. [canPlace] says
     * the same, which is what the footprint under the hand is drawn from.
     *
     * @return whether anything happened.
     */
    fun move(item: InventoryItem, at: InventoryCell): Boolean {
        val here = item(item.id) ?: return false
        val moving = here.copy(at = at, rotated = item.rotated)
        if (!inside(moving, at)) return false
        val under = under(moving, at)
        if (under.isEmpty()) {
            if (here.at == at && here.rotated == moving.rotated) return false
            replace(here, moving)
            return true
        }
        val other = under.singleOrNull() ?: return false
        val merged = if (other.stacksWith(moving)) minOf(other.room, moving.count) else 0
        if (merged > 0) {
            replace(other, other.copy(count = other.count + merged))
            if (merged == here.count) contents.removeAll { it.id == here.id }
            else replace(here, here.copy(count = here.count - merged))
            return true
        }
        if (!swapFits(moving, other, at)) return false
        replace(other, other.copy(at = here.at))
        replace(here, moving)
        return true
    }

    /**
     * Puts a pile from somewhere else into this bag at [at]: a drop from another grid, or half a
     * pile split off this one.
     *
     * Free squares take it, and a pile of the same thing merges with it. Nothing swaps: the item
     * that would come out has nowhere to go, because where this one came from is not this bag's to
     * fill.
     *
     * @return what would not go in, for the grid it came from to keep, or null when all of it did.
     */
    fun accept(item: InventoryItem, at: InventoryCell): InventoryItem? {
        val arriving = item.copy(at = at)
        if (!inside(arriving, at)) return item
        val under = under(arriving, at)
        if (under.isEmpty()) {
            contents += arriving
            return null
        }
        val other = under.singleOrNull() ?: return item
        if (!other.stacksWith(arriving)) return item
        val moved = minOf(other.room, arriving.count)
        if (moved == 0) return item
        replace(other, other.copy(count = other.count + moved))
        return if (moved == arriving.count) null else arriving.copy(count = arriving.count - moved)
    }

    /** Turns a pile on its side where it stands. False when it is square, or when the turn would not fit. */
    fun rotate(item: InventoryItem): Boolean {
        val here = item(item.id) ?: return false
        if (!here.canTurn) return false
        val turned = here.turned()
        if (!inside(turned, here.at) || under(turned, here.at).isNotEmpty()) return false
        replace(here, turned)
        return true
    }

    /**
     * Splits [count] off the pile called [item] into a pile of its own, in the first square it fits.
     *
     * @return the new pile, or null when there is nothing to split — one of it, all of it, or
     *   nowhere for the other half to go.
     */
    fun split(item: InventoryItem, count: Int): InventoryItem? {
        val here = item(item.id) ?: return null
        if (count < 1 || count >= here.count) return null
        val part = here.copy(id = newId(), count = count)
        val cell = firstFree(part) ?: return null
        replace(here, here.copy(count = here.count - count))
        contents += part.copy(at = cell)
        return part.copy(at = cell)
    }

    /** Half of it, rounded up, which is what a split with a modifier held takes. */
    fun splitHalf(item: InventoryItem): InventoryItem? = split(item, (item.count + 1) / 2)

    /**
     * Tidies the bag: piles of the same thing merged, then everything laid out again from the
     * corner in [order].
     *
     * A pile that would not fit in its own orientation is turned, if turning it is anything. If
     * even that fails — which takes a bag that was only ever full by luck — nothing moves at all
     * and this is false, because half a sort is worse than none.
     */
    fun sort(order: Comparator<InventoryItem> = InventoryOrder.Default): Boolean {
        val before = contents.toList()
        val piles = mergedPiles().sortedWith(order)
        contents.clear()
        for (pile in piles) {
            val cell = firstFree(pile)
            if (cell != null) {
                contents += pile.copy(at = cell)
                continue
            }
            val turned = pile.turned().takeIf { pile.canTurn }
            val turnedCell = turned?.let { firstFree(it) }
            if (turned == null || turnedCell == null) {
                contents.clear()
                contents.addAll(before)
                return false
            }
            contents += turned.copy(at = turnedCell)
        }
        return true
    }

    /** Everything out. */
    fun clear() = contents.clear()

    /** Every pile as one list, with piles of the same thing poured together up to their limit. */
    private fun mergedPiles(): List<InventoryItem> {
        val piles = mutableListOf<InventoryItem>()
        for (item in contents) {
            var left = item.count
            if (item.isStackable) {
                for (index in piles.indices) {
                    if (left == 0) break
                    val pile = piles[index]
                    if (!pile.stacksWith(item) || pile.room == 0) continue
                    val moved = minOf(pile.room, left)
                    piles[index] = pile.copy(count = pile.count + moved)
                    left -= moved
                }
            }
            var first = true
            while (left > 0) {
                val count = minOf(left, item.stackLimit)
                piles += item.copy(count = count, id = if (first) item.id else newId())
                left -= count
                first = false
            }
        }
        return piles
    }

    /** Whether [other] would fit in the squares [moving] is leaving behind. */
    private fun swapFits(moving: InventoryItem, other: InventoryItem, at: InventoryCell): Boolean {
        val from = item(moving.id) ?: return false
        val landing = moving.copy(at = at)
        val swapped = other.copy(at = from.at)
        if (!inside(swapped, from.at)) return false
        // The two being swapped are both lifted first: each may land on squares the other is on now.
        return contents.none { it.id != from.id && it.id != other.id && it.overlaps(swapped) } &&
            !landing.overlaps(swapped)
    }

    private fun replace(old: InventoryItem, new: InventoryItem) {
        val index = contents.indexOfFirst { it.id == old.id }
        if (index >= 0) contents[index] = new
    }
}

/** Orders a [InventoryState.sort] can lay a bag out in. A game writes its own with `compareBy`. */
object InventoryOrder {

    /** Big things first, so the awkward shapes get the room while there is still room. */
    val BiggestFirst: Comparator<InventoryItem> = compareByDescending { it.across * it.down }

    /** Everything of one kind together, named the way it prints. */
    val ByKind: Comparator<InventoryItem> = compareBy { it.kind.toString() }

    /** Biggest first, then by kind, then the fullest pile of that kind first. */
    val Default: Comparator<InventoryItem> =
        compareByDescending<InventoryItem> { it.across * it.down }
            .thenBy { it.kind.toString() }
            .thenByDescending { it.count }
}
