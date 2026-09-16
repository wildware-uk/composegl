package dev.wildware.composegl.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a conversation leaves behind: the lines in order and the answers against the right ones. */
class DialogueLogTest {

    private val log = DialogueLog(limit = 4)

    @Test
    fun `it keeps what was said in the order it was said`() {
        val first = DialogueLine("the relay is quiet")
        val second = DialogueLine("we are not alone")

        log.say(first)
        log.say(second)

        assertEquals(listOf(first, second), log.entries.map { it.line })
    }

    @Test
    fun `the same line said twice in a row is written down once`() {
        val line = DialogueLine("…")

        log.say(line)
        log.say(line)

        assertEquals(1, log.entries.size, "a box that recomposes twice must not double the log")
    }

    @Test
    fun `two different lines that say the same thing are two lines`() {
        log.say(DialogueLine("…"))
        log.say(DialogueLine("…"))

        assertEquals(2, log.entries.size)
    }

    @Test
    fun `an answer goes against the line that asked for it`() {
        log.say(DialogueLine("what now"))
        log.answer("run")
        log.say(DialogueLine("agreed"))

        assertEquals("run", log.entries.first().answer)
        assertNull(log.entries.last().answer, "the line nobody answered should have no answer")
    }

    @Test
    fun `the oldest lines go once it is full`() {
        repeat(6) { log.say(DialogueLine("line $it")) }

        assertEquals(4, log.entries.size)
        assertEquals("line 2", log.entries.first().line.text, "it keeps the newest rather than the first four")
        assertEquals("line 5", log.entries.last().line.text)
    }

    @Test
    fun `clearing it starts the next conversation empty`() {
        log.say(DialogueLine("the relay is quiet"))
        log.clear()

        assertTrue(log.isEmpty)
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `answering before anything has been said does nothing`() {
        log.answer("run")

        assertTrue(log.isEmpty)
    }
}
