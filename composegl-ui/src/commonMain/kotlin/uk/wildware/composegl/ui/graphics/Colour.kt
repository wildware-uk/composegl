package uk.wildware.composegl.ui.graphics

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

    /**
     * This colour seen through [tint]: every channel multiplied, white leaving it alone.
     *
     * The same arithmetic the GPU does to a tinted texture, so a flat box and a piece of art tinted
     * the same way come out the same colour. A skin that dims a disabled state relies on that.
     */
    fun modulate(tint: Colour): Colour {
        if (tint.argb == White.argb) return this
        fun mix(a: Int, b: Int) = a * b / 255
        return Colour(mix(alpha, tint.alpha), mix(red, tint.red), mix(green, tint.green), mix(blue, tint.blue))
    }

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

        // The named ones, and what they are for.
        //
        // An interface's real colours belong in its skin, where one edit changes every panel in
        // the game. These are for the other kind of colour: a debug box round a hitbox, an
        // example in the documentation, a prototype nobody has skinned yet, a health bar that is
        // red because red is what it means.
        //
        // Deliberately a dozen rather than a system. There is no Red500 and no onSurfaceVariant,
        // because the moment there is, somebody builds an interface out of them instead of out of
        // a skin, and then the game cannot be reskinned.
        //
        // Plain, fully saturated values at the sRGB corners, plus three greys. They are meant to
        // be recognisable, not tasteful.

        val Red = Colour(0xFFFF0000.toInt())
        val Green = Colour(0xFF00FF00.toInt())
        val Blue = Colour(0xFF0000FF.toInt())
        val Yellow = Colour(0xFFFFFF00.toInt())
        val Cyan = Colour(0xFF00FFFF.toInt())
        val Magenta = Colour(0xFFFF00FF.toInt())
        val Orange = Colour(0xFFFF8000.toInt())

        val DarkGrey = Colour(0xFF404040.toInt())
        val Grey = Colour(0xFF808080.toInt())
        val LightGrey = Colour(0xFFC0C0C0.toInt())

        /** From `0xAARRGGBB`, which is how a colour is written in source. */
        fun argb(value: Long) = Colour(value.toInt())

        /** From `0xRRGGBB`, fully opaque. */
        fun rgb(value: Long) = Colour(0xFF000000.toInt() or value.toInt())
    }
}
