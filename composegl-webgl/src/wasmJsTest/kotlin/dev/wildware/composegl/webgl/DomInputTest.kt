package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.PointerType
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The translators on their own: real DOM events in, the toolkit's events out. */
class DomInputTest {

    // --- keyboard ---

    @Test
    fun `a key is named by where it is and types what the layout printed on it`() = withElement { element ->
        val sink = RecordingSink()
        DomKeyboardInput(sink, isMac = false).attachTo(element)
        // The key where W is on a US keyboard, on a French one, where it types z.
        key(element, "keydown", "z", "KeyW")
        assertEquals(listOf<Any>(KeyEvent(Key.W, KeyEventType.Down), TextEvent("z")), sink.events)
    }

    @Test
    fun `a shortcut types nothing but AltGr does`() = withElement { element ->
        val sink = RecordingSink()
        DomKeyboardInput(sink, isMac = false).attachTo(element)
        key(element, "keydown", "c", "KeyC", control = true)
        assertTrue(sink.events.none { it is TextEvent }, "Control-C is a shortcut: ${sink.events}")
        sink.events.clear()
        key(element, "keydown", "ą", "KeyA", control = true, alt = true)
        assertEquals(TextEvent("ą"), sink.events.last())
    }

    @Test
    fun `named keys arrows and the number pad come through as the toolkit's keys`() = withElement { element ->
        val sink = RecordingSink()
        DomKeyboardInput(sink, isMac = false).attachTo(element)
        key(element, "keydown", "ArrowLeft", "ArrowLeft")
        key(element, "keydown", "7", "Numpad7")
        key(element, "keydown", "F5", "F5")
        key(element, "keyup", "Escape", "Escape")
        key(element, "keydown", "Shift", "ShiftRight", shift = true, repeat = true)
        val keys = sink.events.filterIsInstance<KeyEvent>()
        assertEquals(listOf(Key.Left, Key.Digit7, Key.F5, Key.Escape, Key.Shift), keys.map { it.key })
        assertEquals(KeyEventType.Up, keys[3].type)
        assertTrue(keys[4].repeat)
        assertTrue(keys[4].modifiers.shift)
        assertEquals(listOf(TextEvent("7")), sink.events.filterIsInstance<TextEvent>())
    }

