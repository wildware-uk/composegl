package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.set
import org.w3c.dom.ALPHABETIC
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.CanvasTextBaseline
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One glyph, as it sits in the atlas and beside the one before it. */
internal class WebGlyph(
    val u: Float,
    val v: Float,
    val u2: Float,
    val v2: Float,
    /** From the pen position to the left edge of the picture. */
    val xOffset: Float,
    /** From the baseline to the top edge of the picture. Negative: the picture sits above. */
    val yOffset: Float,
    val width: Float,
    val height: Float,
    /** How far the pen moves afterwards. */
    val advance: Float,
)

/** A glyph placed by measuring, y downwards from the top of the layout. */
internal class WebPlacedGlyph(val left: Float, val top: Float, val glyph: WebGlyph)

/** Text measured by [WebFonts]. The toolkit sees the four properties; the canvas sees the rest. */
class WebTextLayout internal constructor(
    override val text: String,
    override val size: Size,
    override val lineCount: Int,
    override val firstBaseline: Float,
    internal val placed: List<WebPlacedGlyph>,
) : TextLayout

/**
 * Fonts, drawn by the browser.
 *
 * The page already has the best text renderer on the machine, so this does not bring one: each
 * glyph is drawn once with the 2D canvas's `fillText` onto an atlas page, measured with
 * `measureText`, and from then on is a quad out of one texture like everything else — so a screen of
 * panels and labels is still one draw call.
 *
 * A font is registered by name and by size, like the other backends. Unlike them, a character that
 * was not baked up front is not a question mark: it is drawn onto the page the first time a label
 * asks for it, with whatever the browser would use for it — the registered font, or the system's
 * fallback for a script that font does not cover. Glyphs already on the page never move, so a
 * layout measured earlier stays right; only the upload is repeated.
 *
 * Sizes are CSS pixels of em, which is what FreeType means by a size too.
 *
 * Measuring needs no WebGL. The page is uploaded the first time something is drawn.
 *
 * @param pageSize the atlas, in pixels each way. Filling it is an error that says so.
 */
class WebFonts(private val pageSize: Int = 1024) : FontProvider, AutoCloseable {

    private data class Key(val family: String, val size: Int)

    private val families = LinkedHashMap<String, String>()
    private val sizes = HashMap<String, MutableSet<Int>>()
    private val faces = HashMap<Key, Face>()

    private val page: HTMLCanvasElement = (document.createElement("canvas") as HTMLCanvasElement).also {
        it.width = pageSize
        it.height = pageSize
    }
    private val drawing: CanvasRenderingContext2D = context2d(page)

    /** Where the next glyph goes: a row packer, which is all glyphs of a handful of sizes need. */
    private var penX = 0
    private var penY = 0
    private var rowHeight = 0

    private var texture: WebGlTexture? = null
    private var whiteRegion: WebGlTexture? = null
    private var dirty = true

    init {
        // The white block solid colour is sampled from, in the corner the packer never reaches.
        drawing.fillStyle = "#ffffff".toJsString()
        drawing.fillRect(WhiteBlock.toDouble(), (pageSize - WhiteBlock).toDouble(), WhiteBlock.toDouble(), WhiteBlock.toDouble())
    }

    private inner class Face(val font: String, val size: Int) {
        val glyphs = HashMap<Int, WebGlyph?>()
        val ascent: Float
        val descent: Float
        val capHeight: Float

        init {
            drawing.font = font
            val box = measureGlyph(drawing, "H")
            ascent = box.fontAscent.toFloat()
            descent = box.fontDescent.toFloat()
            capHeight = box.ascent.toFloat()
            // The printable ASCII and Latin-1 blocks go on the page together, so ordinary text
            // does not upload the page again for every new letter it meets.
            for (range in Codepoints) for (codepoint in range) glyph(codepoint)
        }

        val spaceAdvance: Float get() = glyph(' '.code)?.advance ?: (size * 0.3f)

        /** The glyph for [codepoint], drawn onto the page now if it is not there yet. */
        fun glyph(codepoint: Int): WebGlyph? = glyphs.getOrPut(codepoint) { bake(this, codepoint) }
    }

    /**
     * Registers [family] at [sizes] from a font the page can already use by CSS name — one loaded by
     * a stylesheet, or a generic like `sans-serif`.
     */
    fun registerCss(family: String, cssFamily: String, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        families[family] = cssFamily
        this.sizes.getOrPut(family) { mutableSetOf() }.addAll(sizes)
    }

    /**
     * Registers [family] at [sizes] from the bytes of a `.ttf` or `.otf`, which the browser loads as a
     * font face first. Suspends because a browser has no other way to load one.
     */
    suspend fun register(family: String, ttf: ByteArray, sizes: List<Int>) {
        val bytes = Uint8Array(ttf.size)
        for (at in ttf.indices) bytes[at] = ttf[at]
        val css = "composegl-$family"
        loadFace(css, bytes).await<JsAny?>()
        registerCss(family, css, sizes)
    }

