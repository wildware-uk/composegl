package uk.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos

/**
 * Which clock an animation runs on.
 *
 * A name and nothing else. The time itself lives in [Clocks], one set per interface, because two
 * games — or two tests — in the same process must not share a clock by accident.
 *
 * Two exist for everybody. [Ui] is the interface's own and a game should never stop it: a pause
 * menu that freezes because the game is paused is a pause menu nobody can use. [World] is the
 * game's, and stopping it is what "paused" means.
 *
 * A game with more than two — a cutscene clock, a clock that runs at half speed for a slow-motion
 * finish — makes its own and advances it itself:
 *
 * ```kotlin
 * val cutscene = Clock("cutscene")
 * ```
 */
class Clock(val name: String) {

    override fun toString(): String = name

    companion object {

        /** The interface's own time. Always runs, whatever the game is doing. */
        val Ui = Clock("ui")

        /** The game's time. Stopping this is what pausing a game means. */
        val World = Clock("world")
    }
}

/**
 * How much time each clock has had.
 *
 * The game hands this one number a frame — the same number it hands the host — and every running
 * clock moves by the gap since the last one. A stopped clock does not move, so an animation on it
 * carries on from exactly where it was when it resumes rather than jumping to where it would have
 * been. That is the whole feature: a pause menu animating over a frozen world.
 *
 * Nothing here is snapshot state, deliberately. A clock that recomposed everything reading it
 * would cost a redraw a frame for every animation on screen, settled or not — and an animation that
 * has settled must cost nothing at all. Animations wait for frames instead, which is a
 * subscription they drop when they finish.
 *
 * Not thread-safe: it is read and written on the frame thread, like the rest of a frame.
 */
class Clocks {

    private val elapsed = HashMap<Clock, Long>()
    private val stopped = HashSet<Clock>()

    private var last = NoFrameYet

    /** How long [clock] has been running, in nanoseconds. Zero for a clock never advanced. */
    fun time(clock: Clock): Long = elapsed[clock] ?: 0L

    fun isRunning(clock: Clock): Boolean = clock !in stopped

    /**
     * Stops [clock]. Time passing no longer reaches it.
     *
     * Stopping [Clock.Ui] is allowed and is almost always a mistake: the menu asking the player
     * whether to quit is drawn on it.
     */
    fun stop(clock: Clock) {
        stopped += clock
    }

    /** Starts [clock] again, from the time it stopped at rather than from where it would have been. */
    fun start(clock: Clock) {
        stopped -= clock
    }

    /** Stops or starts, for a game holding a boolean rather than a branch. */
    fun setRunning(clock: Clock, running: Boolean) {
        if (running) start(clock) else stop(clock)
    }

    /**
     * Moves every running clock on to [frameNanos].
     *
     * [frameNanos] is wall time — `System.nanoTime()`, the same number the host is given. The first
     * call only sets the mark, because there is no gap to measure yet, and a gap that goes backwards
     * counts as nothing: a clock that goes backwards makes an animation jump, and wall clocks do go
     * backwards.
     */
    fun advance(frameNanos: Long) {
        val previous = last
        last = frameNanos
        if (previous == NoFrameYet) return

        val delta = (frameNanos - previous).coerceAtLeast(0L)
        if (delta == 0L) return

        elapsed.keys.forEach { clock ->
            if (clock !in stopped) elapsed[clock] = (elapsed[clock] ?: 0L) + delta
        }
    }

    /**
     * Makes sure [clock] is one of the clocks being advanced.
     *
     * An animation calls this when it starts. A clock nobody has ever asked about is not being
     * tracked, and starting one at the current wall time rather than at zero would make its first
     * animation jump by however long the game has been running.
     */
    fun register(clock: Clock) {
        elapsed.getOrPut(clock) { 0L }
    }

    private companion object {
        /** Not zero: a game may well hand us zero as its first frame time. */
        const val NoFrameYet = Long.MIN_VALUE
    }
}

/**
 * The clocks every animation in this interface runs on.
 *
 * A host provides its own, so this is only ever named by a game that wants a subtree on different
 * clocks — a replay window running at its own speed — or by a test driving frames by hand.
 */
val LocalClocks: ProvidableCompositionLocal<Clocks> = staticCompositionLocalOf { Clocks() }

@Composable
fun ProvideClocks(clocks: Clocks, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalClocks provides clocks, content = content)

/**
 * Waits for [millis] of [clock]'s own time.
 *
 * Not `delay`, which counts wall time and would go on counting through a pause. A hold before a
 * health bar's damage trail starts draining belongs to the world: pause the game half a second into
 * it and it has half a second left when the game comes back, however long the player spent in the
 * menu.
 *
 * A stopped clock waits forever, which is exactly what being paused means.
 */
suspend fun Clocks.wait(clock: Clock, millis: Int) {
    if (millis <= 0) return
    register(clock)
    val until = time(clock) + millis * 1_000_000L
    while (time(clock) < until) withFrameNanos { }
}