    @Test
    fun `a key an input method is holding is left to it`() = withElement { element ->
        val sink = RecordingSink()
        DomKeyboardInput(sink, isMac = false).attachTo(element)
        key(element, "keydown", "n", "KeyN", composing = true)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a key nothing wanted still reaches the browser`() = withElement { element ->
        val sink = RecordingSink(answer = false)
        DomKeyboardInput(sink, isMac = false).attachTo(element)
        assertTrue(key(element, "keydown", "F5", "F5"), "F5 was not prevented, so the page still reloads")
        assertFalse(key(element, "keydown", "x", "KeyX"), "a letter is prevented, so a text box does not type it twice")
    }

    @Test
    fun `ctrl v with no paste event is still sent when the key comes up`() = withElement { element ->
        val sink = RecordingSink()
        val clipboard = DomClipboard("before")
        DomKeyboardInput(sink, clipboard, isMac = false).attachTo(element)
        key(element, "keydown", "v", "KeyV", control = true)
        assertTrue(sink.events.isEmpty(), "held back for the paste")
        key(element, "keyup", "v", "KeyV", control = true)
        assertEquals(listOf(Key.V, Key.V), sink.events.filterIsInstance<KeyEvent>().map { it.key })
        assertEquals(KeyEventType.Down, (sink.events[0] as KeyEvent).type)
    }

    @Test
    fun `on a mac command is the primary modifier`() = withElement { element ->
        val sink = RecordingSink()
        val clipboard = DomClipboard()
        DomKeyboardInput(sink, clipboard, isMac = true).apply { attachTo(element); listenForPaste(element) }
        key(element, "keydown", "v", "KeyV", meta = true)
        assertTrue(sink.events.isEmpty())
        paste(element, "from the mac")
        assertEquals("from the mac", clipboard.read())
        assertEquals(1, sink.events.size)
        DomKeyboardInput(RecordingSink(), isMac = false)
    }

    // --- pointer ---

    @Test
    fun `a press a move and a release arrive in design units through the viewport`() = withElement { element ->
        val sink = RecordingSink()
        // A 200 by 100 canvas showing a 100 by 50 design: everything halves.
        element.width = 200
        element.height = 100
        element.style.width = "200px"
        element.style.height = "100px"
        val viewport = Viewport(Size(100f, 50f), Size(200f, 100f), ScalePolicy.Fit)
        DomPointerInput(sink, element) { viewport }.attach()
        pointer(element, "pointerdown", 40.0, 20.0, button = 0, buttons = 1)
        pointer(element, "pointermove", 60.0, 30.0, buttons = 1)
        pointer(element, "pointerup", 60.0, 30.0, button = 0)
        val press = sink.events[0] as PointerEvent.Press
        val move = sink.events[1] as PointerEvent.Move
        val release = sink.events[2] as PointerEvent.Release
        assertEquals(Offset(20f, 10f), press.position)
        assertEquals(Offset(30f, 15f), move.position)
        assertEquals(setOf(PointerButton.Primary), move.pressed)
        assertEquals(PointerButton.Primary, release.button)
    }

    @Test
    fun `a second button pressed during a drag is a press of its own`() = withElement { element ->
        val sink = RecordingSink()
        val viewport = Viewport.oneToOne(Size(element.width.toFloat(), element.height.toFloat()))
        DomPointerInput(sink, element) { viewport }.attach()
        pointer(element, "pointerdown", 10.0, 10.0, button = 0, buttons = 1)
        // The browser's chord: the right button goes down during a move, and says so in `buttons`.
        pointer(element, "pointermove", 12.0, 10.0, button = 2, buttons = 3)
        val presses = sink.events.filterIsInstance<PointerEvent.Press>()
        assertEquals(listOf(PointerButton.Primary, PointerButton.Secondary), presses.map { it.button })
        assertEquals(setOf(PointerButton.Primary, PointerButton.Secondary), (sink.events.last() as PointerEvent.Move).pressed)
    }

    @Test
    fun `a wheel notch is one step and two fingers are two pointers`() = withElement { element ->
        val sink = RecordingSink()
        val viewport = Viewport.oneToOne(Size(element.width.toFloat(), element.height.toFloat()))
        DomPointerInput(sink, element) { viewport }.attach()
        assertFalse(wheel(element, 10.0, 10.0, deltaY = 100.0), "a wheel the interface used does not scroll the page")
        assertEquals(Offset(0f, 1f), (sink.events.last() as PointerEvent.Scroll).delta)
        wheel(element, 10.0, 10.0, deltaY = -3.0, deltaMode = 1)
        assertEquals(Offset(0f, -1f), (sink.events.last() as PointerEvent.Scroll).delta)

        sink.events.clear()
        pointer(element, "pointerdown", 10.0, 10.0, pointerType = "touch", pointerId = 7)
        pointer(element, "pointerdown", 50.0, 10.0, pointerType = "touch", pointerId = 8)
        val touches = sink.events.filterIsInstance<PointerEvent.Press>()
        assertEquals(2, touches.map { it.pointerId }.toSet().size)
        assertTrue(touches.all { it.type == PointerType.Touch })
    }

    // --- pads ---

    @Test
    fun `a pad arriving pressing and leaving is reported once each`() {
        val sink = RecordingSink()
        var reading = padSlots()
        val input = DomGamepadInput(sink) { reading }
        input.poll()
        assertTrue(sink.events.isEmpty())

        reading = padSlots(pad(pressed = setOf(0, 12)))
        input.poll()
        assertEquals(
            listOf<Any>(
                GamepadEvent.Connected(GamepadId.First),
                GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South),
                GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.DpadUp),
            ),
            sink.events,
        )
        sink.events.clear()
        input.poll()
        assertTrue(sink.events.isEmpty(), "nothing changed, so nothing is said")

        reading = padSlots(pad(pressed = setOf(12)))
        input.poll()
        assertEquals(listOf<Any>(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South)), sink.events)

        sink.events.clear()
        reading = padSlots(null)
        input.poll()
        assertEquals(listOf<Any>(GamepadEvent.Disconnected(GamepadId.First)), sink.events)
    }

    @Test
    fun `sticks lose their slack and triggers come from their buttons`() {
        val sink = RecordingSink()
        var reading = padSlots(pad(axes = listOf(0.1, 0.6, 0.0, -1.0), values = mapOf(7 to 0.5)))
        DomGamepadInput(sink, deadZone = 0.2f) { reading }.poll()
        val axes = sink.events.filterIsInstance<GamepadEvent.Axis>().associate { it.axis to it.value }
        assertEquals(null, axes[GamepadAxis.LeftX], "inside the dead zone is not a movement")
        assertEquals(0.5f, axes.getValue(GamepadAxis.LeftY), 0.0001f, "0.6 is half way from the dead zone to the end")
        assertEquals(-1f, axes.getValue(GamepadAxis.RightY), 0.0001f)
        assertEquals(0.5f, axes.getValue(GamepadAxis.RightTrigger), 0.0001f)
    }

    @Test
    fun `a pad without the standard layout is left for the game`() {
        val sink = RecordingSink()
        DomGamepadInput(sink) { padSlots(pad(pressed = setOf(0), mapping = "")) }.poll()
        assertTrue(sink.events.isEmpty())
    }

    // --- cursor and haptics ---

    @Test
    fun `the cursor's shapes are the page's cursors`() = withElement { element ->
        val cursor = DomSystemCursor(element)
        cursor.set(PointerIcon.Text)
        assertEquals("text", element.style.cursor)
        cursor.set(PointerIcon.Hand)
        assertEquals("pointer", element.style.cursor)
        cursor.set(PointerIcon.ResizeTopRightBottomLeft)
        assertEquals("nesw-resize", element.style.cursor)
        assertEquals(PointerIcon.ResizeTopRightBottomLeft, cursor.current)
    }

    @Test
    fun `a haptic rumbles the first connected pad`() {
        val rumbling = rumblingPad()
        DomHaptics { padSlots(null, rumbling) }.perform(Haptic.HeavyTap)
        assertEquals("dual-rumble 90 1", rumbleLog(rumbling))
    }

    private fun withElement(test: (org.w3c.dom.HTMLCanvasElement) -> Unit) {
        val element = pageCanvas(100, 100)
        try {
            test(element)
        } finally {
            element.remove()
        }
    }
}

private fun rumblingPad(): JsGamepad = js(
    "(() => { const pad = { index: 1, mapping: 'standard', connected: true, buttons: [], axes: [], log: [] }; pad.vibrationActuator = { playEffect(kind, o) { pad.log.push(kind + ' ' + o.duration + ' ' + o.strongMagnitude); return Promise.resolve('complete'); } }; return pad; })()",
)

private fun rumbleLog(pad: JsGamepad): String = js("pad.log.join('|')")
