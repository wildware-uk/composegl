package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.GlyphBitmap
import dev.wildware.composegl.render.GlyphKind
import dev.wildware.composegl.render.GlyphRasteriser
import dev.wildware.composegl.render.ImageDecoder
import dev.wildware.composegl.render.RasterFace
import dev.wildware.composegl.render.RgbaImage
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBImage
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

/** Text measured by [StbFonts]: the shared renderer's layout, under the name this backend has always used. */
typealias StbTextLayout = AtlasTextLayout

/**
 * Fonts, from a `.ttf`, rasterised by stb_truetype into the shared renderer's glyph atlas.
 *
 * Registered by name and by size. Everything registered is made the first time anything is measured
 * — every glyph, every picture, the white block the renderer draws solid colour from — and lands on
 * one page, so a screen of panels and labels is one texture and one draw call and nothing is ever
 * rasterised mid-frame. The page starts at [pageSize] and doubles until everything fits, up to
 * [maxPageSize].
 *
 * Measuring needs no OpenGL: the page is made in memory and uploaded when something is first drawn.
 *
 * **Characters a font does not have** come from the families named by [fallBackTo], tried in order,
 * one character at a time. A fallback font is registered like any other, with the characters it is
 * for — [codepointsOf] turns the text a game expects into the list — and colour emoji are
 * registered as pictures with [registerPictures].
 *
 * No kerning: widths are sums of advances, which is what every backend's text now does.
 *
 * @param pageSize the atlas, in pixels each way, to begin with.
 * @param maxPageSize the most the page may double to. Registering more than fits is an error that
 *   says so rather than a page of missing letters.
 */
class StbFonts private constructor(
    private val rasteriser: StbRasteriser,
    pageSize: Int,
    maxPageSize: Int,
) : AtlasFonts(rasteriser, StbImages, pageSize, maxPageSize, maxPages = 1, atlasOwner = "StbFonts"), AutoCloseable {

    constructor(pageSize: Int = 512, maxPageSize: Int = 4096) : this(StbRasteriser(), pageSize, maxPageSize)

    private var made = false

    /** The page's size each way, once made: [pageSize] doubled as many times as it needed. */
    val bakedPageSize: Int
        get() {
            prepare()
            return atlas.page(0).size
        }

    /**
     * Registers [family] at each of [sizes], from the bytes of a `.ttf`.
     *
     * Everything must be registered before the first measurement, because everything shares one
     * page and the page is made once.
     *
     * @param codepoints the characters to make. [Codepoints] — Latin and the punctuation an
     *   interface uses — unless a font is for something else: a fallback for Chinese names wants
     *   `codepointsOf(everyNameInTheGame)`, not the thirty thousand characters in the file.
     */
    fun register(family: String, ttf: ByteArray, sizes: List<Int>, codepoints: List<IntRange> = Codepoints) {
        check(!made) { "every font must be registered before the first measurement" }
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        require(codepoints.isNotEmpty()) { "registering $family with no characters would register nothing" }
        val sorted = sizes.distinct().sorted()
        rasteriser.add(family, ttf, sorted, codepoints)
        registerFont(family, sorted)
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes]: how colour
     * emoji get into text, since stb_truetype only makes coverage. Decoded with stb_image.
     */
    override fun registerPictures(family: String, pictures: Map<String, ByteArray>, sizes: List<Int>) {
        check(!made) { "every picture must be registered before the first measurement" }
        super.registerPictures(family, pictures, sizes)
    }

    /** Makes every registered glyph and picture, once. */
    override fun prepare() {
        if (made) return
        check(families().isNotEmpty()) { "no fonts were registered" }
        made = true
        rasteriser.registrations.forEach { registration ->
            registration.sizes.forEach { size ->
                makeGlyphs(registration.family, size, registration.codepoints.asSequence().flatMap { it.asSequence() }.asIterable())
            }
        }
        makePictures()
    }

    companion object {

        /**
         * What gets made: printable ASCII, Latin-1 and the punctuation an interface actually uses,
         * the ellipsis among it.
         */
        val Codepoints: List<IntRange> = listOf(
            0x20..0x7E,
            0xA0..0xFF,
            0x2010..0x2027,
            // The shapes a prompt draws, one at a time rather than by the block.
            0x25A0..0x25A1, // ■ □
            0x25B2..0x25B3, // ▲ △
            0x25CB..0x25CB, // ○
            0x25CF..0x25CF, // ●
            0x2630..0x2630, // ☰
            0x2713..0x2716, // ✓ ✔ ✕ ✖
        )

        /** Every character in [text], as the sorted, joined ranges [register] takes. */
        fun codepointsOf(text: String): List<IntRange> = AtlasFonts.codepointsOf(text)
    }
}

