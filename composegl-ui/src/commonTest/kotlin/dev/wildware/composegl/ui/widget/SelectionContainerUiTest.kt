package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.game.Hotbar
import dev.wildware.composegl.ui.game.HotbarSlot
import dev.wildware.composegl.ui.game.NotificationQueue
import dev.wildware.composegl.ui.game.Notifications
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A label inside a [SelectionContainer], driven the way a player drives it: a real press and drag
 * through the pointer router, real Ctrl+C through the key router, the pad through its navigator.
 *
 * Positions are worked out from the label's own box: the headless font gives every character the
 * same width, so the middle of character `i` is `left + (i + 0.5) * width / length`.
 */
class SelectionContainerUiTest {

    private val opened = mutableListOf<UiTest>()
    private val backend = HeadlessBackend()
    private val ctrl = Modifiers.Control

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(600f, 400f), backend, content = content).also { opened += it }

    /** The left edge of character [index] in the one-line label [tag] that reads [text]. */
    private fun UiTest.edge(tag: String, text: String, index: Int): Offset {
        val box = node(tag).boundsInRoot
        return Offset(box.left + index * box.width / text.length, box.centre.y)
    }

    /** Presses at character [from] of [tag] and drags to character [to], then lets go. */
    private fun UiTest.drag(tag: String, text: String, from: Int, to: Int) {
        press(edge(tag, text, from))
        moveTo(edge(tag, text, to))
        release()
    }

    private val seed = "Seed: 8F3A-22C1"

    @Test
    fun `dragging across a label and pressing ctrl c copies what was dragged over`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        ui.drag("seed", seed, from = 6, to = 15)
        assertEquals("8F3A-22C1", state.selectedText)

        assertTrue(ui.key(Key.C, ctrl), "ctrl c with something selected is taken")
        assertEquals("8F3A-22C1", backend.clipboard.read())
    }

    @Test
    fun `a drag that runs off the end of the label keeps selecting to the end`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        ui.press(ui.edge("seed", seed, 6))
        ui.moveTo(Offset(590f, ui.node("seed").boundsInRoot.centre.y))
        ui.release()

        assertEquals("8F3A-22C1", state.selectedText)
    }

    @Test
    fun `double clicking a word selects the word`() {
        val state = SelectionState()
        val text = "lobby 4471 open"
        val ui = open {
            SelectionContainer(state = state) { Text(text, Modifier.testTag("lobby")) }
        }

        val onDigits = ui.edge("lobby", text, 8)
        ui.click(onDigits)
        ui.click(onDigits)

        assertEquals("4471", state.selectedText)
        assertEquals(TextRange(6, 10), state.selection)
    }

    @Test
    fun `ctrl a after a click selects the whole label and ctrl c copies it`() {
        val ui = open {
            SelectionContainer { Text(seed, Modifier.testTag("seed")) }
        }

        ui.click("seed")
        ui.key(Key.A, ctrl)
        ui.key(Key.C, ctrl)

        assertEquals(seed, backend.clipboard.read())
    }

    @Test
    fun `shift with the arrows and end moves the far end of the selection`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        ui.click(ui.edge("seed", seed, 6))
        ui.key(Key.Right, Modifiers.Shift)
        ui.key(Key.Right, Modifiers.Shift)
        assertEquals("8F", state.selectedText)

        ui.key(Key.End, Modifiers.Shift)
        assertEquals("8F3A-22C1", state.selectedText)
    }

    @Test
    fun `a click with nothing dragged copies nothing`() {
        val ui = open {
            SelectionContainer { Text(seed, Modifier.testTag("seed")) }
        }

        ui.click("seed")

        assertTrue(!ui.key(Key.C, ctrl), "ctrl c with an empty selection carries on outwards")
        assertNull(backend.clipboard.read())
    }

    @Test
    fun `a label outside a container cannot be selected`() {
        val ui = open {
            Text(seed, Modifier.testTag("seed"))
        }

        ui.drag("seed", seed, from = 0, to = 15)
        ui.key(Key.A, ctrl)
        ui.key(Key.C, ctrl)

        assertNull(backend.clipboard.read())
        assertNull(ui.focus.focused, "a plain label takes no focus")
    }

    @Test
    fun `the selection is drawn behind the glyphs in the selection colour`() {
        val ui = open {
            SelectionContainer { Text(seed, Modifier.testTag("seed")) }
        }
        val blue = Colour.rgb(0x3F6BD6)

        ui.render()
        assertTrue(backend.canvas.calls.none { it is DrawCall.Rectangle && it.colour == blue }, "nothing selected yet")

        ui.drag("seed", seed, from = 6, to = 15)
        backend.canvas.clear()
        ui.render()

        val box = ui.node("seed").boundsInRoot
        val highlight = backend.canvas.calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == blue }
        val character = box.width / seed.length
        assertEquals(box.left + 6 * character, highlight.rect.left, 0.01f)
        assertEquals(box.left + 15 * character, highlight.rect.right, 0.01f)

        val calls = backend.canvas.calls
        assertTrue(
            calls.indexOf(highlight) < calls.indexOfFirst { it is DrawCall.Text },
            "the highlight goes behind the text, not over it",
        )
    }

    @Test
    fun `pressing a second label moves the selection to it`() {
        val state = SelectionState()
        val address = "play.example.net:25565"
        val ui = open {
            SelectionContainer(state = state) {
                Column {
                    Text(seed, Modifier.testTag("seed"))
                    Text(address, Modifier.testTag("address"))
                }
            }
        }

        ui.drag("seed", seed, from = 0, to = 4)
        assertEquals("Seed", state.selectedText)

        ui.drag("address", address, from = 17, to = 22)
        assertEquals("25565", state.selectedText)
        ui.key(Key.C, ctrl)
        assertEquals("25565", backend.clipboard.read())
    }

    @Test
    fun `focus moving to a button clears the selection`() {
        val state = SelectionState()
        var clicks = 0
        val ui = open {
            SelectionContainer(state = state) {
                Column {
                    Text(seed, Modifier.testTag("seed"))
                    Button("COPY", onClick = { clicks++ }, modifier = Modifier.testTag("copy"))
                }
            }
        }

        ui.drag("seed", seed, from = 0, to = 4)
        assertEquals("Seed", state.selectedText)

        ui.click("copy")

        assertEquals(1, clicks, "a button inside a container still clicks")
        ui.assertFocused("copy")
        assertEquals("", state.selectedText)
    }

    @Test
    fun `the pad walks a menu inside a container without stopping on its labels`() {
        val ui = open {
            SelectionContainer {
                Column {
                    Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
                    Text("Seed: 8F3A-22C1", Modifier.testTag("seed"))
                    Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
                }
            }
        }

        ui.assertFocused("play")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("quit")
        ui.key(Key.Tab)
        ui.assertFocused("play")
    }

    @Test
    fun `a click puts keyboard focus on the label and keeps it there`() {
        val ui = open {
            SelectionContainer {
                Column {
                    Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
                    Text(seed, Modifier.testTag("seed"))
                }
            }
        }

        ui.assertFocused("play")
        ui.drag("seed", seed, from = 0, to = 4)
        ui.advanceBy(500)

        ui.assertFocused("seed")
        ui.key(Key.C, ctrl)
        assertEquals("Seed", backend.clipboard.read(), "ctrl c talks to the label rather than the button")
    }

    @Test
    fun `a checkbox label inside a container still toggles`() {
        var checked by mutableStateOf(false)
        val ui = open {
            SelectionContainer {
                Checkbox(checked, onCheckedChange = { checked = it }, label = "Hardcore", modifier = Modifier.testTag("box"))
            }
        }

        ui.click("box")

        assertTrue(checked)
    }

    @Test
    fun `text inside disable selection cannot be selected`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) {
                DisableSelection { Text(seed, Modifier.testTag("seed")) }
            }
        }

        ui.drag("seed", seed, from = 0, to = 15)

        assertEquals("", state.selectedText)
    }

    @Test
    fun `a label whose text changes drops its selection`() {
        val state = SelectionState()
        var shown by mutableStateOf(seed)
        val ui = open {
            SelectionContainer(state = state) { Text(shown, Modifier.testTag("seed")) }
        }

        ui.drag("seed", seed, from = 6, to = 15)
        assertEquals("8F3A-22C1", state.selectedText)

        shown = "Seed: 0000-1111"
        ui.settle()

        assertEquals("", state.selectedText)
        assertTrue(!ui.key(Key.C, ctrl))
        assertNull(backend.clipboard.read())
    }

    @Test
    fun `a game can clear the selection from outside`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        ui.drag("seed", seed, from = 6, to = 15)
        state.clear()
        ui.settle()
        backend.canvas.clear()
        ui.render()

        assertEquals("", state.selectedText)
        assertTrue(backend.canvas.calls.none { it is DrawCall.Rectangle && it.colour == Colour.rgb(0x3F6BD6) })
    }

    @Test
    fun `a wrapped label inside a container lays out the same as outside`() {
        val long = "the quick brown fox jumps over the lazy dog"
        val ui = open {
            Column {
                Text(long, Modifier.testTag("plain").width(200f))
                SelectionContainer { Text(long, Modifier.testTag("selectable").width(200f)) }
            }
        }

        assertEquals(ui.node("plain").boundsInRoot.size, ui.node("selectable").boundsInRoot.size)
        assertEquals(ui.texts("plain").joinToString(" "), ui.texts("selectable").joinToString(" "))
    }

    @Test
    fun `a still screen with a selection on it draws nothing new`() {
        val ui = open {
            SelectionContainer { Text(seed, Modifier.testTag("seed")) }
        }

        ui.drag("seed", seed, from = 6, to = 15)
        ui.render()

        assertFalse(ui.render(), "nothing moved, so nothing is drawn again")
    }

    @Test
    fun `triple clicking selects the whole line`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        val on = ui.edge("seed", seed, 8)
        repeat(3) { ui.click(on) }

        assertEquals(seed, state.selectedText)
    }

    @Test
    fun `shift clicking extends the selection from where it started`() {
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
        }

        ui.click(ui.edge("seed", seed, 6))
        ui.keyDown(Key.Shift, Modifiers.Shift)
        ui.click(ui.edge("seed", seed, 10))
        ui.keyUp(Key.Shift)

        assertEquals("8F3A", state.selectedText)
    }

    @Test
    fun `a right click on a label is left for whatever is underneath`() {
        var sink: InputSink? = null
        var menus = 0
        val ui = uiTest(Size(600f, 400f), backend, input = { it.also { sink = it } }) {
            Box(Modifier.testTag("panel").onPointer(PointerHandler { e ->
                (e is PointerEvent.Press && e.button == PointerButton.Secondary).also { if (it) menus++ }
            })) {
                SelectionContainer { Text(seed, Modifier.testTag("seed")) }
            }
        }.also { opened += it }

        val at = ui.edge("seed", seed, 3)
        ui.moveTo(at)
        val events = checkNotNull(sink)
        events.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Secondary))
        events.onPointer(PointerEvent.Release(PointerId.Mouse, at))
        ui.settle()

        assertEquals(1, menus)
    }

    @Test
    fun `pad and tab move on from a label a click focused`() {
        val ui = open {
            SelectionContainer {
                Column {
                    Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
                    Text(seed, Modifier.testTag("seed"))
                    Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
                }
            }
        }

        ui.click("seed")
        ui.assertFocused("seed")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("quit")

        ui.click("seed")
        ui.key(Key.Tab)
        assertTrue(ui.focus.focused != null && ui.focus.focused !== ui.node("seed"), "tab leaves the label for a real stop")
    }

    @Test
    fun `a label removed while it holds the selection takes the selection with it`() {
        val state = SelectionState()
        var shown by mutableStateOf(true)
        val ui = open {
            SelectionContainer(state = state) {
                Column {
                    if (shown) Text(seed, Modifier.testTag("seed"))
                    Button("BACK", onClick = {}, modifier = Modifier.testTag("back"))
                }
            }
        }

        ui.drag("seed", seed, from = 6, to = 15)
        assertEquals("8F3A-22C1", state.selectedText)

        shown = false
        ui.settle()

        assertEquals("", state.selectedText)
        assertTrue(!ui.key(Key.C, ctrl))
        assertNull(backend.clipboard.read())
    }

    @Test
    fun `a clickable run inside a selectable label still clicks and the rest still selects`() {
        val state = SelectionState()
        val text = "see the tincture note"
        val clicked = mutableListOf<Any?>()
        val ui = open {
            SelectionContainer(state = state) {
                Text(
                    text,
                    Modifier.testTag("line"),
                    runs = listOf(TextRun(TextRange(8, 16), tag = "tincture")),
                    onRunClick = { clicked += it.tag },
                )
            }
        }

        ui.click(ui.edge("line", text, 10))
        assertEquals(listOf<Any?>("tincture"), clicked)

        ui.drag("line", text, from = 0, to = 3)
        assertEquals("see", state.selectedText)
    }

    @Test
    fun `an inner container keeps its own selection and ctrl c copies the inner one`() {
        val outer = SelectionState()
        val inner = SelectionState()
        val code = "LOBBY-9"
        val ui = open {
            SelectionContainer(state = outer) {
                Column {
                    Text(seed, Modifier.testTag("seed"))
                    SelectionContainer(state = inner) { Text(code, Modifier.testTag("code")) }
                }
            }
        }

        ui.drag("code", code, from = 0, to = 5)
        assertEquals("LOBBY", inner.selectedText)
        assertEquals("", outer.selectedText)

        ui.key(Key.C, ctrl)
        assertEquals("LOBBY", backend.clipboard.read())
    }

    @Test
    fun `an empty label inside a container copies nothing`() {
        val ui = open {
            SelectionContainer {
                Column {
                    Text("", Modifier.testTag("empty").width(100f))
                }
            }
        }

        ui.click("empty")
        ui.key(Key.A, ctrl)
        assertTrue(!ui.key(Key.C, ctrl), "nothing to copy")
        assertNull(backend.clipboard.read())
    }

    @Test
    fun `a clickable card keeps its click when its text is wrapped in disable selection`() {
        var opens = 0
        val ui = open {
            SelectionContainer {
                Column {
                    Box(Modifier.testTag("card").clickable { opens++ }) {
                        DisableSelection { Text("Open save slot 1", Modifier.testTag("slot")) }
                    }
                }
            }
        }

        ui.click("slot")

        assertEquals(1, opens)
    }
    @Test
    fun `a stepper inside a container still steps when its arrow or value is pressed`() {
        var volume by mutableStateOf(5)
        val state = SelectionState()
        val ui = open {
            SelectionContainer(state = state) {
                NumberStepper(volume, onValueChange = { volume = it }, range = 0..10, modifier = Modifier.testTag("volume"))
            }
        }

        ui.click(ui.node("volume").children[2].boundsInRoot.centre)
        assertEquals(6, volume, "the right arrow steps up")
        ui.click(ui.node("volume").children[1].boundsInRoot.centre)
        assertEquals(7, volume, "a click on the value steps too")
        assertEquals("", state.selectedText)
    }

    @Test
    fun `a hotbar slot inside a container is used when its letters are clicked`() {
        val used = mutableListOf<Int>()
        val ui = open {
            SelectionContainer {
                Hotbar(listOf(HotbarSlot(label = "FIRE"), HotbarSlot(label = "ICE")), onUse = { used += it }, modifier = Modifier.testTag("bar"))
            }
        }

        ui.click(ui.node("bar").children[1].boundsInRoot.centre)

        assertEquals(listOf(1), used)
    }

    @Test
    fun `a notification inside a container is dismissed by a click on its text`() {
        val queue = NotificationQueue(holdMillis = 60_000)
        val ui = open {
            SelectionContainer { Notifications(queue, Modifier.testTag("notices")) }
        }
        queue.show("Quest updated")
        ui.advanceBy(500)

        ui.click(ui.node("notices").children[0].boundsInRoot.centre)
        ui.advanceBy(500)

        assertTrue(queue.shown.isEmpty(), "the click reached the card rather than selecting its text")
    }

    @Test
    fun `a dropdown inside a container opens when its shown option is clicked`() {
        var language by mutableStateOf("English")
        val state = SelectionState()
        val ui = open {
            PopupHost {
                SelectionContainer(state = state) {
                    Dropdown(
                        options = listOf("English", "French"),
                        selected = language,
                        onSelect = { language = it },
                        modifier = Modifier.width(160f).testTag("language"),
                    ) { Text(it, Modifier.testTag("option $it")) }
                }
            }
        }

        ui.click(ui.node("option English").boundsInRoot.centre)
        assertEquals("", state.selectedText, "the click opened the list rather than selecting the text")
        val french = assertNotNull(ui.root.findAll("option French").lastOrNull(), "the list did not open")

        ui.click(checkNotNull(french.parent).boundsInRoot.centre)

        assertEquals("French", language)
    }

    @Test
    fun `shift let go while focus was elsewhere does not turn the next click into an extend`() {
        val state = SelectionState()
        val ui = open {
            Column {
                SelectionContainer(state = state) { Text(seed, Modifier.testTag("seed")) }
                // Outside the container, so the key up that lets go of shift never passes through it.
                Button("BACK", onClick = {}, modifier = Modifier.testTag("back"))
            }
        }

        ui.click(ui.edge("seed", seed, 6))
        ui.keyDown(Key.Shift, Modifiers.Shift)
        ui.click("back")
        ui.keyUp(Key.Shift)

        ui.click(ui.edge("seed", seed, 10))

        assertEquals("", state.selectedText, "a plain click is a caret, not a selection from the start")
    }
}
