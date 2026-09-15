package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onActivate
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * `onActivate`: South or Enter on the focused node, asked before the click. A yes is instead of the
 * click, a no leaves the click alone, and a mouse never asks.
 */
class ActivateUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(300f, 200f), content = content).also { opened += it }

    /** A slot that clicks, whose activation says yes only while [takes] is on. */
    @Composable
    private fun Slot(takes: Boolean) {
        var log by remember { mutableStateOf("") }
        Column {
            Box(
                Modifier.testTag("slot").size(60f, 40f)
                    .focusable()
                    .onActivate { if (takes) { log += "A"; true } else false }
                    .clickable { log += "C" },
            )
            Text(log, Modifier.testTag("log"))
        }
    }

    @Test
    fun `South on a node whose activation says yes is not a click`() {
        val ui = open { Slot(takes = true) }
        ui.assertFocused("slot")

        ui.pad(GamepadButton.South)
        ui.assertText("log", "A")

        ui.key(Key.Enter)
        ui.assertText("log", "AA")
    }

    @Test
    fun `South on a node whose activation says no is still a click`() {
        val ui = open { Slot(takes = false) }

        ui.pad(GamepadButton.South)
        ui.key(Key.Enter)

        ui.assertText("log", "CC")
    }

    @Test
    fun `a mouse click never asks the activation`() {
        val ui = open { Slot(takes = true) }

        ui.click("slot")

        ui.assertText("log", "C")
    }

    @Test
    fun `a node with nothing to click is still activated by South`() {
        val ui = open {
            var log by remember { mutableStateOf("") }
            Column {
                Box(Modifier.testTag("slot").size(60f, 40f).focusable().onActivate { log += "A"; true })
                Text(log, Modifier.testTag("log"))
            }
        }

        ui.pad(GamepadButton.South)

        ui.assertText("log", "A")
    }
}