/** stb_image, as the shared renderer's picture decoder. */
internal object StbImages : ImageDecoder {

    override fun decode(encoded: ByteArray): RgbaImage {
        val bytes = BufferUtils.createByteBuffer(encoded.size).put(encoded)
        bytes.flip()
        MemoryStack.stackPush().use { stack ->
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            val channels = stack.mallocInt(1)
            val pixels = STBImage.stbi_load_from_memory(bytes, width, height, channels, 4)
                ?: error("stb_image could not read that picture: ${STBImage.stbi_failure_reason()}")
            try {
                // Read through a duplicate: LWJGL frees the buffer at its position.
                return RgbaImage(width[0], height[0], ByteArray(pixels.remaining()).also { pixels.duplicate().get(it) })
            } finally {
                STBImage.stbi_image_free(pixels)
            }
        }
    }
}

/**
 * stb_truetype, one glyph at a time: the whole of this backend's part in drawing text.
 *
 * A face answers only for the characters it was registered with, so a font asked for a character
 * outside its list hands it to the fallbacks exactly as a font that lacks it does.
 */
internal class StbRasteriser : GlyphRasteriser {

    class Registration(val family: String, val bytes: ByteBuffer, val info: STBTTFontinfo, val sizes: List<Int>, val codepoints: List<IntRange>)

    val registrations = mutableListOf<Registration>()

    fun add(family: String, ttf: ByteArray, sizes: List<Int>, codepoints: List<IntRange>) {
        // Copied into native memory, and kept: stb reads the file's bytes again for every glyph.
        val bytes = BufferUtils.createByteBuffer(ttf.size).put(ttf)
        bytes.flip()
        val info = STBTTFontinfo.create()
        check(STBTruetype.stbtt_InitFont(info, bytes)) { "$family is not a font stb_truetype can read" }
        registrations += Registration(family, bytes, info, sizes, codepoints)
    }

    override fun face(family: String, size: Int): RasterFace? =
        registrations.lastOrNull { it.family == family && size in it.sizes }?.let { Face(it, size) }

    private class Face(private val font: Registration, size: Int) : RasterFace {

        private val info = font.info
        private val scale = STBTruetype.stbtt_ScaleForPixelHeight(info, size.toFloat())
        private val box = IntArray(4)

        override val ascent: Float
        override val descent: Float
        override val capHeight: Float

        init {
            val above = IntArray(1)
            val below = IntArray(1)
            val gap = IntArray(1)
            STBTruetype.stbtt_GetFontVMetrics(info, above, below, gap)
            ascent = above[0] * scale
            // stb measures below the baseline as negative; the toolkit as positive.
            descent = -below[0] * scale
            capHeight = if (has('H'.code)) {
                boxOf('H'.code)
                (box[3] - box[1]).toFloat()
            } else {
                size * 0.7f
            }
        }

        override fun has(codepoint: Int): Boolean =
            font.codepoints.any { codepoint in it } && STBTruetype.stbtt_FindGlyphIndex(info, codepoint) != 0

        override fun advance(codepoint: Int): Float {
            val advance = IntArray(1)
            STBTruetype.stbtt_GetCodepointHMetrics(info, codepoint, advance, IntArray(1))
            return scale * advance[0]
        }

        private fun boxOf(codepoint: Int) {
            val x0 = IntArray(1)
            val y0 = IntArray(1)
            val x1 = IntArray(1)
            val y1 = IntArray(1)
            STBTruetype.stbtt_GetCodepointBitmapBox(info, codepoint, scale, scale, x0, y0, x1, y1)
            box[0] = x0[0]
            box[1] = y0[0]
            box[2] = x1[0]
            box[3] = y1[0]
        }

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            boxOf(codepoint)
            val width = box[2] - box[0]
            val height = box[3] - box[1]
            into.xOffset = box[0].toFloat()
            into.yOffset = box[1].toFloat()
            if (width <= 0 || height <= 0) {
                into.resize(0, 0, GlyphKind.Coverage)
                return true
            }
            into.resize(width, height, GlyphKind.Coverage)
            val pixels = BufferUtils.createByteBuffer(width * height)
            STBTruetype.stbtt_MakeCodepointBitmap(info, pixels, width, height, width, scale, scale, codepoint)
            pixels.get(into.pixels, 0, width * height)
            return true
        }
    }
}
