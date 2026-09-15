package dev.wildware.composegl.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The bookkeeping behind a sectioned lazy list: where each run starts, its keys, and its headers. */
class LazyListSectionsTest {

    private fun sections() = LazyListSections().apply {
        item { }
        stickyHeader(key = "weapons") { }
        items(3, key = { "sword$it" }) { }
        stickyHeader { }
        items(0) { }
        items(2) { }
    }

    @Test
    fun `runs are laid end to end and headers are where they were added`() {
        val list = sections()

        assertEquals(8, list.count)
        assertEquals(listOf(1, 5), list.headers)
    }

    @Test
    fun `a key is asked with the index inside its own run`() {
        val list = sections()

        assertEquals("weapons", list.keyOf(1))
        assertEquals("sword0", list.keyOf(2))
        assertEquals("sword2", list.keyOf(4))
    }

    @Test
    fun `a row with no key is its position and never the same as a key that happens to be a number`() {
        val list = LazyListSections().apply {
            items(2, key = { it + 1 }) { }
            items(2) { }
        }

        // The second row is keyed 2 and the third, unkeyed, is position 2: two rows, two keys.
        assertEquals(2, list.keyOf(1))
        assertNotEquals(list.keyOf(1), list.keyOf(2))
        assertEquals(list.keyOf(3), LazyListSections().apply { items(4) { } }.keyOf(3), "a position is a position")
    }

    @Test
    fun `each index is under the last header at or before it`() {
        val list = sections()

        assertEquals(-1, list.headerFor(0), "the row before any header")
        assertEquals(1, list.headerFor(1), "a header is under itself")
        assertEquals(1, list.headerFor(4))
        assertEquals(5, list.headerFor(5))
        assertEquals(5, list.headerFor(7))
        assertEquals(5, list.headerAfter(1))
        assertEquals(-1, list.headerAfter(5))
    }

    @Test
    fun `headers are found among many`() {
        val list = LazyListSections().apply {
            repeat(1000) {
                stickyHeader { }
                items(2) { }
            }
        }

        assertEquals(3000, list.count)
        assertEquals(true, list.isHeader(2997))
        assertEquals(false, list.isHeader(2998))
        assertEquals(1500, list.headerFor(1502))
        assertEquals(1503, list.headerAfter(1500))
        assertEquals(-1, list.headerAfter(2997))
        assertEquals(false, LazyListSections().isHeader(0), "no headers, nothing is one")
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<IllegalArgumentException> { LazyListSections().items(-1) { } }
    }
}
