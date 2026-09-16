package dev.wildware.composegl.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The watchdog, on a clock the test moves by hand. Nothing here waits. */
class HangWatchdogTest {

    private var now = 1_000L
    private val seconds = 1_000_000_000L
    private val watchdog = HangWatchdog(limitNanos = 5 * seconds, nanoTime = { now })

    @Test
    fun `frames arriving in time are not a hang`() {
        repeat(100) {
            watchdog.working("preview \"greeting\"")
            now += seconds / 60
            assertNull(watchdog.frameDone())
            assertNull(watchdog.check())
        }
    }

    @Test
    fun `no frame for longer than the limit names what was being drawn`() {
        watchdog.frameDone()
        watchdog.working("preview \"greeting\"")
        watchdog.working("preview \"spinner\" (com.game.LoadingKt.SpinnerPreview)")

        now += 4 * seconds
        assertNull(watchdog.check(), "four seconds is under the limit")

        now += 2 * seconds
        val hang = checkNotNull(watchdog.check())
        assertEquals("preview \"spinner\" (com.game.LoadingKt.SpinnerPreview)", hang.doing)
        assertEquals(6 * seconds, hang.nanos)
        assertTrue("SpinnerPreview" in hang.message && "6" in hang.message, hang.message)
    }

    @Test
    fun `a hang is reported once, and handed back when the frame finally ends`() {
        watchdog.frameDone()
        watchdog.working("preview \"spinner\"")
        now += 6 * seconds
        checkNotNull(watchdog.check())

        now += 3 * seconds
        assertNull(watchdog.check(), "still the same hang; it has been said")

        val ended = checkNotNull(watchdog.frameDone())
        assertEquals("preview \"spinner\"", ended.doing)
        assertEquals(9 * seconds, ended.nanos)

        now += seconds / 60
        assertNull(watchdog.frameDone())
        assertNull(watchdog.check())
    }

    @Test
    fun `a slow frame under the limit is handed back as nothing`() {
        watchdog.frameDone()
        now += 4 * seconds
        assertNull(watchdog.frameDone())
    }
}
