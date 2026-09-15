package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `VirtualCursor` on a composed screen, driven by a pad the way a player drives it: the stick held
 * for a while and let go, South pressed and released, the screen switched away mid-drag.
 *
 * Every outcome is read off the screen — what a button says, where a gem was dropped, which node
 * has focus, where the arrow was drawn — never off the cursor's own bookkeeping alone.
 */
class VirtualCursorUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    /** 400 by 300, so the cursor starts at 200, 150. */
    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** A map with one button on it, 320 to 400 across and centred on the cursor's row. */
    @Composable
    private fun MapWithButton(snap: Boolean = true) {
        var said by remember { mutableStateOf("waiting") }
        VirtualCursor(enabled = true, snapToTargets = snap) {
            Box(Modifier.size(400f, 300f)) {
                Text(said, Modifier.testTag("said"))
                Box(Modifier.offset(320f, 130f).size(80f, 40f).testTag("travel").clickable { said = "travelled" })
            }
        }
    }

    private fun UiTest.arrows(): List<DrawCall.Fan> {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        return canvas.calls.filterIsInstance<DrawCall.Fan>()
    }

    private fun assertNear(expected: Offset, actual: Offset, what: String) =
        assertTrue(abs(expected.x - actual.x) < 1f && abs(expected.y - actual.y) < 1f, "$what: expected $expected, got $actual")

    @Test
    fun `the stick moves the cursor and the arrow is drawn with its tip where the cursor is`() {
        val ui = open { MapWithButton() }
        ui.padDown(GamepadButton.Start)
        ui.padUp(GamepadButton.Start)
        assertNear(Offset(200f, 150f), ui.cursor.position, "a cursor comes on in the middle")

        ui.holdStick(0f, 1f, millis = 200)

        val at = ui.cursor.position
        assertEquals(200f, at.x, "straight down stays in its column")
        assertTrue(at.y > 250f, "held down for a fifth of a second it went well down, to $at")
        val arrows = ui.arrows()
        assertTrue(arrows.isNotEmpty(), "an arrow is drawn")
        arrows.forEach { assertTrue(it.points.first().x in at.x - 2f..at.x && it.points.first().y in at.y - 4f..at.y, "tip ${it.points.first()} at $at") }
    }

    @Test
    fun `aimed at a button and let go the cursor settles on it and south clicks it`() {
        val ui = open { MapWithButton() }

        ui.holdStick(1f, 0f, millis = 150)

        assertNear(Offset(360f, 150f), ui.cursor.position, "let go on the button, it is in the button's middle")
        assertTrue(ui.cursor.overTarget)
        assertTrue(ui.pad(GamepadButton.South), "South is the cursor's button")
        ui.assertText("said", "travelled")
    }

    @Test
    fun `let go just short of a button the cursor glides onto it`() {
        val ui = open { MapWithButton() }

        ui.holdStick(1f, 0f, millis = 130)

        assertNear(Offset(360f, 150f), ui.cursor.position, "a near miss snaps")
        ui.pad(GamepadButton.South)
        ui.assertText("said", "travelled")
    }

    @Test
    fun `without snapping a near miss stays a miss and south clicks nothing`() {
        val ui = open { MapWithButton(snap = false) }

        ui.holdStick(1f, 0f, millis = 130)

        assertTrue(ui.cursor.position.x in 290f..320f, "stopped where it was let go, at ${ui.cursor.position}")
        assertTrue(ui.pad(GamepadButton.South), "South is still taken, so nothing focused is pressed instead")
        ui.assertText("said", "waiting")
    }

    @Test
    fun `the d-pad pushes the cursor at full speed`() {
        val ui = open { MapWithButton(snap = false) }

        ui.padDown(GamepadButton.DpadLeft)
        ui.advanceBy(100)
        ui.padUp(GamepadButton.DpadLeft)

        assertTrue(ui.cursor.position.x < 120f, "left, and far: ${ui.cursor.position}")
        assertEquals(150f, ui.cursor.position.y)
    }

    @Test
    fun `south held picks a gem up and the stick carries it to where south is let go`() {
        var drops = 0
        val ui = open {
            var at by remember { mutableStateOf(Offset(180f, 130f)) }
            var grab by remember { mutableStateOf<Offset?>(null) }
            VirtualCursor(enabled = true) {
                Box(Modifier.size(400f, 300f)) {
                    Text("${at.x.toInt()},${at.y.toInt()}", Modifier.testTag("where"))
                    Box(
                        Modifier.offset(at.x, at.y).size(40f, 40f).testTag("gem").onPointer { event ->
                            when (event) {
                                is PointerEvent.Press -> { grab = Offset(event.position.x - at.x, event.position.y - at.y); true }
                                is PointerEvent.Move -> grab?.let { g -> at = Offset(event.position.x - g.x, event.position.y - g.y); true } ?: false
                                is PointerEvent.Release -> { grab = null; drops++; true }
                                is PointerEvent.Cancel -> { grab = null; true }
                                else -> false
                            }
                        },
                    )
                }
            }
        }

        ui.padDown(GamepadButton.South)
        ui.holdStick(-1f, 0f, millis = 200)
        ui.padUp(GamepadButton.South)

        assertEquals(1, drops)
        val dropped = ui.node("gem").boundsInRoot
        assertTrue(dropped.left < 110f, "carried left, to $dropped")
        assertEquals(130f, dropped.top)
        ui.assertText("where", "${dropped.left.toInt()},130")
    }

    @Test
    fun `leaving the screen mid-drag lets go of the gem without dropping it`() {
        var onMap by mutableStateOf(true)
        val events = mutableListOf<String>()
        val ui = open {
            VirtualCursor(enabled = onMap) {
                Box(
                    Modifier.size(400f, 300f).onPointer { event ->
                        if (event !is PointerEvent.Move) events += event::class.simpleName.orEmpty()
                        true
                    },
                )
            }
        }

        ui.padDown(GamepadButton.South)
        onMap = false
        ui.settle()
        ui.padUp(GamepadButton.South)

        assertEquals(listOf("Press", "Cancel"), events, "cancelled, never released, so nothing is dropped or clicked")
        assertTrue(ui.arrows().isEmpty(), "and no arrow once it is off")
    }

    @Test
    fun `switched off the pad moves focus again and switched on it moves the cursor instead`() {
        var onMap by mutableStateOf(false)
        val ui = open {
            VirtualCursor(enabled = onMap) {
                Column {
                    Box(Modifier.size(100f, 40f).testTag("top").focusable(initial = true).clickable { })
                    Box(Modifier.size(100f, 40f).testTag("bottom").focusable().clickable { })
                }
            }
        }

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("bottom")
        assertTrue(ui.source.showsFocusRing, "a pad moving focus gets a ring")
        assertTrue(ui.arrows().isEmpty())

        onMap = true
        ui.settle()
        ui.pad(GamepadButton.DpadUp)
        ui.assertFocused("bottom")
        assertFalse(ui.source.showsFocusRing, "a ring stands aside for a cursor")
        assertTrue(ui.source.isPointing)
        assertTrue(ui.arrows().isNotEmpty())

        onMap = false
        ui.settle()
        ui.pad(GamepadButton.DpadUp)
        ui.assertFocused("top")
        assertTrue(ui.source.showsFocusRing)
    }

    @Test
    fun `picking up the mouse hides the pad's arrow so there are never two cursors`() {
        val ui = open { MapWithButton() }
        ui.pad(GamepadButton.Start)
        assertTrue(ui.arrows().isNotEmpty())

        ui.moveTo(Offset(10f, 10f))

        assertTrue(ui.arrows().isEmpty(), "the mouse's own cursor is the only one")
        ui.stick(0.5f, 0f)
        ui.stick(0f, 0f)
        assertTrue(ui.arrows().isNotEmpty(), "and the pad brings it back")
    }

    @Test
    fun `the pad hovering a hand button leaves the desktop cursor an arrow but still hovers it`() {
        var hovered = false
        val ui = open {
            VirtualCursor(enabled = true) {
                Box(Modifier.size(400f, 300f)) {
                    Box(
                        Modifier.offset(320f, 130f).size(80f, 40f).testTag("travel")
                            .pointerHoverIcon(PointerIcon.Hand)
                            .onPointer { event -> if (event is PointerEvent.Move) hovered = true; false }
                            .clickable { },
                    )
                }
            }
        }

        ui.holdStick(1f, 0f, millis = 150)

        assertTrue(ui.cursor.overTarget)
        assertTrue(hovered, "the button heard the cursor arrive")
        assertEquals(PointerIcon.Default, ui.pointerIcon, "the mouse, which nobody touched, is not turned into a hand")
    }

    @Test
    fun `a still cursor on a still screen changes nothing from frame to frame`() {
        val ui = open { MapWithButton() }
        ui.holdStick(1f, 0f, millis = 150)
        val before = ui.host.changedFrames

        ui.advanceBy(500)

        assertEquals(before, ui.host.changedFrames, "a cursor resting on a button redraws nothing")
        assertTrue(ui.arrows().isNotEmpty())
    }

    @Test
    fun `the right stick scrolls the list under the cursor`() {
        val state = ScrollState()
        val ui = open {
            VirtualCursor(enabled = true, snapToTargets = false) {
                ScrollArea(Modifier.size(400f, 300f), state) {
                    Column { repeat(60) { Text("row $it", Modifier.size(400f, 20f)) } }
                }
            }
        }

        ui.stick(0f, 1f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        assertTrue(state.y > 0f, "scrolled down to ${state.y}")
    }

    @Test
    fun `a switched off cursor beside a switched on one leaves it on and at its own speed`() {
        val ui = open {
            Column {
                VirtualCursor(enabled = true, snapToTargets = false) { Box(Modifier.size(400f, 250f)) }
                // Composed after the map, so its effects run last: it must not undo the map's.
                VirtualCursor(enabled = false, speed = 1f) { Box(Modifier.size(400f, 50f)) }
            }
        }

        ui.holdStick(-1f, 0f, millis = 100)

        assertTrue(ui.cursor.position.x < 150f, "still on, at the map's speed: ${ui.cursor.position}")
        assertTrue(ui.source.isPointing)
        assertEquals(2, ui.arrows().size, "one arrow, an edge and a body")
    }

    @Test
    fun `two switched on side by side draw one arrow and the second keeps it on when the first goes`() {
        var first by mutableStateOf(true)
        val ui = open {
            Column {
                if (first) VirtualCursor(enabled = true, snapToTargets = false) { Box(Modifier.size(400f, 150f)) }
                VirtualCursor(enabled = true, speed = 300f, snapToTargets = false) { Box(Modifier.size(400f, 150f)) }
            }
        }
        ui.pad(GamepadButton.Start)
        assertEquals(2, ui.arrows().size, "one arrow, an edge and a body, not one per VirtualCursor")

        first = false
        ui.settle()
        ui.holdStick(-1f, 0f, millis = 100)

        assertTrue(ui.cursor.enabled, "the second still wants it")
        assertTrue(ui.source.isPointing)
        assertEquals(2, ui.arrows().size, "and it draws the arrow now")
        assertTrue(ui.cursor.position.x in 160f..185f, "at the second one's own speed: ${ui.cursor.position}")
    }

    @Test
    fun `a cursor inside a cursor draws one arrow and the inner one leaving keeps the outer on`() {
        var inner by mutableStateOf(true)
        val ui = open {
            VirtualCursor(enabled = true, snapToTargets = false) {
                Box(Modifier.size(400f, 300f)) {
                    if (inner) VirtualCursor(enabled = true) { Box(Modifier.size(100f, 100f)) }
                }
            }
        }
        ui.pad(GamepadButton.Start)
        assertEquals(2, ui.arrows().size, "one arrow, not one per VirtualCursor")

        inner = false
        ui.settle()
        ui.holdStick(-1f, 0f, millis = 100)

        assertTrue(ui.cursor.position.x < 150f, "the outer one still drives it: ${ui.cursor.position}")
        assertEquals(2, ui.arrows().size)
    }

    @Test
    fun `enabled with no cursor to drive fails loudly`() {
        val failure = assertFailsWith<IllegalStateException> {
            open {
                CompositionLocalProvider(LocalGamepadCursor provides null) {
                    VirtualCursor(enabled = true) { Text("map") }
                }
            }
        }
        assertNotNull(failure.message?.let { if ("ProvideGamepadCursor" in it) it else null })
    }
}
