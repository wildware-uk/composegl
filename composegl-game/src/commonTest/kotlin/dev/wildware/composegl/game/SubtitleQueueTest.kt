package dev.wildware.composegl.game

import dev.wildware.composegl.ui.animation.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The queue on its own, with no screen: what is up, what is waiting, and how time moves it along.
 *
 * Time is handed over by hand here rather than taken from a clock, which is the same door the audio
 * system comes in through — so these are also the tests of the audio-driven mode.
 */
class SubtitleQueueTest {

    @Test
    fun `a line goes up and comes down when its time runs out`() {
        val subs = SubtitleQueue(capacity = 1)

        subs.show("We are through the gate.", speaker = "Mira", durationMillis = 2_000)
        assertEquals(1, subs.shown.size)
        assertEquals(2_000, subs.shown.first().remainingMillis)

        subs.advance(1_500)
        assertEquals(500, subs.shown.first().remainingMillis, "half read is still on screen")

        subs.advance(500)
        assertTrue(subs.isIdle, "it should have come down when its time ran out")
    }

    @Test
    fun `a line with no duration stays up for as long as it takes to read`() {
        val subs = SubtitleQueue(charactersPerSecond = 10f, minimumMillis = 1_000)

        val long = subs.show("0123456789012345678901234")
        assertEquals(2_500, long.remainingMillis, "twenty-five characters at ten a second")

        subs.clear()
        val short = subs.show("Go.")
        assertEquals(1_000, short.remainingMillis, "a line that flashes is a line nobody read")
    }

    @Test
    fun `a script handed over at once is queued rather than piled on the screen`() {
        val subs = SubtitleQueue(capacity = 2)

        repeat(6) { subs.show("line $it", durationMillis = 1_000) }

        assertEquals(listOf("line 0", "line 1"), subs.shown.map { it.text })
        assertEquals(4, subs.waiting)

        subs.advance(1_000)
        assertEquals(listOf("line 2", "line 3"), subs.shown.map { it.text }, "the next two took their turn")
        assertEquals(2, subs.waiting)
    }

    @Test
    fun `a line only starts counting down once it is on screen`() {
        val subs = SubtitleQueue(capacity = 1)

        subs.show("first", durationMillis = 1_000)
        val second = subs.show("second", durationMillis = 1_000)
        assertEquals(0, second.remainingMillis, "it has not been said yet")

        subs.advance(900)
        assertEquals(0, second.remainingMillis, "still waiting")

        subs.advance(100)
        assertEquals(1_000, second.remainingMillis, "its full time starts when it goes up")
    }

    @Test
    fun `the oldest waiting line is dropped rather than the newest`() {
        val subs = SubtitleQueue(capacity = 1, backlog = 2)

        subs.show("on screen", durationMillis = 1_000)
        repeat(4) { subs.show("waiting $it", durationMillis = 1_000) }

        assertEquals(2, subs.waiting, "the backlog holds two")
        subs.advance(1_000)
        assertEquals(listOf("waiting 2"), subs.shown.map { it.text }, "the newest waiting ones are the kept ones")
    }

    @Test
    fun `a caption is up alongside the line being spoken`() {
        val subs = SubtitleQueue(capacity = 2)

        subs.show("Stay down.", speaker = "Mira", durationMillis = 3_000)
        val bang = subs.caption("[explosion in the distance]", durationMillis = 1_000)

        assertEquals(2, subs.shown.size)
        assertTrue(bang.caption)
        assertEquals(null, bang.speaker, "nobody speaks a sound")

        // The shorter one goes first even though it is not the oldest.
        subs.advance(1_000)
        assertEquals(listOf("Stay down."), subs.shown.map { it.text })
    }

    @Test
    fun `skipping a line lets the next one in straight away`() {
        val subs = SubtitleQueue(capacity = 1)

        subs.show("first", durationMillis = 10_000)
        subs.show("second", durationMillis = 10_000)

        subs.dismiss(subs.shown.first())

        assertEquals(listOf("second"), subs.shown.map { it.text })
        assertEquals(0, subs.waiting)
    }

    @Test
    fun `skipping a line that is only waiting takes it out of the queue`() {
        val subs = SubtitleQueue(capacity = 1)

        subs.show("first", durationMillis = 10_000)
        val waiting = subs.show("second", durationMillis = 10_000)

        subs.dismiss(waiting)

        assertEquals(0, subs.waiting)
        assertEquals(listOf("first"), subs.shown.map { it.text }, "the line being spoken was left alone")
    }

