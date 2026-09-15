package dev.wildware.composegl.ui.animation

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debug controls on a set of clocks: freeze time, move it a frame at a time, slow it down.
 *
 * The rule under all of it is that the debug switch and the game's own pause are two different
 * switches. A developer freezing the interface to look at a spring must not be un-frozen by the game
 * starting its world again, and the game must not be paused by a developer's step.
 */
class ClockDebugTest {

    private val clocks = Clocks()
    private val frame = 10_000_000L
    private var wall = 0L

    private fun start() {
        clocks.register(Clock.Ui)
        clocks.register(Clock.World)
        clocks.advance(wall)
    }

    private fun frames(count: Int = 1) = repeat(count) {
        wall += frame
        clocks.advance(wall)
    }

    // --- pausing ---------------------------------------------------------------------------------

    @Test
    fun `a paused set of clocks does not move`() {
        start()
        frames(2)

        clocks.debug.pause()
        frames(5)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
        assertEquals(2 * frame, clocks.time(Clock.World))
        assertTrue(clocks.debug.isPaused)
        assertTrue(clocks.debug.isPaused(Clock.Ui))
    }

    @Test
    fun `resuming carries on from where it froze rather than catching up`() {
        start()
        clocks.debug.pause()
        frames(5)

        clocks.debug.resume()
        frames(1)

        assertEquals(frame, clocks.time(Clock.Ui))
        assertFalse(clocks.debug.isPaused)
    }

    @Test
    fun `one clock can be paused while the others run`() {
        start()

        clocks.debug.pause(Clock.World)
        frames(3)

        assertEquals(3 * frame, clocks.time(Clock.Ui))
        assertEquals(0L, clocks.time(Clock.World))
        assertFalse(clocks.debug.isPaused, "not every clock is paused")
        assertTrue(clocks.debug.isPaused(Clock.World))
        assertFalse(clocks.debug.isPaused(Clock.Ui))
    }

    @Test
    fun `pausing everything also freezes a clock registered afterwards`() {
        start()
        clocks.debug.pause()

        val cutscene = Clock("cutscene")
        clocks.register(cutscene)
        frames(3)

        assertEquals(0L, clocks.time(cutscene))
    }

    @Test
    fun `one clock can be let go while everything else stays paused`() {
        start()
        clocks.debug.pause()

        clocks.debug.resume(Clock.Ui)
        frames(2)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
        assertEquals(0L, clocks.time(Clock.World))
    }

    @Test
    fun `the game starting its world does not undo a debug pause`() {
        start()
        clocks.stop(Clock.World)
        clocks.debug.pause(Clock.World)

        clocks.start(Clock.World)
        frames(3)

        assertEquals(0L, clocks.time(Clock.World), "the developer is still looking at it")
        assertTrue(clocks.isRunning(Clock.World), "and the game's own switch is the game's")
    }

    // --- stepping --------------------------------------------------------------------------------

    @Test
    fun `a step moves a paused clock by exactly one frame`() {
        start()
        clocks.debug.pause()
        frames(3)

        clocks.debug.step()
        frames(4)

        assertEquals(frame, clocks.time(Clock.Ui), "one frame, however many frames went by after it")
        assertEquals(frame, clocks.time(Clock.World))
    }

    @Test
    fun `stepping several frames moves that many`() {
        start()
        clocks.debug.pause()

        clocks.debug.step(frames = 3)
        frames(10)

        assertEquals(3 * frame, clocks.time(Clock.Ui))
    }

    @Test
    fun `stepping a running clock pauses it first`() {
        start()
        frames(1)

        clocks.debug.step()
        frames(5)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
        assertTrue(clocks.debug.isPaused)
    }

    @Test
    fun `stepping one clock leaves the other paused ones where they are`() {
        start()
        clocks.debug.pause()

        clocks.debug.step(frames = 2, clock = Clock.World)
        frames(5)

        assertEquals(2 * frame, clocks.time(Clock.World))
        assertEquals(0L, clocks.time(Clock.Ui))
    }

    @Test
    fun `a step does not move a clock the game has stopped`() {
        start()
        clocks.stop(Clock.World)
        clocks.debug.pause()

        clocks.debug.step()
        frames(2)

        assertEquals(frame, clocks.time(Clock.Ui))
        assertEquals(0L, clocks.time(Clock.World), "a game's pause is not the developer's to step through")
    }

    @Test
    fun `a step waits for a frame with time in it`() {
        start()
        clocks.debug.pause()
        clocks.debug.step()

        // The same wall time twice: a frame with no gap moves nothing, and must not spend the step.
        clocks.advance(wall)
        frames(1)

        assertEquals(frame, clocks.time(Clock.Ui))
    }

    @Test
    fun `resuming throws away steps not yet taken`() {
        start()
        clocks.debug.pause()
        clocks.debug.step(frames = 5)
        clocks.debug.resume()

        clocks.debug.pause()
        frames(3)

        assertEquals(0L, clocks.time(Clock.Ui))
    }

    @Test
    fun `a step must be a frame or more`() {
        assertFailsWith<IllegalArgumentException> { clocks.debug.step(frames = -1) }
    }

    // --- speed -----------------------------------------------------------------------------------

    @Test
    fun `a quarter speed moves a quarter as far`() {
        start()
        clocks.debug.speed = 0.25f

        frames(8)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
        assertEquals(2 * frame, clocks.time(Clock.World))
    }

