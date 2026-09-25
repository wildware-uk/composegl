package dev.wildware.composegl.render

/**
 * The pages glyphs and pictures live on, packed onto shelves, with a white block on the first page
 * that solid colour is sampled from — so a panel and the label on it are one texture and one draw.
 *
 * Pages are kept in memory as straight RGBA and uploaded per device, only the part that changed.
 * Keeping them is what lets a device that lost its context rebuild without asking anybody to
 * rasterise anything again.
 *
 * A page doubles while nothing has been uploaded from it, up to [maxPageSize]; after that, or once
 * it has been drawn, a full page means a new page, up to [maxPages].
 *
 * @param owner how a "full" error names what to make bigger.
 * @param smooth whether pages are sampled smoothly. Glyphs are drawn at whole pixels, where the two
 *   agree; they part only for a copy drawn a fraction of a pixel off, such as an outline's ring.
 */
class GlyphAtlas(
    pageSize: Int = 512,
    val maxPageSize: Int = 4096,
    val maxPages: Int = 8,
    private val owner: String = "the glyph atlas",
    val smooth: Boolean = true,
) {

    init {
        require(pageSize >= WhiteBlock * 2) { "a page of $pageSize pixels is too small to hold anything" }
        require(maxPageSize >= pageSize) { "the largest page cannot be smaller than the first" }
        require(maxPages >= 1) { "an atlas needs at least one page" }
    }

    private val pages = ArrayList<AtlasPage>()

    /** The first page, and where its white block is. */
    val white: AtlasSpot

    init {
        pages += AtlasPage(pageSize, smooth)
        white = placeWhite()
    }

    /**
     * Glyphs drawn again at the screen's own pixel size, for a frame scaled up; see [SharpGlyphs].
     * Null for an atlas no fonts made, which draws its glyphs as they are.
     */
    internal var sharp: SharpGlyphs? = null

    /**
     * Gradients of more than two colours, baked into strips of this atlas; see [GradientRamps].
     * Made the first time one is drawn, so an atlas nobody paints a run of stops onto has none.
     */
    internal val ramps: GradientRamps by lazy { GradientRamps(this) }

    private fun placeWhite(): AtlasSpot {
        val page = pages[0]
        val spot = checkNotNull(page.place(WhiteBlock, WhiteBlock, maxPageSize = 0)) { "no room for the white block" }
        val pixels = page.pixels
        for (y in 0 until WhiteBlock) for (x in 0 until WhiteBlock) pixels[((spot.y + y) * page.size + spot.x + x) * 4 + 3] = -1
        return spot
    }

    val pageCount: Int get() = pages.size

    fun page(index: Int): AtlasPage = pages[index]

    /**
     * Room for a [width] by [height] picture, with a transparent pixel of gap after it each way so a
     * smoothly sampled edge does not pick up its neighbour. Throws when no page can hold it.
     */
    fun place(width: Int, height: Int): AtlasSpot {
        placeOrNull(width, height)?.let { return it }
        val size = pages.last().size
        error(
            "a ${size}x$size page is not big enough for every registered size, and it may not grow past " +
                "$maxPageSize${if (maxPages > 1) " or past $maxPages pages" else ""}; give $owner a bigger " +
                "maxPageSize or register fewer sizes",
        )
    }

    /** The same, answering null rather than throwing when no page can hold it. */
    fun placeOrNull(width: Int, height: Int): AtlasSpot? {
        require(width > 0 && height > 0) { "nothing to place: ${width}x$height" }
        for (page in pages) page.place(width, height, maxPageSize)?.let { return it }
        if (pages.size < maxPages && width + Gap <= maxPageSize && height + Gap <= maxPageSize) {
            val page = AtlasPage(pages.last().size, smooth)
            pages += page
            page.place(width, height, maxPageSize)?.let { return it }
        }
        return null
    }

    /**
     * Empties every page but the white block, keeping the pages and their textures: the next upload
     * sends each page whole. Everything placed before is gone, so nothing drawn from it may be left
     * waiting to be drawn.
     */
    internal fun clear() {
        pages.forEach { it.clear() }
        check(placeWhite().let { it.x == white.x && it.y == white.y }) { "the white block moved" }
    }

    /** The context of [device] went away with its textures: forget them. */
    fun forget(device: GpuDevice) = pages.forEach { it.forget(device) }

    /** Gives every page uploaded to [device] back to it: the device is going away, the pages are not. */
    fun release(device: GpuDevice) = pages.forEach { it.release(device) }

    /** Gives every uploaded page back to the device it was uploaded to. */
    fun close() = pages.forEach { it.close() }

    companion object {
        /** The white block's size each way. Sampled at its middle, so filtering never reaches an edge. */
        const val WhiteBlock = 8

        /** Empty pixels between two pictures. */
        const val Gap = 1
    }
}

/** Where something was placed. */
class AtlasSpot(val page: AtlasPage, val x: Int, val y: Int)

/** One page of the atlas: its pixels, its shelves, and a texture per device it was drawn on. */
class AtlasPage internal constructor(size: Int, private val smooth: Boolean = true) {

    var size: Int = size
        private set

    /** Straight RGBA, top row first. White with no alpha wherever nothing is. */
    internal var pixels: ByteArray = blank(size)
        private set

