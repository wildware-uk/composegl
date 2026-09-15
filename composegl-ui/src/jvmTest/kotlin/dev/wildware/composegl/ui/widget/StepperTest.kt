package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `< Medium >`, driven the way a player drives it.
 *
 * Every test composes a real stepper and pushes real input at it — keys through the key navigator,
 * a pad through the pad navigator, a mouse through the pointer router — and then reads what is on
 * the screen. The one that matters most on a console is the same one as for a slider: at the end,
 * the direction is given up, so a player is never stuck in the control.
 */
class StepperTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private val qualities = listOf("Low", "Medium", "High")

    /** The game's clock, in nanoseconds. One frame is a sixtieth of a second. */
    private var clock = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
        frame()
    }

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += Frame
        MeasurePass().run(host.root, Constraints.atMost(600f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    /** Frames for [millis] of game time. */
    private fun run(millis: Long) = repeat((millis * 1_000_000L / Frame).toInt()) { frame() }

    private fun key(key: Key, repeat: Boolean = false) {
        keys.onKey(KeyEvent(key, KeyEventType.Down, repeat = repeat))
        if (!repeat) keys.onKey(KeyEvent(key, KeyEventType.Up))
        frame()
    }

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>()

    /** What the value says: the text drawn inside the middle of the stepper's three pieces. */
    private fun shown(): String {
        val value = stepper.children[1].boundsInRoot
        return texts().filter { it.at in value }.joinToString("") { it.text }
    }

    private fun arrowColour(glyph: String) = texts().single { it.text == glyph }.colour

    private val stepper get() = host.root.find("stepper")

    /** The middle of the left arrow, the value, or the right arrow, in screen units. */
    private fun centreOf(piece: Int): Offset = stepper.children[piece].boundsInRoot.centre

    private fun press(at: Offset) = pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))

    private fun release(at: Offset) = pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))

    @Composable
    private fun Quality(
        selected: String,
        onSelect: (String) -> Unit,
        wrap: Boolean = false,
        enabled: Boolean = true,
    ) = Stepper(
        options = qualities,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.testTag("stepper"),
        wrap = wrap,
        enabled = enabled,
        initialFocus = true,
    )

    // --- keys ----------------------------------------------------------------------------------

    @Test
    fun `right and left step through the options and the value shown follows`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }
        assertEquals("Low", shown())

        key(Key.Right)
        assertEquals("Medium", quality)
        assertEquals("Medium", shown())

        key(Key.Right)
        assertEquals("High", shown())

        key(Key.Left)
        assertEquals("Medium", quality)
        assertEquals("Medium", shown())
    }

    @Test
    fun `stepping keeps focus on the stepper`() {
        var quality by mutableStateOf("Medium")
        show {
            Row {
                Quality(quality, onSelect = { quality = it })
                Button("APPLY", onClick = {})
            }
        }
        val stepper = stepper
        assertSame(stepper, focus.focused)

        key(Key.Right)

        assertSame(stepper, focus.focused, "right was the stepper's to use, not a way to the button")
        assertEquals("High", quality)
    }

    @Test
    fun `at the last option right leaves the control instead of grinding`() {
        var quality by mutableStateOf("High")
        show {
            Row {
                Quality(quality, onSelect = { quality = it })
                Button("APPLY", onClick = {}, modifier = Modifier.testTag("apply"))
            }
        }

        key(Key.Right)

        assertEquals("High", quality, "there is nothing past High")
        assertSame(host.root.find("apply"), focus.focused, "so the press went to the neighbour")
    }

    @Test
    fun `a wrapping stepper goes round both ways and keeps focus`() {
        var quality by mutableStateOf("High")
        show {
            Row {
                Quality(quality, onSelect = { quality = it }, wrap = true)
                Button("APPLY", onClick = {})
            }
        }
        val stepper = stepper

        key(Key.Right)
        assertEquals("Low", quality)
        assertEquals("Low", shown())

        key(Key.Left)
        assertEquals("High", quality)
        assertSame(stepper, focus.focused)
    }

    @Test
    fun `up and down are not the stepper's so focus moves`() {
        var quality by mutableStateOf("Medium")
        show {
            Column {
                Quality(quality, onSelect = { quality = it })
                Button("APPLY", onClick = {}, modifier = Modifier.testTag("apply"))
            }
        }

        key(Key.Down)

        assertEquals("Medium", quality)
        assertSame(host.root.find("apply"), focus.focused)
    }

    @Test
    fun `a held key repeats one step per repeat the platform sends`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }

        keys.onKey(KeyEvent(Key.Right, KeyEventType.Down))
        frame()
        assertEquals("Medium", quality)

        key(Key.Right, repeat = true)
        assertEquals("High", quality, "the platform's repeat is one more step")

        key(Key.Right, repeat = true)
        assertEquals("High", quality, "and at the end a repeat does nothing more")
    }

    @Test
    fun `enter moves to the next option and goes round at the end`() {
        var quality by mutableStateOf("Medium")
        show { Quality(quality, onSelect = { quality = it }) }

        key(Key.Enter)
        assertEquals("High", quality)

        key(Key.Enter)
        assertEquals("Low", quality, "a press that did nothing on the last option would look broken")
    }

    // --- the pad -------------------------------------------------------------------------------

    @Test
    fun `a held d-pad steps at once then waits then repeats`() {
        var volume by mutableStateOf(0)
        show {
            NumberStepper(
                value = volume,
                onValueChange = { volume = it },
                range = 0..10,
                modifier = Modifier.testTag("stepper"),
                initialFocus = true,
            )
        }

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId(0), GamepadButton.DpadRight))
        frame()
        assertEquals(1, volume, "a fresh push moves at once")
        assertEquals("1", shown())

        pad.frame(300L)
        frame()
        assertEquals(1, volume, "then waits, rather than flying through the values")

        pad.frame(400L)
        frame()
        assertEquals(2, volume)
        pad.frame(510L)
        frame()
        assertEquals(3, volume, "and then steps at the repeat rate")

        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId(0), GamepadButton.DpadRight))
        pad.frame(3_000L)
        frame()
        assertEquals(3, volume, "letting go stops it")
        assertEquals("3", shown())
    }

    @Test
    fun `the pad's south button cycles forward`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId(0), GamepadButton.South))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId(0), GamepadButton.South))
        frame()

        assertEquals("Medium", quality)
    }

    // --- the pointer -----------------------------------------------------------------------------

    @Test
    fun `clicking an arrow steps once and focuses the stepper`() {
        var quality by mutableStateOf("Medium")
        show {
            Column {
                Button("BACK", onClick = {}, initialFocus = true, modifier = Modifier.testTag("back"))
                Stepper(qualities, quality, onSelect = { quality = it }, modifier = Modifier.testTag("stepper"))
            }
        }
        assertSame(host.root.find("back"), focus.focused)

        val right = centreOf(2)
        press(right)
        frame()
        release(right)
        frame()

        assertEquals("High", quality, "one step, and the release is not a second one")
        assertSame(stepper, focus.focused, "clicking it is reaching for it")

        val left = centreOf(0)
        press(left)
        release(left)
        frame()
        assertEquals("Medium", quality)
    }

    @Test
    fun `holding an arrow down repeats on the frame clock until it is let go`() {
        var volume by mutableStateOf(0)
        show {
            NumberStepper(volume, onValueChange = { volume = it }, range = 0..20, modifier = Modifier.testTag("stepper"))
        }
        val right = centreOf(2)

        press(right)
        frame()
        assertEquals(1, volume, "the press is a step straight away")

        run(300)
        assertEquals(1, volume, "then a pause, so a click is only ever one")

        run(250)
        assertTrue(volume >= 2, "past the pause it repeats: $volume")
        val before = volume
        run(330)
        assertTrue(volume >= before + 2, "about every tenth of a second: $before became $volume")

        release(right)
        frame()
        val lettingGo = volume
        run(1_000)
        assertEquals(lettingGo, volume, "letting go stops it")
        assertEquals("$volume", shown())
    }

    @Test
    fun `a held arrow stops at the end of the range`() {
        var volume by mutableStateOf(8)
        show {
            NumberStepper(volume, onValueChange = { volume = it }, range = 0..10, modifier = Modifier.testTag("stepper"))
        }
        val right = centreOf(2)

        press(right)
        frame()
        run(2_000)
        release(right)
        frame()

        assertEquals(10, volume)
    }

    @Test
    fun `clicking the value moves to the next option`() {
        var quality by mutableStateOf("High")
        show { Stepper(qualities, quality, onSelect = { quality = it }, modifier = Modifier.testTag("stepper")) }

        val middle = centreOf(1)
        press(middle)
        release(middle)
        frame()

        assertEquals("Low", quality)
    }

    @Test
    fun `an arrow press let go outside does not swallow the next enter`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }

        press(centreOf(2))
        release(Offset(590f, 390f))
        frame()
        assertEquals("Medium", quality)

        key(Key.Enter)
        assertEquals("High", quality, "Enter is its own press, not the tail of the mouse's")
    }

    // --- how it looks -----------------------------------------------------------------------------

    @Test
    fun `the arrow at an end it cannot pass is drawn disabled`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }
        val blocked = arrowColour("<")
        val open = arrowColour(">")
        assertNotEquals(blocked, open, "nothing left of Low, so the left arrow says so")

        key(Key.Right)
        assertEquals(open, arrowColour("<"), "in the middle both are live")
        assertEquals(open, arrowColour(">"))

        key(Key.Right)
        assertEquals(blocked, arrowColour(">"), "and at High it is the right one")
    }

    @Test
    fun `a wrapping stepper never dims an arrow`() {
        show { Quality("Low", onSelect = {}, wrap = true) }

        assertEquals(arrowColour(">"), arrowColour("<"))
    }

    @Test
    fun `the value is as wide as the widest option so the arrows stay put`() {
        var quality by mutableStateOf("Low")
        show { Quality(quality, onSelect = { quality = it }) }
        val width = stepper.width
        val rightArrow = stepper.children[2].boundsInRoot

        quality = "Medium"
        frame()

        assertEquals(width, stepper.width)
        assertEquals(rightArrow, stepper.children[2].boundsInRoot)
        val medium = MonospaceFontProvider().measure("Medium").size.width
        assertTrue(stepper.children[1].width >= medium, "Medium fits: ${stepper.children[1].width} < $medium")
    }

    @Test
    fun `a width given to it goes to the value`() {
        show {
            Stepper(qualities, "Low", onSelect = {}, modifier = Modifier.testTag("stepper").width(300f))
        }

        assertEquals(300f, stepper.width)
        val outer = stepper.boundsInRoot
        val left = stepper.children[0].boundsInRoot
        val value = stepper.children[1].boundsInRoot
        val right = stepper.children[2].boundsInRoot
        assertEquals(left.right, value.left, 0.01f, "the value starts where the left arrow ends")
        assertEquals(value.right, right.left, 0.01f, "and the right arrow starts where it ends")
        assertEquals(left.left - outer.left, outer.right - right.right, 0.01f, "arrows at the edges, evenly in")
        assertTrue(value.width > 200f, "the room went to the value: ${value.width}")
    }

    @Test
    fun `a disabled stepper ignores keys and the pointer`() {
        var quality by mutableStateOf("Medium")
        show {
            Column {
                Button("BACK", onClick = {}, initialFocus = true)
                Quality(quality, onSelect = { quality = it }, enabled = false)
            }
        }

        key(Key.Right)
        press(centreOf(2))
        release(centreOf(2))
        frame()

        assertEquals("Medium", quality)
    }

    // --- numbers ---------------------------------------------------------------------------------

    @Test
    fun `a number stepper moves by its step and stays in its range`() {
        var value by mutableStateOf(90)
        show {
            NumberStepper(
                value = value,
                onValueChange = { value = it },
                range = 0..100,
                step = 5,
                format = { "$it%" },
                modifier = Modifier.testTag("stepper"),
                initialFocus = true,
            )
        }
        assertEquals("90%", shown())

        key(Key.Right)
        key(Key.Right)
        assertEquals(100, value)
        assertEquals("100%", shown())

        key(Key.Right)
        assertEquals(100, value, "never past the top")

        repeat(25) { key(Key.Left) }
        assertEquals(0, value, "nor under the bottom")
    }

    @Test
    fun `a number off its steps lands on one when nudged`() {
        var value by mutableStateOf(7)
        show {
            NumberStepper(value, onValueChange = { value = it }, range = 0..20, step = 5, initialFocus = true)
        }

        key(Key.Right)

        assertEquals(10, value)
    }

    @Test
    fun `a number stepper refuses a step that goes nowhere`() {
        assertThrows(IllegalArgumentException::class.java) {
            show { NumberStepper(0, onValueChange = {}, step = 0) }
        }
    }

    private companion object {
        const val Frame = 16_666_667L
    }
}
