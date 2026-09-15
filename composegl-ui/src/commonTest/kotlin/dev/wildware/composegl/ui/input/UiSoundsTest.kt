package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.RadioButton
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Menus with sounds provided, driven by a mouse, a keyboard and a pad, and judged by what the game
 * was asked to play.
 *
 * Every screen here is made of stock widgets or the plain modifiers a game builds its own from, and
 * not one of them has a line about sound in it: what is heard comes from [ProvideUiSounds] and the
 * routers, which is the point of the feature.
 */
class UiSoundsTest {

    /** What a game's audio would have been asked to play, in order. */
    private class Heard(private val name: String = "") : UiSounds {
        val log = mutableListOf<String>()
        override fun hover() { log += "${name}hover" }
        override fun press() { log += "${name}press" }
        override fun focusMove() { log += "${name}focusMove" }
        override fun change() { log += "${name}change" }
    }

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(heard: Heard, content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f)) { ProvideUiSounds(heard, content) }.also { opened += it }

    @Composable
    private fun Menu() {
        Column {
            Button("PLAY", onClick = {}, initialFocus = true, modifier = Modifier.testTag("play"))
            Button("OPTIONS", onClick = {}, modifier = Modifier.testTag("options"))
            Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
        }
    }

    // --- the pointer ---------------------------------------------------------------------------

    @Test
    fun `hovering a button ticks once however the mouse moves inside it`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.moveTo("play")
        val bounds = ui.node("play").boundsInRoot
        ui.moveTo(Offset(bounds.left + 2f, bounds.top + 2f))
        ui.moveTo(Offset(bounds.right - 2f, bounds.bottom - 2f))

        assertEquals(listOf("hover"), heard.log)
    }

    @Test
    fun `moving from one button to the next ticks for each and leaving is silent`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.moveTo("play")
        ui.moveTo("options")
        ui.moveTo(Offset(590f, 390f))
        ui.moveTo("options")

        assertEquals(listOf("hover", "hover", "hover"), heard.log)
    }

    @Test
    fun `a button inside a clickable card ticks once for the button`() {
        val heard = Heard()
        val ui = open(heard) {
            Box(Modifier.size(200f).clickable {}.testTag("card")) {
                Button("BUY", onClick = {}, modifier = Modifier.testTag("buy"))
            }
        }

        ui.moveTo("buy")
        assertEquals(listOf("hover"), heard.log, "arriving on the button and its card at once is one tick")

        ui.moveTo(Offset(190f, 190f))
        assertEquals(listOf("hover"), heard.log, "the card was already hovered")

        ui.moveTo("buy")
        assertEquals(listOf("hover", "hover"), heard.log)
    }

    @Test
    fun `a click presses once and letting go does not hover again`() {
        val heard = Heard()
        var clicks = 0
        val ui = open(heard) {
            Button("GO", onClick = { clicks++ }, modifier = Modifier.testTag("go"))
        }

        ui.click("go")

        assertEquals(1, clicks)
        assertEquals(listOf("hover", "press"), heard.log)
    }

    @Test
    fun `dragging off a button and back on does not press it again`() {
        val heard = Heard()
        val ui = open(heard) {
            Button("GO", onClick = {}, modifier = Modifier.testTag("go"))
        }

        ui.press("go")
        ui.dragTo(Offset(590f, 390f))
        ui.dragTo(ui.node("go").boundsInRoot.centre)
        ui.release()

        assertEquals(listOf("hover", "press"), heard.log)
    }

    @Test
    fun `letting go over a different button ticks for that button`() {
        val heard = Heard()
        var clicks = 0
        val ui = open(heard) {
            Row {
                Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("play"))
                Button("QUIT", onClick = { clicks++ }, modifier = Modifier.testTag("quit"))
            }
        }

        ui.press("play")
        ui.dragTo(ui.node("quit").boundsInRoot.centre)
        ui.release()
        assertEquals(0, clicks, "a change of mind is not a click")
        assertEquals(listOf("hover", "press", "hover"), heard.log, "the pointer has arrived on QUIT")

        ui.moveTo("quit")
        assertEquals(listOf("hover", "press", "hover"), heard.log, "and it is already there")
    }

    @Test
    fun `a disabled button and a panel that only watches the pointer are silent`() {
        val heard = Heard()
        var clicks = 0
        val ui = open(heard) {
            Row {
                Button("LOCKED", onClick = { clicks++ }, enabled = false, modifier = Modifier.testTag("locked"))
                Box(Modifier.size(100f).interaction(remember { InteractionState() }).testTag("panel"))
            }
        }

        ui.moveTo("locked")
        ui.click("locked")
        ui.moveTo("panel")
        ui.click("panel")

        assertEquals(0, clicks)
        assertEquals(emptyList(), heard.log)
    }

    // --- focus ---------------------------------------------------------------------------------

    @Test
    fun `the first frame focuses the menu without a sound`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.assertFocused("play")
        assertEquals(emptyList(), heard.log)
    }

    @Test
    fun `arrow keys and the d-pad play a move for each step and nothing at the end`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.key(Key.Down)
        ui.assertFocused("options")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("quit")
        assertEquals(listOf("focusMove", "focusMove"), heard.log)

        ui.key(Key.Down)
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("quit")
        assertEquals(listOf("focusMove", "focusMove"), heard.log, "no step was taken so none is heard")

        ui.key(Key.Tab)
        ui.assertFocused("play")
        assertEquals(listOf("focusMove", "focusMove", "focusMove"), heard.log)
    }

    @Test
    fun `the stick plays a move for each step it takes`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.stick(0f, 1f)
        ui.stick(0f, 0f)

        ui.assertFocused("options")
        assertEquals(listOf("focusMove"), heard.log)
    }

    @Test
    fun `a click that moves focus presses and does not also play a move`() {
        val heard = Heard()
        val ui = open(heard) { Menu() }

        ui.click("quit")

        ui.assertFocused("quit")
        assertEquals(listOf("hover", "press"), heard.log)
    }

    @Test
    fun `enter and the pad south button press the focused button`() {
        val heard = Heard()
        var clicks = 0
        val ui = open(heard) {
            Button("GO", onClick = { clicks++ }, initialFocus = true, modifier = Modifier.testTag("go"))
        }

        ui.key(Key.Enter)
        ui.pad(GamepadButton.South)

        assertEquals(2, clicks)
        assertEquals(listOf("press", "press"), heard.log)
    }

    // --- values --------------------------------------------------------------------------------

    @Test
    fun `ticking a checkbox and flipping a toggle play a change`() {
        val heard = Heard()
        val ui = open(heard) {
            var ticked by remember { mutableStateOf(false) }
            var on by remember { mutableStateOf(false) }
            Column {
                Checkbox(ticked, { ticked = it }, label = if (ticked) "TICKED" else "CLEAR", modifier = Modifier.testTag("tick"))
                Toggle(on, { on = it }, modifier = Modifier.testTag("switch"))
            }
        }

        ui.click("tick")
        ui.assertText("tick", "TICKED")
        assertEquals(listOf("hover", "press", "change"), heard.log)

        heard.log.clear()
        ui.key(Key.Down)
        ui.pad(GamepadButton.South)
        assertEquals(listOf("focusMove", "press", "change"), heard.log)
    }

    @Test
    fun `choosing a radio button changes and choosing it again does not`() {
        val heard = Heard()
        val ui = open(heard) {
            var level by remember { mutableStateOf("easy") }
            Column {
                Text(level, Modifier.testTag("level"))
                RadioButton(level == "easy", { level = "easy" }, label = "EASY", modifier = Modifier.testTag("easy"))
                RadioButton(level == "hard", { level = "hard" }, label = "HARD", modifier = Modifier.testTag("hard"))
            }
        }

        ui.click("hard")
        ui.assertText("level", "hard")
        ui.click("hard")

        assertEquals(listOf("hover", "press", "change", "press"), heard.log)
    }

    @Test
    fun `a slider nudged by the pad changes each step and hands focus on at the end`() {
        val heard = Heard()
        val ui = open(heard) {
            var volume by remember { mutableStateOf(50f) }
            Row {
                Text("${volume.toInt()}", Modifier.testTag("volume"))
                Slider(volume, { volume = it }, range = 0f..100f, step = 25f, initialFocus = true, modifier = Modifier.testTag("slider"))
                Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
            }
        }

        ui.pad(GamepadButton.DpadRight)
        ui.key(Key.Right)
        ui.assertText("volume", "100")
        assertEquals(listOf("change", "change"), heard.log)

        ui.pad(GamepadButton.DpadRight)
        ui.assertFocused("done")
        assertEquals(listOf("change", "change", "focusMove"), heard.log)
    }

    @Test
    fun `dragging a stepped slider ticks as the knob lands on each step`() {
        val heard = Heard()
        val ui = open(heard) {
            var volume by remember { mutableStateOf(0f) }
            Column {
                Text("${volume.toInt()}", Modifier.testTag("volume"))
                Slider(volume, { volume = it }, range = 0f..100f, step = 25f, modifier = Modifier.testTag("slider"))
            }
        }
        val bounds = ui.node("slider").boundsInRoot
        val y = bounds.centre.y
        // The knob is sixteen across and grabbed by its middle, so it travels the width less that.
        fun at(fraction: Float) = Offset(bounds.left + 8f + fraction * (bounds.width - 16f), y)

        ui.press(at(0f))
        ui.dragTo(at(0.25f))
        ui.dragTo(at(0.3f))
        ui.dragTo(at(0.5f))
        ui.dragTo(at(1f))
        ui.release()

        ui.assertText("volume", "100")
        assertEquals(listOf("hover", "press", "change", "change", "change"), heard.log)
    }

    @Test
    fun `dragging a continuous slider plays one change when it is let go`() {
        val heard = Heard()
        val ui = open(heard) {
            var volume by remember { mutableStateOf(0f) }
            Column {
                Text("${(volume * 100).toInt()}", Modifier.testTag("volume"))
                Slider(volume, { volume = it }, modifier = Modifier.testTag("slider"))
            }
        }
        val bounds = ui.node("slider").boundsInRoot
        val y = bounds.centre.y

        ui.press(Offset(bounds.left + 8f, y))
        ui.dragTo(Offset(bounds.left + 40f, y))
        ui.dragTo(Offset(bounds.left + 80f, y))
        assertEquals(listOf("hover", "press"), heard.log, "silent while the knob is moving")

        ui.release()
        assertEquals(listOf("hover", "press", "change"), heard.log)

        heard.log.clear()
        ui.click(Offset(bounds.left + 80f, y))
        assertEquals(listOf("press"), heard.log, "a press that moved nothing changed nothing")
    }

    // --- where the sounds come from ------------------------------------------------------------

    @Test
    fun `a control a game builds from modifiers sounds like a stock one`() {
        val heard = Heard()
        var picked = 0
        val ui = open(heard) {
            Column {
                Button("BACK", onClick = {}, initialFocus = true, modifier = Modifier.testTag("back"))
                val sounds = LocalUiSounds.current
                Box(
                    Modifier.size(80f)
                        .focusable(remember { InteractionState() })
                        .clickable { picked++; sounds.change() }
                        .testTag("card"),
                )
            }
        }

        ui.moveTo("card")
        ui.click("card")
        ui.key(Key.Up)
        ui.key(Key.Down)
        ui.key(Key.Enter)

        assertEquals(2, picked)
        assertEquals(
            listOf("hover", "press", "change", "focusMove", "focusMove", "press", "change"),
            heard.log,
        )
    }

    @Test
    fun `each part of a screen plays the sounds provided around it`() {
        val menu = Heard("menu.")
        val hud = Heard("hud.")
        val ui = uiTest(Size(600f, 400f)) {
            Column {
                ProvideUiSounds(menu) { Button("RESUME", onClick = {}, modifier = Modifier.testTag("resume")) }
                ProvideUiSounds(hud) { Button("MAP", onClick = {}, modifier = Modifier.testTag("map")) }
            }
        }.also { opened += it }

        ui.moveTo("resume")
        ui.moveTo("map")

        assertEquals(listOf("menu.hover"), menu.log)
        assertEquals(listOf("hud.hover"), hud.log)
    }

    @Test
    fun `swapping the sounds mid-game takes effect on the next hover`() {
        val quiet = Heard("quiet.")
        val loud = Heard("loud.")
        var sounds: UiSounds by mutableStateOf(quiet)
        val ui = uiTest(Size(600f, 400f)) {
            ProvideUiSounds(sounds) { Menu() }
        }.also { opened += it }

        ui.moveTo("play")
        sounds = loud
        ui.settle()
        ui.moveTo("options")

        assertEquals(listOf("quiet.hover"), quiet.log)
        assertEquals(listOf("loud.hover"), loud.log)
    }

    @Test
    fun `with nothing provided every control still works in silence`() {
        var clicks = 0
        val ui = uiTest(Size(600f, 400f)) {
            Button("GO", onClick = { clicks++ }, initialFocus = true, modifier = Modifier.testTag("go"))
        }.also { opened += it }

        ui.click("go")
        ui.key(Key.Enter)

        assertEquals(2, clicks)
    }
}
