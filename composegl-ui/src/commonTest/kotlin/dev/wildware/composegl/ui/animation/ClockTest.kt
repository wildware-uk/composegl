package dev.wildware.composegl.ui.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clocks: the reason a pause menu still animates over a frozen game.
 *
 * The one that matters is the last pair. A stopped clock must not be *catching up* while it is
 * stopped, or every animation on it jumps forward the moment the player unpauses — which is the
 * bug this design exists to make impossible.
 */
class ClockTest {

    private val clocks = Clocks()

    private val second = 1_000_000_000L

    private fun advance(seconds: Long) {
        elapsedWall += seconds * second
        clocks.advance(elapsedWall)
    }

    private var elapsedWall = 0L

    private fun start() {
        clocks.register(Clock.Ui)
        clocks.register(Clock.World)
        clocks.advance(elapsedWall)
    }

    @Test
    fun `a clock nobody has advanced is at nothing`() {
        assertEquals(0L, clocks.time(Clock.Ui))
        assertTrue(clocks.isRunning(Clock.Ui))
    }

    @Test
    fun `running clocks move with the frame`() {
        start()

        advance(1)
        advance(2)

        assertEquals(3 * second, clocks.time(Clock.Ui))
        assertEquals(3 * second, clocks.time(Clock.World))
    }

    @Test
    fun `the first frame only sets the mark`() {
        // A game has been running for minutes before its first menu opens. If that first frame
        // counted, every animation on the screen would start three minutes in.
        elapsedWall = 500 * second
        start()

        assertEquals(0L, clocks.time(Clock.Ui))

        advance(1)

        assertEquals(second, clocks.time(Clock.Ui))
    }

    @Test
    fun `a stopped world freezes and the interface does not`() {
        start()
        advance(1)

        clocks.stop(Clock.World)
        advance(5)

        assertEquals(6 * second, clocks.time(Clock.Ui), "the pause menu has to keep animating")
        assertEquals(second, clocks.time(Clock.World))
        assertFalse(clocks.isRunning(Clock.World))
    }

    @Test
    fun `a world started again carries on from where it stopped`() {
        start()
        advance(1)
        clocks.stop(Clock.World)
        advance(5)

        clocks.start(Clock.World)
        advance(2)

        assertEquals(3 * second, clocks.time(Clock.World), "it caught up on the five seconds it was paused")
        assertEquals(8 * second, clocks.time(Clock.Ui))
    }

    @Test
    fun `setRunning is the same thing for a game holding a boolean`() {
        start()
        clocks.setRunning(Clock.World, false)
        advance(3)
        clocks.setRunning(Clock.World, true)
        advance(1)

        assertEquals(second, clocks.time(Clock.World))
    }

    @Test
    fun `a clock registered late starts at nothing rather than at the game's age`() {
        start()
        advance(10)

        val cutscene = Clock("cutscene")
        clocks.register(cutscene)

        assertEquals(0L, clocks.time(cutscene))

        advance(1)

        assertEquals(second, clocks.time(cutscene))
    }

    @Test
    fun `a wall clock that goes backwards does not drag the game with it`() {
        start()
        advance(2)

        clocks.advance(elapsedWall - 10 * second)

        assertEquals(2 * second, clocks.time(Clock.Ui), "time went backwards and nothing moved")

        // And the next real frame measures from where the wall clock actually is, rather than
        // handing over the ten seconds it just lost.
        elapsedWall -= 10 * second
        advance(1)

        assertEquals(3 * second, clocks.time(Clock.Ui))
    }

    @Test
    fun `two sets of clocks know nothing about each other`() {
        start()
        advance(1)

        val other = Clocks()
        other.register(Clock.Ui)

        assertEquals(0L, other.time(Clock.Ui), "a clock is a name; the time belongs to the interface")
    }
}
