package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pad keyboard, used the way a player on a couch uses it: d-pad onto a field, walk the keys,
 * press South, and read what the field says.
 *
 * Every test composes a real screen in [uiTest] and sends pad, mouse and keyboard events through
 * the same routers a game wires. Nothing is typed by calling the keyboard, except where the point
 * is the shortcut buttons a game routes to it itself.
 */
class GamepadKeyboardTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val screen = Size(800f, 600f)

    /** A "START" button with focus, a name field below it, and a screen-level back that counts. */
    private fun nameScreen(
        keyboard: GamepadKeyboard = GamepadKeyboard(),
        maxLength: Int = 0,
        multiline: Boolean = false,
        placeholder: String? = null,
        provided: Boolean = true,
        onBack: () -> Unit = {},
        extra: @Composable (setName: (String) -> Unit) -> Unit = {},
    ): Pair<UiTest, () -> String> {
        var latest = ""
        val ui = uiTest(screen, onBack = onBack) {
            val content: @Composable () -> Unit = {
                var name by remember { mutableStateOf("") }
                latest = name
                Column {
                    Button("START", onClick = {}, initialFocus = true, modifier = Modifier.testTag("start"))
                    TextField(
                        name,
                        onValueChange = { name = it },
                        modifier = Modifier.width(300f).testTag("name"),
                        maxLength = maxLength,
                        multiline = multiline,
                        placeholder = placeholder,
                    )
                    extra { name = it }
                }
            }
            if (provided) ProvideGamepadKeyboard(keyboard, content = content) else content()
        }
        opened += ui
        return ui to { latest }
    }

    /**
     * Walks focus onto [tag] with the d-pad alone, the way a player does: look where it is, press
     * towards it. Fails rather than looping if the pad cannot get there.
     */
    private fun UiTest.walkTo(tag: String) {
        val goal = node(tag)
        repeat(40) {
            val at = focus.focused ?: error("nothing focused while walking to #$tag")
            if (at === goal) return
            val from = at.boundsInRoot
            val to = goal.boundsInRoot
            val dy = to.centre.y - from.centre.y
            val dx = to.centre.x - from.centre.x
            val button = when {
                abs(dy) > from.height / 2f -> if (dy > 0) GamepadButton.DpadDown else GamepadButton.DpadUp
                dx > 0 -> GamepadButton.DpadRight
                else -> GamepadButton.DpadLeft
            }
            pad(button)
        }
        assertFocused(tag)
    }

    /** Walks to a key and presses it. */
    private fun UiTest.hit(tag: String) {
        walkTo(tag)
        pad(GamepadButton.South)
    }

    private fun UiTest.hitKey(character: String) = hit("gamepad-keyboard.key.$character")

    // --- opening -------------------------------------------------------------------------------

    @Test
    fun `moving onto a field with the d-pad opens the keyboard on its first key`() {
        val (ui) = nameScreen()
        ui.assertDoesNotExist("gamepad-keyboard")

        ui.pad(GamepadButton.DpadDown)

        ui.assertExists("gamepad-keyboard")
        ui.assertFocused("gamepad-keyboard.key.1")
        ui.assertText("gamepad-keyboard.key.q", "q")
        ui.assertText("gamepad-keyboard.layout", "#+=")
    }

    @Test
    fun `the keyboard sits along the bottom of the screen in the middle`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        val panel = ui.node("gamepad-keyboard").boundsInRoot
        assertEquals(600f, panel.width, "the default width")
        assertEquals(screen.height, panel.bottom, "flush with the bottom of the screen")
        assertEquals(screen.width / 2f, panel.centre.x, "centred")
        // The screen underneath is where it was: the keyboard is laid over it, not beside it.
        assertEquals(0f, ui.node("start").boundsInRoot.top)
    }

    @Test
    fun `a mouse click on the field does not open it`() {
        val (ui) = nameScreen()

        ui.click("name")

        ui.assertFocused("name")
        ui.assertDoesNotExist("gamepad-keyboard")
    }

    @Test
    fun `tabbing onto the field from a real keyboard does not open it`() {
        val (ui) = nameScreen()

        ui.key(Key.Tab)

        ui.assertFocused("name")
        ui.assertDoesNotExist("gamepad-keyboard")
        ui.type("Ada")
        ui.assertText("name", "Ada")
    }

    @Test
    fun `without the provider a pad player gets no keyboard and the field is unchanged`() {
        val (ui) = nameScreen(provided = false)

        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        ui.assertFocused("name")
        ui.assertDoesNotExist("gamepad-keyboard")
    }

    @Test
    fun `with open on focus off only South on the field opens it`() {
        val (ui) = nameScreen(GamepadKeyboard(openOnFocus = false))

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("name")
        ui.assertDoesNotExist("gamepad-keyboard")

        ui.pad(GamepadButton.South)
        ui.assertExists("gamepad-keyboard")
        ui.assertFocused("gamepad-keyboard.key.1")
    }

    // --- typing --------------------------------------------------------------------------------

    @Test
    fun `walking the keys and pressing South types into the field`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        ui.hitKey("h")
        ui.hitKey("i")
        ui.hitKey("2")

        assertEquals("hi2", name())
        ui.assertText("gamepad-keyboard.line", "hi2")
        ui.assertText("name", "hi2")
    }

    @Test
    fun `shift makes the next letter a capital and then lets go`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        ui.hit("gamepad-keyboard.shift")
        ui.assertText("gamepad-keyboard.key.a", "A")

        ui.hitKey("a")
        ui.hitKey("d")
        ui.hitKey("a")

        assertEquals("Ada", name())
        ui.assertText("gamepad-keyboard.key.a", "a")
    }

    @Test
    fun `the page key goes through symbols and numbers and back to letters`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        ui.hit("gamepad-keyboard.layout")
        ui.assertFocused("gamepad-keyboard.layout")
        ui.assertExists("gamepad-keyboard.key.#")
        ui.assertDoesNotExist("gamepad-keyboard.key.q")
        ui.assertText("gamepad-keyboard.layout", "123")
        ui.hitKey("#")

        ui.hit("gamepad-keyboard.layout")
        ui.assertExists("gamepad-keyboard.key.7")
        ui.assertDoesNotExist("gamepad-keyboard.key.#")
        ui.hitKey("7")

        ui.hit("gamepad-keyboard.layout")
        ui.assertExists("gamepad-keyboard.key.q")
        ui.assertText("gamepad-keyboard.layout", "#+=")

        assertEquals("#7", name())
    }

    @Test
    fun `shift cannot be reached on a page with no capitals`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hit("gamepad-keyboard.layout")

        ui.pad(GamepadButton.DpadRight)

        ui.assertFocused("gamepad-keyboard.space")
    }

    @Test
    fun `delete and the caret keys edit in the middle of the text`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")
        ui.hitKey("c")

        ui.hit("gamepad-keyboard.left")
        ui.assertText("gamepad-keyboard.line", "a\nc")
        ui.hitKey("b")
        assertEquals("abc", name())

        ui.hit("gamepad-keyboard.right")
        ui.hit("gamepad-keyboard.delete")
        assertEquals("ab", name())
        ui.assertText("gamepad-keyboard.line", "ab")
    }

    @Test
    fun `space types a space`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")
        ui.hit("gamepad-keyboard.space")
        ui.hitKey("b")
        assertEquals("a b", name())
    }

    @Test
    fun `a full field refuses keys from the pad the same as from a keyboard`() {
        val (ui, name) = nameScreen(maxLength = 2)
        ui.pad(GamepadButton.DpadDown)

        ui.hitKey("a")
        ui.hitKey("b")
        ui.hitKey("c")

        assertEquals("ab", name())
        ui.assertText("gamepad-keyboard.line", "ab")
    }

    @Test
    fun `the line shows the placeholder until something is typed`() {
        val (ui) = nameScreen(placeholder = "Name your save")
        ui.pad(GamepadButton.DpadDown)

        ui.assertText("gamepad-keyboard.line", "Name your save")
        ui.hitKey("x")
        ui.assertText("gamepad-keyboard.line", "x")
    }

    @Test
    fun `only a multi-line field gets an enter key and it makes a new line`() {
        val (single) = nameScreen()
        single.pad(GamepadButton.DpadDown)
        single.assertDoesNotExist("gamepad-keyboard.enter")

        val (ui, name) = nameScreen(multiline = true)
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")
        ui.hit("gamepad-keyboard.enter")
        ui.hitKey("b")

        assertEquals("a\nb", name())
    }

    @Test
    fun `the line follows the field when the game changes the text`() {
        var set: (String) -> Unit = {}
        val (ui) = nameScreen(extra = { setName -> set = setName })
        ui.pad(GamepadButton.DpadDown)

        set("Rex")
        ui.settle()

        ui.assertText("gamepad-keyboard.line", "Rex")
    }

    // --- focus and closing ---------------------------------------------------------------------

    @Test
    fun `focus cannot walk off the keyboard into the screen behind`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        repeat(6) { ui.pad(GamepadButton.DpadUp) }
        ui.assertFocused("gamepad-keyboard.key.1")
        repeat(12) { ui.pad(GamepadButton.DpadLeft) }
        ui.assertFocused("gamepad-keyboard.key.1")
    }

    @Test
    fun `done closes it and puts focus back on the field without opening it again`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("o")
        ui.hitKey("k")

        ui.hit("gamepad-keyboard.done")

        ui.assertDoesNotExist("gamepad-keyboard")
        ui.assertFocused("name")
        ui.advanceBy(500)
        ui.assertDoesNotExist("gamepad-keyboard")
        assertEquals("ok", name())
    }

    @Test
    fun `South on the field opens it again after it was closed`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hit("gamepad-keyboard.done")
        ui.assertFocused("name")

        ui.pad(GamepadButton.South)

        ui.assertExists("gamepad-keyboard")
        ui.assertFocused("gamepad-keyboard.key.1")
    }

    @Test
    fun `after closing the pad can move on past the field`() {
        val (ui) = nameScreen(extra = { Button("NEXT", onClick = {}, modifier = Modifier.testTag("next")) })
        ui.pad(GamepadButton.DpadDown)
        ui.hit("gamepad-keyboard.done")

        ui.pad(GamepadButton.DpadDown)

        ui.assertFocused("next")
        ui.assertDoesNotExist("gamepad-keyboard")
    }

    @Test
    fun `B closes the keyboard before it reaches the screen`() {
        var backs = 0
        val (ui) = nameScreen(onBack = { backs++ })
        ui.pad(GamepadButton.DpadDown)

        ui.pad(GamepadButton.East)

        ui.assertDoesNotExist("gamepad-keyboard")
        ui.assertFocused("name")
        assertEquals(0, backs, "the screen's own back was not asked")

        ui.pad(GamepadButton.East)
        assertEquals(1, backs, "with the keyboard gone, B is the screen's again")
    }

    @Test
    fun `escape on a real keyboard closes it too`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)

        ui.key(Key.Escape)

        ui.assertDoesNotExist("gamepad-keyboard")
    }

    @Test
    fun `picking up the mouse closes it`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.assertExists("gamepad-keyboard")

        ui.moveTo(Offset(700f, 20f))

        ui.assertDoesNotExist("gamepad-keyboard")
        assertEquals(InputSource.Mouse, ui.source.current)
    }

    @Test
    fun `typing on a real keyboard while it is open closes it and the letters still arrive`() {
        val (ui, name) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")

        ui.type("da")

        ui.assertDoesNotExist("gamepad-keyboard")
        ui.assertFocused("name")
        assertEquals("ada", name())
    }

    @Test
    fun `a field that leaves the screen takes the keyboard with it`() {
        var showField by mutableStateOf(true)
        val keyboard = GamepadKeyboard()
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard(keyboard) {
                Column {
                    Button("START", onClick = {}, initialFocus = true, modifier = Modifier.testTag("start"))
                    if (showField) TextField("", onValueChange = {}, modifier = Modifier.testTag("name"))
                }
            }
        }.also { opened += it }
        ui.pad(GamepadButton.DpadDown)
        ui.assertExists("gamepad-keyboard")

        showField = false
        ui.settle()

        ui.assertDoesNotExist("gamepad-keyboard")
        assertFalse(keyboard.isOpen)
        ui.assertFocused("start")
    }

    @Test
    fun `a field that is disabled while it is open closes it`() {
        var enabled by mutableStateOf(true)
        val keyboard = GamepadKeyboard()
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard(keyboard) {
                Column {
                    Button("START", onClick = {}, initialFocus = true, modifier = Modifier.testTag("start"))
                    TextField("", onValueChange = {}, enabled = enabled, modifier = Modifier.testTag("name"))
                }
            }
        }.also { opened += it }
        ui.pad(GamepadButton.DpadDown)
        ui.assertExists("gamepad-keyboard")

        enabled = false
        ui.settle()

        ui.assertDoesNotExist("gamepad-keyboard")
        assertFalse(keyboard.isOpen)
    }

    @Test
    fun `done on one field and the pad onto the next opens it for the next`() {
        var first = ""
        var second = ""
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard {
                var a by remember { mutableStateOf("") }
                var b by remember { mutableStateOf("") }
                first = a
                second = b
                Column {
                    Button("START", onClick = {}, initialFocus = true, modifier = Modifier.testTag("start"))
                    TextField(a, onValueChange = { a = it }, modifier = Modifier.width(300f).testTag("first"))
                    TextField(b, onValueChange = { b = it }, modifier = Modifier.width(300f).testTag("second"))
                }
            }
        }.also { opened += it }
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")
        ui.hit("gamepad-keyboard.done")
        ui.assertFocused("first")

        ui.pad(GamepadButton.DpadDown)

        ui.assertExists("gamepad-keyboard")
        ui.assertText("gamepad-keyboard.line", "")
        ui.hitKey("b")
        assertEquals("a", first)
        assertEquals("b", second)
    }

    @Test
    fun `an inner provider keeps its fields to itself`() {
        val outer = GamepadKeyboard()
        val inner = GamepadKeyboard()
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard(outer) {
                ProvideGamepadKeyboard(inner) {
                    Column {
                        Button("START", onClick = {}, initialFocus = true)
                        TextField("", onValueChange = {}, modifier = Modifier.testTag("name"))
                    }
                }
            }
        }.also { opened += it }

        ui.pad(GamepadButton.DpadDown)

        assertTrue(inner.isOpen)
        assertFalse(outer.isOpen)
        assertEquals(1, ui.root.findAll("gamepad-keyboard").size, "one keyboard on the screen")
    }

    @Test
    fun `an open keyboard on a still screen draws nothing new`() {
        val (ui) = nameScreen()
        ui.pad(GamepadButton.DpadDown)
        ui.hitKey("a")

        // Every key's handler and the line are remembered, so a frame where nothing happened
        // compares equal all the way down and asks for nothing.
        repeat(5) { assertFalse(ui.render(), "frame $it: nothing changed, so nothing is redrawn") }
    }

    @Test
    fun `a keyboard given no width still opens and closes`() {
        val keyboard = GamepadKeyboard()
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard(keyboard, modifier = Modifier.width(0f)) {
                Column {
                    Button("START", onClick = {}, initialFocus = true)
                    TextField("", onValueChange = {}, modifier = Modifier.testTag("name"))
                }
            }
        }.also { opened += it }

        ui.pad(GamepadButton.DpadDown)
        assertTrue(keyboard.isOpen)
        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.East)
        assertFalse(keyboard.isOpen)
        ui.assertFocused("name")
    }

    // --- a desk player inside the provider -----------------------------------------------------

    @Test
    fun `inside the provider a mouse click still puts the caret where it was clicked`() {
        var name = "hello"
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard {
                var text by remember { mutableStateOf("hello") }
                name = text
                TextField(text, onValueChange = { text = it }, modifier = Modifier.width(300f).testTag("name"))
            }
        }.also { opened += it }
        val field = ui.node("name").boundsInRoot

        // Just inside the left edge: before the first letter, where the caret was not.
        ui.click(Offset(field.left + 1f, field.centre.y))
        ui.type("X")

        assertEquals("Xhello", name)
        ui.assertDoesNotExist("gamepad-keyboard")
    }

    @Test
    fun `inside the provider enter on a real keyboard still submits`() {
        var submitted = 0
        val ui = uiTest(screen) {
            ProvideGamepadKeyboard {
                var text by remember { mutableStateOf("") }
                TextField(text, onValueChange = { text = it }, onSubmit = { submitted++ }, initialFocus = true)
            }
        }.also { opened += it }

        ui.type("go")
        ui.key(Key.Enter)

        assertEquals(1, submitted)
        ui.assertDoesNotExist("gamepad-keyboard")
    }

    // --- the pad's shortcuts -------------------------------------------------------------------

    @Test
    fun `the pad shortcuts delete add a space move the caret and finish`() {
        val keyboard = GamepadKeyboard()
        val (ui, name) = nameScreen(keyboard)
        fun shortcut(button: GamepadButton): Boolean =
            keyboard.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button)).also { ui.settle() }

        assertFalse(shortcut(GamepadButton.West), "closed, it takes nothing")
        ui.pad(GamepadButton.DpadDown)

        ui.hitKey("a")
        ui.hitKey("b")
        assertTrue(shortcut(GamepadButton.West))
        assertEquals("a", name())

        shortcut(GamepadButton.North)
        ui.hitKey("c")
        assertEquals("a c", name())

        shortcut(GamepadButton.LeftBumper)
        shortcut(GamepadButton.LeftBumper)
        shortcut(GamepadButton.LeftStick)
        ui.hitKey("x")
        assertEquals("aX c", name())
        shortcut(GamepadButton.RightBumper)
        shortcut(GamepadButton.RightBumper)
        ui.hitKey("y")
        assertEquals("aX cy", name())

        assertFalse(shortcut(GamepadButton.South), "South is the navigator's, not a shortcut")
        assertTrue(shortcut(GamepadButton.Start))
        ui.assertDoesNotExist("gamepad-keyboard")
        ui.assertFocused("name")
    }
}
