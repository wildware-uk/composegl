package dev.wildware.composegl.ui.animation

import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import kotlin.math.floor

/**
 * A developer's hand on a set of clocks: freeze them, move them on a frame at a time, slow them.
 *
 * ```kotlin
 * host.clocks.debug.pause()
 * host.clocks.debug.step(frames = 1)
 * host.clocks.debug.speed = 0.25f
 * ```
 *
 * A spring that overshoots for three frames, or a fade that pops on its last one, is over before an
 * eye can see it at sixty frames a second. Frozen and stepped, every frame it draws can be looked
 * at in turn.
 *
 * Everything takes a [Clock], or nothing for every clock at once, so the interface can be stepped
 * through while the world carries on — or the world slowed while the pause menu over it runs at
 * full speed.
 *
 * This is not the game's pause, and the two never touch. [Clocks.stop] is the game saying its world
 * is paused; this is somebody debugging. A game starting its world again does not un-freeze a clock
 * a developer is looking at, and a developer's step does not move a world the game has stopped.
 *
 * Not thread-safe, like [Clocks]: call it on the frame thread, which is where input arrives anyway.
 */
class ClockDebug internal constructor() {

    /** Set by [pause] with no clock: every clock, including ones nobody has registered yet. */
    private var allPaused = false

    /** Paused one by one, while [allPaused] is off. */
    private val paused = HashSet<Clock>()

    /** Let go one by one, while [allPaused] is on. */
    private val exempt = HashSet<Clock>()

    /** Frames every paused clock is still owed by [step]. */
    private var stepsForAll = 0

    /** Frames one paused clock is still owed by [step]. */
    private val steps = HashMap<Clock, Int>()

    /** Whether this frame is spending one of [stepsForAll]. Settled once a frame, in [beginFrame]. */
    private var steppingAll = false

    private val speeds = HashMap<Clock, Float>()

    /** The part of a nanosecond a slowed clock has not been given yet. See [scale]. */
    private val carry = HashMap<Clock, Double>()

    /** Whether every clock is frozen, as [pause] with no clock leaves them. */
    val isPaused: Boolean get() = allPaused && exempt.isEmpty()

    /** Whether [clock] is frozen, by either kind of [pause]. */
    fun isPaused(clock: Clock): Boolean = if (allPaused) clock !in exempt else clock in paused

    /**
     * Freezes [clock], or every clock when it is null.
     *
     * Animations on a frozen clock hold exactly where they are. Frames still arrive — the game keeps
     * drawing — and nothing on the clock moves until a [step] or a [resume].
     */
    fun pause(clock: Clock? = null) {
        if (clock == null) {
            allPaused = true
            exempt.clear()
            paused.clear()
        } else if (allPaused) {
            exempt -= clock
        } else {
            paused += clock
        }
    }

    /**
     * Lets [clock] run again, or every clock when it is null, from the time it froze at.
     *
     * Steps not yet taken are thrown away, so a stray press of the step key before resuming does not
     * steal a frame from the next pause.
     */
    fun resume(clock: Clock? = null) {
        if (clock == null) {
            allPaused = false
            paused.clear()
            exempt.clear()
            stepsForAll = 0
            steps.clear()
        } else {
            if (allPaused) exempt += clock else paused -= clock
            steps.remove(clock)
        }
    }

    /**
     * Moves a frozen [clock], or every frozen clock, on by [frames] frames and freezes it again.
     *
     * A running clock is paused first: stepping is only ever done to something standing still, and a
     * step key pressed on a running game is somebody wanting it to stop there. With no clock, that
     * only happens when nothing at all is frozen; a clock already let go with [resume] keeps running,
     * so the interface over a stepped world goes on animating.
     *
     * The step is taken on the frames that follow rather than now, one per frame with time in it,
     * because an animation only moves when it is handed a frame. Each is as long as the frame it is
     * taken on, at the clock's [speedOf], so stepping a slowed spring shows the frames a slowed spring
     * draws.
     */
    fun step(frames: Int = 1, clock: Clock? = null) {
        require(frames >= 0) { "cannot step back $frames frames; time only runs forwards" }
        if (clock == null) {
            if (!allPaused && paused.isEmpty()) pause()
            stepsForAll += frames
        } else {
            if (!isPaused(clock)) pause(clock)
            steps[clock] = (steps[clock] ?: 0) + frames
        }
    }

    /**
     * How fast every clock runs. 1 is real time, 0.25 a quarter of it.
     *
     * Multiplies with each clock's own from [setSpeed]. Must be above zero: a clock that should not
     * move wants [pause], which [step] can still move one frame at a time.
     */
    var speed: Float = 1f
        set(value) {
            requireSpeed(value)
            field = value
        }

