package dev.wildware.composegl.game

import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic behind a skill tree, with no screen involved: what each node's state is, and which
 * node a direction leaves it by.
 *
 * Worth its own test because both answers are wrong in ways a screenshot does not show. A node
 * whose second prerequisite is unbought must not quietly open, and a direction from a node with two
 * children below it must pick the one more nearly underneath rather than whichever edge was listed
 * first.
 */
class SkillGraphTest {

    /**
     * The tree the tests use, with `rank` left at zero unless a test says otherwise:
     *
     * ```
     *   power ──▶ strike ──▶ combo
     *     │          │
     *     ▼          ▼
     *   guard ────▶ fury
     * ```
     */
    private fun nodes(
        power: Int = 0,
        strike: Int = 0,
        guard: Int = 0,
        fury: Int = 0,
        furyEnabled: Boolean = true,
    ) = listOf(
        SkillNode("power", 0f, 0f, rank = power),
        SkillNode("strike", 120f, 0f, rank = strike),
        SkillNode("combo", 240f, 0f, rank = 0),
        SkillNode("guard", 0f, 120f, rank = guard),
        SkillNode("fury", 120f, 120f, ranks = 3, rank = fury, enabled = furyEnabled),
    )

    private val edges = listOf(
        SkillEdge("power", "strike"),
        SkillEdge("strike", "combo"),
        SkillEdge("power", "guard"),
        SkillEdge("strike", "fury"),
        SkillEdge("guard", "fury"),
    )

    private fun states(
        power: Int = 0,
        strike: Int = 0,
        guard: Int = 0,
        fury: Int = 0,
        furyEnabled: Boolean = true,
        unlock: SkillUnlock = SkillUnlock.All,
    ): Map<Any, SkillState> {
        val list = nodes(power, strike, guard, fury, furyEnabled)
        val graph = SkillGraph(list, edges, unlock)
        return list.indices.associate { list[it].id to graph.states[it] }
    }

    // --- what each node is ----------------------------------------------------------------------

    @Test
    fun `a node with no way in starts open and everything behind it is locked`() {
        val states = states()

        assertEquals(SkillState.Available, states["power"], "a root is where the first point goes")
        assertEquals(SkillState.Locked, states["strike"])
        assertEquals(SkillState.Locked, states["combo"])
        assertEquals(SkillState.Locked, states["fury"])
    }

    @Test
    fun `buying a node opens the ones it leads to and no further`() {
        val states = states(power = 1)

        assertEquals(SkillState.Maxed, states["power"], "one of one rank is finished")
        assertEquals(SkillState.Available, states["strike"])
        assertEquals(SkillState.Available, states["guard"])
        assertEquals(SkillState.Locked, states["combo"], "two steps away is still two steps away")
    }

    @Test
    fun `a node with two ways in waits for both of them`() {
        val both = states(power = 1, strike = 1)

        assertEquals(SkillState.Locked, both["fury"], "guard is not bought yet")
        assertEquals(SkillState.Available, states(power = 1, strike = 1, guard = 1)["fury"])
    }

    @Test
    fun `and opens on either of them when the tree says so`() {
        val states = states(power = 1, strike = 1, unlock = SkillUnlock.Any)

        assertEquals(SkillState.Available, states["fury"], "one way in is enough on a talent grid")
    }

    @Test
    fun `a node partway through its ranks is owned and the last rank maxes it`() {
        assertEquals(SkillState.Owned, states(fury = 1)["fury"], "one of three is bought and buyable")
        assertEquals(SkillState.Owned, states(fury = 2)["fury"])
        assertEquals(SkillState.Maxed, states(fury = 3)["fury"])
    }

    @Test
    fun `a node the game has switched off never opens but keeps what was bought`() {
        val shut = states(power = 1, strike = 1, guard = 1, furyEnabled = false)
        assertEquals(SkillState.Locked, shut["fury"], "not enough points is still not enough points")

        val part = states(power = 1, strike = 1, guard = 1, fury = 1, furyEnabled = false)
        assertEquals(SkillState.Owned, part["fury"], "switching it off must not take a rank back")
    }

    @Test
    fun `an edge naming a node that is not in the list is ignored`() {
        val list = listOf(SkillNode("power", 0f, 0f), SkillNode("strike", 120f, 0f))
        val graph = SkillGraph(list, edges, SkillUnlock.All)

        assertEquals(1, graph.links.size / 2, "only power to strike has both ends here")
        assertEquals(SkillState.Available, graph.states[0])
        assertEquals(SkillState.Locked, graph.states[1])
    }

    // --- which way the pad goes -------------------------------------------------------------------

    private fun graph() = SkillGraph(nodes(), edges, SkillUnlock.All)

    private fun SkillGraph.stepFrom(id: Any, direction: Int): Any? {
        val from = nodes.indexOfFirst { it.id == id }
        val to = neighbour(from, direction)
        return if (to < 0) null else nodes[to].id
    }

    @Test
    fun `a direction follows the line that leaves the node most nearly that way`() {
        val graph = graph()

        assertEquals("strike", graph.stepFrom("power", NodeRight), "right along power to strike")
        assertEquals("guard", graph.stepFrom("power", NodeDown), "down along power to guard")
        assertEquals("combo", graph.stepFrom("strike", NodeRight))
        assertEquals("fury", graph.stepFrom("strike", NodeDown))
    }

    @Test
    fun `and it walks back up the same line`() {
        val graph = graph()

        assertEquals("power", graph.stepFrom("strike", NodeLeft), "left is where it came from")
        assertEquals("power", graph.stepFrom("guard", NodeUp))
        assertEquals("strike", graph.stepFrom("fury", NodeUp))
    }

    @Test
    fun `a direction with no line that way says so and leaves it to the toolkit`() {
        val graph = graph()

        assertEquals(null, graph.stepFrom("power", NodeUp), "nothing above the root")
        assertEquals(null, graph.stepFrom("combo", NodeDown), "combo leads nowhere downwards")
    }

    @Test
    fun `where two lines leave a node the same way the straighter one wins`() {
        val list = listOf(
            SkillNode("top", 0f, 0f),
            SkillNode("under", 10f, 200f),
            SkillNode("aside", 120f, 130f),
        )
        val graph = SkillGraph(
            list,
            listOf(SkillEdge("top", "under"), SkillEdge("top", "aside")),
            SkillUnlock.All,
        )

        // "aside" is the nearer of the two as the crow flies, and the wrong answer: down means down.
        assertEquals(1, list.indexOfFirst { it.id == "under" })
        assertEquals(1, graph.neighbour(0, NodeDown), "down should go to the node underneath")
    }

    @Test
    fun `two nodes in the same place are no direction from each other`() {
        val list = listOf(SkillNode("one", 40f, 40f), SkillNode("two", 40f, 40f))
        val graph = SkillGraph(list, listOf(SkillEdge("one", "two")), SkillUnlock.All)

        assertEquals(-1, graph.neighbour(0, NodeUp))
        assertEquals(-1, graph.neighbour(0, NodeRight))
    }

    // --- the box round it -------------------------------------------------------------------------

    @Test
    fun `the bounds hold every node with a margin round the outermost`() {
        assertEquals(Rect(-20f, -20f, 260f, 140f), skillTreeBounds(nodes(), margin = 20f))
    }

    @Test
    fun `and an empty tree still gives the camera something to look at`() {
        assertEquals(Rect(-50f, -50f, 50f, 50f), skillTreeBounds(emptyList(), margin = 50f))
    }
}
