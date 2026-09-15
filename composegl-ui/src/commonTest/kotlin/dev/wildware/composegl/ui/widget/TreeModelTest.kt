package dev.wildware.composegl.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tree laid out as rows, with no screen: which rows show, how deep, and what the guides beside
 * each need to draw.
 */
class TreeModelTest {

    private class Node(val id: String, vararg val kids: Node)

    private val tree = listOf(
        Node(
            "world",
            Node("player", Node("camera"), Node("weapon")),
            Node("enemies", Node("drone")),
        ),
        Node("sky"),
    )

    private val asked = mutableListOf<String>()

    private fun rows(vararg open: String): TreeRows<Node> = flattenTree(
        roots = tree,
        children = { asked += it.id; it.kids.toList() },
        key = { it.id },
        isExpanded = { it in open },
    )

    private fun TreeRows<Node>.ids() = rows.map { it.key }

    @Test
    fun `with nothing open only the roots show`() {
        assertEquals(listOf("world", "sky"), rows().ids())
    }

    @Test
    fun `an open node's children come straight after it`() {
        assertEquals(
            listOf("world", "player", "camera", "weapon", "enemies", "sky"),
            rows("world", "player").ids(),
        )
    }

    @Test
    fun `a child of a closed node does not show even when it is open itself`() {
        assertEquals(listOf("world", "sky"), rows("player").ids())
    }

    @Test
    fun `only open nodes are asked for their children`() {
        rows("world", "camera")

        assertEquals(listOf("world"), asked, "a closed player and a leaf camera should never have been asked")
    }

    @Test
    fun `depth parent and index are what the arrows need`() {
        val shown = rows("world", "player")
        val camera = shown.rows[shown.index.getValue("camera")]

        assertEquals(2, camera.depth)
        assertEquals("player", shown.rows[camera.parent].key)
        assertEquals(-1, shown.rows[0].parent)
        assertTrue(shown.rows[0].expanded)
        assertTrue(!shown.rows[shown.index.getValue("enemies")].expanded, "a closed row is not open")
    }

    @Test
    fun `a guide carries on past a row only while a sibling is still to come`() {
        val shown = rows("world", "player", "enemies")
        fun lines(id: String) = shown.rows[shown.index.getValue(id)].lines.toList()

        // Player has Enemies after it, so its own line goes on down; Camera's parent line does too.
        assertEquals(listOf(true), lines("player"))
        assertEquals(listOf(true, true), lines("camera"))
        assertEquals(listOf(true, false), lines("weapon"))
        // Enemies is World's last child: its line turns the corner, and so does the one above Drone.
        assertEquals(listOf(false), lines("enemies"))
        assertEquals(listOf(false, false), lines("drone"))
        assertEquals(emptyList(), lines("sky"))
    }

    @Test
    fun `an open node that turns out to have no children is still open and shows nothing under it`() {
        val shown = flattenTree(
            roots = listOf(Node("folder")),
            children = { emptyList() },
            key = { it.id },
            isExpanded = { true },
        )

        assertEquals(listOf("folder"), shown.ids())
        assertTrue(shown.rows[0].expanded)
    }

    @Test
    fun `a tree a thousand levels deep is a long list and not a crash`() {
        var chain = Node("n0")
        for (level in 1 until 1_000) chain = Node("n$level", chain)

        val shown = flattenTree(listOf(chain), { it.kids.toList() }, { it.id }, { true })

        assertEquals(1_000, shown.rows.size)
        assertEquals(999, shown.rows.last().depth)
    }

    @Test
    fun `two nodes with one key are refused by name`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            flattenTree(listOf(Node("a"), Node("a")), { it.kids.toList() }, { it.id }, { false })
        }
        assertTrue("a" in failure.message.orEmpty())
    }
}
