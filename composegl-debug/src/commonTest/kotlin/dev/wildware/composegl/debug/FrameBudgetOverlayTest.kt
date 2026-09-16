package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.isDebugOverlay
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frame budget overlay: the budget's numbers and its busiest nodes, on a real screen a test
 * clicks and presses buttons at. What the budget counts is `composegl-ui`'s, tested there.
 */
class FrameBudgetOverlayTest {

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

    /** A button that ticks, a box handed a new lambda each tick, a box handed the same one, and the overlay. */
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
            FrameBudgetOverlay(budget, Modifier.testTag("budget"))
        }
    }

    @Test
    fun `the node a click keeps changing is listed in the overlay with its count`() {
        val budget = budget()
        val game = open(budget) { Screen(budget) }
        game.frame()

        repeat(6) {
            game.ui.click("tick")
            game.frame()
        }
        game.ui.settle()

        val shown = game.ui.texts("budget")
        assertTrue("frame" in shown && "redraws" in shown, "the numbers are up: $shown")
        assertTrue("busiest" in shown, "the overlay has the heading: $shown")
        val at = shown.indexOf("  #inline")
        assertTrue(at >= 0, "and the node: $shown")
        assertEquals("6", shown[at + 1], "with its count beside it")
        assertTrue("  #remembered" !in shown, "the box handed the same lambda never changed: $shown")
    }

    @Test
    fun `pad presses count the same as clicks`() {
        val budget = budget()
        val game = open(budget) { Screen(budget) }
        game.ui.assertFocused("tick")

        repeat(3) {
            game.ui.pad(GamepadButton.South)
            game.frame()
        }
        game.ui.settle()

        val shown = game.ui.texts("budget")
        assertEquals("3", shown[shown.indexOf("  #inline") + 1], "listed after three presses: $shown")
    }

    @Test
    fun `a still screen lists nothing and the overlay's own numbers are left out`() {
        val budget = budget()
        val game = open(budget) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(40f, 40f).drawBehind(remembered).testTag("still"))
                FrameBudgetOverlay(budget, Modifier.testTag("budget"))
            }
        }

        repeat(30) {
            game.frame()
            game.ui.settle()
        }

        val underBudget = mutableListOf<UiNode>()
        game.ui.node("budget").forEach { underBudget += it }
        assertTrue(underBudget.any { it.changes > 0 }, "the overlay's numbers did change and were counted")
        assertTrue(isDebugOverlay(game.ui.node("budget")), "the overlay's box is known for debug tooling")
        assertEquals(emptyList(), budget.reading.busiest, "but are not what anybody is looking for")
        assertTrue("busiest" !in game.ui.texts("budget"), "so the overlay lists nothing")
    }

    /**
     * The redraw count is two numbers with a slash between them, and that is the one shape the
     * Unicode bidirectional algorithm turns round: on a screen that reads from the right, the
     * spaces round the slash used to be neutral characters that took the screen's direction and
     * put the two numbers back to front — "12 / 60" read as "60 / 12". Without the spaces the
     * slash is joined onto the digits and the whole thing is one number, which cannot move.
     *
     * The same fix the objective tracker's counter got; `RtlNumbersUiTest` in composegl-game is
     * the sweep of everything else a widget writes for itself.
     */
    @Test
    fun `the redraw count reads the same way round on a screen that reads from the right`() {
        val budget = budget()
        val game = open(budget) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(40f, 40f).drawBehind(remembered).testTag("still"))
                    FrameBudgetOverlay(budget, Modifier.testTag("budget"))
                }
            }
        }

        repeat(6) {
            game.frame()
            game.ui.settle()
        }

        val shown = game.ui.texts("budget")
        val at = shown.indexOf("redraws")
        assertTrue(at >= 0, "the overlay draws the count: $shown")
        val count = shown[at + 1]
        assertTrue(
            Regex("""\d+/\d+""").matches(count),
            "the count is handed over as one number rather than two put back to front: $count",
        )
    }
}
