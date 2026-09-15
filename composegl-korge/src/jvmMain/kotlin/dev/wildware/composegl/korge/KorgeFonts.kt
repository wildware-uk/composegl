package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.paragraph
import korlibs.image.bitmap.Bitmap
import korlibs.image.font.Font
import korlibs.image.font.TtfFont
import korlibs.image.format.PNG
import korlibs.io.stream.openSync
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
    /**
     * True for a picture with colours of its own — an emoji — rather than a letter. The canvas draws
     * it in its own colours, and leaves it out of an outline's ring.
     */
    val picture: Boolean = false,
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
 * where the toolkit would break it.
 *
 * **Characters a font does not have** — a player called 玩家, a chat line with 😀 in it — come from
 * the families named by [fallBackTo], tried in order, one character at a time. A fallback font is
 * registered with [registerTrueType] like any other, and since glyphs are made when first asked for,
 * a font with twenty thousand characters costs nothing until text uses some. Colour emoji are
 * registered as pictures with [registerPictures], packed into the same atlas.
 *
 * **Text size.** `ProvideTextScale` asks for whole sizes — 16 at 125% is a style at 20 — and each is
 * rasterised at that size, never stretched. `scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f))` is
 * the list to hand [registerTrueType], and fallbacks need every one of those sizes too.
 *
 * **Kerning is deliberately not applied.** A width here is the sum of the glyphs' advances, so text
 * measured in pieces adds up to text measured whole. The toolkit leans on that: `Paragraph` breaks a
 * line by measuring words, `TextField` puts the caret by measuring a prefix, bidirectional text
 * measures each run in its own order, and `Typewriter` draws a character at a time. Kerning would make
 * every one of those disagree with the drawn line by a pixel or two. The LWJGL backend makes the same
 * choice; LibGDX kerns within one font, and so draws `AV` a little tighter than a caret measures it.
 *
 * Metrics are KorGE's reading of the font's own: the ascent and descent from its horizontal header and
 * the cap height from its `H`. The first baseline is an ascent down, so an accent on a capital stays
 * inside the box, and `TextAnchor` and `TextMetricsOverlay` read the same numbers the glyphs are
 * placed with.
 *
 * @param atlas where glyphs are packed. Share one with [KorgeCanvas] so text and boxes are one texture.
 */
class KorgeFonts(val atlas: KorgeAtlas = KorgeAtlas()) : FontProvider {

    private data class Key(val family: String, val size: Int)

    private val fonts = LinkedHashMap<String, Font>()
    private val sizes = LinkedHashMap<String, List<Int>>()
    private val faces = HashMap<Key, Face>()

    /** Families of pictures standing in for characters, which can only ever be fallbacks. */
    private val pictures = LinkedHashMap<String, Map<Int, Map<Int, Glyph>>>()

    private var everyFamilyFallsBackTo: List<String> = emptyList()
    private val fallbacksByFamily = HashMap<String, List<String>>()

