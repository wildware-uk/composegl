package dev.wildware.composegl.debug

import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers behind a plot, with no screen in the way: the ring the samples go into, the range the
 * graph is drawn against, and which sample a point is over.
 *
 * These are the parts a picture cannot be asserted on. What is actually drawn, and what a pointer
 * and a pad do to it, are in `PlotTest`.
 */
class PlotBufferTest {

    @Test
    fun `a buffer holds what it is given until it is full`() {
        val buffer = PlotBuffer(capacity = 4)
        assertEquals(0, buffer.size)

        buffer.add(1f)
        buffer.add(2f)

        assertEquals(2, buffer.size)
        assertEquals(1f, buffer[0])
        assertEquals(2f, buffer[1])
        assertEquals(2f, buffer.latest)
    }

    @Test
    fun `a full buffer drops the oldest sample and keeps the order`() {
        val buffer = PlotBuffer(capacity = 3)
        repeat(6) { buffer.add(it.toFloat()) }

        assertEquals(3, buffer.size)
        assertEquals(listOf(3f, 4f, 5f), (0 until buffer.size).map { buffer[it] })
        assertEquals(5f, buffer.latest)
    }

    @Test
    fun `the readout is the smallest and the largest and the mean of what is held`() {
        val buffer = PlotBuffer(capacity = 4)
        listOf(10f, 2f, 6f, 4f).forEach { buffer.add(it) }

        assertEquals(2f, buffer.min)
        assertEquals(10f, buffer.max)
        assertEquals(5.5f, buffer.average)

        // The sample that was the largest has gone, so the summary has to be worked out again
        // rather than kept from last time.
        buffer.add(8f)
        assertEquals(8f, buffer.max)
        assertEquals(2f, buffer.min)
        assertEquals(5f, buffer.average)
    }

    @Test
    fun `the readout leaves out a sample that is not a number`() {
        // The range already skips those and the trace already leaves a gap. The readout used to
        // add them in anyway, so one NaN made min mean and max all read as a dash.
        val buffer = PlotBuffer(capacity = 4)
        listOf(4f, Float.NaN, 8f, 6f).forEach { buffer.add(it) }

        assertEquals(4f, buffer.min)
        assertEquals(8f, buffer.max)
        assertEquals(6f, buffer.average, "the mean of the three there are numbers for")
    }

    @Test
    fun `a buffer of nothing but non-numbers has no readout to give`() {
        val buffer = PlotBuffer(capacity = 4)
        listOf(Float.NaN, Float.POSITIVE_INFINITY).forEach { buffer.add(it) }

        assertTrue(buffer.min.isNaN(), "there is nothing to be the smallest of")
        assertTrue(buffer.max.isNaN(), "nor the largest")
        assertTrue(buffer.average.isNaN(), "nor a mean of")
        assertEquals("-", plotNumber(buffer.min), "which is the dash the readout writes")
    }

    @Test
    fun `an empty buffer reads as zero rather than failing`() {
        val buffer = PlotBuffer(capacity = 4)

        assertEquals(0f, buffer.min)
        assertEquals(0f, buffer.max)
        assertEquals(0f, buffer.average)
        assertEquals(0f, buffer.latest)
    }

    @Test
    fun `clearing forgets every sample and says so`() {
        val buffer = PlotBuffer(capacity = 4)
        repeat(4) { buffer.add(it.toFloat()) }
        val before = buffer.revision

        buffer.clear()

        assertEquals(0, buffer.size)
        assertTrue(buffer.revision > before, "a plot has to hear that the samples have gone")
    }

    @Test
    fun `every sample moves the revision a plot is redrawn on`() {
        val buffer = PlotBuffer(capacity = 2)
        val before = buffer.revision

        buffer.add(1f)
        buffer.add(2f)

        assertEquals(before + 2, buffer.revision)
    }