    private class Shelf(val y: Int, val height: Int) {
        var x = 0
    }

    private val shelves = ArrayList<Shelf>()
    private var nextShelf = 0

    private class Upload(val device: GpuDevice, val texture: DeviceTexture, val size: Int) {
        var left = 0
        var top = 0
        var right = size
        var bottom = size

        fun mark(x: Int, y: Int, width: Int, height: Int) {
            if (right <= left) {
                left = x
                top = y
                right = x + width
                bottom = y + height
            } else {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + width)
                bottom = maxOf(bottom, y + height)
            }
        }
    }

    private val uploads = ArrayList<Upload>(1)

    internal fun canGrow(maxPageSize: Int) = uploads.isEmpty() && size * 2 <= maxPageSize

    internal fun place(width: Int, height: Int, maxPageSize: Int): AtlasSpot? {
        val needWidth = width + GlyphAtlas.Gap
        val needHeight = height + GlyphAtlas.Gap
        while (true) {
            // The lowest shelf that is tall enough and has room left.
            var best: Shelf? = null
            for (shelf in shelves) {
                if (shelf.height >= needHeight && shelf.x + needWidth <= size && (best == null || shelf.height < best.height)) {
                    best = shelf
                }
            }
            if (best != null) {
                val spot = AtlasSpot(this, best.x, best.y)
                best.x += needWidth
                return spot
            }
            if (nextShelf + needHeight <= size && needWidth <= size) {
                val shelf = Shelf(nextShelf, needHeight)
                shelves += shelf
                nextShelf += needHeight
                shelf.x = needWidth
                return AtlasSpot(this, 0, shelf.y)
            }
            if (maxPageSize <= 0 || !canGrow(maxPageSize)) return null
            growTo(size * 2)
        }
    }

    internal fun clear() {
        pixels = blank(size)
        shelves.clear()
        nextShelf = 0
        changed(0, 0, size, size)
    }

    private fun growTo(bigger: Int) {
        val old = pixels
        val oldSize = size
        pixels = blank(bigger)
        for (row in 0 until oldSize) old.copyInto(pixels, row * bigger * 4, row * oldSize * 4, (row + 1) * oldSize * 4)
        size = bigger
    }

    /** Coverage, one byte a pixel, written as white with that alpha at [x], [y]. */
    internal fun writeCoverage(x: Int, y: Int, width: Int, height: Int, coverage: ByteArray) {
        for (row in 0 until height) {
            var to = ((y + row) * size + x) * 4
            var from = row * width
            for (column in 0 until width) {
                pixels[to] = -1
                pixels[to + 1] = -1
                pixels[to + 2] = -1
                pixels[to + 3] = coverage[from++]
                to += 4
            }
        }
        changed(x, y, width, height)
    }

    /** Straight RGBA, four bytes a pixel, at [x], [y]. */
    internal fun writeRgba(x: Int, y: Int, width: Int, height: Int, rgba: ByteArray) {
        for (row in 0 until height) {
            rgba.copyInto(pixels, ((y + row) * size + x) * 4, row * width * 4, (row + 1) * width * 4)
        }
        changed(x, y, width, height)
    }

    private fun changed(x: Int, y: Int, width: Int, height: Int) = uploads.forEach { it.mark(x, y, width, height) }

    /**
     * This page's texture on [device], made the first time and brought up to date with whatever
     * was placed since. Only the rectangle that changed goes up.
     */
    fun texture(device: GpuDevice): DeviceTexture {
        var upload: Upload? = null
        for (candidate in uploads) if (candidate.device === device) upload = candidate
        if (upload == null) {
            upload = Upload(device, device.texture(size, size, smooth), size)
            uploads += upload
        }
        if (upload.right > upload.left && upload.bottom > upload.top) {
            device.write(upload.texture, upload.left, upload.top, upload.right - upload.left, upload.bottom - upload.top, pixels, size)
            upload.left = 0
            upload.right = 0
        }
        return upload.texture
    }

    internal fun forget(device: GpuDevice) {
        uploads.removeAll { it.device === device }
    }

    internal fun release(device: GpuDevice) {
        uploads.removeAll { upload ->
            (upload.device === device).also { mine -> if (mine) device.delete(upload.texture) }
        }
    }

    /** The pixel at [x], [y] as `0xRRGGBBAA`, straight alpha: what was placed there, for a test or a tool to look at. */
    fun rgbaAt(x: Int, y: Int): Int {
        require(x in 0 until size && y in 0 until size) { "($x, $y) is outside a ${size}x$size page" }
        val at = (y * size + x) * 4
        return ((pixels[at].toInt() and 0xFF) shl 24) or ((pixels[at + 1].toInt() and 0xFF) shl 16) or
            ((pixels[at + 2].toInt() and 0xFF) shl 8) or (pixels[at + 3].toInt() and 0xFF)
    }

    internal fun close() {
        uploads.forEach { it.device.delete(it.texture) }
        uploads.clear()
    }

    private companion object {
        fun blank(size: Int): ByteArray {
            val pixels = ByteArray(size * size * 4)
            var at = 0
            while (at < pixels.size) {
                pixels[at] = -1
                pixels[at + 1] = -1
                pixels[at + 2] = -1
                at += 4
            }
            return pixels
        }
    }
}
