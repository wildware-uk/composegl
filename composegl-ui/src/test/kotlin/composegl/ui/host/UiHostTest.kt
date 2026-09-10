package composegl.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.layout.Constraints
import composegl.ui.layout.Layout
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePass
import composegl.ui.layout.MeasurePolicy
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    /** Frames until nothing more changes, so a test can start from a settled tree. */
    private fun settle(): Int {
        var frames = 0
        while (frame()) frames++
        return frames
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
        settle()

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
        settle()

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
        settle()
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
        settle()
        val nodes = host.root.children.toList()

        names = listOf("c", "a", "b")
        assertTrue(frame())

        assertEquals(listOf("c", "a", "b"), host.root.childNames())
        // The same three node objects, moved — not three new ones.
        assertEquals(nodes.toSet(), host.root.children.toSet())
    }

    @Test
    fun `a game can compose its own layout and measure it`() {
        val row = MeasurePolicy { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.loosen()) }
            layout(placeables.sumOf { it.width.toDouble() }.toFloat(), placeables.maxOf { it.height }) {
                var x = 0f
                placeables.forEach {
                    it.at(x, 0f)
                    x += it.width
                }
            }
        }
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
        settle()

        MeasurePass().run(host.root, Constraints.atMost(100f, 100f))

        val rowNode = host.root.children.single()
        assertEquals(30f, rowNode.width)
        assertEquals(9f, rowNode.height)
        assertEquals(10f, rowNode.children[1].x)
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
