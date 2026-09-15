package dev.wildware.composegl.webgl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.TextField
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A whole interface in a browser tab, driven the way a player drives one: pointer events on the
 * canvas, key events, a paste, an input method composing, a pad — all real DOM events, all through
 * [BrowserUi]'s own wiring — and judged by what came out on the WebGL canvas and in the screen's state.
 */
class BrowserUiTest {

    private val red = Colour.rgb(0xFF0000)
    private val green = Colour.rgb(0x00FF00)
    private val blue = Colour.rgb(0x0000FF)

    private var frameNanos = 0L

    /** A page with [ui] on a canvas [cssWidth] by [cssHeight], closed and taken off the page afterwards. */
    private suspend fun page(
        cssWidth: Int = 320,
        cssHeight: Int = 240,
        design: Size = Size(320f, 240f),
        pads: () -> JsArray<JsGamepad?> = { padSlots() },
        content: @Composable () -> Unit,
        test: suspend (BrowserUi, HTMLCanvasElement) -> Unit,
    ) {
        val element = pageCanvas(cssWidth, cssHeight)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        val ui = BrowserUi(backend, design, pads = pads, content = content)
        try {
            frames(ui)
            test(ui, element)
        } finally {
            ui.close()
            backend.close()
            element.remove()
        }
    }

    /** A few frames, enough for an event's state change to be recomposed, laid out and drawn. */
    private fun frames(ui: BrowserUi, count: Int = 4) = repeat(count) {
        frameNanos += 16_666_667L
        ui.frame(frameNanos)
    }

    private fun pixel(ui: BrowserUi, x: Int, y: Int): Int {
        val width = ui.backend.element.width
        return readFrame(ui.backend.gl, width, ui.backend.element.height)[y * width + x]
    }

    private fun assertColour(expected: Colour, actual: Int, because: String) {
        val close = kotlin.math.abs(expected.red - red(actual)) < 8 &&
            kotlin.math.abs(expected.green - green(actual)) < 8 &&
            kotlin.math.abs(expected.blue - blue(actual)) < 8
        assertTrue(close, "$because: expected $expected, got #${actual.toString(16).padStart(6, '0')}")
    }

    @Composable
    private fun Switch() {
        var on by remember { mutableStateOf(false) }
        Box(
            Modifier.offset(100f, 100f).size(100f, 100f)
                .background(if (on) green else red)
                .clickable { on = true }
                .testTag("switch"),
        )
    }

    @Test
    fun `a click on the canvas turns the square green on the screen`() = runTest(timeout = 2.minutes) {
        page(content = { Switch() }) { ui, canvas ->
            assertColour(red, pixel(ui, 150, 150), "before the click")
            click(canvas, 150.0, 150.0)
            frames(ui)
            assertColour(green, pixel(ui, 150, 150), "after the click")
            assertEquals(InputSource.Mouse, ui.source.current)
        }
    }

    @Test
    fun `a click misses when it is outside the square`() = runTest(timeout = 2.minutes) {
        page(content = { Switch() }) { ui, canvas ->
            click(canvas, 50.0, 50.0)
            frames(ui)
            assertColour(red, pixel(ui, 150, 150), "a click at 50,50 is not on a square at 100,100")
        }
    }

    @Test
    fun `a click on a letterboxed canvas lands where the square is drawn`() = runTest(timeout = 2.minutes) {
        // Twice as wide as the design, so the design sits in the middle with 160 pixels of bar either side.
        page(cssWidth = 640, cssHeight = 240, content = { Switch() }) { ui, canvas ->
            assertEquals(640, canvas.width)
            assertColour(red, pixel(ui, 160 + 150, 150), "the square is drawn 160 to the right")
            click(canvas, 150.0, 150.0)
            frames(ui)
            assertColour(red, pixel(ui, 160 + 150, 150), "a click on the bar is not a click on the square")
            click(canvas, 160.0 + 150.0, 150.0)
            frames(ui)
            assertColour(green, pixel(ui, 160 + 150, 150), "a click on the square as drawn")
        }
    }