    /** The same, fetching the font from [url] first. */
    suspend fun load(family: String, url: String, sizes: List<Int>) {
        val css = "composegl-$family"
        fetchFace(css, url).await<JsAny?>()
        registerCss(family, css, sizes)
    }

    /**
     * Where characters a family does not have come from, in order, by the names they were registered
     * under — a Chinese or Korean cut, say, loaded with [load]. The browser tries each before its own
     * system fallback, glyph by glyph, so one label can mix all of them.
     *
     * Set before text is drawn: a face already measured keeps the fonts it was made with.
     */
    fun fallBackTo(families: List<String>) {
        fallbacks = families.map { name -> requireNotNull(this.families[name]) { "no font is registered as $name" } }
    }

    private var fallbacks: List<String> = emptyList()

    /** The families that were registered. */
    fun families(): List<String> = families.keys.toList()

    /** The sizes [family] was registered at. */
    fun sizesOf(family: String): List<Int> = sizes[family].orEmpty().sorted()

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

        val placed = mutableListOf<WebPlacedGlyph>()
        var widest = 0f
        lines.forEachIndexed { index, line ->
            val baseline = face.ascent + index * style.lineHeight
            widest = maxOf(widest, place(face, line, baseline, placed))
        }

        return WebTextLayout(
            text = text,
            size = Size(widest, lines.size * style.lineHeight),
            lineCount = lines.size,
            firstBaseline = face.ascent,
            placed = placed,
        )
    }

    private fun place(face: Face, line: String, baseline: Float, into: MutableList<WebPlacedGlyph>): Float {
        var pen = 0f
        line.forEachCodePoint { codepoint ->
            val glyph = face.glyph(codepoint) ?: return@forEachCodePoint
            if (glyph.width > 0f && glyph.height > 0f) {
                // Whole pixels, so a glyph drawn at one position is not sampled half a pixel off.
                into += WebPlacedGlyph(
                    left = floor(pen + glyph.xOffset + 0.5f),
                    top = floor(baseline + glyph.yOffset + 0.5f),
                    glyph = glyph,
                )
            }
            pen += glyph.advance
        }
        return pen
    }

    private fun widthOf(face: Face, text: String): Float {
        var pen = 0f
        text.forEachCodePoint { pen += face.glyph(it)?.advance ?: 0f }
        return pen
    }

    /** Greedy wrapping by whole words, breaking a word only when it cannot fit a line by itself. */
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

    /** The most of [text] that fits in [maxWidth], never splitting a surrogate pair. */
    private fun longestPrefix(face: Face, text: String, maxWidth: Float): String {
        var pen = 0f
        var at = 0
        while (at < text.length) {
            val next = if (text[at].isHighSurrogate() && at + 1 < text.length) at + 2 else at + 1
            pen += face.glyph(codePointAt(text, at))?.advance ?: 0f
            if (pen > maxWidth) return text.take(at)
            at = next
        }
        return text
    }

    private fun withEllipsis(face: Face, line: String, ellipsis: String, maxWidth: Float): String {
        if (ellipsis.isEmpty()) return line
        var kept = line.trimEnd()
        while (kept.isNotEmpty() && widthOf(face, kept + ellipsis) > maxWidth) {
            kept = kept.dropLast(if (kept.length >= 2 && kept.last().isLowSurrogate()) 2 else 1).trimEnd()
        }
        return kept + ellipsis
    }

    private fun faceFor(style: TextStyle): Face {
        val key = Key(style.family, style.size.roundToInt())
        faces[key]?.let { return it }
        val css = families[key.family]
        val registered = sizesOf(key.family)
        if (css == null || key.size !in registered) {
            val detail = if (css == null) {
                "no font is registered under that name. Registered names: ${families().ifEmpty { listOf("none") }}"
            } else {
                "that name is registered at $registered"
            }
            error("no font for ${key.family} at ${key.size}: $detail")
        }
        // Quoted, so a family with a space or a digit in its name is still one name to CSS.
        val stack = (listOf(css) + fallbacks.filter { it != css }).joinToString(", ") { "\"$it\"" }
        return Face("${key.size}px $stack", key.size).also { faces[key] = it }
    }

    // --- the atlas ---

    /**
     * Draws one character onto the page and says where it went. Null only for a character with
     * nothing at all to it — no picture and no advance.
     */
    private fun bake(face: Face, codepoint: Int): WebGlyph? {
        val text = stringOf(codepoint)
        drawing.font = face.font
        val box = measureGlyph(drawing, text)
        val advance = box.advance.toFloat()
        val left = ceil(box.left).toInt()
        val right = ceil(box.right).toInt()
        val above = ceil(box.ascent).toInt()
        val below = ceil(box.descent).toInt()
        val inkWidth = left + right
        val inkHeight = above + below
        if (inkWidth <= 0 || inkHeight <= 0) {
            if (advance <= 0f) return null
            return WebGlyph(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, advance)
        }

        val cellWidth = inkWidth + Padding * 2
        val cellHeight = inkHeight + Padding * 2
        if (penX + cellWidth > pageSize) {
            penX = 0
            penY += rowHeight
            rowHeight = 0
        }
        check(penY + cellHeight <= pageSize - WhiteBlock) {
            "a ${pageSize}x$pageSize page is not big enough for every glyph asked for; give WebFonts a bigger one"
        }
        val x = penX
        val y = penY
        penX += cellWidth
        rowHeight = maxOf(rowHeight, cellHeight)

        drawing.fillStyle = "#ffffff".toJsString()
        drawing.textBaseline = CanvasTextBaseline.ALPHABETIC
        drawing.fillText(text, (x + Padding + left).toDouble(), (y + Padding + above).toDouble())
        dirty = true

        val page = pageSize.toFloat()
        return WebGlyph(
            u = x / page,
            v = y / page,
            u2 = (x + cellWidth) / page,
            v2 = (y + cellHeight) / page,
            xOffset = -(left + Padding).toFloat(),
            yOffset = -(above + Padding).toFloat(),
            width = cellWidth.toFloat(),
            height = cellHeight.toFloat(),
            advance = advance,
        )
    }

    /**
     * The page, as a texture on [gl], uploaded again when a glyph has been drawn onto it since.
     *
     * Premultiplication is left off, so a glyph arrives as white with its coverage in the alpha —
     * which is what the shader multiplies a colour by, and what stb bakes on the desktop.
     */
    internal fun texture(gl: GL): WebGlTexture {
        val existing = texture
        if (existing != null && existing.gl === gl) {
            if (dirty) {
                gl.bindTexture(GL.TEXTURE_2D, existing.name)
                WebGlTexture.upload(gl, page)
                gl.bindTexture(GL.TEXTURE_2D, null)
                dirty = false
            }
            return existing
        }
        existing?.close()
        whiteRegion = null
        dirty = false
        return WebGlTexture.of(gl, page, pageSize, pageSize).also { texture = it }
    }

    /** Where solid colour is sampled from, so a panel and the label on it are one draw call. */
    internal fun white(gl: GL): WebGlTexture {
        val atlas = texture(gl)
        return whiteRegion ?: atlas
            .region(WhiteBlock + 1, pageSize - WhiteBlock + 1, WhiteBlock - 2, WhiteBlock - 2)
            .also { whiteRegion = it }
    }

    override fun close() {
        texture?.close()
        texture = null
        whiteRegion = null
    }

    companion object {

        /** What goes on the page as soon as a face is first used: printable ASCII and Latin-1. */
        val Codepoints: List<IntRange> = listOf(0x20..0x7E, 0xA0..0xFF, 0x2026..0x2026)

        private const val WhiteBlock = 8

        /** Clear pixels round each glyph, so a smooth sample at its edge reads nothing from its neighbour. */
        private const val Padding = 1
    }
}

