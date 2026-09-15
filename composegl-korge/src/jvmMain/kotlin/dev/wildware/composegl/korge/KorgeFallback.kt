package dev.wildware.composegl.korge

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.font.Font
import korlibs.image.font.renderGlyphToBitmap
import kotlin.math.roundToInt

/**
 * A glyph at one size: how far it moves the pen and, if it draws anything, where its picture is.
 *
 * [left] and [top] go from the pen on the baseline to the picture's top-left corner, so a glyph from
 * any font sits on whichever baseline it is placed on without being moved.
 */
internal class Glyph(
    val advance: Float,
    val left: Float,
    val top: Float,
    val region: KorgeAtlas.Region?,
    /**
     * True for a picture with colours of its own — an emoji — rather than a letter. A letter is white
     * coverage and takes the text's colour; a picture is drawn as it is.
     */
    val picture: Boolean = false,
) {
    companion object {
        val Nothing = Glyph(0f, 0f, 0f, null)
    }
}

/** One font at one size, and every glyph asked of it so far. */
internal class Face(private val font: Font, val size: Int, private val atlas: KorgeAtlas) {

    private val korge = font.getFontMetrics(size.toDouble())

    /** Baseline up to the top of the tallest glyph, as the toolkit counts it: positive. */
    val ascent = korge.ascent.toFloat()

    /** Baseline down to the bottom of the lowest glyph, also positive. */
    val descent = -korge.descent.toFloat()

    val capHeight: Float = font.getGlyphMetrics(size.toDouble(), 'H'.code).let { metrics ->
        if (metrics.existing && metrics.height > 0.0) metrics.height.toFloat() else size * 0.7f
    }

    private val glyphs = HashMap<Int, Glyph?>()

    /** [codepoint]'s glyph, or null when the font does not have it. Remembered either way. */
    fun glyph(codepoint: Int): Glyph? {
        if (glyphs.containsKey(codepoint)) return glyphs[codepoint]
        return make(codepoint).also { glyphs[codepoint] = it }
    }

    /** What a character nothing has comes out as: this font's `?`, which is at least readable. */
    val missing: Glyph get() = glyph('?'.code) ?: Glyph.Nothing

    private fun make(codepoint: Int): Glyph? {
        val metrics = font.getGlyphMetrics(size.toDouble(), codepoint)
        if (!metrics.existing) return null
        val advance = metrics.xadvance.toFloat()
        if (metrics.width <= 0.0 || metrics.height <= 0.0) return Glyph(advance, 0f, 0f, null)

        // KorGE draws the glyph into a bitmap a border's width larger than its bounds each way, with
        // the baseline `height + top` down from the bounds' top edge. Copying the whole bitmap keeps
        // the soft edge; the offsets below put it back where the pen is.
        val rendered = font.renderGlyphToBitmap(
            size.toDouble(), codepoint, paint = Colors.WHITE, fill = true, border = Border, nativeRendering = false,
        ).bmp
        val region = atlas.pack(rendered.width, rendered.height)
        atlas.putCoverage(region, rendered)
        return Glyph(
            advance = advance,
            left = metrics.left.toFloat() - Border,
            top = -(metrics.height + metrics.top).toFloat() - Border,
            region = region,
        )
    }

    private companion object {
        /** Empty pixels KorGE leaves round a rendered glyph, so its antialiased edge is not cut off. */
        const val Border = 1
    }
}

/** Where a family in a fallback chain gets its glyphs from. */
internal sealed interface GlyphSource {

    fun glyph(codepoint: Int): Glyph?

    /** A font registered under that name and size. */
    class OfFace(val face: Face) : GlyphSource {
        override fun glyph(codepoint: Int) = face.glyph(codepoint)
    }

    /** Pictures registered under that name and size, by codepoint, already packed. */
    class OfPictures(val glyphs: Map<Int, Glyph>) : GlyphSource {
        override fun glyph(codepoint: Int) = glyphs[codepoint]
    }
}

/**
 * One family with its fallbacks behind it: where each character of a piece of text comes from.
 *
 * The primary is asked first and each fallback after it, one character at a time, and the first that
 * has the character wins. Every metric is the primary's, so a line is as tall as it was before a
 * fallback existed. A glyph is measured from the baseline, so one borrowed from a font with a taller
 * ascent still sits on the primary's line without being moved.
 */
internal class Chain(val primary: Face, private val fallbacks: List<GlyphSource>) {

    private val resolved = HashMap<Int, Glyph>()

    fun glyph(codepoint: Int): Glyph = resolved.getOrPut(codepoint) { resolve(codepoint) }

    private fun resolve(codepoint: Int): Glyph {
        if (codepoint in Invisible) return Glyph.Nothing
        primary.glyph(codepoint)?.let { return it }
        for (source in fallbacks) source.glyph(codepoint)?.let { return it }
        return primary.missing
    }

    private companion object {
        /** The variation selectors: they choose how the character before them looks, and look like nothing. */
        val Invisible = setOf(0xFE0E, 0xFE0F)
    }
}

