package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Padding
import kotlin.math.ceil

/** What a stretchable part of a nine-patch does when it is bigger than the art. */
enum class EdgeMode {

    /** The pixels are pulled to fit. Right for gradients and flat colour. */
    Stretch,

    /** The art is repeated. Right for a pattern — rivets, chain, woven cloth — that stretching would smear. */
    Tile,
}

/**
 * A picture cut into nine, so it can be any size without its corners going soft.
 *
 * The mechanism behind every panel, button and frame a game actually ships. The four corners are
 * drawn at their own size and never touched; the four edges stretch or tile along one axis; the
 * middle does both. A 48-pixel bevelled box becomes a dialog the width of the screen and still has
 * a 16-pixel bevel.
 *
 * The [padding] is the other half of the idea, and the half people forget. Art with a 6-pixel
 * bevel and a 4-pixel inner glow needs its contents kept 10 pixels from the edge, and that number
 * belongs to the *art*, not to the code that uses it — change the skin and the number changes with
 * it. `Modifier.ninePatch` applies it for you, so a game never writes `padding(10f)` next to a
 * background and then forgets to update it.
 *
 * Each edge gets its own mode, because each edge stretches along exactly one axis. The middle
 * gets two, because it stretches along both, and a header band whose pattern should repeat across
 * but not down is an ordinary thing to want.
 *
 * [texture] is usually one picture with the nine pieces laid out inside it, and then the [slice]
 * says where the cuts fall. It can instead be a [NineRegions] — nine pieces the host cut for
 * itself — and then the cuts are already made and [of] is the way to build one. See [NineRegions]
 * for the mipmapped-atlas artefact that is the whole reason for the second form.
 *
 * @param slice how many texture pixels each border is. Corners are [slice]'s two ends multiplied.
 * @param padding how far the contents are kept from the edge. Defaults to [slice], which is right
 *   whenever the art's border *is* its frame.
 */
