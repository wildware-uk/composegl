package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LocalInputSource
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Local co-op: two players' menus, a `UiHost` each, sharing one window and one [InputRouter].
 *
 * Every test composes both screens for real, sends the events a backend would send for the whole
 * window — pads by number, the keyboard, the mouse in window pixels — and reads each player's screen
 * separately, so "player two's stick moved player one's focus" is something that fails here.
 */
class SplitScreenUiTest {

    /** A 400x300 design in each half of an 800x300 window. */
    private val halves = Viewport.splitScreen(Size(400f, 300f), Size(800f, 300f), players = 2)

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun player(index: Int, content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), viewport = halves[index], content = content).also { opened += it }

    /** Something every player's half has: a menu, a counter per button, and the device they are on. */
    @Composable
    private fun Menu(play: InteractionState = remember { InteractionState() }) {
        var played by remember { mutableStateOf(0) }
        var quit by remember { mutableStateOf(0) }
        Column {
            Button("PLAY", onClick = { played++ }, initialFocus = true, interaction = play, modifier = Modifier.testTag("play"))
            Button("OPTIONS", onClick = {}, modifier = Modifier.testTag("options"))
            Button("QUIT", onClick = { quit++ }, modifier = Modifier.testTag("quit"))
            Text("played $played quit $quit", Modifier.testTag("count"))
            Text(LocalInputSource.current.current.name, Modifier.testTag("source"))
        }
    }

    private val padZero = GamepadId(0)
    private val padOne = GamepadId(1)

    /** Both players wired the way a game wires them, and every event settling both screens. */
    private inner class Couch(val one: UiTest, val two: UiTest, unclaimed: InputSink? = null) {
        val router = InputRouter(unclaimed).apply {
            assignGamepad(padZero, one.input)
            assignGamepad(padOne, two.input)
            assignKeyboard(one.input)
            assignPointer(halves[0], one.input)
            assignPointer(halves[1], two.input)
        }

        fun send(block: InputRouter.() -> Unit) {
            router.block()
            one.settle()
            two.settle()
        }

        fun pad(gamepad: GamepadId, button: GamepadButton) = send {
            onGamepad(GamepadEvent.ButtonDown(gamepad, button))
            onGamepad(GamepadEvent.ButtonUp(gamepad, button))
        }

        /** Where [tag] in [player]'s half is on the whole window: what a real mouse would report. */
        fun onWindow(player: UiTest, tag: String): Offset =
            player.viewport.toScreen(player.node(tag).boundsInRoot.centre)

        fun moveTo(at: Offset) = send { onPointer(PointerEvent.Move(PointerId.Mouse, at)) }
        fun press(at: Offset) = send { onPointer(PointerEvent.Press(PointerId.Mouse, at)) }
        fun release(at: Offset) = send { onPointer(PointerEvent.Release(PointerId.Mouse, at)) }

        fun click(at: Offset) {
            moveTo(at)
            press(at)
            release(at)
        }
    }

    private fun couch(unclaimed: InputSink? = null) = Couch(player(0) { Menu() }, player(1) { Menu() }, unclaimed)

    // --- pads ------------------------------------------------------------------------------------

    @Test
    fun `player two's d-pad moves only player two's focus`() {
        val couch = couch()
        couch.one.assertFocused("play")
        couch.two.assertFocused("play")

        couch.pad(padOne, GamepadButton.DpadDown)
        couch.pad(padOne, GamepadButton.DpadDown)

        couch.two.assertFocused("quit")
        couch.one.assertFocused("play")

        couch.pad(padZero, GamepadButton.DpadDown)

        couch.one.assertFocused("options")
        couch.two.assertFocused("quit")
    }

    @Test
    fun `south on each pad presses the button in that player's own menu`() {
        val couch = couch()
        couch.pad(padOne, GamepadButton.DpadDown)
        couch.pad(padOne, GamepadButton.DpadDown)

        couch.pad(padZero, GamepadButton.South)
        couch.pad(padOne, GamepadButton.South)

        couch.one.assertText("count", "played 1 quit 0")
        couch.two.assertText("count", "played 0 quit 1")
    }

    @Test
    fun `a pad nobody has joined does nothing until start gives it a player`() {
        lateinit var couch: Couch
        val joining = GamepadId(2)
        // The lobby: a pad that belongs to nobody joins as player two by pressing Start.
        val lobby = object : InputSink {
            override fun onPointer(event: PointerEvent) = false
            override fun onKey(event: KeyEvent) = false
            override fun onText(event: TextEvent) = false
            override fun onGamepad(event: GamepadEvent): Boolean {
                if (event !is GamepadEvent.ButtonDown || event.button != GamepadButton.Start) return false
                couch.router.assignGamepad(event.gamepadId, couch.two.input)
                return true
            }
        }
        couch = couch(unclaimed = lobby)

        couch.pad(joining, GamepadButton.DpadDown)
        couch.one.assertFocused("play")
        couch.two.assertFocused("play")

        couch.pad(joining, GamepadButton.Start)
        couch.pad(joining, GamepadButton.DpadDown)

        couch.two.assertFocused("options")
        couch.one.assertFocused("play")
        assertSame(couch.two.input, couch.router.ownerOf(joining))
    }

    @Test
    fun `a stick held while its pad changes hands stops moving the menu it left`() {
        val one = player(0) {
            Column {
                repeat(10) { Button("ITEM $it", onClick = {}, initialFocus = it == 0, modifier = Modifier.testTag("item$it")) }
            }
        }
        val couch = Couch(one, player(1) { Menu() })

        couch.send { onGamepad(GamepadEvent.Axis(padZero, GamepadAxis.LeftY, 1f)) }
        one.assertFocused("item1")

        couch.router.assignGamepad(padZero, couch.two.input)
        one.advanceBy(2000)

        one.assertFocused("item1")
    }

    // --- the keyboard ----------------------------------------------------------------------------

    @Test
    fun `typing goes into the keyboard player's field and not the other player's`() {
        @Composable
        fun NameEntry() {
            var name by remember { mutableStateOf("") }
            TextField(name, onValueChange = { name = it }, initialFocus = true, modifier = Modifier.testTag("name"))
        }
        val couch = Couch(player(0) { NameEntry() }, player(1) { NameEntry() })

        "Ada".forEach { letter -> couch.send { onText(TextEvent(letter.toString())) } }

        couch.one.assertText("name", "Ada")
        couch.two.assertText("name", "")

        couch.router.assignKeyboard(couch.two.input)
        couch.send { onText(TextEvent("B")) }

        couch.one.assertText("name", "Ada")
        couch.two.assertText("name", "B")
    }

    @Test
    fun `each player's prompts follow the device that player is using`() {
        val couch = couch()

        couch.send { onKey(KeyEvent(Key.Down, KeyEventType.Down)) }
        couch.pad(padOne, GamepadButton.DpadDown)

        couch.one.assertText("source", "Keyboard")
        couch.two.assertText("source", "Gamepad")
        couch.one.assertFocused("options")
        couch.two.assertFocused("options")
    }

    @Test
    fun `handing a pad to another player keeps the keyboard player's prompts on the keyboard`() {
        val couch = couch()
        couch.send { onKey(KeyEvent(Key.Down, KeyEventType.Down)) }
        couch.one.assertText("source", "Keyboard")

        // Player one gives up the pad they were not using: the router tells them it was unplugged.
        couch.send { assignGamepad(padZero, couch.two.input) }
        couch.one.assertText("source", "Keyboard")
        couch.two.assertText("source", "Mouse")

        // A pad really pulled out is not somebody picking one up either.
        couch.send { onGamepad(GamepadEvent.Disconnected(padOne)) }
        couch.two.assertText("source", "Mouse")

        couch.pad(padZero, GamepadButton.DpadDown)
        couch.two.assertText("source", "Gamepad")
        couch.one.assertText("source", "Keyboard")
    }

    // --- the pointer -----------------------------------------------------------------------------

    @Test
    fun `a click in the right half presses player two's button and not player one's`() {
        val couch = couch()
        val quit = couch.onWindow(couch.two, "quit")
        assertTrue(quit.x >= 400f, "player two's quit is on the right of the window, at $quit")

        couch.click(quit)

        couch.two.assertText("count", "played 0 quit 1")
        couch.one.assertText("count", "played 0 quit 0")

        couch.click(couch.onWindow(couch.one, "play"))

        couch.one.assertText("count", "played 1 quit 0")
        couch.two.assertText("count", "played 0 quit 1")
    }

    @Test
    fun `a click lands on the right button when each half is scaled up`() {
        val big = Viewport.splitScreen(Size(400f, 300f), Size(1600f, 600f), players = 2)
        val one = uiTest(Size(400f, 300f), viewport = big[0]) { Menu() }.also { opened += it }
        val two = uiTest(Size(400f, 300f), viewport = big[1]) { Menu() }.also { opened += it }
        val router = InputRouter().apply {
            assignPointer(big[0], one.input)
            assignPointer(big[1], two.input)
        }
        // Worked out by hand rather than with toScreen: twice the design, 800 across for player two.
        val quit = two.node("quit").boundsInRoot.centre
        val at = Offset(800f + quit.x * 2f, quit.y * 2f)

        router.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        router.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        router.onPointer(PointerEvent.Release(PointerId.Mouse, at))
        one.settle()
        two.settle()

        two.assertText("count", "played 0 quit 1")
        one.assertText("count", "played 0 quit 0")
    }

    @Test
    fun `hover moves from one player's button to the other's as the mouse crosses the divider`() {
        val playOne = InteractionState()
        val playTwo = InteractionState()
        val couch = Couch(player(0) { Menu(playOne) }, player(1) { Menu(playTwo) })

        couch.moveTo(couch.onWindow(couch.one, "play"))
        assertTrue(playOne.isHovered)
        assertFalse(playTwo.isHovered)

        couch.moveTo(couch.onWindow(couch.two, "play"))

        assertFalse(playOne.isHovered, "player one's button let go of the hover")
        assertTrue(playTwo.isHovered)
    }

    @Test
    fun `a press dragged across the divider stays with the player who started it`() {
        val playOne = InteractionState()
        val playTwo = InteractionState()
        val couch = Couch(player(0) { Menu(playOne) }, player(1) { Menu(playTwo) })
        val start = couch.onWindow(couch.one, "play")
        val end = couch.onWindow(couch.two, "play")

        couch.moveTo(start)
        couch.press(start)
        couch.moveTo(end)

        assertFalse(playTwo.isHovered, "player two's button is not lit by player one's drag")
        assertFalse(playTwo.isPressed)

        couch.release(end)

        couch.one.assertText("count", "played 0 quit 0")
        couch.two.assertText("count", "played 0 quit 0")

        couch.moveTo(end)
        assertTrue(playTwo.isHovered, "the next move, with nothing held, hovers player two")
        assertFalse(playOne.isHovered)
    }

    @Test
    fun `a player who leaves while holding their button is let go of and nothing is clicked`() {
        val playTwo = InteractionState()
        val couch = Couch(player(0) { Menu() }, player(1) { Menu(playTwo) })
        val play = couch.onWindow(couch.two, "play")

        couch.moveTo(play)
        couch.press(play)
        assertTrue(playTwo.isPressed)

        couch.send { unassign(couch.two.input) }

        assertFalse(playTwo.isPressed, "player two's button is not left held down")
        assertFalse(playTwo.isHovered, "or lit")
        couch.release(play)
        couch.pad(padOne, GamepadButton.South)
        couch.two.assertText("count", "played 0 quit 0")
        assertTrue(couch.router.gamepadsOf(couch.two.input).isEmpty())
    }

    @Test
    fun `four players each get the click made in their own quarter`() {
        val quarters = Viewport.splitScreen(Size(400f, 300f), Size(800f, 600f), players = 4)
        val players = quarters.map { viewport ->
            uiTest(Size(400f, 300f), viewport = viewport) { Menu() }.also { opened += it }
        }
        val router = InputRouter().apply { quarters.forEachIndexed { i, it -> assignPointer(it, players[i].input) } }

        fun click(at: Offset) {
            router.onPointer(PointerEvent.Move(PointerId.Mouse, at))
            router.onPointer(PointerEvent.Press(PointerId.Mouse, at))
            router.onPointer(PointerEvent.Release(PointerId.Mouse, at))
            players.forEach { it.settle() }
        }
        // Player four is bottom right: their quit, on the window.
        val quit = players[3].node("quit").boundsInRoot.centre
        click(Offset(400f + quit.x, 300f + quit.y))
        // Player three is bottom left: their play.
        val play = players[2].node("play").boundsInRoot.centre
        click(Offset(play.x, 300f + play.y))

        assertEquals(
            listOf("played 0 quit 0", "played 0 quit 0", "played 1 quit 0", "played 0 quit 1"),
            players.map { it.text("count") },
        )
    }

    @Test
    fun `each player's screen is laid out whole in its own half`() {
        val couch = couch()

        // The same design, the same layout: one HUD written once is right in both halves.
        assertEquals(couch.one.node("quit").boundsInRoot, couch.two.node("quit").boundsInRoot)
        assertEquals(couch.onWindow(couch.one, "quit").x + 400f, couch.onWindow(couch.two, "quit").x)
    }
}
