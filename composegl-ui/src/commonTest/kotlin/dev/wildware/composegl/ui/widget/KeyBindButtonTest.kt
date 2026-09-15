package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PromptStyle
import dev.wildware.composegl.ui.input.Prompts
import dev.wildware.composegl.ui.input.bind
import dev.wildware.composegl.ui.input.clashesWith
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A controls screen, driven by a keyboard, a mouse and a pad, and judged by what the buttons say.
 *
 * Every test composes the screen a game would, presses what a player would, and reads the label
 * off the drawing — so "bound" means the player can see it is bound.
 */
class KeyBindButtonTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val jump = Action("jump")
    private val crouch = Action("crouch")

    /** Every binding the screen was handed, in order. */
    private val bound = mutableListOf<InputBinding>()
    private var backs = 0

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), onBack = { backs += 1 }, content = content).also { opened += it }

    /**
     * Two actions in a column, each with its binding, the way a settings screen lists them.
     *
     * Fixed width, as on a real controls screen: a button that shrinks from PRESS A KEY to LMB moves
     * its edge out from under the mouse, and the release would miss it for a reason that is not the
     * widget's.
     */
    @Composable
    private fun Controls(
        initial: Map<Action, InputBinding?> = mapOf(
            jump to InputBinding.Keyboard(Key.Space),
            crouch to InputBinding.Keyboard(Key.C),
        ),
        accepts: (InputBinding) -> Boolean = { true },
        prompts: Prompts = Prompts(),
    ) {
        var binds by remember { mutableStateOf(initial) }
        ProvidePrompts(prompts) {
            Column {
                listOf(jump, crouch).forEach { action ->
                    KeyBindButton(
                        binding = binds[action],
                        onBind = { input ->
                            bound += input
                            binds = binds + (action to input)
                        },
                        accepts = accepts,
                        initialFocus = action == jump,
                        modifier = Modifier.width(160f).testTag(action.name),
                    )
                }
            }
        }
    }

    @Test
    fun `it shows what the action is bound to`() {
        val ui = open { Controls(initial = mapOf(jump to InputBinding.Keyboard(Key.Space), crouch to null)) }

        ui.assertText("jump", "SPACE")
        ui.assertText("crouch", "—")
    }

    @Test
    fun `a click starts it listening and the next key is the new binding`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.assertText("jump", "PRESS A KEY")

        ui.key(Key.J)

        ui.assertText("jump", "J")
        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.J)), bound)
        ui.assertText("crouch", "C")
    }

    @Test
    fun `enter starts it and binding enter does not start it again`() {
        val ui = open { Controls() }

        ui.key(Key.Enter)
        ui.assertText("jump", "PRESS A KEY")
        assertTrue(bound.isEmpty(), "the Enter that started it is not an answer")

        ui.key(Key.Enter)

        ui.assertText("jump", "ENTER")
        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.Enter)), bound)
    }

    @Test
    fun `escape cancels and the old binding stays`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.key(Key.Escape)

        ui.assertText("jump", "SPACE")
        assertTrue(bound.isEmpty())
        assertEquals(0, backs, "Escape was the button's, not the screen's")
    }

    @Test
    fun `an arrow key is bound rather than moving focus`() {
        val ui = open { Controls() }

        ui.key(Key.Enter)
        ui.key(Key.Down)

        ui.assertText("jump", "DOWN")
        ui.assertFocused("jump")

        // And once bound the arrows navigate again.
        ui.key(Key.Down)
        ui.assertFocused("crouch")
    }

    @Test
    fun `a held key repeating does not bind twice`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.keyDown(Key.K)
        ui.keyDown(Key.K, repeat = true)
        ui.keyDown(Key.K, repeat = true)
        ui.keyUp(Key.K)

        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.K)), bound)
        ui.assertText("jump", "K")
    }

    @Test
    fun `a key already held when it starts listening is not bound by its repeats`() {
        val state = KeyBindState()
        val ui = open {
            KeyBindButton(
                binding = InputBinding.Keyboard(Key.Space),
                onBind = { bound += it },
                initialFocus = true,
                state = state,
                modifier = Modifier.testTag("bind"),
            )
        }

        // W is held for walking, and the game opens the controls screen already listening.
        ui.keyDown(Key.W)
        state.listen()
        ui.settle()
        ui.keyDown(Key.W, repeat = true)
        ui.keyDown(Key.W, repeat = true)

        ui.assertText("bind", "PRESS A KEY")
        assertTrue(bound.isEmpty(), "a repeat is a key still held from before, not an answer")
    }

    @Test
    fun `the answering key is not heard by the screen around it`() {
        val heard = mutableListOf<KeyEvent>()
        val ui = open {
            Box(Modifier.onKeyEvent { heard += it; false }) { Controls() }
        }

        ui.click("jump")
        ui.keyDown(Key.J)
        ui.keyDown(Key.J, repeat = true)
        ui.keyUp(Key.J)

        ui.assertText("jump", "J")
        assertTrue(heard.none { it.key == Key.J }, "the press, its repeat and its release all stopped at the button: $heard")
    }

    @Test
    fun `south starts it on a pad and east is bound instead of going back`() {
        val ui = open { Controls() }

        ui.pad(GamepadButton.South)
        ui.assertText("jump", "PRESS A KEY")

        ui.pad(GamepadButton.East)

        ui.assertText("jump", "B")
        assertEquals(listOf<InputBinding>(InputBinding.Gamepad(GamepadButton.East)), bound)
        assertEquals(0, backs)
    }

    @Test
    fun `binding south does not press the button again`() {
        val ui = open { Controls() }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.South)

        ui.assertText("jump", "A")
        assertEquals(1, bound.size)
    }

    @Test
    fun `the d-pad is bound and afterwards navigates normally`() {
        val ui = open { Controls() }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)

        ui.assertText("jump", "D-DOWN")
        ui.assertFocused("jump")

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("crouch")
    }

    @Test
    fun `back on the pad cancels`() {
        val ui = open { Controls() }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.Back)

        ui.assertText("jump", "SPACE")
        assertTrue(bound.isEmpty())
        assertEquals(0, backs)
    }

    @Test
    fun `a pad binding is drawn the way the pad in use prints it`() {
        val ui = open { Controls(prompts = Prompts(PromptStyle.PlayStation)) }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.South)

        ui.assertText("jump", "✕")
    }

    @Test
    fun `a right click while listening binds the right mouse button`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.click("jump", PointerButton.Secondary)

        ui.assertText("jump", "RMB")
        assertEquals(listOf<InputBinding>(InputBinding.Mouse(PointerButton.Secondary)), bound)
    }

    @Test
    fun `binding the left mouse button does not start it listening again`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.click("jump")

        ui.assertText("jump", "LMB")

        // The swallowed click is used up: the next click listens as normal.
        ui.click("jump")
        ui.assertText("jump", "PRESS A KEY")
    }

    @Test
    fun `focus moving to another button gives up`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.click("crouch")

        ui.assertText("jump", "SPACE")
        ui.assertText("crouch", "PRESS A KEY")
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `a press the game refuses is ignored and it keeps listening`() {
        val ui = open { Controls(accepts = { it !is InputBinding.Mouse }) }

        ui.click("jump")
        ui.click("jump", PointerButton.Secondary)

        ui.assertText("jump", "PRESS A KEY")

        ui.key(Key.X)
        ui.assertText("jump", "X")
        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.X)), bound)
    }

    @Test
    fun `a clash is handed to the screen to decide`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.key(Key.C)

        // Both show C: the button reported it and the screen chose to allow it.
        ui.assertText("jump", "C")
        ui.assertText("crouch", "C")
        val binds = mapOf(jump to bound.last(), crouch to InputBinding.Keyboard(Key.C))
        assertEquals(listOf(crouch), binds.clashesWith(InputBinding.Keyboard(Key.C), ignoring = jump))
    }

    @Test
    fun `the new binding reaches every prompt glyph for the action`() {
        val prompts = Prompts()
        val ui = open {
            ProvidePrompts(prompts) {
                Row {
                    KeyBindButton(
                        binding = null,
                        onBind = { prompts.bind(jump, it) },
                        initialFocus = true,
                        modifier = Modifier.testTag("bind"),
                    )
                    PromptGlyph(jump, Modifier.testTag("glyph"), promptStyle = PromptStyle.Keyboard)
                }
            }
        }

        ui.assertText("glyph", "—")

        ui.key(Key.Enter)
        ui.key(Key.G)

        ui.assertText("glyph", "G")
    }

    @Test
    fun `listening before focus has arrived is not given up`() {
        val state = KeyBindState()
        val ui = open {
            Column {
                Button("BACK", onClick = {}, initialFocus = true, modifier = Modifier.testTag("back"))
                KeyBindButton(
                    binding = InputBinding.Keyboard(Key.Space),
                    onBind = { bound += it },
                    state = state,
                    modifier = Modifier.testTag("bind"),
                )
            }
        }

        state.listen()
        ui.settle()
        ui.assertText("bind", "PRESS A KEY")

        // Focus arriving and then leaving is walking away, and that does give up.
        ui.key(Key.Down)
        ui.assertFocused("bind")
        ui.assertText("bind", "PRESS A KEY")
        ui.click("back")
        ui.assertText("bind", "SPACE")
    }

    @Test
    fun `tab is bound rather than moving focus`() {
        val ui = open { Controls() }

        ui.key(Key.Enter)
        ui.key(Key.Tab)

        ui.assertText("jump", "TAB")
        ui.assertFocused("jump")
    }

    @Test
    fun `a key whose release went elsewhere still binds next time`() {
        val ui = open { Controls() }

        // J answers, and the mouse moves focus away before J comes up.
        ui.click("jump")
        ui.keyDown(Key.J)
        ui.click("crouch")
        ui.keyUp(Key.J)
        ui.key(Key.Escape)

        ui.click("jump")
        ui.key(Key.J)

        ui.assertText("jump", "J")
        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.J), InputBinding.Keyboard(Key.J)), bound)
    }

    @Test
    fun `a pad button whose release went elsewhere still binds next time`() {
        val ui = open { Controls() }

        ui.pad(GamepadButton.South)
        ui.padDown(GamepadButton.North)
        ui.click("crouch")
        ui.padUp(GamepadButton.North)
        ui.pad(GamepadButton.Back)

        ui.click("jump")
        ui.pad(GamepadButton.North)

        ui.assertText("jump", "Y")
        assertEquals(2, bound.size)
    }

    @Test
    fun `turning it off while it listens gives up`() {
        var enabled by mutableStateOf(true)
        val state = KeyBindState()
        val ui = open {
            Column {
                KeyBindButton(
                    binding = InputBinding.Keyboard(Key.Space),
                    onBind = { bound += it },
                    enabled = enabled,
                    initialFocus = true,
                    state = state,
                    modifier = Modifier.width(160f).testTag("bind"),
                )
                Button("OTHER", onClick = {}, modifier = Modifier.testTag("other"))
            }
        }

        ui.key(Key.Enter)
        ui.assertText("bind", "PRESS A KEY")

        enabled = false
        ui.settle()
        ui.assertText("bind", "SPACE")

        ui.key(Key.K)
        enabled = true
        ui.settle()
        ui.assertText("bind", "SPACE")
        assertTrue(bound.isEmpty())
        assertEquals(false, state.isListening)
    }

    @Test
    fun `turning it off gives up even when focus never came`() {
        var enabled by mutableStateOf(true)
        val state = KeyBindState()
        val ui = open {
            Column {
                Button("BACK", onClick = {}, initialFocus = true, modifier = Modifier.testTag("back"))
                KeyBindButton(
                    binding = InputBinding.Keyboard(Key.Space),
                    onBind = { bound += it },
                    enabled = enabled,
                    state = state,
                    modifier = Modifier.width(160f).testTag("bind"),
                )
            }
        }

        state.listen()
        ui.settle()
        ui.assertText("bind", "PRESS A KEY")

        enabled = false
        ui.settle()
        enabled = true
        ui.settle()

        ui.assertText("bind", "SPACE")
        assertEquals(false, state.isListening)
    }

    @Test
    fun `a screen that closes on bind does not press what focus lands on`() {
        var showing by mutableStateOf(true)
        var pressed = 0
        val ui = open {
            if (showing) {
                KeyBindButton(
                    binding = null,
                    onBind = { bound += it; showing = false },
                    initialFocus = true,
                    modifier = Modifier.width(160f).testTag("bind"),
                )
            } else {
                Button("DONE", onClick = { pressed += 1 }, initialFocus = true, modifier = Modifier.testTag("done"))
            }
        }

        ui.key(Key.Enter)
        ui.key(Key.Enter)
        ui.pad(GamepadButton.South)

        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.Enter)), bound)
        ui.assertFocused("done")
        assertEquals(1, pressed, "only the South pressed on DONE itself, not the Enter that closed the screen")
    }

    @Test
    fun `a still screen draws nothing new while it waits`() {
        val ui = open { Controls() }

        ui.click("jump")
        ui.assertText("jump", "PRESS A KEY")
        ui.render()

        repeat(30) { assertFalse(ui.render(), "waiting for a press changes nothing on the screen") }

        ui.key(Key.J)
        ui.render()
        repeat(30) { assertFalse(ui.render(), "a bound button is as still as any other") }
    }

    @Test
    fun `a state held outside can open it already listening`() {
        val state = KeyBindState()
        val ui = open {
            KeyBindButton(
                binding = InputBinding.Keyboard(Key.Space),
                onBind = { bound += it },
                initialFocus = true,
                state = state,
                modifier = Modifier.testTag("bind"),
            )
        }

        state.listen()
        ui.settle()
        ui.assertText("bind", "PRESS A KEY")

        ui.key(Key.Q)
        assertEquals(false, state.isListening)
        assertEquals(listOf<InputBinding>(InputBinding.Keyboard(Key.Q)), bound)
    }
}