data class NinePatch(
    val texture: TextureHandle,
    val slice: Padding,
    val padding: Padding = slice,
    val leftEdge: EdgeMode = EdgeMode.Stretch,
    val topEdge: EdgeMode = EdgeMode.Stretch,
    val rightEdge: EdgeMode = EdgeMode.Stretch,
    val bottomEdge: EdgeMode = EdgeMode.Stretch,
    val centreAcross: EdgeMode = EdgeMode.Stretch,
    val centreDown: EdgeMode = EdgeMode.Stretch,
) {

    init {
        require(slice.left >= 0f && slice.top >= 0f && slice.right >= 0f && slice.bottom >= 0f) {
            "a nine-patch slice cannot be negative, was $slice"
        }
        require(slice.horizontal <= texture.width) {
            "the left and right slices add up to ${slice.horizontal}, " +
                "wider than the ${texture.width}-pixel texture"
        }
        require(slice.vertical <= texture.height) {
            "the top and bottom slices add up to ${slice.vertical}, " +
                "taller than the ${texture.height}-pixel texture"
        }
        if (texture is NineRegions) {
            require(slice == texture.slice) {
                "a patch cut into nine regions already knows its slice — its pieces make " +
                    "${texture.slice} — so it cannot also be given $slice. Build it with " +
                    "NinePatch.of(regions, …), which takes the slice from the art."
            }
        }

        // The pitch check lives here rather than on the art, because the edge modes live here: two
        // pieces of different sizes are perfectly fine until somebody tiles both of them.
        requireOnePitch()
    }

    /** The smallest this draws at without its corners having to give way to each other. */
    val minimumSize: Size get() = Size(slice.horizontal, slice.vertical)

    /**
     * Draws the patch across [destination].
     *
     * Deliberately on the patch rather than on [UiCanvas]: slicing is arithmetic, the same
     * arithmetic on every backend, and a backend that had to implement it is a backend that can
     * get it wrong. All any of them has to do is draw part of a texture.
     */
    fun drawInto(canvas: UiCanvas, destination: Rect, tint: Colour = Colour.White) {
        if (destination.isEmpty) return

        // Corners keep their real size until there is no room left for them, and then both ends
        // give way together. A panel squeezed below its minimum looks small rather than broken.
        val horizontal = squeeze(slice.horizontal, destination.width)
        val vertical = squeeze(slice.vertical, destination.height)

        val x = floatArrayOf(
            destination.left,
            destination.left + slice.left * horizontal,
            destination.right - slice.right * horizontal,
            destination.right,
        )
        val y = floatArrayOf(
            destination.top,
            destination.top + slice.top * vertical,
            destination.bottom - slice.bottom * vertical,
            destination.bottom,
        )

        val regions = texture as? NineRegions
        // Only the one-texture form has cuts to work out; nine regions arrive already cut. Null
        // rather than an empty array, so that reading a cut on the nine-region path is a compiler
        // error rather than a read past the end of one.
        val cuts = if (regions != null) null else Cuts(
            u = floatArrayOf(0f, slice.left, texture.width - slice.right, texture.width.toFloat()),
            v = floatArrayOf(0f, slice.top, texture.height - slice.bottom, texture.height.toFloat()),
        )

        for (row in 0..2) {
            for (column in 0..2) {
                val part = Rect(x[column], y[row], x[column + 1], y[row + 1])
                if (part.isEmpty) continue

                val piece: TextureHandle
                val source: Rect
                if (cuts != null) {
                    piece = texture
                    source = Rect(cuts.u[column], cuts.v[row], cuts.u[column + 1], cuts.v[row + 1])
                } else {
                    // No cuts means nine regions, which is what made them null. A piece the art
                    // does not have is a cell with nothing in it rather than a hole in the
                    // arithmetic: the row or column either has no slice or is simply blank.
                    piece = regions?.at(row, column) ?: continue
                    source = Rect.of(0f, 0f, piece.width.toFloat(), piece.height.toFloat())
                }
                if (source.isEmpty) continue

                fill(canvas, piece, source, part, tilesAcross(row, column), tilesDown(row, column), tint)
            }
        }
    }

    /**
     * Whether this piece repeats sideways.
     *
     * Only the middle column ever can: a corner is a corner whatever the edges either side of it
     * are doing, and the left and right edges are only ever as wide as the art.
     */
    private fun tilesAcross(row: Int, column: Int): Boolean = column == 1 && when (row) {
        0 -> topEdge
        1 -> centreAcross
        else -> bottomEdge
    } == EdgeMode.Tile

    /** And the mirror of it: only the middle row repeats downwards. */
    private fun tilesDown(row: Int, column: Int): Boolean = row == 1 && when (column) {
        0 -> leftEdge
        1 -> centreDown
        else -> rightEdge
    } == EdgeMode.Tile

    /** How wide the piece in that cell is, or null when the art has nothing there. */
    private fun pieceWidth(row: Int, column: Int): Float? = when (texture) {
        is NineRegions -> texture.at(row, column)?.width?.toFloat()
        else -> when (column) {
            0 -> slice.left
            1 -> texture.width - slice.horizontal
            else -> slice.right
        }
    }

    /** And how tall. */
    private fun pieceHeight(row: Int, column: Int): Float? = when (texture) {
        is NineRegions -> texture.at(row, column)?.height?.toFloat()
        else -> when (row) {
            0 -> slice.top
            1 -> texture.height - slice.vertical
            else -> slice.bottom
        }
    }

    /**
     * Stops a patch whose tiled pieces would repeat at two different pitches.
     *
     * [fill] steps by the piece's own size, so a left edge 8 pixels tall and a right edge 12 pixels
     * tall tile down the two sides at 8 and at 12, and the rivets stop lining up a third of the way
     * down. One texture makes that impossible — its two sides are cut from the same band and so are
     * the same size by construction — but nine separately cut regions do not.
     *
     * Only where the edge tiles. Stretching keeps its freedom, and it has to: cutting the middle
     * band down to a single texel to dodge the mip artefact depends on the middle being allowed to
     * be a different size from the ends.
     */
    private fun requireOnePitch() {
        // Tiling downwards is the middle row: three pieces whose heights are the step.
        onePitch(
            "downwards", "tall",
            listOf(
                Tiling("left", leftEdge, pieceHeight(1, 0)),
                Tiling("centre", centreDown, pieceHeight(1, 1)),
                Tiling("right", rightEdge, pieceHeight(1, 2)),
            ),
        )
        // And tiling across is the middle column, stepping by their widths.
        onePitch(
            "across", "wide",
            listOf(
                Tiling("top", topEdge, pieceWidth(0, 1)),
                Tiling("centre", centreAcross, pieceWidth(1, 1)),
                Tiling("bottom", bottomEdge, pieceWidth(2, 1)),
            ),
        )
    }

    /** Where a one-texture patch's cuts fall, across and down. The nine-region form has none. */
    private class Cuts(val u: FloatArray, val v: FloatArray)

    /** One of the three pieces that can repeat along an axis, with the mode that decides it. */
    private class Tiling(val name: String, val edge: EdgeMode, val size: Float?)

    private fun onePitch(direction: String, dimension: String, pieces: List<Tiling>) {
        val tiling = pieces.filter { it.edge == EdgeMode.Tile }
            .mapNotNull { piece -> piece.size?.let { piece.name to it } }
        val (firstName, firstSize) = tiling.firstOrNull() ?: return
        tiling.forEach { (name, size) ->
            require(size == firstSize) {
                "the $firstName piece is ${plain(firstSize)} pixels $dimension and the $name " +
                    "piece is ${plain(size)}, and both edges are EdgeMode.Tile, so they repeat " +
                    "$direction at two different pitches and their patterns drift apart. Make " +
                    "them the same $dimension, or set one edge to EdgeMode.Stretch."
            }
        }
    }

    /** One of the nine, laid down once if it stretches and repeatedly if it tiles. */
    private fun fill(
        canvas: UiCanvas,
        texture: TextureHandle,
        source: Rect,
        part: Rect,
        across: Boolean,
        down: Boolean,
        tint: Colour,
    ) {
        if (!across && !down) {
            canvas.image(texture, part, tint, source)
            return
        }

        val stepX = if (across) source.width else part.width
        val stepY = if (down) source.height else part.height
        if (stepX <= 0f || stepY <= 0f) return

        // A one-pixel tile across a full-width panel is a thousand quads a frame. Past a sane
        // count, stretching costs one and nobody can tell the difference at that density.
        val tiles = ceil(part.width / stepX) * ceil(part.height / stepY)
        if (tiles > MaxTiles) {
            canvas.image(texture, part, tint, source)
            return
        }

        var top = part.top
        while (top < part.bottom - Epsilon) {
            val height = minOf(stepY, part.bottom - top)
            var left = part.left
            while (left < part.right - Epsilon) {
                val width = minOf(stepX, part.right - left)
                // The last tile in a row or a column is usually a fraction of one, so it takes a
                // matching fraction of the source. Tiling that ends in a squashed tile is the
                // classic nine-patch bug.
                canvas.image(
                    texture,
                    Rect.of(left, top, width, height),
                    tint,
                    Rect.of(
                        source.left,
                        source.top,
                        source.width * (width / stepX),
                        source.height * (height / stepY),
                    ),
                )
                left += stepX
            }
            top += stepY
        }
    }

    companion object {

        /** Past this many tiles in one part, tiling gives way to stretching. */
        const val MaxTiles = 1024

        private const val Epsilon = 0.01f

        /** How much the corners have to give way to fit [available]. One when they all fit. */
        private fun squeeze(needed: Float, available: Float): Float =
            if (needed <= available || needed <= 0f) 1f else available / needed

        /** A pixel count without a pointless `.0` on the end of it. */
        private fun plain(value: Float): String =
            if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

        /**
         * A patch built from nine pieces the host cut for itself.
         *
         * The slice is the art's own, because with nine regions it already is: the left column's
         * width *is* the left slice. See [NineRegions] for what this buys, which is a middle band
         * that can be cut down to a single texel and so cannot fetch a neighbour out of a mip.
         */
        fun of(
            regions: NineRegions,
            padding: Padding = regions.slice,
            leftEdge: EdgeMode = EdgeMode.Stretch,
            topEdge: EdgeMode = EdgeMode.Stretch,
            rightEdge: EdgeMode = EdgeMode.Stretch,
            bottomEdge: EdgeMode = EdgeMode.Stretch,
            centreAcross: EdgeMode = EdgeMode.Stretch,
            centreDown: EdgeMode = EdgeMode.Stretch,
        ) = NinePatch(
            regions, regions.slice, padding,
            leftEdge, topEdge, rightEdge, bottomEdge, centreAcross, centreDown,
        )

        /** Every edge and the middle repeat the art instead of pulling it. */
        fun tiled(texture: TextureHandle, slice: Padding, padding: Padding = slice) = NinePatch(
            texture, slice, padding,
            EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile,
        )

        /** Border art that repeats, over a middle that stretches. The common mixed case. */
        fun tiledEdges(texture: TextureHandle, slice: Padding, padding: Padding = slice) = NinePatch(
            texture, slice, padding,
            EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile, EdgeMode.Tile,
            EdgeMode.Stretch, EdgeMode.Stretch,
        )
    }
}
