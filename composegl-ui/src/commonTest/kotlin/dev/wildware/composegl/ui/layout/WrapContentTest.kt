package dev.wildware.composegl.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.wrapContentSize
import dev.wildware.composegl.ui.modifier.wrapContentWidth
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A small thing in a big slot, in composed UI: where it lands, where it takes clicks, and where
 * focus goes between two of them.
 */
class WrapContentTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        settle()
    }

    private fun settle() {
        repeat(8) {
            clock += 16_666_667L
            if (!host.settle(Constraints.atMost(400f, 400f), focus, nanos = clock)) return
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    private fun press(key: Key) {
        KeyNavigator(focus).apply {
            onKey(KeyEvent(key, KeyEventType.Down))
            onKey(KeyEvent(key, KeyEventType.Up))
        }
        settle()
    }

    /** A row of three equal shares, 300 by 60, with a 24-pixel badge in the middle one. */
    @Composable
    private fun BadgeRow(badge: Modifier, onClick: () -> Unit) {
        Row(Modifier.width(300f).height(60f)) {
            Box(Modifier.weight(1f).background(Colour.Grey)) {}
            Box(badge.size(24f).background(Colour.Red).clickable(onClick = onClick).testTag("badge")) {}
            Box(Modifier.weight(1f).background(Colour.Grey).testTag("right")) {}
        }
    }

    @Test
    fun `a badge in a weighted slot stays its own size and sits in the middle of the slot`() {
        show { BadgeRow(Modifier.weight(1f).wrapContentSize(Alignment.Centre), onClick = {}) }

        // Centred across the third the weight forced on it. Down is not forced by a row — its
        // children are offered 0 to 60 tall — so the badge stays where the row puts it, at the top.
        assertEquals(Rect.of(138f, 0f, 24f, 24f), host.root.find("badge").boundsInRoot)
        assertEquals(200f, host.root.find("right").boundsInRoot.left, "the row still gave it a whole third")
    }

    @Test
    fun `the badge takes clicks on itself and not on the empty part of its slot`() {
        var clicks = 0
        show { BadgeRow(Modifier.weight(1f).wrapContentSize(), onClick = { clicks++ }) }

        click(Offset(105f, 12f))  // inside the middle third, level with the badge, outside it
        assertEquals(0, clicks, "the empty part of the slot is not the badge's")

        click(host.root.find("badge").boundsInRoot.centre)
        assertEquals(1, clicks)
    }

    @Test
    fun `changing the alignment moves the badge on the next frame and its clicks move with it`() {
        var alignment by mutableStateOf(Alignment.Centre)
        var clicks = 0
        show { BadgeRow(Modifier.weight(1f).wrapContentSize(alignment), onClick = { clicks++ }) }

        alignment = Alignment.BottomEnd
        settle()

        val moved = host.root.find("badge").boundsInRoot
        assertEquals(Rect.of(176f, 0f, 24f, 24f), moved)

        click(Offset(150f, 12f))  // where it used to be
        assertEquals(0, clicks, "nothing is left behind at the old place")
        click(moved.centre)
        assertEquals(1, clicks)
    }

    @Test
    fun `taking the wrap away stretches the badge again and taking the badge away leaves no clicks`() {
        var wrapped by mutableStateOf(true)
        var shown by mutableStateOf(true)
        var clicks = 0
        show {
            Row(Modifier.width(300f).height(60f)) {
                Box(Modifier.weight(1f)) {}
                if (shown) {
                    val slot = if (wrapped) Modifier.weight(1f).wrapContentSize() else Modifier.weight(1f)
                    Box(slot.size(24f).clickable { clicks++ }.testTag("badge")) {}
                } else {
                    Box(Modifier.weight(1f)) {}
                }
                Box(Modifier.weight(1f).testTag("right")) {}
            }
        }
        assertEquals(Rect.of(138f, 0f, 24f, 24f), host.root.find("badge").boundsInRoot)

        wrapped = false
        settle()
        assertEquals(Rect.of(100f, 0f, 100f, 24f), host.root.find("badge").boundsInRoot, "stretched across its third")
        click(Offset(105f, 12f))
        assertEquals(1, clicks, "the whole slot is the badge now")

        wrapped = true
        settle()
        assertEquals(Rect.of(138f, 0f, 24f, 24f), host.root.find("badge").boundsInRoot, "and back to its own size")

        shown = false
        settle()
        click(Offset(150f, 12f))
        assertEquals(1, clicks, "a badge that is gone takes no clicks")
        assertEquals(200f, host.root.find("right").boundsInRoot.left)
    }

    @Test
    fun `two wrapped buttons keep their own width and the arrow keys walk between them`() {
        var pressed = ""
        show {
            Row(Modifier.width(360f)) {
                Button(
                    "A", onClick = { pressed += "A" }, initialFocus = true,
                    modifier = Modifier.weight(1f).wrapContentWidth().testTag("a"),
                )
                Button(
                    "B", onClick = { pressed += "B" },
                    modifier = Modifier.weight(1f).wrapContentWidth().testTag("b"),
                )
            }
        }

        val a = host.root.find("a")
        val b = host.root.find("b")
        assertTrue(a.boundsInRoot.width < 180f, "the button is as wide as its label, not its half: ${a.boundsInRoot}")
        assertEquals(90f, a.boundsInRoot.centre.x, 0.5f, "and centred in the left half")
        assertEquals(270f, b.boundsInRoot.centre.x, 0.5f, "and the other in the right half")

        assertSame(a, focus.focused)
        press(Key.Right)
        assertSame(b, focus.focused, "right moves focus across the gap to the other button")
        press(Key.Enter)
        assertEquals("B", pressed)
    }

    @Test
    fun `in a column the forced axis is the height and the badge sits down it where it asked`() {
        var clicks = 0
        show {
            Column(Modifier.width(60f).height(300f)) {
                Box(Modifier.weight(1f)) {}
                Box(
                    Modifier.weight(1f).wrapContentSize(Alignment.BottomCentre).size(24f)
                        .background(Colour.Red).clickable { clicks++ }.testTag("badge"),
                ) {}
                Box(Modifier.weight(1f).testTag("below")) {}
            }
        }

        assertEquals(Rect.of(0f, 176f, 24f, 24f), host.root.find("badge").boundsInRoot)
        assertEquals(200f, host.root.find("below").boundsInRoot.top)

        click(Offset(12f, 110f))  // the top of its slot, where a stretched badge would have been
        assertEquals(0, clicks)
        click(Offset(12f, 190f))
        assertEquals(1, clicks)
    }

    /**
     * A toolbar of three small buttons, each in a third of a 600-wide row, with a label that says
     * which was pressed last. The screen a game would build, driven through [uiTest].
     */
    @Composable
    private fun Toolbar(alignment: Alignment) {
        var last by remember { mutableStateOf("none") }
        Column {
            Row(Modifier.width(600f).height(80f)) {
                for (name in listOf("map", "bag", "quit")) {
                    Button(
                        name, onClick = { last = name }, initialFocus = name == "map",
                        modifier = Modifier.weight(1f).wrapContentSize(alignment).testTag(name),
                    )
                }
            }
            Text(last, Modifier.testTag("last"))
        }
    }

    @Test
    fun `a wrapped toolbar is walked with the pad and pressed with its south button`() {
        val ui = uiTest(Size(600f, 200f)) { Toolbar(Alignment.Centre) }
        ui.use {
            val map = ui.node("map").boundsInRoot
            assertTrue(map.width < 200f, "the button is its own width, not its third: $map")
            assertEquals(100f, map.centre.x, 0.5f, "and centred across the first third")
            assertEquals(300f, ui.node("bag").boundsInRoot.centre.x, 0.5f)
            assertEquals(500f, ui.node("quit").boundsInRoot.centre.x, 0.5f)

            ui.assertFocused("map")
            ui.pad(GamepadButton.DpadRight)
            ui.pad(GamepadButton.DpadRight)
            ui.assertFocused("quit")
            ui.pad(GamepadButton.South)
            ui.assertText("last", "quit")
        }
    }

    @Test
    fun `the mouse presses a wrapped button but not the empty edge of its slot`() {
        val ui = uiTest(Size(600f, 200f)) { Toolbar(Alignment.CentreStart) }
        ui.use {
            val bag = ui.node("bag").boundsInRoot
            assertEquals(200f, bag.left, 0.5f, "pushed to the start of the middle third")
            assertTrue(bag.right < 390f, "and not stretched across it: $bag")

            ui.click(Offset(390f, bag.centre.y))  // the far end of the bag's slot
            ui.assertText("last", "none")

            ui.click("bag")
            ui.assertText("last", "bag")
            ui.key(Key.Tab)
            ui.key(Key.Enter)
            ui.assertText("last", "quit")
        }
    }
}
