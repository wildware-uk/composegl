package dev.wildware.composegl.korge

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA

/**
 * One texture for the whole interface.
 *
 * A draw call ends when the texture changes, so the cost of a screen is not how many rectangles
 * are on it — it is how many times the interface changes its mind about which picture it is
 * drawing from. So everything the toolkit itself draws lives on one page: every glyph, at every
 * registered size, of every registered family, and the single white block every plain rectangle,
 * border and shadow is made of. A panel with a label on it is then one texture and one draw call.
 *
 * The pages are ordinary [Bitmap32]s in memory. Nothing here touches OpenGL: KorGE uploads a
 * bitmap the first time it is drawn and again whenever its contents change, so a glyph packed while
 * text is being measured — on any thread, with no context — reaches the GPU the next time the canvas
 * draws from that page. That is what lets every measuring test in this module run with no display.
 *
 * A page that fills starts another one, which costs a draw call whenever a screen alternates
 * between the two. Fallback fonts and emoji pictures, when they come, are packed here the same way,
 * so a chat line with a smiley in it stays one texture for as long as the page has room.
 *
 * @param pageSize how big each page is, in pixels each way. A thousand square holds several sizes
 *   of a Latin face.
 */
class KorgeAtlas(val pageSize: Int = 1024) {

    /**
     * A rectangle on one page, in pixels from the page's top-left, with the texture coordinates of
     * its edges already worked out.
     */
    class Region internal constructor(
        val page: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        pageSize: Int,
    ) {
        val u: Float = x / pageSize.toFloat()
        val v: Float = y / pageSize.toFloat()
        val u2: Float = (x + width) / pageSize.toFloat()
        val v2: Float = (y + height) / pageSize.toFloat()
    }

    private class Shelf(var x: Int = Padding, var y: Int = Padding, var height: Int = 0)

    private val bitmaps = mutableListOf<Bitmap32>()
    private val shelves = mutableListOf<Shelf>()

    /** The pages so far. The canvas draws from these; nothing else should write to them. */
    val pages: List<Bitmap> get() = bitmaps

    /** How many pages the glyphs and the white block needed. More than one costs draw calls. */
    val pageCount: Int get() = bitmaps.size

    /**
     * Where solid colour is sampled from: the middle of a small white block, a pixel in from every
     * side, packed first so it is on page zero and stays there however many glyphs follow.
     *
     * A pixel in, because sampling the very edge of a packed region is how a rectangle picks up a
     * neighbour's colour.
     */
    val white: Region

    init {
        val block = pack(WhiteBlock, WhiteBlock)
        val page = bitmaps[block.page]
        for (y in 0 until WhiteBlock) for (x in 0 until WhiteBlock) page.setRgbaRaw(block.x + x, block.y + y, Opaque)
        white = Region(block.page, block.x + 1, block.y + 1, WhiteBlock - 2, WhiteBlock - 2, pageSize)
    }

    /**
     * Room for a [width] by [height] picture, on the current page if it fits and on a new one if it
     * does not. Shelves, filled left to right and top to bottom: glyphs of one size are all about as
     * tall as each other, which is the case shelves are good at.
     */
    fun pack(width: Int, height: Int): Region {
        require(width >= 0 && height >= 0) { "a region cannot be $width by $height" }
        require(width + Padding * 2 <= pageSize && height + Padding * 2 <= pageSize) {
            "a $width by $height picture does not fit on a $pageSize page; make the atlas bigger"
        }
        if (bitmaps.isEmpty()) newPage()
        val page = bitmaps.lastIndex
        val shelf = shelves[page]
        if (shelf.x + width + Padding > pageSize) {
            shelf.y += shelf.height + Padding
            shelf.x = Padding
            shelf.height = 0
        }
        if (shelf.y + height + Padding > pageSize) {
            newPage()
            return pack(width, height)
        }
        val region = Region(page, shelf.x, shelf.y, width, height, pageSize)
        shelf.x += width + Padding
        shelf.height = maxOf(shelf.height, height)
        return region
    }

    /**
     * Copies [coverage]'s alpha into [region] as white, so the shader can multiply it by the text's
     * colour. Only the alpha is read, which makes a premultiplied bitmap and a straight one the same.
     */
    fun putCoverage(region: Region, coverage: Bitmap, fromX: Int = 0, fromY: Int = 0) {
        val page = bitmaps[region.page]
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val alpha = coverage.getRgbaRaw(fromX + x, fromY + y).a
                // Premultiplied white is the coverage in every channel.
                page.setRgbaRaw(region.x + x, region.y + y, RGBA(alpha, alpha, alpha, alpha))
            }
        }
        changed(page)
    }

    private fun newPage() {
        // Premultiplied, because that is the only kind KorGE uploads without complaint. The batch
        // straightens a premultiplied picture before it blends, so nothing downstream can tell.
        bitmaps += Bitmap32(pageSize, pageSize, premultiplied = true)
        shelves += Shelf()
    }

    /** Tells KorGE the page is different, so it uploads it again before it is next drawn. */
    private fun changed(page: Bitmap) {
        page.contentVersion++
    }

    private companion object {
        /** Two pixels between regions, so a neighbouring glyph cannot bleed into one being magnified. */
        const val Padding = 2
        const val WhiteBlock = 4
        val Opaque = RGBA(255, 255, 255, 255)
    }
}
