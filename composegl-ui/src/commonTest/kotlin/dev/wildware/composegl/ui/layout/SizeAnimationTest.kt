package dev.wildware.composegl.ui.layout

import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.animation.Tween
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The size an `animateContentSize` node travels through, on its own: no tree, no host, just a
 * clock moved by hand and the numbers it hands back.
 */
class SizeAnimationTest {

    private val clocks = Clocks()
    private val clock = Clock("resize")
    private val linear = Tween(durationMillis = 100, easing = Easings.Linear)
    private var wall = 0L

    private fun tick(millis: Long) {
        wall += millis * 1_000_000L
        clocks.advance(wall)
    }

    init {
        clocks.advance(wall)
    }

    @Test
    fun `the first size is taken at once`() {
        val animation = SizeAnimation()

        assertTrue(animation.follow(80f, 40f, linear, clock, clocks))

        assertEquals(80f, animation.width)
        assertEquals(40f, animation.height)
        assertFalse(animation.isRunning)
        assertFalse(clocks.isAnimating)
    }

    @Test
    fun `a new size is travelled to over the spec's time`() {
        val animation = SizeAnimation()
        animation.follow(80f, 40f, linear, clock, clocks)

        assertFalse(animation.follow(180f, 140f, linear, clock, clocks), "no time has passed, so it has not moved")
        assertEquals(80f, animation.width)
        assertTrue(clocks.isAnimating)

        tick(50)
        assertTrue(animation.follow(180f, 140f, linear, clock, clocks))
        assertEquals(130f, animation.width, 0.01f)
        assertEquals(90f, animation.height, 0.01f)

        tick(50)
        animation.follow(180f, 140f, linear, clock, clocks)
        assertEquals(180f, animation.width)
        assertEquals(140f, animation.height)
        assertFalse(animation.isRunning)
        assertFalse(clocks.isAnimating, "arriving stops it counting as playing")
    }

    @Test
    fun `a size that has arrived reports no change however often it is asked`() {
        val animation = SizeAnimation()
        animation.follow(80f, 40f, linear, clock, clocks)

        repeat(10) {
            tick(16)
            assertFalse(animation.follow(80f, 40f, linear, clock, clocks))
        }
    }

    @Test
    fun `a new target part-way turns round from where it is`() {
        val animation = SizeAnimation()
        val spring = Spring(threshold = 0.5f)
        animation.follow(0f, 0f, spring, clock, clocks)
        animation.follow(200f, 200f, spring, clock, clocks)
        tick(40)
        animation.follow(200f, 200f, spring, clock, clocks)
        val reached = animation.width
        assertTrue(reached > 20f && reached < 180f, "part-way, at $reached")

        animation.follow(0f, 0f, spring, clock, clocks)
        tick(16)
        animation.follow(0f, 0f, spring, clock, clocks)

        assertTrue(abs(animation.width - reached) < 30f, "carried on from $reached rather than jumping, at ${animation.width}")
    }

    @Test
    fun `with no clocks there is no time to animate in and the size is taken`() {
        val animation = SizeAnimation()
        animation.follow(80f, 40f, linear, clock, null)
        animation.follow(180f, 140f, linear, clock, null)

        assertEquals(180f, animation.width)
        assertFalse(animation.isRunning)
    }

    @Test
    fun `forgetting part-way stops it playing and the next size is taken at once`() {
        val animation = SizeAnimation()
        animation.follow(80f, 40f, linear, clock, clocks)
        animation.follow(180f, 140f, linear, clock, clocks)
        assertTrue(clocks.isAnimating)

        animation.forget()

        assertFalse(clocks.isAnimating)
        animation.follow(300f, 10f, linear, clock, clocks)
        assertEquals(300f, animation.width)
        assertEquals(10f, animation.height)
    }

    @Test
    fun `moving to another clock part-way plays on that clock instead`() {
        val animation = SizeAnimation()
        animation.follow(80f, 40f, linear, clock, clocks)
        animation.follow(180f, 140f, linear, clock, clocks)
        clocks.stop(clock)
        assertFalse(clocks.isAnimating, "only on a stopped clock")

        animation.follow(180f, 140f, linear, Clock.Ui, clocks)

        assertTrue(clocks.isAnimating, "now on a running one")
        tick(100)
        animation.follow(180f, 140f, linear, Clock.Ui, clocks)
        assertEquals(180f, animation.width)
        assertFalse(clocks.isAnimating)
    }
}