    @Test
    fun `clearing takes everything away at once`() {
        val subs = SubtitleQueue(capacity = 2)
        repeat(6) { subs.show("$it", durationMillis = 1_000) }

        subs.clear()

        assertTrue(subs.isIdle)
        assertEquals(0, subs.waiting)
    }

    // --- driven by the audio -----------------------------------------------------------------

    @Test
    fun `the audio's playback position moves the queue along`() {
        val subs = SubtitleQueue(capacity = 1, clock = null)
        subs.show("We are through the gate.", durationMillis = 2_000)

        // The first call only takes the mark: there is no gap to measure yet.
        subs.playTo(4_000)
        assertEquals(2_000, subs.shown.first().remainingMillis)

        subs.playTo(5_200)
        assertEquals(800, subs.shown.first().remainingMillis, "it moves by what the audio moved by")

        subs.playTo(6_000)
        assertTrue(subs.isIdle, "the words leave when the actor stops speaking")
    }

    @Test
    fun `a frame the game slept through still costs the line the right amount of time`() {
        val subs = SubtitleQueue(capacity = 1, clock = null)
        subs.show("a long recorded line", durationMillis = 5_000)

        subs.playTo(0)
        subs.playTo(3_000)

        assertEquals(2_000, subs.shown.first().remainingMillis, "one slow frame is not one frame's worth of time")
    }

    @Test
    fun `the audio jumping backwards clears what was on screen`() {
        val subs = SubtitleQueue(capacity = 1, clock = null)
        subs.show("before the seek", durationMillis = 5_000)
        subs.playTo(10_000)

        subs.playTo(0)

        assertTrue(subs.isIdle, "those words belong to a moment that is no longer happening")

        // And the new position is the mark, so the next line is timed from there rather than jumped past.
        subs.show("after the seek", durationMillis = 5_000)
        subs.playTo(1_000)
        assertEquals(4_000, subs.shown.first().remainingMillis)
    }

    @Test
    fun `time handed to an empty queue does nothing`() {
        val subs = SubtitleQueue(clock = Clock.World)

        subs.advance(10_000)
        subs.advance(-5)

        assertTrue(subs.isIdle)
        assertFalse(subs.shown.isNotEmpty())
    }

    // --- the player's own settings ------------------------------------------------------------

    @Test
    fun `every size preset has a scale and they are in order`() {
        val scales = SubtitleSize.scales

        assertEquals(SubtitleSize.entries.size, scales.size)
        assertEquals(1f, SubtitleSize.Medium.scale, "medium is the size the skin asks for")
        assertEquals(scales.sorted(), scales, "the presets should read small to large")
    }

    @Test
    fun `settings that make no sense are refused rather than drawn`() {
        assertFailsWith<IllegalArgumentException> { SubtitleSettings(backgroundOpacity = 1.4f) }
        assertFailsWith<IllegalArgumentException> { SubtitleSettings(maxLines = 0) }
        assertFailsWith<IllegalArgumentException> { SubtitleSettings(widthFraction = 0f) }

        // A queue built out of nonsense would swallow every line in silence rather than say so.
        assertFailsWith<IllegalArgumentException> { SubtitleQueue(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { SubtitleQueue(backlog = -1) }
        assertFailsWith<IllegalArgumentException> { SubtitleQueue(charactersPerSecond = 0f) }
        assertFailsWith<IllegalArgumentException> { SubtitleQueue(minimumMillis = -1) }
    }

    @Test
    fun `saying the same line object twice leaves the one that is already up alone`() {
        val subs = SubtitleQueue(capacity = 2)
        val reloading = SubtitleLine("Reloading.", durationMillis = 2_000)

        subs.show(reloading)
        subs.advance(500)
        subs.show(reloading)

        assertEquals(1, subs.shown.size, "one object is one line")
        assertEquals(0, subs.waiting, "and it should not be waiting behind itself either")
        assertEquals(1_500, reloading.remainingMillis, "its countdown should not have been restarted")

        // Taking it down takes it down, rather than leaving a copy behind.
        subs.dismiss(reloading)
        assertTrue(subs.isIdle)

        // The same words said again are a line of their own, which is what a game should hand over.
        subs.show(SubtitleLine("Reloading.", durationMillis = 2_000))
        assertEquals(1, subs.shown.size)
    }

    @Test
    fun `a line waiting its turn is not queued a second time`() {
        val subs = SubtitleQueue(capacity = 1)
        subs.show("on screen", durationMillis = 1_000)
        val waiting = SubtitleLine("waiting", durationMillis = 1_000)

        subs.show(waiting)
        subs.show(waiting)

        assertEquals(1, subs.waiting)
    }
}