    @Test
    fun `slow motion loses nothing to rounding`() {
        // A third of a 10ms frame is not a whole number of nanoseconds. Dropped every frame, a slowed
        // spring would drift behind where it should be; carried, it lands exactly.
        // Against the speed as a Float actually holds it, which is not quite a third.
        val third = 1f / 3f
        start()
        clocks.debug.speed = third

        frames(3_000)

        val drift = clocks.time(Clock.Ui) - (3_000 * frame * third.toDouble()).toLong()
        assertTrue(drift in -1L..1L, "it drifted by $drift nanoseconds")
    }

    @Test
    fun `one clock can run slower than the rest`() {
        start()
        clocks.debug.setSpeed(Clock.World, 0.5f)

        frames(4)

        assertEquals(4 * frame, clocks.time(Clock.Ui))
        assertEquals(2 * frame, clocks.time(Clock.World))
        assertEquals(0.5f, clocks.debug.speedOf(Clock.World))
    }

    @Test
    fun `the overall speed and a clock's own speed multiply`() {
        start()
        clocks.debug.speed = 0.5f
        clocks.debug.setSpeed(Clock.World, 0.5f)

        frames(8)

        assertEquals(4 * frame, clocks.time(Clock.Ui))
        assertEquals(2 * frame, clocks.time(Clock.World))
        assertEquals(0.25f, clocks.debug.speedOf(Clock.World))
    }

    @Test
    fun `a step is a frame at the slowed speed`() {
        // Stepping a slowed spring shows the frames a slowed spring would draw, not full-speed ones.
        start()
        clocks.debug.speed = 0.5f
        clocks.debug.pause()

        clocks.debug.step(frames = 2)
        frames(2)

        assertEquals(frame, clocks.time(Clock.Ui))
    }

    @Test
    fun `a speed of nothing or less is refused`() {
        // Pausing is what pause is for. A zero speed would be a second pause that step cannot move.
        assertFailsWith<IllegalArgumentException> { clocks.debug.speed = 0f }
        assertFailsWith<IllegalArgumentException> { clocks.debug.speed = -1f }
        assertFailsWith<IllegalArgumentException> { clocks.debug.setSpeed(Clock.Ui, Float.NaN) }
    }

    // --- what a test harness waits on ------------------------------------------------------------

    @Test
    fun `an animation on a debug-paused clock is not waited for`() {
        clocks.began(Clock.Ui)
        clocks.debug.pause()

        assertFalse(clocks.isAnimating, "it will not move until somebody steps or resumes")

        clocks.debug.step()
        assertTrue(clocks.isAnimating, "a step is waiting to be taken")

        start()
        frames(1)
        assertFalse(clocks.isAnimating, "and it has been taken")
    }

    @Test
    fun `an animation on a clock that is still running is waited for while another is paused`() {
        clocks.began(Clock.Ui)
        clocks.debug.pause(Clock.World)

        assertTrue(clocks.isAnimating)
    }

    // --- keys ------------------------------------------------------------------------------------

    private fun ClockDebugKeys.press(key: Key, repeat: Boolean = false) =
        onKey(KeyEvent(key, KeyEventType.Down, repeat = repeat)).also {
            onKey(KeyEvent(key, KeyEventType.Up))
        }

    @Test
    fun `the pause key freezes and then lets go`() {
        val keys = ClockDebugKeys(clocks)

        assertTrue(keys.press(Key.F5))
        assertTrue(clocks.debug.isPaused)

        keys.press(Key.F5)
        assertFalse(clocks.debug.isPaused)
    }

    @Test
    fun `the step key moves one frame each press and holding it keeps stepping`() {
        val keys = ClockDebugKeys(clocks)
        start()

        keys.press(Key.F6)
        keys.press(Key.F6, repeat = true)
        frames(5)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
    }

    @Test
    fun `the speed keys halve and double between an eighth and full speed`() {
        val keys = ClockDebugKeys(clocks)

        keys.press(Key.F7)
        assertEquals(0.5f, clocks.debug.speed)
        repeat(5) { keys.press(Key.F7) }
        assertEquals(0.125f, clocks.debug.speed, "no slower than an eighth")

        repeat(5) { keys.press(Key.F8) }
        assertEquals(1f, clocks.debug.speed, "and no faster than real time")
    }

    @Test
    fun `the speed keys never push a speed set in code the wrong way`() {
        val keys = ClockDebugKeys(clocks)

        clocks.debug.speed = 2f
        keys.press(Key.F8)
        assertEquals(2f, clocks.debug.speed, "faster does not slow a double-speed clock to real time")

        clocks.debug.speed = 0.1f
        keys.press(Key.F7)
        assertEquals(0.1f, clocks.debug.speed, "slower does not speed a tenth up to an eighth")
    }

    @Test
    fun `keys bound to one clock leave the others alone`() {
        val keys = ClockDebugKeys(clocks, clock = Clock.World)
        start()

        keys.press(Key.F5)
        keys.press(Key.F7)
        frames(2)

        assertEquals(2 * frame, clocks.time(Clock.Ui))
        assertEquals(0L, clocks.time(Clock.World))
        assertEquals(0.5f, clocks.debug.speedOf(Clock.World))
        assertEquals(1f, clocks.debug.speed)
    }

    @Test
    fun `other keys and key releases are not taken`() {
        val keys = ClockDebugKeys(clocks)

        assertFalse(keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down)))
        assertFalse(keys.onKey(KeyEvent(Key.F5, KeyEventType.Up)))
        assertFalse(clocks.debug.isPaused)
    }

    @Test
    fun `a held pause key does not flicker the pause on and off`() {
        val keys = ClockDebugKeys(clocks)

        keys.press(Key.F5)
        keys.press(Key.F5, repeat = true)

        assertTrue(clocks.debug.isPaused)
    }
}
