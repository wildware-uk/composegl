package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.paragraph
import korlibs.image.color.Colors
import korlibs.image.font.Font
import korlibs.image.font.TtfFont
import korlibs.image.font.renderGlyphToBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One glyph as it was placed by measuring: a quad in the layout's own coordinates, y down from its
 * top-left, and where on the atlas its picture is.
 */
class PlacedGlyph internal constructor(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val region: KorgeAtlas.Region,
)

/**
 * A measured string, with everything the canvas needs to draw exactly what was measured.
 *
 * The toolkit only ever looks at the four properties [TextLayout] declares. The canvas casts to this
 * and uses the rest, which is the seam that keeps KorGE out of the toolkit while letting the backend
 * draw a label as a run of quads out of a texture that is already bound.
 */
class KorgeTextLayout internal constructor(
    override val text: String,
    override val size: Size,
    override val lineCount: Int,
    override val firstBaseline: Float,
    val style: TextStyle,
    /** Every visible glyph, in drawing order. Spaces and characters with no picture are not here. */
    val glyphs: List<PlacedGlyph>,
    /** Where [glyphs] were packed, so the canvas draws from the right page. */
    val atlas: KorgeAtlas,
) : TextLayout {
    override fun toString() = "KorgeTextLayout(\"$text\", $size, $lineCount lines)"
}

/**
 * Fonts, by name.
 *
 * A game registers what it wants at startup and the toolkit never touches a file: it asks for "body
 * at 16" and gets whatever this was told that means. Two weights are two names.
 *
 * Sizes are registered one by one, as the LibGDX backend's are, and asking for a size nobody
 * registered is an error that names the sizes that exist — a label appearing at a size nobody
 * anticipated is a mistake worth hearing about, not something to paper over with a blurry scale.
 * Glyphs, on the other hand, are made the first time text asks for them: KorGE reads the `.ttf` in
 * plain Kotlin and rasterises a glyph on the CPU, so a character first seen in a player's name costs a
 * fraction of a millisecond, needs no OpenGL, and goes onto the shared [atlas] page.
 *
 * Every size of every family goes onto the same page, along with the white block the canvas draws
 * solid colour from, so a screen of panels and labels is one texture and, barring a clip, one draw
 * call.
 *
 * Wrapping is the toolkit's own [paragraph] — the same breaking rules `Text` uses for styled text,
 * including breaking between Chinese and Japanese characters — so a label measured here breaks
 * where the toolkit would break it. Fallback fonts and emoji come next and will be packed into the
 * same atlas; see docs/superpowers/specs/2026-09-15-korge-backend.md.
 *
 * @param atlas where glyphs are packed. Share one with [KorgeCanvas] so text and boxes are one texture.
 */
class KorgeFonts(val atlas: KorgeAtlas = KorgeAtlas()) : FontProvider {

    private data class Key(val family: String, val size: Int)

    /** A glyph at one size: how far it moves the pen and, if it draws anything, where its picture is. */
    private class Glyph(
        val advance: Float,
        val left: Float,
        val top: Float,
        val region: KorgeAtlas.Region?,
    )

