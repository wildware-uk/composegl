package dev.wildware.composegl.ui.text

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The size text at [size] is drawn at under a text scale of [scale]: the product, to the nearest
 * whole size, and never less than one.
 *
 * The one rule [TextStyle.scaled] and [scaledTextSizes] share, so the sizes a game registers are
 * exactly the sizes the widgets go on to ask for.
 */
fun scaledSize(size: Float, scale: Float): Float =
    if (isUnscaled(scale)) size else (size * scale).roundToInt().coerceAtLeast(1).toFloat()

/**
 * Whether [scale] is one, give or take the float error of multiplying nested scales together.
 *
 * 1.15 inside 1/1.15 is 0.99999994, not one, and text at 13.5 would otherwise be rounded to 14 by
 * two scales that were meant to cancel out.
 */
internal fun isUnscaled(scale: Float): Boolean = abs(scale - 1f) < 1e-4f

/**
 * Every whole font size a game needs so that each of [sizes] can be drawn at each of [scales].
 *
 * A backend bakes fonts one size at a time, at startup, and refuses a size it was never given —
 * so a text-size setting that offers 100%, 125% and 150% needs 16, 20 and 24 registered for
 * sixteen-unit text before the player ever opens the menu:
 *
 * ```kotlin
 * val sizes = scaledTextSizes(listOf(13, 16, 20), listOf(1f, 1.25f, 1.5f))
 * fonts.registerTrueType("body", file, sizes)
 * ```
 *
 * Sorted, with no size twice.
 */
fun scaledTextSizes(sizes: Iterable<Int>, scales: Iterable<Float>): List<Int> =
    sizes.flatMap { size ->
        scales.map { scale ->
            require(scale > 0f && scale.isFinite()) { "a text scale must be positive, was $scale" }
            scaledSize(size.toFloat(), scale).toInt()
        }
    }.distinct().sorted()
