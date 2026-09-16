package dev.wildware.composegl.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onReveal
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The frame contract.
 *
 * These run in milliseconds with no window, no OpenGL context and no native library, which is the
 * point: the runtime half of a UI toolkit does not need a GPU to be tested.
 */
class UiHostTest {

    private val host = UiHost()
    private var now = 0L

    private fun frame(): Boolean {
        now += 16_666_667L
        return host.frame(now)
    }

    /**
     * Frames until nothing more changes, so a test can start from a settled tree.
     *
     * Not [settle]: this pumps the runtime and never lays anything out, which is the difference
     * the tests below are about.
     */
    private fun frames(): Int {
        var count = 0
        while (frame()) count++
        return count
    }

    @AfterEach
    fun tearDown() = host.dispose()

    @Test
    fun `a static composition changes once and then never again`() {
        host.setContent { LeafLayout(Modifier.size(10f), name = "box") }

        assertTrue(frame(), "the first frame builds the tree")
        repeat(100) { assertFalse(frame(), "nothing has changed, so nothing should be redrawn") }
        assertEquals(1L, host.changedFrames)
    }

    @Test
    fun `a state write changes exactly one frame`() {
        var padding by mutableStateOf(4f)
        host.setContent { LeafLayout(Modifier.padding(padding), name = "box") }
        frames()

        padding = 8f

        assertTrue(frame())
        repeat(10) { assertFalse(frame()) }
    }

    /**
     * The frame pump's drain order, named so that breaking it fails here and not in an animation
     * three milestones later.
     *
     * The recomposer is a coroutine. Waking it on a snapshot notification only queues it, so if the
     * frame is sent before that queued work runs, the recomposer has not yet reached its
     * `withFrameNanos` and misses the frame entirely. The change then lands on the *next* frame —
     * invisible in a menu, and a permanent one-frame lag in anything that moves.
     */
    @Test
    fun `a state write lands on the same frame, not the next one`() {
        var padding by mutableStateOf(4f)
        host.setContent { LeafLayout(Modifier.padding(padding), name = "box") }
        frames()

        padding = 8f
        val changedImmediately = frame()

        assertTrue(changedImmediately, "the change landed a frame late: check the drain order")
        assertEquals(8f, host.root.children.single().resolved.padding.left)
    }

    @Test
    fun `structure follows state`() {
        var showing by mutableStateOf(false)
        host.setContent {
            LeafLayout(name = "always")
            if (showing) LeafLayout(name = "sometimes")
        }
        frames()
        assertEquals(listOf("always"), host.root.childNames())

        showing = true
        assertTrue(frame())
        assertEquals(listOf("always", "sometimes"), host.root.childNames())

        showing = false
        assertTrue(frame())
        assertEquals(listOf("always"), host.root.childNames())
    }

    @Test
    fun `a list reorders without rebuilding`() {
        var names by mutableStateOf(listOf("a", "b", "c"))
        host.setContent {
            names.forEach { name -> androidx.compose.runtime.key(name) { LeafLayout(name = name) } }
        }
        frames()
        val nodes = host.root.children.toList()

        names = listOf("c", "a", "b")
        assertTrue(frame())

        assertEquals(listOf("c", "a", "b"), host.root.childNames())
        // The same three node objects, moved — not three new ones.
        assertEquals(nodes.toSet(), host.root.children.toSet())
    }

