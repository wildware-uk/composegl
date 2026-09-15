package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.repeatingClickable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The same gestures as [ClickGesturesTest], through [uiTest]: the routers a game wires, every
 * action settled, and the outcome read off the text the screen draws rather than off a counter.
 *
 * What this adds is the harness's own timing — every press and release settles for a few frames —
 * so a double click here is as slow as a real one, and a hold is held across settles.
 */
class ClickGesturesUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** An inventory slot that says what has happened to it. */
    @Composable
    private fun Slot(clock: Clock = Clock.Ui) {
        var log by remember { mutableStateOf("") }
        Column {
            Box(
                Modifier.testTag("slot").size(120f, 40f).focusable().clickable(
                    onDoubleClick = { log += "D" },
                    onLongPress = { log += "L" },
                    clock = clock,
                ) { log += "C" },
            ) {
                Text("slot")
            }
            Text(log, Modifier.testTag("log"))
        }
    }

    /** A quantity picker: + repeats while held. */
    @Composable
    private fun Picker() {
        var count by remember { mutableStateOf(0) }
        Column {
            Box(Modifier.testTag("plus").size(60f, 40f).focusable().repeatingClickable { count++ }) {
                Text("+")
            }
            Text("$count", Modifier.testTag("count"))
        }
    }

    @Test
    fun `a click then a quick second click on a slot reads as select then equip`() {
        val ui = open { Slot() }

        ui.click("slot")
        ui.assertText("log", "C")
        ui.click("slot")
        ui.assertText("log", "CD")
        ui.click("slot")
        ui.assertText("log", "CDC")
    }

    @Test
    fun `holding the mouse on a slot opens its actions and letting go does nothing more`() {
        val ui = open { Slot() }

        ui.press("slot")
        ui.advanceBy(300)
        ui.assertText("log", "")
        ui.advanceBy(300)
        ui.assertText("log", "L")
        ui.release()
        ui.advanceBy(1_000)
        ui.assertText("log", "L")
    }

    @Test
    fun `a second press held down is a long press rather than a double click`() {
        val ui = open { Slot() }

        ui.click("slot")
        ui.press("slot")
        ui.advanceBy(600)
        ui.release()
        ui.click("slot")

        ui.assertText("log", "CLC")
    }

    @Test
    fun `a long press on the world clock waits out a pause`() {
        val ui = open { Slot(clock = Clock.World) }

        ui.press("slot")
        ui.host.clocks.stop(Clock.World)
        ui.advanceBy(2_000)
        ui.assertText("log", "")
        ui.host.clocks.start(Clock.World)
        ui.advanceBy(600)
        ui.assertText("log", "L")
    }

    @Test
    fun `holding the plus button counts up and a tap adds one`() {
        val ui = open { Picker() }

        ui.click("plus")
        ui.assertText("count", "1")

        ui.press("plus")
        ui.advanceBy(300)
        ui.assertText("count", "1")
        ui.advanceBy(1_000)
        ui.release()
        val held = ui.text("count").toInt()
        check(held in 15..20) { "400ms, then a step every 60ms for the rest of 1.3s, was $held" }
        ui.advanceBy(500)
        ui.assertText("count", "$held")
    }

    @Test
    fun `holding South on the focused plus button counts up`() {
        val ui = open { Picker() }
        ui.assertFocused("plus")

        ui.padDown(GamepadButton.South)
        ui.advanceBy(700)
        ui.padUp(GamepadButton.South)
        val held = ui.text("count").toInt()
        check(held in 5..8) { "700ms held is a handful of steps, was $held" }
    }

    @Test
    fun `holding plus stops at the most allowed once the button disables itself`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Column {
                Box(Modifier.testTag("plus").size(60f, 40f).repeatingClickable(enabled = count < 3) { count++ }) {
                    Text("+")
                }
                Text("$count", Modifier.testTag("count"))
            }
        }

        ui.press("plus")
        ui.advanceBy(2_000)
        ui.release()

        ui.assertText("count", "3")
    }

    private var slotEnabled by mutableStateOf(true)

    @Test
    fun `a slot disabled while held does not long press`() {
        val ui = open {
            var log by remember { mutableStateOf("") }
            Column {
                Box(
                    Modifier.testTag("slot").size(120f, 40f)
                        .clickable(enabled = slotEnabled, onLongPress = { log += "L" }) { log += "C" },
                ) {
                    Text("slot")
                }
                Text(log, Modifier.testTag("log"))
            }
        }

        ui.press("slot")
        ui.advanceBy(200)
        // The game locks the slot while the player is still holding it.
        slotEnabled = false
        ui.advanceBy(1_000)
        ui.release()

        ui.assertText("log", "")
    }

    @Test
    fun `a plain button held inside a panel that long presses does not open the panel`() {
        val ui = open {
            var log by remember { mutableStateOf("") }
            Column {
                Box(Modifier.testTag("panel").size(200f, 100f).clickable(onLongPress = { log += "L" }) { log += "P" }) {
                    Box(Modifier.testTag("inner").size(60f, 40f).clickable { log += "I" }) { Text("ok") }
                }
                Text(log, Modifier.testTag("log"))
            }
        }

        ui.press("inner")
        ui.advanceBy(1_000)
        ui.release()

        ui.assertText("log", "I")
    }
}
