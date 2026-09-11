package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.GamepadAxis
import composegl.ui.input.GamepadEvent
import composegl.ui.input.GamepadId
import composegl.ui.input.GamepadNavigator
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.PointerButton
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.layout.Column
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.width
import composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A value the player drags, nudges or pushes a stick at.
 *
 * The three ways in must agree, and the two that go through focus — the arrow keys and the pad —
 * must not move focus while the slider is using them, and must give it up at the ends. That last
 * one is the test that matters on a console: a slider a pad cannot get out of is a menu a player
 * is stuck in.
 */
class SliderTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    /** 200 wide, a 16-wide knob: 184 pixels of travel, and the knob is grabbed by its middle. */
    private val length = 200f
    private val knob = 16f
    private val travel = length - knob

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
    }

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun press(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun drag(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y), setOf(PointerButton.Primary)))

    private fun release(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    /**
     * A press and a release, and then a frame — because a game runs one between key presses, and
     * that frame is what hands the slider the value the last press produced.
     */
    private fun key(key: Key) {
        keys.onKey(KeyEvent(key, KeyEventType.Down))
        keys.onKey(KeyEvent(key, KeyEventType.Up))
        frame()
    }

    private fun node(name: String, from: UiNode = host.root): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { node(name, it) }

    private fun knobRect() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()[2].rect

    private fun fillRect() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()[1].rect

    // --- dragging ----------------------------------------------------------------------------

    @Test
    fun `pressing the track puts the value under the pointer`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, length = length) }

        press(knob / 2f + travel / 2f, 8f)

        assertEquals(0.5f, value, 0.001f, "pressed halfway along, so halfway between the ends")
    }

    @Test
    fun `dragging past the end clamps rather than wrapping`() {
        var value by mutableStateOf(0.5f)
        show { Slider(value, onValueChange = { value = it }, length = length) }

        press(100f, 8f)
        drag(10_000f, 8f)
        assertEquals(1f, value, "far past the right-hand end")

        drag(-10_000f, 8f)
        assertEquals(0f, value, "and far past the left-hand one, rather than round to the top again")

        release(-10_000f, 8f)
    }

    @Test
    fun `a drag that leaves the slider keeps moving it`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, length = length) }

        press(knob / 2f, 8f)
        drag(knob / 2f + travel / 4f, 400f)

        assertEquals(0.25f, value, 0.001f, "the press captured the pointer, so the drag is still ours")
    }

    @Test
    fun `a stepped slider lands only on its steps`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, range = 0f..100f, step = 25f, length = length) }

        press(knob / 2f + travel * 0.3f, 8f)

        assertEquals(25f, value, "30% of the way along is nearer 25 than 50")
    }

    @Test
    fun `a range of its own is reported in its own units`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, range = 20f..40f, length = length) }

        press(knob / 2f + travel / 2f, 8f)

        assertEquals(30f, value, 0.001f)
    }

    @Test
    fun `a disabled slider does not move`() {
        var value by mutableStateOf(0.5f)
        show { Slider(value, onValueChange = { value = it }, enabled = false, length = length) }

        press(knob / 2f, 8f)

        assertEquals(0.5f, value)
    }

    // --- the knob, the track and the fill --------------------------------------------------------

    @Test
    fun `the knob sits where the value says and stays on the track`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, length = length) }
        assertEquals(0f, knobRect().left, "hard against the left, with all of it on the track")

        value = 1f
        frame()
        assertEquals(length, knobRect().right, "and hard against the right at the other end")
        assertEquals(knob, knobRect().width, "the knob never changes size")
    }

    @Test
    fun `the fill reaches the middle of the knob`() {
        var value by mutableStateOf(0.5f)
        show { Slider(value, onValueChange = { value = it }, length = length) }

        assertEquals(knobRect().centre.x, fillRect().right, 0.001f)
    }

    @Test
    fun `a vertical slider has its maximum at the top`() {
        var value by mutableStateOf(1f)
        show {
            Slider(value, onValueChange = { value = it }, orientation = Orientation.Vertical, length = length)
        }
        val top = knobRect().top

        value = 0f
        frame()

        assertTrue(knobRect().top > top, "more is up: ${knobRect()} was below $top")
        assertEquals(length, knobRect().bottom, "and none at all puts the knob at the bottom")
    }

    @Test
    fun `a width given to it wins over its natural length`() {
        show { Slider(0.5f, onValueChange = {}, modifier = Modifier.width(320f), length = length) }

        assertEquals(320f, node("slider")?.width)
    }

    // --- keys and the pad ------------------------------------------------------------------------

    @Test
    fun `an arrow key nudges it by one step`() {
        var value by mutableStateOf(50f)
        show {
            Slider(value, onValueChange = { value = it }, range = 0f..100f, step = 10f, initialFocus = true)
        }
        frame()

        key(Key.Right)
        assertEquals(60f, value)

        key(Key.Left)
        key(Key.Left)
        assertEquals(40f, value)
    }

    @Test
    fun `a continuous slider nudges a twentieth of its range`() {
        var value by mutableStateOf(0f)
        show { Slider(value, onValueChange = { value = it }, initialFocus = true) }
        frame()

        key(Key.Right)

        assertEquals(0.05f, value, 0.001f, "twenty presses from end to end")
    }

    @Test
    fun `nudging does not move focus off the slider`() {
        var value by mutableStateOf(0.5f)
        show {
            Column {
                Slider(value, onValueChange = { value = it }, initialFocus = true)
                Button("NEXT", onClick = {})
            }
        }
        frame()
        val slider = node("slider")

        key(Key.Right)
        key(Key.Left)

        assertEquals(slider, focus.focused, "left and right are this slider's to use, not a way out")
        assertEquals(0.5f, value, 0.001f, "and they went one step each way")
    }

    @Test
    fun `at the end it gives the direction up so the player can leave`() {
        var value by mutableStateOf(1f)
        show {
            Column {
                Slider(value, onValueChange = { value = it }, initialFocus = true)
                Button("NEXT", onClick = {})
            }
        }
        frame()
        val slider = node("slider")

        key(Key.Right)
        assertEquals(slider, focus.focused, "still on the slider: right is its axis")
        assertEquals(1f, value, "and it is already as far right as it goes")

        key(Key.Down)
        assertTrue(focus.focused !== slider, "down is not its axis at all, so focus moved on")
    }

    @Test
    fun `the pad's stick moves it at the repeat rate rather than every frame`() {
        var value by mutableStateOf(0f)
        show {
            Slider(value, onValueChange = { value = it }, range = 0f..100f, step = 10f, initialFocus = true)
        }
        frame()

        pad.onGamepad(GamepadEvent.Axis(GamepadId(0), GamepadAxis.LeftX, 1f))
        frame()
        assertEquals(10f, value, "a fresh push moves at once")

        pad.frame(100L)
        frame()
        assertEquals(10f, value, "and then waits, rather than flying across the range")

        pad.frame(500L)
        frame()
        assertEquals(20f, value)

        pad.onGamepad(GamepadEvent.Axis(GamepadId(0), GamepadAxis.LeftX, 0f))
        pad.frame(2_000L)
        frame()
        assertEquals(20f, value, "letting go stops it")
    }

    @Test
    fun `a vertical slider claims up and down and leaves left and right alone`() {
        var value by mutableStateOf(0.5f)
        show {
            Slider(
                value,
                onValueChange = { value = it },
                orientation = Orientation.Vertical,
                initialFocus = true,
            )
        }
        frame()

        key(Key.Up)
        assertEquals(0.55f, value, 0.001f, "up is more")

        key(Key.Down)
        key(Key.Down)
        assertEquals(0.45f, value, 0.001f)
    }
}
