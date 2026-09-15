package dev.wildware.composegl.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which picture is up after a given time: the arithmetic every animation comes down to. */
class SpriteAnimationTest {

    private val tenth = 100_000_000L

    @Test
    fun `a loop moves one picture per frame length and wraps`() {
        val shown = (0..7).map { frameAt(it * tenth, fps = 10f, count = 3, loop = true) }

        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1), shown)
    }

    @Test
    fun `a one-shot stays on its last picture`() {
        val shown = listOf(0L, tenth, 2 * tenth, 3 * tenth, 50 * tenth).map {
            frameAt(it, fps = 10f, count = 3, loop = false)
        }

        assertEquals(listOf(0, 1, 2, 2, 2), shown)
    }

    @Test
    fun `just before a boundary is still the earlier picture`() {
        assertEquals(0, frameAt(tenth - 1_000L, fps = 10f, count = 4, loop = true))
        assertEquals(1, frameAt(tenth, fps = 10f, count = 4, loop = true))
    }

    @Test
    fun `exactly one twelfth of a second at twelve a second is the second picture`() {
        // 83_333_333 nanoseconds times twelve is a hair under a second in doubles.
        assertEquals(1, frameAt(83_333_334L, fps = 12f, count = 12, loop = true))
        assertEquals(11, frameAt(11 * 83_333_334L, fps = 12f, count = 12, loop = true))
    }

    @Test
    fun `a slow frame skips the pictures it owed`() {
        assertEquals(5, frameAt(5 * tenth + 30_000_000L, fps = 10f, count = 8, loop = true))
    }

    @Test
    fun `one picture is always picture nought`() {
        assertEquals(0, frameAt(99 * tenth, fps = 10f, count = 1, loop = true))
    }

    @Test
    fun `time before the start is the first picture`() {
        assertEquals(0, frameAt(-tenth, fps = 10f, count = 3, loop = true))
    }
}