    /**
     * A card 160 square at (40, 40), red on its left half and blue on its right, seen by a camera
     * 200 away. A click flips it over, Right holds it at sixty degrees, and Escape lays it flat.
     */
    @Composable
    private fun Card() {
        var flipped by remember { mutableStateOf(false) }
        var held by remember { mutableStateOf<Float?>(null) }
        Box(
            Modifier.offset(40f, 40f).size(160f)
                .rotate3d(y = held ?: if (flipped) 180f else 0f, cameraDistance = 200f / CameraDistanceUnit)
                .background(red)
                .focusable(initial = true)
                .clickable { flipped = true }
                .onKeyEvent { event ->
                    when {
                        event.type != KeyEventType.Down -> false
                        event.key == Key.Escape -> { flipped = false; held = null; true }
                        event.key == Key.Right -> { held = 60f; true }
                        else -> false
                    }
                }
                .testTag("card"),
        ) {
            Box(Modifier.offset(80f, 0f).size(80f, 160f).background(blue))
        }
    }

    @Test
    fun `a click turns a rotate3d card over and escape lays it flat again`() = runTest(timeout = 2.minutes) {
        page(cssWidth = 240, cssHeight = 240, design = Size(240f, 240f), content = { Card() }) { ui, canvas ->
            assertColour(red, pixel(ui, 60, 120), "flat, red on the left")
            assertColour(blue, pixel(ui, 180, 120), "flat, blue on the right")

            click(canvas, 120.0, 120.0)
            frames(ui)
            assertColour(blue, pixel(ui, 60, 120), "turned over, blue has come round to the left")
            assertColour(red, pixel(ui, 180, 120), "and red to the right")

            tap(canvas, "Escape", "Escape")
            frames(ui)
            assertColour(red, pixel(ui, 60, 120), "laid flat again")
        }
    }

    @Test
    fun `a card held part way over is drawn in perspective without bending`() = runTest(timeout = 2.minutes) {
        page(cssWidth = 240, cssHeight = 240, design = Size(240f, 240f), content = { Card() }) { ui, canvas ->
            tap(canvas, "ArrowRight", "ArrowRight")
            frames(ui)
            assertTrue(ui.backend.canvas.tiltsLayers, "WebGL divides by depth rather than drawing it flat")
            // Near edge at 58.8, far edge at 149.7, and red meets blue at 120, where the camera
            // looks. Two flat triangles would put the meeting at 104.
            assertColour(red, pixel(ui, 112, 120), "the near red half takes more of the screen")
            assertColour(blue, pixel(ui, 126, 120), "blue from the middle on")
            assertColour(red, pixel(ui, 64, 30), "the near edge is taller than the box")
            assertFalse(blue(pixel(ui, 160, 120)) > 128, "the far edge has swung in past 150")
        }
    }

    @Test
    fun `a finger tap presses a button like a click does`() = runTest(timeout = 2.minutes) {
        page(content = { Switch() }) { ui, canvas ->
            click(canvas, 150.0, 150.0, pointerType = "touch")
            frames(ui)
            assertColour(green, pixel(ui, 150, 150), "after the tap")
            assertEquals(InputSource.Touch, ui.source.current)
        }
    }

    @Test
    fun `the canvas follows its size on the page`() = runTest(timeout = 2.minutes) {
        page(content = { Switch() }) { ui, canvas ->
            canvas.style.width = "480px"
            canvas.style.height = "360px"
            frames(ui, 1)
            assertEquals(480, canvas.width)
            assertEquals(360, canvas.height)
            assertEquals(Size(480f, 360f), ui.viewport.physical)
            // Scaled one and a half times: the square's middle is now at 225, 225.
            assertColour(red, pixel(ui, 225, 225), "the square scaled with the canvas")
            click(canvas, 225.0, 225.0)
            frames(ui)
            assertColour(green, pixel(ui, 225, 225), "a click on the scaled square")
        }
    }

    @Test
    fun `typing on the keyboard fills a focused field and backspace takes it away`() = runTest(timeout = 2.minutes) {
        var name = ""
        page(content = {
            var text by remember { mutableStateOf("") }
            TextField(text, { text = it; name = it }, Modifier.width(200f).testTag("name"), initialFocus = true)
        }) { ui, canvas ->
            assertEquals("name", ui.focus.focused?.testTag)
            tap(canvas, "A", "KeyA", shift = true)
            tap(canvas, "d", "KeyD")
            tap(canvas, "a", "KeyA")
            frames(ui)
            assertEquals("Ada", name)

            val prevented = !key(canvas, "keydown", "Backspace", "Backspace")
            key(canvas, "keyup", "Backspace", "Backspace")
            frames(ui)
            assertEquals("Ad", name)
            assertTrue(prevented, "a key the field used does not also reach the page")
        }
    }

    @Test
    fun `a focused field raises the text box an input method and a phone keyboard type into`() = runTest(timeout = 2.minutes) {
        page(content = {
            TextField("", {}, Modifier.width(200f).testTag("name"), initialFocus = true)
        }) { ui, _ ->
            assertSame(ui.backend.textBox, activeElement(), "the invisible text box has focus while the field does")
        }
    }

