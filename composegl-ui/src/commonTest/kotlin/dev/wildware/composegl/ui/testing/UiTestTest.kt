package dev.wildware.composegl.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.InputSink
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.onTextEvent
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LocalInputSource
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real screens, driven by a mouse, a keyboard and a pad through [uiTest], and judged by what they
 * show.
 *
 * Nothing here reaches into a widget. Each test composes the screen a game would, sends the events
 * a player would, and reads the result off focus and off the text that was drawn.
 */
class UiTestTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size = Size(400f, 300f),
        onBack: () -> Unit = {},
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, onBack = onBack, content = content).also { opened += it }

    // --- the screens ---------------------------------------------------------------------------

    /** A title menu, and the options screen behind it. */
    @Composable
    private fun MainMenu() {
        var screen by remember { mutableStateOf("menu") }
        if (screen == "menu") {
            Column {
                Button("PLAY", onClick = { screen = "playing" }, initialFocus = true, modifier = Modifier.testTag("play"))
                Button("OPTIONS", onClick = { screen = "options" }, modifier = Modifier.testTag("options"))
                Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
            }
        } else {
            Column {
                Text(screen.uppercase(), Modifier.testTag("title"))
                OnBack { screen = "menu" }
                Button("BACK", onClick = { screen = "menu" }, initialFocus = true, modifier = Modifier.testTag("back"))
            }
        }
    }

    /** A name box, and a Continue button that only works once there is a name in it. */
    @Composable
    private fun NewGame(onContinue: (String) -> Unit) {
        var name by remember { mutableStateOf("") }
        Column {
            TextField(name, onValueChange = { name = it }, modifier = Modifier.testTag("name"))
            Button(
                "CONTINUE",
                onClick = { onContinue(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("continue"),
            )
        }
    }

    // --- the pointer ---------------------------------------------------------------------------

    @Test
    fun `a click on a tagged button runs it and the screen shows the result`() {
        val ui = open {
            var count by remember { mutableStateOf(0) }
            Column {
                Text("$count", Modifier.testTag("count"))
                Button("ADD", onClick = { count++ }, modifier = Modifier.testTag("add"))
            }
        }
        ui.assertText("count", "0")

        assertTrue(ui.click("add"), "the button took the press")
        ui.click("add")

        ui.assertText("count", "2")
        ui.assertText("add", "ADD")
    }

    @Test
    fun `a click lands where a button is after the last click moved it`() {
        val pressed = mutableListOf<String>()
        val ui = open {
            var banner by remember { mutableStateOf(false) }
            Column {
                Button("SHOW", onClick = { banner = true; pressed += "show" }, modifier = Modifier.testTag("show"))
                if (banner) Box(Modifier.size(200f, 120f).testTag("banner"))
                Button("GO", onClick = { pressed += "go" }, modifier = Modifier.testTag("go"))
            }
        }
        val before = ui.node("go").boundsInRoot.top

        ui.click("show")
        ui.assertExists("banner")
        assertEquals(before + 120f, ui.node("go").boundsInRoot.top, "the banner pushed GO down")

        ui.click("go")
        assertEquals(listOf("show", "go"), pressed, "the second click found GO where it is now")
    }

    @Test
    fun `hovering and holding show on the widget and a release elsewhere is no click`() {
        val state = InteractionState()
        var clicks = 0
        val ui = open {
            Column {
                Button("HOLD", onClick = { clicks++ }, interaction = state, modifier = Modifier.testTag("hold"))
                Box(Modifier.size(100f, 100f).testTag("away"))
            }
        }

        ui.moveTo("hold")
        assertTrue(state.isHovered)
        assertFalse(state.isPressed)

        ui.press("hold")
        assertTrue(state.isPressed)

        ui.moveTo("away")
        ui.release()
        assertFalse(state.isPressed)
        assertEquals(0, clicks, "dragged off before letting go")
    }

    @Test
    fun `clicking a node that is off the screen fails with the tree printed`() {
        val ui = open(size = Size(200f, 100f)) {
            Box(Modifier.offset(0f, 500f).size(50f, 20f).testTag("far"))
        }

        val failure = assertFailsWith<AssertionError> { ui.click("far") }
        assertTrue("off the 200x100 screen" in failure.message.orEmpty(), failure.message)
        assertTrue("#far" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `clicking a node with no area fails rather than doing nothing`() {
        val ui = open { Column { Box(Modifier.testTag("empty")) } }

        val failure = assertFailsWith<AssertionError> { ui.click("empty") }
        assertTrue("no area" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a misspelt tag fails with the tags that are there`() {
        val ui = open { MainMenu() }

        val failure = assertFailsWith<IllegalStateException> { ui.click("plya") }
        assertTrue("#play" in failure.message.orEmpty(), failure.message)
    }

    // --- the keyboard --------------------------------------------------------------------------

    @Test
    fun `typing into the name field enables Continue`() {
        val continued = mutableListOf<String>()
        val ui = open { NewGame { continued += it } }

        ui.click("continue")
        assertEquals(emptyList(), continued, "Continue does nothing with no name")

        ui.click("name")
        ui.assertFocused("name")
        assertTrue(ui.type("Ada"))
        ui.assertText("name", "Ada")

        ui.click("continue")
        assertEquals(listOf("Ada"), continued)
    }

    @Test
    fun `tab and enter get from the name field to Continue without a mouse`() {
        val continued = mutableListOf<String>()
        val ui = open { NewGame { continued += it } }

        ui.key(Key.Tab)
        ui.assertFocused("name")
        ui.type("Grace")
        ui.key(Key.Tab)
        ui.assertFocused("continue")
        ui.key(Key.Enter)

        assertEquals(listOf("Grace"), continued)
    }

    @Test
    fun `a field keeps the arrow keys it needs and the menu gets the rest`() {
        val ui = open {
            Column {
                // A button to the left of the field, so Left going to navigation first would take
                // focus off the field rather than move its caret.
                Row {
                    Button("BACK", onClick = {}, modifier = Modifier.testTag("left"))
                    TextField("abc", onValueChange = {}, initialFocus = true, modifier = Modifier.testTag("field"))
                }
                Button("OK", onClick = {}, modifier = Modifier.testTag("ok"))
            }
        }
        ui.assertFocused("field")

        assertTrue(ui.key(Key.Left), "the caret moved")
        ui.assertFocused("field")

        ui.key(Key.Down)
        ui.assertFocused("ok")
    }

    @Test
    fun `a pulse on a stopped clock does not keep the test waiting`() {
        val ui = open {
            val clocks = LocalClocks.current
            remember { clocks.stop(Clock.World) }
            val pulse = remember { Animatable(1f, FloatVectoriser, Clock.World, clocks) }
            LaunchedEffect(Unit) {
                while (true) {
                    pulse.animateTo(0.5f, Tween(durationMillis = 300))
                    pulse.animateTo(1f, Tween(durationMillis = 300))
                }
            }
            Text("${(pulse.value * 100).toInt()}", Modifier.testTag("pulse"))
        }

        ui.assertText("pulse", "100")
    }

    @Test
    fun `typing sends one character at a time and keeps accents and emoji whole`() {
        val received = mutableListOf<String>()
        val ui = open {
            Box(Modifier.size(100f, 20f).focusable(initial = true).onTextEvent { received += it.text; true })
        }

        ui.type("aé👍")

        assertEquals(listOf("a", "é", "👍"), received)
    }

    @Test
    fun `a full field refuses the rest of what was typed and says so`() {
        val ui = open {
            var name by remember { mutableStateOf("") }
            TextField(name, onValueChange = { name = it }, maxLength = 3, initialFocus = true, modifier = Modifier.testTag("name"))
        }

        assertFalse(ui.type("Adam"))
        ui.assertText("name", "Ada")
    }

    @Test
    fun `typing with nothing focused fails`() {
        val ui = open { Text("nothing to type into") }

        val failure = assertFailsWith<AssertionError> { ui.type("hello") }
        assertTrue("nothing focused" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `shift tab walks focus backwards`() {
        val ui = open { MainMenu() }
        ui.assertFocused("play")

        ui.key(Key.Tab, Modifiers.Shift)

        ui.assertFocused("quit")
    }

    // --- the pad -------------------------------------------------------------------------------

    @Test
    fun `pressing down then south opens the options screen`() {
        val ui = open { MainMenu() }
        ui.assertFocused("play")

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("options")
        ui.pad(GamepadButton.South)

        ui.assertText("title", "OPTIONS")
        ui.assertDoesNotExist("play")
        ui.assertFocused("back")
    }

    @Test
    fun `east goes back through the screen's own OnBack before the game's`() {
        var leftTheGame = 0
        val ui = open(onBack = { leftTheGame++ }) { MainMenu() }
        ui.click("options")
        ui.assertExists("title")

        ui.pad(GamepadButton.East)
        ui.assertExists("play")
        assertEquals(0, leftTheGame, "the options screen answered back itself")

        ui.pad(GamepadButton.East)
        assertEquals(1, leftTheGame, "nothing on the menu wanted back, so it reached the game")
    }

    @Test
    fun `escape on the keyboard goes back the same way`() {
        val ui = open { MainMenu() }
        ui.pad(GamepadButton.DpadDown)
        ui.key(Key.Enter)
        ui.assertExists("title")

        ui.key(Key.Escape)

        ui.assertFocused("play")
    }

    @Test
    fun `a held stick moves once then repeats after the delay`() {
        val ui = open {
            Column {
                repeat(6) { Button("ITEM $it", onClick = {}, initialFocus = it == 0, modifier = Modifier.testTag("item$it")) }
            }
        }

        ui.stick(0f, 1f)
        ui.assertFocused("item1")

        // Settling after each action is frames too, so the wait is counted from the clock rather
        // than added up by hand: a quarter of a second in, still well short of the first repeat.
        val pushed = ui.nanos
        ui.advanceBy(250L - (ui.nanos - pushed) / 1_000_000L - 50L)
        assertTrue(ui.nanos - pushed < 400_000_000L, "still inside the first-repeat delay")
        ui.assertFocused("item1")

        ui.advanceBy(300)
        val afterRepeat = ui.focus.focused
        assertTrue(afterRepeat !== ui.node("item1") && afterRepeat !== ui.node("item0"), "held past the delay it stepped on")

        ui.stick(0f, 0f)
        ui.advanceBy(1000)
        assertTrue(ui.focus.focused === afterRepeat, "let go of the stick and it stopped")
    }

    @Test
    fun `a stick named by its axes moves those axes and not the left stick`() {
        val seen = mutableListOf<GamepadEvent>()
        val ui = uiTest(Size(400f, 300f), input = { sink ->
            object : InputSink by sink {
                override fun onGamepad(event: GamepadEvent) = seen.add(event).let { sink.onGamepad(event) }
            }
        }) {
            Column {
                Button("ONE", onClick = {}, initialFocus = true, modifier = Modifier.testTag("one"))
                Button("TWO", onClick = {}, modifier = Modifier.testTag("two"))
            }
        }.also { opened += it }

        ui.stick(0.5f, 1f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        assertEquals(
            listOf(GamepadAxis.RightX to 0.5f, GamepadAxis.RightY to 1f),
            seen.map { (it as GamepadEvent.Axis).let { axis -> axis.axis to axis.value } },
        )
        ui.assertFocused("one")
    }

    @Test
    fun `a wrapped sink sees every event before the screen does`() {
        val seen = mutableListOf<String>()
        val ui = uiTest(Size(400f, 300f), input = { sink ->
            object : InputSink by sink {
                override fun onPointer(event: PointerEvent) = seen.add("pointer").let { sink.onPointer(event) }
                override fun onKey(event: KeyEvent) = seen.add("key").let { sink.onKey(event) }
                override fun onGamepad(event: GamepadEvent) = seen.add("pad").let { sink.onGamepad(event) }
            }
        }) {
            var clicked by remember { mutableStateOf(false) }
            Column {
                Button(if (clicked) "DONE" else "GO", onClick = { clicked = true }, modifier = Modifier.testTag("go"))
            }
        }.also { opened += it }

        ui.click("go")
        ui.key(Key.Tab)
        ui.pad(GamepadButton.DpadDown)

        ui.assertText("go", "DONE")
        assertTrue(seen.containsAll(listOf("pointer", "key", "pad")), "it saw $seen")
    }

    @Test
    fun `a step the held stick takes is on the screen whenever the test looks`() {
        val states = List(20) { InteractionState() }
        val ui = open(size = Size(400f, 1400f)) {
            Column {
                val lit = states.indexOfFirst { it.isFocused }
                Text("$lit", Modifier.testTag("lit"))
                states.forEachIndexed { index, state ->
                    Button(
                        "ITEM $index",
                        onClick = {},
                        interaction = state,
                        initialFocus = index == 0,
                        modifier = Modifier.testTag("item$index"),
                    )
                }
            }
        }

        ui.stick(0f, 1f)
        // One frame at a time, so a repeat lands at every point of a settle, the last quiet frame
        // included. Whatever focus is on, the screen has to have caught up with it.
        repeat(120) {
            ui.advanceBy(17)
            val on = states.indexOfFirst { it.isFocused }
            ui.assertText("lit", "$on")
            assertTrue(ui.focus.focused === ui.node("item$on"), "focus and the widget agree")
        }
        ui.assertFocused("item19")
    }

    @Test
    fun `the device last used is what the screen reads`() {
        val ui = open {
            Column {
                Text(LocalInputSource.current.current.name, Modifier.testTag("source"))
                Button("OK", onClick = {}, initialFocus = true, modifier = Modifier.testTag("ok"))
            }
        }

        ui.pad(GamepadButton.DpadDown)
        ui.assertText("source", "Gamepad")
        ui.key(Key.Down)
        ui.assertText("source", "Keyboard")
        ui.click("ok")
        ui.assertText("source", "Mouse")
        assertEquals(InputSource.Mouse, ui.source.current)
    }

    // --- time ----------------------------------------------------------------------------------

    @Test
    fun `an animation a click started has finished by the time the test looks`() {
        val ui = open {
            var shown by remember { mutableStateOf(false) }
            val level by animateFloatAsState(if (shown) 100f else 0f, Tween(durationMillis = 2_000))
            Column {
                Text("${level.toInt()}", Modifier.testTag("level"))
                Button("SHOW", onClick = { shown = true }, modifier = Modifier.testTag("show"))
            }
        }

        ui.click("show")

        ui.assertText("level", "100")
    }

    @Test
    fun `advanceBy lets a wait run out that changes nothing until it ends`() {
        val ui = open {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val start = withFrameNanos { it }
                var now = start
                while (now - start < 1_000_000_000L) now = withFrameNanos { it }
                ready = true
            }
            Text(if (ready) "GO" else "WAIT", Modifier.testTag("light"))
        }
        ui.assertText("light", "WAIT")

        ui.advanceBy(500)
        ui.assertText("light", "WAIT")

        ui.advanceBy(600)
        ui.assertText("light", "GO")
    }

    @Test
    fun `a screen that changes every frame forever fails instead of hanging`() {
        val failure = assertFailsWith<AssertionError> {
            open {
                var ticks by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) { while (true) withFrameNanos { ticks++ } }
                Text("$ticks")
            }
        }
        assertTrue("still changing" in failure.message.orEmpty(), failure.message)
    }

    // --- reading the screen --------------------------------------------------------------------

    @Test
    fun `assertFocused says where focus is instead`() {
        val ui = open { MainMenu() }

        val failure = assertFailsWith<AssertionError> { ui.assertFocused("quit") }
        assertTrue("#play" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertText says what was drawn instead`() {
        val ui = open { NewGame {} }
        ui.click("name")
        ui.type("Ada")

        val failure = assertFailsWith<AssertionError> { ui.assertText("name", "Bob") }
        assertTrue("\"Ada\"" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `text is what a row of labels draws in order`() {
        val ui = open {
            Row(Modifier.testTag("row")) {
                Text("HP")
                Text("42")
            }
        }

        assertEquals(listOf("HP", "42"), ui.texts("row"))
        ui.assertText("row", "HP\n42")
    }

    @Test
    fun `text faded to nothing by a parent is not there to read`() {
        val ui = open {
            var hidden by remember { mutableStateOf(false) }
            Column {
                Box(Modifier.alpha(if (hidden) 0f else 1f)) { Text("SECRET", Modifier.testTag("secret")) }
                Button("HIDE", onClick = { hidden = true }, modifier = Modifier.testTag("hide"))
            }
        }
        ui.assertText("secret", "SECRET")

        ui.click("hide")

        ui.assertText("secret", "")
    }

    @Test
    fun `text on a panel shrunk to nothing is not there to read`() {
        val ui = open {
            var closed by remember { mutableStateOf(false) }
            Column {
                Box(Modifier.size(100f, 40f).scale(if (closed) 0f else 1f)) { Text("MENU", Modifier.testTag("menu")) }
                Button("CLOSE", onClick = { closed = true }, modifier = Modifier.testTag("close"))
            }
        }
        ui.assertText("menu", "MENU")

        ui.click("close")

        ui.assertText("menu", "")
    }

    @Test
    fun `render draws a frame into the backend canvas`() {
        val ui = open { MainMenu() }

        ui.render()

        val canvas = ui.backend.canvas as RecordingCanvas
        assertEquals(1, canvas.frames)
        assertEquals(listOf("PLAY", "OPTIONS", "QUIT"), canvas.texts())
        canvas.assertBalanced()
    }

    @Test
    fun `scroll turns the wheel over the tagged node`() {
        var scrolled = Offset.Zero
        val ui = open {
            Box(
                Modifier.size(100f, 100f).testTag("map").onPointer { event ->
                    if (event is PointerEvent.Scroll) scrolled = event.delta
                    event is PointerEvent.Scroll
                },
            )
        }

        assertTrue(ui.scroll("map", Offset(0f, 3f)))

        assertEquals(Offset(0f, 3f), scrolled)
    }

    @Test
    fun `close lets go of the composition`() {
        val ui = uiTest { MainMenu() }

        ui.close()

        assertTrue(ui.host.isDisposed)
    }
}
