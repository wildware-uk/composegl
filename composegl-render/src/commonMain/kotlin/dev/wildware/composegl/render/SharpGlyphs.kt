package dev.wildware.composegl.render

import kotlin.math.roundToInt

/**
 * Where glyphs drawn onto a scaled-up screen are kept.
 *
 * Text is measured once, in design units, and its glyphs are made at the size the style asks for.
 * Drawn onto a screen at twice the design size, each of those glyphs would be stretched to twice
 * its pixels and come out soft. So a frame that is scaled up draws each glyph from a copy made at
 * the screen's own size instead — the font asked again at size × scale — in the same design-unit
 * place. Measuring never sees any of this: widths, line breaks and carets are exactly what they
 * were.
 *
 * A scale still moving is rounded ([stepOf]), so a window being dragged bigger makes a handful
 * of sizes rather than one a frame. The copies live on an atlas of their own, which is emptied at
 * the start of a frame once it has filled with sizes the screen has moved on from. A glyph with no
 * room is drawn from its ordinary copy, stretched, as before.
 */
internal class SharpGlyphs(private val pageSize: Int, private val smooth: Boolean, private val owner: String) {

    /** The atlas the copies are on, made the first time one is. */
    var atlas: GlyphAtlas? = null
        private set

    /** Goes up each time the atlas is emptied: a copy made in an earlier generation is gone. */
    var generation = 0
        private set

    private var full = false
    private var latest = 0
    private val steps = HashSet<Int>()

    /** Room for a copy made for [step], or null when there is none this generation. */
    fun place(step: Int, width: Int, height: Int): AtlasSpot? {
        latest = step
        if (width + GlyphAtlas.Gap > pageSize || height + GlyphAtlas.Gap > pageSize) return null
        val atlas = atlas ?: GlyphAtlas(pageSize, pageSize, MaxPages, owner, smooth).also { atlas = it }
        val spot = atlas.placeOrNull(width, height)
        if (spot == null) full = true else steps += step
        return spot
    }

    /**
     * Before a frame draws anything: a full atlas holding copies for a scale other than the latest is
     * emptied, so the screen's current size gets the room. One that is full of the current size alone
     * is left be, rather than emptied and refilled every frame.
     */
    fun beginFrame() {
        if (!full) return
        full = false
        val atlas = atlas ?: return
        if (steps.any { it != latest }) {
            atlas.clear()
            steps.clear()
            generation++
        }
    }

    fun close() {
        atlas?.close()
    }

    companion object {

        /** A scale of one, in steps: a step is a sixty-fourth, so a size lands within one percent. */
        const val One = 64

        /** The step a scale is snapped to while it is still moving: a quarter of the design size. */
        const val Coarse = One / 4

        /** The most a glyph is enlarged by: four times, past which it is stretched. */
        const val MaxStep = One * 4

        private const val MaxPages = 2

        /**
         * [scale] as a count of steps, at most [MaxStep].
         *
         * A [steady] scale — a window that is not being resized, a zoom that has stopped — is taken
         * exactly, so a glyph is made at the pixels it is drawn at. One that is still moving is
         * snapped to [Coarse], so a resize makes a handful of sizes rather than one a frame.
         */
        fun stepOf(scale: Float, steady: Boolean = false): Int {
            if (!scale.isFinite()) return One
            val exact = (scale * One).roundToInt()
            val step = if (steady) exact else (exact + Coarse / 2) / Coarse * Coarse
            return step.coerceIn(0, MaxStep)
        }

        /** How many pixels tall a glyph of [size] is made for [step]. */
        fun pixelsFor(size: Int, step: Int): Int = (size * step / One.toFloat()).roundToInt()
    }
}
