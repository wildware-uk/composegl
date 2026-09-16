package dev.wildware.composegl.game

import dev.wildware.composegl.ui.text.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules a chat follows, with nothing drawn: what it keeps, where a typed line goes, and what
 * Up and Down do with what has been said before.
 *
 * The half a player touches is [ChatBoxTest].
 */
class ChatStateTest {

    private val all = ChatChannel("all", "All", prefix = "/a")
    private val team = ChatChannel("team", "Team", prefix = "/t")
    private val teamLead = ChatChannel("lead", "Lead", prefix = "/te")
    private val channels = listOf(all, team, teamLead)

    private val outbox = mutableListOf<Pair<ChatChannel, String>>()

    private fun chat(
        maxMessages: Int = 200,
        idleLines: Int = 6,
        sentLimit: Int = 30,
    ) = ChatState(maxMessages = maxMessages, idleLines = idleLines, sentLimit = sentLimit)
        .also { it.channel = all }

    /** Types [text] and presses Enter. True when the line only moved the player to a channel. */
    private fun ChatState.typeAndSend(text: String): Boolean {
        onInput(TextFieldValue(text))
        return send(channels) { channel, line -> outbox += channel to line }
    }

    // --- what arrives ------------------------------------------------------------------------

    @Test
    fun `a message that arrives is in the log and up over the HUD`() {
        val chat = chat()

        chat.receive(ChatMessage("on my way", from = "Mira", channel = team))

        assertEquals(listOf("on my way"), chat.messages.map { it.text })
        assertEquals(listOf("on my way"), chat.recent.map { it.text })
        assertFalse(chat.isIdle)
    }

    @Test
    fun `the log keeps the newest and lets the oldest go`() {
        val chat = chat(maxMessages = 3)

        repeat(5) { chat.receive(ChatMessage("line $it", from = "Mira")) }

        assertEquals(listOf("line 2", "line 3", "line 4"), chat.messages.map { it.text })
    }

    @Test
    fun `only the newest few linger over the HUD`() {
        val chat = chat(idleLines = 2)

        repeat(4) { chat.receive(ChatMessage("line $it", from = "Mira")) }

        assertEquals(listOf("line 2", "line 3"), chat.recent.map { it.text })
        assertEquals(4, chat.messages.size, "the log itself keeps them all")
    }

    @Test
    fun `a line the game said itself has nobody in front of it`() {
        val chat = chat()

        val said = chat.system("Mira has joined")

        assertNull(said.from)
        assertNull(said.channel)
    }

    @Test
    fun `a line that has faded leaves the HUD and stays in the log`() {
        val chat = chat()
        val message = chat.receive(ChatMessage("hello", from = "Mira"))

        chat.retire(message)

        assertTrue(chat.recent.isEmpty())
        assertEquals(1, chat.messages.size)
        assertTrue(chat.isIdle, "nothing on screen and nothing being typed is what costs nothing")
    }

    @Test
    fun `clearing empties the log and the screen`() {
        val chat = chat()
        chat.receive(ChatMessage("hello", from = "Mira"))

        chat.clear()

        assertTrue(chat.messages.isEmpty())
        assertTrue(chat.recent.isEmpty())
    }

    // --- where a typed line goes ---------------------------------------------------------------

    @Test
    fun `a line with no prefix goes to the channel being spoken in`() {
        val chat = chat()

        chat.typeAndSend("ready")

        assertEquals(listOf(all to "ready"), outbox)
        assertEquals("", chat.draft.text, "the box is empty again")
    }

    @Test
    fun `a prefix sends one line elsewhere without leaving the channel being spoken in`() {
        val chat = chat()

        chat.typeAndSend("/t on my way")

        assertEquals(listOf(team to "on my way"), outbox)
        assertEquals(all, chat.channel, "one line to the team is not a move to the team")
    }

    @Test
    fun `a prefix on its own switches channel and says nothing`() {
        val chat = chat()

        val moved = chat.typeAndSend("/t")

        assertEquals(emptyList(), outbox)
        assertEquals(team, chat.channel)
        assertEquals("", chat.draft.text)
        assertTrue(moved, "it should report that it only moved channel, so the box stays open")
    }

    @Test
    fun `the longest prefix wins`() {
        val chat = chat()

        chat.typeAndSend("/te regroup")

        assertEquals(listOf(teamLead to "regroup"), outbox)
    }

    @Test
    fun `an empty line says nothing at all`() {
        val chat = chat()

        val moved = chat.typeAndSend("   ")

        assertTrue(outbox.isEmpty())
        assertTrue(chat.sent.isEmpty())
        assertFalse(moved, "an empty box is not a channel being chosen: Enter on it closes the box")
    }

    @Test
    fun `a line that was really said is not a channel being chosen`() {
        val chat = chat()

        assertFalse(chat.typeAndSend("ready"), "a line in the channel being spoken in")
        assertFalse(chat.typeAndSend("/t on my way"), "and a line prefixed to another one")
    }

    // --- what was said before --------------------------------------------------------------------

    @Test
    fun `up walks back through what was sent and down walks forward again`() {
        val chat = chat()
        chat.typeAndSend("first")
        chat.typeAndSend("second")

        chat.earlier()
        assertEquals("second", chat.draft.text)
        chat.earlier()
        assertEquals("first", chat.draft.text)
        chat.later()
        assertEquals("second", chat.draft.text)
    }

    @Test
    fun `the caret lands at the end of a line brought back`() {
        val chat = chat()
        chat.typeAndSend("regroup at the relay")

        chat.earlier()

        assertEquals("regroup at the relay".length, chat.draft.selection.start)
        assertTrue(chat.draft.selection.collapsed)
    }

    @Test
    fun `the half-typed line comes back when down reaches the end of the history`() {
        val chat = chat()
        chat.typeAndSend("first")
        chat.onInput(TextFieldValue("half a th"))

        chat.earlier()
        assertEquals("first", chat.draft.text)
        chat.later()

        assertEquals("half a th", chat.draft.text)
    }

    @Test
    fun `the same line sent twice is only remembered once`() {
        val chat = chat()
        chat.typeAndSend("ready")
        chat.typeAndSend("ready")

        assertEquals(listOf("ready"), chat.sent.toList())
    }

    @Test
    fun `the history keeps only the newest lines`() {
        val chat = chat(sentLimit = 2)

        chat.typeAndSend("one")
        chat.typeAndSend("two")
        chat.typeAndSend("three")

        assertEquals(listOf("two", "three"), chat.sent.toList())
    }

    @Test
    fun `what a line was sent to is what is remembered rather than the prefix`() {
        val chat = chat()

        chat.typeAndSend("/t on my way")

        assertEquals(listOf("on my way"), chat.sent.toList())
    }

    @Test
    fun `typing something ends the walk through the history`() {
        val chat = chat()
        chat.typeAndSend("first")
        chat.typeAndSend("second")

        chat.earlier()
        chat.onInput(TextFieldValue("something else"))
        chat.later()

        assertEquals("something else", chat.draft.text, "Down after typing is not a walk that was going on")
    }

    // --- being open ---------------------------------------------------------------------------

    @Test
    fun `opening it in a channel is how a game answers a whisper`() {
        val chat = chat()

        chat.open(team)

        assertTrue(chat.isOpen)
        assertEquals(team, chat.channel)
        chat.close()
        assertFalse(chat.isOpen)
    }
}
