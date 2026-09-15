package dev.wildware.composegl.korge

import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.GlyphBitmap
import dev.wildware.composegl.render.GlyphKind
import dev.wildware.composegl.render.GlyphRasteriser
import dev.wildware.composegl.render.ImageDecoder
import dev.wildware.composegl.render.RasterFace
import dev.wildware.composegl.render.RgbaImage
import dev.wildware.composegl.ui.text.TextStyle
import korlibs.image.bitmap.Bitmap
import korlibs.image.color.Colors
import korlibs.image.font.Font
import korlibs.image.font.TtfFont
import korlibs.image.font.renderGlyphToBitmap
import korlibs.image.format.PNG
import korlibs.io.stream.openSync
import kotlin.math.floor
import kotlin.math.roundToInt

/** Text measured by [KorgeFonts]: the shared renderer's layout, under the name this backend has always used. */
typealias KorgeTextLayout = AtlasTextLayout

/**
 * Fonts, by name, rasterised by KorGE into the shared renderer's glyph atlas.
 *
 * A game registers what it wants at startup and the toolkit never touches a file: it asks for "body
 * at 16" and gets whatever this was told that means. Two weights are two names. Asking for a size
 * nobody registered is an error that names the sizes that exist.
 *
 * Glyphs are made the first time text asks for them: KorGE reads the `.ttf` in plain Kotlin and
 * rasterises a glyph on the CPU, so a character first seen in a player's name costs a fraction of a
 * millisecond, needs no OpenGL, and goes onto the shared [atlas] beside the white block the canvas
 * draws solid colour from — so a screen of panels and labels is one texture and one draw call.
 *
 * Wrapping is the toolkit's own `paragraph()`, including breaking between Chinese and Japanese
 * characters. **Characters a font does not have** come from the families named by [fallBackTo], one
 * character at a time; colour emoji are registered as pictures with [registerPictures].
 *
 * **Text size.** `ProvideTextScale` asks for whole sizes, each rasterised at that size, never
 * stretched: `scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f))` is the list to register.
 *
 * **Kerning is deliberately not applied.** A width is the sum of the advances, rounded up to a whole
 * pixel, so text measured in pieces adds up to text measured whole — which `Paragraph`, carets,
 * bidirectional text and `Typewriter` all rely on.
 *
 * @param pageSize each atlas page, in pixels each way. A page that fills starts another.
 */
class KorgeFonts private constructor(
    private val rasteriser: KorgeRasteriser,
    pageSize: Int,
) : AtlasFonts(
    rasteriser,
    KorgeImages,
    pageSize = pageSize,
    maxPageSize = pageSize,
    maxPages = MaxPages,
    atlasOwner = "KorgeFonts",
    lineBreaking = LineBreaking.Paragraph,
    wholePixelWidths = true,
    // Sampled a texel at a time, as KorGE's own text is: an outline's ring copies sit a fraction of
    // a pixel off, and smooth sampling would blur them.
    smoothPages = false,
) {

    constructor(pageSize: Int = 1024) : this(KorgeRasteriser(), pageSize)

    /**
     * Registers [family] from the bytes of a `.ttf` or `.otf`, at each of [sizes].
     *
     * Reads the file here, once. Nothing is rasterised until text asks for it, and nothing needs an
     * OpenGL context, so this can run on a loading thread.
     */
    fun registerTrueType(family: String, bytes: ByteArray, sizes: List<Int>) =
        register(family, TtfFont(bytes, extName = family), sizes)

    /** Registers a font the game already has — one it loaded with `readTtfFont`, say — under [family]. */
    fun register(family: String, font: Font, sizes: List<Int>) {
        require(family !in families() || family in rasteriser.fonts) { "$family is already pictures; a font needs a name of its own" }
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        rasteriser.fonts[family] = font
        registerFont(family, sizes.distinct().sorted())
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes]: how colour
     * emoji get into text. Each is scaled to the text size tall and drawn in its own colours. A
     * family of pictures can only be a fallback: name it in [fallBackTo].
     *
     * @param pictures by the one character each draws: `"😀"`, `"❤️"`.
     */
    @JvmName("registerBitmapPictures")
    fun registerPictures(family: String, pictures: Map<String, Bitmap>, sizes: List<Int>) =
        registerDecodedPictures(family, pictures.mapValues { (_, bitmap) -> KorgeImages.rgba(bitmap) }, sizes)

    /** [registerPictures] from encoded files — a PNG, say — rather than bitmaps already decoded. */
    fun registerEncodedPictures(family: String, pictures: Map<String, ByteArray>, sizes: List<Int>) =
        registerPictures(family, pictures, sizes)

    /** The KorGE font behind [style]. Throws, naming what is registered, when there is none. */
    fun fontFor(style: TextStyle): Font {
        val size = style.size.roundToInt()
        val font = rasteriser.fonts[style.family]
        if (font != null && size in sizesOf(style.family)) return font
        val registered = sizesOf(style.family)
        val detail = if (registered.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $registered"
        }
        error("no font for ${style.family} at $size: $detail")
    }

    private companion object {
        /** Past this many pages a registration is refused rather than a page of missing letters drawn. */
        const val MaxPages = 16
    }
}

