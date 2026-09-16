package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.paragraph
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One glyph, as it sits in the atlas and as it sits beside the one before it.
 *
 * [page] is null for a glyph with no ink, a space: it moves the pen and draws nothing.
 */
class Glyph internal constructor(
    val page: AtlasPage?,
    val x: Int,
    val y: Int,
    val width: Float,
    val height: Float,
    /** From the pen position to the left edge of the picture. */
    val xOffset: Float,
    /** From the baseline down to the top edge of the picture. Usually negative. */
    val yOffset: Float,
    /** How far the pen moves afterwards. */
    val advance: Float,
    /** True for a picture with colours of its own — an emoji — rather than a letter. */
    val colour: Boolean = false,
    /** The face that made it and the character it draws, for a copy made at a scaled-up size. */
    internal val face: AtlasFonts.Face? = null,
    internal val codepoint: Int = 0,
    /** How many of this glyph's pixels make one design unit: more than one for a copy made for a scaled-up screen. */
    internal val pixelsPerUnit: Float = 1f,
    /** A copy that is drawn into its original's box, as a picture is, rather than from its own offsets. */
    internal val fillsBox: Boolean = false,
) {
    private var sharpQuarter = 0
    private var sharpGeneration = -1
    private var sharpCopy: Glyph? = null

    /**
     * This glyph made again for a screen [quarter] quarters the design size, or null when it is drawn
     * as it is: no face to ask, no room, or no gain. The last answer is kept, so a frame after frame at
     * one scale asks nothing.
     */
    internal fun sharp(quarter: Int): Glyph? {
        val face = face ?: return null
        val generation = face.sharpGeneration
        if (quarter == sharpQuarter && generation == sharpGeneration) return sharpCopy
        val copy = face.sharp(codepoint, quarter)
        sharpQuarter = quarter
        sharpGeneration = face.sharpGeneration
        sharpCopy = copy
        return copy
    }
}

/**
 * A glyph placed by measuring, in the layout's own coordinates with y downwards from its top.
 *
 * [pen] and [baseline] are where it was placed from, before its box was snapped to whole units: a
 * copy of the glyph made for a scaled-up screen has offsets of its own and is placed from them.
 */
class PlacedGlyph(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val glyph: Glyph,
    val pen: Float = left - glyph.xOffset,
    val baseline: Float = top - glyph.yOffset,
)

/** Text measured by [AtlasFonts]. The toolkit sees the four properties; the canvas sees the rest. */
class AtlasTextLayout(
    override val text: String,
    override val size: Size,
    override val lineCount: Int,
    override val firstBaseline: Float,
    val placed: List<PlacedGlyph>,
) : TextLayout

/**
 * Text for every backend: registration by family and size, fallback fonts tried one character at a
 * time, colour pictures standing in for emoji, wrapping, ellipsis and metrics, all on one shared
 * [GlyphAtlas]. The only part a backend brings is its [rasteriser].
 *
 * **Characters a font does not have** come from the families named by [fallBackTo], tried in order.
 * A character none of them has comes out as the main font's `?`. The variation selectors are
 * invisible. **No kerning**: a width is the sum of the advances, which carets, bidi and typewriter
 * text rely on.
 *
 * Glyphs are made the first time they are asked for. A backend that wants every glyph made up front
 * — so that nothing is rasterised mid-frame — overrides [prepare].
 *
 * @param decoder turns the encoded pictures [registerPictures] takes into pixels.
 * @param lineBreaking how text given a width is broken into lines and cut to a line limit.
 * @param wholePixelWidths whether a layout's width is rounded up to a whole pixel.
 * @param smoothPages whether the atlas's pages are sampled smoothly; see [GlyphAtlas].
 */