/** Pictures standing in for characters: scaling them to a text size and placing them on its baseline. */
internal object Pictures {

    /** How far below the baseline a picture's bottom edge sits, as a share of its height. */
    private const val Drop = 0.12f

    private const val VariationSelector = 0xFE0F

    /** The one character a picture key stands for. */
    fun codepointOf(text: String): Int {
        val codepoints = text.codePoints().toArray().let {
            if (it.size == 2 && it[1] == VariationSelector) intArrayOf(it[0]) else it
        }
        require(codepoints.size == 1) {
            "a picture stands for one character, and \"$text\" is ${codepoints.size}; " +
                "sequences joined into one emoji are not supported"
        }
        return codepoints[0]
    }

    /**
     * [picture] at [size] tall, keeping its shape, packed into [atlas] as a glyph measured from the
     * baseline — the same numbers the LibGDX and LWJGL backends use, so an emoji takes the same room
     * in all three.
     */
    fun pack(picture: Bitmap, size: Int, atlas: KorgeAtlas): Glyph {
        val source = picture.toBMP32IfRequired()
        val height = size
        val width = (source.width * size / source.height.toFloat()).roundToInt().coerceAtLeast(1)
        val scaled = scale(source, width, height)
        val region = atlas.pack(width, height)
        atlas.putPicture(region, scaled)
        val gap = (size / 16f).roundToInt().coerceAtLeast(1)
        return Glyph(
            advance = (width + gap * 2).toFloat(),
            left = gap.toFloat(),
            // Its bottom a little below the baseline, where an emoji font puts it: level with the
            // letters' descenders, sharing their middle.
            top = -(height - (size * Drop).roundToInt()).toFloat(),
            region = region,
            picture = true,
        )
    }

    /**
     * [picture] at [width] by [height], halved until it is close and then filtered the rest of the
     * way. A straight bilinear shrink from seventy-two pixels to sixteen skips most of the pixels and
     * leaves an emoji's outline full of holes. Worked on premultiplied values, so a transparent
     * pixel's colour never bleeds into the edge.
     */
    fun scale(picture: Bitmap32, width: Int, height: Int): Premultiplied {
        var current = Premultiplied.of(picture)
        while (current.width >= width * 2 && current.height >= height * 2) current = current.halved()
        return current.resized(width, height)
    }

    /** RGBA, premultiplied, one int per channel: simple to average. */
    class Premultiplied(val width: Int, val height: Int, val channels: IntArray) {

        operator fun get(x: Int, y: Int, channel: Int) = channels[(y * width + x) * 4 + channel]

        fun halved(): Premultiplied {
            val w = width / 2
            val h = height / 2
            val out = IntArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) {
                out[(y * w + x) * 4 + c] =
                    (this[x * 2, y * 2, c] + this[x * 2 + 1, y * 2, c] + this[x * 2, y * 2 + 1, c] + this[x * 2 + 1, y * 2 + 1, c] + 2) / 4
            }
            return Premultiplied(w, h, out)
        }

        fun resized(toWidth: Int, toHeight: Int): Premultiplied {
            if (toWidth == width && toHeight == height) return this
            val out = IntArray(toWidth * toHeight * 4)
            for (y in 0 until toHeight) {
                val sy = ((y + 0.5f) * height / toHeight - 0.5f).coerceIn(0f, (height - 1).toFloat())
                val y0 = sy.toInt()
                val y1 = minOf(y0 + 1, height - 1)
                val fy = sy - y0
                for (x in 0 until toWidth) {
                    val sx = ((x + 0.5f) * width / toWidth - 0.5f).coerceIn(0f, (width - 1).toFloat())
                    val x0 = sx.toInt()
                    val x1 = minOf(x0 + 1, width - 1)
                    val fx = sx - x0
                    for (c in 0 until 4) {
                        val top = this[x0, y0, c] * (1 - fx) + this[x1, y0, c] * fx
                        val bottom = this[x0, y1, c] * (1 - fx) + this[x1, y1, c] * fx
                        out[(y * toWidth + x) * 4 + c] = (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
                    }
                }
            }
            return Premultiplied(toWidth, toHeight, out)
        }

        companion object {
            fun of(bitmap: Bitmap32): Premultiplied {
                val out = IntArray(bitmap.width * bitmap.height * 4)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val raw = bitmap.getRgbaRaw(x, y)
                    val a = raw.a
                    val i = (y * bitmap.width + x) * 4
                    if (bitmap.premultiplied) {
                        out[i] = raw.r; out[i + 1] = raw.g; out[i + 2] = raw.b
                    } else {
                        out[i] = (raw.r * a + 127) / 255; out[i + 1] = (raw.g * a + 127) / 255; out[i + 2] = (raw.b * a + 127) / 255
                    }
                    out[i + 3] = a
                }
                return Premultiplied(bitmap.width, bitmap.height, out)
            }
        }
    }
}
