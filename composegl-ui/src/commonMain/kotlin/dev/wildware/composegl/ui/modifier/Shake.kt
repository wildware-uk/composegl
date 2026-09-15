package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Offset
import kotlin.math.floor

/**
 * How shaken something is, and which way that moves it this frame.
 *
 * Wrong password, not enough gold, taking a hit: the game calls [trigger] and the widget carrying
 * [Modifier.shake] jolts and settles on its own. Nothing has to turn it off.
 *
 * It is **trauma**-based, the way game cameras shake. A knock adds to [trauma], which is capped at
 * one and drains at [decayPerSecond]. The movement is trauma *squared* times a smooth noise, so a
 * light knock is a light wobble rather than a smaller copy of a heavy one, and a heavy one eases out
 * instead of stopping dead. Two knocks in a row add up; a third on top of a full shake changes
 * nothing, which is what stops a player mashing a locked door from shaking the panel off the screen.
 *
 * The noise is worked out from the clock's time and [seed] alone — no randomness — so a shake is
 * the same every time it is run, and a test can say exactly where the widget was.
 *
 * It runs on one named clock. A hit on the player belongs to [Clock.World] and freezes with the
 * pause menu; a wrong password on that menu belongs to [Clock.Ui], the default, and does not.
 *
 * @param maxOffset how far, in each direction, a full shake can move the widget.
 * @param frequency how many times a second the wobble changes direction, roughly.
 * @param decayPerSecond how much trauma drains in a second: at the default, a full knock is still
 *   in two thirds of a second.
 * @param seed which of the possible wobbles this one is. Give two widgets shaking side by side
 *   different seeds, or they move in step and read as one thing.
 */
class ShakeState(
    val clock: Clock = Clock.Ui,
    private val clocks: Clocks = Clocks(),
    val maxOffset: Float = 12f,
    val frequency: Float = 15f,
    val decayPerSecond: Float = 1.5f,
    val seed: Int = 0,
) {

    init {
        require(maxOffset >= 0f) { "a shake cannot reach a negative distance, was $maxOffset" }
        require(frequency > 0f) { "a shake's frequency must be positive, was $frequency" }
        require(decayPerSecond > 0f) { "a shake has to die away, so decay must be positive, was $decayPerSecond" }
    }

    /** How shaken it is, from 0 for still to 1 for as hard as it goes. */
    var trauma: Float by mutableStateOf(0f)
        private set

    /**
     * Where the widget is moved to this frame. Snapshot state: reading it in a composable — which
     * is what [Modifier.shake] does — is what redraws the frame.
     */
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** How many knocks there have been. Watched by [rememberShake] so a knock wakes a still shake. */
    internal var knocks: Int by mutableStateOf(0)
        private set

    /**
     * Knocks it. [intensity] is added to [trauma], which stops at one.
     *
     * One is the heaviest knock. A half is a quarter of the movement, because movement is trauma
     * squared, and a tenth barely moves at all — a good size for a step on a creaky floorboard.
     */
    fun trigger(intensity: Float = 1f) {
        require(!intensity.isNaN()) { "a knock cannot be NaN" }
        require(intensity >= 0f) { "a knock cannot be negative, was $intensity" }
        if (intensity == 0f) return
        trauma = (trauma + intensity).coerceAtMost(1f)
        knocks++
    }

    /** Stops the shake now and puts the widget back where layout put it. */
    fun stop() {
        trauma = 0f
        offset = Offset.Zero
    }

    /**
     * Where a shake with [trauma] is at [nanos] of its clock's time.
     *
     * Two smooth noises, one per axis, each between -1 and 1, scaled by [maxOffset] and trauma
     * squared. Smooth because the noise is interpolated between fixed points [frequency] times a
     * second rather than picked afresh each frame — a shake that picks a new random place every
     * frame is a blur at sixty frames a second and a strobe at thirty.
     */
    internal fun offsetAt(trauma: Float, nanos: Long): Offset {
        if (trauma <= 0f || maxOffset == 0f) return Offset.Zero
        val reach = maxOffset * trauma * trauma
        val t = nanos / 1_000_000_000.0 * frequency
        return Offset(noise(t, 0) * reach, noise(t, 1) * reach)
    }

    /**
     * Drains the trauma a frame at a time and moves the widget, until it is still.
     *
     * The only subscription to frames a shake has, and it ends when the trauma does: a shake that
     * has settled costs nothing. On a stopped clock no time passes, so nothing drains and nothing
     * moves, and writing the same values as last frame redraws nothing.
     */
    internal suspend fun run() {
        clocks.register(clock)
        var last = clocks.time(clock)
        // Counted as playing, like any animation, so something waiting for the screen to come to
        // rest — a test harness — waits out the tail of the shake, where it moves by less than a
        // pixel, rather than looking while the panel is still a hair out of its slot.
        clocks.began(clock)
        try {
            while (trauma > 0f) {
                withFrameNanos { }
                val now = clocks.time(clock)
                val seconds = (now - last) / 1_000_000_000f
                last = now
                trauma = (trauma - decayPerSecond * seconds).coerceAtLeast(0f)
                offset = offsetAt(trauma, now)
            }
            offset = Offset.Zero
        } finally {
            clocks.ended(clock)
        }
    }

    /** Value noise: a fixed pseudo-random height at each whole [t], eased smoothly between them. */
    private fun noise(t: Double, axis: Int): Float {
        val whole = floor(t)
        val step = whole.toLong()
        val f = (t - whole).toFloat()
        val eased = f * f * (3f - 2f * f)
        val a = height(step, axis)
        val b = height(step + 1, axis)
        return a + (b - a) * eased
    }

    /** A height between -1 and 1 for one point of the noise, the same every time it is asked. */
    private fun height(step: Long, axis: Int): Float {
        var h = (step * 374_761_393L + axis * 668_265_263L + seed * 1_274_126_177L).toInt()
        h = (h xor (h ushr 13)) * 1_274_126_177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32_767.5f - 1f
    }
}

