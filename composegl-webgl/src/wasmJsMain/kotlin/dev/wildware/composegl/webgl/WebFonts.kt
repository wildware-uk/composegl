package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.GlyphBitmap
import dev.wildware.composegl.render.GlyphKind
import dev.wildware.composegl.render.GlyphRasteriser
import dev.wildware.composegl.render.RasterFace
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.ALPHABETIC
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.CanvasTextBaseline
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise
import kotlin.math.ceil
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/** Text measured by [WebFonts]: the shared renderer's layout, under the name this backend has always used. */
typealias WebTextLayout = AtlasTextLayout

/**
 * Fonts, drawn by the browser, into the shared renderer's glyph atlas.
 *
 * The page already has the best text renderer on the machine, so this does not bring one: each
 * glyph is drawn once with the 2D canvas's `fillText`, measured with `measureText`, and put on the
 * atlas, and from then on is a quad out of one texture like everything else — so a screen of panels
 * and labels is still one draw call.
 *
 * A font is registered by name and by size, like the other backends. Unlike them, a character that
 * was not made up front is not a question mark: it is drawn the first time a label asks for it, with
 * whatever the browser would use for it — the registered font, the families named by [fallBackTo], or
 * the system's fallback for a script none of them covers. A colour emoji the browser draws keeps its
 * colours. Glyphs already on the atlas never move, so a layout measured earlier stays right.
 *
 * Sizes are CSS pixels of em, which is what FreeType means by a size too.
 *
 * Measuring needs no WebGL. The atlas is uploaded the first time something is drawn, and after that
 * only the part of it that changed.
 *
 * No kerning: widths are sums of advances, which is what every backend's text does.
 *
 * @param pageSize each atlas page, in pixels each way. A full page starts another.
 */
class WebFonts private constructor(
    private val rasteriser: CanvasRasteriser,
    pageSize: Int,
) : AtlasFonts(rasteriser, decoder = null, pageSize = pageSize, maxPageSize = pageSize, maxPages = 8, atlasOwner = "WebFonts"), AutoCloseable {

    constructor(pageSize: Int = 1024) : this(CanvasRasteriser(), pageSize)

    /**
     * Registers [family] at [sizes] from a font the page can already use by CSS name — one loaded by
     * a stylesheet, or a generic like `sans-serif`.
     */
    fun registerCss(family: String, cssFamily: String, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        rasteriser.css[family] = cssFamily
        registerFont(family, sizes)
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
     * system fallback, glyph by glyph, so one label can mix all of them, and a fallback need not be
     * registered at every size.
     *
     * Set before text is drawn: a face already measured keeps the fonts it was made with.
     */
    override fun fallBackTo(families: List<String>) {
        rasteriser.fallbacks = cssNames(families)
    }

    /** Where [family] alone looks for a character it does not have, instead of the list for everyone. */
    override fun fallBackTo(family: String, families: List<String>) {
        rasteriser.fallbacksByFamily[family] = cssNames(families)
    }

    private fun cssNames(families: List<String>) =
        families.map { name -> requireNotNull(rasteriser.css[name]) { "no font is registered as $name" } }

    companion object {
        /** Printable ASCII and Latin-1: what every face is certain to be asked for first. */
        val Codepoints: List<IntRange> = listOf(0x20..0x7E, 0xA0..0xFF, 0x2026..0x2026)
    }
}

/**
 * The browser's 2D canvas, one glyph at a time: the whole of this backend's part in drawing text.
 *
 * A face is a CSS font list — the family's own font, then its fallbacks — so the browser picks the
 * font for each character and falls back to the system's for a script nothing registered covers.
 */
internal class CanvasRasteriser : GlyphRasteriser {

    /** Registered family to the CSS family it is drawn with. */
    val css = HashMap<String, String>()
    var fallbacks: List<String> = emptyList()
    val fallbacksByFamily = HashMap<String, List<String>>()

    /** Where one glyph is drawn to be read back. Grown to the biggest glyph asked for. */
    private val scratch: HTMLCanvasElement = (document.createElement("canvas") as HTMLCanvasElement).also {
        it.width = 64
        it.height = 64
    }
    private var drawing: CanvasRenderingContext2D = context2d(scratch)

    override fun face(family: String, size: Int): RasterFace? {
        val name = css[family] ?: return null
        val list = fallbacksByFamily[family] ?: fallbacks
        // Quoted, so a family with a space or a digit in its name is still one name to CSS.
        val stack = (listOf(name) + list.filter { it != name }).joinToString(", ") { "\"$it\"" }
        return Face("${size}px $stack")
    }

    private inner class Face(private val font: String) : RasterFace {

        override val ascent: Float
        override val descent: Float
        override val capHeight: Float

        init {
            drawing.font = font
            val box = measureGlyph(drawing, "H")
            ascent = box.fontAscent.toFloat()
            descent = box.fontDescent.toFloat()
            capHeight = box.ascent.toFloat()
        }

        private var measured = -1
        private var box: GlyphBox? = null

        /** Asked three times a glyph — has, draw, advance — and measured once. */
        private fun measure(codepoint: Int): GlyphBox {
            box?.let { if (measured == codepoint) return it }
            drawing.font = font
            return measureGlyph(drawing, stringOf(codepoint)).also {
                box = it
                measured = codepoint
            }
        }

        override fun has(codepoint: Int): Boolean {
            val box = measure(codepoint)
            return box.advance > 0.0 || (ceil(box.left) + ceil(box.right) > 0.0 && ceil(box.ascent) + ceil(box.descent) > 0.0)
        }

        override fun advance(codepoint: Int): Float = measure(codepoint).advance.toFloat()

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            val box = measure(codepoint)
            val left = ceil(box.left).toInt()
            val above = ceil(box.ascent).toInt()
            val inkWidth = left + ceil(box.right).toInt()
            val inkHeight = above + ceil(box.descent).toInt()
            if (inkWidth <= 0 || inkHeight <= 0) {
                into.resize(0, 0, GlyphKind.Coverage)
                return true
            }
            // A clear pixel all round, so a smooth sample at the edge reads nothing from a neighbour.
            val width = inkWidth + Padding * 2
            val height = inkHeight + Padding * 2
            if (scratch.width < width || scratch.height < height) {
                scratch.width = maxOf(scratch.width, width)
                scratch.height = maxOf(scratch.height, height)
                drawing = context2d(scratch)
            }
            drawing.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
            drawing.font = font
            drawing.fillStyle = "#ffffff".toJsString()
            drawing.textBaseline = CanvasTextBaseline.ALPHABETIC
            drawing.fillText(stringOf(codepoint), (Padding + left).toDouble(), (Padding + above).toDouble())
            readGlyph(drawing, width, height, into)
            into.xOffset = -(left + Padding).toFloat()
            into.yOffset = -(above + Padding).toFloat()
            return true
        }
    }

    private companion object {
        const val Padding = 1
    }
}

