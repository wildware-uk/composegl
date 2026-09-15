package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputRouter
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyListState
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.rememberLazyListState
import korlibs.event.GameButton
import korlibs.event.GamePadUpdateEvent
import korlibs.event.MouseButton
import korlibs.event.MouseEvent
import korlibs.event.PauseEvent
import korlibs.event.Touch
import korlibs.event.TouchEvent
import korlibs.image.bitmap.Bitmap32
import korlibs.image.format.PNG
import korlibs.korge.view.Views
import korlibs.math.geom.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File
import korlibs.event.Key as KorgeKey
import korlibs.event.KeyEvent as KorgeKeyEvent

/**
 * A player at a KorGE window: real KorGE mouse, touch, key and pad events dispatched into a real
 * stage, the way the window dispatches them, and the composed interface judged by what it did —
 * its state, where focus is, and the pixels.
 *
 * Each test builds a [ComposeGlView], adds it to the shared test game's stage, and talks to it only
 * through `views.dispatch`. Nothing here calls the toolkit's routers directly.
 */
class KorgeInputScreenTest {

    private val side = KorgeGl.size

    /** What the composed screen did, written on KorGE's thread and read on the test's. */
    private class Seen {
        @Volatile var first = 0
        @Volatile var second = 0
        @Volatile var text = ""
        @Volatile var slider = 0f
        @Volatile var list: LazyListState? = null
    }

    private val seen = Seen()

