package dev.wildware.composegl.testing

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Whether two pictures are the same picture, by the rule every backend's goldens are held to.
 *
 * Common rather than beside the PNG code, so that the browser's WebGL backend is judged by exactly
 * the arithmetic the desktop backends are — a looser copy of it in one place would be a backend
 * quietly allowed to be worse.
 *
 * Tolerant on purpose. Two rasterisers disagree about the last bit of an antialiased edge, and a
 * golden that fails on a rounding difference is a golden that gets regenerated without being read,
 * which is worse than not having one. So a pixel counts as different only when a channel is off by
 * more than [ChannelTolerance], the picture fails only when more than [MaxDifferingFraction] of it
 * differs, and a wholesale shift too small to trip either is caught by [MaxMeanDifference].
 */
object GoldenRule {

    /** A channel off by this much or less is the two rasterisers disagreeing, not a change. */
    const val ChannelTolerance = 20

    /** How much of a picture may differ before it counts as a different picture. */
    const val MaxDifferingFraction = 0.01

    /** Catches a change that moved everything a little rather than something a lot. */
    const val MaxMeanDifference = 2.0
}

/** What [comparePictures] found. [difference] is a picture of where, in the same `0xRRGGBB` form. */
class PictureComparison(
    val differingFraction: Double,
    val meanDifference: Double,
    val difference: IntArray,
) {

    val passes: Boolean
        get() = differingFraction <= GoldenRule.MaxDifferingFraction &&
            meanDifference <= GoldenRule.MaxMeanDifference

    val summary: String
        get() = "${fixed(differingFraction * 100, 3)}% of pixels differ by more than " +
            "${GoldenRule.ChannelTolerance}, mean difference ${fixed(meanDifference, 2)}"
}

/**
 * Compares two pictures the same size, each `width * height` pixels of `0xRRGGBB`, top row first.
 *
 * Alpha is ignored: nothing reads the alpha of a frame that has already been drawn.
 */
fun comparePictures(width: Int, height: Int, expected: IntArray, actual: IntArray): PictureComparison {
    require(expected.size >= width * height && actual.size >= width * height) {
        "a ${width}x$height picture is ${width * height} pixels"
    }
    val difference = IntArray(width * height)
    var differing = 0L
    var total = 0L

    for (at in 0 until width * height) {
        val a = expected[at]
        val b = actual[at]
        val worst = maxOf(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )
        total += worst.toLong()
        difference[at] = if (worst > GoldenRule.ChannelTolerance) {
            differing++
            // Magenta, because nothing these scenes draw is magenta.
            0xFF00FF
        } else {
            // What was expected, dimmed, so the highlights have somewhere to sit.
            (a shr 2) and 0x3F3F3F
        }
    }

    val pixels = (width * height).toDouble()
    return PictureComparison(differing / pixels, total / pixels, difference)
}

/** [value] to [digits] places, without `String.format`, which only a JVM has. */
private fun fixed(value: Double, digits: Int): String {
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = (value * scale).roundToLong()
    val whole = scaled / scale
    val part = (scaled % scale).toString().padStart(digits, '0')
    return "$whole.$part"
}
