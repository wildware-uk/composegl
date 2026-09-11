package composegl.ui.graphics

import kotlin.jvm.JvmInline

/**
 * A colour, packed as `0xAARRGGBB`.
 *
 * A value class rather than four floats, because an interface holds thousands of them and every
 * one of them is compared on every recomposition to decide whether anything changed.
 */
@JvmInline
value class Colour(val argb: Int) {

    constructor(alpha: Int, red: Int, green: Int, blue: Int) : this(
        (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF),
    )

    val alpha: Int get() = argb ushr 24 and 0xFF
    val red: Int get() = argb ushr 16 and 0xFF
    val green: Int get() = argb ushr 8 and 0xFF
    val blue: Int get() = argb and 0xFF

    val alphaFraction: Float get() = alpha / 255f
    val isTransparent: Boolean get() = alpha == 0

    /** The same colour at a different opacity. Multiplies, so it composes down a tree of alphas. */
    fun scaleAlpha(factor: Float): Colour =
        Colour((alpha * factor.coerceIn(0f, 1f)).toInt(), red, green, blue)

    fun withAlpha(alpha: Int): Colour = Colour(alpha, red, green, blue)

    /** Mixes towards [other]. `fraction` of 0 is this colour, 1 is the other. */
    fun lerp(other: Colour, fraction: Float): Colour {
        val t = fraction.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt()
        return Colour(mix(alpha, other.alpha), mix(red, other.red), mix(green, other.green), mix(blue, other.blue))
    }

    override fun toString(): String =
        "Colour(#${argb.toUInt().toString(16).uppercase().padStart(8, '0')})"

    companion object {
        val Transparent = Colour(0x00000000)
        val Black = Colour(0xFF000000.toInt())
        val White = Colour(0xFFFFFFFF.toInt())

        /** From `0xAARRGGBB`, which is how a colour is written in source. */
        fun argb(value: Long) = Colour(value.toInt())

        /** From `0xRRGGBB`, fully opaque. */
        fun rgb(value: Long) = Colour(0xFF000000.toInt() or value.toInt())
    }
}
