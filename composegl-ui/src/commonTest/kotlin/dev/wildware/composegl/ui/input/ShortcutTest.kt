package dev.wildware.composegl.ui.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.onShortcutGamepad
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shortcuts: what a key and its modifiers say, and who hears a key nothing focused wanted. */
class ShortcutTest {

    @AfterTest
    fun tearDown() {
        Modifiers.isMac = false
    }

    // --- KeyShortcut ---------------------------------------------------------------------------

    @Test
    fun `primary is control everywhere but a mac`() {
        assertEquals(KeyShortcut(Key.S, Modifiers.Control), Modifiers.Primary + Key.S)
        Modifiers.isMac = true
        assertEquals(KeyShortcut(Key.S, Modifiers.Meta), Modifiers.Primary + Key.S)
    }

    @Test
    fun `a shortcut matches its own keys going down and nothing else`() {
        val save = Modifiers.Control + Key.S

        assertTrue(save.matches(KeyEvent(Key.S, KeyEventType.Down, Modifiers.Control)))
        assertFalse(save.matches(KeyEvent(Key.S, KeyEventType.Up, Modifiers.Control)), "not coming up")
        assertFalse(save.matches(KeyEvent(Key.S, KeyEventType.Down)), "not without Control")
        assertFalse(save.matches(KeyEvent(Key.S, KeyEventType.Down, Modifiers.Control + Modifiers.Shift)), "not with Shift as well")
        assertFalse(save.matches(KeyEvent(Key.S, KeyEventType.Down, Modifiers.Control, repeat = true)), "not a held key repeating")
    }

    @Test
    fun `the label uses each platform's names`() {
        assertEquals("Ctrl+S", (Modifiers.Primary + Key.S).label)
        assertEquals("Ctrl+Shift+Z", (Modifiers.Control + Modifiers.Shift + Key.Z).label)
        assertEquals("Alt+F4", (Modifiers.Alt + Key.F4).label)
        assertEquals("F5", KeyShortcut(Key.F5).label)
        assertEquals("Ctrl+1", (Modifiers.Control + Key.Digit1).label)
        assertEquals("Del", KeyShortcut(Key.Delete).label)

        Modifiers.isMac = true
        assertEquals("Cmd+S", (Modifiers.Primary + Key.S).label)
        assertEquals("Opt+Shift+Cmd+Z", (Modifiers.Primary + Modifiers.Shift + Modifiers.Alt + Key.Z).label)
    }

    // --- onShortcutKey -------------------------------------------------------------------------

    private val heard = mutableListOf<String>()

    private fun listener(name: String) = KeyHandler { event ->
        (Modifiers.Control + Key.S).matches(event).also { if (it) heard += name }
    }

    @Test
    fun `a shortcut is heard wherever focus is`() {
        val ui = uiTest(Size(400f, 300f)) {
            Column {
                Box(Modifier.onShortcutKey(remember { listener("saver") }))
                Button("A", onClick = {}, modifier = Modifier.testTag("a"), initialFocus = true)
            }
        }
        ui.use {
            ui.assertFocused("a")
            assertTrue(ui.key(Key.S, Modifiers.Control))
            assertEquals(listOf("saver"), heard)
        }
    }

    @Test
    fun `the focused node's own keys come first`() {
        val ui = uiTest(Size(400f, 300f)) {
            Column {
                Box(Modifier.onShortcutKey(remember { listener("saver") }))
                Button(
                    "A",
                    onClick = {},
                    modifier = Modifier.testTag("a").onKeyEvent(remember { listener("field") }),
                    initialFocus = true,
                )
            }
        }
        ui.use {
            ui.key(Key.S, Modifiers.Control)
            assertEquals(listOf("field"), heard, "the field used Ctrl+S, so the shortcut never heard it")
        }
    }

    @Test
    fun `a shortcut behind a focus trap is not heard and one inside it is`() {
        var trapped by mutableStateOf(true)
        val ui = uiTest(Size(400f, 300f)) {
            Column {
                Box(Modifier.onShortcutKey(remember { listener("behind") }))
                Column(Modifier.focusTrap(trapped).onShortcutKey(remember { listener("inside") })) {
                    Button("OK", onClick = {}, modifier = Modifier.testTag("ok"))
                }
            }
        }
        ui.use {
            ui.key(Key.S, Modifiers.Control)
            assertEquals(listOf("inside"), heard)

            trapped = false
            ui.advanceBy(16)
            heard.clear()
            ui.key(Key.S, Modifiers.Control)
            assertEquals(listOf("behind"), heard, "with the trap gone both are reachable, in tree order")
        }
    }

    @Test
    fun `a shortcut on something faded out is not heard`() {
        val ui = uiTest(Size(400f, 300f)) {
            Column {
                Box(Modifier.alpha(0f)) { Box(Modifier.onShortcutKey(remember { listener("hidden") })) }
                Button("A", onClick = {}, initialFocus = true)
            }
        }
        ui.use {
            assertFalse(ui.key(Key.S, Modifiers.Control))
            assertEquals(emptyList(), heard)
        }
    }

    @Test
    fun `a pad shortcut is heard before the pad navigates`() {
        var backs = 0
        val ui = uiTest(Size(400f, 300f), onBack = { backs++ }) {
            Column {
                val listener = remember {
                    GamepadHandler { event ->
                        (event is GamepadEvent.ButtonDown && event.button == GamepadButton.Back).also { if (it) heard += "pad" }
                    }
                }
                Box(Modifier.onShortcutGamepad(listener))
                Button("A", onClick = {}, initialFocus = true)
            }
        }
        ui.use {
            ui.pad(GamepadButton.Back)
            ui.pad(GamepadButton.East)
            assertEquals(listOf("pad"), heard)
            assertEquals(1, backs, "East was not the shortcut's, so it still went back")
        }
    }
}
