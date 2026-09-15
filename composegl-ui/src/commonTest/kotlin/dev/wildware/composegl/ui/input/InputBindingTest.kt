package dev.wildware.composegl.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a binding is called and drawn as, and who else has it. */
class InputBindingTest {

    @Test
    fun `a key is drawn by its short name`() {
        assertEquals(Prompt("key.escape", "ESC"), InputBinding.Keyboard(Key.Escape).prompt())
        assertEquals(Prompt("key.e", "E"), InputBinding.Keyboard(Key.E).prompt())
        assertEquals(Prompt("key.digit1", "1"), InputBinding.Keyboard(Key.Digit1).prompt())
        assertEquals("F12", InputBinding.Keyboard(Key.F12).prompt().label)
        assertEquals("/", InputBinding.Keyboard(Key.Slash).prompt().label)
    }

    @Test
    fun `the keyboard keys match the default prompt table`() {
        val prompts = Prompts()
        listOf(Action.Confirm to Key.E, Action.Cancel to Key.Escape, Action.Menu to Key.Tab).forEach { (action, key) ->
            assertEquals(prompts.prompt(action, PromptStyle.Keyboard), InputBinding.Keyboard(key).prompt())
        }
    }

    @Test
    fun `a mouse button is drawn the same on every style`() {
        PromptStyle.entries.forEach {
            assertEquals(Prompt("mouse.secondary", "RMB"), InputBinding.Mouse(PointerButton.Secondary).prompt(it))
        }
    }

    @Test
    fun `a pad button is drawn the way each make prints it`() {
        val south = InputBinding.Gamepad(GamepadButton.South)
        assertEquals(Prompt("pad.south", "A"), south.prompt(PromptStyle.Xbox))
        assertEquals(Prompt("pad.south", "✕"), south.prompt(PromptStyle.PlayStation))
        assertEquals(Prompt("pad.south", "B"), south.prompt(PromptStyle.Nintendo))
        assertEquals("A", south.prompt(PromptStyle.Keyboard).label)
        assertEquals(Prompt("pad.dpad.up", "D-UP"), InputBinding.Gamepad(GamepadButton.DpadUp).prompt())
        assertEquals("R1", InputBinding.Gamepad(GamepadButton.RightBumper).prompt(PromptStyle.PlayStation).label)
    }

    @Test
    fun `every button has a label on every pad`() {
        PromptStyle.entries.forEach { style ->
            GamepadButton.entries.forEach { button ->
                assertTrue(InputBinding.Gamepad(button).prompt(style).label.isNotBlank(), "$button on $style")
            }
        }
    }

    @Test
    fun `binding a key changes the keyboard prompt only`() {
        val prompts = Prompts()
        prompts.bind(Action.Interact, InputBinding.Keyboard(Key.G))

        assertEquals("G", prompts.prompt(Action.Interact, PromptStyle.Keyboard).label)
        assertEquals("X", prompts.prompt(Action.Interact, PromptStyle.Xbox).label)
    }

    @Test
    fun `binding a pad button changes every pad each in its own glyph`() {
        val prompts = Prompts()
        prompts.bind(Action.Interact, InputBinding.Gamepad(GamepadButton.East))

        assertEquals("B", prompts.prompt(Action.Interact, PromptStyle.Xbox).label)
        assertEquals("○", prompts.prompt(Action.Interact, PromptStyle.PlayStation).label)
        assertEquals("A", prompts.prompt(Action.Interact, PromptStyle.Nintendo).label)
        assertEquals("F", prompts.prompt(Action.Interact, PromptStyle.Keyboard).label)
    }

    @Test
    fun `clashes name everybody else with the same binding`() {
        val binds = mapOf(
            "jump" to InputBinding.Keyboard(Key.Space),
            "vault" to InputBinding.Keyboard(Key.Space),
            "crouch" to InputBinding.Keyboard(Key.C),
            "fire" to null,
        )

        assertEquals(listOf("vault"), binds.clashesWith(InputBinding.Keyboard(Key.Space), ignoring = "jump"))
        assertEquals(listOf("jump", "vault"), binds.clashesWith(InputBinding.Keyboard(Key.Space)))
        assertEquals(emptyList(), binds.clashesWith(InputBinding.Mouse(PointerButton.Primary)))
    }

    @Test
    fun `a key has a stable name to save it by`() {
        assertEquals("Escape", Key.Escape.name)
        assertEquals("Digit7", Key.Digit7.name)
        assertEquals("Unknown", Key(999).name)
    }
}
