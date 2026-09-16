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
 * What is deliberately not asserted is a duration. A test that says layout took less than a
 * millisecond is a test that fails on a loaded machine, and one that says it took more than zero is
 * a test that fails on a fast one, so the timings are checked for being plausible and the counting
 * is checked for being right.
 */
class FrameBudgetTest {

    /** Publishes on every frame, so a test does not have to wait a quarter of a second. */
    private fun budget() = FrameBudget(window = 4, publishEveryMillis = 0L)

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
        budget.draw { spin() }
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
        budget.recompose { spin() }
        budget.layout { spin() }
        budget.draw { spin() }
        budget.endFrame()

        val reading = budget.reading
        assertTrue(reading.recomposeMillis >= 0f)
        assertTrue(reading.layoutMillis >= 0f)
        assertTrue(reading.drawMillis >= 0f)
        // The total is the three added up, give or take the rounding each one does to milliseconds.
        val parts = reading.recomposeMillis + reading.layoutMillis + reading.drawMillis
        assertTrue(
            (reading.totalMillis - parts) < 0.01f,
            "total was ${reading.totalMillis} and the parts add up to $parts",
        )
    }

    @Test
    fun `everything inside one frame is added together`() {
        val budget = budget()
        // Two panels drawn in one frame is one frame's drawing, not two.
        budget.draw { spin() }
        budget.draw { spin() }
        budget.endFrame()
        val both = budget.reading.drawMillis

        val one = budget()
        one.draw { spin() }
        one.endFrame()

        assertTrue(both >= one.reading.drawMillis, "two lots of work cannot cost less than one")
    }

    @Test
    fun `the average is taken over the window and no further`() {
        val budget = FrameBudget(window = 3, publishEveryMillis = 0L)
        // Four frames into a window of three: the first one is gone.
        budget.draw { spinFor(4) }
        budget.endFrame()
        val expensive = budget.reading.drawMillis
        repeat(3) {
            budget.endFrame()
        }

        assertTrue(
            budget.reading.drawMillis < expensive,
            "the expensive frame should have fallen out of a window of three",
        )
        assertEquals(4L, budget.reading.frames, "the frame count is not a window, it is a total")
    }

    @Test
    fun `the worst frame is remembered when the average has forgiven it`() {
        val budget = budget()
        budget.draw { spinFor(4) }
        budget.endFrame()
        repeat(3) { budget.endFrame() }

        val reading = budget.reading
        assertTrue(
            reading.worstMillis >= reading.totalMillis,
            "worst was ${reading.worstMillis} and the average ${reading.totalMillis}",
        )
    }

    @Test
    fun `it does not publish more often than it was asked to`() {
        val budget = FrameBudget(window = 8, publishEveryMillis = 60_000L)
        budget.endFrame(drawCalls = 1)
        val first = budget.reading
        repeat(20) { budget.endFrame(drawCalls = 99) }

        assertSame(first, budget.reading, "a minute has not passed")
        assertEquals(1, budget.reading.drawCalls)
    }

    @Test
    fun `reset forgets everything`() {
        val budget = budget()
        budget.draw { spinFor(2) }
        budget.endFrame(drawCalls = 5)
        budget.reset()

        assertSame(FrameReading.Nothing, budget.reading)
        budget.endFrame(drawCalls = 2, redrew = false)
        assertEquals(1L, budget.reading.frames)
        assertEquals(0L, budget.reading.redraws)
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
        val budget = FrameBudget(window = 4, publishEveryMillis = 60_000L)
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
        // Whole milliseconds put in by hand rather than measured, so the order is readable and the
        // test does not depend on how fast the machine running it is.
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
            budget.addDraw(it * 1_000_000L)
            budget.endFrame()
        }
    }

    private var sink = 0

    /** A little real work, so that a phase has something to measure. */
    private fun spin() = spinFor(1)

    private fun spinFor(rounds: Int) {
        repeat(rounds) {
            for (index in 0 until 20_000) sink += index
        }
    }
}