    /** Each family with its fallbacks behind it, built the first time a style asks for it. */
    private val chains = HashMap<Key, Chain>()

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
        require(family !in pictures) { "$family is already pictures; a font needs a name of its own" }
        fonts[family] = font
        this.sizes[family] = sizes.distinct().sorted()
        faces.keys.removeAll { it.family == family }
        chains.clear()
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes].
     *
     * This is how colour emoji get into text. Each picture is scaled to [sizes] tall — the text size,
     * so an emoji is as tall as the type is big — keeping its shape, and packed into the shared
     * [atlas], so a chat line with a smiley in it is still one texture. It sits a little below the
     * baseline, the way an emoji font's glyphs do, and is drawn in its own colours whatever colour the
     * text around it is.
     *
     * A family of pictures can only be a fallback: name it in [fallBackTo]. It has no letters of its
     * own to be the main font with.
     *
     * @param pictures by the character each one draws: `"😀"`, `"❤️"`. One character each, with or
     *   without the emoji variation selector after it. A sequence joined into one emoji — a family, a
     *   flag, a skin tone — is refused, because it would need text shaping to find.
     */
    fun registerPictures(family: String, pictures: Map<String, Bitmap>, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a size must be positive, got $sizes" }
        require(pictures.isNotEmpty()) { "registering $family with no pictures would register nothing" }
        require(family !in fonts) { "$family is already a font; pictures need a name of their own" }
        val codepoints = pictures.mapKeys { (text, _) -> Pictures.codepointOf(text) }

        this.pictures[family] = sizes.distinct().sorted().associateWith { size ->
            codepoints.mapValues { (_, picture) -> Pictures.pack(picture, size, atlas) }
        }
        chains.clear()
    }

    /** [registerPictures] from encoded files — a PNG, say — rather than bitmaps already decoded. */
    @JvmName("registerEncodedPictures")
    fun registerEncodedPictures(family: String, pictures: Map<String, ByteArray>, sizes: List<Int>) =
        registerPictures(family, pictures.mapValues { (_, bytes) -> PNG.readImage(bytes.openSync()).mainBitmap }, sizes)

    /**
     * Where every family looks for a character its own font does not have: each of [families], in
     * order.
     *
     * Checked one character at a time, so `"Ace 玩家 😀"` takes its letters from the main font, its
     * Chinese from the first fallback that has it and its emoji from the pictures. The main font
     * always wins for a character it does have, and a character nothing has comes out as the main
     * font's `?`.
     *
     * A fallback must be registered at every size the families that name it are asked for, or
     * measuring says which size it wanted — the same rule as a missing size of the font itself.
     *
     * Not followed any further: a fallback's own fallbacks are not tried. List everything here.
     */
    fun fallBackTo(families: List<String>) {
        everyFamilyFallsBackTo = families.toList()
        chains.clear()
    }

    /** Where [family] alone looks for a character it does not have, instead of the list for everyone. */
    fun fallBackTo(family: String, families: List<String>) {
        fallbacksByFamily[family] = families.toList()
        chains.clear()
    }

    /** The families [family] falls back to, in the order they are tried. */
    fun fallbacksOf(family: String): List<String> =
        (fallbacksByFamily[family] ?: everyFamilyFallsBackTo).filter { it != family }

    /** The families that were registered, fonts and pictures, in the order they were first registered. */
    fun families(): List<String> = (fonts.keys + pictures.keys).distinct()

    /** The sizes [family] was registered at. Empty for a family nobody registered. */
    fun sizesOf(family: String): List<Int> = sizes[family] ?: pictures[family]?.keys?.toList().orEmpty()

    /** The KorGE font behind [style]. Throws, naming what is registered, when there is none. */
    fun fontFor(style: TextStyle): Font {
        // Through the face, so a size or family nobody registered fails with the same message.
        faceFor(Key(style.family, style.size.roundToInt()), "")
        return fonts.getValue(style.family)
    }

    private fun faceFor(key: Key, context: String): Face {
        faces[key]?.let { return it }
        val font = fonts[key.family]
        if (font == null || key.size !in sizes[key.family].orEmpty()) missing(key, context)
        return Face(font, key.size, atlas).also { faces[key] = it }
    }

    private fun chainFor(style: TextStyle): Chain {
        val key = Key(style.family, style.size.roundToInt())
        chains[key]?.let { return it }
        val primary = faceFor(key, "")
        val sources = fallbacksOf(key.family).map { family ->
            val fallback = Key(family, key.size)
            pictures[family]?.let { bySize -> bySize[key.size]?.let { return@map GlyphSource.OfPictures(it) } }
            GlyphSource.OfFace(faceFor(fallback, ", which ${key.family} falls back to"))
        }
        return Chain(primary, sources).also { chains[key] = it }
    }

    private fun missing(key: Key, context: String): Nothing {
        val registered = sizesOf(key.family)
        if (key.family in pictures && context.isEmpty()) {
            error("no font for ${key.family} at ${key.size}: that name is pictures, which can only be a fallback")
        }
        val detail = if (registered.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $registered"
        }
        error("no font for ${key.family} at ${key.size}$context: $detail")
    }

    override fun metrics(style: TextStyle): FontMetrics {
        val face = chainFor(style).primary
        return FontMetrics(
            size = style.size,
            ascent = face.ascent,
            descent = face.descent,
            capHeight = face.capHeight,
            lineHeight = style.lineHeight,
            spaceAdvance = face.glyph(' '.code)?.advance ?: 0f,
        )
    }

    override fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout {
        val chain = chainFor(style)
        val face = chain.primary
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
            widest = maxOf(widest, place(chain, line, face.ascent + index * style.lineHeight, placed))
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
    private fun place(chain: Chain, line: String, baseline: Float, into: MutableList<PlacedGlyph>): Float {
        var pen = 0f
        var at = 0
        // The baseline to a whole pixel first and each glyph's offset from it after, so where one
        // glyph sits against the next never depends on the fraction in the main font's ascent. A
        // character borrowed from a fallback is then exactly the shape that font draws on its own.
        val row = floor(baseline + 0.5f)
        while (at < line.length) {
            val codepoint = line.codePointAt(at)
            at += Character.charCount(codepoint)
            val glyph = chain.glyph(codepoint)
            val region = glyph.region
            if (region != null) {
                // Whole pixels: a glyph rasterised at one position and drawn half a pixel off is a
                // glyph with soft edges.
                into += PlacedGlyph(
                    left = floor(pen + glyph.left + 0.5f),
                    top = row + floor(glyph.top + 0.5f),
                    width = region.width.toFloat(),
                    height = region.height.toFloat(),
                    region = region,
                    picture = glyph.picture,
                )
            }
            // No kerning, on purpose: see the class note.
            pen += glyph.advance
        }
        return pen
    }
}
