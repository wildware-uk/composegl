package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Shake: a knock that moves a widget about and dies away on its own.
 *
 * Every test here composes a real screen — a password panel with a button beside it — clicks the
 * button with a pointer, runs frames on the host's clocks, and looks at where the panel ended up:
 * its box, what was drawn, and whether the frames stopped once it was still.
 */
class ShakeTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private var canvas = RecordingCanvas(Rect(0f, 0f, 400f, 200f))
    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        val changed = host.frame(wall)
        canvas.clear(Rect(0f, 0f, 400f, 200f))
        MeasurePass().run(host.root, Constraints.atMost(400f, 200f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    /** The screen the issue names: a wrong password shakes the panel it was typed into. */
    private fun passwordScreen(shake: @Composable () -> ShakeState, content: @Composable (ShakeState) -> Unit = {}) {
        host.setContent {
            val state = shake()
            Row(Modifier.padding(left = 20f, top = 40f)) {
                Box(Modifier.testTag("panel").shake(state).size(120f, 40f).background(PanelColour))
                Box(Modifier.testTag("submit").size(60f, 40f).background(ButtonColour).clickable { state.trigger() })
                content(state)
            }
        }
        frames(2)
    }

    private fun panel() = host.root.find("panel").boundsInRoot
    private fun submit() = host.root.find("submit").boundsInRoot

    private fun drawnPanel(): Rect =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == PanelColour }.rect

    private val slot = Rect(20f, 40f, 140f, 80f)

    // --- still -----------------------------------------------------------------------------------

    @Test
    fun `a panel nobody has knocked sits in its slot and costs no frames`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake().also { shake = it } })

        assertEquals(slot, panel())
        assertEquals(Offset.Zero, shake.offset)
        assertEquals(0f, shake.trauma)
        repeat(10) { assertFalse(frame(), "a still shake must not ask for redraws") }
    }

    // --- a knock ---------------------------------------------------------------------------------

    @Test
    fun `clicking the button shakes the panel without moving the button beside it`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake().also { shake = it } })
        val button = submit()

        click(button.centre)
        assertEquals(1f, shake.trauma, "a click at full intensity")

        var furthest = 0f
        repeat(20) {
            frame()
            val moved = panel()
            furthest = max(furthest, max(abs(moved.left - slot.left), abs(moved.top - slot.top)))
            assertEquals(slot.width, moved.width, 0.001f, "shaking moves it and never resizes it")
            assertEquals(slot.height, moved.height, 0.001f)
            assertEquals(moved, drawnPanel(), "what is drawn is where the box is")
            assertEquals(button, submit(), "layout does not move, so the neighbour stays put")
        }
        assertTrue(furthest > 3f, "a full knock should visibly move the panel, and it went $furthest")
    }

    @Test
    fun `the shake dies away and the panel lands exactly back in its slot`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake(decayPerSecond = 2f).also { shake = it } })

        click(submit().centre)
        frames(5)
        assertNotEquals(slot, panel(), "it is moving part way through")
        assertTrue(shake.trauma in 0.5f..0.99f, "trauma drains with time, and was ${shake.trauma}")

        frames(40)
        assertEquals(0f, shake.trauma)
        assertEquals(Offset.Zero, shake.offset)
        assertEquals(slot, panel(), "back exactly where layout put it")
        assertEquals(slot, drawnPanel())

        frame()
        repeat(10) { assertFalse(frame(), "a shake that has died away costs nothing") }
    }

    @Test
    fun `a small knock moves far less than a big one`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake(maxOffset = 20f).also { shake = it } })

        fun peak(intensity: Float): Float {
            shake.trigger(intensity)
            var furthest = 0f
            repeat(60) {
                frame()
                val moved = panel()
                furthest = max(furthest, max(abs(moved.left - slot.left), abs(moved.top - slot.top)))
            }
            assertEquals(slot, panel(), "and it settled before the next knock")
            return furthest
        }

        val small = peak(0.5f)
        val big = peak(1f)

        // Offset is trauma squared: half a knock is a quarter of the movement, not half of it.
        assertTrue(small > 0f, "half a knock still moves")
        assertTrue(small <= 20f * 0.25f + 0.01f, "half a knock is at most a quarter of the reach, and went $small")
        assertTrue(big > 20f * 0.25f, "a full knock goes further than a quarter of the reach, and went $big")
        assertTrue(big <= 20f + 0.01f, "and never past the reach, and went $big")
    }

    @Test
    fun `knocks add up but never past full`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake().also { shake = it } })

        shake.trigger(0.3f)
        assertEquals(0.3f, shake.trauma, 0.0001f)
        shake.trigger(0.3f)
        assertEquals(0.6f, shake.trauma, 0.0001f)

        click(submit().centre)
        click(submit().centre)
        assertEquals(1f, shake.trauma, "two full knocks are still only full")
    }

    @Test
    fun `knocking again part way through keeps it going`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake(decayPerSecond = 2f).also { shake = it } })

        click(submit().centre)
        frames(25)
        val before = shake.trauma
        click(submit().centre)
        frames(10)
        assertTrue(shake.trauma > before - 0.01f, "the second knock topped it up: ${shake.trauma} after $before")
        assertNotEquals(Offset.Zero, shake.offset)

        frames(60)
        assertEquals(slot, panel())
    }

    @Test
    fun `stop puts the panel straight back`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake().also { shake = it } })

        click(submit().centre)
        frames(4)
        assertNotEquals(slot, panel())

        shake.stop()
        assertEquals(Offset.Zero, shake.offset, "straight back, not after the next frame's step")
        assertEquals(0f, shake.trauma)
        frame()
        assertEquals(slot, panel())
        assertEquals(slot, drawnPanel())
        repeat(5) { assertFalse(frame()) }
    }

    // --- clocks ----------------------------------------------------------------------------------

    @Test
    fun `a world shake freezes while the game is paused and carries on after`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake(clock = Clock.World).also { shake = it } })

        click(submit().centre)
        frames(4)
        host.clocks.stop(Clock.World)
        // One frame for the last offset it wrote before the clock stopped to reach layout.
        frame()
        val frozen = panel()
        val trauma = shake.trauma
        assertNotEquals(slot, frozen)

        repeat(20) { assertFalse(frame(), "a paused shake does not redraw") }
        assertEquals(frozen, panel(), "and holds still where it was")
        assertEquals(trauma, shake.trauma, "with none of its trauma gone")

        host.clocks.start(Clock.World)
        frames(80)
        assertEquals(0f, shake.trauma)
        assertEquals(slot, panel())
    }

    @Test
    fun `a ui shake carries on while the world is paused`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake().also { shake = it } })
        host.clocks.stop(Clock.World)

        click(submit().centre)
        frames(80)
        assertEquals(0f, shake.trauma)
        assertEquals(slot, panel())
    }

    // --- nesting and leaving ---------------------------------------------------------------------

    @Test
    fun `a shaken panel inside a shaken frame moves by both`() {
        lateinit var outer: ShakeState
        lateinit var inner: ShakeState
        host.setContent {
            outer = rememberShake(seed = 1)
            inner = rememberShake(seed = 2)
            Box(Modifier.padding(left = 40f, top = 40f).testTag("frame").shake(outer).size(200f, 100f)) {
                Box(Modifier.testTag("panel").shake(inner).size(120f, 40f).background(PanelColour))
            }
        }
        frames(2)
        val frameSlot = host.root.find("frame").boundsInRoot
        val panelSlot = panel()

        outer.trigger()
        inner.trigger()
        var frameWent = 0f
        var panelWentInside = 0f
        repeat(20) {
            frame()
            val frameMoved = host.root.find("frame").boundsInRoot
            val panelMoved = panel()
            // Where the panel is inside its frame: the frame's shake is carried along, so only the
            // panel's own shake is left, and that never goes past its reach.
            val insideX = (panelMoved.left - frameMoved.left) - (panelSlot.left - frameSlot.left)
            val insideY = (panelMoved.top - frameMoved.top) - (panelSlot.top - frameSlot.top)
            assertTrue(abs(insideX) <= 12.01f && abs(insideY) <= 12.01f, "the panel moves with its frame: $insideX, $insideY")
            frameWent = max(frameWent, max(abs(frameMoved.left - frameSlot.left), abs(frameMoved.top - frameSlot.top)))
            panelWentInside = max(panelWentInside, max(abs(insideX), abs(insideY)))
        }
        assertTrue(frameWent > 3f, "the frame shook, and went $frameWent")
        assertTrue(panelWentInside > 3f, "and the panel shook inside it on its own seed, and went $panelWentInside")

        frames(60)
        assertEquals(frameSlot, host.root.find("frame").boundsInRoot)
        assertEquals(panelSlot, panel())
    }

    @Test
    fun `a panel taken off the screen mid shake lets its frames go and comes back still`() {
        var showing by mutableStateOf(true)
        val made = mutableListOf<ShakeState>()
        host.setContent {
            if (showing) {
                val state = rememberShake()
                if (state !in made) made += state
                Box(Modifier.padding(left = 20f, top = 40f)) {
                    Box(Modifier.testTag("panel").shake(state).size(120f, 40f).background(PanelColour))
                }
            }
        }
        frames(2)
        made.single().trigger()
        frames(4)
        assertTrue(host.clocks.isAnimating, "it is shaking before it leaves")

        showing = false
        frames(2)
        assertFalse(host.clocks.isAnimating, "a shake that left the screen is no longer playing")
        repeat(10) { assertFalse(frame(), "and asks for no more frames") }

        showing = true
        frames(2)
        assertEquals(2, made.size, "coming back is a new shake")
        assertEquals(0f, made.last().trauma)
        assertEquals(slot, panel(), "sitting still in its slot")
    }

    @Test
    fun `a shake with no reach never moves and a zero sized panel shakes without trouble`() {
        lateinit var still: ShakeState
        lateinit var tiny: ShakeState
        host.setContent {
            still = rememberShake(maxOffset = 0f)
            tiny = rememberShake()
            Row(Modifier.padding(left = 20f, top = 40f)) {
                Box(Modifier.testTag("panel").shake(still).size(120f, 40f).background(PanelColour))
                Box(Modifier.testTag("tiny").shake(tiny).size(0f, 0f))
            }
        }
        frames(2)
        val tinySlot = host.root.find("tiny").boundsInRoot

        still.trigger()
        tiny.trigger()
        repeat(10) {
            frame()
            assertEquals(slot, panel(), "no reach is no movement")
        }
        assertTrue(still.trauma in 0.01f..0.99f, "though the trauma still drains, and was ${still.trauma}")
        assertEquals(0f, host.root.find("tiny").boundsInRoot.width)
        frames(60)
        assertEquals(tinySlot, host.root.find("tiny").boundsInRoot)
    }

    @Test
    fun `a shake counts as playing on its clock until it settles`() {
        lateinit var shake: ShakeState
        passwordScreen({ rememberShake(clock = Clock.World).also { shake = it } })
        assertFalse(host.clocks.isAnimating)

        click(submit().centre)
        frames(3)
        assertTrue(host.clocks.isAnimating, "a shake is an animation playing")

        host.clocks.stop(Clock.World)
        assertFalse(host.clocks.isAnimating, "but not one anybody can wait for while its clock is stopped")
        host.clocks.start(Clock.World)

        frames(60)
        assertEquals(0f, shake.trauma)
        assertFalse(host.clocks.isAnimating, "and it stops playing once it is still")
    }

    // --- through the test harness, by keyboard and pad --------------------------------------------

    /** The password screen again, with a real button, and a note of how far the panel ever got. */
    private class Shaken {
        var furthest = 0f
        lateinit var shake: ShakeState
        var ui: UiTest? = null
    }

    private fun harness(seen: Shaken): UiTest = uiTest(Size(400f, 200f)) {
        val shake = rememberShake().also { seen.shake = it }
        // Read here, so this scope recomposes every frame the panel moves, and note how far its laid
        // out box has got from the slot: what a player sees, not what the shake says it asked for.
        shake.offset
        SideEffect {
            val box = seen.ui?.node("panel")?.boundsInRoot ?: return@SideEffect
            seen.furthest = max(seen.furthest, max(abs(box.left - slot.left), abs(box.top - slot.top)))
        }
        Row(Modifier.padding(left = 20f, top = 40f)) {
            Box(Modifier.testTag("panel").shake(shake).size(120f, 40f).background(PanelColour))
            Button("SUBMIT", onClick = { shake.trigger() }, initialFocus = true, modifier = Modifier.testTag("submit"))
        }
    }

    @Test
    fun `enter on the focused submit button shakes the panel and the harness waits for it to land`() {
        val seen = Shaken()
        harness(seen).also { seen.ui = it }.use { ui ->
            val button = ui.node("submit").boundsInRoot
            ui.assertFocused("submit")
            assertEquals(0f, seen.furthest)

            ui.key(Key.Enter)

            assertTrue(seen.furthest > 3f, "Enter knocked the panel, and it went ${seen.furthest}")
            // The key settled the screen, so by now the whole shake has played out.
            assertEquals(0f, seen.shake.trauma)
            assertEquals(slot, ui.node("panel").boundsInRoot, "back in its slot the moment the test looks")
            assertEquals(button, ui.node("submit").boundsInRoot, "the button never moved")
            ui.assertFocused("submit")
        }
    }

    @Test
    fun `south on the pad shakes the panel and a mouse click shakes it again`() {
        val seen = Shaken()
        harness(seen).also { seen.ui = it }.use { ui ->
            ui.pad(GamepadButton.South)
            assertTrue(seen.furthest > 3f, "the pad knocked the panel, and it went ${seen.furthest}")
            assertEquals(slot, ui.node("panel").boundsInRoot)

            seen.furthest = 0f
            ui.click("submit")
            assertTrue(seen.furthest > 3f, "so did the mouse, and it went ${seen.furthest}")
            assertEquals(slot, ui.node("panel").boundsInRoot)
            assertEquals(0f, seen.shake.trauma)
        }
    }

    // --- the numbers -----------------------------------------------------------------------------

    @Test
    fun `the same seed shakes the same way every time`() {
        val one = ShakeState(seed = 7)
        val two = ShakeState(seed = 7)
        val other = ShakeState(seed = 8)

        val times = (0..30).map { it * 16_666_667L }
        val first = times.map { one.offsetAt(1f, it) }
        assertEquals(first, times.map { two.offsetAt(1f, it) })
        assertNotEquals(first, times.map { other.offsetAt(1f, it) })
        assertTrue(first.toSet().size > 20, "it moves from frame to frame rather than sitting still")
    }

    @Test
    fun `the wobble moves smoothly rather than jumping between frames`() {
        val shake = ShakeState(maxOffset = 10f, frequency = 15f)
        var previous = shake.offsetAt(1f, 0L)
        for (frame in 1..120) {
            val next = shake.offsetAt(1f, frame * 16_666_667L)
            assertTrue(abs(next.x - previous.x) < 10f && abs(next.y - previous.y) < 10f, "frame $frame jumped")
            assertTrue(abs(next.x) <= 10f && abs(next.y) <= 10f, "frame $frame went past the reach: $next")
            previous = next
        }
        assertEquals(Offset.Zero, shake.offsetAt(0f, 123_456_789L), "no trauma is no movement")
    }

    @Test
    fun `a knock that is not a number is refused`() {
        val shake = ShakeState()
        assertFailsWith<IllegalArgumentException> { shake.trigger(-0.5f) }
        assertFailsWith<IllegalArgumentException> { shake.trigger(Float.NaN) }
        shake.trigger(0f)
        assertEquals(0f, shake.trauma, "a zero knock is allowed and does nothing")
        assertFailsWith<IllegalArgumentException> { ShakeState(maxOffset = -1f) }
        assertFailsWith<IllegalArgumentException> { ShakeState(decayPerSecond = 0f) }
    }

    private companion object {
        val PanelColour = Colour.rgb(0x2C3545)
        val ButtonColour = Colour.rgb(0x4CC2FF)
    }
}