    @Test
    fun `replacing takes the last values that fit`() {
        val buffer = PlotBuffer(capacity = 3)
        buffer.replaceWith(floatArrayOf(1f, 2f, 3f, 4f, 5f))

        assertEquals(3, buffer.size)
        assertEquals(listOf(3f, 4f, 5f), (0 until buffer.size).map { buffer[it] })

        // And a shorter run than the ring leaves the ring short, rather than padded with what was
        // there before.
        buffer.replaceWith(floatArrayOf(7f, 8f, 9f), count = 2)
        assertEquals(listOf(7f, 8f), (0 until buffer.size).map { buffer[it] })
    }

    @Test
    fun `a series over an array reads the values as they stand`() {
        val values = floatArrayOf(1f, 2f, 3f, 4f)
        val series = PlotSeries.of(values, count = 3)

        assertEquals(3, series.size)
        assertEquals(3, series.capacity)

        values[1] = 9f
        assertEquals(9f, series[1], "the array is read, not copied")
    }

    // --- the range ------------------------------------------------------------------------------

    @Test
    fun `an automatic range covers the samples`() {
        val buffer = PlotBuffer(capacity = 4)
        listOf(3f, 11f, 7f).forEach { buffer.add(it) }

        assertEquals(3f, plotLow(buffer, null, emptyList()))
        assertEquals(11f, plotHigh(buffer, null, emptyList()))
    }

    @Test
    fun `an automatic range covers the guides too`() {
        val buffer = PlotBuffer(capacity = 4)
        listOf(3f, 7f).forEach { buffer.add(it) }

        // A guide nobody can see is not a guide, and "sixteen milliseconds is the budget" is exactly
        // the case where every sample is under it.
        assertEquals(16.6f, plotHigh(buffer, null, listOf(16.6f)))
        assertEquals(0f, plotLow(buffer, null, listOf(0f)))
    }

    @Test
    fun `a fixed range is left alone`() {
        val buffer = PlotBuffer(capacity = 4)
        listOf(3f, 99f).forEach { buffer.add(it) }

        assertEquals(0f, plotLow(buffer, 0f..33f, listOf(16.6f)))
        assertEquals(33f, plotHigh(buffer, 0f..33f, listOf(16.6f)))
    }

    @Test
    fun `a series that never changes is given room either side of itself`() {
        val buffer = PlotBuffer(capacity = 4)
        repeat(3) { buffer.add(60f) }

        val low = plotLow(buffer, null, emptyList())
        val high = plotHigh(buffer, null, emptyList())
        assertTrue(high > low, "a flat line would otherwise divide by nothing: $low..$high")

        // Either side, not just above: room only over it puts the trace on the bottom edge, where a
        // steady sixty reads as nothing at all.
        val where = (60f - low) / (high - low)
        assertTrue(where > 0.4f && where < 0.6f, "a steady sixty belongs across the middle: $low..$high")
    }

    @Test
    fun `a flat series of a large number is still given room either side of it`() {
        // A float holds about seven digits, so 4096f plus a ten-thousandth is still 4096f. A fixed
        // amount of room left the top and the bottom the same number, and every coordinate worked
        // out from them was a division by nothing. Bytes of memory is a number exactly this big.
        val buffer = PlotBuffer(capacity = 5)
        repeat(5) { buffer.add(4096f) }

        val low = plotLow(buffer, null, emptyList())
        val high = plotHigh(buffer, null, emptyList())
        assertTrue(high > low, "room a float can actually hold: $low..$high")

        val where = (4096f - low) / (high - low)
        assertTrue(where > 0.4f && where < 0.6f, "and still across the middle: $low..$high")
    }

    @Test
    fun `a range with nothing in it is given room rather than failing`() {
        // An overlay works its range out from what it is measuring and can land on nought to
        // nought. Refusing that throws while the screen is composing which is no help to anyone.
        val buffer = PlotBuffer(capacity = 4)
        repeat(3) { buffer.add(0f) }

        assertEquals(0f, plotLow(buffer, 0f..0f, emptyList()))
        assertTrue(plotHigh(buffer, 0f..0f, emptyList()) > 0f, "a top the bottom is not")
    }

