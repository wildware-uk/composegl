package dev.wildware.composegl.kool

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.GlyphBitmap
import dev.wildware.composegl.render.GlyphKind
import dev.wildware.composegl.render.GlyphRasteriser
import dev.wildware.composegl.render.RasterFace
import java.nio.ByteBuffer

/**
 * Fonts, drawn by Android, into the shared renderer's glyph atlas.
 *
 * The phone already has a text renderer, so this does not bring one: each glyph is drawn once with
 * Android's own text drawing, measured by the same [Paint], and put on the atlas, and from then on is a
 * quad out of one texture like everything else — a screen of panels and labels is still one draw call.
 *
 * A font is registered by name and by size from a [Typeface] — `Typeface.createFromAsset(assets,
 * "fonts/Body.ttf")` in a game. A glyph is drawn the first time a label asks for it. A character the
 * typeface does not have is drawn with the system's font for it, as Android draws any text, so a
 * registered fallback family is only asked for what the system has no font for either.
 *
 * Sizes are pixels of em. Measuring needs no OpenGL. No kerning: widths are sums of advances, which is
 * what every backend's text does.
 *
 * @param pageSize each atlas page, in pixels each way. A full page starts another.
 */
class AndroidFonts private constructor(
    private val rasteriser: PaintRasteriser,
    pageSize: Int,
) : AtlasFonts(rasteriser, decoder = null, pageSize = pageSize, maxPageSize = pageSize, maxPages = 8, atlasOwner = "AndroidFonts"), AutoCloseable {

    constructor(pageSize: Int = 1024) : this(PaintRasteriser(), pageSize)

    /** Registers [family] at [sizes], drawn with [typeface]. */
    fun register(family: String, typeface: Typeface, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a font size must be positive, got $sizes" }
        rasteriser.typefaces[family] = typeface
        registerFont(family, sizes)
    }
}

/** Android's [Paint], one glyph at a time: the whole of this frontend's part in drawing text on a phone. */
internal class PaintRasteriser : GlyphRasteriser {

    val typefaces = HashMap<String, Typeface>()

    override fun face(family: String, size: Int): RasterFace? = typefaces[family]?.let { Face(it, size) }

    private class Face(typeface: Typeface, size: Int) : RasterFace {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size.toFloat()
            color = android.graphics.Color.WHITE
        }

        private val bounds = Rect()

        override val ascent: Float = -paint.fontMetrics.ascent

        override val descent: Float = paint.fontMetrics.descent

        override val capHeight: Float = bounds.also { paint.getTextBounds("H", 0, 1, it) }.let { -it.top.toFloat() }

        override fun has(codepoint: Int): Boolean = paint.hasGlyph(String(Character.toChars(codepoint)))

        override fun advance(codepoint: Int): Float = paint.measureText(String(Character.toChars(codepoint)))

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            val text = String(Character.toChars(codepoint))
            paint.getTextBounds(text, 0, text.length, bounds)
            if (bounds.isEmpty) {
                into.resize(0, 0, GlyphKind.Coverage)
                return true
            }
            // A clear pixel all round, so a smooth sample at the edge reads nothing from a neighbour.
            val width = bounds.width() + Padding * 2
            val height = bounds.height() + Padding * 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            try {
                Canvas(bitmap).drawText(text, (Padding - bounds.left).toFloat(), (Padding - bounds.top).toFloat(), paint)
                into.resize(width, height, GlyphKind.Coverage)
                val coverage = ByteBuffer.allocate(bitmap.rowBytes * height)
                bitmap.copyPixelsToBuffer(coverage)
                val pixels = into.pixels
                for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = coverage.get(y * bitmap.rowBytes + x)
            } finally {
                bitmap.recycle()
            }
            into.xOffset = (bounds.left - Padding).toFloat()
            into.yOffset = (bounds.top - Padding).toFloat()
            return true
        }
    }

    private companion object {
        const val Padding = 1
    }
}
