package dev.wildware.composegl.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The counter on a quest step — "3/5 wolves" — on its own, with no screen anywhere near it.
 *
 * It is two counts rather than a fraction because two counts is what the player reads, and the
 * arithmetic on top of them is the part a widget must not get wrong: a sixth wolf killed is still a
 * finished step rather than a bar past its own end.
 */
class ObjectiveProgressTest {

    @Test
    fun `it reads as the counter a player sees`() {
        assertEquals("3/5", ObjectiveProgress(3, 5).toString())
    }

    @Test
    fun `the fraction is the counts divided`() {
        assertEquals(0.6f, ObjectiveProgress(3, 5).fraction)
        assertEquals(0f, ObjectiveProgress(0, 5).fraction)
        assertEquals(1f, ObjectiveProgress(5, 5).fraction)
    }

    @Test
    fun `counting past the end is finished rather than more than finished`() {
        val over = ObjectiveProgress(6, 5)
        assertEquals(1f, over.fraction, "a bar cannot run past its own end")
        assertTrue(over.isComplete)
        assertEquals("6/5", over.toString(), "and the counter still says what the game counted")
    }

    @Test
    fun `a step nobody has started is not complete`() {
        assertFalse(ObjectiveProgress(0, 1).isComplete)
        assertTrue(ObjectiveProgress(1, 1).isComplete)
    }

    @Test
    fun `two counters of the same two numbers are the same counter`() {
        assertEquals(ObjectiveProgress(2, 4), ObjectiveProgress(2, 4))
        assertEquals(ObjectiveProgress(2, 4).hashCode(), ObjectiveProgress(2, 4).hashCode())
        assertFalse(ObjectiveProgress(2, 4) == ObjectiveProgress(3, 4))
    }

    @Test
    fun `a counter of nothing and a count below nought are refused`() {
        assertFailsWith<IllegalArgumentException> { ObjectiveProgress(1, 0) }
        assertFailsWith<IllegalArgumentException> { ObjectiveProgress(-1, 5) }
    }
}