    /** Children side by side, left to right: the smallest policy that puts one at a known x. */
    private val row = MeasurePolicy { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.loosen()) }
        layout(placeables.sumOf { it.width.toDouble() }.toFloat(), placeables.maxOf { it.height }) {
            var x = 0f
            placeables.forEach {
                it.at(x, 0f)
                x += it.width
            }
        }
    }

    @Test
    fun `a game can compose its own layout and measure it`() {
        host.setContent {
            Layout(
                name = "row",
                content = {
                    LeafLayout(Modifier.size(10f, 4f), name = "a")
                    LeafLayout(Modifier.size(20f, 9f), name = "b")
                },
                measurePolicy = row,
            )
        }
        frames()

        MeasurePass().run(host.root, Constraints.atMost(100f, 100f))

        val rowNode = host.root.children.single()
        assertEquals(30f, rowNode.width)
        assertEquals(9f, rowNode.height)
        assertEquals(10f, rowNode.children[1].x)
    }

    // --- settle -------------------------------------------------------------------------------
    //
    // The three calls a readable tree needs, and the two things that go wrong when a caller does
    // them by hand: the layout left out, and the focus refresh left out.

    private val whole = Constraints.atMost(100f, 100f)

    private fun tick(): Long {
        now += 16_666_667L
        return now
    }

    /**
     * Settles until nothing more changes, and fails rather than spinning.
     *
     * The recipe is a loop — `while (host.settle(...)) { }` — and a plain one is a hang waiting to
     * happen: a settle that wrongly reports a change every time turns a red build into a job that
     * never finishes and gets reaped with nothing printed. So the loop is bounded, and running out
     * is itself the failure. Eight turns is far more than any tree in this file needs.
     *
     * @return how many turns reported a change.
     */
    private fun settled(focus: FocusManager? = null, budget: FrameBudget? = null): Int {
        repeat(8) { turn ->
            if (!host.settle(whole, focus, nanos = tick(), budget = budget)) return turn
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    /** The same bound, against a viewport. */
    private fun settled(viewport: Viewport): Int {
        repeat(8) { turn -> if (!host.settle(viewport, nanos = tick())) return turn }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    @Test
    fun `a frame on its own leaves last frame's rectangles behind`() {
        var wide by mutableStateOf(10f)
        host.setContent { LeafLayout(Modifier.size(wide, 4f), name = "box") }
        frames()
        MeasurePass().run(host.root, whole)

        wide = 40f
        assertTrue(frame(), "the contents changed")

        // Right contents, last frame's size. This is the bug settle exists to stop.
        assertEquals(10f, host.root.children.single().width)
    }

    @Test
    fun `settle lays the tree out as well as recomposing it`() {
        var wide by mutableStateOf(10f)
        host.setContent { LeafLayout(Modifier.size(wide, 4f), name = "box") }
        settled()

        wide = 40f
        assertTrue(host.settle(whole, nanos = tick()))

        assertEquals(40f, host.root.children.single().width)
    }

    @Test
    fun `settle against a viewport puts the root where the viewport says`() {
        host.setContent { LeafLayout(Modifier.size(10f, 4f), name = "box") }
        settled(Viewport(Size(200f, 100f), Size(400f, 200f)))

        // Laid out at the design size, not the framebuffer's: the scale is the backend's business.
        assertEquals(200f, host.root.width)
        assertEquals(100f, host.root.height)
    }

    @Test
    fun `settle moves focus off a node that has gone`() {
        var showing by mutableStateOf(true)
        host.setContent {
            LeafLayout(Modifier.size(10f).focusable(), name = "always")
            if (showing) LeafLayout(Modifier.size(10f).focusable(), name = "sometimes")
        }
        settled()

        val focus = FocusManager(host.root)
        focus.focusOn(host.root.children.single { it.name == "sometimes" })
        assertEquals("sometimes", focus.focused?.name)

        showing = false
        host.settle(whole, focus, nanos = tick())

        // Not merely null: a menu that ends up with nothing selected cannot be walked with a pad.
        assertNotNull(focus.focused, "focus should have landed somewhere real")
        assertEquals("always", focus.focused?.name)
    }

    @Test
    fun `settle refreshes the manager it was handed and no other`() {
        host.setContent { LeafLayout(Modifier.size(10f).focusable(), name = "box") }
        settled()

        val handed = FocusManager(host.root)
        val someoneElses = FocusManager(host.root)
        host.settle(whole, handed, nanos = tick())

        // autoFocus only fires from refresh, so who was refreshed is exactly who has focus now.
        assertEquals("box", handed.focused?.name, "the manager settle was given was not refreshed")
        assertNull(someoneElses.focused, "a second manager on the same tree is nobody else's business")
    }

    /**
     * Focus is refreshed *after* the layout, and this is what notices if it stops being.
     *
     * Taking focus asks every ancestor to reveal the newly focused node, and what a scrolling list
     * is handed there is a rectangle from the tree. Refresh before the layout and that rectangle is
     * whatever the last frame left behind — for a node composed this frame, nothing at all.
     */
    @Test
    fun `settle lays the tree out before it refreshes focus`() {
        var revealed: Rect? = null
        host.setContent {
            Layout(
                modifier = Modifier.onReveal { area ->
                    revealed = area
                    true
                },
                name = "row",
                content = {
                    LeafLayout(Modifier.size(12f, 4f), name = "first")
                    LeafLayout(Modifier.size(10f, 4f).focusable(), name = "second")
                },
                measurePolicy = row,
            )
        }

        host.settle(whole, FocusManager(host.root), nanos = tick())

        assertEquals(Rect.of(12f, 0f, 10f, 4f), revealed, "focus was settled against an unlaid tree")
    }

    /**
     * That the budget is not merely held but filled in.
     *
     * The overlay's whole job is the recompose/layout split, and a settle that quietly ignored the
     * budget handed to it would show two zeroes with nothing to say why.
     */
    @Test
    fun `settle fills in the budget it was handed`() {
        host.setContent { repeat(20) { LeafLayout(Modifier.size(10f), name = "box$it") } }
        // A clock that moves a millisecond every time it is read, so what is asserted is that the
        // passes were wrapped at all rather than how fast this machine happens to be.
        var nanos = 0L
        val budget = FrameBudget(nanoTime = { nanos += 1_000_000; nanos })
        budget.isOn = true

        settled(budget = budget)
        // The numbers are only published when a frame is closed off, which settle does not do.
        budget.endFrame()

        assertTrue(budget.reading.recomposeMillis > 0f, "settle did not time the recompose")
        assertTrue(budget.reading.layoutMillis > 0f, "settle did not time the layout")
    }

    /**
     * The recipe the KDoc gives, and the reason it is a loop.
     *
     * One settle publishes what the previous frame wrote. Anything written while this frame is
     * running waits for the next `Snapshot.sendApplyNotifications`, which is at the top of the
     * frame after it — so a harness that wants a finished tree keeps going until nothing changes.
     * [settled] is that loop with a bound on it, and running out of turns fails the test.
     */
    @Test
    fun `the settle loop ends, and ends settled`() {
        host.setContent { LeafLayout(Modifier.size(10f), name = "box") }

        val turns = settled()

        assertTrue(turns >= 1, "the first settle builds the tree, so something changed")
        assertFalse(host.settle(whole, nanos = tick()), "a settled tree settles again for free")
    }

    @Test
    fun `a disposed host refuses to run frames`() {
        host.setContent { LeafLayout(name = "box") }
        host.dispose()

        assertThrowsIllegalState { host.frame(0L) }
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return
        }
        throw AssertionError("expected an IllegalStateException")
    }
}
