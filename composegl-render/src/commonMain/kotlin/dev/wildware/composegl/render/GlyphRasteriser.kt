package dev.wildware.composegl.render

/**
 * What a backend supplies for text: one font at one size, turned into pictures one glyph at a time.
 *
 * Everything else about text — the atlas, fallback fonts, emoji pictures, wrapping, ellipsis and
 * metrics — is [AtlasFonts], shared by every backend. Each platform already has a good rasteriser
 * (stb_truetype, FreeType, Canvas2D, KorGE's own), so that is the one part that stays behind.
 */
fun interface GlyphRasteriser {

    /** [family] at [size], or null when this rasteriser has no such font. */
    fun face(family: String, size: Int): RasterFace?

    /**
     * [family], as it was registered at [size], drawn [pixels] tall: what a glyph is made from when
     * the screen is scaled up, so it gets a pixel of its own for every pixel it covers. Every
     * measurement comes out at [pixels]; only the pictures are used, since text is still laid out
     * at [size].
     *
     * The default asks [face] for [pixels], which suits a rasteriser that draws any size asked for.
     * One that only answers for the sizes it was registered at overrides this.
     */
    fun face(family: String, size: Int, pixels: Int): RasterFace? = face(family, pixels)
}

/** One font at one size. Every measurement is in pixels, from the baseline, down positive. */
interface RasterFace {

    /** Baseline to the top of the tallest glyph. */
    val ascent: Float

    /** Baseline to the bottom of the lowest glyph, positive. */
    val descent: Float

    /** Baseline to the top of a capital letter. */
    val capHeight: Float

    /** Whether this face draws [codepoint] itself. False sends the character to the fallbacks. */
    fun has(codepoint: Int): Boolean

    /** How far the pen moves after [codepoint]. No kerning: widths are sums of advances. */
    fun advance(codepoint: Int): Float

    /**
     * Draws [codepoint] into [into]. A glyph with no ink — a space — sets a zero width and still
     * answers true. False means it could not be drawn at all.
     */
    fun draw(codepoint: Int, into: GlyphBitmap): Boolean
}

/**
 * One glyph's picture, refilled for every glyph so rasterising costs no allocation per character.
 */
class GlyphBitmap {

    var width = 0
        private set
    var height = 0
        private set

    /** From the pen position to the left edge of the picture. */
    var xOffset = 0f

    /** From the baseline down to the top edge of the picture. Usually negative. */
    var yOffset = 0f

    var kind = GlyphKind.Coverage

    /** One byte a pixel for [GlyphKind.Coverage]; four, straight RGBA, for [GlyphKind.Colour]. Top row first. */
    var pixels = ByteArray(0)
        private set

    /** Makes room for a [width] by [height] picture of [kind], zeroed. */
    fun resize(width: Int, height: Int, kind: GlyphKind) {
        this.width = width
        this.height = height
        this.kind = kind
        val bytes = width * height * (if (kind == GlyphKind.Colour) 4 else 1)
        if (pixels.size < bytes) pixels = ByteArray(bytes) else pixels.fill(0, 0, bytes)
    }
}

enum class GlyphKind {
    /** How much of each pixel the letter covers. Drawn in the text's colour. */
    Coverage,

    /** A picture in its own colours, an emoji. Drawn as it is, faded with the text. */
    Colour,
}

/** A decoded picture: straight RGBA, top row first. */
class RgbaImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "a picture cannot be ${width}x$height" }
        require(pixels.size >= width * height * 4) {
            "a ${width}x$height picture needs ${width * height * 4} bytes, got ${pixels.size}"
        }
    }
}

/** Turns an encoded picture — a PNG, say — into pixels. A backend supplies it. */
fun interface ImageDecoder {
    fun decode(encoded: ByteArray): RgbaImage
}
