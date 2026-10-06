package dev.wildware.composegl.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.rememberScrollState
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference

/**
 * A screen that is closed lets go of everything (#264).
 *
 * A game swaps screens by disposing one host and opening the next. Disposing cancels the screen's
 * coroutines, but a cancelled coroutine only tidies up — its `finally` blocks, unregistering what it
 * registered — the next time it runs, and nothing ran a closed host's work again. So every closed
 * screen stayed registered with the Compose runtime for good: kept in memory, and asked about every
 * state change anywhere in the game after it.
 */
class DisposedHostTest {

    @Test
    fun `a closed screen's effects finish cleaning up before dispose returns`() {
        var cleanedUp = false
        val ui = uiTest(Size(600f, 400f)) {
            LaunchedEffect(Unit) {
                try {
                    awaitCancellation()
                } finally {
                    cleanedUp = true
                }
            }
        }

        ui.close()

        assertTrue(cleanedUp, "the effect was cancelled but its finally block never ran")
    }

    /**
     * A screen's own code can close it: a "quit to menu" effect. That runs inside the host's frame,
     * and the frame finishes the cleaning up before it returns.
     *
     * After the closing effect is done, not in the middle of it: coroutines on one screen take
     * turns, each running until it waits, and the one closing the screen must not find the others
     * have run while it was still going.
     */
    @Test
    fun `a screen that closes itself from its own effect cleans up after that effect in the same frame`() {
        val before = Recomposer.runningRecomposers.value.size
        val host = UiHost()
        val happened = mutableListOf<String>()
        var quit by mutableStateOf(false)
        host.setContent {
            LaunchedEffect(Unit) {
                try {
                    awaitCancellation()
                } finally {
                    happened += "cleaned up"
                }
            }
            if (quit) {
                LaunchedEffect(Unit) {
                    host.dispose()
                    happened += "closed"
                }
            }
        }
        var now = 0L
        repeat(3) { host.frame(++now * 16_666_667L) }

        quit = true
        host.frame(++now * 16_666_667L)

        assertTrue(host.isDisposed, "the effect closed the screen")
        assertEquals(listOf("closed", "cleaned up"), happened, "the other effect cleans up once the closing one is done")
        assertEquals(before, Recomposer.runningRecomposers.value.size, "its recomposer is still running")
    }