    @Test
    fun `tapping a field that already has focus keeps the phone keyboard up`() = runTest(timeout = 2.minutes) {
        var name = ""
        page(content = {
            var text by remember { mutableStateOf("") }
            TextField(text, { text = it; name = it }, Modifier.offset(20f, 20f).width(200f).testTag("name"), initialFocus = true)
        }) { ui, canvas ->
            val field = ui.host.root.findOrNull("name")!!.boundsInRoot
            // A finger on the field to move the caret. The field is focused already, so nothing starts
            // a new session; if the press took the page's focus to the canvas, the keyboard would drop.
            click(canvas, field.centre.x.toDouble(), field.centre.y.toDouble(), pointerType = "touch")
            frames(ui)
            assertSame(ui.backend.textBox, activeElement(), "the text box still has focus after the tap")
            input(ui.backend.textBox, "insertText", "k")
            frames(ui)
            assertEquals("k", name)
        }
    }

    @Test
    fun `tab moves focus between buttons and enter presses the focused one`() = runTest(timeout = 2.minutes) {
        var pressed = ""
        page(content = {
            Column {
                Button("First", { pressed = "first" }, Modifier.testTag("first"), initialFocus = true)
                Button("Second", { pressed = "second" }, Modifier.testTag("second"))
            }
        }) { ui, canvas ->
            assertEquals("first", ui.focus.focused?.testTag)
            val prevented = !key(canvas, "keydown", "Tab", "Tab")
            key(canvas, "keyup", "Tab", "Tab")
            frames(ui)
            assertEquals("second", ui.focus.focused?.testTag)
            assertTrue(prevented, "Tab moved focus in the interface, so it does not also leave the canvas")
            tap(canvas, "Enter", "Enter")
            frames(ui)
            assertEquals("second", pressed)
            assertEquals(InputSource.Keyboard, ui.source.current)
        }
    }

    @Test
    fun `ctrl v pastes what the browser says was on the clipboard`() = runTest(timeout = 2.minutes) {
        var name = ""
        page(content = {
            var text by remember { mutableStateOf("") }
            TextField(text, { text = it; name = it }, Modifier.width(200f), initialFocus = true)
        }) { ui, canvas ->
            key(canvas, "keydown", "v", "KeyV", control = true)
            frames(ui)
            assertEquals("", name, "nothing is pasted before the browser has said what is on the clipboard")
            paste(canvas, "Grace")
            key(canvas, "keyup", "v", "KeyV", control = true)
            frames(ui)
            assertEquals("Grace", name)
            assertEquals("Grace", ui.backend.clipboard.read())
        }
    }

    @Test
    fun `an input method composes into the field and commits what was chosen`() = runTest(timeout = 2.minutes) {
        var name = ""
        page(content = {
            var text by remember { mutableStateOf("") }
            TextField(text, { text = it; name = it }, Modifier.width(200f), initialFocus = true)
        }) { ui, _ ->
            val box = ui.backend.textBox
            composition(box, "compositionstart", "")
            composition(box, "compositionupdate", "に")
            composition(box, "compositionupdate", "にほ")
            frames(ui)
            assertEquals("にほ", name, "the provisional run is in the field while it is being chosen")
            assertTrue(ui.textInput.composing)

            composition(box, "compositionend", "日本")
            frames(ui)
            assertEquals("日本", name, "the choice replaced the provisional run rather than joining it")
            assertFalse(ui.textInput.composing)
        }
    }

    @Test
    fun `a phone keyboard's letters and backspace reach the field through the text box`() = runTest(timeout = 2.minutes) {
        var name = ""
        page(content = {
            var text by remember { mutableStateOf("") }
            TextField(text, { text = it; name = it }, Modifier.width(200f), initialFocus = true)
        }) { ui, _ ->
            val box = ui.backend.textBox
            input(box, "insertText", "h")
            input(box, "insertText", "i")
            frames(ui)
            assertEquals("hi", name)
            input(box, "deleteContentBackward", null)
            frames(ui)
            assertEquals("h", name)
        }
    }

