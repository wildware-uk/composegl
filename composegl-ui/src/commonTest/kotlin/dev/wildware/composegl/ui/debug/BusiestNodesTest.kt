package dev.wildware.composegl.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame budget's busiest nodes: the ones the most frames changed, named in its reading, on a real
 * screen a test clicks and types at. The overlay that lists them is `composegl-debug`'s, tested there.
 */
class BusiestNodesTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    /** Publishes every frame, so a test does not wait a quarter of a second for the numbers. */
    private fun budget(busiest: Int = 5) = FrameBudget(window = 4, publishEveryMillis = 0L, busiest = busiest)

    private class Game(val ui: UiTest, val renderer: UiRenderer)

    private fun open(budget: FrameBudget, content: @Composable () -> Unit): Game {
        val ui = uiTest(Size(500f, 400f), content = content).also { opened += it }
        return Game(ui, UiRenderer(ui.host, RecordingCanvas(), budget))
    }

    /** One frame through the renderer the budget is filled in by, on the harness's own clock. */
    private fun Game.frame() = renderer.render(ui.viewport, ui.nanos)

    private val remembered: UiCanvas.(Rect) -> Unit = { rect(it, Colour.Green) }

    /** A button that ticks, a box handed a new lambda each tick, a box handed the same one, and the budget's numbers. */
    @Composable
    private fun Screen(budget: FrameBudget) {
        var tick by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxSize()) {
            Column {
                Button("tick", onClick = { tick++ }, modifier = Modifier.testTag("tick"), initialFocus = true)
                val seen = tick
                Box(Modifier.size(40f, 40f).drawBehind { if (seen >= 0) rect(it, Colour.Red) }.testTag("inline"))
                Box(Modifier.size(40f, 40f).drawBehind(remembered).testTag("remembered"))
            }
            BudgetNumbers(budget)
        }
    }

    /**
     * What the frame budget overlay in `composegl-debug` is to the tree: a box named
     * [FrameBudget.OverlayName] whose numbers change whenever the budget publishes.
     */
    @Composable
    private fun BudgetNumbers(budget: FrameBudget) {
        Layout(
            Modifier.testTag("budget"),
            name = FrameBudget.OverlayName,
            content = { Text("${budget.reading.frames} frames") },
            measurePolicy = MeasurePolicy.Stack,
        )
    }

    @Test
    fun `the node a click keeps changing is listed with its count`() {
        val budget = budget()
        val game = open(budget) { Screen(budget) }
        game.frame()

        repeat(6) {
            game.ui.click("tick")
            game.frame()
        }
        game.ui.settle()

        val busiest = budget.reading.busiest
        val inline = busiest.single { it.tag == "inline" }
        assertEquals(6, inline.changes)
        assertEquals("#inline", inline.label)
        assertTrue(busiest.none { it.tag == "remembered" }, "the box handed the same lambda never changed: $busiest")
        assertEquals(busiest.sortedByDescending { it.changes }, busiest, "most first")
    }

    @Test
    fun `keys count the same as clicks`() {
        val budget = budget()
        val game = open(budget) { Screen(budget) }
        game.ui.assertFocused("tick")

        repeat(3) {
            game.ui.key(Key.Enter)
            game.frame()
        }

        assertEquals(3, budget.reading.busiest.single { it.tag == "inline" }.changes)
    }

    @Test
    fun `no more than asked for are listed`() {
        val budget = budget(busiest = 1)
        val game = open(budget) {
            var tick by remember { mutableStateOf(0) }
            Column {
                Button("tick", onClick = { tick++ }, modifier = Modifier.testTag("tick"))
                val seen = tick
                Box(Modifier.size(40f, 40f).drawBehind { if (seen >= 0) rect(it, Colour.Red) }.testTag("one"))
                Box(Modifier.size(40f, 40f).drawBehind { if (seen >= 0) rect(it, Colour.Blue) }.testTag("two"))
            }
        }
        repeat(2) {
            game.ui.click("tick")
            game.frame()
        }

        assertEquals(1, budget.reading.busiest.size)
    }

    @Test
    fun `a still screen lists nothing and the budget's own numbers are left out`() {
        val budget = budget()
        val game = open(budget) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(40f, 40f).drawBehind(remembered).testTag("still"))
                BudgetNumbers(budget)
            }
        }

        repeat(30) {
            game.frame()
            game.ui.settle()
        }

        val underBudget = mutableListOf<UiNode>()
        game.ui.node("budget").forEach { underBudget += it }
        assertTrue(underBudget.any { it.changes > 0 }, "the budget's numbers did change and were counted")
        assertEquals(emptyList(), budget.reading.busiest, "but are not what anybody is looking for")
    }

    @Test
    fun `nothing is counted until the budget is asked and switching it off stops the counting`() {
        val budget = budget(busiest = 0)
        val game = open(budget) { Screen(budget) }
        game.ui.click("tick")
        game.frame()

        assertFalse(game.ui.host.tree.counting)
        assertEquals(0, game.ui.node("inline").changes)
        assertTrue(budget.reading.busiest.isEmpty())

        budget.busiest = 3
        game.ui.click("tick")
        game.frame()
        assertEquals(1, game.ui.node("inline").changes, "counted from the moment it was asked")

        budget.isOn = false
        assertFalse(game.ui.host.tree.counting, "a budget switched off counts nothing")
        game.ui.click("tick")
        assertEquals(1, game.ui.node("inline").changes)

        budget.isOn = true
        game.ui.click("tick")
        game.frame()
        assertEquals(2, game.ui.node("inline").changes, "and back on, carries on")
    }

    @Test
    fun `reset starts the counts again`() {
        val budget = budget()
        val game = open(budget) { Screen(budget) }
        repeat(3) { game.ui.click("tick") }
        assertEquals(3, game.ui.node("inline").changes)

        budget.reset()
        assertEquals(0, game.ui.node("inline").changes)
        game.ui.click("tick")
        game.frame()
        assertEquals(1, budget.reading.busiest.single { it.tag == "inline" }.changes)
    }
}
