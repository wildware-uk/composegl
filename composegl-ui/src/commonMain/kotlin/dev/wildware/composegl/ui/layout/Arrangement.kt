package dev.wildware.composegl.ui.layout

/**
 * How a row or a column shares out the space along its own axis.
 *
 * Given the room available and how big each child turned out, it says where each child starts.
 * Everything a game normally wants — pack to one end, centre the lot, spread them out, put a fixed
 * gap between them — is one of these, and a game can write its own in four lines.
 *
 * It works on arrays rather than lists because a row is arranged every frame, for every row on the
 * screen, and a list of numbers in Kotlin is a list of *objects*: a boxed float per child per frame
 * on the way in, and another on the way out. The arrays are lent by the layout and are good until
 * [arrange] returns.
 */
fun interface Arrangement {

    /**
     * Room reserved *between* children before anything is measured.
     *
     * Only a fixed gap has any: the spreading arrangements work out their gaps from the space that
     * is left, so there is nothing to reserve.
     */
    val spacing: Float get() = 0f

    /**
     * Writes where each child starts, in order, along an axis [totalSize] long.
     *
     * @param sizes how big each child turned out, in order. Only the first [count] mean anything.
     * @param count how many children there are. Both arrays may be longer.
     * @param into where the answer goes: [count] positions, from index zero.
     */
    fun arrange(totalSize: Float, sizes: FloatArray, count: Int, into: FloatArray)

    companion object {

        /** Packed against the beginning. The left of a row, the top of a column. */
        val Start: Arrangement = Arrangement { _, sizes, count, into ->
            runningFrom(0f, 0f, sizes, count, into)
        }

        /** Packed against the end. */
        val End: Arrangement = Arrangement { total, sizes, count, into ->
            runningFrom(total - sum(sizes, count), 0f, sizes, count, into)
        }

        /** Packed together in the middle. */
        val Centre: Arrangement = Arrangement { total, sizes, count, into ->
            runningFrom((total - sum(sizes, count)) / 2f, 0f, sizes, count, into)
        }

        /** First and last against the ends, the rest spread evenly between them. */
        val SpaceBetween: Arrangement = Arrangement { total, sizes, count, into ->
            val gaps = (count - 1).coerceAtLeast(1)
            runningFrom(0f, free(total, sizes, count) / gaps, sizes, count, into)
        }

        /** Equal space around each child, so the ends get half as much as the middles. */
        val SpaceAround: Arrangement = Arrangement { total, sizes, count, into ->
            if (count > 0) {
                val gap = free(total, sizes, count) / count
                runningFrom(gap / 2f, gap, sizes, count, into)
            }
        }

        /** Equal space everywhere, ends included. */
        val SpaceEvenly: Arrangement = Arrangement { total, sizes, count, into ->
            if (count > 0) {
                val gap = free(total, sizes, count) / (count + 1)
                runningFrom(gap, gap, sizes, count, into)
            }
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

        private fun sum(sizes: FloatArray, count: Int): Float {
            var total = 0f
            for (index in 0 until count) total += sizes[index]
            return total
        }

        private fun free(total: Float, sizes: FloatArray, count: Int) =
            (total - sum(sizes, count)).coerceAtLeast(0f)

        private fun runningFrom(start: Float, gap: Float, sizes: FloatArray, count: Int, into: FloatArray) {
            var position = start
            for (index in 0 until count) {
                into[index] = position
                position += sizes[index] + gap
            }
        }

        private data class FixedGap(val gap: Float, val align: Arrangement) : Arrangement {

            // Where the whole run starts, worked out by asking [align] to place one child the size
            // of the run. Two arrays of one, kept, because this is asked every frame; they are safe
            // to keep because an arrangement never nests inside itself.
            private val run = FloatArray(1)
            private val start = FloatArray(1)

            override val spacing get() = gap

            override fun arrange(totalSize: Float, sizes: FloatArray, count: Int, into: FloatArray) {
                if (count == 0) return
                run[0] = sum(sizes, count) + gap * (count - 1)
                align.arrange(totalSize, run, 1, start)
                runningFrom(start[0], gap, sizes, count, into)
            }
        }
    }
}
