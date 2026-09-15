package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
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
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.ScrollArea
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * `onPlaced` and `onSizeChanged` on a composed screen, driven by a mouse, a keyboard and a pad.
 *
 * The screen is the issue's own example: a popup that sits under a button, with nobody polling.
 * The button's toolbar moves when it is clicked, grows when a key or pad button says so, and the
 * popup follows because the button's `onPlaced` wrote where it now is. In the common source set,
 * so it runs on Linux native as well.
 */
class LayoutCallbackInteractionTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private var clock = 0L

    /** Where the toolbar is pushed from the corner, and how wide it is. The input changes both. */
    private var toolbarAt by mutableStateOf(Offset(20f, 20f))
    private var toolbarWidth by mutableStateOf(120f)

    private var anchor: Rect? by mutableStateOf(null)
    private val placedCalls = mutableListOf<Rect>()
    private val toolbarSizes = mutableListOf<Size>()

    @AfterTest
    fun tearDown() = host.dispose()

    private fun settled() {
        var rounds = 0
        while (host.settle(Constraints.atMost(400f, 300f), focus, nanos = clock)) {
            clock += 16_666_667L
            if (++rounds > 20) fail("the screen never settled")
        }
        clock += 16_666_667L
    }

    private fun screen() {
        host.setContent {
            val onPlaced = remember {
                PlacedHandler { node ->
                    placedCalls += node.boundsInRoot
                    anchor = node.boundsInRoot
                }
            }
            val onSize = remember { SizeChangedHandler { toolbarSizes += it } }
            val move = remember { { toolbarAt = Offset(toolbarAt.x + 100f, toolbarAt.y + 60f) } }

            Box(Modifier.offset(toolbarAt.x, toolbarAt.y).size(toolbarWidth, 40f).onSizeChanged(onSize)) {
                // The button sits at the toolbar's right-hand end, so growing the toolbar moves it.
                Box(
                    Modifier.offset(toolbarWidth - 40f, 0f).size(40f, 40f)
                        .focusable(initial = true).clickable(onClick = move).onPlaced(onPlaced),
                )
            }

            val under = anchor
            if (under != null) {
                Box(Modifier.offset(under.left, under.bottom).size(80f, 30f))
            }
        }
        settled()
    }

    private fun button(): UiNode = host.root.children[0].children[0]

    private fun popup(): Rect = assertNotNull(host.root.children.getOrNull(1), "no popup").layoutBoundsInRoot

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    @Test
    fun `the popup opens under the button with nothing polling`() {
        screen()

        assertEquals(Rect(100f, 20f, 140f, 60f), button().boundsInRoot)
        assertEquals(Rect(100f, 60f, 180f, 90f), popup(), "the popup should hang from the button's bottom edge")
        assertEquals(1, placedCalls.size)
    }

    @Test
    fun `clicking the button moves it and the popup follows`() {
        screen()

        click(Offset(120f, 40f))
        settled()

        assertEquals(Rect(200f, 80f, 240f, 120f), button().boundsInRoot)
        assertEquals(Rect(200f, 120f, 280f, 150f), popup())
        assertEquals(listOf(Size(120f, 40f)), toolbarSizes, "moving the toolbar is not resizing it")
    }

    @Test
    fun `a still screen tells nobody anything`() {
        screen()
        val placed = placedCalls.size
        val sized = toolbarSizes.size

        repeat(120) {
            host.settle(Constraints.atMost(400f, 300f), focus, nanos = clock)
            clock += 16_666_667L
        }
        // Hovering changes interaction state and still moves nothing.
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(120f, 40f)))
        settled()

        assertEquals(placed, placedCalls.size, "a screen standing still called onPlaced")
        assertEquals(sized, toolbarSizes.size, "a screen standing still called onSizeChanged")
    }

    @Test
    fun `the keyboard pressing the button moves the popup`() {
        screen()

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        settled()

        assertEquals(Rect(200f, 120f, 280f, 150f), popup())
        assertEquals(2, placedCalls.size)
    }

    @Test
    fun `growing the toolbar from the pad reports the new size and the popup follows`() {
        // The same screen, except the pad's South presses a button that grows the toolbar.
        screenThatGrows()

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        settled()

        assertEquals(listOf(Size(120f, 40f), Size(220f, 40f)), toolbarSizes)
        assertEquals(Rect(200f, 20f, 240f, 60f), button().boundsInRoot)
        assertEquals(Rect(200f, 60f, 280f, 90f), popup())
    }

    @Test
    fun `scrolling with the wheel moves the anchor and resizes nothing`() {
        var shownAt by mutableStateOf(0)
        host.setContent {
            val onPlaced = remember { PlacedHandler { node -> anchor = node.boundsInRoot; shownAt++ } }
            val onSize = remember { SizeChangedHandler { toolbarSizes += it } }
            ScrollArea(Modifier.size(200f, 200f), bars = false) {
                Column {
                    Box(Modifier.size(200f, 100f))
                    Box(Modifier.size(40f, 40f).onPlaced(onPlaced).onSizeChanged(onSize))
                    Box(Modifier.size(200f, 600f))
                }
            }
        }
        settled()
        assertEquals(Rect(0f, 100f, 40f, 140f), anchor)

        // One notch is 48 units, and scrolling is layout: the button is placed higher, not drawn higher.
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(100f, 100f), Offset(0f, 1f)))
        settled()

        assertEquals(Rect(0f, 52f, 40f, 92f), anchor)
        assertEquals(2, shownAt)
        assertEquals(listOf(Size(40f, 40f)), toolbarSizes, "scrolling is not resizing")
    }

    @Test
    fun `a button that goes away and comes back is told where it is again`() {
        var shown by mutableStateOf(true)
        host.setContent {
            val onPlaced = remember { PlacedHandler { node -> placedCalls += node.boundsInRoot } }
            val toggle = remember { { shown = !shown } }
            Box(Modifier.offset(0f, 250f).size(40f, 40f).clickable(onClick = toggle))
            if (shown) Box(Modifier.offset(60f, 10f).size(30f, 30f).onPlaced(onPlaced))
        }
        settled()

        click(Offset(20f, 270f))
        settled()
        assertEquals(1, placedCalls.size, "a button that is gone was told something")

        click(Offset(20f, 270f))
        settled()

        assertEquals(listOf(Rect(60f, 10f, 90f, 40f), Rect(60f, 10f, 90f, 40f)), placedCalls)
    }

    @Test
    fun `switching which popup the button feeds tells the new one where the button is`() {
        // Two popups, and a key that picks which one the button's onPlaced writes to. The button
        // never moves, so only a handler that has heard nothing yet can open the second popup.
        var second: Rect? by mutableStateOf(null)
        var feedsSecond by mutableStateOf(false)
        host.setContent {
            val first = remember { PlacedHandler { node -> anchor = node.boundsInRoot } }
            val other = remember { PlacedHandler { node -> second = node.boundsInRoot } }
            val swap = remember { { feedsSecond = true } }
            Box(
                Modifier.offset(60f, 10f).size(30f, 30f).focusable(initial = true).clickable(onClick = swap)
                    .onPlaced(if (feedsSecond) other else first),
            )
            second?.let { Box(Modifier.offset(it.left, it.bottom).size(80f, 30f)) }
        }
        settled()
        assertEquals(null, second)

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        settled()

        assertEquals(Rect(60f, 10f, 90f, 40f), second, "the new handler never heard where the button is")
        assertEquals(Rect(60f, 40f, 140f, 70f), host.root.children[1].layoutBoundsInRoot)
    }

    private fun screenThatGrows() {
        host.setContent {
            val onPlaced = remember { PlacedHandler { node -> anchor = node.boundsInRoot } }
            val onSize = remember { SizeChangedHandler { toolbarSizes += it } }
            val grow = remember { { toolbarWidth += 100f } }

            Box(Modifier.offset(toolbarAt.x, toolbarAt.y).size(toolbarWidth, 40f).onSizeChanged(onSize)) {
                Box(
                    Modifier.offset(toolbarWidth - 40f, 0f).size(40f, 40f)
                        .focusable(initial = true).clickable(onClick = grow).onPlaced(onPlaced),
                )
            }
            val under = anchor
            if (under != null) Box(Modifier.offset(under.left, under.bottom).size(80f, 30f))
        }
        settled()
    }
}
