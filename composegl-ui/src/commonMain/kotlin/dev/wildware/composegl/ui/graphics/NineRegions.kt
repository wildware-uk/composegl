package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.layout.Padding

/**
 * Nine pieces of art that were cut separately, handed to a [NinePatch] in place of one picture.
 *
 * The reason this exists is a faint line across the middle of a widget on a mipmapped atlas. A
 * patch made of one texture takes its stretched middle band from a rectangle inside that texture,
 * and at small sizes a coarser mip level averages that rectangle together with whatever the packer
 * put next to it. A host that can cut nine regions dodges it by cutting the band down to a single
 * texel — there is then no neighbouring art inside the region to average in.
 *
 * To correct something this repository has said before: narrowing the band's source rectangle to
 * one texel *does* remove the artefact. The mip level is chosen from how fast the texture
 * coordinate changes across the drawn quad, not from how near the sample sits to the edge of a
 * region, and one texel stretched wide is magnification — so the level of detail lands at 0 and no
 * mip is fetched at all. That is exactly why cutting nine regions works too. The reasons not to do
 * the narrowing inside the library are the other two: it would throw away art the host may
 * actually want drawn, and it cannot be done in common code, which has no pixels to cut.
 *
 * Every piece is optional — all but the last one, since nine nothings are not a picture — and a
 * missing one means that row or column has no slice at all, the same thing a zero in a slice means
 * today. `NineRegions(left = cap, centre = track, right = cap)` is a scrollbar track or a progress
 * bar: three pieces, no top row, no bottom row. A missing middle piece is a frame with a hole in
 * it, which is also a real thing to want.
 *
 * Pieces down the left column must agree on their width, because that width *is* the patch's left
 * slice; the same goes for the right column and for the top and bottom rows. The middle column and
 * the middle row are deliberately free, since cutting the band to one texel is the whole point.
 *
 * @see NinePatch.of
 */
