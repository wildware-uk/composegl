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
        val u = floatArrayOf(0f, slice.left, texture.width - slice.right, texture.width.toFloat())
        val v = floatArrayOf(0f, slice.top, texture.height - slice.bottom, texture.height.toFloat())

        for (row in 0..2) {
            for (column in 0..2) {
                val part = Rect(x[column], y[row], x[column + 1], y[row + 1])
                val source = Rect(u[column], v[row], u[column + 1], v[row + 1])
                if (part.isEmpty || source.isEmpty) continue

                fill(canvas, source, part, tilesAcross(row, column), tilesDown(row, column), tint)
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

    /** One of the nine, laid down once if it stretches and repeatedly if it tiles. */
    private fun fill(
        canvas: UiCanvas,
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