    /** One font at one size, and every glyph asked of it so far. */
    private inner class Face(val font: Font, val size: Int) {

        private val korge = font.getFontMetrics(size.toDouble())

        /** Baseline up to the top of the tallest glyph, as the toolkit counts it: positive. */
        val ascent = korge.ascent.toFloat()

        /** Baseline down to the bottom of the lowest glyph, also positive. */
        val descent = -korge.descent.toFloat()

        private val glyphs = HashMap<Int, Glyph>()

        fun glyph(codepoint: Int): Glyph = glyphs.getOrPut(codepoint) { make(codepoint) }

        val capHeight: Float = font.getGlyphMetrics(size.toDouble(), 'H'.code).let { metrics ->
            if (metrics.existing && metrics.height > 0.0) metrics.height.toFloat() else size * 0.7f
        }

        val spaceAdvance: Float get() = glyph(' '.code).advance

        private fun make(codepoint: Int): Glyph {
            if (codepoint in Invisible) return Glyph(0f, 0f, 0f, null)
            val metrics = font.getGlyphMetrics(size.toDouble(), codepoint)
            // A character the font does not have comes out as its `?`, which is at least readable,
            // until fallback fonts exist to ask instead.
            if (!metrics.existing && codepoint != '?'.code) return glyph('?'.code)
            val advance = metrics.xadvance.toFloat()
            if (metrics.width <= 0.0 || metrics.height <= 0.0) return Glyph(advance, 0f, 0f, null)

            // KorGE draws the glyph into a bitmap a border's width larger than its bounds each way,
            // with the baseline `height + top` down from the bounds' top edge. Copying the whole
            // bitmap keeps the soft edge; the offsets below put it back where the pen is.
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
    }

    private val fonts = LinkedHashMap<String, Font>()
    private val sizes = LinkedHashMap<String, List<Int>>()
    private val faces = HashMap<Key, Face>()

    /**
     * Registers [family] from the bytes of a `.ttf` or `.otf`, at each of [sizes].
     *
     * Reads the file here, once. Nothing is rasterised until text asks for it, and nothing needs an
     * OpenGL context, so this can run on a loading thread.
     */
    fun registerTrueType(family: String, bytes: ByteArray, sizes: List<Int>) =
        register(family, TtfFont(bytes, extName = family), sizes)

    /**
     * Registers a font the game already has — one it loaded with `readTtfFont`, say — under [family]
     * at each of [sizes].
     */
    fun register(family: String, font: Font, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        fonts[family] = font
        this.sizes[family] = sizes.distinct().sorted()
        faces.keys.removeAll { it.family == family }
    }

    /** The families that were registered. */
    fun families(): List<String> = fonts.keys.toList()

    /** The sizes [family] was registered at. Empty for a family nobody registered. */
    fun sizesOf(family: String): List<Int> = sizes[family].orEmpty()

    private fun faceFor(style: TextStyle): Face {
        val key = Key(style.family, style.size.roundToInt())
        faces[key]?.let { return it }
        val font = fonts[key.family]
        val registered = sizes[key.family].orEmpty()
        if (font == null) {
            error(
                "no font for ${key.family} at ${key.size}: no font is registered under that name. " +
                    "Registered names: ${families().ifEmpty { "none" }}",
            )
        }
        if (key.size !in registered) error("no font for ${key.family} at ${key.size}: that name is registered at $registered")
        return Face(font, key.size).also { faces[key] = it }
    }

    override fun metrics(style: TextStyle): FontMetrics {
        val face = faceFor(style)
        return FontMetrics(
            size = style.size,
            ascent = face.ascent,
            descent = face.descent,
            capHeight = face.capHeight,
            lineHeight = style.lineHeight,
            spaceAdvance = face.spaceAdvance,
        )
    }

    override fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout {
        val face = faceFor(style)
        val wrap = maxWidth.isFinite() && maxWidth > 0f
        val lines: List<String> = if (!wrap && '\n' !in text && style.maxLines <= 0) {
            listOf(text)
        } else {
            // The toolkit's own breaking, so a label wraps exactly where styled text would. It calls
            // back into this with single lines and no width, which is the branch above.
            val laidOut = paragraph(text, style, if (wrap) maxWidth else Float.POSITIVE_INFINITY)
            laidOut.lines.map { line ->
                val body = text.substring(line.range.min, line.range.max)
                if (line.ellipsised) body + style.ellipsis else body
            }
        }

        val placed = ArrayList<PlacedGlyph>()
        var widest = 0f
        lines.forEachIndexed { index, line ->
            widest = maxOf(widest, place(face, line, face.ascent + index * style.lineHeight, placed))
        }
        return KorgeTextLayout(
            text = text,
            // The style's line spacing rather than the font's own, so two labels in the same style line
            // up whether or not their glyphs happen to be tall.
            size = Size(ceil(widest), lines.size * style.lineHeight),
            lineCount = lines.size,
            firstBaseline = face.ascent,
            style = style,
            glyphs = placed,
            atlas = atlas,
        )
    }

    /** Lays one line out along [baseline], adding to [into], and answers how far the pen went. */
    private fun place(face: Face, line: String, baseline: Float, into: MutableList<PlacedGlyph>): Float {
        var pen = 0f
        var at = 0
        while (at < line.length) {
            val codepoint = line.codePointAt(at)
            at += Character.charCount(codepoint)
            val glyph = face.glyph(codepoint)
            val region = glyph.region
            if (region != null) {
                // Whole pixels: a glyph rasterised at one position and drawn half a pixel off is a
                // glyph with soft edges.
                into += PlacedGlyph(
                    left = floor(pen + glyph.left + 0.5f),
                    top = floor(baseline + glyph.top + 0.5f),
                    width = region.width.toFloat(),
                    height = region.height.toFloat(),
                    region = region,
                )
            }
            pen += glyph.advance
        }
        return pen
    }

    private companion object {
        /** Empty pixels KorGE leaves round a rendered glyph, so its antialiased edge is not cut off. */
        const val Border = 1

        /** The variation selectors: they choose how the character before them looks, and look like nothing. */
        val Invisible = setOf(0xFE0E, 0xFE0F)
    }
}
