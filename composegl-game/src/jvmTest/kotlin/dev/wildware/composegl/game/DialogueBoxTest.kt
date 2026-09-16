package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.text.Locale
import dev.wildware.composegl.ui.text.ProvideLocale
import dev.wildware.composegl.ui.text.Strings
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dialogue box, driven the way a player drives one: real presses through a `PointerRouter`,
 * real keys through a `KeyRouter` and a `KeyNavigator`, real buttons through a `GamepadNavigator`.
 *
 * The issue's list, each of them here: one press that finishes the line and then moves on, a
 * portrait that swaps when the expression does, answers with focus and a timer and a reason for the
 * ones that cannot be taken, skip and auto and a log, and a name in a colour.
 */
class DialogueBoxTest {

    private val host = UiHost()
    private val bounds = Rect(0f, 0f, 600f, 400f)
    private val canvas = RecordingCanvas(bounds)
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val navigator = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(bounds)
        MeasurePass().run(host.root, Constraints.atMost(600f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(2)
    }

    // --- the conversation under test --------------------------------------------------------------

    private val first = DialogueLine("the relay is quiet", speaker = "VEGA", portrait = "calm")
    private val second = DialogueLine("we are not alone", speaker = "VEGA", portrait = "alarmed")

    private val script = listOf(first, second)

    private var at by mutableStateOf(0)
    private var auto by mutableStateOf(false)
    private var skipping by mutableStateOf(false)

    private val taken = mutableListOf<String>()
    private val timedOut = mutableListOf<Boolean>()
    private val history = mutableListOf<Boolean>()

    private val answers = listOf(
        DialogueChoice("say nothing", tag = "quiet"),
        DialogueChoice("open a channel", tag = "hail"),
        DialogueChoice("run the decoder", enabled = false, reason = "no decoder", tag = "decode"),
    )

    /**
     * The box as a game writes it: the conversation is the test's, and the widget is handed one
     * line at a time.
     */
    @Suppress("LongParameterList")
    private fun conversation(
        lines: List<DialogueLine> = script,
        choices: List<DialogueChoice> = emptyList(),
        timerMillis: Int = 0,
        controls: Boolean = false,
        rtl: Boolean = false,
        portraits: Boolean = false,
        strings: Strings? = null,
        locale: Locale = Locale.English,
    ) = show {
        val body: @Composable () -> Unit = {
            DialogueBox(
                line = lines.getOrNull(at),
                modifier = Modifier.fillMaxWidth(),
                choices = choices,
                onChoose = { taken += it.tag as String },
                onAdvance = { at++ },
                auto = auto,
                onAutoChange = if (controls) ({ auto = it }) else null,
                skipping = skipping,
                onSkippingChange = if (controls) ({ skipping = it }) else null,
                onHistory = if (controls) ({ history += true }) else null,
                onTimeout = { timedOut += true },
                timerMillis = timerMillis,
                charactersPerSecond = 20f,
                autoMillis = 400,
                skipMillis = 100,
                portrait = if (portraits) ({ Text("<${it.portrait}>") }) else null,
            )
        }
        ProvideLayoutDirection(if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            if (strings == null) body() else ProvideLocale(locale, strings) { body() }
        }
    }

    // --- driving it -------------------------------------------------------------------------------

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) navigator.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()
    }

    private fun hold(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) navigator.onKey(down)
        frame()
    }

    private fun release(key: Key) {
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) navigator.onKey(up)
        frame()
    }

    private fun press(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
        frame()
    }

    private fun click(x: Float, y: Float) {
        val where = Offset(x, y)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, where, timeMillis = wall / 1_000_000))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, where, timeMillis = wall / 1_000_000))
        frame()
    }

    // --- reading it -------------------------------------------------------------------------------

    private fun texts(): List<DrawCall.Text> = canvas.calls.filterIsInstance<DrawCall.Text>()

    private fun words(): List<String> = texts().map { it.text }

    /** How much of the line being said is on screen. */
    private fun typed(): String {
        val whole = script.getOrNull(at)?.text ?: return ""
        return words().lastOrNull { it.isNotEmpty() && whole.startsWith(it) } ?: ""
    }

    private fun drawn(text: String): DrawCall.Text? = texts().firstOrNull { it.text == text }

    private fun onScreen(text: String): Boolean = drawn(text) != null

    /** Where a skinned part of the box was drawn, found by the colour the skin gives it. */
    private fun rectOf(style: String): Rect? {
        val colour = (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour
        return canvas.calls.filterIsInstance<DrawCall.Rectangle>().lastOrNull { it.colour == colour }?.rect
    }

    // --- one press does two things ------------------------------------------------------------------

    @Test
    fun `a press shows the rest of the line and the next one moves on`() {
        conversation()

        frames(3, 50)
        val part = typed()
        assertTrue(part.isNotEmpty() && part.length < first.text.length, "it should still be arriving: \"$part\"")

        key(Key.Space)
        assertEquals(first.text, typed(), "a player who has read ahead gets the whole line")
        assertEquals(0, at, "and is still on it")

        key(Key.Space)
        assertEquals(1, at, "the second press should have moved on")
    }

    @Test
    fun `a click on the box does the same as the key`() {
        conversation()

        frames(3, 50)
        click(300f, 20f)
        assertEquals(first.text, typed())

        click(300f, 20f)
        assertEquals(1, at)
    }

    @Test
    fun `the pad advances with South`() {
        conversation()

        frames(3, 50)
        press(GamepadButton.South)
        assertEquals(first.text, typed())

        press(GamepadButton.South)
        assertEquals(1, at)
    }

    @Test
    fun `a line that says the same as the last one is typed out again`() {
        val words = "we are not alone"
        conversation(lines = listOf(DialogueLine(words, speaker = "VEGA"), DialogueLine(words, speaker = "VEGA")))

        frames(30, 50)
        assertTrue(onScreen(words), "the first one should be out by now")

        key(Key.Space)
        assertEquals(1, at)
        frames(1, 0)

        assertFalse(onScreen(words), "the second line should have started from nothing rather than already be there")

        frames(30, 50)
        assertTrue(onScreen(words), "and then typed itself out")
    }

    @Test
    fun `a conversation that is over draws nothing`() {
        conversation()
        at = 2
        frames(4, 50)

        assertTrue(words().isEmpty(), "it should be gone: ${words()}")
    }

    // --- what it costs when nobody is talking ---------------------------------------------------------

    @Test
    fun `a conversation that is over asks for no frames`() {
        conversation()
        at = 2
        frames(4, 50)

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it redrew a screen with no conversation on it")
        }
    }

    @Test
    fun `a finished line with an indicator of the game's own asks for no frames`() {
        // The arrow the box draws itself breathes, which is the point of it. A game that would
        // rather have a still glyph gets a box that asks for nothing at all once the line is out.
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                DialogueBox(
                    line = first,
                    modifier = Modifier.fillMaxWidth(),
                    onAdvance = { at++ },
                    charactersPerSecond = 20f,
                    indicator = { Text(">") },
                )
            }
        }
        frames(40, 50)
        assertEquals(first.text, typed(), "it should have finished typing by now")

        repeat(30) {
            wall += 16_000_000L
            assertFalse(host.frame(wall), "frame $it redrew a line that had already been read out")
        }
    }

    // --- the answers ---------------------------------------------------------------------------------

    @Test
    fun `answers wait until the line has been read out`() {
        conversation(choices = answers)

        frames(3, 50)
        assertFalse(onScreen("open a channel"), "a question should not be asked over the line asking it")

        key(Key.Space)
        frames(2, 50)
        assertTrue(onScreen("open a channel"), "and should be there once the line has finished")
    }

    @Test
    fun `advancing does nothing while there is a question up`() {
        conversation(choices = answers)

        frames(30, 50)
        key(Key.Space)

        assertEquals(0, at, "the way on is the answer")
        // Space belongs to the answer that has focus, and that answer is only pressed when the
        // player presses it: a box that advanced and answered at once would do both.
        assertEquals(listOf("quiet"), taken, "Space should have pressed the answer focus is on")
    }

    @Test
    fun `the number row answers the question`() {
        conversation(choices = answers)
        frames(30, 50)

        key(Key.Digit2)

        assertEquals(listOf("hail"), taken)
    }

    @Test
    fun `an answer that cannot be taken says why and does not answer`() {
        conversation(choices = answers)
        frames(30, 50)

        assertTrue(onScreen("no decoder"), "a greyed-out answer with no reason tells the player nothing")

        key(Key.Digit3)

        assertEquals(emptyList<String>(), taken)
    }

    @Test
    fun `the first answer that can be taken has focus so a pad can answer`() {
        conversation(choices = answers)
        frames(30, 50)

        press(GamepadButton.South)

        assertEquals(listOf("quiet"), taken, "South should have pressed the answer focus landed on")
    }

    @Test
    fun `the answers take focus even when the box draws its own buttons`() {
        // The box the showcase draws: Auto, Skip and Log up in the corner, and a question under the
        // line. Those buttons come first in the focus trap, so an answer that only asked to be the
        // screen's initial focus would never get it and South would toggle Auto instead.
        conversation(choices = answers, controls = true)
        frames(30, 50)

        press(GamepadButton.South)

        assertEquals(listOf("quiet"), taken, "South should have answered rather than pressed a button")
        assertFalse(auto, "and Auto should not have been touched")
    }

    @Test
    fun `the keyboard answers past the box's own buttons too`() {
        conversation(choices = answers, controls = true)
        frames(30, 50)

        key(Key.Space)

        assertEquals(listOf("quiet"), taken, "Space should have pressed the answer focus landed on")
        assertEquals(0, at, "and not moved the conversation on")
    }

    @Test
    fun `a question nobody can answer can still be pressed past`() {
        // Every answer greyed out is a wall, not a question: there is nothing to focus and nothing
        // to press, so a box that trapped focus on it would leave the player stuck in front of it.
        val walls = listOf(
            DialogueChoice("pay the toll", enabled = false, reason = "you have 40 credits", tag = "pay"),
            DialogueChoice("run the decoder", enabled = false, reason = "no decoder", tag = "decode"),
        )
        conversation(choices = walls)
        frames(30, 50)

        assertTrue(onScreen("you have 40 credits"), "the reasons are the whole point of showing them")

        key(Key.Space)
        assertEquals(1, at, "there was no way out of the conversation at all")
        assertEquals(emptyList<String>(), taken)

        frames(30, 50)
        press(GamepadButton.South)
        assertEquals(2, at, "and the pad should get past it as well")
    }

    @Test
    fun `the pad walks down the answers`() {
        conversation(choices = answers)
        frames(30, 50)

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.DpadDown))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.DpadDown))
        frame()
        press(GamepadButton.South)

        assertEquals(listOf("hail"), taken)
    }

    @Test
    fun `the timer runs out and the game is told`() {
        conversation(choices = answers, timerMillis = 2_000)
        frames(30, 50)
        assertTrue(onScreen("open a channel"), "the question should be up with the clock running")
        assertEquals(emptyList<Boolean>(), timedOut, "and the player still has time")

        frames(50, 50)

        assertEquals(listOf(true), timedOut, "silence is an answer and the game has to hear it")
    }

    // --- auto and skip --------------------------------------------------------------------------------

    @Test
    fun `auto moves on by itself once the line has been read`() {
        conversation()
        auto = true

        frames(40, 50)

        assertTrue(at >= 1, "it should have gone on by itself")
    }

    @Test
    fun `auto waits at a question`() {
        conversation(choices = answers)
        auto = true

        frames(60, 50)

        assertEquals(0, at, "answering for the player is the one thing it must never do")
        assertEquals(emptyList<String>(), taken)
    }

    @Test
    fun `skipping shows the line whole and moves on`() {
        conversation()
        skipping = true

        frames(2, 16)
        assertEquals(first.text, typed(), "skipping is not fast typing")

        // And keeps going: skip is held until the player has found where they were, or until the
        // conversation runs out, which here is the second line.
        frames(20, 50)
        assertEquals(2, at)
    }

    @Test
    fun `skip stops at a question`() {
        conversation(choices = answers, controls = true)
        skipping = true

        frames(10, 50)

        assertFalse(skipping, "the box should have put skip down rather than reading past the question")
        assertEquals(0, at)
    }

    @Test
    fun `skip turned on while a question is already up is put down as well`() {
        conversation(choices = answers, controls = true)
        frames(30, 50)
        assertTrue(onScreen("open a channel"), "the question should be up before skip goes on")

        skipping = true
        frames(10, 50)

        assertFalse(skipping, "skip left on at a question blows past the rest of it the moment it is answered")
        assertEquals(0, at)
        assertEquals(emptyList<String>(), taken)
    }

    @Test
    fun `holding control skips and letting go stops`() {
        conversation(controls = true)

        hold(Key.Control)
        assertTrue(skipping, "Ctrl is how a visual novel has been skipped for thirty years")

        release(Key.Control)
        assertFalse(skipping)
    }

    @Test
    fun `the auto button turns auto on and the log button asks for the log`() {
        conversation(controls = true)
        frames(30, 50)

        val button = checkNotNull(drawn("Auto")) { "no Auto button: ${words()}" }
        click(button.at.x + 4f, button.at.y + 4f)
        assertTrue(auto, "the button should have turned it on")

        val log = checkNotNull(drawn("Log")) { "no Log button: ${words()}" }
        click(log.at.x + 4f, log.at.y + 4f)
        assertEquals(listOf(true), history)
    }

    @Test
    fun `the pad opens the log with North`() {
        conversation(controls = true)
        frames(30, 50)

        press(GamepadButton.North)

        assertEquals(listOf(true), history)
    }

    @Test
    fun `nothing else is composed again while the timer runs`() {
        // Reading an animation recomposes whoever read it, so the timer's value is read inside the
        // bar's own composable — the way the portrait and the arrow read theirs. What this watches
        // is that a box with a clock running on it does not compose its slots again every frame.
        val passes = intArrayOf(0)
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                DialogueBox(
                    line = first,
                    modifier = Modifier.fillMaxWidth(),
                    choices = answers,
                    onChoose = { taken += it.tag as String },
                    timerMillis = 8_000,
                    charactersPerSecond = 20f,
                    portrait = { line ->
                        passes[0]++
                        Text("<${line.portrait}>")
                    },
                )
            }
        }
        frames(30, 50)
        val settled = passes[0]

        frames(20, 16)

        assertEquals(settled, passes[0], "the portrait was drawn again while only the timer was moving")
    }

    // --- the portrait ----------------------------------------------------------------------------------

    @Test
    fun `the portrait swaps when the expression changes`() {
        conversation(portraits = true)
        frames(30, 50)
        assertEquals(1f, drawn("<calm>")?.alpha, "it should be up at full")

        key(Key.Space)
        key(Key.Space)
        frames(2, 16)

        val leaving = checkNotNull(drawn("<calm>")) { "the old face should still be on its way out" }
        assertTrue(leaving.alpha < 1f, "it should be fading rather than cutting: ${leaving.alpha}")

        frames(20, 16)
        assertNull(drawn("<calm>"), "and be gone")
        assertEquals(1f, drawn("<alarmed>")?.alpha, "with the new one up at full")
    }

    @Test
    fun `the same expression twice does not blink`() {
        val same = listOf(
            DialogueLine("the relay is quiet", speaker = "VEGA", portrait = "calm"),
            DialogueLine("we are not alone", speaker = "VEGA", portrait = "calm"),
        )
        conversation(lines = same, portraits = true)
        frames(30, 50)

        key(Key.Space)
        key(Key.Space)

        repeat(10) {
            frame()
            assertEquals(1f, drawn("<calm>")?.alpha, "the same face should not have flickered")
        }
    }

    // --- styled runs and words of its own ------------------------------------------------------------

    @Test
    fun `a name in the line is typed in its own colour`() {
        val gold = Colour.rgb(0xF2C94C)
        val line = DialogueLine(
            "ask VEGA about it",
            speaker = "COMMS",
            runs = listOf(TextRun(TextRange(4, 8), colour = gold)),
        )
        conversation(lines = listOf(line))
        frames(40, 50)

        val name = texts().filter { it.text in listOf("V", "E", "G", "A") && it.colour == gold }
        assertEquals(4, name.size, "the name should be drawn in its own colour: ${texts().map { it.text to it.colour }}")
        assertTrue(
            texts().any { it.text == "a" && it.colour != gold },
            "and the rest of the line should not be",
        )
    }

    @Test
    fun `its own words come from the game's strings`() {
        val strings = Strings(
            mapOf(
                Locale.English to mapOf("dialogue.auto" to "Auto"),
                Locale("fr") to mapOf("dialogue.auto" to "Automatique", "dialogue.skip" to "Passer"),
            ),
        )
        conversation(controls = true, strings = strings, locale = Locale("fr"))
        frames(4, 50)

        assertTrue(onScreen("Automatique"), "the translated word should be on the button: ${words()}")
        assertTrue(onScreen("Passer"))
        // Nobody has translated the log button, and a button saying `dialogue.log` is worse than an
        // English one.
        assertTrue(onScreen("Log"), "an untranslated word should fall back to English: ${words()}")
    }

    @Test
    fun `the box is mirrored in a right-to-left language`() {
        conversation(rtl = true, portraits = true)
        frames(40, 50)

        val face = checkNotNull(drawn("<calm>"))
        val line = checkNotNull(texts().firstOrNull { first.text.startsWith(it.text) && it.text.isNotEmpty() })

        assertTrue(face.at.x > line.at.x, "the portrait should be on the right of the words in Arabic")
        assertNotNull(drawn("VEGA"))
    }

    @Test
    fun `the answers and the timer are mirrored too`() {
        conversation(choices = answers, timerMillis = 4_000, rtl = true)
        frames(30, 50)
        frames(6, 50)

        // The answer and the reason under it are two lines of the same column, so in Arabic they
        // both start on the right: the shorter of the two begins further right than the longer one,
        // where left to right would have them both begin at the same x.
        val answer = checkNotNull(drawn("run the decoder")) { "the answers should be up: ${words()}" }
        val reason = checkNotNull(drawn("no decoder"))
        assertTrue(
            reason.at.x > answer.at.x,
            "the answers should be right-aligned in Arabic: ${reason.at.x} against ${answer.at.x}",
        )

        val track = checkNotNull(rectOf("dialogue.timer.track")) { "no timer on screen" }
        val fill = checkNotNull(rectOf("dialogue.timer.fill"))
        assertTrue(fill.width < track.width, "some of the timer should have gone: ${fill.width} of ${track.width}")
        assertEquals(track.right, fill.right, 0.5f, "in Arabic the bar should empty towards the left")
    }

    // --- the log ------------------------------------------------------------------------------------

    @Test
    fun `what was said and what was answered go into the log`() {
        lateinit var log: DialogueLog
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                log = rememberDialogueLog()
                DialogueBox(
                    line = script.getOrNull(at),
                    modifier = Modifier.fillMaxWidth(),
                    choices = if (at == 0) answers else emptyList(),
                    // Nothing writes the answer down here: the box does it, against the line that
                    // asked for it, which is what a game reading the docs is promised.
                    onChoose = { at++ },
                    onAdvance = { at++ },
                    log = log,
                    charactersPerSecond = 20f,
                )
            }
        }
        frames(30, 50)

        key(Key.Digit1)
        frames(30, 50)

        assertEquals(listOf(first, second), log.entries.map { it.line })
        assertEquals("say nothing", log.entries.first().answer)
        assertNull(log.entries.last().answer)
    }

    @Test
    fun `the history draws what was said and opens at the newest`() {
        val log = DialogueLog()
        repeat(30) { log.say(DialogueLine("line $it", speaker = "VEGA")) }
        log.answer("the last word")

        show { DialogueHistory(log, Modifier.fillMaxWidth()) }
        frames(3)

        assertTrue(onScreen("line 29"), "a log opened at the top is a log nobody reads: ${words()}")
        assertTrue(onScreen("the last word"), "the answer belongs in the log with the line that asked")
        assertFalse(onScreen("line 0"), "and the oldest line should be a long way up")
    }
}
