package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The developer console, driven the way a developer drives it: a key to bring it down, words typed
 * at the prompt, Tab to finish them, Enter to run them, and the arrows to go back through what was
 * typed before.
 *
 * Every test here composes a real screen with a game behind the console, so what the console takes
 * and what it lets through is tested rather than described.
 */
class DevConsoleTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    // What the commands did, which is how a test knows a line really ran.
    private var noclips = 0
    private var scale = 1f
    private val given = mutableListOf<String>()

    /** Keys the game behind the console saw. A console that is down should leave it none. */
    private var keysToGame = 0

    private var console: DevConsoleState? = null

    private val store = RememberingHistory()

    /** A store that keeps the lines in this test rather than on a disk the toolkit does not have. */
    private class RememberingHistory : ConsoleHistoryStore {
        var kept: List<String> = emptyList()
        override fun load(): List<String> = kept
        override fun save(lines: List<String>) {
            kept = lines
        }
    }

    private fun open(
        direction: LayoutDirection = LayoutDirection.Ltr,
        toggleKey: Key? = Key.Grave,
    ): UiTest {
        val test = uiTest(Size(800f, 600f)) {
            ProvideLayoutDirection(direction) { Screen(toggleKey) }
        }
        opened += test
        return test
    }

    @Composable
    private fun Screen(toggleKey: Key?) {
        val state = rememberDevConsole(history = store) {
            command("noclip", help = "Walk through walls") { noclips++ }
            command("timescale", arg<Float>("scale")) { scale = it }
            command(
                "give",
                arg<String>("item", suggest = { listOf("sword", "shield", "sapphire") }),
                arg<Int>("count", default = 1),
            ) { item, count -> given += "$item x$count" }
            command("giveall") { given += "everything" }
        }
        console = state

        val watching = KeyHandler { event ->
            if (event.type == KeyEventType.Down) keysToGame++
            false
        }
        Box(Modifier.fillMaxSize().onKeyEvent(watching)) {
            Button("play", onClick = {}, modifier = Modifier.testTag("play"))
            DevConsole(state, toggleKey = toggleKey)
        }
    }

    private fun UiTest.state(): DevConsoleState = checkNotNull(console)

    private fun UiTest.run(line: String) {
        type(line)
        key(Key.Enter)
    }

    /** What the frame really drew, for the tests about colour and about what is on screen. */
    private fun UiTest.drawn(): List<DrawCall> {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        return canvas.calls.toList()
    }

    // --- opening and closing ----------------------------------------------------------------------

    @Test
    fun `the toggle key brings the console down and puts it away again`() {
        val ui = open()
        ui.assertDoesNotExist(ConsoleTags.Panel)

        ui.key(Key.Grave)

        ui.assertExists(ConsoleTags.Panel)
        ui.assertFocused(ConsoleTags.Prompt)

        ui.key(Key.Grave)

        ui.assertDoesNotExist(ConsoleTags.Panel)
    }

    @Test
    fun `it opens while the game has focus on something else`() {
        val ui = open()
        ui.click("play")
        ui.assertFocused("play")

        ui.key(Key.Grave)

        ui.assertFocused(ConsoleTags.Prompt)
    }

    @Test
    fun `a pad chord brings it down and one of the buttons on its own does not`() {
        val ui = open()

        ui.pad(GamepadButton.Back)
        ui.assertDoesNotExist(ConsoleTags.Panel)

        ui.padDown(GamepadButton.Back)
        ui.padDown(GamepadButton.RightBumper)

        ui.assertExists(ConsoleTags.Panel)
    }

    @Test
    fun `escape closes it`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.key(Key.Escape)

        ui.assertDoesNotExist(ConsoleTags.Panel)
    }

    @Test
    fun `the character on the toggle key is not left sitting in the prompt`() {
        val ui = open()
        ui.key(Key.Grave)

        // A backend sends the key and then the character it makes, both before the next frame.
        ui.input.onKey(KeyEvent(Key.Grave, KeyEventType.Down))
        ui.input.onText(TextEvent("`"))
        ui.input.onKey(KeyEvent(Key.Grave, KeyEventType.Up))
        ui.settle()

        ui.assertDoesNotExist(ConsoleTags.Panel)
        assertEquals("", ui.state().input.text, "a backtick was left in the prompt")
    }

    @Test
    fun `a game that wants no toggle key gets none and opens it itself`() {
        val ui = open(toggleKey = null)

        ui.key(Key.Grave)
        ui.assertDoesNotExist(ConsoleTags.Panel)

        ui.state().open()
        ui.settle()

        ui.assertExists(ConsoleTags.Panel)
    }

    // --- running commands ---------------------------------------------------------------------------

    @Test
    fun `a typed command runs and the line is echoed above its answer`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.run("noclip")

        assertEquals(1, noclips)
        assertTrue(ui.texts(ConsoleTags.Log).contains("> noclip"), ui.texts(ConsoleTags.Log).toString())
        assertEquals("", ui.state().input.text, "the prompt should be empty again")
    }

    @Test
    fun `arguments reach the command as the types it asked for`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.run("timescale 0.25")
        ui.run("give sword 3")

        assertEquals(0.25f, scale)
        assertEquals(listOf("sword x3"), given)
    }

    @Test
    fun `a bad argument says which one and does not run the command`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.run("timescale fast")

        assertEquals(1f, scale)
        assertTrue(
            ui.texts(ConsoleTags.Log).any { it.contains("\"fast\" is not a number") },
            ui.texts(ConsoleTags.Log).toString(),
        )
    }

    @Test
    fun `a command that throws says so instead of taking the game with it`() {
        val ui = open()
        ui.state().define { command("boom") { throw IllegalStateException("no such level") } }
        ui.key(Key.Grave)

        ui.run("boom")

        assertTrue(
            ui.texts(ConsoleTags.Log).any { it.contains("boom failed: no such level") },
            ui.texts(ConsoleTags.Log).toString(),
        )
    }

    @Test
    fun `help lists the commands and clear empties the log`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.run("help")

        assertTrue(
            ui.texts(ConsoleTags.Log).any { it.contains("give <item> [count]") },
            ui.texts(ConsoleTags.Log).toString(),
        )

        ui.run("clear")

        assertTrue(ui.texts(ConsoleTags.Log).isEmpty(), ui.texts(ConsoleTags.Log).toString())
    }

    // --- completing -----------------------------------------------------------------------------

    @Test
    fun `tab finishes a command nobody else starts like`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.type("tim")
        ui.key(Key.Tab)

        assertEquals("timescale", ui.state().input.text)
    }

    @Test
    fun `tab fills in as far as the choices agree and then walks them`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.type("gi")
        ui.key(Key.Tab)
        assertEquals("give", ui.state().input.text, "as far as give and giveall agree")
        assertTrue(ui.texts(ConsoleTags.Suggestions).containsAll(listOf("give", "giveall")))

        ui.key(Key.Tab)
        assertEquals("give", ui.state().input.text)

        ui.key(Key.Tab)
        assertEquals("giveall", ui.state().input.text)

        ui.key(Key.Tab, Modifiers.Shift)
        assertEquals("give", ui.state().input.text, "shift walks it backwards")
    }

    @Test
    fun `tab completes an argument from what the command suggests`() {
        val ui = open()
        ui.key(Key.Grave)

        ui.type("give sw")
        ui.key(Key.Tab)

        assertEquals("give sword", ui.state().input.text)
    }

    @Test
    fun `escape puts the list away and the next one closes the console`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.type("gi")
        ui.key(Key.Tab)
        ui.assertExists(ConsoleTags.Suggestions)

        ui.key(Key.Escape)

        ui.assertDoesNotExist(ConsoleTags.Suggestions)
        ui.assertExists(ConsoleTags.Panel)

        ui.key(Key.Escape)

        ui.assertDoesNotExist(ConsoleTags.Panel)
    }

    @Test
    fun `a click on a suggestion takes it`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.type("gi")
        ui.key(Key.Tab)

        ui.click(ConsoleTags.suggestion(1))

        assertEquals("giveall", ui.state().input.text)
    }

    // --- the history ----------------------------------------------------------------------------

    @Test
    fun `up brings back what was typed before and down comes forward again`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.run("noclip")
        ui.run("giveall")

        ui.key(Key.Up)
        assertEquals("giveall", ui.state().input.text)

        ui.key(Key.Up)
        assertEquals("noclip", ui.state().input.text)

        ui.key(Key.Down)
        assertEquals("giveall", ui.state().input.text)

        ui.key(Key.Down)
        assertEquals("", ui.state().input.text, "back to the line being typed")
    }

    @Test
    fun `the history is kept where the game said to keep it`() {
        val first = open()
        first.key(Key.Grave)
        first.run("noclip")
        first.close()
        opened -= first

        assertEquals(listOf("noclip"), store.kept)

        // A second run of the game: a console of its own, reading the same store.
        console = null
        val second = open()
        second.key(Key.Grave)
        second.key(Key.Up)

        assertEquals("noclip", second.state().input.text)
    }

    // --- the log --------------------------------------------------------------------------------

    @Test
    fun `each level is drawn in its own colour`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.state().log("an ordinary line")
        ui.state().warn("something is odd")
        ui.state().error("something failed")
        ui.settle()

        val texts = ui.drawn().filterIsInstance<DrawCall.Text>()
        val colourOf = { line: String -> texts.first { it.text == line }.colour }

        assertEquals(Skin.Default.resolve("console.line.info").textColour, colourOf("an ordinary line"))
        assertEquals(Skin.Default.resolve("console.line.warn").textColour, colourOf("something is odd"))
        assertEquals(Skin.Default.resolve("console.line.error").textColour, colourOf("something failed"))
    }

    @Test
    fun `the log keeps the newest line in sight as it fills up`() {
        val ui = open()
        ui.key(Key.Grave)
        repeat(60) { ui.state().log("line $it") }
        ui.settle()

        val log = ui.node(ConsoleTags.Log).boundsInRoot
        val newest = ui.drawn().filterIsInstance<DrawCall.Text>().firstOrNull { it.text == "line 59" }

        assertTrue(newest != null, "the newest line was not drawn at all")
        assertTrue(newest.at.y in log.top..log.bottom, "the newest line is at ${newest.at.y}, outside $log")
    }

    @Test
    fun `the log holds only as many lines as it was told to`() {
        val console = DevConsoleState(maxLines = 5)

        repeat(20) { console.log("line $it") }

        assertEquals(5, console.lines.size)
        assertEquals("line 15", console.lines.first().text)
        assertEquals("line 19", console.lines.last().text)
    }

    @Test
    fun `a line with newlines in it becomes one line of the log each`() {
        val console = DevConsoleState()

        console.error("boom\nat level 3")

        assertEquals(listOf("boom", "at level 3"), console.lines.map { it.text })
        assertTrue(console.lines.all { it.level == ConsoleLevel.Error })
    }

    @Test
    fun `the filter leaves only the lines with that word in them`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.state().log("loading level 3")
        ui.state().log("spawned a drone")
        ui.settle()

        ui.click(ConsoleTags.Filter)
        ui.type("drone")

        val shown = ui.texts(ConsoleTags.Log)
        assertTrue(shown.contains("spawned a drone"), shown.toString())
        assertFalse(shown.contains("loading level 3"), shown.toString())
    }

    @Test
    fun `the panel covers the top of the screen with the prompt along its bottom`() {
        val ui = open()
        ui.key(Key.Grave)

        val panel = ui.node(ConsoleTags.Panel).boundsInRoot
        val log = ui.node(ConsoleTags.Log).boundsInRoot
        val prompt = ui.node(ConsoleTags.Prompt).boundsInRoot

        assertEquals(0f, panel.top, "it hangs from the top of the screen")
        assertEquals(800f, panel.width)
        assertEquals(240f, panel.height, "two fifths of a 600-high screen by default")
        assertTrue(log.bottom <= prompt.top, "the log should be above the prompt")
        assertTrue(prompt.bottom <= panel.bottom, "the prompt should be inside the panel")
    }

    @Test
    fun `a line in the log can be selected with the pointer and copied`() {
        val ui = open()
        ui.key(Key.Grave)
        ui.state().log("shader failed to compile")
        ui.settle()

        // Across the first line of the log, which is the only line there is.
        val log = ui.node(ConsoleTags.Log).boundsInRoot
        ui.press(Offset(log.left + 2f, log.top + 6f))
        ui.dragTo(Offset(log.left + 120f, log.top + 6f))
        ui.release()
        ui.key(Key.C, Modifiers.Primary)

        val copied = ui.backend.clipboard.read()
        assertTrue(copied != null && copied.isNotEmpty(), "nothing was copied")
        assertTrue("shader failed to compile".startsWith(copied), "copied \"$copied\"")
    }

    // --- what the game behind it sees -----------------------------------------------------------

    @Test
    fun `typing at the console does not also reach the game`() {
        val ui = open()
        ui.key(Key.Grave)
        keysToGame = 0

        ui.type("noclip")
        ui.key(Key.Enter)

        assertEquals(0, keysToGame, "the game was played while the console was being typed into")
        assertEquals(1, noclips)
    }

    @Test
    fun `a key reaches the game again once the console is away`() {
        val ui = open()
        ui.click("play")
        ui.key(Key.Grave)
        ui.key(Key.Grave)
        keysToGame = 0

        ui.key(Key.W)

        assertEquals(1, keysToGame)
    }

    // --- right to left ---------------------------------------------------------------------------

    @Test
    fun `it opens and runs a command in a right-to-left interface`() {
        val ui = open(direction = LayoutDirection.Rtl)

        ui.key(Key.Grave)
        ui.run("noclip")

        assertEquals(1, noclips)
        assertTrue(ui.texts(ConsoleTags.Log).contains("> noclip"))

        // The panel still covers the width of the screen, mirrored or not.
        val panel = ui.node(ConsoleTags.Panel).boundsInRoot
        assertEquals(0f, panel.left)
        assertEquals(800f, panel.right)
    }
}
