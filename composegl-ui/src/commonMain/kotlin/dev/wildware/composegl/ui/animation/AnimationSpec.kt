package dev.wildware.composegl.ui.animation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a value is, and how fast, part-way through an animation.
 *
 * One number rather than a whole vector: a colour is animated as four of these with the same spec,
 * which is both simpler and what every other toolkit does.
 */
data class Motion(val value: Float, val velocity: Float)

/**
 * How a value gets from one number to another.
 *
 * Asked, rather than stepped. Everything here is a function of the time since the animation started,
 * so a dropped frame, a frame that took 300ms, and a clock that was paused for a minute all come
 * out at the right place instead of drifting — which is what stepping a simulation by whatever the
 * last frame happened to be gets you.
 */
interface AnimationSpec {

    /**
     * Where the value is [playTimeNanos] after the animation started.
     *
     * @param initialVelocity how fast it was moving when it started, which is what makes retargeting
     *   mid-flight continuous rather than a jump.
     */
    fun at(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Motion

    /** Whether it has arrived and stopped moving, and can therefore stop asking for frames. */
    fun isFinished(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Boolean
}

/**
 * A duration and a curve. What most interface movement is.
 *
 * @param durationMillis how long it takes, not counting [delayMillis].
 * @param delayMillis how long before it starts. The value does not move during it.
 */
class Tween(
    val durationMillis: Int = DefaultDurationMillis,
    val delayMillis: Int = 0,
    val easing: Easing = Easings.EaseOut,
) : AnimationSpec {

    init {
        require(durationMillis >= 0) { "a tween cannot last for less than no time" }
        require(delayMillis >= 0) { "a tween cannot start before it is asked to" }
    }

    override fun at(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Motion {
        val fraction = fractionAt(playTimeNanos)
        val value = from + (to - from) * easing.transform(fraction)
        return Motion(value, velocityAt(from, to, playTimeNanos))
    }

    override fun isFinished(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Boolean =
        playTimeNanos >= totalNanos

    /**
     * Speed, as the slope of the curve here.
     *
     * Measured across a millisecond rather than solved, because an easing is an arbitrary function
     * and a game may hand us its own. It only has to be good enough to hand to the next animation
     * when a target changes mid-flight, and for that it is.
     */
    private fun velocityAt(from: Float, to: Float, playTimeNanos: Long): Float {
        if (durationMillis == 0) return 0f
        val step = 1_000_000L
        val before = (playTimeNanos - step).coerceAtLeast(0L)
        val after = (playTimeNanos + step).coerceAtMost(totalNanos)
        if (after <= before) return 0f
        val startValue = from + (to - from) * easing.transform(fractionAt(before))
        val endValue = from + (to - from) * easing.transform(fractionAt(after))
        return (endValue - startValue) / ((after - before) / 1_000_000_000f)
    }

    private fun fractionAt(playTimeNanos: Long): Float {
        if (durationMillis == 0) return 1f
        val afterDelay = playTimeNanos - delayMillis * 1_000_000L
        if (afterDelay <= 0L) return 0f
        return (afterDelay / (durationMillis * 1_000_000f)).coerceIn(0f, 1f)
    }

    private val totalNanos: Long get() = (durationMillis + delayMillis) * 1_000_000L

    companion object {
        /** Long enough to be seen, short enough not to be waited for. */
        const val DefaultDurationMillis = 200
    }
}

/**
 * No duration: the value is simply there.
 *
 * Worth having as a spec rather than as a special case, because it lets a game turn animation off
 * — an accessibility setting, a fast-forward — by changing one value rather than every call site.
 */
class Snap(val delayMillis: Int = 0) : AnimationSpec {

    override fun at(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Motion =
        if (playTimeNanos < delayMillis * 1_000_000L) Motion(from, 0f) else Motion(to, 0f)

    override fun isFinished(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Boolean =
        playTimeNanos >= delayMillis * 1_000_000L
}

/**
 * A weight on a spring, pulled towards the target.
 *
 * What a game wants for anything a player is pushing around, because it has no duration: a target
 * that changes mid-flight is simply a new target, and the weight carries its speed into it. A tween
 * has to decide what to do about the time it had left, and every answer to that looks wrong.
 *
 * Solved in closed form rather than stepped. A spring stepped by whatever the last frame happened
 * to be explodes on a frame that took a quarter of a second, which on a game's loading screen is
 * not a rare event.
 *
 * @param damping how much it fights being moved. 1 arrives without overshooting; below that it
 *   wobbles; above it crawls in.
 * @param stiffness how hard it pulls. Higher is faster and snappier.
 * @param threshold how close counts as arrived, in the units being animated. The default suits a
 *   fraction from 0 to 1; a spring on a pixel position wants a bigger one.
 */
class Spring(
    val damping: Float = NoWobble,
    val stiffness: Float = Medium,
    val threshold: Float = 0.001f,
) : AnimationSpec {

    init {
        require(damping >= 0f) { "a spring's damping cannot be negative" }
        require(stiffness > 0f) { "a spring with no stiffness never arrives" }
    }

    private val naturalFrequency = sqrt(stiffness)

    override fun at(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Motion {
        val t = playTimeNanos / 1_000_000_000.0
        val offset = (from - to).toDouble()
        val v0 = initialVelocity.toDouble()
        val w = naturalFrequency.toDouble()
        val zeta = damping.toDouble()

        val (position, velocity) = when {
            zeta < 1.0 -> {
                val wd = w * sqrt(1.0 - zeta * zeta)
                val r = -zeta * w
                val a = offset
                val b = (v0 - r * offset) / wd
                val decay = exp(r * t)
                val cosine = cos(wd * t)
                val sine = sin(wd * t)
                val x = decay * (a * cosine + b * sine)
                val dx = decay * ((a * r + b * wd) * cosine + (b * r - a * wd) * sine)
                x to dx
            }
            zeta == 1.0 -> {
                val a = offset
                val b = v0 + w * offset
                val decay = exp(-w * t)
                val x = (a + b * t) * decay
                val dx = decay * (b - w * (a + b * t))
                x to dx
            }
            else -> {
                val root = w * sqrt(zeta * zeta - 1.0)
                val r1 = -w * zeta + root
                val r2 = -w * zeta - root
                val a = (v0 - r2 * offset) / (r1 - r2)
                val b = offset - a
                val x = a * exp(r1 * t) + b * exp(r2 * t)
                val dx = a * r1 * exp(r1 * t) + b * r2 * exp(r2 * t)
                x to dx
            }
        }

        return Motion((to + position).toFloat(), velocity.toFloat())
    }

    override fun isFinished(from: Float, to: Float, initialVelocity: Float, playTimeNanos: Long): Boolean {
        val (value, velocity) = at(from, to, initialVelocity, playTimeNanos)
        // Both, not either: a weight passing through the target at speed has not arrived, and one
        // that has stopped short of it at the top of a wobble has not either.
        return abs(value - to) < threshold && abs(velocity) < threshold * VelocityAllowance
    }

    companion object {

        /** Arrives and stops. What an interface usually wants. */
        const val NoWobble = 1f

        /** A little wobble at the end. For something a player threw. */
        const val Gentle = 0.75f

        /** An obvious wobble. Use on one thing at a time. */
        const val Bouncy = 0.5f

        const val Low = 200f
        const val Medium = 1500f
        const val High = 10_000f

        /**
         * How much faster than the position threshold the speed may be and still count as stopped.
         *
         * Speed is in units per second and the threshold is in units, so they are not the same
         * scale; this is the number that says a value within a thousandth of its target moving at
         * a hundredth of a unit a second has, for a player's purposes, arrived.
         */
        private const val VelocityAllowance = 10f
    }
}
