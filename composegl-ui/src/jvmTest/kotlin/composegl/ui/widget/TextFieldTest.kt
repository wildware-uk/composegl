package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.backend.InMemoryClipboard
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.backend.RecordingSoftKeyboard
import composegl.ui.draw.DrawPass
import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.KeyRouter
import composegl.ui.input.Modifiers
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.input.TextEvent
import composegl.ui.layout.Column
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.width
import composegl.ui.skin.Skin
import composegl.ui.skin.SkinDrawable
import composegl.ui.text.TextFieldValue
import composegl.ui.text.TextRange
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The field itself: what a player sees and what the keyboard alone can do with it.
 *
 * Everything underneath was tested before this — the editing model, the movement, the gestures, the
 * keyboard. What is left, and what is here, is the part that has to be *seen*: a caret that is
 * where the caret is, a highlight behind the selected words, and a field that scrolls so a long
 * name does not type itself off the right-hand edge.
 *
 * The font pretends every character is 9.6 units wide (0.6 of a 16-unit text size), so an expected
 * number in a test can be worked out on paper.
 */
class TextFieldTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val router = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val clipboard = InMemoryClipboard()

    /** One character of the test font, at the default text size. */
    private val character = 16f * 0.6f

    @AfterEach
    fun tearDown() {
        host.dispose()
        Modifiers.isMac = false
    }

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { ProvideClipboard(clipboard) { content() } } }
        frames(2)
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

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun key(key: Key, modifiers: Modifiers = Modifiers.None) {
        val down = KeyEvent(key, KeyEventType.Down, modifiers)
        if (!router.onKey(down)) keys.onKey(down)
        val up = KeyEvent(key, KeyEventType.Up, modifiers)
        if (!router.onKey(up)) keys.onKey(up)
        frame()
    }

    private fun type(text: String) {
        text.forEach { router.onText(TextEvent(it.toString())) }
        frame()
    }

    private fun click(x: Float, y: Float = 10f) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y), timeMillis = clock / 1_000_000))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y), timeMillis = clock / 1_000_000))
        frame()
    }

    private fun rectangles() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    private fun fill(style: String): composegl.ui.graphics.Colour =
        (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    private fun caret(): DrawCall.Rectangle? = rectangles().lastOrNull { it.colour == fill("field.caret") }

    private fun highlight(): List<DrawCall.Rectangle> = rectangles().filter { it.colour == fill("field.selection") }

    private fun drawn(): List<String> = canvas.calls.filterIsInstance<DrawCall.Text>().map { it.text }

    /** Where the field's text was actually drawn, which moves when the field scrolls. */
    private fun textLeft(): Float = canvas.calls.filterIsInstance<DrawCall.Text>().first().at.x

    // --- typing ---------------------------------------------------------------------------------------

    @Test
    fun `a field takes what is typed into it`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, initialFocus = true) }

        type("Ryland")

        assertEquals("Ryland", text)
        assertTrue("Ryland" in drawn())
    }

    @Test
    fun `backspace and the arrows work without a mouse ever being used`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, initialFocus = true) }

        type("Rylnd")
        key(Key.Left)
        key(Key.Left)
        type("a")

        assertEquals("Ryland", text)

        key(Key.End)
        key(Key.Backspace)
        assertEquals("Rylan", text)
    }

    @Test
    fun `a burst of typing between two frames keeps every character`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, initialFocus = true) }

        // No frame in between: this is a fast typist, a held key, or a word committed by an input
        // method. Each character has to edit the result of the one before it.
        "burst".forEach { router.onText(TextEvent(it.toString())) }
        frame()

        assertEquals("burst", text)
    }

    @Test
    fun `the owner of the value has the last word on it`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it.uppercase() }, initialFocus = true) }

        type("ab")
        assertEquals("AB", text, "a field that filters what it is given keeps filtering")

        // And a value changed from outside — a clear button, a screen loading a saved name — is the
        // one the next keystroke edits, rather than the field carrying on from its own old copy.
        text = ""
        frames(2)

        type("c")

        assertEquals("C", text)
    }

    @Test
    fun `a disabled field ignores the keyboard`() {
        var text by mutableStateOf("locked")
        show { TextField(text, onValueChange = { text = it }, enabled = false, initialFocus = true) }

        type("x")

        assertEquals("locked", text)
    }

    @Test
    fun `a character limit stops typing and trims a paste`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, maxLength = 4, initialFocus = true) }

        type("abcdef")
        assertEquals("abcd", text, "the first four went in and the rest did nothing")

        text = ""
        frames(2)
        clipboard.write("abcdefgh")
        key(Key.V, Modifiers.Control)

        assertEquals("abcd", text, "a paste fills the room that is left")
    }

    // --- the caret --------------------------------------------------------------------------------------

    @Test
    fun `the caret is drawn where the caret is`() {
        show {
            TextField(
                TextFieldValue("hello", TextRange(2)),
                onValueChange = {},
                modifier = Modifier.width(200f),
                initialFocus = true,
            )
        }

        val caret = caret() ?: error("no caret drawn")
        val textStart = textLeft()
        assertEquals(textStart + 2 * character, caret.rect.left, 0.5f)
    }

    @Test
    fun `an unfocused field has no caret`() {
        show {
            Column {
                Button("ELSEWHERE", onClick = {}, initialFocus = true)
                TextField("hello", onValueChange = {})
            }
        }

        assertFalse(caret() != null, "a caret in a field nobody is in is a lie about where typing goes")
    }

    @Test
    fun `the caret blinks, and stops blinking while typing`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, initialFocus = true) }

        // Half a second of frames: the caret must go away at some point.
        var wentAway = false
        repeat(40) {
            frame()
            if (caret() == null) wentAway = true
        }
        assertTrue(wentAway, "a caret that never blinks is not a caret")

        type("a")
        var missing = 0
        repeat(20) {
            frame()
            if (caret() == null) missing++
        }
        assertEquals(0, missing, "typing shows a steady caret rather than one winking mid-word")
    }

    // --- selection --------------------------------------------------------------------------------------

    @Test
    fun `a selection is drawn behind the words`() {
        show {
            TextField(
                TextFieldValue("hello world", TextRange(0, 5)),
                onValueChange = {},
                modifier = Modifier.width(200f),
                initialFocus = true,
            )
        }

        val highlight = highlight().singleOrNull() ?: error("no highlight: ${rectangles().map { it.colour }}")
        assertEquals(5 * character, highlight.rect.right - highlight.rect.left, 0.5f)

        val text = canvas.calls.filterIsInstance<DrawCall.Text>().first()
        val highlightIndex = canvas.calls.indexOf(highlight)
        assertTrue(highlightIndex < canvas.calls.indexOf(text), "behind, not over the top of")
    }

    @Test
    fun `shift and an arrow selects, and typing replaces the selection`() {
        var value by mutableStateOf(TextFieldValue("hello world", TextRange(0)))
        show { TextField(value, onValueChange = { value = it }, initialFocus = true) }

        repeat(5) { key(Key.Right, Modifiers.Shift) }
        assertEquals("hello", value.selected)

        type("bye")

        assertEquals("bye world", value.text)
    }

    @Test
    fun `a click puts the caret where it was clicked`() {
        var value by mutableStateOf(TextFieldValue("hello world", TextRange(0)))
        show {
            TextField(value, onValueChange = { value = it }, modifier = Modifier.width(300f))
        }
        val field = host.root.children.first()
        val padding = Skin.Default.resolve("field").padding

        click(field.boundsInRoot.left + padding.left + 3 * character + 1f)

        assertEquals(3, value.selection.start)
    }

    @Test
    fun `a double click takes a word`() {
        var value by mutableStateOf(TextFieldValue("hello world", TextRange(0)))
        show {
            TextField(value, onValueChange = { value = it }, modifier = Modifier.width(300f))
        }
        val field = host.root.children.first()
        val padding = Skin.Default.resolve("field").padding
        val at = field.boundsInRoot.left + padding.left + 8 * character

        click(at)
        click(at)

        assertEquals("world", value.selected)
    }

    // --- scrolling --------------------------------------------------------------------------------------

    @Test
    fun `typing past the right edge scrolls, and the caret stays visible`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, modifier = Modifier.width(120f), initialFocus = true) }
        val field = host.root.children.first()

        type("a very long name indeed")

        val caret = caret() ?: error("no caret")
        assertTrue(
            caret.rect.right <= field.boundsInRoot.right + 0.5f && caret.rect.left >= field.boundsInRoot.left - 0.5f,
            "the caret is off the edge: $caret against ${field.boundsInRoot}",
        )
        val padding = Skin.Default.resolve("field").padding
        assertTrue(
            textLeft() < field.boundsInRoot.left + padding.left,
            "which it is only because the text moved left",
        )
    }

    @Test
    fun `going back to the start shows the first character again`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, modifier = Modifier.width(120f), initialFocus = true) }
        val field = host.root.children.first()
        type("a very long name indeed")

        key(Key.Home)

        val padding = Skin.Default.resolve("field").padding
        assertEquals(field.boundsInRoot.left + padding.left, textLeft(), 0.5f, "scrolled back to the start")
        val caret = caret() ?: error("no caret")
        assertEquals(textLeft(), caret.rect.left, 0.5f, "with the caret before the first character")
    }

    @Test
    fun `the text is clipped to the field rather than spilling out of it`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, modifier = Modifier.width(120f), initialFocus = true) }
        val field = host.root.children.first()

        type("a very long name indeed")

        val text_ = canvas.calls.filterIsInstance<DrawCall.Text>().first()
        assertTrue(
            text_.clip.left >= field.boundsInRoot.left - 0.5f && text_.clip.right <= field.boundsInRoot.right + 0.5f,
            "drawn with a clip of ${text_.clip} inside ${field.boundsInRoot}",
        )
    }

    // --- the rest ----------------------------------------------------------------------------------------

    @Test
    fun `a placeholder shows only while there is nothing to read`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, placeholder = "your name", initialFocus = true) }

        assertTrue("your name" in drawn())

        type("R")

        assertFalse("your name" in drawn(), "the hint goes the moment there is something to say")
        assertTrue("R" in drawn())
    }

    @Test
    fun `tab leaves the field rather than being typed into it`() {
        var text by mutableStateOf("")
        show {
            Column {
                TextField(text, onValueChange = { text = it }, initialFocus = true)
                Button("NEXT", onClick = {})
            }
        }
        val field = focus.focused

        key(Key.Tab)

        assertTrue(focus.focused !== field, "a field a pad cannot leave is a trap")
        assertEquals("", text)
    }

    @Test
    fun `enter submits a single-line field and makes a line in a multi-line one`() {
        var submitted = 0
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, onSubmit = { submitted++ }, initialFocus = true) }

        type("go")
        key(Key.Enter)

        assertEquals(1, submitted)
        assertEquals("go", text, "and Enter put nothing in a field that has one line")
    }

    @Test
    fun `a multi-line field keeps the lines`() {
        var text by mutableStateOf("")
        show { TextField(text, onValueChange = { text = it }, multiline = true, initialFocus = true) }

        type("one")
        key(Key.Enter)
        type("two")

        assertEquals("one\ntwo", text)
        assertEquals(listOf("one", "two"), drawn(), "drawn as two lines, not one with a square in it")
    }

    // --- the phone's keyboard ------------------------------------------------------------------------
    //
    // Half of what a phone needs, and the half that can be checked without one. What is left is the
    // keyboard covering the bottom third of the screen, which needs a height LibGDX does not report
    // and a device to try it on.

    @Test
    fun `the keyboard comes up with the field and goes away with it`() {
        val keyboard = RecordingSoftKeyboard()
        var focused by mutableStateOf(true)
        show {
            Column {
                Button("ELSEWHERE", onClick = {}, initialFocus = !focused)
                TextField("", onValueChange = {}, softKeyboard = keyboard, initialFocus = focused)
            }
        }

        assertEquals(listOf("show"), keyboard.requests)

        key(Key.Tab)

        assertEquals(listOf("show", "hide"), keyboard.requests, "focus left, so the keyboard should")
    }

    @Test
    fun `a field leaving the screen while focused puts the keyboard away`() {
        val keyboard = RecordingSoftKeyboard()
        var showing by mutableStateOf(true)
        show { if (showing) TextField("", onValueChange = {}, softKeyboard = keyboard, initialFocus = true) }

        assertEquals(listOf("show"), keyboard.requests)

        showing = false
        frames(2)

        assertFalse(keyboard.isVisible, "a dialogue closing over a field left the keyboard up")
    }

    @Test
    fun `a disabled field does not raise the keyboard`() {
        val keyboard = RecordingSoftKeyboard()
        show { TextField("", onValueChange = {}, enabled = false, softKeyboard = keyboard) }

        assertTrue(keyboard.requests.isEmpty())
    }

    @Test
    fun `cut and paste go through the clipboard the game provided`() {
        var value by mutableStateOf(TextFieldValue("hello world", TextRange(0, 5)))
        show { TextField(value, onValueChange = { value = it }, initialFocus = true) }

        key(Key.X, Modifiers.Control)
        assertEquals("hello", clipboard.read())
        assertEquals(" world", value.text)

        key(Key.End)
        key(Key.V, Modifiers.Control)

        assertEquals(" worldhello", value.text)
    }
}
