package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.draggable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Slider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `draggable` on a real composed screen: state moved by the drag, recomposed every frame of it,
 * laid out and drawn — and a window with a button and a slider inside, dragged by a mouse.
 *
 * The router tests prove the gesture. These prove what a player sees: the thing under the pointer
 * goes where the pointer goes, frame after frame, while the screen rebuilds itself around it.
 */
class DraggableUiTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)

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
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.fixed(600f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    // Each event is followed by a frame, the way a game hands the interface one per event batch.
    private fun press(at: Offset) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        frame()
    }

    private fun drag(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at, setOf(PointerButton.Primary)))
        frame()
    }

    private fun release(at: Offset) {
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
        frame()
    }

    private fun cancel(at: Offset) {
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, at))
        frame()
    }

    private fun node(name: String, from: UiNode = host.root): UiNode =
        checkNotNull(find(name, from)) { "no $name in\n${host.root.debugTree()}" }

    private fun find(name: String, from: UiNode): UiNode? =
        if (from.name == name) from else from.children.firstNotNullOfOrNull { find(name, it) }

    private fun drawnInColour(colour: Colour): Rect =
        canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == colour }.rect

    // --- a card that follows the pointer ------------------------------------------------------

    private val cardColour = Colour.rgb(0x3366CC)

    @Composable
    private fun Card(state: CardState) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset(state.at.x, state.at.y)
                    .size(80f, 50f)
                    .background(cardColour)
                    .interaction(state.touch)
                    .draggable(
                        onDragStart = { state.started = it },
                        onDragEnd = { state.ended = true },
                        onDragCancel = { state.at = state.home },
                    ) { state.at += it },
            )
        }
    }

    private class CardState {
        val home = Offset(100f, 100f)
        var at by mutableStateOf(home)
        var started: Offset? = null
        var ended = false
        val touch = InteractionState()
    }

    @Test
    fun `a dragged card is laid out and drawn where the pointer took it`() {
        val card = CardState()
        show { Card(card) }
        assertEquals(Rect.of(100f, 100f, 80f, 50f), drawnInColour(cardColour))

        press(Offset(120f, 110f))
        drag(Offset(150f, 110f))
        assertEquals(Offset(20f, 10f), card.started, "grabbed 20 across and 10 down")
        assertEquals(Rect.of(130f, 100f, 80f, 50f), drawnInColour(cardColour), "and it came with the pointer")

        // Every one of these recomposes the card with a new modifier. The drag survives all of them.
        for (step in 1..10) drag(Offset(150f + step * 20f, 110f + step * 15f))
        release(Offset(400f, 300f))

        assertEquals(Rect.of(380f, 290f, 80f, 50f), drawnInColour(cardColour))
        assertEquals(380f, card.at.x)
        assertTrue(card.ended)
        assertFalse(card.touch.isPressed, "let go, so not held any more")
    }

    @Test
    fun `a card whose drag is cancelled goes back where it came from`() {
        val card = CardState()
        show { Card(card) }

        press(Offset(120f, 110f))
        drag(Offset(300f, 250f))
        assertEquals(Rect.of(280f, 240f, 80f, 50f), drawnInColour(cardColour))

        cancel(Offset(300f, 250f))

        assertEquals(Rect.of(100f, 100f, 80f, 50f), drawnInColour(cardColour), "the platform took it away, so it went home")
        assertFalse(card.ended, "a cancel is not a drop")
    }

    @Test
    fun `a wobbly click does not move the card`() {
        val card = CardState()
        show { Card(card) }

        press(Offset(120f, 110f))
        drag(Offset(123f, 112f))
        release(Offset(123f, 112f))

        assertEquals(Rect.of(100f, 100f, 80f, 50f), drawnInColour(cardColour))
        assertEquals(null, card.started)
    }

    // --- a window with controls in it ---------------------------------------------------------

    private var windowAt by mutableStateOf(Offset(40f, 40f))
    private var clicks = 0
    private var volume by mutableStateOf(0f)

    private fun window() = show {
        Box(Modifier.fillMaxSize()) {
            Panel(Modifier.offset(windowAt.x, windowAt.y).width(260f).draggable { windowAt += it }) {
                Column {
                    Button("OK", onClick = { clicks++ })
                    Slider(volume, onValueChange = { volume = it }, length = 200f)
                }
            }
        }
    }

    private fun centre(node: UiNode) = node.boundsInRoot.centre

    /** The button is a box with no name of its own: the first thing in the window's column. */
    private fun button(): UiNode = node("slider").parent!!.children.first()

    @Test
    fun `dragging a window by its empty space moves the window and everything in it`() {
        window()
        val slider = node("slider").boundsInRoot
        val panel = node("slider").parent!!.parent!!
        val corner = Offset(panel.boundsInRoot.right - 4f, panel.boundsInRoot.bottom - 4f)

        press(corner)
        drag(corner + Offset(50f, 30f))
        drag(corner + Offset(100f, 60f))
        release(corner + Offset(100f, 60f))

        assertEquals(Offset(140f, 100f), windowAt)
        assertEquals(slider.left + 100f, node("slider").boundsInRoot.left, "the slider came with it")
        assertEquals(slider.top + 60f, node("slider").boundsInRoot.top)
        assertEquals(0, clicks)
    }

    @Test
    fun `a steady click on a button in a draggable window is a click`() {
        window()
        val at = centre(button())

        press(at)
        drag(at + Offset(2f, 1f))
        release(at + Offset(2f, 1f))

        assertEquals(1, clicks)
        assertEquals(Offset(40f, 40f), windowAt, "the window stayed put")
    }

    @Test
    fun `dragging from a button moves the window and does not click`() {
        window()
        val at = centre(button())

        press(at)
        drag(at + Offset(60f, 0f))
        drag(at + Offset(60f, 80f))
        release(at + Offset(60f, 80f))

        // A button's centre is a fraction of a pixel, so these are to within a hundredth.
        assertEquals(100f, windowAt.x, 0.01f, "the window followed the pointer all the way")
        assertEquals(120f, windowAt.y, 0.01f)
        assertEquals(0, clicks, "and let go over the button it started on, which is still not a click")
        assertEquals(at.x + 60f, centre(button()).x, 0.01f, "the button is under the pointer still")
        assertEquals(at.y + 80f, centre(button()).y, 0.01f)
    }

    @Test
    fun `dragging a slider in a draggable window moves the slider and not the window`() {
        window()
        val track = node("slider").boundsInRoot
        val start = Offset(track.left + 8f, track.centre.y)

        press(start)
        drag(Offset(track.left + 8f + (track.width - 16f) / 2f, track.centre.y))
        drag(Offset(track.right - 8f, track.centre.y))
        release(Offset(track.right - 8f, track.centre.y))

        assertEquals(1f, volume, 0.001f)
        assertEquals(Offset(40f, 40f), windowAt)
    }

    // --- cards on a board that is draggable itself --------------------------------------------

    private val boardColour = Colour.rgb(0x224422)
    private var boardAt by mutableStateOf(Offset(20f, 20f))

    /** A map that pans, with a card on it that can be picked up, or locked in place. */
    private fun board(card: CardState, locked: Boolean = false) = show {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(boardAt.x, boardAt.y).size(400f, 300f).background(boardColour)
                    .draggable { boardAt += it },
            ) {
                Box(
                    Modifier.offset(card.at.x, card.at.y).size(80f, 50f).background(cardColour)
                        .draggable(enabled = !locked) { card.at += it },
                )
            }
        }
    }

    @Test
    fun `a card on a draggable board is picked up and the board stays put`() {
        val card = CardState()
        board(card)

        // The card is at 100, 100 on a board at 20, 20.
        press(Offset(140f, 140f))
        drag(Offset(200f, 180f))
        release(Offset(260f, 220f))

        assertEquals(Offset(220f, 180f), card.at)
        assertEquals(Offset(20f, 20f), boardAt, "the board did not pan under the card")
        assertEquals(Rect.of(20f, 20f, 400f, 300f), drawnInColour(boardColour))
        assertEquals(Rect.of(240f, 200f, 80f, 50f), drawnInColour(cardColour))
    }

    @Test
    fun `a locked card lets the press through and the board pans instead`() {
        val card = CardState()
        board(card, locked = true)

        press(Offset(140f, 140f))
        drag(Offset(200f, 180f))
        release(Offset(200f, 180f))

        assertEquals(Offset(100f, 100f), card.at, "locked, so it stayed where it is on the board")
        assertEquals(Offset(80f, 60f), boardAt)
        assertEquals(Rect.of(180f, 160f, 80f, 50f), drawnInColour(cardColour), "and went along with the board")
    }

    @Test
    fun `the right mouse button does not move a card`() {
        val card = CardState()
        show { Card(card) }

        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(120f, 110f), PointerButton.Secondary))
        frame()
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(300f, 250f), setOf(PointerButton.Secondary)))
        frame()
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(300f, 250f), PointerButton.Secondary))
        frame()

        assertEquals(Rect.of(100f, 100f, 80f, 50f), drawnInColour(cardColour))
        assertEquals(null, card.started)
    }

    @Test
    fun `two fingers drag two cards at once`() {
        var left by mutableStateOf(Offset(20f, 100f))
        var right by mutableStateOf(Offset(300f, 100f))
        val leftColour = Colour.rgb(0xAA0000)
        val rightColour = Colour.rgb(0x00AA00)
        show {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(left.x, left.y).size(80f, 50f).background(leftColour).draggable { left += it })
                Box(Modifier.offset(right.x, right.y).size(80f, 50f).background(rightColour).draggable { right += it })
            }
        }
        val one = PointerId(1)
        val two = PointerId(2)
        fun finger(event: PointerEvent) {
            pointer.onPointer(event)
            frame()
        }
        finger(PointerEvent.Press(one, Offset(40f, 120f), type = PointerType.Touch))
        finger(PointerEvent.Press(two, Offset(320f, 120f), type = PointerType.Touch))
        finger(PointerEvent.Move(one, Offset(40f, 220f), setOf(PointerButton.Primary), PointerType.Touch))
        finger(PointerEvent.Move(two, Offset(420f, 120f), setOf(PointerButton.Primary), PointerType.Touch))
        finger(PointerEvent.Release(one, Offset(40f, 220f), type = PointerType.Touch))
        finger(PointerEvent.Release(two, Offset(420f, 120f), type = PointerType.Touch))

        assertEquals(Rect.of(20f, 200f, 80f, 50f), drawnInColour(leftColour), "the first finger took the left card down")
        assertEquals(Rect.of(400f, 100f, 80f, 50f), drawnInColour(rightColour), "the second took the right one across")
    }
}