/** KorGE's PNG reader, as the shared renderer's picture decoder. */
internal object KorgeImages : ImageDecoder {

    override fun decode(encoded: ByteArray): RgbaImage = rgba(PNG.readImage(encoded.openSync()).mainBitmap)

    /** Straight RGBA, top row first, whichever way the bitmap keeps its colours. */
    fun rgba(bitmap: Bitmap): RgbaImage {
        val straight = bitmap.toBMP32IfRequired().depremultipliedIfRequired()
        val pixels = ByteArray(straight.width * straight.height * 4)
        for (y in 0 until straight.height) for (x in 0 until straight.width) {
            val colour = straight.getRgbaRaw(x, y)
            val at = (y * straight.width + x) * 4
            pixels[at] = colour.r.toByte()
            pixels[at + 1] = colour.g.toByte()
            pixels[at + 2] = colour.b.toByte()
            pixels[at + 3] = colour.a.toByte()
        }
        return RgbaImage(straight.width, straight.height, pixels)
    }
}

/**
 * KorGE's TrueType rasteriser, one glyph at a time: the whole of this backend's part in drawing text.
 *
 * Offsets come out as KorGE places a glyph: its picture a border's width bigger than its bounds each
 * way, and the top snapped to a whole pixel from the baseline.
 */
internal class KorgeRasteriser : GlyphRasteriser {

    val fonts = LinkedHashMap<String, Font>()

    override fun face(family: String, size: Int): RasterFace? = fonts[family]?.let { Face(it, size) }

    private class Face(private val font: Font, private val size: Int) : RasterFace {

        private val metrics = font.getFontMetrics(size.toDouble())

        override val ascent = metrics.ascent.toFloat()

        override val descent = -metrics.descent.toFloat()

        override val capHeight: Float = font.getGlyphMetrics(size.toDouble(), 'H'.code).let { h ->
            if (h.existing && h.height > 0.0) h.height.toFloat() else size * 0.7f
        }

        override fun has(codepoint: Int): Boolean = font.getGlyphMetrics(size.toDouble(), codepoint).existing

        override fun advance(codepoint: Int): Float = font.getGlyphMetrics(size.toDouble(), codepoint).xadvance.toFloat()

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            val glyph = font.getGlyphMetrics(size.toDouble(), codepoint)
            if (glyph.width <= 0.0 || glyph.height <= 0.0) {
                into.resize(0, 0, GlyphKind.Coverage)
                return true
            }
            val rendered = font.renderGlyphToBitmap(
                size.toDouble(), codepoint, paint = Colors.WHITE, fill = true, border = Border, nativeRendering = false,
            ).bmp
            into.resize(rendered.width, rendered.height, GlyphKind.Coverage)
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                into.pixels[y * rendered.width + x] = rendered.getRgbaRaw(x, y).a.toByte()
            }
            into.xOffset = glyph.left.toFloat() - Border
            // The baseline is `height + top` below the picture's top edge.
            into.yOffset = floor(-(glyph.height + glyph.top).toFloat() - Border + 0.5f)
            return true
        }

        private companion object {
            /** Empty pixels KorGE leaves round a rendered glyph, so its soft edge is not cut off. */
            const val Border = 1
        }
    }
}