    private fun backend() = KorgeBackend(
        KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(12, 13, 14, 16, 18, 22, 26)) },
    )

    /** Puts [content] on the stage in a view the size of the window, runs [test], and takes it off. */
    private fun onStage(
        configure: (ComposeGlView) -> Unit = {},
        content: @Composable () -> Unit,
        test: (ComposeGlView) -> Unit,
    ) {
        val backend = backend()
        val view = ComposeGlView(backend, Size(side.toFloat(), side.toFloat()))
        configure(view)
        view.setContent(content)
        try {
            KorgeGl.render { KorgeGl.stage.addChild(view) }
            KorgeGl.frames(3)
            test(view)
        } finally {
            KorgeGl.render { view.removeFromParent() }
            view.close()
            backend.close()
        }
    }

    // --- sending ---------------------------------------------------------------------------------

    /** Dispatches events inside one frame, as the window does, then lets two frames go by. */
    private fun send(block: Views.() -> Unit) {
        KorgeGl.render { KorgeGl.stage.views.block() }
        KorgeGl.frames(2)
    }

    private fun mouse(type: MouseEvent.Type, x: Int, y: Int, button: MouseButton = MouseButton.NONE) =
        MouseEvent(type = type, x = x, y = y, button = button)

    private fun click(x: Int, y: Int) = send {
        dispatch(mouse(MouseEvent.Type.MOVE, x, y))
        dispatch(mouse(MouseEvent.Type.DOWN, x, y, MouseButton.LEFT))
        dispatch(mouse(MouseEvent.Type.UP, x, y, MouseButton.LEFT))
    }

    private fun key(key: KorgeKey, shift: Boolean = false) = send {
        dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = key, shift = shift))
        dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = key, shift = shift))
    }

    /** Types [text] as an AWT window reports it: per character a press, the character, a release. */
    private fun type(text: String) = text.forEach { character ->
        val key = when {
            character == ' ' -> KorgeKey.SPACE
            else -> KorgeKey.valueOf(character.uppercaseChar().toString())
        }
        val shift = character.isUpperCase()
        send {
            if (shift) dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = KorgeKey.SHIFT, shift = true))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = key, shift = shift))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.TYPE, character = character, shift = shift))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = key, shift = shift))
            if (shift) dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = KorgeKey.SHIFT))
        }
    }

    /** One frame's pad snapshot, with [held] down on KorGE's pad 0. */
    private fun pad(vararg held: GameButton) = GamePadUpdateEvent().apply {
        gamepadsLength = 1
        gamepads[0].connected = true
        gamepads[0].index = 0
        held.forEach { gamepads[0].rawButtons[it.index] = 1f }
    }

    /** A tap of [button]: pressed in one snapshot, let go in the next. */
    private fun tap(button: GameButton) = send {
        dispatch(pad(button))
        dispatch(pad())
    }

    private fun touch(type: TouchEvent.Type, x: Int, y: Int, status: Touch.Status) = TouchEvent(type = type).apply {
        startFrame(type)
        touch(0, Point(x, y), status)
        endFrame()
    }

    // --- reading ---------------------------------------------------------------------------------

    private fun bounds(view: ComposeGlView, tag: String): Rect =
        KorgeGl.render { requireNotNull(view.host.root.findOrNull(tag)) { "nothing tagged $tag" }.boundsInRoot }

    private fun focused(view: ComposeGlView): String? = KorgeGl.render { view.focus.focused?.testTag }

    /** Frames go by until [check] holds, or the test fails saying [what]. */
    private fun waitFor(what: String, frames: Int = 90, check: () -> Boolean) {
        repeat(frames) { if (KorgeGl.render { check() }) return }
        fail<Unit>("$what, after $frames frames")
    }

    /** How many pixels inside [area], grown by [margin], differ between two pictures of the window. */
    private fun changed(before: Bitmap32, after: Bitmap32, area: Rect, margin: Int = 0): Int {
        val xs = (area.left.toInt() - margin).coerceAtLeast(0) until (area.right.toInt() + margin).coerceAtMost(side)
        val ys = (area.top.toInt() - margin).coerceAtLeast(0) until (area.bottom.toInt() + margin).coerceAtMost(side)
        return ys.sumOf { y -> xs.count { x -> before[x, y] != after[x, y] } }
    }

    private fun save(name: String, picture: Bitmap32) {
        val bytes = PNG.encode(picture)
        File("build/screenshots/$name.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { dir -> File(dir, "$name.png").also { it.parentFile.mkdirs() }.writeBytes(bytes) }
    }

    // --- screens ---------------------------------------------------------------------------------

    @Composable
    private fun Counter() {
        var clicks by remember { mutableStateOf(0) }
        Column(verticalArrangement = Arrangement.spacedBy(12f)) {
            Text("Clicked $clicks times", Modifier.testTag("count"))
            Button("CLICK ME", onClick = { clicks++; seen.first = clicks }, modifier = Modifier.testTag("button"))
        }
    }

    @Composable
    private fun TwoButtons() {
        Column(verticalArrangement = Arrangement.spacedBy(24f)) {
            Button("ONE", onClick = { seen.first++ }, modifier = Modifier.testTag("one"), initialFocus = true)
            Button("TWO", onClick = { seen.second++ }, modifier = Modifier.testTag("two"))
        }
    }

    // --- the mouse -------------------------------------------------------------------------------

    @Test
    fun `a KorGE mouse click presses a real button and its label changes in the pixels`() = onStage(content = { Counter() }) { view ->
        val button = bounds(view, "button")
        val count = bounds(view, "count")
        val before = KorgeGl.window()

        click(button.centre.x.toInt(), button.centre.y.toInt())

        waitFor("the button was clicked once, clicked ${seen.first}") { seen.first == 1 }
        val after = KorgeGl.window()
        assertTrue(changed(before, after, count) > 20, "the label's pixels changed after the click")
        assertEquals(InputSource.Mouse, view.source.current)
    }

    @Test
    fun `a view drawn at half size is still clicked where it is drawn`() = onStage(
        configure = { it.scale = 0.5 },
        content = {
            Box(Modifier.offset(200f, 200f).size(200f, 200f).background(Colour.rgb(0xFF0000)).clickable { seen.first++ })
        },
    ) { _ ->
        // The design's 200..400 square is drawn at 100..200 in the window.
        click(50, 50)
        click(150, 150)

        waitFor("the click inside the drawn square landed, clicked ${seen.first}") { seen.first == 1 }
        assertColour(Red, KorgeGl.window().at(150, 150), "the square is where the click was")
    }

    @Test
    fun `the mouse wheel scrolls a LazyColumn`() = onStage(content = {
        LazyColumn(
            count = 100,
            modifier = Modifier.fillMaxSize().testTag("list"),
            state = rememberLazyListState().also { seen.list = it },
        ) { index -> Text("Row $index", Modifier.height(40f)) }
    }) { _ ->
        val before = KorgeGl.window()

        send {
            dispatch(mouse(MouseEvent.Type.MOVE, 200, 200))
            dispatch(MouseEvent(type = MouseEvent.Type.SCROLL, x = 200, y = 200).apply { setScrollDelta(MouseEvent.ScrollDeltaMode.PIXEL, 0f, 3f, 0f) })
        }

        waitFor("the list scrolled past its first row") { (seen.list?.firstVisibleItem ?: 0) > 0 }
        assertTrue(changed(before, KorgeGl.window(), Rect(0f, 0f, side.toFloat(), 80f)) > 50, "the rows at the top are different rows")
    }

    @Test
    fun `dragging a slider with the mouse moves it to the end`() = onStage(content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
            var value by remember { mutableStateOf(0f) }
            Slider(value = value, onValueChange = { value = it; seen.slider = it }, modifier = Modifier.width(300f).testTag("slider"))
        }
    }) { view ->
        val slider = bounds(view, "slider")
        val y = slider.centre.y.toInt()
        val left = slider.left.toInt() + 4
        val right = slider.right.toInt() - 2
        val before = KorgeGl.window()

        send {
            dispatch(mouse(MouseEvent.Type.MOVE, left, y))
            dispatch(mouse(MouseEvent.Type.DOWN, left, y, MouseButton.LEFT))
        }
        (1..5).forEach { step -> send { dispatch(mouse(MouseEvent.Type.DRAG, left + (right + 40 - left) * step / 5, y)) } }
        send { dispatch(mouse(MouseEvent.Type.UP, right + 40, y, MouseButton.LEFT)) }

        waitFor("the slider followed the drag to its end, at ${seen.slider}") { seen.slider > 0.95f }
        assertTrue(changed(before, KorgeGl.window(), slider) > 30, "the thumb moved in the pixels")
    }

    @Test
    fun `a pause in the middle of a press cancels it rather than clicking`() = onStage(content = { Counter() }) { view ->
        val button = bounds(view, "button")
        val x = button.centre.x.toInt()
        val y = button.centre.y.toInt()

        send {
            dispatch(mouse(MouseEvent.Type.MOVE, x, y))
            dispatch(mouse(MouseEvent.Type.DOWN, x, y, MouseButton.LEFT))
            dispatch(PauseEvent())
            dispatch(mouse(MouseEvent.Type.UP, x, y, MouseButton.LEFT))
        }
        KorgeGl.frames(3)
        assertEquals(0, seen.first, "the app went to the background mid-press, which is not a click")

        click(x, y)
        waitFor("and the next click still works") { seen.first == 1 }
    }

    // --- touch -----------------------------------------------------------------------------------

    @Test
    fun `a finger tapping a button presses it, as touch`() = onStage(content = { Counter() }) { view ->
        val button = bounds(view, "button")
        val x = button.centre.x.toInt()
        val y = button.centre.y.toInt()

        send { dispatch(touch(TouchEvent.Type.START, x, y, Touch.Status.ADD)) }
        send { dispatch(touch(TouchEvent.Type.END, x, y, Touch.Status.REMOVE)) }

        waitFor("the tap clicked the button, clicked ${seen.first}") { seen.first == 1 }
        assertEquals(InputSource.Touch, view.source.current)
    }

    // --- keys and text ---------------------------------------------------------------------------

    @Test
    fun `clicking a text field and typing on the keyboard puts the text in it`() = onStage(content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Text("CALLSIGN")
                var text by remember { mutableStateOf("") }
                TextField(value = text, onValueChange = { text = it; seen.text = it }, modifier = Modifier.width(300f).testTag("name"))
            }
        }
    }) { view ->
        val field = bounds(view, "name")
        click(field.centre.x.toInt(), field.centre.y.toInt())
        waitFor("the click focused the field, focus is on ${focused(view)}") { view.focus.focused?.testTag == "name" }
        val empty = KorgeGl.window()

        type("Hello KorGF")
        waitFor("the field says what was typed, it says '${seen.text}'") { seen.text == "Hello KorGF" }

        // A typo: Backspace as AWT sends it, a key and a control character, then the right letter.
        send {
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = KorgeKey.BACKSPACE))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.TYPE, character = '\b'))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = KorgeKey.BACKSPACE))
        }
        type("E")

        waitFor("Backspace deleted one letter and nothing was inserted for it, it says '${seen.text}'") { seen.text == "Hello KorGE" }
        assertEquals(InputSource.Keyboard, view.source.current)
        val typed = KorgeGl.window()
        assertTrue(changed(empty, typed, field) > 150, "the letters are drawn in the field")
        save("korge-text-field", typed)
    }

    @Test
    fun `Tab moves focus and lights its ring, Enter presses, and Shift-Tab goes back`() = onStage(content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) { TwoButtons() }
    }) { view ->
        assertEquals("one", focused(view))
        val two = bounds(view, "two")
        val before = KorgeGl.window()

        key(KorgeKey.TAB)

        waitFor("Tab moved focus to the second button, it is on ${focused(view)}") { view.focus.focused?.testTag == "two" }
        assertEquals(InputSource.Keyboard, view.source.current, "a key makes the keyboard the thing in use")
        KorgeGl.frames(10)
        assertTrue(changed(before, KorgeGl.window(), two, margin = 6) > 20, "a focus ring is drawn round the second button")

        key(KorgeKey.ENTER)
        waitFor("Enter pressed the focused button, pressed ${seen.second}") { seen.second == 1 }
        assertEquals(0, seen.first)

        send {
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = KorgeKey.SHIFT, shift = true))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.DOWN, key = KorgeKey.TAB, shift = true))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = KorgeKey.TAB, shift = true))
            dispatch(KorgeKeyEvent(type = KorgeKeyEvent.Type.UP, key = KorgeKey.SHIFT))
        }
        waitFor("Shift-Tab brought focus back, it is on ${focused(view)}") { view.focus.focused?.testTag == "one" }
    }

    // --- pads ------------------------------------------------------------------------------------

    @Test
    fun `a pad's d-pad moves focus and South presses, from KorGE's pad snapshots`() = onStage(content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) { TwoButtons() }
    }) { view ->
        tap(GameButton.DOWN)

        waitFor("d-pad down moved focus, it is on ${focused(view)}") { view.focus.focused?.testTag == "two" }
        assertEquals(InputSource.Gamepad, view.source.current)

        tap(GameButton.BUTTON_SOUTH)
        waitFor("South pressed the focused button, pressed ${seen.second}") { seen.second == 1 }
        assertEquals(0, seen.first)
    }

    // --- split screen ----------------------------------------------------------------------------

    @Test
    fun `two players share the window through one KorgeInput and an InputRouter`() {
        val backend = backend()
        val half = Size(side / 2f, side.toFloat())
        fun player(onClick: () -> Unit, colour: Long) = ComposeGlView(backend, half, listens = false).also { view ->
            view.boxSize = half
            view.setContent {
                Box(Modifier.fillMaxSize().background(Colour.rgb(colour)).focusable(initial = true).clickable { onClick() })
            }
        }
        val one = player({ seen.first++ }, 0x2B4C7E)
        val two = player({ seen.second++ }, 0xC8702A).also { it.x = side / 2.0 }
        val router = InputRouter()
        val input = KorgeInput(router, { Viewport.oneToOne(Size(side.toFloat(), side.toFloat())) })
        var listening: AutoCloseable? = null
        try {
            KorgeGl.render {
                KorgeGl.stage.addChild(one)
                KorgeGl.stage.addChild(two)
                listening = input.listen(KorgeGl.stage)
            }
            KorgeGl.frames(3)
            KorgeGl.render {
                router.assignPointer(one.viewport, one.player)
                router.assignPointer(two.viewport, two.player)
                router.assignGamepad(GamepadId(0), one.player)
            }

            click(300, 200)
            waitFor("a click in the right half pressed player two's screen") { seen.second == 1 }
            assertEquals(0, seen.first, "and not player one's")

            tap(GameButton.BUTTON_SOUTH)
            waitFor("pad 0, player one's, pressed player one's screen") { seen.first == 1 }
            assertEquals(1, seen.second, "and not player two's")
        } finally {
            KorgeGl.render {
                listening?.close()
                one.removeFromParent()
                two.removeFromParent()
            }
            one.close()
            two.close()
            backend.close()
        }
    }
}
