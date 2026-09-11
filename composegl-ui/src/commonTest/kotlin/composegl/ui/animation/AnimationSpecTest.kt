package composegl.ui.animation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic under the animations, without a frame anywhere near it.
 *
 * Two things are worth being strict about. A spec is asked where the value is at a time, never told
 * to take a step, so a dropped frame and a two-hundred-millisecond frame land in the right place
 * instead of drifting. And a spring is solved rather than simulated, so the frame that took a
 * quarter of a second — a game loading something — does not make it explode.
 */
class AnimationSpecTest {

    private val millis = 1_000_000L

    // --- tweens ---------------------------------------------------------------------------------

    private val tween = Tween(200, easing = Easings.Linear)

    @Test
    fun `a tween is where the clock says rather than where the last step left it`() {
        assertEquals(0f, tween.at(0f, 100f, 0f, 0).value)
        assertEquals(50f, tween.at(0f, 100f, 0f, 100 * millis).value)
        assertEquals(100f, tween.at(0f, 100f, 0f, 200 * millis).value)
    }

    @Test
    fun `a frame that took a quarter of a second lands at the end rather than past it`() {
        val motion = tween.at(0f, 100f, 0f, 250 * millis)

        assertEquals(100f, motion.value)
        assertTrue(tween.isFinished(0f, 100f, 0f, 250 * millis))
    }

    @Test
    fun `a delay is time the value does not move`() {
        val delayed = Tween(100, delayMillis = 100, easing = Easings.Linear)

        assertEquals(0f, delayed.at(0f, 100f, 0f, 50 * millis).value)
        assertEquals(50f, delayed.at(0f, 100f, 0f, 150 * millis).value)
        assertEquals(100f, delayed.at(0f, 100f, 0f, 200 * millis).value)
        assertFalse(delayed.isFinished(0f, 100f, 0f, 150 * millis))
    }

    @Test
    fun `a tween of no duration is simply there`() {
        val instant = Tween(0)

        assertEquals(100f, instant.at(0f, 100f, 0f, 0).value)
        assertTrue(instant.isFinished(0f, 100f, 0f, 0))
    }

    @Test
    fun `a tween reports the speed it is moving at`() {
        // Halfway through a linear tween from 0 to 100 over 200ms: 500 units a second.
        val speed = tween.at(0f, 100f, 0f, 100 * millis).velocity

        assertTrue(abs(speed - 500f) < 5f, "it thought it was doing $speed")
    }

    @Test
    fun `snapping is there at once and after a delay if it is given one`() {
        assertEquals(100f, Snap().at(0f, 100f, 0f, 0).value)
        assertEquals(0f, Snap(delayMillis = 50).at(0f, 100f, 0f, 10 * millis).value)
        assertEquals(100f, Snap(delayMillis = 50).at(0f, 100f, 0f, 50 * millis).value)
    }

    // --- springs --------------------------------------------------------------------------------

    @Test
    fun `a spring arrives and stops`() {
        val spring = Spring()

        assertFalse(spring.isFinished(0f, 1f, 0f, 0))

        var time = 0L
        while (time < 5_000 * millis && !spring.isFinished(0f, 1f, 0f, time)) time += 16 * millis

        assertTrue(time < 3_000 * millis, "it took ${time / millis}ms to settle")
        assertTrue(abs(spring.at(0f, 1f, 0f, time).value - 1f) < 0.01f)
    }

    @Test
    fun `a spring that is not damped wobbles past its target and one that is does not`() {
        val bouncy = Spring(damping = Spring.Bouncy)
        val steady = Spring(damping = Spring.NoWobble)

        val overshootsBouncing = (0..200).any { bouncy.at(0f, 1f, 0f, it * 5L * millis).value > 1.001f }
        val overshootsSteady = (0..200).any { steady.at(0f, 1f, 0f, it * 5L * millis).value > 1.001f }

        assertTrue(overshootsBouncing, "a bouncy spring should go past and come back")
        assertFalse(overshootsSteady, "a damped one should not")
    }

    @Test
    fun `an over-damped spring crawls in without overshooting`() {
        val stiff = Spring(damping = 2f, stiffness = Spring.Low)

        val values = (0..100).map { stiff.at(0f, 1f, 0f, it * 10L * millis).value }

        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 1e-4f }, "it went backwards at some point")
        assertTrue(values.none { it > 1.001f })
        assertTrue(values.last() > 0.9f, "it never got there: ${values.last()}")
    }

    @Test
    fun `a spring given a starting speed carries it`() {
        val spring = Spring()

        val thrown = spring.at(0f, 1f, 10f, 10 * millis).value
        val dropped = spring.at(0f, 1f, 0f, 10 * millis).value

        assertTrue(thrown > dropped, "it was thrown at the target and should be further along")
    }

    @Test
    fun `a spring does not explode on an enormous frame`() {
        val spring = Spring(stiffness = Spring.High)

        val motion = spring.at(0f, 1f, 0f, 250 * millis)

        assertTrue(motion.value.isFinite() && abs(motion.value - 1f) < 0.01f, "it was at ${motion.value}")
    }

    @Test
    fun `a spring already at its target has nothing to do`() {
        assertTrue(Spring().isFinished(1f, 1f, 0f, 0))
    }

    // --- easings --------------------------------------------------------------------------------

    @Test
    fun `every easing starts at nothing and ends at one`() {
        listOf(
            Easings.Linear,
            Easings.EaseIn,
            Easings.EaseOut,
            Easings.EaseInOut,
            Easings.Overshoot,
            Easings.Bounce,
            Easings.Sine,
        ).forEach { easing ->
            assertTrue(abs(easing.transform(0f)) < 0.001f, "one of them did not start at nothing")
            assertTrue(abs(easing.transform(1f) - 1f) < 0.001f, "one of them did not end at one")
        }
    }

    @Test
    fun `ease out is ahead of linear and ease in is behind it`() {
        assertTrue(Easings.EaseOut.transform(0.25f) > 0.25f)
        assertTrue(Easings.EaseIn.transform(0.25f) < 0.25f)
    }

    @Test
    fun `overshoot goes past one on the way`() {
        assertTrue((1..99).any { Easings.Overshoot.transform(it / 100f) > 1f })
    }

    @Test
    fun `a bezier easing is monotonic when its control points are`() {
        val curve = CubicBezier(0.4f, 0f, 0.2f, 1f)

        val values = (0..100).map { curve.transform(it / 100f) }

        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 1e-4f })
    }

    @Test
    fun `a bezier easing solves the flat-start curve designers like`() {
        // The one Newton's method alone wanders off on, which is why there is a bisection fallback.
        val curve = CubicBezier(1f, 0f, 0f, 1f)

        assertTrue(abs(curve.transform(0.5f) - 0.5f) < 0.01f, "it was at ${curve.transform(0.5f)}")
        assertTrue(curve.transform(0.1f) < 0.05f)
        assertTrue(curve.transform(0.9f) > 0.95f)
    }
}