/** Each code point of a string, a surrogate pair counted once. */
internal inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var at = 0
    while (at < length) {
        val point = codePointAt(this, at)
        action(point)
        at += if (point > 0xFFFF) 2 else 1
    }
}

internal fun codePointAt(text: String, at: Int): Int {
    val high = text[at]
    if (high.isHighSurrogate() && at + 1 < text.length) {
        val low = text[at + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

internal fun stringOf(codepoint: Int): String {
    if (codepoint <= 0xFFFF) return codepoint.toChar().toString()
    val offset = codepoint - 0x10000
    return charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
}

/** What `measureText` knows about one run, the parts this needs, as numbers. */
internal external interface GlyphBox : JsAny {
    val advance: Double
    val left: Double
    val right: Double
    val ascent: Double
    val descent: Double
    val fontAscent: Double
    val fontDescent: Double
}

private fun measureGlyph(context: CanvasRenderingContext2D, text: String): GlyphBox = js(
    """(() => { const m = context.measureText(text); return { advance: m.width, left: m.actualBoundingBoxLeft, right: m.actualBoundingBoxRight, ascent: m.actualBoundingBoxAscent, descent: m.actualBoundingBoxDescent, fontAscent: m.fontBoundingBoxAscent, fontDescent: m.fontBoundingBoxDescent }; })()""",
)

private fun context2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D = js("canvas.getContext('2d', { willReadFrequently: false })")

private fun loadFace(family: String, bytes: Uint8Array): Promise<JsAny?> =
    js("new FontFace(family, bytes).load().then(face => { document.fonts.add(face); return null; })")

private fun fetchFace(family: String, url: String): Promise<JsAny?> =
    js("fetch(url).then(r => { if (!r.ok) throw new Error('could not fetch ' + url + ': ' + r.status); return r.arrayBuffer(); }).then(b => new FontFace(family, b).load()).then(face => { document.fonts.add(face); return null; })")