    /**
     * A cleanup that throws is the screen's own error, reported where any effect's error goes: the
     * platform's handler for uncaught coroutine errors, here the thread's. It stops nothing else
     * cleaning up, and it is not the screen failing: the screen was closed.
     */
    @Test
    fun `a cleanup that throws is reported and the rest still cleans up`() {
        val before = Recomposer.runningRecomposers.value.size
        val reported = mutableListOf<Throwable>()
        var cleanedUp = false
        val thread = Thread.currentThread()
        val handler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> reported += error }
        try {
            val ui = uiTest(Size(600f, 400f)) {
                LaunchedEffect(Unit) {
                    try {
                        awaitCancellation()
                    } finally {
                        error("the cleanup broke")
                    }
                }
                LaunchedEffect(Unit) {
                    try {
                        awaitCancellation()
                    } finally {
                        cleanedUp = true
                    }
                }
            }

            ui.close()

            assertEquals(listOf("the cleanup broke"), reported.map { it.message }, "the error went nowhere")
            assertTrue(cleanedUp, "the other effect never cleaned up")
            assertNull(ui.host.failure, "closing a screen is not the screen failing")
            assertEquals(before, Recomposer.runningRecomposers.value.size, "its recomposer is still running")
        } finally {
            thread.uncaughtExceptionHandler = handler
        }
    }

    /**
     * Compose refuses to dispose a composition while it is being composed, so a screen that closes
     * itself from its own composable body gets that error back. It is still closed: nothing of it
     * is left running.
     */
    @Test
    fun `a screen closed while it is being composed still stops`() {
        val before = Recomposer.runningRecomposers.value.size
        val host = UiHost()

        val thrown = runCatching { host.setContent { host.dispose() } }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "Compose let a composition go while composing it: $thrown")
        assertTrue(host.isDisposed)
        assertEquals(before, Recomposer.runningRecomposers.value.size, "its recomposer is still running")
    }

    @Test
    fun `a closed screen leaves no recomposer running`() {
        val before = Recomposer.runningRecomposers.value.size
        val ui = uiTest(Size(600f, 400f)) { Screen() }
        assertEquals(before + 1, Recomposer.runningRecomposers.value.size, "showing a screen runs one recomposer")

        ui.close()

        assertEquals(before, Recomposer.runningRecomposers.value.size, "and closing it stops that recomposer")
    }

    @Test
    fun `a closed screen and the state it showed are freed`() {
        val (host, state) = showFlickAndClose()

        assertTrue(collected(state), "the scroll state the closed screen showed is still held")
        assertTrue(collected(host), "the closed screen is still held")
    }

    /**
     * Timed, so the bound is loose. Each screen left registered made every later change ask its
     * recomposer whether the change was one of its own: about eleven nanoseconds each, so the two
     * hundred closed here would add over two microseconds to a change that costs a fifth of one.
     * Measured with a hundred: 155 ns before and 1,264 ns after when closed screens stayed
     * registered, 180 ns and 157 ns now.
     */
    @Test
    fun `a state change costs no more after screens have been closed`() {
        // Read by nothing: the change itself, and whatever hears about every change, is all a round
        // of these costs.
        var elsewhere by mutableIntStateOf(0)
        val change = {
            elsewhere++
            Snapshot.sendApplyNotifications()
        }
        val before = nanosEach(change)

        repeat(200) { showFlickAndClose() }
        val after = nanosEach(change)

        assertTrue(
            after < before * 2 + 500,
            "a state change took $after ns after two hundred screens were closed, against $before ns before",
        )
    }

    /**
     * A screen as a game shows one: a scroll area the player has flicked, and something animating
     * on every frame, so a coroutine is waiting on the next frame at the moment it is closed. Only
     * weak references come back, so nothing in the test keeps either alive.
     */
    private fun showFlickAndClose(): Pair<WeakReference<UiHost>, WeakReference<ScrollState>> {
        var shown: ScrollState? = null
        val ui = uiTest(Size(600f, 400f)) {
            val state = rememberScrollState()
            shown = state
            Screen(state)
        }
        ui.flick(Offset(100f, 180f), Offset(100f, 60f))
        val state = checkNotNull(shown)
        check(state.y > 0f) { "the flick did not scroll the area, so the screen did not do what a game's does" }
        shown = null

        ui.close()
        return WeakReference(ui.host) to WeakReference(state)
    }

    @Composable
    private fun Screen(state: ScrollState = rememberScrollState()) {
        LaunchedEffect(Unit) { while (true) withFrameNanos { } }
        ScrollArea(Modifier.size(200f), state, bars = false) {
            Column(Modifier.fillMaxWidth()) {
                repeat(10) { Box(Modifier.fillMaxWidth().height(100f)) }
            }
        }
    }

    /** Whether the collector has cleared [reference], asking it up to [attempts] times. */
    private fun collected(reference: WeakReference<*>, attempts: Int = 50): Boolean {
        repeat(attempts) {
            if (reference.get() == null) return true
            System.gc()
            @Suppress("UNUSED_VARIABLE")
            val pressure = ByteArray(1 shl 20)
        }
        return reference.get() == null
    }

    /** How long [what] takes, in nanoseconds: the fastest of five rounds of two thousand, after a warm-up. */
    private fun nanosEach(what: () -> Unit): Long {
        repeat(20_000) { what() }
        var least = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            repeat(2_000) { what() }
            least = minOf(least, System.nanoTime() - start)
        }
        return least / 2_000
    }
}
