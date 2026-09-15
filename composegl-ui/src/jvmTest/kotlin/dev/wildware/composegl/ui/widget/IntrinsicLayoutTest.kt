package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Colour
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
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.IntrinsicSize
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * Screens sized by what their contents would like to be, composed for real and driven the way a
 * player drives them: a mouse, a keyboard, a pad, and frames going by.
 *
 * No width in here is typed in. Each expected number is what the same widget measures on its own,
 * so the tests say "the menu is as wide as its longest button" rather than "the menu is 91 wide",
 * and keep passing when the skin's padding or the test font changes.
 */
class IntrinsicLayoutTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    private val divider = Colour.rgb(0xFF0000)

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        repeat(2) { frame() }
    }

    /** One turn of a game loop: recompose, lay out, settle focus, draw. */
    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun click(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, timeMillis = clock / 1_000_000))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, timeMillis = clock / 1_000_000))
        frame()
    }

    private fun key(key: Key) {
        val down = KeyEvent(key, KeyEventType.Down)
        if (!router.onKey(down)) keys.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up)
        if (!router.onKey(up)) keys.onKey(up)
        frame()
    }

    private fun button(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
        frame()
    }

    private fun only(): UiNode = host.root.children.single()

    /** How wide a button with [label] on it is when nothing makes it any wider. */
    private fun buttonWidth(label: String): Float {
        show { Button(label, onClick = {}) }
        return only().width
    }

    /** How big a label is on its own, on one line. */
    private fun textSize(label: String): Pair<Float, Float> {
        show { Text(label) }
        return only().width to only().height
    }

    private val UiNode.centre: Offset get() = boundsInRoot.let { Offset((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }

    private fun menu(onPlay: () -> Unit = {}, onOptions: () -> Unit = {}, quitLabel: () -> String = { "QUIT" }, onQuit: () -> Unit = {}) {
        show {
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(4f)) {
                Button("PLAY", onClick = onPlay, modifier = Modifier.fillMaxWidth(), initialFocus = true)
                Button("OPTIONS", onClick = onOptions, modifier = Modifier.fillMaxWidth())
                Button(quitLabel(), onClick = onQuit, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // --- a menu as wide as its longest label ---

    @Test
    fun `every button in a menu sized to its widest is as wide as the longest label`() {
        val options = buttonWidth("OPTIONS")
        val play = buttonWidth("PLAY")
        menu()

        val column = only()
        assertEquals(options, column.width, "the menu is as wide as its widest button, not the screen")
        column.children.forEach { assertEquals(options, it.width, "${it.name} fills to the longest label") }
        assertTrue(play < options, "the check means something only if the labels differ")

        val frames = canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.rect.width == options }
        assertEquals(3, frames.size, "and every button is drawn that wide")
    }

    @Test
    fun `a click past the end of a short label still lands on its button and not past the menu`() {
        val quitAlone = buttonWidth("QUIT")
        var quits = 0
        menu(onQuit = { quits++ })
        val menuWidth = only().width
        val quit = only().children[2]
        val y = quit.centre.y

        assertTrue(quitAlone + 4f < menuWidth)
        click(Offset(menuWidth - 2f, y))
        assertEquals(1, quits, "the widened button takes the click where its label alone would not reach")

        click(Offset(menuWidth + 4f, y))
        assertEquals(1, quits, "and nothing past the menu's own width is a button")
    }

    @Test
    fun `the menu widens when a label grows and pressing it with the keyboard did that`() {
        var quitLabel by mutableStateOf("QUIT")
        val wide = buttonWidth("QUIT TO DESKTOP")
        menu(onPlay = { quitLabel = "QUIT TO DESKTOP" }, quitLabel = { quitLabel })
        assertTrue(only().width < wide)

        key(Key.Enter)
        frame()

        assertEquals("QUIT TO DESKTOP", quitLabel, "Enter pressed the focused PLAY button")
        assertEquals(wide, only().width)
        only().children.forEach { assertEquals(wide, it.width, "${it.name} grew with the longest label") }
    }

    @Test
    fun `a pad walks down the menu and presses a button as wide as the rest`() {
        var options = 0
        menu(onOptions = { options++ })
        frame()

        button(GamepadButton.DpadDown)
        val focused = checkNotNull(focus.focused) { "the pad moved focus somewhere" }
        assertEquals(only().children[1], focused, "down from PLAY is OPTIONS")
        assertEquals(only().width, focused.width)

        button(GamepadButton.South)
        assertEquals(1, options)
    }

    // --- a form whose label column fits its longest label ---

    @Test
    fun `a form's labels are as wide as the longest and the fields start straight after`() {
        val (callsign, _) = textSize("CALLSIGN")
        val (name, _) = textSize("NAME")
        var typed by mutableStateOf("")
        show {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8f)) {
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6f)) {
                    Text("NAME", Modifier.fillMaxWidth(), align = HorizontalAlignment.End)
                    Text("CALLSIGN", Modifier.fillMaxWidth(), align = HorizontalAlignment.End)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6f)) {
                    TextField(typed, { typed = it }, Modifier.fillMaxWidth())
                }
            }
        }

        val (labels, fields) = only().children
        assertEquals(callsign, labels.width)
        assertEquals(callsign + 8f, fields.x, "the fields start one gap after the longest label")
        assertEquals(400f - callsign - 8f, fields.width, "and take the rest of the row")

        val drawnName = canvas.calls.filterIsInstance<DrawCall.Text>().first { it.text == "NAME" }
        assertEquals(callsign - name, drawnName.at.x, "the short label is right-aligned against the fields")

        click(fields.children.single().centre)
        router.onText(TextEvent("V"))
        router.onText(TextEvent("e"))
        router.onText(TextEvent("x"))
        frame()

        assertEquals("Vex", typed)
        assertEquals(callsign + 8f, only().children[1].x, "typing moves nothing")
    }

    // --- text squeezed to its longest word ---

    @Test
    fun `a label sized to its narrowest breaks at every space and is as wide as its longest word`() {
        val (word, line) = textSize("SYSTEMS")
        show {
            Box(Modifier.width(IntrinsicSize.Min)) { Text("ALL SYSTEMS NOMINAL") }
        }

        val box = only()
        assertEquals(word, box.width)
        assertEquals(line * 3f, box.height, "three words, three lines")
    }

    // --- a divider as tall as the row ---

    @Test
    fun `a divider in a row sized to its tallest cell is drawn exactly that tall`() {
        val (_, line) = textSize("ONE")
        show {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6f)) {
                Text("ONE")
                Box(Modifier.width(2f).fillMaxHeight().background(divider)) {}
                Text("TWO\nLINES")
            }
        }

        assertEquals(line * 2f, only().height)
        val drawn = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == divider }
        assertEquals(line * 2f, drawn.rect.height, "the divider is as tall as the two-line cell, not the screen")
    }

    // --- cost ---

    @Test
    fun `a still menu sized by its contents allocates almost nothing a frame`() {
        menu()

        repeat(5) {
            host.frame(clock)
            clock += 16_666_667L
            MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        }

        val before = allocatedBytes()
        repeat(20) {
            host.frame(clock)
            clock += 16_666_667L
            MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        }
        val perFrame = (allocatedBytes() - before) / 20

        // The questions are asked every frame, so what they keep has to be kept rather than made.
        assertTrue(perFrame < 1_024, "a still frame of an intrinsic menu allocated $perFrame bytes")
    }

    private fun allocatedBytes(): Long {
        val beans = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return beans.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }
}
