package composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.graphics.Colour
import composegl.ui.host.UiHost
import composegl.ui.layout.Box
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.alpha
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Animation: getting somewhere smoothly, and costing nothing once you are there.
 *
 * The last test is the one that matters most, and it is the property spike S6 measured: an
 * animation that has arrived must stop asking for frames. An interface with a dozen settled
 * animations on it has to redraw exactly as often as an interface with none, which is never.
 */
class AnimationTest {

    private val host = UiHost()

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        return host.frame(wall)
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent(content)
        frames(2)
    }

    /**
     * The two frames an animation takes to get going, so the tests below can talk about the time it
     * has been running rather than about the runtime's plumbing.
     *
     * A state change wakes the recomposer, which asks for a frame before it recomposes, so the
     * effect that starts the animation runs at the end of that frame and its first tick is the next
     * one. Every Compose-based toolkit behaves this way; the animation starts at the right value
     * either way, so nothing a player can see depends on it.
     */
    private fun begin() = frames(2, millis = 0)

    /**
     * No time passes; whatever the last frame worked out reaches the screen.
     *
     * The same one-frame plumbing as [begin], at the other end: an animation writes its new value
     * during a frame, and the composition that reads it runs on the next one.
     */
    private fun settle() = frame(0)

    // --- getting there ------------------------------------------------------------------------------

    @Test
    fun `a value goes to its target rather than jumping to it`() {
        var alpha = -1f
        var target by mutableStateOf(0f)
        show {
            val animated by animateFloatAsState(target, Tween(200, easing = Easings.Linear))
            alpha = animated
            Box(Modifier.alpha(animated))
        }

        assertEquals(0f, alpha)

        target = 1f
        begin()
        frame(100)
        settle()

        assertTrue(alpha > 0.4f && alpha < 0.6f, "half way through a linear tween, and it was at $alpha")

        frame(100)
        frame()
        settle()

        assertEquals(1f, alpha, "it arrived")
    }

    @Test
    fun `a delay holds the value still and then it moves`() {
        var alpha = -1f
        var target by mutableStateOf(0f)
        show {
            val animated by animateFloatAsState(target, Tween(100, delayMillis = 100, easing = Easings.Linear))
            alpha = animated
            Box(Modifier.alpha(animated))
        }

        target = 1f
        begin()
        frame(50)
        settle()
        assertEquals(0f, alpha, "it has not started yet")

        frame(100)
        settle()
        assertTrue(alpha > 0.4f && alpha < 0.6f, "half way, and it was at $alpha")
    }

    @Test
    fun `a colour fades channel by channel`() {
        var colour = Colour.Black
        var target by mutableStateOf(Colour.Black)
        show {
            val animated by animateColourAsState(target, Tween(100, easing = Easings.Linear))
            colour = animated
            Box(Modifier.alpha(if (animated == Colour.Black) 1f else 0.5f))
        }

        target = Colour.White
        begin()
        frame(50)
        settle()

        assertTrue(colour.red in 100..160, "half faded, and red was ${colour.red}")
        assertEquals(colour.red, colour.blue, "the channels move together when they started together")

        frame(100)
        settle()
        assertEquals(Colour.White, colour)
    }

    @Test
    fun `a transparent colour fades out rather than going black on the way`() {
        var colour = Colour.White
        var target by mutableStateOf(Colour.White)
        show {
            val animated by animateColourAsState(target, Tween(100, easing = Easings.Linear))
            colour = animated
            Box(Modifier.alpha(animated.alphaFraction))
        }

        target = Colour.White.withAlpha(0)
        begin()
        frame(50)
        settle()

        assertTrue(colour.alpha in 100..160, "the alpha is what moved: ${colour.alpha}")
        assertEquals(255, colour.red, "and the colour stayed white while it went")
    }

    // --- changing your mind -------------------------------------------------------------------------

    @Test
    fun `retargeting mid-flight turns around rather than jumping`() {
        val seen = mutableListOf<Float>()
        var target by mutableStateOf(0f)
        show {
            val animated by animateFloatAsState(target, Spring(stiffness = Spring.Low))
            seen += animated
            Box(Modifier.alpha(animated.coerceIn(0f, 1f)))
        }

        target = 1f
        frames(10)
        val turningPoint = seen.last()
        assertTrue(turningPoint > 0.05f, "it should be under way, and it was at $turningPoint")

        target = 0f
        frames(20)

        // Continuity is the point: no frame moved the value further than a frame's worth of the
        // speed it had. A jump back to where the animation "should" have started would show up as
        // one enormous step.
        val biggestStep = seen.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(biggestStep < 0.2f, "something jumped by $biggestStep")
        assertTrue(seen.last() < turningPoint, "and it did turn around")
    }

    @Test
    fun `a cancelled animation stops where it was`() {
        val animatable = Animatable(0f, FloatVectoriser, Clock.Ui, host.clocks)
        var running by mutableStateOf(true)
        show {
            Box(Modifier.alpha(animatable.value))
            if (running) {
                LaunchedEffect(Unit) { animatable.animateTo(1f, Tween(1000, easing = Easings.Linear)) }
            }
        }

        frames(10)
        val stopped = animatable.value
        assertTrue(stopped > 0f && stopped < 1f, "it was still going, at $stopped")

        running = false
        frames(10)

        assertEquals(stopped, animatable.value, "it kept moving after it was cancelled")
        assertFalse(animatable.isRunning)
    }

    @Test
    fun `snapping puts it there with no animation at all`() {
        val animatable = Animatable(0f, FloatVectoriser, Clock.Ui, host.clocks)
        show { Box(Modifier.alpha(animatable.value)) }

        animatable.snapTo(1f)
        frames(2)

        assertEquals(1f, animatable.value)
        assertEquals(1f, animatable.target)
    }

    // --- the part that must not regress ---------------------------------------------------------------

    @Test
    fun `a settled animation costs nothing`() {
        var target by mutableStateOf(0f)
        show {
            val animated by animateFloatAsState(target, Tween(100, easing = Easings.Linear))
            Box(Modifier.alpha(animated))
        }

        target = 1f
        frames(20)

        // Settled. From here on the interface must be as cheap as one with no animation on it at
        // all: a hundred frames, and not one of them has anything to redraw.
        val before = host.changedFrames
        repeat(100) {
            assertFalse(frame(), "frame $it redrew a settled interface")
        }
        assertEquals(before, host.changedFrames)
    }

    @Test
    fun `an animation on a stopped clock costs nothing either`() {
        host.clocks.register(Clock.World)
        var target by mutableStateOf(0f)
        show {
            val animated by animateFloatAsState(target, Tween(1000, easing = Easings.Linear), Clock.World)
            Box(Modifier.alpha(animated))
        }

        target = 1f
        frames(5)
        host.clocks.stop(Clock.World)
        // One frame for the last value it wrote before the clock stopped; after that, nothing.
        frame()
        val paused = host.changedFrames

        repeat(50) { assertFalse(frame(), "a paused animation redrew") }
        assertEquals(paused, host.changedFrames)

        // And it carries on from where it stopped rather than jumping to where it would have been.
        host.clocks.start(Clock.World)
        frame()
        assertTrue(frame(), "it should be moving again")
    }

    @Test
    fun `a pause menu animates over a frozen world`() {
        var world = -1f
        var menu = -1f
        var target by mutableStateOf(0f)
        show {
            val onWorld by animateFloatAsState(target, Tween(400, easing = Easings.Linear), Clock.World)
            val onUi by animateFloatAsState(target, Tween(400, easing = Easings.Linear), Clock.Ui)
            world = onWorld
            menu = onUi
            Box(Modifier.alpha(onWorld)) { Box(Modifier.alpha(onUi)) }
        }

        target = 1f
        begin()
        frame(100)
        settle()
        val frozen = world
        assertTrue(frozen > 0.2f, "it should be under way before we freeze it, and it was at $frozen")

        host.clocks.stop(Clock.World)
        frames(6, 50)
        settle()

        assertEquals(frozen, world, "the world kept moving while the game was paused")
        assertTrue(menu > frozen + 0.3f, "the menu did not: it was at $menu against $frozen")
    }
}
