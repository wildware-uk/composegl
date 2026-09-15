package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A settings screen with steppers on it, driven through [uiTest] the way a player drives it.
 *
 * Nothing reaches into the stepper: keys, pad buttons, a stick and a mouse go in through the same
 * routers a game wires, and what comes out is read off focus and off the words drawn on the screen.
 */
class StepperScreenTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), content = content).also { opened += it }

    /** Quality, then volume, then an apply button, one under the other. */
    @Composable
    private fun Settings(wrap: Boolean = false, enabled: Boolean = true, startVolume: Int = 5) {
        var quality by remember { mutableStateOf("Medium") }
        var volume by remember { mutableStateOf(startVolume) }
        var applied by remember { mutableStateOf("") }
        Column {
            Row {
                Stepper(
                    options = listOf("Low", "Medium", "High"),
                    selected = quality,
                    onSelect = { quality = it },
                    wrap = wrap,
                    enabled = enabled,
                    initialFocus = true,
                    modifier = Modifier.testTag("quality"),
                )
                Button("RESET", onClick = { quality = "Medium" }, modifier = Modifier.testTag("reset"))
            }
            NumberStepper(
                value = volume,
                onValueChange = { volume = it },
                range = 0..10,
                format = { "$it/10" },
                enabled = enabled,
                modifier = Modifier.testTag("volume"),
            )
            Button("APPLY", onClick = { applied = "$quality $volume" }, modifier = Modifier.testTag("apply"))
            Text(applied, modifier = Modifier.testTag("applied"))
        }
    }

    /** What a stepper shows, as it is drawn: left arrow, value, right arrow. */
    private fun shows(value: String) = "<\n$value\n>"

    /** The middle of one of a stepper's three pieces: 0 the left arrow, 1 the value, 2 the right. */
    private fun UiTest.pieceOf(tag: String, piece: Int): Offset = node(tag).children[piece].boundsInRoot.centre

    // --- keys ------------------------------------------------------------------------------------

    @Test
    fun `the arrow keys change the focused stepper and apply sees it`() {
        val ui = open { Settings() }
        ui.assertFocused("quality")
        ui.assertText("quality", shows("Medium"))

        ui.key(Key.Left)
        ui.assertText("quality", shows("Low"))
        ui.assertFocused("quality")

        ui.key(Key.Down)
        ui.assertFocused("volume")
        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertText("volume", shows("7/10"))

        ui.key(Key.Down)
        ui.key(Key.Enter)
        ui.assertText("applied", "Low 7")
    }

    @Test
    fun `right past the last option moves focus to the button beside it`() {
        val ui = open { Settings() }

        ui.key(Key.Right)
        ui.assertText("quality", shows("High"))
        ui.assertFocused("quality")

        ui.key(Key.Right)
        ui.assertText("quality", shows("High"))
        ui.assertFocused("reset")
    }

    @Test
    fun `a wrapping stepper goes round and keeps focus`() {
        val ui = open { Settings(wrap = true) }

        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertText("quality", shows("Low"))
        ui.assertFocused("quality")

        ui.key(Key.Left)
        ui.assertText("quality", shows("High"))
    }

    @Test
    fun `enter on a stepper moves to the next option and goes round`() {
        val ui = open { Settings() }

        ui.key(Key.Enter)
        ui.assertText("quality", shows("High"))
        ui.key(Key.Enter)
        ui.assertText("quality", shows("Low"))
    }

    // --- the pad ---------------------------------------------------------------------------------

    @Test
    fun `the d-pad steps and south cycles on a pad`() {
        val ui = open { Settings() }

        ui.pad(GamepadButton.DpadLeft)
        ui.assertText("quality", shows("Low"))

        ui.pad(GamepadButton.South)
        ui.assertText("quality", shows("Medium"))

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("volume")
        ui.pad(GamepadButton.DpadLeft)
        ui.assertText("volume", shows("4/10"))
    }

    @Test
    fun `a stick held right steps once then waits then repeats until let go`() {
        val ui = open { Settings(startVolume = 0) }
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("volume")

        ui.stick(1f, 0f)
        ui.assertText("volume", shows("1/10"))

        // Each axis sent settles a few frames too, so this stays well short of the 400ms pause.
        ui.advanceBy(150)
        ui.assertText("volume", shows("1/10"))

        ui.advanceBy(600)
        val held = ui.text("volume").lines()[1].substringBefore("/").toInt()
        assertTrue(held >= 4, "past the pause it repeats: $held")

        ui.stick(0f, 0f)
        ui.advanceBy(1_000)
        ui.assertText("volume", shows("$held/10"))
        ui.assertFocused("volume")
    }

    @Test
    fun `a stick held on the last option leaves the control`() {
        val ui = open { Settings(startVolume = 9) }
        ui.pad(GamepadButton.DpadDown)

        ui.stick(1f, 0f)
        ui.assertText("volume", shows("10/10"))
        ui.advanceBy(1_000)
        ui.stick(0f, 0f)

        ui.assertText("volume", shows("10/10"))
        assertEquals(false, ui.node("volume") === ui.focus.focused, "the held stick went on past the end")
    }

    // --- the mouse -------------------------------------------------------------------------------

    @Test
    fun `clicking an arrow steps once and takes focus`() {
        val ui = open { Settings() }

        assertTrue(ui.click(ui.pieceOf("volume", 2)))
        ui.assertText("volume", shows("6/10"))
        ui.assertFocused("volume")

        ui.click(ui.pieceOf("volume", 0))
        ui.click(ui.pieceOf("volume", 0))
        ui.assertText("volume", shows("4/10"))
    }

    @Test
    fun `holding the mouse on an arrow repeats and stops at the end`() {
        val ui = open { Settings(startVolume = 0) }

        ui.press(ui.pieceOf("volume", 2))
        ui.assertText("volume", shows("1/10"))
        ui.advanceBy(250)
        ui.assertText("volume", shows("1/10"))

        ui.advanceBy(3_000)
        ui.release()
        ui.assertText("volume", shows("10/10"))
    }

    @Test
    fun `dragging off a held arrow waits and coming back carries on`() {
        val ui = open { Settings(startVolume = 0) }
        val right = ui.pieceOf("volume", 2)

        ui.press(right)
        ui.assertText("volume", shows("1/10"))

        // Off the arrow and onto the value, still held: nothing more however long it stays there.
        ui.moveTo(ui.pieceOf("volume", 1))
        ui.advanceBy(1_000)
        ui.assertText("volume", shows("1/10"))

        ui.moveTo(right)
        ui.advanceBy(600)
        ui.release()
        val back = ui.text("volume").lines()[1].substringBefore("/").toInt()
        assertTrue(back >= 3, "back on the arrow it repeats again: $back")

        ui.advanceBy(1_000)
        ui.assertText("volume", shows("$back/10"))
    }

    @Test
    fun `a stepper disabled while its arrow is held stops stepping`() {
        var enabled by mutableStateOf(true)
        val ui = open { Settings(enabled = enabled, startVolume = 0) }

        ui.press(ui.pieceOf("volume", 2))
        ui.assertText("volume", shows("1/10"))

        enabled = false
        ui.advanceBy(2_000)
        ui.release()
        ui.assertText("volume", shows("1/10"))

        // And once it is back, the old hold is gone: nothing steps by itself.
        enabled = true
        ui.advanceBy(2_000)
        ui.assertText("volume", shows("1/10"))
    }

    @Test
    fun `left from a volume between its steps lands on the step below`() {
        val ui = open {
            var volume by remember { mutableStateOf(7) }
            NumberStepper(
                value = volume,
                onValueChange = { volume = it },
                range = 0..20,
                step = 5,
                initialFocus = true,
                modifier = Modifier.testTag("volume"),
            )
        }
        ui.assertText("volume", shows("7"))

        ui.key(Key.Left)
        ui.assertText("volume", shows("5"))
        ui.key(Key.Left)
        ui.assertText("volume", shows("0"))
    }

    @Test
    fun `clicking the value moves to the next option`() {
        val ui = open { Settings() }

        ui.click(ui.pieceOf("quality", 1))
        ui.assertText("quality", shows("High"))
        ui.click(ui.pieceOf("quality", 1))
        ui.assertText("quality", shows("Low"))
    }

    @Test
    fun `a disabled stepper ignores the mouse and the keys`() {
        val ui = open { Settings(enabled = false) }

        ui.click(ui.pieceOf("quality", 2))
        ui.click(ui.pieceOf("volume", 0))
        ui.key(Key.Right)

        ui.assertText("quality", shows("Medium"))
        ui.assertText("volume", shows("5/10"))
    }

    @Test
    fun `the arrows do not move as the value changes`() {
        val ui = open { Settings() }
        val right = ui.node("quality").children[2].boundsInRoot

        ui.key(Key.Left)
        ui.assertText("quality", shows("Low"))

        assertEquals(right, ui.node("quality").children[2].boundsInRoot)
    }

    // --- odd values ------------------------------------------------------------------------------

    @Test
    fun `a volume below its range steps up into it and never down`() {
        val reported = mutableListOf<Int>()
        val ui = open {
            Column {
                var volume by remember { mutableStateOf(-3) }
                NumberStepper(
                    value = volume,
                    onValueChange = { reported += it; volume = it },
                    range = 0..10,
                    initialFocus = true,
                    modifier = Modifier.testTag("volume"),
                )
                Button("NEXT", onClick = {}, modifier = Modifier.testTag("next"))
            }
        }
        ui.assertText("volume", shows("-3"))

        // Left from below the range has nowhere lower to go, so it lets focus move on.
        ui.key(Key.Left)
        ui.assertText("volume", shows("-3"))
        assertEquals(emptyList(), reported)
        ui.key(Key.Up)
        ui.assertFocused("volume")

        ui.key(Key.Right)
        ui.assertText("volume", shows("0"))
        assertEquals(listOf(0), reported)
    }

    @Test
    fun `a selection that is not one of the options shows nothing and right picks the first`() {
        val ui = open {
            var quality by remember { mutableStateOf("Ultra") }
            Stepper(
                options = listOf("Low", "Medium", "High"),
                selected = quality,
                onSelect = { quality = it },
                initialFocus = true,
                modifier = Modifier.testTag("quality"),
            )
        }
        assertEquals(listOf("<", ">"), ui.texts("quality").filter { it.isNotEmpty() })

        ui.key(Key.Left)
        assertEquals(listOf("<", ">"), ui.texts("quality").filter { it.isNotEmpty() })

        ui.key(Key.Right)
        ui.assertText("quality", shows("Low"))
    }

    @Test
    fun `a stepper with no options takes no direction and no click`() {
        var picked = 0
        val ui = open {
            Column {
                Stepper(
                    options = emptyList<String>(),
                    selected = "",
                    onSelect = { picked++ },
                    initialFocus = true,
                    modifier = Modifier.testTag("empty"),
                )
                Button("NEXT", onClick = {}, modifier = Modifier.testTag("next"))
            }
        }

        ui.key(Key.Right)
        ui.key(Key.Enter)
        ui.click(ui.pieceOf("empty", 2))
        ui.click(ui.pieceOf("empty", 1))
        assertEquals(0, picked)
        ui.key(Key.Down)
        ui.assertFocused("next")
    }

    @Test
    fun `new options widen the value to the new widest`() {
        var options by mutableStateOf(listOf("A", "B"))
        val ui = open {
            Stepper(options = options, selected = options[0], onSelect = {}, modifier = Modifier.testTag("s"))
        }
        val before = ui.node("s").children[1].boundsInRoot.width

        options = listOf("A", "A much longer choice")
        ui.settle()
        assertTrue(ui.node("s").children[1].boundsInRoot.width > before, "the value did not grow")
    }

    @Test
    fun `a stepper squeezed to no width lays out and still steps`() {
        val ui = open {
            var volume by remember { mutableStateOf(2) }
            NumberStepper(
                value = volume,
                onValueChange = { volume = it },
                initialFocus = true,
                modifier = Modifier.width(0f).testTag("volume"),
            )
        }
        assertEquals(0f, ui.node("volume").boundsInRoot.width)
        ui.key(Key.Right)
        ui.assertText("volume", shows("3"))
    }

    @Test
    fun `a stepper taken off the screen while its arrow is held stops stepping`() {
        var shown by mutableStateOf(true)
        var volume = 0
        val ui = open {
            if (shown) {
                var v by remember { mutableStateOf(0) }
                NumberStepper(value = v, onValueChange = { v = it; volume = it }, modifier = Modifier.testTag("volume"))
            }
        }

        ui.press(ui.pieceOf("volume", 2))
        assertEquals(1, volume)
        shown = false
        ui.advanceBy(2_000)
        ui.release()
        assertEquals(1, volume)
        ui.assertDoesNotExist("volume")
    }

    @Test
    fun `a still stepper draws nothing new frame after frame`() {
        val ui = open { Settings() }
        ui.key(Key.Right)
        ui.settle()
        ui.render()
        assertEquals(false, ui.render(), "a stepper nobody touched asked for another frame")
    }
}
