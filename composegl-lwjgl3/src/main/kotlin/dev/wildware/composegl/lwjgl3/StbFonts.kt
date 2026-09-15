package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBImage
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTTPackContext
import org.lwjgl.stb.STBTTPackRange
import org.lwjgl.stb.STBTTPackedchar
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryStack
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
    /**
     * True for a picture with colours of its own — an emoji — rather than a letter. A letter is
     * coverage and takes the text's colour; a picture is drawn as it is.
     */
    val colour: Boolean = false,
)

/** One font at one size: what it looks like, and every glyph that was baked for it. */
internal class Face(
    val ascent: Float,
    val descent: Float,
    val capHeight: Float,
    val spaceAdvance: Float,
    val glyphs: Map<Int, Glyph>,
)

/**
 * A face with its fallbacks behind it: where each character of a piece of text actually comes from.
 *
 * Every metric is the [primary]'s. A fallback's glyphs are measured from the baseline like the
 * primary's are, so they sit on the same line without being moved.
 */
internal class Chain(val primary: Face, private val fallbacks: List<Face>) {

    /**
     * The glyph for [codepoint]: the primary's, else the first fallback's, else the primary's `?`
     * — which is at least readable. Null for a character that draws nothing at all.
     */
    fun glyph(codepoint: Int): Glyph? {
        if (codepoint in Invisible) return null
        primary.glyphs[codepoint]?.let { return it }
        for (face in fallbacks) face.glyphs[codepoint]?.let { return it }
        return primary.glyphs['?'.code]
    }

    private companion object {
        /** The variation selectors: they choose how the character before them looks, and look like nothing. */
        val Invisible = setOf(0xFE0E, 0xFE0F)
    }
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
 * colour from, so a screen of panels and labels is one texture and one draw call. The page starts
 * at the size it was given and doubles until everything fits, up to a limit.
 *
 * Measuring needs no OpenGL: the atlas is baked into ordinary memory when the first measurement
 * asks for it, and uploaded only when something is first drawn. That is what lets the layout tests
 * in this module run on a machine with no display.
 *
 * **Characters a font does not have** come from the families named by [fallBackTo], tried in
 * order, one character at a time. A fallback font is registered like any other, with the
 * characters it is for — [codepointsOf] turns the text a game expects into the list — and colour
 * emoji are registered as pictures with [registerPictures].
 *
 * Two things this does not do, and a game that needs them wants the LibGDX backend: kerning, which
 * stb's packed advances do not include, and characters generated when they are first asked for —
 * everything here is baked before the first measurement. This backend exists to keep the toolkit
 * honest about its own seams, not to be the one you ship.
 *
 * @param pageSize the atlas, in pixels each way, to begin with.
 * @param maxPageSize the most the page may double to. Registering more than fits in that is an
 *   error that says so rather than a page of missing letters.
 */
class StbFonts(private var pageSize: Int = 512, private val maxPageSize: Int = 4096) : FontProvider, AutoCloseable {

    private data class Key(val family: String, val size: Int)

    private class Registration(
        val family: String,
        val bytes: ByteBuffer,
        val sizes: List<Int>,
        val codepoints: List<IntRange>,
    )

    /** One picture, at one size, as straight-alpha RGBA ready to go onto the page. */
    private class Picture(val family: String, val size: Int, val codepoint: Int, val width: Int, val height: Int, val rgba: ByteArray)

    /** Where a [Picture] went on the page. */
    private class Placed(val picture: Picture, val x: Int, val top: Int)

    private val registrations = mutableListOf<Registration>()
    private val pictures = mutableListOf<Picture>()
    private val faces = LinkedHashMap<Key, Face>()
    private val pictureFaces = LinkedHashMap<Key, Face>()
    private val chains = HashMap<Key, Chain>()

    private var everyFamilyFallsBackTo: List<String> = emptyList()
    private val fallbacksByFamily = HashMap<String, List<String>>()

