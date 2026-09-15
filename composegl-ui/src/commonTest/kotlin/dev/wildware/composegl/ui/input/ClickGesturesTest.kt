package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.repeatingClickable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Long press, double click and hold-to-repeat, on real composed widgets driven the way a player
 * drives them: a pointer going down and staying down while frames go by, Enter held on a focused
 * button, the pad's South button.
 *
 * Every hold here is frames on the host's clock, never a sleep. That is the feature — the timings
 * run on a [Clock] — and it is also what makes a two-second hold a test that takes no time.
 */
class ClickGesturesTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root, autoFocus = false)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)
    private val canvas = RecordingCanvas()
    private var clock = 0L

    private var clicks by mutableStateOf(0)
    private var doubles by mutableStateOf(0)
    private var longPresses by mutableStateOf(0)

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(8) {
            if (!frame()) return
        }
        throw AssertionError("the screen never settled")
    }

    /** One turn of a game loop. True when something changed. */
    private fun frame(): Boolean {
        clock += FrameNanos
        return host.settle(Constraints.atMost(400f, 400f), focus, nanos = clock)
    }

    /** Frames until [millis] have gone by on the host's clock. */
    private fun waitMillis(millis: Int) {
        repeat(((millis * 1_000_000L + FrameNanos - 1) / FrameNanos).toInt()) { frame() }
    }

    /** What the screen actually draws, as text. */
    private fun drawnText(): List<String> {
        canvas.clear()
        DrawPass(canvas).draw(host.root)
        return canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }
    }

    private fun centreOf(tag: String): Offset = host.root.find(tag).boundsInRoot.centre

    private fun press(tag: String) = pointer.onPointer(PointerEvent.Press(PointerId.Mouse, centreOf(tag)))

    private fun release(tag: String) = pointer.onPointer(PointerEvent.Release(PointerId.Mouse, centreOf(tag)))

    private fun click(tag: String) {
        press(tag)
        frame()
        release(tag)
        frame()
    }

    private fun moveTo(at: Offset) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at, setOf(PointerButton.Primary)))

    /** An item slot: tap selects, double click equips, a long press opens its actions. */
    @Composable
    private fun Item(tag: String = "item", clock: Clock = Clock.Ui) {
        Box(
            Modifier.testTag(tag).size(120f, 40f).focusable().clickable(
                onDoubleClick = { doubles++ },
                onLongPress = { longPresses++ },
                clock = clock,
            ) { clicks++ },
        ) {
            Text("c$clicks d$doubles l$longPresses")
        }
    }

    // --- long press ----------------------------------------------------------------------------

    @Test
    fun `a held press long presses once while still held and the release is not a click`() {
        show { Item() }

        press("item")
        waitMillis(400)
        assertEquals(0, longPresses, "not yet: 400ms is under the 500ms a long press needs")

        waitMillis(120)
        assertEquals(1, longPresses, "fired while the pointer is still down")
        assertTrue("c0 d0 l1" in drawnText(), "and the screen shows it: ${drawnText()}")

        waitMillis(1_000)
        assertEquals(1, longPresses, "once per press, however long it is held")

        release("item")
        frame()
        assertEquals(0, clicks, "the release that ends a long press is not also a click")
    }

    @Test
    fun `a short press is a click and never becomes a long press`() {
        show { Item() }

        press("item")
        waitMillis(200)
        release("item")
        waitMillis(1_000)

        assertEquals(1, clicks)
        assertEquals(0, longPresses, "the timer stopped when the press came up")
        assertTrue("c1 d0 l0" in drawnText(), "${drawnText()}")
    }

    @Test
    fun `sliding off before the long press is a change of mind even after coming back`() {
        show { Item() }
        val centre = centreOf("item")

        press("item")
        waitMillis(200)
        moveTo(Offset(390f, 390f))
        waitMillis(100)
        moveTo(centre)
        waitMillis(1_000)

        assertEquals(0, longPresses, "the player moved away, so this press cannot be a long press")
        release("item")
        frame()
        assertEquals(1, clicks, "released back on the node, it is an ordinary click")
    }

    @Test
    fun `a long press on the world clock waits while the game is paused`() {
        show { Item(clock = Clock.World) }

        press("item")
        waitMillis(300)
        host.clocks.stop(Clock.World)
        waitMillis(3_000)
        assertEquals(0, longPresses, "three seconds of pause is no time at all to the world")

        host.clocks.start(Clock.World)
        waitMillis(150)
        assertEquals(0, longPresses, "300ms before the pause and 150ms after is still under 500ms")
        waitMillis(100)
        assertEquals(1, longPresses)
    }

    @Test
    fun `a pointer cancel mid hold fires nothing`() {
        show { Item() }

        press("item")
        waitMillis(200)
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, centreOf("item")))
        waitMillis(1_000)

        assertEquals(0, longPresses)
        assertEquals(0, clicks)
    }

    @Test
    fun `a node taken off the screen mid hold never long presses`() {
        var visible by mutableStateOf(true)
        show { if (visible) Item() }

        press("item")
        waitMillis(200)
        visible = false
        waitMillis(1_000)

        assertEquals(0, longPresses, "there is nothing left for the long press to happen to")
    }

    @Test
    fun `holding the pad South button on a focused item long presses it`() {
        show { Item() }
        focus.focusOn(host.root.find("item"))

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        waitMillis(520)
        assertEquals(1, longPresses, "South held is the same press a finger held is")

        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        frame()
        assertEquals(0, clicks, "and letting go of it after is not a click")
    }

    // --- double click --------------------------------------------------------------------------

    @Test
    fun `two quick clicks are a click and then a double click`() {
        show { Item() }

        click("item")
        assertEquals(1, clicks, "the first click happens at once, not after a wait")
        click("item")

        assertEquals(1, clicks, "the second click is the double click rather than another click")
        assertEquals(1, doubles)
        assertTrue("c1 d1 l0" in drawnText(), "${drawnText()}")
    }

    @Test
    fun `two clicks further apart than the window are two clicks`() {
        show { Item() }

        click("item")
        waitMillis(400)
        click("item")

        assertEquals(2, clicks)
        assertEquals(0, doubles)
    }

    @Test
    fun `a third quick click is an ordinary click again`() {
        show { Item() }

        click("item")
        click("item")
        click("item")
        click("item")

        assertEquals(2, clicks, "click and double and click and double")
        assertEquals(2, doubles)
    }

    @Test
    fun `a click on one item then another is two clicks and no double`() {
        show {
            Column {
                Item("first")
                Item("second")
            }
        }

        click("first")
        click("second")

        assertEquals(0, doubles, "a double click is two clicks on the same thing")
    }

    @Test
    fun `a change of mind between two clicks breaks the double`() {
        show { Item() }

        click("item")
        press("item")
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(390f, 390f)))
        frame()
        click("item")

        assertEquals(0, doubles, "the release off the node was not a click, so this one is a first")
        assertEquals(2, clicks)
    }

    @Test
    fun `a double click waits with its clock`() {
        show { Item(clock = Clock.World) }

        click("item")
        host.clocks.stop(Clock.World)
        waitMillis(2_000)
        host.clocks.start(Clock.World)
        click("item")

        assertEquals(1, doubles, "no world time passed between the two clicks")
    }

    @Test
    fun `Enter pressed twice quickly double clicks the focused item`() {
        show { Item() }
        focus.focusOn(host.root.find("item"))

        repeat(2) {
            keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
            frame()
            keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
            frame()
        }

        assertEquals(1, clicks)
        assertEquals(1, doubles)
    }

    // --- hold to repeat ------------------------------------------------------------------------

    private var count by mutableStateOf(0)

    @Composable
    private fun Picker() {
        Column {
            Box(Modifier.testTag("plus").size(60f, 40f).focusable().repeatingClickable { count++ }) {
                Text("+")
            }
            Box(Modifier.testTag("other").size(60f, 40f).focusable().clickable {}) {
                Text("x")
            }
            Text("count $count")
        }
    }

    @Test
    fun `holding a repeating button steps the count on the clock`() {
        show { Picker() }

        press("plus")
        waitMillis(350)
        assertEquals(0, count, "the first repeat waits 400ms, so a player has time to let go")

        waitMillis(60)
        assertEquals(1, count)

        waitMillis(240)
        assertEquals(5, count, "then one every 60ms: at 400 460 520 580 and 640")
        assertTrue("count 5" in drawnText(), "${drawnText()}")

        release("plus")
        waitMillis(500)
        assertEquals(5, count, "letting go of a hold that already stepped adds nothing and stops it")
    }

    @Test
    fun `a tap on a repeating button is exactly one step`() {
        show { Picker() }

        click("plus")
        waitMillis(1_000)

        assertEquals(1, count)
        assertTrue("count 1" in drawnText(), "${drawnText()}")
    }

    @Test
    fun `sliding off pauses the repeat and coming back resumes it`() {
        show { Picker() }
        val plus = centreOf("plus")

        press("plus")
        waitMillis(410)
        assertEquals(1, count)

        moveTo(Offset(390f, 390f))
        waitMillis(1_000)
        assertEquals(1, count, "nothing repeats while the pointer is off the button")

        moveTo(plus)
        waitMillis(20)
        assertEquals(2, count, "back on it, the step that was due happens straight away")
    }

    @Test
    fun `a stalled frame steps once rather than bursting`() {
        show { Picker() }

        press("plus")
        waitMillis(410)
        assertEquals(1, count)

        clock += 2_000_000_000L
        frame()
        assertEquals(2, count, "two seconds in one frame is one step, not thirty")
    }

    @Test
    fun `holding Enter on a focused repeating button repeats and ignores key repeat`() {
        show { Picker() }
        focus.focusOn(host.root.find("plus"))

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        repeat(40) {
            // The platform's own key repeat, arriving every frame. It must not add steps of its own.
            keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down, repeat = true))
            frame()
        }
        val held = count
        assertTrue(held in 5..6, "40 frames is ~667ms: 400ms, then every 60ms, was $held")

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        waitMillis(500)
        assertEquals(held, count)
    }

    @Test
    fun `moving focus off a held repeating button stops it stepping`() {
        show { Picker() }
        focus.focusOn(host.root.find("plus"))

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        waitMillis(410)
        assertEquals(1, count)

        focus.focusOn(host.root.find("other"))
        waitMillis(1_000)
        assertEquals(1, count, "focus went elsewhere, which is sliding a finger off")

        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        frame()
        assertEquals(1, count)
    }

    // --- what it costs -------------------------------------------------------------------------

    @Test
    fun `a still screen stays free while a plain button is held`() {
        show {
            Box(Modifier.testTag("plain").size(60f, 40f).clickable { clicks++ }) { Text("go") }
        }

        press("plain")
        frame()
        repeat(60) { assertFalse(frame(), "nothing is timed, so a held press changes nothing") }
        release("plain")
        frame()
        assertEquals(1, clicks)
    }

    @Test
    fun `a screen goes still again once a long press has fired`() {
        show { Item() }

        press("item")
        waitMillis(520)
        assertEquals(1, longPresses)
        frame()

        repeat(30) { assertFalse(frame(), "the long press is spent and nothing waits on the clock") }
    }

    @Test
    fun `timings that are not positive are refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.clickable(longPressMillis = 0) {} }
        assertFailsWith<IllegalArgumentException> { Modifier.clickable(doubleClickMillis = -1) {} }
        assertFailsWith<IllegalArgumentException> { Modifier.repeatingClickable(intervalMillis = 0) {} }
        assertFailsWith<IllegalArgumentException> { Modifier.repeatingClickable(initialDelayMillis = 0) {} }
    }

    private companion object {
        const val FrameNanos = 16_666_667L
    }
}
