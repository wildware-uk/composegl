package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.text.FontMetrics
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.text.TextLayout
import uk.wildware.composegl.ui.text.TextStyle
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTTPackContext
import org.lwjgl.stb.STBTTPackRange
import org.lwjgl.stb.STBTTPackedchar
import org.lwjgl.stb.STBTruetype
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

/** How many codepoints a range covers. */
private val IntRange.length: Int get() = last - first + 1

/** One glyph, as it sits in the atlas and as it sits beside the one before it. */
internal class Glyph(
    val u: Float,
    val v: Float,
    val u2: Float,
    val v2: Float,
    /** From the pen position to the left edge of the picture. */
    val xOffset: Float,
    /** From the baseline down to the top edge of the picture. Usually negative. */
    val yOffset: Float,
    val width: Float,
    val height: Float,
    /** How far the pen moves afterwards. */
    val advance: Float,
)

/** One font at one size: what it looks like, and every glyph that was baked for it. */
internal class Face(
    val ascent: Float,
    val descent: Float,
    val capHeight: Float,
    val spaceAdvance: Float,
    val glyphs: Map<Int, Glyph>,
) {

    /** The glyph for [codepoint], or the one for `?` when the font has nothing to show. */
    fun glyph(codepoint: Int): Glyph? = glyphs[codepoint] ?: glyphs['?'.code]
}

/** A glyph placed by measuring, in the layout's own coordinates with y downwards from its top. */
internal class PlacedGlyph(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val glyph: Glyph,
)

/** Text measured by [StbFonts]. The toolkit sees the four properties; the canvas sees the rest. */
class StbTextLayout internal constructor(
    override val text: String,
    override val size: Size,
    override val lineCount: Int,
    override val firstBaseline: Float,
    internal val placed: List<PlacedGlyph>,
) : TextLayout

/**
 * Fonts, from a `.ttf`, with nothing between here and the file but stb_truetype.
 *
 * Registered by name and by size, like the LibGDX backend and for the same reason: baking a glyph
 * atlas allocates a texture and takes milliseconds, and doing that mid-frame because a label
 * appeared at an unexpected size is exactly the stutter a game cannot afford.
 *
 * Everything registered lands on one page, along with the white block the renderer draws solid
 * colour from, so a screen of panels and labels is one texture and one draw call.
 *
 * Measuring needs no OpenGL: the atlas is baked into ordinary memory when the first measurement
 * asks for it, and uploaded only when something is first drawn. That is what lets the layout tests
 * in this module run on a machine with no display.
 *
 * Two things this does not do, and a game that needs them wants the LibGDX backend: kerning, which
 * stb's packed advances do not include, and glyphs outside [Codepoints]. This backend exists to
 * keep the toolkit honest about its own seams, not to be the one you ship.
 *
 * @param pageSize the atlas, in pixels each way. Registering more than fits is an error that says
 *   so rather than a page of missing letters.
 */
class StbFonts(private val pageSize: Int = 512) : FontProvider, AutoCloseable {

    private data class Key(val family: String, val size: Int)

    private class Registration(val family: String, val bytes: ByteBuffer, val sizes: List<Int>)

    private val registrations = mutableListOf<Registration>()
    private val faces = LinkedHashMap<Key, Face>()

    private var coverage: ByteBuffer? = null
    private var texture: GlTexture? = null
    private var whiteRegion: GlTexture? = null

    /**
     * Registers [family] at each of [sizes], baked from the bytes of a `.ttf`.
     *
     * Everything must be registered before the first measurement, because everything shares one
     * page and the page is baked once. Registering afterwards would move glyphs that text already
     * measured against is relying on staying still.
     */
    fun register(family: String, ttf: ByteArray, sizes: List<Int>) {
        check(coverage == null) { "every font must be registered before the first measurement" }
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }

