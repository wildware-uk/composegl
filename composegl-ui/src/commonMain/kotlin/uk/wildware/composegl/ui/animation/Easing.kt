package uk.wildware.composegl.ui.animation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shape of a movement: in at 0, out at 1, and whatever it likes in between.
 *
 * An easing may overshoot past 1 and come back — that is what makes a menu feel like a menu rather
 * than a spreadsheet — so nothing here clamps the value it returns.
 */
fun interface Easing {
    fun transform(fraction: Float): Float
}

/**
 * The standard set, plus the two a game actually reaches for.
 *
 * The four at the top are the ones every toolkit has. [Overshoot] and [Bounce] are the ones a game
 * uses on the things a player pokes: a panel that slides in and settles, a button that lands.
 */
object Easings {

    /** No shape at all. For anything that is a measurement rather than a movement: a timer bar. */
    val Linear = Easing { it }

    /** Starts slow. Something leaving the screen. */
    val EaseIn: Easing = CubicBezier(0.42f, 0f, 1f, 1f)

    /** Ends slow. Something arriving, which is most things. */
    val EaseOut: Easing = CubicBezier(0f, 0f, 0.58f, 1f)

    val EaseInOut: Easing = CubicBezier(0.42f, 0f, 0.58f, 1f)

    /** Goes past the target and comes back. A panel that lands rather than stops. */
    val Overshoot: Easing = Easing { fraction ->
        val t = fraction - 1f
        t * t * ((OvershootTension + 1f) * t + OvershootTension) + 1f
    }

    /** Lands, bounces twice, settles. */
    val Bounce = Easing { fraction ->
        when {
            fraction < 1f / 2.75f -> 7.5625f * fraction * fraction
            fraction < 2f / 2.75f -> {
                val t = fraction - 1.5f / 2.75f
                7.5625f * t * t + 0.75f
            }
            fraction < 2.5f / 2.75f -> {
                val t = fraction - 2.25f / 2.75f
                7.5625f * t * t + 0.9375f
            }
            else -> {
                val t = fraction - 2.625f / 2.75f
                7.5625f * t * t + 0.984375f
            }
        }
    }

    /** Slow in, slow out, on a sine curve. Gentler than [EaseInOut]; good for a breathing glow. */
    val Sine = Easing { fraction -> (-(cos(PI * fraction) - 1f) / 2f) }

    /** For a value that jumps at the end rather than moving: a caret, a flag. */
    val Step = Easing { fraction -> if (fraction < 1f) 0f else 1f }

    private const val OvershootTension = 1.70158f
    private const val PI = 3.1415927f
}

/**
 * The curve every design tool exports: two control points on a unit square.
 *
 * Solving it is Newton's method with a bisection fallback, which is what every other
 * implementation of this does and for the same reason — the derivative goes to zero on the
 * flat-start curves designers like, and Newton alone wanders off when it does.
 */
class CubicBezier(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) : Easing {

    init {
        require(x1 in 0f..1f && x2 in 0f..1f) { "a bezier easing's control points must be in 0..1" }
    }

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f || fraction >= 1f) return fraction
        return curve(solveT(fraction), y1, y2)
    }

    private fun solveT(x: Float): Float {
        var t = x
        repeat(NewtonSteps) {
            val error = curve(t, x1, x2) - x
            if (abs(error) < Precision) return t
            val slope = slope(t, x1, x2)
            if (abs(slope) < Precision) return bisect(x)
            t -= error / slope
        }
        return bisect(x)
    }

    private fun bisect(x: Float): Float {
        var low = 0f
        var high = 1f
        var t = x
        repeat(BisectSteps) {
            t = (low + high) / 2f
            val error = curve(t, x1, x2) - x
            if (abs(error) < Precision) return t
            if (error > 0f) high = t else low = t
        }
        return t
    }

    private companion object {

        const val NewtonSteps = 8
        const val BisectSteps = 24
        const val Precision = 1e-5f

        /** The cubic with its first and last control points pinned to 0 and 1. */
        fun curve(t: Float, a: Float, b: Float): Float {
            val inverse = 1f - t
            return 3f * inverse * inverse * t * a + 3f * inverse * t * t * b + t * t * t
        }

        fun slope(t: Float, a: Float, b: Float): Float {
            val inverse = 1f - t
            return 3f * inverse * inverse * a +
                6f * inverse * t * (b - a) +
                3f * t * t * (1f - b)
        }
    }
}
