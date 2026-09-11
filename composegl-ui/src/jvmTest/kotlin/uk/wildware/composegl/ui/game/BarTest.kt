package uk.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.width
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.SkinDrawable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The health bar, and the damage trail that is the reason it is a widget.
 *
 * The three cases that go wrong in every hand-rolled version are the three that get tests: two
 * hits in a row must keep draining rather than start again, healing must not leave a ghost behind
 * it, and a bar nobody is hitting must cost nothing.
 */
class BarTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear()
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        frames(2)
    }

    /** The runtime takes a frame to notice a state change and another to act on it. */
    private fun begin() = frames(2, millis = 0)

    private fun settle() = frame(0)

    private fun fill(style: String): Colour =
        (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    private fun widthOf(style: String): Float =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            .lastOrNull { it.colour == fill(style) }
            ?.rect?.width ?: 0f

    // --- the trail ------------------------------------------------------------------------------

    @Test
    fun `the fill moves at once and the trail follows a moment later`() {
        var health by mutableStateOf(1f)
        show { Bar(health, Modifier.width(100f), holdMillis = 100, drainMillis = 200, clock = Clock.Ui) }

        assertEquals(100f, widthOf("bar.fill"), 0.5f)

        health = 0.5f
        begin()

        assertEquals(50f, widthOf("bar.fill"), 0.5f, "the real value does not wait for anything")
        assertEquals(100f, widthOf("bar.trail"), 0.5f, "and the trail has not started yet")

        frame(100)
        frame(100)
        settle()

        assertTrue(widthOf("bar.trail") < 90f, "it should be draining: ${widthOf("bar.trail")}")

        frames(4, 100)
        settle()

        assertEquals(50f, widthOf("bar.trail"), 0.5f, "and it caught up")
    }

    @Test
    fun `a second hit keeps draining rather than starting again`() {
        var health by mutableStateOf(1f)
        show { Bar(health, Modifier.width(100f), holdMillis = 100, drainMillis = 400, clock = Clock.Ui) }

        health = 0.7f
        begin()
        frame(100)
        frames(2, 100)
        settle()
        val partway = widthOf("bar.trail")
        assertTrue(partway < 100f && partway > 70f, "mid-drain, at $partway")

        health = 0.4f
        begin()
        settle()

        assertTrue(
            widthOf("bar.trail") <= partway + 0.5f,
            "the trail jumped back up to ${widthOf("bar.trail")} from $partway",
        )

        // And it keeps going down to the newer value rather than stopping at the older one.
        frames(6, 100)
        settle()
        assertEquals(40f, widthOf("bar.trail"), 0.5f)
    }

    @Test
    fun `healing leaves no trail behind it`() {
        var health by mutableStateOf(0.3f)
        show { Bar(health, Modifier.width(100f), holdMillis = 100, drainMillis = 400, clock = Clock.Ui) }

        health = 0.9f
        begin()
        settle()

        assertEquals(90f, widthOf("bar.fill"), 0.5f)
        assertEquals(90f, widthOf("bar.trail"), 0.5f, "a ghost bar lagging behind a heal reads as damage")
    }

    @Test
    fun `a trail can be turned off`() {
        var health by mutableStateOf(1f)
        show { Bar(health, Modifier.width(100f), trail = false, clock = Clock.Ui) }

        health = 0.5f
        begin()
        settle()

        assertEquals(50f, widthOf("bar.trail"), 0.5f, "with no trail the ghost is exactly the value")
    }

    @Test
    fun `the trail waits on its own clock, so a paused game does not drain it`() {
        var health by mutableStateOf(1f)
        show { Bar(health, Modifier.width(100f), holdMillis = 100, drainMillis = 200) }
        host.clocks.register(Clock.World)

        health = 0.5f
        begin()
        host.clocks.stop(Clock.World)
        frames(30, 16)
        settle()

        assertEquals(100f, widthOf("bar.trail"), 0.5f, "the damage carried on happening while paused")

        host.clocks.start(Clock.World)
        frames(6, 100)
        settle()

        assertEquals(50f, widthOf("bar.trail"), 0.5f)
    }

    // --- reading it at a glance -----------------------------------------------------------------

    @Test
    fun `a threshold changes the colour`() {
        var health by mutableStateOf(1f)
        val thresholds = listOf(
            BarThreshold(0.25f, "bar.fill.critical"),
            BarThreshold(0.5f, "bar.fill.low"),
        )
        show { Bar(health, Modifier.width(100f), thresholds = thresholds, clock = Clock.Ui) }

        assertTrue(widthOf("bar.fill") > 0f, "a full bar is the ordinary colour")

        health = 0.4f
        begin()
        settle()
        assertTrue(widthOf("bar.fill.low") > 0f, "under a half it is the low colour")

        health = 0.2f
        begin()
        settle()
        assertTrue(widthOf("bar.fill.critical") > 0f, "under a quarter it is the critical one")
        assertEquals(0f, widthOf("bar.fill.low"), "and only one colour at a time")
    }

    @Test
    fun `segments are notches across the bar`() {
        show { Bar(0.5f, Modifier.width(100f), segments = 4, clock = Clock.Ui) }

        // A notch is the same colour as the track it is cut out of, so it is found by being thin.
        val notches = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            .filter { it.colour == fill("bar.segment") && it.rect.width < 5f }

        assertEquals(3, notches.size, "four blocks have three gaps between them")
        assertEquals(24f, notches.first().rect.left, 1f, "the first is a quarter of the way along")
    }

    @Test
    fun `a bar at nothing draws no fill at all`() {
        show { Bar(0f, Modifier.width(100f), clock = Clock.Ui) }

        assertEquals(0f, widthOf("bar.fill"))
    }

    @Test
    fun `a value outside nought to one is clamped rather than drawn off the end`() {
        show { Bar(3f, Modifier.width(100f), clock = Clock.Ui) }

        assertEquals(100f, widthOf("bar.fill"), 0.5f)
    }

    // --- what it costs --------------------------------------------------------------------------

    @Test
    fun `a steady bar costs nothing`() {
        var health by mutableStateOf(1f)
        show { Bar(health, Modifier.width(100f), holdMillis = 100, drainMillis = 200, clock = Clock.Ui) }

        health = 0.5f
        frames(20, 100)

        repeat(100) { assertFalse(frame(), "frame $it redrew a bar nobody is hitting") }
    }

    @Test
    fun `a pulsing bar keeps asking for frames and stops when it is healed`() {
        var health by mutableStateOf(0.1f)
        show { Bar(health, Modifier.width(100f), pulseBelow = 0.25f, clock = Clock.Ui) }

        frames(10, 50)
        assertTrue((1..10).any { frame(50) }, "a bar this low should be breathing")

        health = 1f
        frames(20, 50)

        repeat(50) { assertFalse(frame(50), "it kept pulsing after it was healed") }
    }
}