/**
 * The glyph just drawn at the scratch canvas's top-left, into [into]: its coverage when it came out
 * white, as a letter does, or its own colours when it did not, as an emoji does. Copied through the
 * module's memory in one go rather than a call into the page per byte.
 */
@OptIn(UnsafeWasmMemoryApi::class)
private fun readGlyph(context: CanvasRenderingContext2D, width: Int, height: Int, into: GlyphBitmap) {
    val count = width * height * 4
    withScopedMemoryAllocator { allocator ->
        val start = allocator.allocate(count)
        copyImageData(wasmMemory, context, width, height, start.address.toInt())
        var colour = false
        var at = 0
        while (at < count && !colour) {
            val alpha = (start + at + 3).loadByte().toInt() and 0xFF
            if (alpha >= 128) {
                for (channel in 0 until 3) if ((start + at + channel).loadByte().toInt() and 0xFF < 200) colour = true
            }
            at += 4
        }
        if (colour) {
            into.resize(width, height, GlyphKind.Colour)
            val pixels = into.pixels
            for (index in 0 until count) pixels[index] = (start + index).loadByte()
        } else {
            into.resize(width, height, GlyphKind.Coverage)
            val pixels = into.pixels
            for (index in 0 until width * height) pixels[index] = (start + index * 4 + 3).loadByte()
        }
    }
}

private fun copyImageData(memory: JsAny, context: CanvasRenderingContext2D, width: Int, height: Int, address: Int): Unit =
    js("{ new Uint8Array(memory.buffer, address, width * height * 4).set(context.getImageData(0, 0, width, height).data); }")

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

private fun context2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D = js("canvas.getContext('2d', { willReadFrequently: true })")

private fun loadFace(family: String, bytes: Uint8Array): Promise<JsAny?> =
    js("new FontFace(family, bytes).load().then(face => { document.fonts.add(face); return null; })")

private fun fetchFace(family: String, url: String): Promise<JsAny?> =
    js("fetch(url).then(r => { if (!r.ok) throw new Error('could not fetch ' + url + ': ' + r.status); return r.arrayBuffer(); }).then(b => new FontFace(family, b).load()).then(face => { document.fonts.add(face); return null; })")
