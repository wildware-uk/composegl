package dev.wildware.composegl.ui.graphics

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A colour as hue, saturation and value: the way a person picks one, rather than the way a screen
 * stores it.
 *
 * Red, green and blue are what a pixel is made of, but nobody thinks "a bit more green and less
 * red" when they want a warmer yellow. They think of which colour (the hue), how strong (the
 * saturation) and how bright (the value), and a [dev.wildware.composegl.ui.widget.ColourPicker] is
 * laid out along exactly those three.
 *
 * ```kotlin
 * val gold = Hsv(hue = 45f, saturation = 0.8f, value = 1f).toColour()
 * val darker = Hsv.of(gold).copy(value = 0.6f).toColour()
 * ```
 *
 * Going to a [Colour] and back is not exact: a [Colour] has 256 steps a channel and this has many
 * more. And a grey has no hue at all, and black no saturation either, which is why [of] takes the
 * ones to keep when the colour cannot say.
 *
 * @param hue where round the colour wheel, in degrees: red at 0, green at 120, blue at 240, and 360
 *   back at red. Anything outside that goes round again.
 * @param saturation how strong, from 0 (grey) to 1 (as strong as it gets).
 * @param value how bright, from 0 (black) to 1 (as bright as it gets).
 * @param alpha how opaque, from 0 (not there) to 1 (solid).
 */
data class Hsv(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f,
) {

    /** The colour this is, rounded to the nearest of a [Colour]'s steps. */
    fun toColour(): Colour {
        val h = (((hue % 360f) + 360f) % 360f) / 60f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val chroma = v * s
        val second = chroma * (1f - abs(h % 2f - 1f))
        val r: Float
        val g: Float
        val b: Float
        when (h.toInt()) {
            0 -> { r = chroma; g = second; b = 0f }
            1 -> { r = second; g = chroma; b = 0f }
            2 -> { r = 0f; g = chroma; b = second }
            3 -> { r = 0f; g = second; b = chroma }
            4 -> { r = second; g = 0f; b = chroma }
            else -> { r = chroma; g = 0f; b = second }
        }
        val lift = v - chroma
        return Colour(byte(alpha), byte(r + lift), byte(g + lift), byte(b + lift))
    }

    companion object {

        /**
         * [colour] as hue, saturation and value.
         *
         * @param hue the hue to give a grey, which has none of its own. A picker passes the hue it
         *   was on, so dragging a colour down to black and back up does not turn it red.
         * @param saturation the saturation to give black, which has none of its own either.
         */
        fun of(colour: Colour, hue: Float = 0f, saturation: Float = 0f): Hsv {
            val r = colour.red / 255f
            val g = colour.green / 255f
            val b = colour.blue / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val chroma = max - min
            val h = when {
                chroma == 0f -> hue
                max == r -> 60f * (((g - b) / chroma) % 6f)
                max == g -> 60f * ((b - r) / chroma + 2f)
                else -> 60f * ((r - g) / chroma + 4f)
            }
            val s = if (max == 0f) saturation else chroma / max
            return Hsv((h + 360f) % 360f, s, max, colour.alphaFraction)
        }

        private fun byte(fraction: Float) = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()
    }
}