    @Test
    fun `a histogram of nothing keeps its floor`() {
        // Bars are measured from zero, so the flat case leaves the bottom where it is rather than
        // dropping it below the floor the bars stand on.
        val buffer = PlotBuffer(capacity = 4)
        repeat(3) { buffer.add(0f) }

        assertEquals(0f, plotLow(buffer, null, emptyList(), fromZero = true))
        assertTrue(plotHigh(buffer, null, emptyList(), fromZero = true) > 0f, "and still has room in it")
    }

    @Test
    fun `a sample that is not a number is left out of the range`() {
        // A game measures things that are sometimes not measurable: a ping before the first reply,
        // a ratio over a zero denominator. One of those must not take the graph with it.
        val buffer = PlotBuffer(capacity = 4)
        listOf(3f, Float.NaN, 11f, Float.POSITIVE_INFINITY).forEach { buffer.add(it) }

        assertEquals(3f, plotLow(buffer, null, emptyList()))
        assertEquals(11f, plotHigh(buffer, null, emptyList()))
    }

    @Test
    fun `a series of nothing but NaN still has a range`() {
        val buffer = PlotBuffer(capacity = 4)
        repeat(3) { buffer.add(Float.NaN) }

        val low = plotLow(buffer, null, listOf(Float.NaN))
        val high = plotHigh(buffer, null, listOf(Float.NaN))
        assertEquals(0f, low)
        assertEquals(1f, high)
    }

    @Test
    fun `an empty series still has a range`() {
        val empty = PlotBuffer(capacity = 4)

        assertEquals(0f, plotLow(empty, null, emptyList()))
        assertEquals(1f, plotHigh(empty, null, emptyList()))
    }

    // --- which sample a point is over ------------------------------------------------------------

    private val bounds = Rect(0f, 0f, 100f, 50f)

    @Test
    fun `a point picks the sample whose column it is in`() {
        // Ten columns of ten across a hundred.
        assertEquals(0, plotIndexAt(bounds, capacity = 10, count = 10, x = 4f, rtl = false))
        assertEquals(3, plotIndexAt(bounds, capacity = 10, count = 10, x = 35f, rtl = false))
        assertEquals(9, plotIndexAt(bounds, capacity = 10, count = 10, x = 99f, rtl = false))
    }

    @Test
    fun `a point past the samples there are reads the newest one`() {
        // A half-full buffer: the right-hand half of the graph is empty, and pointing at it should
        // read the last sample rather than nothing.
        assertEquals(4, plotIndexAt(bounds, capacity = 10, count = 5, x = 90f, rtl = false))
    }

    @Test
    fun `a right to left graph is read from the other end`() {
        assertEquals(0, plotIndexAt(bounds, capacity = 10, count = 10, x = 96f, rtl = true))
        assertEquals(9, plotIndexAt(bounds, capacity = 10, count = 10, x = 4f, rtl = true))
    }

    @Test
    fun `a graph with no samples and no width is over nothing`() {
        assertEquals(-1, plotIndexAt(bounds, capacity = 10, count = 0, x = 50f, rtl = false))
        assertEquals(-1, plotIndexAt(Rect(0f, 0f, 0f, 0f), capacity = 10, count = 4, x = 0f, rtl = false))
    }

    // --- how a number is written out --------------------------------------------------------------

    @Test
    fun `a value is written with two decimal places`() {
        assertEquals("16.60", plotNumber(16.6f))
        assertEquals("0.00", plotNumber(0f))
        assertEquals("-2.50", plotNumber(-2.5f))
        assertEquals("1.00", plotNumber(0.999f))
    }

    @Test
    fun `an enormous value is written whole`() {
        assertEquals("120000", plotNumber(120_000f))
        assertEquals("-", plotNumber(Float.NaN))
    }
}