open class AtlasFonts(
    private val rasteriser: GlyphRasteriser,
    private val decoder: ImageDecoder? = null,
    pageSize: Int = 512,
    maxPageSize: Int = 4096,
    maxPages: Int = 8,
    atlasOwner: String = "AtlasFonts",
    private val lineBreaking: LineBreaking = LineBreaking.Words,
    private val wholePixelWidths: Boolean = false,
    smoothPages: Boolean = true,
) : FontProvider, AutoCloseable {

    /** How measured text is broken into lines. */
    enum class LineBreaking {
        /** Greedy, at spaces, a long word broken where it has to be; the ellipsis on the last kept line. */
        Words,

        /**
         * The toolkit's own `paragraph()`: the breaking rules styled text uses, including between
         * Chinese and Japanese characters, with a word too long for the width left to overflow.
         */
        Paragraph,
    }

    /** Every glyph and picture drawn with these fonts, and the white block solid colour comes from. */
    val atlas = GlyphAtlas(pageSize, maxPageSize, maxPages, atlasOwner, smoothPages)

    private data class Key(val family: String, val size: Int)

    /** Font families by name, with the sizes each was registered at, in registration order. */
    private val fontSizes = LinkedHashMap<String, MutableSet<Int>>()

    /** Picture families by name: size, then codepoint, then the picture scaled to that size. */
    private val pictureSets = LinkedHashMap<String, LinkedHashMap<Int, LinkedHashMap<Int, RgbaImage>>>()

    /** Picture families by name, then codepoint: each picture as it was given, for a scaled-up screen. */
    private val pictureSources = HashMap<String, HashMap<Int, RgbaImage>>()

    /** Copies of glyphs made at a scaled-up screen's own size. */
    private val sharp = SharpGlyphs(maxOf(pageSize, SharpPageSize), smoothPages, atlasOwner).also { atlas.sharp = it }

    private val faces = HashMap<Key, Face>()
    private val chains = HashMap<Key, Chain>()

    private var everyFamilyFallsBackTo: List<String> = emptyList()
    private val fallbacksByFamily = HashMap<String, List<String>>()

    private val bitmap = GlyphBitmap()

    /**
     * Records that [rasteriser] serves [family] at [sizes]. A backend's own `register` calls this
     * once it has handed the font to its rasteriser.
     */
    protected fun registerFont(family: String, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        fontSizes.getOrPut(family) { LinkedHashSet() }.addAll(sizes)
        forgetFaces(family)
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes].
     *
     * Each picture is scaled to the size tall, keeping its shape, sits a little below the baseline
     * the way an emoji font's glyphs do, and is drawn in its own colours whatever colour the text
     * is. A family of pictures can only be a fallback: name it in [fallBackTo].
     *
     * @param pictures encoded pictures by the one character each draws: `"😀"`, `"❤️"`, with or
     *   without the emoji variation selector. A sequence joined into one emoji is refused.
     */
    open fun registerPictures(family: String, pictures: Map<String, ByteArray>, sizes: List<Int>) {
        val decode = checkNotNull(decoder) { "these fonts were made without an image decoder, so they cannot read pictures" }
        registerDecodedPictures(family, pictures.mapValues { (_, encoded) -> decode.decode(encoded) }, sizes)
    }

    /** The same, for pictures already decoded. */
    fun registerDecodedPictures(family: String, pictures: Map<String, RgbaImage>, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a size must be positive, got $sizes" }
        require(pictures.isNotEmpty()) { "registering $family with no pictures would register nothing" }
        require(family !in fontSizes) { "$family is already a font; pictures need a name of their own" }

        val bySize = pictureSets.getOrPut(family) { LinkedHashMap() }
        pictures.forEach { (text, image) ->
            val codepoint = codepointOf(text)
            pictureSources.getOrPut(family) { HashMap() }[codepoint] = image
            sizes.distinct().forEach { size ->
                val scaledWidth = (image.width * size / image.height.toFloat()).roundToInt().coerceAtLeast(1)
                bySize.getOrPut(size) { LinkedHashMap() }[codepoint] =
                    RgbaImage(scaledWidth, size, shrink(image.pixels, image.width, image.height, scaledWidth, size))
            }
        }
        forgetFaces(family)
    }

    /**
     * Where every family looks for a character its own font does not have: each of [families], in
     * order. A fallback must be registered at every size it is asked for. Not followed any further.
     *
     * Open for a backend whose rasteriser falls back by itself — a browser picks a font per glyph
     * from a CSS font list — and so hands the list to the rasteriser instead.
     */
    open fun fallBackTo(families: List<String>) {
        everyFamilyFallsBackTo = families.toList()
        chains.clear()
    }

    /** Where [family] alone looks for a character it does not have, instead of the list for everyone. */
    open fun fallBackTo(family: String, families: List<String>) {
        fallbacksByFamily[family] = families.toList()
        chains.clear()
    }

    /** The families [family] falls back to, in the order they are tried. */
    fun fallbacksOf(family: String): List<String> =
        (fallbacksByFamily[family] ?: everyFamilyFallsBackTo).filter { it != family }

    /** The families that were registered, fonts and pictures both. */
    fun families(): List<String> = (fontSizes.keys + pictureSets.keys).distinct()

    /** The sizes [family] was registered at. */
    fun sizesOf(family: String): List<Int> =
        ((fontSizes[family] ?: emptySet()) + (pictureSets[family]?.keys ?: emptySet())).distinct().sorted()

    /**
     * Makes whatever this provider makes up front. Nothing by default: glyphs are made when first
     * measured. Called before anything is drawn with these fonts.
     */
    open fun prepare() = Unit

    override fun metrics(style: TextStyle): FontMetrics {
        val face = chainFor(style).primary
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
        val chain = chainFor(style)
        val face = chain.primary
        val lines = when (lineBreaking) {
            LineBreaking.Words -> {
                val wrapped = wrap(chain, text, maxWidth)
                if (style.maxLines in 1 until wrapped.size) {
                    wrapped.take(style.maxLines).toMutableList().also { kept ->
                        kept[kept.lastIndex] = withEllipsis(chain, kept.last(), style.ellipsis, maxWidth)
                    }
                } else {
                    wrapped
                }
            }
            LineBreaking.Paragraph -> paragraphLines(text, style, maxWidth)
        }

        val placed = mutableListOf<PlacedGlyph>()
        var widest = 0f
        lines.forEachIndexed { index, line ->
            val baseline = face.ascent + index * style.lineHeight
            widest = maxOf(widest, place(chain, line, baseline, placed))
        }

        return AtlasTextLayout(
            text = text,
            // The style's line spacing rather than the font's own, so two labels in one style line up.
            size = Size(if (wholePixelWidths) ceil(widest) else widest, lines.size * style.lineHeight),
            lineCount = lines.size,
            firstBaseline = face.ascent,
            placed = placed,
        )
    }

    /**
     * Every glyph [family] at [size] has among [codepoints], made now. For a backend that makes its
     * glyphs up front; characters the font lacks are skipped.
     */
    protected fun makeGlyphs(family: String, size: Int, codepoints: Iterable<Int>) {
        val face = faceFor(Key(family, size)) ?: return
        codepoints.forEach { face.glyph(it) }
    }

    /** Every picture registered, at every size, placed now. */
    protected fun makePictures() {
        pictureSets.forEach { (family, bySize) ->
            bySize.forEach { (size, pictures) ->
                val face = faceFor(Key(family, size)) ?: return@forEach
                pictures.keys.forEach { face.glyph(it) }
            }
        }
    }

    /** Lays one line out along [baseline], adding to [into], and answers how wide it came out. */
    private fun place(chain: Chain, line: String, baseline: Float, into: MutableList<PlacedGlyph>): Float {
        var pen = 0f
        line.forEachCodepoint { codepoint ->
            val glyph = chain.glyph(codepoint) ?: return@forEachCodepoint
            if (glyph.width > 0f && glyph.height > 0f) {
                // Snapped to whole pixels: a glyph drawn half a pixel off has soft edges.
                into += PlacedGlyph(
                    left = floor(pen + glyph.xOffset + 0.5f),
                    top = floor(baseline + glyph.yOffset + 0.5f),
                    width = glyph.width,
                    height = glyph.height,
                    glyph = glyph,
                    pen = pen,
                    baseline = baseline,
                )
            }
            pen += glyph.advance
        }
        return pen
    }

    /**
     * The toolkit's paragraph breaking. It measures single lines with no width through [measure]
     * again, which comes straight back here as one line.
     */
    private fun paragraphLines(text: String, style: TextStyle, maxWidth: Float): List<String> {
        val wraps = maxWidth.isFinite() && maxWidth > 0f
        if (!wraps && '\n' !in text && style.maxLines <= 0) return listOf(text)
        return paragraph(text, style, if (wraps) maxWidth else Float.POSITIVE_INFINITY).lines.map { line ->
            val body = text.substring(line.range.min, line.range.max)
            if (line.ellipsised) body + style.ellipsis else body
        }
    }

    private fun widthOf(chain: Chain, text: String): Float {
        var pen = 0f
        text.forEachCodepoint { pen += chain.glyph(it)?.advance ?: 0f }
        return pen
    }

    /**
     * Greedy wrapping by whole words, breaking a word only when it cannot fit a line by itself.
     * Chinese and Japanese have no spaces to break at, so a line of it is one long word broken
     * where it has to be.
     */
    private fun wrap(chain: Chain, text: String, maxWidth: Float): List<String> {
        if (!maxWidth.isFinite() || maxWidth <= 0f) return text.split('\n')

        return text.split('\n').flatMap { paragraph ->
            if (widthOf(chain, paragraph) <= maxWidth) return@flatMap listOf(paragraph)

            val lines = mutableListOf<String>()
            var current = StringBuilder()

            paragraph.split(' ').forEach { word ->
                var remaining = word
                while (widthOf(chain, remaining) > maxWidth) {
                    val fits = longestPrefix(chain, remaining, maxWidth)
                    if (fits.isEmpty()) break
                    if (current.isNotEmpty()) {
                        lines += current.toString()
                        current = StringBuilder()
                    }
                    lines += fits
                    remaining = remaining.substring(fits.length)
                }
                val separator = if (current.isEmpty()) "" else " "
                if (current.isNotEmpty() && widthOf(chain, "$current$separator$remaining") > maxWidth) {
                    lines += current.toString()
                    current = StringBuilder(remaining)
                } else {
                    current.append(separator).append(remaining)
                }
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
            lines
        }
    }

    /** The most of [text] that fits in [maxWidth], a whole character at a time. */
    private fun longestPrefix(chain: Chain, text: String, maxWidth: Float): String {
        var pen = 0f
        var at = 0
        while (at < text.length) {
            val codepoint = text.codepointAt(at)
            pen += chain.glyph(codepoint)?.advance ?: 0f
            if (pen > maxWidth) return text.take(at)
            at += charCount(codepoint)
        }
        return text
    }

    /** [line] with [ellipsis] on the end, shortened a character at a time until the pair of them fit. */
    private fun withEllipsis(chain: Chain, line: String, ellipsis: String, maxWidth: Float): String {
        if (ellipsis.isEmpty()) return line
        var kept = line.trimEnd()
        while (kept.isNotEmpty() && widthOf(chain, kept + ellipsis) > maxWidth) {
            kept = kept.substring(0, lastCodepointStart(kept)).trimEnd()
        }
        return kept + ellipsis
    }

    private fun chainFor(style: TextStyle): Chain {
        prepare()
        val key = Key(style.family, style.size.roundToInt())
        chains[key]?.let { return it }

        val primary = fontFace(key) ?: missing(key, "")
        val fallbacks = fallbacksOf(key.family).map { family ->
            val fallback = Key(family, key.size)
            faceFor(fallback) ?: missing(fallback, ", which ${key.family} falls back to")
        }
        return Chain(primary, fallbacks).also { chains[key] = it }
    }

    private fun fontFace(key: Key): Face? = if (fontSizes[key.family]?.contains(key.size) == true) faceFor(key) else null

    private fun faceFor(key: Key): Face? {
        faces[key]?.let { return it }
        val face = if (fontSizes[key.family]?.contains(key.size) == true) {
            rasteriser.face(key.family, key.size)?.let { Face(it, null, key.family, key.size) }
        } else {
            pictureSets[key.family]?.get(key.size)?.let { Face(null, it, key.family, key.size) }
        }
        if (face != null) faces[key] = face
        return face
    }

    /**
     * Forgets [family]'s faces, so the next measurement asks the rasteriser again. Every other
     * family keeps its glyphs: registering a font late must not place the ones already made a
     * second time.
     */
    private fun forgetFaces(family: String) {
        faces.keys.removeAll { it.family == family }
        chains.clear()
    }

    private fun missing(key: Key, context: String): Nothing {
        val sizes = sizesOf(key.family)
        if (context.isEmpty() && sizes.isNotEmpty() && key.family !in fontSizes) {
            error("no font for ${key.family} at ${key.size}: that name is pictures, which can only be a fallback")
        }
        val detail = if (sizes.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $sizes"
        }
        error("no font for ${key.family} at ${key.size}$context: $detail")
    }

    /**
     * One family at one size: a font through the rasteriser, or a set of pictures. Glyphs are made
     * the first time they are asked for and kept.
     */
    internal inner class Face(
        private val raster: RasterFace?,
        private val pictures: Map<Int, RgbaImage>?,
        private val family: String,
        private val size: Int,
    ) {

        private val glyphs = HashMap<Int, Glyph>()

        /** Copies made for a scaled-up screen, by quarter and codepoint, and the faces they came from by pixel size. */
        private val sharpGlyphs = HashMap<Long, Glyph>()
        private val sharpFaces = HashMap<Int, RasterFace>()
        private var madeIn = 0

        val sharpGeneration: Int get() = sharp.generation

        val ascent: Float get() = raster?.ascent ?: 0f
        val descent: Float get() = raster?.descent ?: 0f
        val capHeight: Float get() = raster?.capHeight ?: 0f
        val spaceAdvance: Float by lazy { glyph(' '.code)?.advance ?: (size * 0.3f) }

        fun glyph(codepoint: Int): Glyph? {
            glyphs[codepoint]?.let { return if (it === Missing) null else it }
            val made = make(codepoint)
            glyphs[codepoint] = made ?: Missing
            return made
        }

        private fun make(codepoint: Int): Glyph? {
            if (pictures != null) return pictures[codepoint]?.let { picture(it, codepoint) }
            val raster = raster ?: return null
            if (!raster.has(codepoint)) return null
            if (!raster.draw(codepoint, bitmap)) return null
            val advance = raster.advance(codepoint)
            if (bitmap.width <= 0 || bitmap.height <= 0) {
                if (advance <= 0f) return null
                return Glyph(null, 0, 0, 0f, 0f, bitmap.xOffset, bitmap.yOffset, advance)
            }
            val spot = atlas.place(bitmap.width, bitmap.height)
            val colour = bitmap.kind == GlyphKind.Colour
            if (colour) {
                spot.page.writeRgba(spot.x, spot.y, bitmap.width, bitmap.height, bitmap.pixels)
            } else {
                spot.page.writeCoverage(spot.x, spot.y, bitmap.width, bitmap.height, bitmap.pixels)
            }
            return Glyph(
                spot.page, spot.x, spot.y,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                bitmap.xOffset, bitmap.yOffset, advance, colour,
                face = this, codepoint = codepoint,
            )
        }

        /**
         * [codepoint] made for a screen [quarter] quarters the design size, or null to draw it as it is.
         * Smaller than one is a zoomed-out plane's, and is made too: a glyph shrunk on the GPU with no
         * smaller copy to sample from shimmers.
         */
        fun sharp(codepoint: Int, quarter: Int): Glyph? {
            if (quarter == SharpGlyphs.One || quarter <= 0) return null
            if (madeIn != sharp.generation) {
                sharpGlyphs.clear()
                sharpFaces.clear()
                madeIn = sharp.generation
            }
            val key = (quarter.toLong() shl 32) or codepoint.toLong()
            sharpGlyphs[key]?.let { return if (it === Missing) null else it }
            val made = makeSharp(codepoint, quarter)
            // Whatever was made, a copy that was not made is not tried again this generation.
            if (madeIn == sharp.generation) sharpGlyphs[key] = made ?: Missing
            return made
        }

        private fun makeSharp(codepoint: Int, quarter: Int): Glyph? {
            val pixels = SharpGlyphs.pixelsFor(size, quarter)
            if (pixels == size || pixels <= 0) return null
            if (pictures != null) return sharpPicture(codepoint, pixels, quarter)
            val raster = sharpFaces[pixels] ?: rasteriser.face(family, size, pixels)?.also { sharpFaces[pixels] = it } ?: return null
            if (!raster.has(codepoint) || !raster.draw(codepoint, bitmap)) return null
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val spot = sharp.place(quarter, bitmap.width, bitmap.height) ?: return null
            val colour = bitmap.kind == GlyphKind.Colour
            if (colour) {
                spot.page.writeRgba(spot.x, spot.y, bitmap.width, bitmap.height, bitmap.pixels)
            } else {
                spot.page.writeCoverage(spot.x, spot.y, bitmap.width, bitmap.height, bitmap.pixels)
            }
            return Glyph(
                spot.page, spot.x, spot.y,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                bitmap.xOffset, bitmap.yOffset, raster.advance(codepoint), colour,
                codepoint = codepoint,
                pixelsPerUnit = pixels / size.toFloat(),
            )
        }

        /** A picture from its source, as tall as the screen wants it or as the source is, whichever is less. */
        private fun sharpPicture(codepoint: Int, pixels: Int, quarter: Int): Glyph? {
            val source = pictureSources[family]?.get(codepoint) ?: return null
            val tall = minOf(pixels, source.height)
            if (tall == size || tall <= 0) return null
            val wide = (source.width * tall / source.height.toFloat()).roundToInt().coerceAtLeast(1)
            val spot = sharp.place(quarter, wide, tall) ?: return null
            spot.page.writeRgba(spot.x, spot.y, wide, tall, shrink(source.pixels, source.width, source.height, wide, tall))
            return Glyph(
                spot.page, spot.x, spot.y, wide.toFloat(), tall.toFloat(), 0f, 0f, 0f, colour = true,
                codepoint = codepoint,
                pixelsPerUnit = tall / size.toFloat(),
                fillsBox = true,
            )
        }

        private fun picture(picture: RgbaImage, codepoint: Int): Glyph {
            val spot = atlas.place(picture.width, picture.height)
            spot.page.writeRgba(spot.x, spot.y, picture.width, picture.height, picture.pixels)
            val gap = (size / 16f).roundToInt().coerceAtLeast(1)
            return Glyph(
                page = spot.page,
                x = spot.x,
                y = spot.y,
                width = picture.width.toFloat(),
                height = picture.height.toFloat(),
                xOffset = gap.toFloat(),
                // Its bottom a little below the baseline, where an emoji font puts it.
                yOffset = -(picture.height - (size * PictureDrop).roundToInt()).toFloat(),
                advance = (picture.width + gap * 2).toFloat(),
                colour = true,
                face = this,
                codepoint = codepoint,
            )
        }
    }

    /** A face with its fallbacks behind it. Every metric is the primary's. */
    private class Chain(val primary: Face, private val fallbacks: List<Face>) {

        fun glyph(codepoint: Int): Glyph? {
            if (codepoint == 0xFE0E || codepoint == 0xFE0F) return null
            primary.glyph(codepoint)?.let { return it }
            for (face in fallbacks) face.glyph(codepoint)?.let { return it }
            return primary.glyph('?'.code)
        }
    }

    /**
     * Gives the atlas's textures back to the devices they were uploaded to, and forgets every
     * registration: measuring with fonts that were closed is refused, naming no families.
     */
    override fun close() {
        atlas.close()
        sharp.close()
        fontSizes.clear()
        pictureSets.clear()
        pictureSources.clear()
        faces.clear()
        chains.clear()
    }

    companion object {

        /** How far below the baseline a picture's bottom edge sits, as a share of the text size. */
        const val PictureDrop = 0.12f

        /** The smallest page glyphs made for a scaled-up screen go on. */
        private const val SharpPageSize = 1024

        private val Missing = Glyph(null, 0, 0, 0f, 0f, 0f, 0f, 0f)

        private const val VariationSelector = 0xFE0F

        /**
         * Every character in [text], as sorted ranges with each run of neighbours joined: what a
         * backend that makes glyphs up front is told to make for a fallback font.
         */
        fun codepointsOf(text: String): List<IntRange> {
            val sorted = ArrayList<Int>()
            text.forEachCodepoint { sorted += it }
            val distinct = sorted.distinct().sorted()
            val ranges = mutableListOf<IntRange>()
            var start = -1
            var end = -1
            for (codepoint in distinct) {
                if (start >= 0 && codepoint == end + 1) {
                    end = codepoint
                } else {
                    if (start >= 0) ranges += start..end
                    start = codepoint
                    end = codepoint
                }
            }
            if (start >= 0) ranges += start..end
            return ranges
        }

        /** The one character a picture key stands for. */
        fun codepointOf(text: String): Int {
            val codepoints = ArrayList<Int>()
            text.forEachCodepoint { codepoints += it }
            if (codepoints.size == 2 && codepoints[1] == VariationSelector) codepoints.removeAt(1)
            require(codepoints.size == 1) {
                "a picture stands for one character, and \"$text\" is ${codepoints.size}; " +
                    "sequences joined into one emoji are not supported"
            }
            return codepoints[0]
        }

        /**
         * [rgba] shrunk to [toWidth] by [toHeight], each new pixel the average of the ones it covers,
         * weighted by alpha so the invisible pixels round an emoji do not darken its edge.
         */
        fun shrink(rgba: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): ByteArray {
            val out = ByteArray(toWidth * toHeight * 4)
            for (y in 0 until toHeight) {
                val top = y * height / toHeight
                val bottom = maxOf(top + 1, (y + 1) * height / toHeight)
                for (x in 0 until toWidth) {
                    val left = x * width / toWidth
                    val right = maxOf(left + 1, (x + 1) * width / toWidth)
                    var red = 0L
                    var green = 0L
                    var blue = 0L
                    var alpha = 0L
                    var count = 0
                    for (sy in top until bottom) {
                        for (sx in left until right) {
                            val at = (sy * width + sx) * 4
                            val a = rgba[at + 3].toInt() and 0xFF
                            red += (rgba[at].toInt() and 0xFF) * a
                            green += (rgba[at + 1].toInt() and 0xFF) * a
                            blue += (rgba[at + 2].toInt() and 0xFF) * a
                            alpha += a
                            count++
                        }
                    }
                    val to = (y * toWidth + x) * 4
                    if (alpha > 0) {
                        out[to] = (red / alpha).toInt().toByte()
                        out[to + 1] = (green / alpha).toInt().toByte()
                        out[to + 2] = (blue / alpha).toInt().toByte()
                    }
                    out[to + 3] = (alpha / count).toInt().toByte()
                }
            }
            return out
        }
    }
}

/** The codepoint starting at [index]: a surrogate pair joined, a lone surrogate as itself. */
internal fun String.codepointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

/** How many chars [codepoint] takes. */
internal fun charCount(codepoint: Int): Int = if (codepoint >= 0x10000) 2 else 1

internal inline fun String.forEachCodepoint(action: (Int) -> Unit) {
    var at = 0
    while (at < length) {
        val codepoint = codepointAt(at)
        action(codepoint)
        at += charCount(codepoint)
    }
}

/** Where the last codepoint of [text] starts. */
internal fun lastCodepointStart(text: String): Int {
    val last = text.length - 1
    if (last >= 1 && text[last].isLowSurrogate() && text[last - 1].isHighSurrogate()) return last - 1
    return last
}
