package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pausing and stepping animations on a composed screen, driven by the debug keys and by frames.
 *
 * Each test opens a real screen, starts an animation with a click, presses the keys a developer
 * would, and reads the result off the laid-out tree and the text drawn — where a box is, how faded
 * it is, what a label says.
 */
class ClockDebugUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    /**
     * A slide and a fade behind a GO button, with the debug keys on the screen's root.
     *
     * The root is focusable so that a key pressed before anything else has focus still has a path to
     * the keys; once the button has been clicked, the key walks up from the button to the same place.
     */
    private fun screen(
        spec: AnimationSpec,
        keysFor: Clock? = null,
        slideClock: Clock = Clock.Ui,
    ): UiTest = uiTest {
        val clocks = LocalClocks.current
        val keys = remember(clocks) { ClockDebugKeys(clocks, clock = keysFor) }
        var go by remember { mutableStateOf(false) }
        Column(Modifier.focusable(initial = true).onKeyEvent(keys).testTag("root")) {
            Button("GO", onClick = { go = true }, modifier = Modifier.testTag("go"))
            Slide(if (go) 1f else 0f, spec, slideClock)
            Fade(if (go) 1f else 0f)
        }
    }.also { opened += it }

    @Composable
    private fun Slide(target: Float, spec: AnimationSpec, clock: Clock) {
        val x by animateFloatAsState(target, spec, clock)
        Box(Modifier.offset(x = x * 400f).size(20f, 20f).testTag("ball"))
        Text("${(x * 100f).roundToInt()}%", modifier = Modifier.testTag("percent"))
    }

    /** Always on the interface's clock, so a test can tell a world held still from a stuck screen. */
    @Composable
    private fun Fade(target: Float) {
        val alpha by animateFloatAsState(target, Tween(200, easing = Easings.Linear))
        Box(Modifier.alpha(alpha).size(20f, 20f).testTag("fade"))
    }

    private val UiTest.ballX: Float get() = node("ball").boundsInRoot.left

    private val linear = Tween(1_000, easing = Easings.Linear)

    /** One frame of [uiTest], in milliseconds. */
    private val frameMillis = 16.666667f

    @Test
    fun `a paused screen holds an animation still however much time passes`() {
        val ui = screen(linear)

        ui.key(Key.F5)
        ui.click("go")
        ui.advanceBy(2_000)

        assertEquals(0f, ui.ballX, "two seconds went by on a frozen clock")
        ui.assertText("percent", "0%")

        ui.key(Key.F5)
        ui.settle()

        assertEquals(400f, ui.ballX, "and let go, it plays out to the end")
        ui.assertText("percent", "100%")
    }

    @Test
    fun `each press of the step key moves the animation exactly one frame`() {
        val ui = screen(linear)
        ui.key(Key.F5)
        ui.click("go")

        val seen = mutableListOf(ui.ballX)
        repeat(6) {
            ui.key(Key.F6)
            seen += ui.ballX
        }

        // A linear second across 400 pixels is 400/60 of a pixel a frame. Every press moves one of
        // those, never nothing and never two — the starting frame included.
        val perFrame = 400f * frameMillis / 1_000f
        seen.zipWithNext { before, after ->
            assertTrue(abs(after - before - perFrame) < 0.05f, "a step moved ${after - before}px in $seen")
        }
        ui.advanceBy(1_000)
        assertEquals(seen.last(), ui.ballX, "and between presses it stayed put")
    }

    @Test
    fun `stepping a spring shows the overshoot a frame at a time`() {
        // The point of the feature. At full speed this spring flies past its target and back in a
        // handful of frames; stepped, each of those frames can be read.
        val ui = screen(Spring(damping = 0.3f, stiffness = Spring.Medium))
        ui.key(Key.F5)
        ui.click("go")

        val path = mutableListOf<Float>()
        repeat(60) {
            ui.key(Key.F6)
            path += ui.ballX
        }

        val furthest = path.max()
        assertTrue(furthest > 440f, "a bouncy spring should overshoot 400 by a long way; it reached $furthest")
        assertTrue(path.count { it > 400f } >= 3, "the overshoot should be readable over several frames: $path")
        val bouncing = path.take(12)
        assertTrue(bouncing.zipWithNext().all { (a, b) -> a != b }, "every press moved it while it was bouncing: $bouncing")
    }

    @Test
    fun `a quarter speed makes an animation take four times as long on screen`() {
        val normal = screen(Tween(200, easing = Easings.Linear))
        val start = normal.nanos
        normal.click("go")
        val fullSpeed = normal.nanos - start

        val slowed = screen(Tween(200, easing = Easings.Linear))
        slowed.key(Key.F7)
        slowed.key(Key.F7)
        assertEquals(0.25f, slowed.host.clocks.debug.speed)
        val slowStart = slowed.nanos
        slowed.click("go")
        val quarterSpeed = slowed.nanos - slowStart

        assertEquals(400f, slowed.ballX, "it still arrives")
        // A click spends the same few settling frames either way, so compare the difference: a
        // 200ms tween at a quarter speed plays for 800ms, which is 600ms more.
        val extraMillis = (quarterSpeed - fullSpeed) / 1_000_000L
        assertTrue(extraMillis in 550L..650L, "full speed took $fullSpeed ns, a quarter took $quarterSpeed ns")
    }

    @Test
    fun `half way through a slowed animation it has gone half as far`() {
        val ui = screen(linear)
        ui.key(Key.F7)
        ui.key(Key.F5)
        ui.click("go")

        // Sixty steps is a second of frames; at half speed that is half a second of the animation.
        repeat(60) { ui.key(Key.F6) }

        assertTrue(abs(ui.ballX - 200f) < 8f, "a second of half-speed frames should be half way; it was ${ui.ballX}")
        ui.assertText("percent", "${(ui.ballX / 4f).roundToInt()}%")
    }

    @Test
    fun `the world can be frozen while the interface keeps animating`() {
        val ui = screen(linear, keysFor = Clock.World, slideClock = Clock.World)

        ui.key(Key.F5)
        ui.click("go")

        assertEquals(1f, ui.node("fade").resolved.alpha, "the interface's fade finished")
        assertEquals(0f, ui.ballX, "while the world's slide stayed frozen")

        ui.key(Key.F6)
        assertTrue(ui.ballX > 0f && ui.ballX < 10f, "and a step moved only the world, by a frame: ${ui.ballX}")
    }

    @Test
    fun `the step key on a moving animation stops it one frame on`() {
        // A test only ever looks at a settled screen, so the slide is started on a world the game
        // has stopped and the game lets it go without a frame passing. The step key is then the
        // first thing to reach a moving animation: it must freeze it there rather than let it run.
        val ui = screen(linear, slideClock = Clock.World)
        ui.host.clocks.stop(Clock.World)
        ui.click("go")
        ui.host.clocks.start(Clock.World)

        ui.key(Key.F6)

        assertTrue(ui.ballX > 0f && ui.ballX < 10f, "one frame of a second-long slide: ${ui.ballX}")
        ui.advanceBy(1_000)
        assertTrue(ui.ballX < 10f, "and it stayed frozen there: ${ui.ballX}")
    }

    @Test
    fun `the faster key brings a slowed screen back to real time`() {
        val normal = screen(Tween(200, easing = Easings.Linear))
        val start = normal.nanos
        normal.click("go")
        val fullSpeed = normal.nanos - start

        val ui = screen(Tween(200, easing = Easings.Linear))
        ui.key(Key.F7)
        ui.key(Key.F7)
        ui.key(Key.F8)
        ui.key(Key.F8)
        assertEquals(1f, ui.host.clocks.debug.speed)

        val uiStart = ui.nanos
        ui.click("go")
        val took = ui.nanos - uiStart

        assertEquals(400f, ui.ballX)
        // The same as a screen never slowed, within a frame or so; at a quarter it would be 600ms more.
        val extraMillis = (took - fullSpeed) / 1_000_000L
        assertTrue(extraMillis in -20L..20L, "never slowed took $fullSpeed ns, sped back up took $took ns")
    }

    @Test
    fun `stepping everything leaves the interface animating once it was let go`() {
        val ui = screen(linear, slideClock = Clock.World)
        val debug = ui.host.clocks.debug
        debug.pause()
        debug.resume(Clock.Ui)

        ui.click("go")
        ui.key(Key.F6)

        assertTrue(ui.ballX > 0f && ui.ballX < 10f, "the world moved one frame: ${ui.ballX}")
        assertEquals(1f, ui.node("fade").resolved.alpha, "and the interface's fade, let go, played out")
        assertTrue(!debug.isPaused(Clock.Ui), "the step did not freeze the interface again")
    }

    @Test
    fun `stopping the world is the game's switch and the debug pause survives it`() {
        val ui = screen(linear, slideClock = Clock.World)
        val clocks = ui.host.clocks

        clocks.stop(Clock.World)
        ui.key(Key.F5)
        clocks.start(Clock.World)
        ui.click("go")
        ui.advanceBy(500)

        assertEquals(0f, ui.ballX, "the game unpausing does not un-freeze what a developer froze")
    }
}