/**
 * A [ShakeState] that lives as long as the screen it is on, running on the host's clocks.
 *
 * ```kotlin
 * val shake = rememberShake()
 * Panel(Modifier.shake(shake)) { … }
 * // …and when the password is wrong
 * shake.trigger()
 * ```
 */
@Composable
fun rememberShake(
    clock: Clock = Clock.Ui,
    maxOffset: Float = 12f,
    frequency: Float = 15f,
    decayPerSecond: Float = 1.5f,
    seed: Int = 0,
): ShakeState {
    val clocks = LocalClocks.current
    val shake = remember(clocks, clock, maxOffset, frequency, decayPerSecond, seed) {
        ShakeState(clock, clocks, maxOffset, frequency, decayPerSecond, seed)
    }
    // Keyed on the knock count, so a knock on a shake that has already gone still starts it again.
    // A knock while it is still moving restarts the loop too, which carries on from the trauma it
    // had rather than from the beginning.
    LaunchedEffect(shake, shake.knocks) {
        if (shake.trauma > 0f) shake.run()
    }
    return shake
}

/**
 * Moves this widget about while [shake] is shaken, without moving anything else.
 *
 * It is an [offset], so layout does not change: the neighbours stay where they are, the parent does
 * not grow, and a still shake adds nothing to a node but an offset of zero. What *does* move with
 * it is where it can be clicked, the same as any other offset.
 *
 * Put it before `background` and friends so the whole widget moves, or after them to shake the
 * contents inside a frame that stays put.
 *
 * The offset is read here, while composing, so every frame of a shake recomposes the scope that
 * calls this. Nothing for a panel, but keep a shaken widget in a small composable of its own rather
 * than at the top of a screen with a hundred widgets in it. A still shake costs nothing.
 */
fun Modifier.shake(shake: ShakeState): Modifier = offset(shake.offset.x, shake.offset.y)
