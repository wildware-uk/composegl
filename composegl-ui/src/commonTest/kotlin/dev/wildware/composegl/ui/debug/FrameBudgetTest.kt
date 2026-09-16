package dev.wildware.composegl.ui.debug

import dev.wildware.composegl.ui.node.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The budget's arithmetic, on every target.
 *
 * Every budget here is handed a clock the test moves by hand, so a frame costs exactly the
 * milliseconds the test said and nothing depends on how fast the machine running it is.
 *
 * Measuring real work instead failed a build now and again, from both ends at once: two identical
 * pieces of work do not take the same time on a loaded machine, so comparing one measured duration
 * with another is a coin toss, and a browser with its timers deliberately blunted reports short
 * work as having taken no time at all, so a test that wanted a number above zero got a zero.
 */
class FrameBudgetTest {

    /** Nanoseconds that only move when a test says so. */
    private class FakeClock {
        private var nanos = 0L

        /** Handed to a budget as its [FrameBudget.nanoTime]. */
        val reading: () -> Long = { nanos }

        fun advance(millis: Long) {
            nanos += millis * 1_000_000
        }
    }

    private val clock = FakeClock()

    /** Publishes on every frame, so a test does not have to wait a quarter of a second. */
    private fun budget(window: Int = 4, publishEveryMillis: Long = 0L) =
        FrameBudget(window = window, publishEveryMillis = publishEveryMillis, nanoTime = clock.reading)

    /** Work that takes exactly this many milliseconds. */
    private fun costing(millis: Long): Unit = clock.advance(millis)

    @Test
    fun `it reports nothing before the first frame`() {
        assertSame(FrameReading.Nothing, budget().reading)
    }

    @Test
    fun `the wrappers hand back what the block returned`() {
        val budget = budget()
        assertEquals(7, budget.recompose { 7 })
        assertEquals("laid out", budget.layout { "laid out" })
        assertEquals(true, budget.draw { true })
    }

    @Test
    fun `switched off it measures nothing and still runs the work`() {
        val budget = budget()
        budget.isOn = false

        var ran = 0
        repeat(10) {
            budget.recompose { ran++ }
            budget.layout { ran++ }
            budget.draw { ran++ }
            budget.endFrame(drawCalls = 3)
        }

        assertEquals(30, ran, "the work still has to happen")
        assertSame(FrameReading.Nothing, budget.reading, "nothing measured means nothing to report")
    }

    @Test
    fun `switching it off clears what it was showing`() {
        val budget = budget()
        budget.draw { costing(1) }
        budget.endFrame()
        assertNotEquals(FrameReading.Nothing, budget.reading)

        budget.isOn = false
        assertSame(FrameReading.Nothing, budget.reading)
    }

    @Test
    fun `it counts frames and the ones that redrew`() {
        val budget = budget()
        repeat(5) { index -> budget.endFrame(redrew = index % 2 == 0) }

        assertEquals(5L, budget.reading.frames)
        assertEquals(3L, budget.reading.redraws)
    }

    @Test
    fun `draw calls are whatever the canvas said`() {
        val budget = budget()
        budget.endFrame(drawCalls = 12)
        assertEquals(12, budget.reading.drawCalls)

        budget.endFrame(drawCalls = -1)
        assertEquals(-1, budget.reading.drawCalls, "a backend that does not count says so")
    }

    @Test
    fun `the three phases are measured apart`() {
        val budget = budget()
        budget.recompose { costing(2) }
        budget.layout { costing(3) }
        budget.draw { costing(5) }
        budget.endFrame()

        val reading = budget.reading
        assertEquals(2f, reading.recomposeMillis, Tolerance)
        assertEquals(3f, reading.layoutMillis, Tolerance)
        assertEquals(5f, reading.drawMillis, Tolerance)
        assertEquals(10f, reading.totalMillis, Tolerance, "the total is the three added up")
    }

    @Test
    fun `everything inside one frame is added together`() {
        val budget = budget()
        // Two panels drawn in one frame is one frame's drawing, not two.
        budget.draw { costing(2) }
        budget.draw { costing(3) }
        budget.endFrame()

        assertEquals(5f, budget.reading.drawMillis, Tolerance, "both panels, not the last one")
    }

    @Test
    fun `the average is taken over the window and no further`() {
        val budget = budget(window = 3)
        // Four frames into a window of three: the first one is gone.
        budget.draw { costing(12) }
        budget.endFrame()
        assertEquals(12f, budget.reading.drawMillis, Tolerance, "the one frame there has been")

        repeat(3) { budget.endFrame() }

        assertEquals(
            0f,
            budget.reading.drawMillis,
            Tolerance,
            "the expensive frame should have fallen out of a window of three",
        )
        assertEquals(4L, budget.reading.frames, "the frame count is not a window, it is a total")
    }

