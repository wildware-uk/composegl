package composegl.ui.layout

/**
 * How a row or a column shares out the space along its own axis.
 *
 * Given the room available and how big each child turned out, it says where each child starts.
 * Everything a game normally wants — pack to one end, centre the lot, spread them out, put a fixed
 * gap between them — is one of these, and a game can write its own in four lines.
 */
fun interface Arrangement {

    /**
     * Room reserved *between* children before anything is measured.
     *
     * Only a fixed gap has any: the spreading arrangements work out their gaps from the space that
     * is left, so there is nothing to reserve.
     */
    val spacing: Float get() = 0f

    /** Where each child starts, in order, along an axis [totalSize] long. */
    fun arrange(totalSize: Float, sizes: List<Float>): List<Float>

    companion object {

        /** Packed against the beginning. The left of a row, the top of a column. */
        val Start: Arrangement = Arrangement { _, sizes -> runningFrom(0f, 0f, sizes) }

        /** Packed against the end. */
        val End: Arrangement = Arrangement { total, sizes -> runningFrom(total - sizes.sum(), 0f, sizes) }

        /** Packed together in the middle. */
        val Centre: Arrangement = Arrangement { total, sizes ->
            runningFrom((total - sizes.sum()) / 2f, 0f, sizes)
        }

        /** First and last against the ends, the rest spread evenly between them. */
        val SpaceBetween: Arrangement = Arrangement { total, sizes ->
            val gaps = (sizes.size - 1).coerceAtLeast(1)
            runningFrom(0f, free(total, sizes) / gaps, sizes)
        }

        /** Equal space around each child, so the ends get half as much as the middles. */
        val SpaceAround: Arrangement = Arrangement { total, sizes ->
            if (sizes.isEmpty()) return@Arrangement emptyList()
            val gap = free(total, sizes) / sizes.size
            runningFrom(gap / 2f, gap, sizes)
        }

        /** Equal space everywhere, ends included. */
        val SpaceEvenly: Arrangement = Arrangement { total, sizes ->
            if (sizes.isEmpty()) return@Arrangement emptyList()
            val gap = free(total, sizes) / (sizes.size + 1)
            runningFrom(gap, gap, sizes)
        }

        /** The top of a column, which is the same thing as the start of a row. */
        val Top: Arrangement get() = Start

        /** The bottom of a column. */
        val Bottom: Arrangement get() = End

        /**
         * A fixed gap between children, with the whole run positioned by [align].
         *
         * This is the one arrangement that reserves room in advance, because the gap is known
         * before anything is measured and the children have to be offered less because of it.
         */
        fun spacedBy(gap: Float, align: Arrangement = Start): Arrangement = FixedGap(gap, align)

        private fun free(total: Float, sizes: List<Float>) = (total - sizes.sum()).coerceAtLeast(0f)

        private fun runningFrom(start: Float, gap: Float, sizes: List<Float>): List<Float> {
            var position = start
            return sizes.map {
                val here = position
                position += it + gap
                here
            }
        }

        private data class FixedGap(val gap: Float, val align: Arrangement) : Arrangement {
            override val spacing get() = gap

            override fun arrange(totalSize: Float, sizes: List<Float>): List<Float> {
                if (sizes.isEmpty()) return emptyList()
                val run = sizes.sum() + gap * (sizes.size - 1)
                val start = align.arrange(totalSize, listOf(run)).first()
                return runningFrom(start, gap, sizes)
            }
        }
    }
}