        // Copied into native memory, and kept: stb reads the file's bytes again for every metric,
        // so a buffer that the garbage collector can move is not good enough.
        val bytes = BufferUtils.createByteBuffer(ttf.size).put(ttf)
        bytes.flip()
        registrations += Registration(family, bytes, sizes.distinct().sorted())
    }

    /** The families that were registered. */
    fun families(): List<String> = registrations.map { it.family }.distinct()

    /** The sizes [family] was registered at. */
    fun sizesOf(family: String): List<Int> =
        registrations.filter { it.family == family }.flatMap { it.sizes }.distinct().sorted()

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
        val wrapped = wrap(face, text, maxWidth)
        val lines = if (style.maxLines in 1 until wrapped.size) {
            wrapped.take(style.maxLines).toMutableList().also { kept ->
                kept[kept.lastIndex] = withEllipsis(face, kept.last(), style.ellipsis, maxWidth)
            }
        } else {
            wrapped
        }

        val placed = mutableListOf<PlacedGlyph>()
        var widest = 0f
        lines.forEachIndexed { index, line ->
            val baseline = face.ascent + index * style.lineHeight
            widest = maxOf(widest, place(face, line, baseline, placed))
        }

        return StbTextLayout(
            text = text,
            // The height is the style's line spacing rather than the font's own, so that two
            // labels in the same style line up whether or not their glyphs happen to be tall.
            size = Size(widest, lines.size * style.lineHeight),
            lineCount = lines.size,
            firstBaseline = face.ascent,
            placed = placed,
        )
    }

    /** Lays one line out along [baseline], adding to [into], and answers how wide it came out. */
    private fun place(face: Face, line: String, baseline: Float, into: MutableList<PlacedGlyph>): Float {
        var pen = 0f
        line.codePoints().forEach { codepoint ->
            val glyph = face.glyph(codepoint) ?: return@forEach
            if (glyph.width > 0f && glyph.height > 0f) {
                // Snapped to whole pixels, which is what stb's own quads do: a glyph baked at one
                // position and drawn half a pixel off is a glyph with soft edges.
                into += PlacedGlyph(
                    left = floor(pen + glyph.xOffset + 0.5f),
                    top = floor(baseline + glyph.yOffset + 0.5f),
                    width = glyph.width,
                    height = glyph.height,
                    glyph = glyph,
                )
            }
            pen += glyph.advance
        }
        return pen
    }

    private fun widthOf(face: Face, text: String): Float {
        var pen = 0f
        text.codePoints().forEach { pen += face.glyph(it)?.advance ?: 0f }
        return pen
    }

    /**
     * Greedy wrapping by whole words, breaking a word only when it cannot fit a line by itself.
     *
     * The same rule the toolkit's own test font uses, so a layout laid out here makes the same
     * decisions it makes there even though the widths differ.
     */
    private fun wrap(face: Face, text: String, maxWidth: Float): List<String> {
        if (!maxWidth.isFinite() || maxWidth <= 0f) return text.split('\n')

        return text.split('\n').flatMap { paragraph ->
            if (widthOf(face, paragraph) <= maxWidth) return@flatMap listOf(paragraph)

            val lines = mutableListOf<String>()
            var current = StringBuilder()

            paragraph.split(' ').forEach { word ->
                var remaining = word
                while (widthOf(face, remaining) > maxWidth) {
                    val fits = longestPrefix(face, remaining, maxWidth)
                    if (fits.isEmpty()) break
                    if (current.isNotEmpty()) {
                        lines += current.toString()
                        current = StringBuilder()
                    }
                    lines += fits
                    remaining = remaining.substring(fits.length)
                }
                val separator = if (current.isEmpty()) "" else " "
                if (current.isNotEmpty() && widthOf(face, "$current$separator$remaining") > maxWidth) {
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

    /** The most of [text] that fits in [maxWidth]. Empty when not even one character does. */
    private fun longestPrefix(face: Face, text: String, maxWidth: Float): String {
        var pen = 0f
        text.forEachIndexed { index, character ->
            pen += face.glyph(character.code)?.advance ?: 0f
            if (pen > maxWidth) return text.take(index)
        }
        return text
    }

    /** [line] with [ellipsis] on the end, shortened until the pair of them fit. */
    private fun withEllipsis(face: Face, line: String, ellipsis: String, maxWidth: Float): String {
        if (ellipsis.isEmpty()) return line
        var kept = line.trimEnd()
        while (kept.isNotEmpty() && widthOf(face, kept + ellipsis) > maxWidth) {
            kept = kept.dropLast(1).trimEnd()
        }
        return kept + ellipsis
    }

    private fun faceFor(style: TextStyle): Face {
        bake()
        val key = Key(style.family, style.size.roundToInt())
        faces[key]?.let { return it }

        val sizes = sizesOf(key.family)
        val detail = if (sizes.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $sizes"
        }
        error("no font for ${key.family} at ${key.size}: $detail")
    }

    // --- the atlas ---

    /**
     * The one texture every registered glyph lives on, uploaded on first use.
     *
     * Needs an OpenGL context, which is why it is separate from measuring: a layout test has no
     * window and should not need one.
     */
    internal fun texture(): GlTexture = texture ?: upload()

    /** Where solid colour is sampled from, so a panel and the label on it are one draw call. */
    internal fun white(): GlTexture = whiteRegion ?: texture()
        .region(WhiteBlock + 1, pageSize - WhiteBlock + 1, WhiteBlock - 2, WhiteBlock - 2)
        .also { whiteRegion = it }

    private fun upload(): GlTexture {
        bake()
        val baked = checkNotNull(coverage) { "the atlas was not baked" }
        // stb bakes one byte of coverage per pixel. The shader multiplies a colour by what it
        // samples, so the picture that goes to the driver is white with that coverage as its alpha.
        val pixels = BufferUtils.createByteBuffer(pageSize * pageSize * 4)
        for (index in 0 until pageSize * pageSize) {
            pixels.put(WhiteByte).put(WhiteByte).put(WhiteByte).put(baked.get(index))
        }
        pixels.flip()
        return GlTexture.rgba(pageSize, pageSize, pixels).also { texture = it }
    }

    /**
     * Bakes every registered size onto one page.
     *
     * The bottom [WhiteBlock] rows are kept back from stb so there is somewhere to put the white
     * block, which is the cheapest way to have one: the packer is told the page is shorter than it
     * really is and never knows the difference.
     */
    private fun bake() {
        if (coverage != null) return
        check(registrations.isNotEmpty()) { "no fonts were registered" }

        val pixels = BufferUtils.createByteBuffer(pageSize * pageSize)
        val packer = STBTTPackContext.create()
        check(STBTruetype.stbtt_PackBegin(packer, pixels, pageSize, pageSize - WhiteBlock, 0, 1)) {
            "stb_truetype would not start packing a ${pageSize}x$pageSize page"
        }
        // Missing glyphs are left out rather than drawn as a box, and asked for later they come
        // back as a question mark, which is at least readable.
        STBTruetype.stbtt_PackSetSkipMissingCodepoints(packer, true)

        try {
            registrations.forEach { registration -> bake(packer, registration) }
        } finally {
            STBTruetype.stbtt_PackEnd(packer)
        }

        // The white block, in the rows the packer was never told about.
        for (y in pageSize - WhiteBlock until pageSize) {
            for (x in WhiteBlock until WhiteBlock * 2) {
                pixels.put(y * pageSize + x, WhiteByte)
            }
        }
        coverage = pixels
    }

    private fun bake(packer: STBTTPackContext, registration: Registration) {
        val info = STBTTFontinfo.create()
        check(STBTruetype.stbtt_InitFont(info, registration.bytes)) {
            "${registration.family} is not a font stb_truetype can read"
        }

        val vertical = IntArray(1)
        val below = IntArray(1)
        val gap = IntArray(1)
        STBTruetype.stbtt_GetFontVMetrics(info, vertical, below, gap)

        registration.sizes.forEach { size ->
            val ranges = STBTTPackRange.create(Codepoints.size)
            val chars = Codepoints.mapIndexed { index, codepoints ->
                STBTTPackedchar.create(codepoints.length).also { packed ->
                    ranges[index]
                        .font_size(size.toFloat())
                        .first_unicode_codepoint_in_range(codepoints.first)
                        .num_chars(codepoints.length)
                        .chardata_for_range(packed)
                }
            }
            check(STBTruetype.stbtt_PackFontRanges(packer, registration.bytes, 0, ranges)) {
                "a ${pageSize}x$pageSize page is not big enough for every registered size; " +
                    "give StbFonts a bigger one"
            }

            val scale = STBTruetype.stbtt_ScaleForPixelHeight(info, size.toFloat())
            val glyphs = HashMap<Int, Glyph>()
            Codepoints.forEachIndexed { index, codepoints ->
                for (offset in 0 until codepoints.length) {
                    glyphOf(chars[index], offset)?.let { glyphs[codepoints.first + offset] = it }
                }
            }

            faces[Key(registration.family, size)] = Face(
                ascent = vertical[0] * scale,
                // stb measures below the baseline as negative; the toolkit measures every
                // direction from the baseline as positive.
                descent = -below[0] * scale,
                capHeight = glyphs['H'.code]?.height ?: (size * 0.7f),
                spaceAdvance = glyphs[' '.code]?.advance ?: (size * 0.3f),
                glyphs = glyphs,
            )
        }
    }

    /** One packed character, as a glyph. Null when the font had nothing for that codepoint. */
    private fun glyphOf(chars: STBTTPackedchar.Buffer, at: Int): Glyph? {
        val packed = chars[at]
        if (packed.xadvance() <= 0f && packed.x1() == packed.x0()) return null
        val page = pageSize.toFloat()
        return Glyph(
            u = packed.x0() / page,
            v = packed.y0() / page,
            u2 = packed.x1() / page,
            v2 = packed.y1() / page,
            xOffset = packed.xoff(),
            yOffset = packed.yoff(),
            width = (packed.x1() - packed.x0()).toFloat(),
            height = (packed.y1() - packed.y0()).toFloat(),
            advance = packed.xadvance(),
        )
    }

    override fun close() {
        texture?.close()
        texture = null
        whiteRegion = null
    }

    companion object {

        /**
         * What gets baked: printable ASCII, Latin-1 and the punctuation an interface actually uses.
         *
         * The ellipsis is in that last group and is the reason it exists — it is what the toolkit
         * puts on the end of text that was cut short, so a backend without it loses the one
         * character that says something is missing.
         */
        val Codepoints: List<IntRange> = listOf(
            0x20..0x7E,
            0xA0..0xFF,
            0x2010..0x2027,
            // The shapes a prompt draws, one at a time rather than by the block: a page holds a
            // fixed number of glyphs, and two hundred box-drawing characters nobody asks for is
            // the difference between fitting and not.
            0x25A0..0x25A1, // ■ □
            0x25B2..0x25B3, // ▲ △
            0x25CB..0x25CB, // ○
            0x25CF..0x25CF, // ●
            0x2630..0x2630, // ☰
            0x2713..0x2716, // ✓ ✔ ✕ ✖
        )

        private const val WhiteBlock = 8
        private const val WhiteByte = 0xFF.toByte()
    }
}