    @Test
    fun `the worst frame is remembered when the average has forgiven it`() {
        val budget = budget()
        budget.draw { costing(12) }
        budget.endFrame()
        repeat(3) { budget.endFrame() }

        val reading = budget.reading
        assertEquals(3f, reading.totalMillis, Tolerance, "twelve milliseconds spread over four frames")
        assertEquals(12f, reading.worstMillis, Tolerance, "but the bad frame is still named")
    }

    @Test
    fun `it does not publish more often than it was asked to`() {
        val budget = budget(window = 8, publishEveryMillis = 60_000L)
        budget.endFrame(drawCalls = 1)
        val first = budget.reading

        clock.advance(59_999)
        repeat(20) { budget.endFrame(drawCalls = 99) }
        assertSame(first, budget.reading, "a minute has not passed")
        assertEquals(1, budget.reading.drawCalls)

        clock.advance(1)
        budget.endFrame(drawCalls = 99)
        assertEquals(99, budget.reading.drawCalls, "and now it has")
    }

    @Test
    fun `reset forgets everything`() {
        val budget = budget()
        budget.draw { costing(2) }
        budget.endFrame(drawCalls = 5)
        budget.reset()

        assertSame(FrameReading.Nothing, budget.reading)
        budget.endFrame(drawCalls = 2, redrew = false)
        assertEquals(1L, budget.reading.frames)
        assertEquals(0L, budget.reading.redraws)
        assertEquals(0f, budget.reading.drawMillis, Tolerance, "and the frame it measured before")
    }

    @Test
    fun `the frame's culprits are published beside its draw calls and then forgotten`() {
        val budget = budget()
        budget.trace.node = UiNode("hotbar")
        budget.trace.record(BatchBreak.Blend)
        budget.trace.record(BatchBreak.Blend)
        budget.trace.node = null
        budget.trace.record(BatchBreak.End)
        budget.endFrame(drawCalls = 3)

        val culprits = budget.reading.culprits
        assertEquals(listOf("hotbar" to BatchBreak.Blend), culprits.map { it.name to it.reason })
        assertEquals(2, culprits.single().calls)
        assertEquals(0, budget.trace.total, "the next frame starts with nothing blamed")

        budget.endFrame(drawCalls = 1)
        assertTrue(budget.reading.culprits.isEmpty(), "a frame that cut nothing blames nothing")
    }

    @Test
    fun `a frame that is not published still forgets what it traced`() {
        val budget = budget(publishEveryMillis = 60_000L)
        budget.endFrame()
        budget.trace.record(BatchBreak.Clip)
        budget.endFrame()

        assertEquals(0, budget.trace.total, "otherwise a quiet minute adds up to one enormous frame")
    }

    @Test
    fun `reset forgets what was traced`() {
        val budget = budget()
        budget.trace.record(BatchBreak.Texture)
        budget.reset()
        assertEquals(0, budget.trace.total)
    }

    @Test
    fun `the frames it hands back are the last ones it measured oldest first`() {
        val budget = budget()
        measure(budget, 1, 2, 3, 4, 5, 6)

        val frames = FloatArray(4)
        assertEquals(4, budget.recentFrameMillis(frames), "a full window once the ring has been round")
        assertEquals(listOf(3f, 4f, 5f, 6f), frames.toList(), "the four still held, oldest first")

        val fewer = FloatArray(2)
        assertEquals(2, budget.recentFrameMillis(fewer), "only what fits when less is asked for")
        assertEquals(listOf(5f, 6f), fewer.toList(), "and the last two rather than the first two")

        val roomier = FloatArray(6)
        assertEquals(4, budget.recentFrameMillis(roomier), "and only what it has when more is asked for")
        assertEquals(listOf(3f, 4f, 5f, 6f), roomier.take(4), "written from the start of the array")
    }

    @Test
    fun `before the ring has been round it hands back the frames there are`() {
        val budget = budget()
        measure(budget, 7, 8)

        val frames = FloatArray(4)
        assertEquals(2, budget.recentFrameMillis(frames), "two frames measured is two frames to give")
        assertEquals(listOf(7f, 8f), frames.take(2), "still oldest first")
    }

    @Test
    fun `a budget that has measured nothing hands back nothing`() {
        assertEquals(0, budget().recentFrameMillis(FloatArray(4)))
    }

    /** Closes off one frame per entry, each costing that many whole milliseconds of draw. */
    private fun measure(budget: FrameBudget, vararg millis: Int) {
        millis.forEach {
            budget.draw { costing(it.toLong()) }
            budget.endFrame()
        }
    }

    private companion object {
        /** Floats made of whole milliseconds land exactly; this only guards the arithmetic. */
        const val Tolerance = 0.0001f
    }
}