    @Test
    fun `a pad's d-pad moves focus and south presses`() = runTest(timeout = 2.minutes) {
        var presses = 0
        var reading = padSlots()
        page(pads = { reading }, content = {
            Column {
                Button("First", {}, Modifier.testTag("first"), initialFocus = true)
                Button("Second", { presses++ }, Modifier.testTag("second"))
            }
        }) { ui, _ ->
            reading = padSlots(pad())
            frames(ui, 2)
            reading = padSlots(pad(pressed = setOf(13)))
            frames(ui, 2)
            reading = padSlots(pad())
            frames(ui, 2)
            assertEquals("second", ui.focus.focused?.testTag)
            assertEquals(InputSource.Gamepad, ui.source.current)

            reading = padSlots(pad(pressed = setOf(0)))
            frames(ui, 2)
            reading = padSlots(pad())
            frames(ui, 2)
            assertEquals(1, presses)
        }
    }

    @Test
    fun `hovering a field turns the page's cursor into an i-beam`() = runTest(timeout = 2.minutes) {
        page(content = {
            TextField("", {}, Modifier.offset(20f, 20f).width(200f).testTag("name"))
        }) { ui, canvas ->
            val field = ui.host.root.findOrNull("name")!!.boundsInRoot
            pointer(canvas, "pointermove", field.centre.x.toDouble(), field.centre.y.toDouble())
            frames(ui)
            assertEquals("text", canvas.style.cursor)
            pointer(canvas, "pointermove", 300.0, 230.0)
            frames(ui)
            assertEquals("default", canvas.style.cursor)
        }
    }

    @Test
    fun `the wheel over a scroll area scrolls it and not the page`() = runTest(timeout = 2.minutes) {
        val scroll = ScrollState()
        page(content = {
            ScrollArea(Modifier.size(200f, 100f).testTag("area"), scroll, bars = false) {
                Column {
                    Box(Modifier.size(200f, 100f).background(red))
                    Box(Modifier.size(200f, 300f).background(green))
                }
            }
        }) { ui, canvas ->
            assertColour(red, pixel(ui, 100, 90), "the top of the list before the wheel")
            val reachedPage = wheel(canvas, 100.0, 50.0, deltaY = 100.0)
            frames(ui, 30)
            assertTrue(scroll.y > 0f, "one notch down moved the list: ${scroll.y}")
            assertFalse(reachedPage, "a wheel the list used does not also scroll the page")
            repeat(5) { wheel(canvas, 100.0, 50.0, deltaY = 100.0) }
            frames(ui, 60)
            assertColour(green, pixel(ui, 100, 90), "the list scrolled far enough to show what was below")
        }
    }

    @Test
    fun `escape answers the innermost back handler and then the page's own`() = runTest(timeout = 2.minutes) {
        var menuOpen by mutableStateOf(true)
        var pageBacks = 0
        val element = pageCanvas(320, 240)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        val ui = BrowserUi(backend, Size(320f, 240f), onBack = { pageBacks++ }, pads = { padSlots() }) {
            Button("Play", {}, initialFocus = true)
            if (menuOpen) OnBack { menuOpen = false }
        }
        try {
            frames(ui)
            tap(element, "Escape", "Escape")
            frames(ui)
            assertFalse(menuOpen, "Escape closed the menu")
            assertEquals(0, pageBacks)
            tap(element, "Escape", "Escape")
            frames(ui)
            assertEquals(1, pageBacks, "with nothing left on the stack, Escape is the page's")
        } finally {
            ui.close()
            backend.close()
            element.remove()
        }
    }

    @Test
    fun `a press held when the tab loses focus is let go without a click`() = runTest(timeout = 2.minutes) {
        page(content = { Switch() }) { ui, canvas ->
            pointer(canvas, "pointerdown", 150.0, 150.0, button = 0, buttons = 1)
            frames(ui)
            blurWindow()
            frames(ui)
            pointer(canvas, "pointerup", 150.0, 150.0, button = 0)
            frames(ui)
            assertColour(red, pixel(ui, 150, 150), "the switch away cancelled the press, so letting go later is not a click")
        }
    }

    @Test
    fun `closing takes the interface off the page's events`() = runTest(timeout = 2.minutes) {
        var clicks by mutableIntStateOf(0)
        val element = pageCanvas(320, 240)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        val ui = BrowserUi(backend, Size(320f, 240f), pads = { padSlots() }) {
            Box(Modifier.size(320f, 240f).focusable().clickable { clicks++ })
        }
        try {
            frames(ui)
            click(element, 10.0, 10.0)
            frames(ui)
            assertEquals(1, clicks)
            ui.close()
            click(element, 10.0, 10.0)
            assertEquals(1, clicks, "a closed interface hears nothing")
        } finally {
            backend.close()
            element.remove()
        }
    }
}