    /** How fast [clock] runs on its own, before the overall [speed]. 1 is real time. */
    fun setSpeed(clock: Clock, speed: Float) {
        requireSpeed(speed)
        if (speed == 1f) speeds.remove(clock) else speeds[clock] = speed
    }

    /** How fast [clock] actually runs: its own speed and the overall [speed] together. */
    fun speedOf(clock: Clock): Float = speed * (speeds[clock] ?: 1f)

    /** The speed [clock] was given by [setSpeed], without the overall [speed]. */
    fun ownSpeedOf(clock: Clock): Float = speeds[clock] ?: 1f

    // --- what Clocks asks, once a frame ---------------------------------------------------------

    /** Called once per frame with time in it, before any clock is asked about. */
    internal fun beginFrame() {
        steppingAll = stepsForAll > 0
        if (steppingAll) stepsForAll--
    }

    /** Whether [clock] moves this frame, spending a step owed to it if that is why. */
    internal fun moves(clock: Clock): Boolean {
        if (!isPaused(clock) || steppingAll) return true
        val owed = steps[clock] ?: return false
        if (owed <= 1) steps.remove(clock) else steps[clock] = owed - 1
        return true
    }

    /** Whether [clock] will move on the next frame, which is what a test waiting for it needs. */
    internal fun willMove(clock: Clock): Boolean =
        !isPaused(clock) || stepsForAll > 0 || (steps[clock] ?: 0) > 0

    /**
     * [nanos] of real time as [clock]'s time, at its speed.
     *
     * The fraction of a nanosecond a slowed frame comes to is carried to the next frame rather than
     * dropped. Dropped, a third-speed clock loses a third of a nanosecond every frame and a slowed
     * animation lands measurably late; carried, it lands where it would at full speed, later.
     */
    internal fun scale(clock: Clock, nanos: Long): Long {
        val factor = speedOf(clock)
        if (factor == 1f) return nanos
        val exact = nanos * factor.toDouble() + (carry[clock] ?: 0.0)
        val whole = floor(exact)
        carry[clock] = exact - whole
        return whole.toLong()
    }

    private fun requireSpeed(speed: Float) =
        require(speed > 0f && speed.isFinite()) { "a clock's speed must be above zero, not $speed; use pause" }
}

/**
 * The debug controls on keys, for a game to hand its keyboard to while somebody is looking.
 *
 * [pauseKey] freezes and lets go, [stepKey] moves one frame each press and keeps stepping while
 * held, [slowerKey] and [fasterKey] halve and double the speed between an eighth and real time. The
 * function keys by default, because nothing a player types uses them.
 *
 * It is a [KeyHandler], so it goes either at the front of a game's own sink or on the screen's root:
 *
 * ```kotlin
 * val debugKeys = ClockDebugKeys(host.clocks)
 * override fun onKey(event: KeyEvent) = debugKeys.onKey(event) || router.onKey(event)
 * ```
 *
 * @param clock the one clock to control, or null for all of them. A world stepped through while the
 *   interface over it keeps animating is `ClockDebugKeys(clocks, Clock.World)`.
 */
class ClockDebugKeys(
    private val clocks: Clocks,
    val clock: Clock? = null,
    val pauseKey: Key = Key.F5,
    val stepKey: Key = Key.F6,
    val slowerKey: Key = Key.F7,
    val fasterKey: Key = Key.F8,
) : KeyHandler {

    override fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down) return false
        val debug = clocks.debug
        when (event.key) {
            pauseKey -> {
                // A held key repeating would flick the pause on and off as fast as the platform
                // repeats; only a fresh press toggles.
                if (event.repeat) return true
                val frozen = if (clock == null) debug.isPaused else debug.isPaused(clock)
                if (frozen) debug.resume(clock) else debug.pause(clock)
            }
            stepKey -> debug.step(frames = 1, clock = clock)
            // Only ever in the direction pressed: a speed set in code outside the keys' range — a
            // tenth, or double — is left where it is rather than dragged back the wrong way.
            slowerKey -> currentSpeed().let { if (it > Slowest) setSpeed((it / 2f).coerceAtLeast(Slowest)) }
            fasterKey -> currentSpeed().let { if (it < 1f) setSpeed((it * 2f).coerceAtMost(1f)) }
            else -> return false
        }
        return true
    }

    private fun currentSpeed(): Float = if (clock == null) clocks.debug.speed else clocks.debug.ownSpeedOf(clock)

    private fun setSpeed(speed: Float) {
        if (clock == null) clocks.debug.speed = speed else clocks.debug.setSpeed(clock, speed)
    }

    private companion object {
        /** An eighth. Slower than that, a frame-by-frame [ClockDebug.step] is the better tool. */
        const val Slowest = 0.125f
    }
}