data class NineRegions(
    val topLeft: TextureHandle? = null,
    val top: TextureHandle? = null,
    val topRight: TextureHandle? = null,
    val left: TextureHandle? = null,
    val centre: TextureHandle? = null,
    val right: TextureHandle? = null,
    val bottomLeft: TextureHandle? = null,
    val bottom: TextureHandle? = null,
    val bottomRight: TextureHandle? = null,
) : TextureHandle {

    init {
        var pieces = 0
        for (index in Names.indices) {
            val piece = at(index / 3, index % 3) ?: continue
            pieces++
            require(piece.width > 0 && piece.height > 0) {
                "the ${Names[index]} piece of a nine-patch is ${piece.width}x${piece.height}, " +
                    "which draws nothing; leave it out rather than give it no size"
            }
        }
        require(pieces > 0) { "a nine-patch made of regions needs at least one of them" }

        agree("left side", "wide", 0, 3, 6) { it.width }
        agree("right side", "wide", 2, 5, 8) { it.width }
        agree("top", "tall", 0, 1, 2) { it.height }
        agree("bottom", "tall", 6, 7, 8) { it.height }
    }

    /**
     * How thick each border is, taken from the art rather than written down a second time.
     *
     * A side with no pieces at all is zero, which is what makes a three-piece bar work: its top and
     * bottom slices are nothing, so those rows have no height and nothing is drawn in them.
     */
    val slice: Padding = Padding(
        left = thickness(topLeft, left, bottomLeft) { it.width },
        top = thickness(topLeft, top, topRight) { it.height },
        right = thickness(topRight, right, bottomRight) { it.width },
        bottom = thickness(bottomLeft, bottom, bottomRight) { it.height },
    )

    /**
     * A bound, not a picture size.
     *
     * There is no one texture here to have a size, so this is the two side slices plus the widest
     * middle piece, and it exists so that [NinePatch] has something to check a slice against. It is
     * emphatically **not** the art's natural size: a patch whose centre is cut to a single texel to
     * dodge the mip artefact reports a width of one more than its two corners, which is no use at
     * all to anybody laying out at the size of the picture. Nothing but [NinePatch] should read it,
     * and the things that otherwise would — the `Image` widget, a skin's image background, and
     * every [UiCanvas] in this repository — refuse one of these outright rather than lay out
     * against a fiction.
     */
    override val width: Int = slice.left.toInt() + widest(top, centre, bottom) + slice.right.toInt()

    /**
     * The same bound downwards, and just as much a fiction: the two side slices plus the tallest
     * middle piece. Read [width] for why nothing but [NinePatch] should be asking.
     */
    override val height: Int = slice.top.toInt() + tallest(left, centre, right) + slice.bottom.toInt()

    /**
     * The piece in that cell of the three by three, or null when the art does not have one.
     *
     * Row 0 is the top and column 0 is the left. Indexed rather than named because slicing is a
     * loop over a grid, and a loop that has to name nine fields is nine chances to name the wrong
     * one. Internal because the loop that wants it is [NinePatch]'s: a host has the nine fields.
     */
    internal fun at(row: Int, column: Int): TextureHandle? = when (row) {
        0 -> when (column) {
            0 -> topLeft
            1 -> top
            else -> topRight
        }
        1 -> when (column) {
            0 -> left
            1 -> centre
            else -> right
        }
        else -> when (column) {
            0 -> bottomLeft
            1 -> bottom
            else -> bottomRight
        }
    }

    /**
     * Every piece there is one of, by the name a skin file calls it. What a writer walks.
     *
     * Internal because those names are the skin format's spelling, and a published graphics type
     * should not be the place a host reads the file format off. Turning it public later costs
     * nothing; turning it internal later would be a break.
     */
    internal fun named(): List<Pair<String, TextureHandle>> =
        Names.indices.mapNotNull { index ->
            at(index / 3, index % 3)?.let { Names[index] to it }
        }

    /**
     * Stops a side whose pieces do not line up, at the point somebody writes it down.
     *
     * The three [cells] are one column or one row; whichever of them exist must be the same size
     * across, because that size is the slice and there is only one of those.
     */
    private inline fun agree(side: String, dimension: String, vararg cells: Int, of: (TextureHandle) -> Int) {
        var foundName = ""
        var found = -1
        for (cell in cells) {
            val size = at(cell / 3, cell % 3)?.let(of) ?: continue
            if (found < 0) {
                foundName = Names[cell]
                found = size
                continue
            }
            require(size == found) {
                "the $side of a nine-patch is as $dimension as its pieces, so they have to agree: " +
                    "$foundName is $found and ${Names[cell]} is $size"
            }
        }
    }

    companion object {

        /**
         * What the whole nine-region idea has to say to anything that draws one picture.
         *
         * One sentence in one place, because it is said by the `Image` widget and by every backend,
         * and a reader who meets it should not have to meet three different versions of it.
         */
        const val NotOnePicture: String =
            "this is nine separately-cut nine-patch pieces rather than one picture, so it cannot " +
                "be drawn as an image. Only NinePatch knows how to draw one: put it behind " +
                "Modifier.ninePatch, or name it as a skin background."

        /** In the order [at] walks them, so an index is a row and a column. */
        private val Names = listOf(
            "topLeft", "top", "topRight",
            "left", "centre", "right",
            "bottomLeft", "bottom", "bottomRight",
        )

        private fun widest(vararg pieces: TextureHandle?): Int = pieces.maxOf { it?.width ?: 0 }

        private fun tallest(vararg pieces: TextureHandle?): Int = pieces.maxOf { it?.height ?: 0 }

        /** A side's thickness: whichever of its three pieces is there, or nothing when none is. */
        private inline fun thickness(
            first: TextureHandle?,
            second: TextureHandle?,
            third: TextureHandle?,
            of: (TextureHandle) -> Int,
        ): Float = (first ?: second ?: third)?.let { of(it).toFloat() } ?: 0f
    }
}

/**
 * Stops nine separately-cut pieces reaching something that draws one picture.
 *
 * They are a [TextureHandle] so that they can sit in a [NinePatch]'s texture slot without changing
 * a published signature, and the price of that trick is that they now fit anywhere a picture fits.
 * Caught at the point of use, where somebody can act on it — a backend would only be able to say
 * that it did not make this texture, which is not the problem and sends the reader off hunting for
 * a mismatch that is not there.
 */
internal fun refuseNineRegions(texture: TextureHandle) {
    require(texture !is NineRegions) { NineRegions.NotOnePicture }
}
