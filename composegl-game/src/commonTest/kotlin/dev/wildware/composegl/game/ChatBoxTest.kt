package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.Locale
import dev.wildware.composegl.ui.text.ProvideLocale
import dev.wildware.composegl.ui.text.Strings
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.PopupHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat box, composed for real and played with a keyboard, a mouse and a pad.
 *
 * The rules underneath are [ChatStateTest]'s. This is the half a player touches: the last lines
 * fading over the HUD, one key in and one key out, a letter typed here that does not also drive the
 * game, the channels, a name right-clicked, a log that stays where it was put, and all of it
 * mirrored for a screen that reads right to left.
 */
class ChatBoxTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val all = ChatChannel("all", "All", prefix = "/a")
    private val team = ChatChannel("team", "Team", prefix = "/t", style = "chat.message")
    private val channels = listOf(all, team)

    private val outbox = mutableListOf<Pair<ChatChannel, String>>()
    private val named = mutableListOf<String>()

    private fun open(
        state: ChatState,
        size: Size = Size(600f, 400f),
        rtl: Boolean = false,
        menu: Boolean = false,
        historyHeight: Float = 120f,
        show: ((ChatMessage) -> Boolean)? = null,
        placeholder: String? = null,
        strings: Strings? = null,
        locale: Locale = Locale.English,
    ): UiTest = uiTest(size) {
        Screen(state, rtl, menu, historyHeight, show, placeholder, strings, locale)
    }.also { opened += it }

    /** One chat box in the corner of a screen, with everything it needs around it. */
    @Composable
    private fun Screen(
        state: ChatState,
        rtl: Boolean,
        menu: Boolean,
        historyHeight: Float,
        show: ((ChatMessage) -> Boolean)?,
        placeholder: String?,
        strings: Strings?,
        locale: Locale,
    ) {
        val body: @Composable () -> Unit = {
            ProvideLayoutDirection(if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                PopupHost {
                    Box(Modifier.fillMaxSize()) {
                        ChatBox(
                            state = state,
                            modifier = Modifier.align(Alignment.BottomStart),
                            channels = channels,
                            onSend = { channel, text -> outbox += channel to text },
                            width = 360f,
                            historyHeight = historyHeight,
                            show = show,
                            placeholder = placeholder,
                            nameMenu = if (!menu) null else {
                                { message ->
                                    Item("Whisper") { named += "whisper ${message.from}" }
                                    Separator()
                                    Item("Mute") { named += "mute ${message.tag}" }
                                }
                            },
                        )
                    }
                }
            }
        }
        if (strings == null) body() else ProvideLocale(locale, strings) { body() }
    }

    private fun chat(
        idleMillis: Int = 300,
        fadeMillis: Int = 100,
        idleLines: Int = 6,
        maxMessages: Int = 200,
    ) = ChatState(
        maxMessages = maxMessages,
        idleLines = idleLines,
        idleMillis = idleMillis,
        fadeMillis = fadeMillis,
    ).also { it.channel = all }

    private fun UiTest.words(): String = text(ChatTags.Root)

    /** Every run of text [node] and everything inside it draws. Menus carry no tags of their own. */
    private fun UiTest.wordsOf(node: UiNode): String {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        val bounds = node.layoutBoundsInRoot
        DrawPass(canvas).draw(node, bounds.left - node.x, bounds.top - node.y)
        return canvas.texts().joinToString("")
    }

    private fun UiTest.menuRows(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.name == "menu.item") found += it }
        return found
    }

    private fun UiTest.clickRow(label: String) {
        val row = menuRows().lastOrNull { wordsOf(it).startsWith(label) }
            ?: throw AssertionError("no open menu has a row \"$label\":\n" + dump())
        click(row.boundsInRoot.centre)
    }

    // --- over the HUD -----------------------------------------------------------------------------

    @Test
    fun `the newest lines show over the HUD and fade away by themselves`() {
        val chat = chat(idleMillis = 300, fadeMillis = 100)
        val ui = open(chat)

        chat.receive(ChatMessage("on my way", from = "Mira", channel = team))
        ui.settle()
        assertTrue(ui.words().contains("on my way"), "a line that arrived should be readable: ${ui.words()}")
        assertTrue(ui.words().contains("Mira"), "with who said it in front of it")

        ui.advanceBy(600)

        assertTrue(chat.recent.isEmpty(), "it should have faded and gone")
        assertFalse(ui.words().contains("on my way"), "still drawn: ${ui.words()}")
    }

    @Test
    fun `a quiet chat draws nothing at all`() {
        val chat = chat()
        val ui = open(chat)

        assertEquals("", ui.words())
        assertTrue(chat.isIdle)
    }

    @Test
    fun `and asks for no frames while nobody is talking`() {
        val chat = chat()
        val ui = open(chat)
        chat.receive(ChatMessage("on my way", from = "Mira", channel = team))
        ui.advanceBy(600)

        var wall = ui.nanos
        repeat(30) {
            wall += 16_000_000L
            assertFalse(ui.host.frame(wall), "frame $it redrew a chat where nothing had changed")
        }
    }

    // --- one key in and one key out ----------------------------------------------------------------

    @Test
    fun `the open key opens the box with the caret already in it`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)

        assertTrue(chat.isOpen)
        ui.assertExists(ChatTags.Panel)
        ui.assertFocused(ChatTags.Input)
    }

    @Test
    fun `a line typed and sent reaches the game and puts the box away`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.type("on my way")
        ui.key(Key.Enter)

        assertEquals(listOf(all to "on my way"), outbox)
        assertFalse(chat.isOpen, "one key in and one key out")
        ui.assertDoesNotExist(ChatTags.Panel)
    }

    @Test
    fun `a prefix sends the line to that channel instead`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.type("/t regroup")
        ui.key(Key.Enter)

        assertEquals(listOf(team to "regroup"), outbox)
    }

    @Test
    fun `a prefix on its own moves channel and leaves the box open`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.type("/t")
        ui.key(Key.Enter)

        assertEquals(team, chat.channel, "the player chose where to talk")
        assertTrue(chat.isOpen, "choosing a channel is not saying something: the box should stay open")
        ui.assertExists(ChatTags.Panel)
        assertEquals(emptyList(), outbox, "and nothing was said in it")
        assertEquals("Say something", ui.text(ChatTags.Input), "with an empty box to say it in")

        ui.type("on my way")
        ui.key(Key.Enter)
        assertEquals(listOf(team to "on my way"), outbox, "and the next line goes to the new channel")
        assertFalse(chat.isOpen, "which does close it")
    }

    @Test
    fun `escape closes it and leaves what was half typed`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.type("half a th")
        ui.key(Key.Escape)

        assertFalse(chat.isOpen)
        assertEquals(emptyList(), outbox)

        ui.key(Key.Enter)
        assertEquals("half a th", ui.text(ChatTags.Input), "what was being typed should still be there")
    }

    @Test
    fun `up at the input brings back the last line sent`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.type("ready")
        ui.key(Key.Enter)

        ui.key(Key.Enter)
        ui.key(Key.Up)

        assertEquals("ready", ui.text(ChatTags.Input))
    }

    @Test
    fun `a letter meant for the chat does not also drive the game`() {
        val chat = chat()
        val ui = open(chat)

        assertFalse(ui.key(Key.W), "with the box closed the game hears its own keys")

        ui.key(Key.Enter)

        assertTrue(ui.key(Key.W), "with it open the box eats what the game would have walked on")
    }

    // --- the channels ------------------------------------------------------------------------------

    @Test
    fun `clicking a tab chooses the channel that is spoken in`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        ui.click(ChatTags.tab("team"))

        assertEquals(team, chat.channel)

        ui.type("on my way")
        ui.key(Key.Enter)
        assertEquals(listOf(team to "on my way"), outbox)
    }

    @Test
    fun `the pad opens it and its bumpers walk the channels`() {
        val chat = chat()
        val ui = open(chat)

        ui.pad(GamepadButton.North)
        assertTrue(chat.isOpen)

        ui.pad(GamepadButton.RightBumper)
        assertEquals(team, chat.channel)

        ui.pad(GamepadButton.LeftBumper)
        assertEquals(all, chat.channel, "and back round again")
    }

    @Test
    fun `back closes it the way the pad's east button does`() {
        val chat = chat()
        val ui = open(chat)

        ui.key(Key.Enter)
        assertTrue(ui.backs.back())
        ui.settle()

        assertFalse(chat.isOpen)
    }

    // --- the names ----------------------------------------------------------------------------------

    @Test
    fun `a name right-clicked opens the menu the game gave it`() {
        val chat = chat()
        val ui = open(chat, menu = true)
        chat.receive(ChatMessage("on my way", from = "Mira", channel = team, tag = "mira#1"))
        ui.key(Key.Enter)

        ui.click(ChatTags.name("Mira"), PointerButton.Secondary)
        ui.clickRow("Whisper")

        assertEquals(listOf("whisper Mira"), named)
    }

    @Test
    fun `the menu carries the game's own handle for whoever said it`() {
        val chat = chat()
        val ui = open(chat, menu = true)
        chat.receive(ChatMessage("on my way", from = "Mira", channel = team, tag = "mira#1"))
        ui.key(Key.Enter)

        ui.click(ChatTags.name("Mira"), PointerButton.Secondary)
        ui.clickRow("Mute")

        assertEquals(listOf("mute mira#1"), named)
    }

    // --- the log ------------------------------------------------------------------------------------

    @Test
    fun `the log opens at the newest line and follows it`() {
        val chat = chat()
        val ui = open(chat)
        repeat(20) { chat.receive(ChatMessage("line $it", from = "Mira", channel = all)) }
        ui.key(Key.Enter)

        assertTrue(ui.text(ChatTags.Log).contains("line 19"), "opened somewhere else: ${ui.text(ChatTags.Log)}")

        chat.receive(ChatMessage("line 20", from = "Mira", channel = all))
        ui.settle()

        assertTrue(ui.text(ChatTags.Log).contains("line 20"), "it stopped following: ${ui.text(ChatTags.Log)}")
    }

    @Test
    fun `a player who scrolled back to read keeps their place`() {
        val chat = chat()
        val ui = open(chat)
        repeat(30) { chat.receive(ChatMessage("line $it", from = "Mira", channel = all)) }
        ui.key(Key.Enter)

        ui.key(Key.PageUp)
        ui.key(Key.PageUp)
        val reading = ui.text(ChatTags.Log)
        assertFalse(reading.contains("line 29"), "PageUp scrolled nowhere: $reading")

        chat.receive(ChatMessage("line 30", from = "Mira", channel = all))
        ui.settle()

        assertFalse(
            ui.text(ChatTags.Log).contains("line 30"),
            "the new line snatched the log away from what was being read: ${ui.text(ChatTags.Log)}",
        )
    }

    @Test
    fun `a log at its limit still follows the newest line`() {
        // Every line that arrives from here on drops one off the front, so the log stays the same
        // length while everything in it moves up. A view watching the length would stop here.
        val chat = chat(maxMessages = 6)
        val ui = open(chat)
        repeat(6) { chat.receive(ChatMessage("line $it", from = "Mira", channel = all)) }
        ui.key(Key.Enter)

        repeat(6) { chat.receive(ChatMessage("line ${it + 6}", from = "Mira", channel = all)) }
        ui.settle()

        assertTrue(ui.text(ChatTags.Log).contains("line 11"), "it stopped following: ${ui.text(ChatTags.Log)}")
    }

    // --- the box narrowed to one channel ------------------------------------------------------------

    @Test
    fun `a closed box narrowed to one channel keeps the rest off the HUD`() {
        val chat = chat()
        val ui = open(chat, show = { it.channel == team })

        chat.receive(ChatMessage("on my way", from = "Mira", channel = team))
        chat.receive(ChatMessage("anyone selling ore", from = "Rook", channel = all))
        ui.settle()

        assertTrue(ui.words().contains("on my way"), "the channel it draws should be up: ${ui.words()}")
        assertFalse(
            ui.words().contains("selling ore"),
            "a channel this box does not draw faded over the HUD anyway: ${ui.words()}",
        )
    }

    @Test
    fun `a line the closed box never drew still leaves and the chat goes quiet`() {
        // Retiring a line is the fading line's own job, so one that is never drawn has to be let go
        // some other way or the chat would count itself busy for ever.
        val chat = chat()
        val ui = open(chat, show = { it.channel == team })

        chat.receive(ChatMessage("anyone selling ore", from = "Rook", channel = all))
        ui.settle()

        assertEquals("", ui.words(), "it drew something: ${ui.words()}")
        assertTrue(chat.isIdle, "a line nothing drew is holding the chat awake")
    }

    @Test
    fun `an open box narrowed to one channel shows only that channel's lines`() {
        val chat = chat()
        val ui = open(chat, show = { it.channel == team })
        chat.receive(ChatMessage("on my way", from = "Mira", channel = team))
        chat.receive(ChatMessage("anyone selling ore", from = "Rook", channel = all))

        ui.key(Key.Enter)

        val log = ui.text(ChatTags.Log)
        assertTrue(log.contains("on my way"), "the channel it draws is missing: $log")
        assertFalse(log.contains("selling ore"), "the log drew a channel it was told not to: $log")
    }

    @Test
    fun `a filtered log held at its limit stays where a player put it`() {
        // The log is full of another channel's chatter in front of the lines this box draws, so
        // every line that arrives drops one off the front — and none of the drops is a line the
        // player can see. Counting the drops rather than the lines on screen would read all of that
        // as the log having shuffled up under the window and snatch it back to the newest line.
        val chat = chat(maxMessages = 60)
        val ui = open(chat, show = { it.channel == team })
        repeat(30) { chat.receive(ChatMessage("chatter $it", from = "Rook", channel = all)) }
        repeat(30) { chat.receive(ChatMessage("team $it", from = "Mira", channel = team)) }
        ui.key(Key.Enter)

        assertTrue(ui.text(ChatTags.Log).contains("team 29"), "opened somewhere else: ${ui.text(ChatTags.Log)}")

        ui.key(Key.PageUp)
        ui.key(Key.PageUp)
        assertFalse(ui.text(ChatTags.Log).contains("team 29"), "PageUp scrolled nowhere: ${ui.text(ChatTags.Log)}")

        repeat(30) { chat.receive(ChatMessage("chatter ${it + 30}", from = "Rook", channel = all)) }
        ui.settle()

        assertEquals(30, chat.messages.count { it.channel == team }, "a line the box draws fell off the log")
        assertFalse(
            ui.text(ChatTags.Log).contains("team 29"),
            "lines the box never drew snatched the log away from what was being read: ${ui.text(ChatTags.Log)}",
        )
    }

    // --- the words of its own -----------------------------------------------------------------------

    @Test
    fun `the hint in the empty box comes from the game's strings`() {
        val strings = Strings(
            mapOf(
                Locale.English to mapOf("chat.say" to "Say something"),
                Locale("fr") to mapOf("chat.say" to "Dites quelque chose"),
            ),
        )
        val chat = chat()
        val ui = open(chat, strings = strings, locale = Locale("fr"))

        ui.key(Key.Enter)

        assertEquals("Dites quelque chose", ui.text(ChatTags.Input), "the hint should be in the player's language")
    }

    @Test
    fun `an untranslated hint keeps its English words rather than the key`() {
        // Nobody has translated this one, and a box saying `chat.say` is worse than an English one.
        val strings = Strings(mapOf(Locale("fr") to mapOf("chat.channel" to "Canal")))
        val chat = chat()
        val ui = open(chat, strings = strings, locale = Locale("fr"))

        ui.key(Key.Enter)

        assertEquals("Say something", ui.text(ChatTags.Input), "an untranslated hint should fall back to English")
    }

    @Test
    fun `a hint the game gave beats both the strings and the English`() {
        val strings = Strings(mapOf(Locale("fr") to mapOf("chat.say" to "Dites quelque chose")))
        val chat = chat()
        val ui = open(chat, placeholder = "Parlez au groupe", strings = strings, locale = Locale("fr"))

        ui.key(Key.Enter)

        assertEquals("Parlez au groupe", ui.text(ChatTags.Input), "the game's own hint should win")
    }

    // --- right to left ------------------------------------------------------------------------------

    @Test
    fun `on a right-to-left screen the name sits at the trailing edge of its own line`() {
        // Measured against the words it is in front of rather than against the screen: the whole box
        // moving to the other side of a mirrored screen would say nothing about the row inside it.
        val said = "on my way"

        val chat = chat()
        val ltr = open(chat)
        chat.receive(ChatMessage(said, from = "Mira", channel = team))
        ltr.settle()
        val name = ltr.node(ChatTags.name("Mira")).boundsInRoot
        val words = ltr.node(ChatTags.said(said)).boundsInRoot

        val mirrored = chat()
        val rtl = open(mirrored, rtl = true)
        mirrored.receive(ChatMessage(said, from = "Mira", channel = team))
        rtl.settle()
        val mirroredName = rtl.node(ChatTags.name("Mira")).boundsInRoot
        val mirroredWords = rtl.node(ChatTags.said(said)).boundsInRoot

        assertTrue(
            name.right <= words.left,
            "in English the name should be left of what was said: $name then $words",
        )
        assertTrue(
            mirroredName.left >= mirroredWords.right,
            "and in Arabic right of it: $mirroredWords then $mirroredName",
        )
    }

    @Test
    fun `a line mixing hebrew and english reads by its own first letter`() {
        // The pieces come back in the order they are drawn across the line, so this is the order a
        // player reads them in, and Hebrew is drawn right to left inside its own piece. Which way a
        // line reads is the line's own first letter, not the screen's — so the same mixed line reads
        // the same way whichever screen it lands on, which is what a chat full of both needs.
        val english = "Press \u05e9\u05dc\u05d5\u05dd to start"
        val hebrew = "\u05e9\u05dc\u05d5\u05dd world."
        val shalom = "\u05dd\u05d5\u05dc\u05e9"

        listOf(false, true).forEach { mirrored ->
            val chat = chat()
            val ui = open(chat, rtl = mirrored)
            chat.receive(ChatMessage(english, from = "Mira", channel = team))
            chat.receive(ChatMessage(hebrew, from = "Mira", channel = team))
            ui.settle()

            val where = if (mirrored) "on a mirrored screen" else "on an English screen"
            assertEquals(
                listOf("Press ", shalom, " to start"),
                ui.texts(ChatTags.said(english)),
                "a line starting in English reads left to right $where",
            )
            assertEquals(
                listOf(".", "world", " $shalom"),
                ui.texts(ChatTags.said(hebrew)),
                "and one starting in Hebrew reads from the right $where with its full stop at the far left",
            )
        }
    }
}
