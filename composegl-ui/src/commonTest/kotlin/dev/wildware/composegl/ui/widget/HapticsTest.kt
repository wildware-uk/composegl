package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.RecordingHaptics
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import androidx.compose.runtime.Composable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The small bump a control gives back, asked for by real screens driven the way a player drives
 * them.
 *
 * Every test composes widgets under [uiTest] with a [RecordingHaptics] as the backend's, presses
 * them with a mouse, a keyboard or a pad, and then checks two things: what the screen shows, and
 * what the player's hand was sent. The motor itself is the backend's business and is tested there.
 */
class HapticsTest {

    private val opened = mutableListOf<UiTest>()
    private val felt = RecordingHaptics()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), HeadlessBackend(haptics = felt), content = content).also { opened += it }

    @Test
    fun `a clicked button taps once`() {
        val ui = open {
            var clicks by remember { mutableStateOf(0) }
            Column {
                Button("Play", onClick = { clicks++ }, modifier = Modifier.testTag("play"))
                Text("clicks $clicks", Modifier.testTag("count"))
            }
        }

        ui.click("play")

        ui.assertText("count", "clicks 1")
        assertEquals(listOf(Haptic.LightTap), felt.performed)
    }

    @Test
    fun `a button pressed with the pad taps the same as a click`() {
        val ui = open {
            Button("Play", onClick = {}, modifier = Modifier.testTag("play"), initialFocus = true)
        }
        ui.assertFocused("play")

        ui.pad(GamepadButton.South)

        assertEquals(listOf(Haptic.LightTap), felt.performed)
    }

    @Test
    fun `a disabled button gives nothing back`() {
        val ui = open {
            Button("Locked", onClick = {}, modifier = Modifier.testTag("locked"), enabled = false)
        }

        ui.click("locked")

        assertEquals(emptyList(), felt.performed, "a control that did nothing should not feel like it did")
    }

    @Test
    fun `a merely hovered button gives nothing back`() {
        val ui = open { Button("Play", onClick = {}, modifier = Modifier.testTag("play")) }

        ui.moveTo("play")
        ui.press("play")

        assertEquals(emptyList(), felt.performed, "the tap is for the click, which a held press is not yet")

        ui.release()
        assertEquals(listOf(Haptic.LightTap), felt.performed)
    }

    @Test
    fun `a checkbox a toggle and a radio button tap when they change`() {
        val ui = open {
            var ticked by remember { mutableStateOf(false) }
            var on by remember { mutableStateOf(false) }
            var picked by remember { mutableStateOf(false) }
            Column {
                Checkbox(ticked, { ticked = it }, Modifier.testTag("tick"), label = "Subtitles")
                Toggle(on, { on = it }, Modifier.testTag("switch"), label = "Music")
                RadioButton(picked, { picked = true }, Modifier.testTag("radio"), label = "Hard")
                Text("$ticked $on $picked", Modifier.testTag("state"))
            }
        }

        ui.click("tick")
        ui.click("switch")
        ui.click("radio")

        ui.assertText("state", "true true true")
        assertEquals(listOf(Haptic.LightTap, Haptic.LightTap, Haptic.LightTap), felt.performed)
    }

    @Test
    fun `a slider ticks once for every notch the pad moves it and not at the end`() {
        val ui = open {
            var volume by remember { mutableStateOf(80f) }
            Column {
                Slider(
                    volume, { volume = it }, Modifier.testTag("volume"),
                    range = 0f..100f, step = 10f, initialFocus = true,
                )
                Text("volume ${volume.toInt()}", Modifier.testTag("value"))
            }
        }
        ui.assertFocused("volume")

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)
        ui.assertText("value", "volume 100")
        assertEquals(listOf(Haptic.Tick, Haptic.Tick), felt.performed)

        // One more press leaves the slider rather than grinding on the end, and grinding is exactly
        // what a tick there would feel like.
        ui.pad(GamepadButton.DpadRight)
        ui.assertText("value", "volume 100")
        assertEquals(2, felt.performed.size)

        ui.key(Key.Left)
        ui.assertText("value", "volume 90")
        assertEquals(listOf(Haptic.Tick, Haptic.Tick, Haptic.Tick), felt.performed)
    }

    @Test
    fun `a stepped slider ticks for each notch a drag crosses`() {
        val ui = open {
            var level by remember { mutableStateOf(0f) }
            Column {
                Slider(level, { level = it }, Modifier.testTag("level").width(216f), range = 0f..10f, step = 1f)
                Text("level ${level.toInt()}", Modifier.testTag("value"))
            }
        }
        val track = ui.node("level").boundsInRoot
        // 216 wide with a 16 knob leaves 200 of travel, so a notch is 20 pixels.
        fun at(value: Float) = Offset(track.left + 8f + value * 20f, track.centre.y)

        ui.press(at(0f))
        assertEquals(emptyList(), felt.performed, "pressing the knob where it already is changes nothing")

        ui.moveTo(at(0.4f))
        assertEquals(emptyList(), felt.performed, "not far enough to reach the next notch")

        ui.moveTo(at(1f))
        ui.moveTo(at(3f))
        ui.release()

        ui.assertText("value", "level 3")
        // One per change of value: a move that jumps two notches in a frame is one bump, the same
        // as a phone's own picker.
        assertEquals(listOf(Haptic.Tick, Haptic.Tick), felt.performed)
    }

    @Test
    fun `a continuous slider dragged smoothly does not buzz the whole way`() {
        val ui = open {
            var level by remember { mutableStateOf(0f) }
            Slider(level, { level = it }, Modifier.testTag("level").width(216f), range = 0f..1f)
        }
        val track = ui.node("level").boundsInRoot

        ui.press(Offset(track.left + 8f, track.centre.y))
        for (x in 1..10) ui.moveTo(Offset(track.left + 8f + x * 15f, track.centre.y))
        ui.release()

        assertEquals(emptyList(), felt.performed, "a slider with no notches has nothing to tick against")
    }

    @Composable
    private fun Quality(wrap: Boolean = false, enabled: Boolean = true) {
        var quality by remember { mutableStateOf("Medium") }
        Column {
            Stepper(
                options = listOf("Low", "Medium", "High"),
                selected = quality,
                onSelect = { quality = it },
                wrap = wrap,
                enabled = enabled,
                initialFocus = true,
                modifier = Modifier.testTag("quality"),
            )
            Button("Apply", onClick = {}, modifier = Modifier.testTag("apply"))
        }
    }

    /** The middle of one of a stepper's three pieces: 0 the left arrow, 1 the value, 2 the right. */
    private fun UiTest.pieceOf(tag: String, piece: Int): Offset = node(tag).children[piece].boundsInRoot.centre

    /** What a stepper shows, as it is drawn: left arrow, value, right arrow. */
    private fun shows(value: String) = "<\n$value\n>"

    @Test
    fun `a stepper ticks for each option the pad and the keys step to and not at the end`() {
        val ui = open { Quality() }
        ui.assertFocused("quality")

        ui.pad(GamepadButton.DpadRight)
        ui.assertText("quality", shows("High"))
        assertEquals(listOf(Haptic.Tick), felt.performed)

        // Past the end the stepper lets go and focus moves on. Nothing changed, so nothing is felt.
        ui.pad(GamepadButton.DpadRight)
        ui.assertText("quality", shows("High"))
        assertEquals(listOf(Haptic.Tick), felt.performed, "a press that only left the stepper should not tick")

        ui.key(Key.Up)
        ui.assertFocused("quality")
        ui.key(Key.Left)
        ui.key(Key.Left)
        ui.assertText("quality", shows("Low"))
        assertEquals(listOf(Haptic.Tick, Haptic.Tick, Haptic.Tick), felt.performed)
    }

    @Test
    fun `a stepper's arrows value and enter each tick once`() {
        val ui = open { Quality() }

        ui.click(ui.pieceOf("quality", 0))
        ui.assertText("quality", shows("Low"))
        assertEquals(listOf(Haptic.Tick), felt.performed, "the arrow's click is the step, not a second tap")

        ui.click(ui.pieceOf("quality", 1))
        ui.assertText("quality", shows("Medium"))
        ui.key(Key.Enter)
        ui.assertText("quality", shows("High"))
        ui.pad(GamepadButton.South)
        ui.assertText("quality", shows("Low"))

        assertEquals(List(4) { Haptic.Tick }, felt.performed)
    }

    @Test
    fun `holding a stepper's arrow ticks with every repeat and goes quiet at the end`() {
        val ui = open { Quality() }

        ui.press(ui.pieceOf("quality", 0))
        ui.release()
        ui.assertText("quality", shows("Low"))
        assertEquals(listOf(Haptic.Tick), felt.performed)

        ui.press(ui.pieceOf("quality", 2))
        ui.advanceBy(3_000)
        ui.release()

        ui.assertText("quality", shows("High"))
        assertEquals(List(3) { Haptic.Tick }, felt.performed, "two steps up from Low, then a held arrow at the end")
    }

    @Test
    fun `a disabled stepper gives nothing back`() {
        val ui = open { Quality(enabled = false) }

        ui.click(ui.pieceOf("quality", 2))
        ui.click(ui.pieceOf("quality", 1))

        ui.assertText("quality", shows("Medium"))
        assertEquals(emptyList(), felt.performed)
    }

    @Test
    fun `a stick held on a number stepper ticks once per number and stops at the top`() {
        val ui = open {
            var volume by remember { mutableStateOf(6) }
            NumberStepper(
                value = volume,
                onValueChange = { volume = it },
                initialFocus = true,
                modifier = Modifier.testTag("volume"),
            )
        }

        ui.stick(1f, 0f)
        ui.advanceBy(3_000)
        ui.stick(0f, 0f)
        ui.advanceBy(500)

        ui.assertText("volume", shows("10"))
        assertEquals(List(4) { Haptic.Tick }, felt.performed, "6 to 10 is four numbers, and the end is not one")
    }

    @Test
    fun `a wrapping stepper ticks going round and a number between steps ticks landing on one`() {
        val ui = open {
            var volume by remember { mutableStateOf(7) }
            Column {
                Quality(wrap = true)
                NumberStepper(
                    value = volume,
                    onValueChange = { volume = it },
                    range = 0..20,
                    step = 5,
                    modifier = Modifier.testTag("volume"),
                )
            }
        }

        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.assertText("quality", shows("Low"))
        assertEquals(List(2) { Haptic.Tick }, felt.performed, "going round from High is still a step")

        felt.clear()
        ui.click(ui.pieceOf("volume", 0))
        ui.assertText("volume", shows("5"))
        assertEquals(listOf(Haptic.Tick), felt.performed)
    }

    @Test
    fun `a game asks for its own feedback when an action fails`() {
        val ui = open {
            var message by remember { mutableStateOf("") }
            val haptics = LocalHaptics.current
            Column {
                Button(
                    "Buy",
                    onClick = {
                        message = "not enough gold"
                        haptics.perform(Haptic.Failure)
                    },
                    modifier = Modifier.testTag("buy"),
                )
                Text(message, Modifier.testTag("message"))
            }
        }

        ui.click("buy")

        ui.assertText("message", "not enough gold")
        assertEquals(listOf(Haptic.LightTap, Haptic.Failure), felt.performed)
    }

    @Test
    fun `a button keeps tapping after the game swaps its haptics`() {
        val other = RecordingHaptics()
        var useOther by mutableStateOf(false)
        val ui = open {
            ProvideHaptics(if (useOther) other else felt) {
                Button("Play", onClick = {}, modifier = Modifier.testTag("play"))
            }
        }

        ui.click("play")
        useOther = true
        ui.render()
        ui.click("play")

        assertEquals(listOf(Haptic.LightTap), felt.performed)
        assertEquals(listOf(Haptic.LightTap), other.performed, "the old motor was still the one asked")
    }

    @Test
    fun `the backend's haptics are what the screen is given`() {
        var seen: Haptics? = null
        open { seen = LocalHaptics.current }

        assertSame(felt, seen)
    }

    @Test
    fun `a screen nobody wired haptics into still clicks`() {
        // No provider at all, as in a game written before this existed: the default must be a
        // quiet one rather than a crash on the first click.
        var seen: Haptics? = null
        val host = UiHost()
        try {
            host.setContent {
                seen = LocalHaptics.current
                Button(onClick = {}) {}
            }
            host.settle(Viewport.oneToOne(Size(200f, 100f)), nanos = 0L)
            assertSame(Haptics.None, seen)
            Haptics.None.perform(Haptic.HeavyTap)
        } finally {
            host.dispose()
        }
    }

    @Test
    fun `every kind of feedback has a rumble a pad can play`() {
        Haptic.entries.forEach {
            assertTrue(it.durationMillis > 0, "$it lasts no time")
            assertTrue(it.strength > 0f && it.strength <= 1f, "$it has strength ${it.strength}")
        }
        assertTrue(Haptic.LightTap.strength < Haptic.HeavyTap.strength)
        assertTrue(Haptic.Tick.durationMillis < Haptic.Failure.durationMillis)
    }

    @Test
    fun `a recording can be read and cleared`() {
        val recording = RecordingHaptics()
        recording.perform(Haptic.Success)
        recording.perform(Haptic.Warning)
        assertEquals(listOf(Haptic.Success, Haptic.Warning), recording.performed)

        recording.clear()
        assertEquals(emptyList(), recording.performed)
    }
}