    private var coverage: ByteBuffer? = null
    private var placedPictures: List<Placed> = emptyList()
    private var texture: GlTexture? = null
    private var whiteRegion: GlTexture? = null

    /** The page's size each way, once baked: [pageSize] doubled as many times as it needed. */
    val bakedPageSize: Int get() {
        bake()
        return pageSize
    }

    /**
     * Registers [family] at each of [sizes], baked from the bytes of a `.ttf`.
     *
     * Everything must be registered before the first measurement, because everything shares one
     * page and the page is baked once. Registering afterwards would move glyphs that text already
     * measured against is relying on staying still.
     *
     * @param codepoints the characters to bake. [Codepoints] — Latin and the punctuation an
     *   interface uses — unless a font is for something else: a fallback for Chinese names wants
     *   `codepointsOf(everyNameInTheGame)`, not the thirty thousand characters in the file.
     */
    fun register(family: String, ttf: ByteArray, sizes: List<Int>, codepoints: List<IntRange> = Codepoints) {
        check(coverage == null) { "every font must be registered before the first measurement" }
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        require(codepoints.isNotEmpty()) { "registering $family with no characters would register nothing" }

        // Copied into native memory, and kept: stb reads the file's bytes again for every metric,
        // so a buffer that the garbage collector can move is not good enough.
        val bytes = BufferUtils.createByteBuffer(ttf.size).put(ttf)
        bytes.flip()
        registrations += Registration(family, bytes, sizes.distinct().sorted(), codepoints)
        chains.clear()
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes].
     *
     * How colour emoji get into text: stb_truetype only makes coverage, so an emoji font's colour
     * glyphs are not something it can read. Each picture is scaled to the size tall, keeping its
     * shape, and baked onto the same page as the letters. It sits a little below the baseline, the
     * way an emoji font's glyphs do, and is drawn in its own colours whatever colour the text is.
     *
     * A family of pictures can only be a fallback: name it in [fallBackTo].
     *
     * @param pictures encoded pictures — a PNG, say — by the character each one draws: `"😀"`,
     *   `"❤️"`. One character each, with or without the emoji variation selector. A sequence joined
     *   into one emoji — a family, a flag, a skin tone — is refused, because it would need text
     *   shaping to find.
     */
    fun registerPictures(family: String, pictures: Map<String, ByteArray>, sizes: List<Int>) {
        check(coverage == null) { "every picture must be registered before the first measurement" }
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a size must be positive, got $sizes" }
        require(pictures.isNotEmpty()) { "registering $family with no pictures would register nothing" }
        require(registrations.none { it.family == family }) { "$family is already a font; pictures need a name of their own" }

        pictures.forEach { (text, encoded) ->
            val codepoint = codepointOf(text)
            val (width, height, rgba) = decode(encoded)
            sizes.distinct().forEach { size ->
                val scaledWidth = (width * size / height.toFloat()).roundToInt().coerceAtLeast(1)
                this.pictures += Picture(
                    family,
                    size,
                    codepoint,
                    scaledWidth,
                    size,
                    shrink(rgba, width, height, scaledWidth, size),
                )
            }
        }
        chains.clear()
    }

    /**
     * Where every family looks for a character its own font does not have: each of [families], in
     * order.
     *
     * Checked one character at a time. The main font always wins for a character it has, and a
     * character none of them has comes out as the main font's `?`. A fallback must be registered
     * at every size it is asked for. Not followed any further: a fallback's own fallbacks are not
     * tried.
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

    /** The families that were registered, fonts and pictures both. */
    fun families(): List<String> = (registrations.map { it.family } + pictures.map { it.family }).distinct()

    /** The sizes [family] was registered at. */
    fun sizesOf(family: String): List<Int> =
        (registrations.filter { it.family == family }.flatMap { it.sizes } + pictures.filter { it.family == family }.map { it.size })
            .distinct().sorted()

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
        val wrapped = wrap(chain, text, maxWidth)
        val lines = if (style.maxLines in 1 until wrapped.size) {
            wrapped.take(style.maxLines).toMutableList().also { kept ->
                kept[kept.lastIndex] = withEllipsis(chain, kept.last(), style.ellipsis, maxWidth)
            }
        } else {
            wrapped
        }

        val placed = mutableListOf<PlacedGlyph>()
        var widest = 0f
        lines.forEachIndexed { index, line ->
            val baseline = face.ascent + index * style.lineHeight
            widest = maxOf(widest, place(chain, line, baseline, placed))
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
    private fun place(chain: Chain, line: String, baseline: Float, into: MutableList<PlacedGlyph>): Float {
        var pen = 0f
        line.codePoints().forEach { codepoint ->
            val glyph = chain.glyph(codepoint) ?: return@forEach
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

    private fun widthOf(chain: Chain, text: String): Float {
        var pen = 0f
        text.codePoints().forEach { pen += chain.glyph(it)?.advance ?: 0f }
        return pen
    }

    /**
     * Greedy wrapping by whole words, breaking a word only when it cannot fit a line by itself.
     *
     * The same rule the toolkit's own test font uses, so a layout laid out here makes the same
     * decisions it makes there even though the widths differ. Chinese and Japanese have no spaces
     * to break at, so a line of it is one long word broken where it has to be — which, for text
     * made of characters rather than words, is the right place anyway.
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

    /**
     * The most of [text] that fits in [maxWidth], a whole character at a time — never half an
     * emoji. Empty when not even one character does.
     */
    private fun longestPrefix(chain: Chain, text: String, maxWidth: Float): String {
        var pen = 0f
        var at = 0
        while (at < text.length) {
            val codepoint = text.codePointAt(at)
            pen += chain.glyph(codepoint)?.advance ?: 0f
            if (pen > maxWidth) return text.take(at)
            at += Character.charCount(codepoint)
        }
        return text
    }

    /** [line] with [ellipsis] on the end, shortened a character at a time until the pair of them fit. */
    private fun withEllipsis(chain: Chain, line: String, ellipsis: String, maxWidth: Float): String {
        if (ellipsis.isEmpty()) return line
        var kept = line.trimEnd()
        while (kept.isNotEmpty() && widthOf(chain, kept + ellipsis) > maxWidth) {
            kept = kept.substring(0, kept.offsetByCodePoints(kept.length, -1)).trimEnd()
        }
        return kept + ellipsis
    }

    private fun chainFor(style: TextStyle): Chain {
        bake()
        val key = Key(style.family, style.size.roundToInt())
        chains[key]?.let { return it }

        val primary = faces[key] ?: missing(key, "")
        val fallbacks = fallbacksOf(key.family).map { family ->
            val fallback = Key(family, key.size)
            faces[fallback] ?: pictureFaces[fallback] ?: missing(fallback, ", which ${key.family} falls back to")
        }
        return Chain(primary, fallbacks).also { chains[key] = it }
    }

    private fun missing(key: Key, context: String): Nothing {
        val sizes = sizesOf(key.family)
        if (context.isEmpty() && sizes.isNotEmpty() && registrations.none { it.family == key.family }) {
            error("no font for ${key.family} at ${key.size}: that name is pictures, which can only be a fallback")
        }
        val detail = if (sizes.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $sizes"
        }
        error("no font for ${key.family} at ${key.size}$context: $detail")
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
        // Pictures are the exception, and go on over the top in their own colours.
        placedPictures.forEach { placed ->
            val picture = placed.picture
            for (row in 0 until picture.height) {
                for (column in 0 until picture.width) {
                    val from = (row * picture.width + column) * 4
                    val to = ((placed.top + row) * pageSize + placed.x + column) * 4
                    for (channel in 0 until 4) pixels.put(to + channel, picture.rgba[from + channel])
                }
            }
        }
        pixels.flip()
        return GlTexture.rgba(pageSize, pageSize, pixels).also { texture = it }
    }

    /**
     * Bakes every registered size onto one page, doubling the page until it all fits.
     *
     * Doubling rather than a second page, because a second page is a second texture and a second
     * draw call for every screen that mixes text from both — the thing the one page is for.
     */
    private fun bake() {
        if (coverage != null) return
        check(registrations.isNotEmpty()) { "no fonts were registered" }
        while (!tryBake()) {
            check(pageSize * 2 <= maxPageSize) {
                "a ${pageSize}x$pageSize page is not big enough for every registered size, and it may not " +
                    "grow past $maxPageSize; give StbFonts a bigger maxPageSize or register fewer sizes"
            }
            pageSize *= 2
        }
    }

    /**
     * One attempt at baking onto a page of [pageSize]. False when it does not fit.
     *
     * The bottom rows are kept back from stb so there is somewhere to put the white block and the
     * pictures, which is the cheapest way to have them: the packer is told the page is shorter than
     * it really is and never knows the difference.
     */
    private fun tryBake(): Boolean {
        faces.clear()
        pictureFaces.clear()
        chains.clear()

        val shelves = shelve() ?: return false
        val reserved = maxOf(WhiteBlock, shelves.second)

        val pixels = BufferUtils.createByteBuffer(pageSize * pageSize)
        val packer = STBTTPackContext.create()
        check(STBTruetype.stbtt_PackBegin(packer, pixels, pageSize, pageSize - reserved, 0, 1)) {
            "stb_truetype would not start packing a ${pageSize}x$pageSize page"
        }
        // Missing glyphs are left out rather than drawn as a box, and asked for later they come
        // back as a question mark, which is at least readable.
        STBTruetype.stbtt_PackSetSkipMissingCodepoints(packer, true)

        try {
            if (!registrations.all { registration -> bake(packer, registration) }) return false
        } finally {
            STBTruetype.stbtt_PackEnd(packer)
        }

        // The white block, in the rows the packer was never told about.
        for (y in pageSize - WhiteBlock until pageSize) {
            for (x in WhiteBlock until WhiteBlock * 2) {
                pixels.put(y * pageSize + x, WhiteByte)
            }
        }

        placedPictures = shelves.first
        placedPictures.groupBy { Key(it.picture.family, it.picture.size) }.forEach { (key, placed) ->
            pictureFaces[key] = Face(0f, 0f, 0f, 0f, placed.associate { it.picture.codepoint to glyphOf(it) })
        }
        coverage = pixels
        return true
    }

    /**
     * Where each picture goes, in rows along the bottom of the page beside the white block, and how
     * many rows that took. Null when they would take more than half the page.
     */
    private fun shelve(): Pair<List<Placed>, Int>? {
        val placed = mutableListOf<Placed>()
        var x = WhiteBlock * 2 + Gap
        var shelfBottom = 0
        var shelfHeight = WhiteBlock
        pictures.sortedByDescending { it.height }.forEach { picture ->
            if (picture.width + Gap * 2 > pageSize) return null
            if (x + picture.width + Gap > pageSize) {
                shelfBottom += shelfHeight + Gap
                shelfHeight = 0
                x = Gap
            }
            placed += Placed(picture, x, pageSize - shelfBottom - picture.height)
            x += picture.width + Gap
            shelfHeight = maxOf(shelfHeight, picture.height)
        }
        val height = shelfBottom + shelfHeight + Gap
        return if (height > pageSize / 2) null else placed to height
    }

    private fun glyphOf(placed: Placed): Glyph {
        val picture = placed.picture
        val page = pageSize.toFloat()
        val gap = (picture.size / 16f).roundToInt().coerceAtLeast(1)
        return Glyph(
            u = placed.x / page,
            v = placed.top / page,
            u2 = (placed.x + picture.width) / page,
            v2 = (placed.top + picture.height) / page,
            xOffset = gap.toFloat(),
            // Its bottom a little below the baseline, where an emoji font puts it: level with the
            // letters' descenders, sharing their middle.
            yOffset = -(picture.height - (picture.size * PictureDrop).roundToInt()).toFloat(),
            width = picture.width.toFloat(),
            height = picture.height.toFloat(),
            advance = (picture.width + gap * 2).toFloat(),
            colour = true,
        )
    }

    /** Bakes one registration. False when the page ran out of room. */
    private fun bake(packer: STBTTPackContext, registration: Registration): Boolean {
        val info = STBTTFontinfo.create()
        check(STBTruetype.stbtt_InitFont(info, registration.bytes)) {
            "${registration.family} is not a font stb_truetype can read"
        }

        val vertical = IntArray(1)
        val below = IntArray(1)
        val gap = IntArray(1)
        STBTruetype.stbtt_GetFontVMetrics(info, vertical, below, gap)

        val codepoints = registration.codepoints
        registration.sizes.forEach { size ->
            val ranges = STBTTPackRange.create(codepoints.size)
            val chars = codepoints.mapIndexed { index, range ->
                STBTTPackedchar.create(range.length).also { packed ->
                    ranges[index]
                        .font_size(size.toFloat())
                        .first_unicode_codepoint_in_range(range.first)
                        .num_chars(range.length)
                        .chardata_for_range(packed)
                }
            }
            if (!STBTruetype.stbtt_PackFontRanges(packer, registration.bytes, 0, ranges)) return false

            val scale = STBTruetype.stbtt_ScaleForPixelHeight(info, size.toFloat())
            val glyphs = HashMap<Int, Glyph>()
            codepoints.forEachIndexed { index, range ->
                for (offset in 0 until range.length) {
                    glyphOf(chars[index], offset)?.let { glyphs[range.first + offset] = it }
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
        return true
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

        /**
         * Every character in [text], as the ranges [register] takes: sorted, each run of
         * neighbours joined into one.
         *
         * For a fallback font with far more characters than a page holds, where what a game needs
         * is the characters its own text uses — `codepointsOf(names + chat)` — rather than a block.
         */
        fun codepointsOf(text: String): List<IntRange> {
            val sorted = text.codePoints().distinct().sorted().toArray()
            val ranges = mutableListOf<IntRange>()
            var start = -1
            var end = -1
            for (codepoint in sorted) {
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

        private const val WhiteBlock = 8
        private const val WhiteByte = 0xFF.toByte()

        /** Empty pixels between two pictures, so a smoothly sampled edge does not pick up the next one. */
        private const val Gap = 1

        /** How far below the baseline a picture's bottom edge sits, as a share of the text size. */
        private const val PictureDrop = 0.12f

        private const val VariationSelector = 0xFE0F

        /** The one character a picture key stands for. */
        private fun codepointOf(text: String): Int {
            val codepoints = text.codePoints().toArray().let {
                if (it.size == 2 && it[1] == VariationSelector) intArrayOf(it[0]) else it
            }
            require(codepoints.size == 1) {
                "a picture stands for one character, and \"$text\" is ${codepoints.size}; " +
                    "sequences joined into one emoji are not supported"
            }
            return codepoints[0]
        }

        /** [encoded] as width, height and straight-alpha RGBA. */
        private fun decode(encoded: ByteArray): Triple<Int, Int, ByteArray> {
            val bytes = BufferUtils.createByteBuffer(encoded.size).put(encoded)
            bytes.flip()
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val pixels = STBImage.stbi_load_from_memory(bytes, width, height, channels, 4)
                    ?: error("stb_image could not read that picture: ${STBImage.stbi_failure_reason()}")
                try {
                    // Read through a duplicate: LWJGL frees the buffer at its position, and reading
                    // the original would move that to the end and hand free() a pointer it never
                    // gave out.
                    return Triple(width[0], height[0], ByteArray(pixels.remaining()).also { pixels.duplicate().get(it) })
                } finally {
                    STBImage.stbi_image_free(pixels)
                }
            }
        }

        /**
         * [rgba] shrunk to [toWidth] by [toHeight], each new pixel the average of the ones it
         * covers.
         *
         * Weighted by alpha, so the invisible pixels round an emoji — whatever colour a paint
         * program left in them — do not darken its edge.
         */
        private fun shrink(rgba: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): ByteArray {
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
