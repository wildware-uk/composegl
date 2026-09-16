package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.skin.SkinOverride
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.PopupHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A floating debug window, driven the way somebody tuning a game drives it: dragging the title bar,
 * pulling an edge, clicking a slider, pressing the hotkey, cycling in with a pad. Every answer is
 * read off the screen — where the nodes landed, what the property says now, what went to the store.
 */
class DebugWindowTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(800f, 600f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    /** What a game tunes: a plain object with properties, as a game's own systems are. */
    private class Physics {
        var gravity by mutableStateOf(9.8f)
        var enemies by mutableStateOf(3)
        var godMode by mutableStateOf(false)
        var fog by mutableStateOf(Colour.rgb(0x204060))
        var difficulty by mutableStateOf(Difficulty.Normal)
        var waves = 0
    }

    private enum class Difficulty { Easy, Normal, Hard }

    private val store = MemoryDebugWindowStore()

    /** The game underneath, with one button of its own, and one window over it. */
    @Composable
    private fun Screen(
        physics: Physics,
        state: DebugWindowsState,
        initialPosition: Offset = Offset(20f, 20f),
        initialSize: Size? = null,
        onClose: (() -> Unit)? = null,
    ) {
        DebugWindowHost(state = state) {
            Button("PLAY", onClick = {}, modifier = Modifier.align(Alignment.BottomStart).testTag("play"))
            DebugWindow("Physics", initialPosition = initialPosition, initialSize = initialSize, onClose = onClose) {
                tweak("Gravity", physics::gravity, 0f..50f, step = 0.1f)
                tweak("Enemies", physics::enemies, 0..10)
                toggle("God mode", physics::godMode)
                choice("Difficulty", physics::difficulty, Difficulty.entries)
                colour("Fog", physics::fog)
                button("Spawn wave") { physics.waves++ }
                text("Waves", physics.waves.toString())
            }
        }
    }

    private fun UiTest.window(id: String = "Physics"): UiNode = node(DebugWindowTags.window(id))

    private fun UiTest.title(id: String = "Physics"): UiNode = node(DebugWindowTags.title(id))

    private fun UiTest.edge(edge: WindowEdge, id: String = "Physics"): UiNode = node(DebugWindowTags.edge(id, edge))

    private fun UiTest.control(label: String, id: String = "Physics"): UiNode = node(DebugWindowTags.control(id, label))

    private fun UiTest.drag(from: Offset, to: Offset) {
        press(from)
        dragTo(to)
        release()
    }

    // --- where it is --------------------------------------------------------------------------

    @Test
    fun `a window opens where it was told to and over the game`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        val window = ui.window()
        assertEquals(20f, window.layoutBoundsInRoot.left)
        assertEquals(20f, window.layoutBoundsInRoot.top)
        assertTrue(window.width < 800f, "as wide as what is in it, not the screen: ${window.width}")
        assertEquals(listOf("Physics"), state.windows)
    }

    @Test
    fun `the labels line up so every control starts in the same place`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        val lefts = listOf("Gravity", "God mode", "Difficulty")
            .map { ui.control(it).layoutBoundsInRoot.left }

        assertEquals(1, lefts.toSet().size, "controls at $lefts")
    }

    @Test
    fun `dragging the title bar moves the window and where it landed is saved`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        ui.drag(ui.title().boundsInRoot.centre, ui.title().boundsInRoot.centre + Offset(120f, 80f))

        assertEquals(Offset(140f, 100f), state.position("Physics"))
        assertEquals(140f, ui.window().layoutBoundsInRoot.left)
        assertTrue(store.saves > 0, "the drag was not saved")
        assertTrue(store.values.getValue("window:Physics").startsWith("140.0;100.0"), store.values.toString())
    }

    @Test
    fun `a window cannot be dragged so far off the screen that it cannot be dragged back`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        ui.drag(ui.title().boundsInRoot.centre, Offset(2000f, 2000f))

        val window = ui.window().layoutBoundsInRoot
        assertTrue(window.left < 800f - 8f, "the whole window is off the right: $window")
        assertTrue(window.top < 600f, "the whole window is off the bottom: $window")
    }

    @Test
    fun `dragging an edge resizes the window and what is in it then scrolls`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        val was = ui.window().let { Size(it.width, it.height) }

        val corner = ui.edge(WindowEdge.BottomRight).boundsInRoot.centre
        ui.drag(corner, corner + Offset(60f, -40f))

        val now = ui.window()
        assertEquals(was.width + 60f, now.width)
        assertEquals(was.height - 40f, now.height)
        assertEquals(Size(was.width + 60f, was.height - 40f), state.size("Physics"))
        val body = ui.node(DebugWindowTags.body("Physics"))
        assertTrue(body.height < was.height, "the contents should be inside a shorter window now")
    }

    @Test
    fun `dragging the left edge moves that edge and leaves the other one where it was`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        val right = ui.window().layoutBoundsInRoot.right

        val edge = ui.edge(WindowEdge.Left).boundsInRoot.centre
        ui.drag(edge, edge + Offset(-30f, 0f))

        assertEquals(right, ui.window().layoutBoundsInRoot.right, "the right edge moved too")
        assertEquals(-10f, state.position("Physics")?.x)
    }

    @Test
    fun `a window cannot be dragged smaller than its minimum`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        val corner = ui.edge(WindowEdge.BottomRight).boundsInRoot.centre
        ui.drag(corner, corner - Offset(2000f, 2000f))

        assertEquals(140f, ui.window().width)
        assertEquals(60f, ui.window().height)
    }

    @Test
    fun `dragging the top edge past the top of the screen stops there rather than growing downwards`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state, initialPosition = Offset(40f, 40f)) }
        val bottom = ui.window().layoutBoundsInRoot.bottom

        val edge = ui.edge(WindowEdge.Top).boundsInRoot.centre
        ui.drag(edge, edge - Offset(0f, 300f))

        val window = ui.window().layoutBoundsInRoot
        assertEquals(0f, window.top, "the top edge should have stopped at the top of the screen")
        assertEquals(bottom, window.bottom, "the bottom edge moved instead of the top one")
        assertEquals(0f, state.position("Physics")?.y, "the window is drawn at the top but says it is above it")

        // Nothing hidden above the screen to spend first: the next drag moves it straight away.
        val bar = ui.title().boundsInRoot.centre
        ui.drag(bar, bar + Offset(100f, 0f))

        assertEquals(140f, state.position("Physics")?.x)
        assertEquals(140f, ui.window().layoutBoundsInRoot.left)
    }

    @Test
    fun `a window with more in it than the screen is tall is fitted to the room under it`() {
        val state = DebugWindowsState(store)
        val ui = open(Size(300f, 200f)) {
            DebugWindowHost(state = state) {
                DebugWindow("Physics", initialPosition = Offset(10f, 10f)) {
                    repeat(20) { row -> tweak("Row $row", 0f, {}, 0f..10f) }
                }
            }
        }

        val window = ui.window().layoutBoundsInRoot
        assertEquals(10f, window.top)
        assertTrue(window.bottom <= 200f, "the window hangs off the bottom of the screen: $window")
        // So the bottom edge, with the grip on it, is somewhere the player can still grab.
        assertTrue(ui.edge(WindowEdge.Bottom).boundsInRoot.bottom <= 200f, "the grip is off the screen:\n" + ui.dump())
    }

    @Test
    fun `the corner grabs keep clear of the buttons on the title bar`() {
        val state = DebugWindowsState(store)
        var closes = 0
        val ui = open { Screen(Physics(), state, onClose = { closes++ }) }

        // The very corner of each button, which is where an invisible corner grab would reach in.
        val cross = ui.node(DebugWindowTags.close("Physics")).boundsInRoot
        ui.click(Offset(cross.right - 1f, cross.top + 1f))
        assertEquals(1, closes, "the top-right corner grab ate the press meant for the cross")

        val triangle = ui.node(DebugWindowTags.collapse("Physics")).boundsInRoot
        ui.click(Offset(triangle.left + 1f, triangle.top + 1f))
        assertTrue(state.isCollapsed("Physics"), "the top-left corner grab ate the press meant for the triangle")
    }

    @Test
    fun `a window given a size opens at it and scrolls what does not fit`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store), initialSize = Size(220f, 120f)) }

        assertEquals(Size(220f, 120f), ui.window().let { Size(it.width, it.height) })
        val body = ui.node(DebugWindowTags.body("Physics"))
        assertTrue(body.height < 120f, "the contents were not fitted inside the window")
        // The last line is below the window, which is what there is to scroll to.
        assertTrue(ui.control("Waves").layoutBoundsInRoot.top > body.boundsInRoot.bottom, ui.dump())
    }

    // --- folding and closing ------------------------------------------------------------------

    @Test
    fun `the triangle folds the window to its title bar and back`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        val open = ui.window().height

        ui.click(DebugWindowTags.collapse("Physics"))

        assertTrue(state.isCollapsed("Physics"))
        ui.assertDoesNotExist(DebugWindowTags.control("Physics", "Gravity"))
        assertTrue(ui.window().height < open, "still as tall as it was")
        assertEquals("collapsed", store.values.getValue("window:Physics").split(';').last())

        ui.click(DebugWindowTags.collapse("Physics"))

        assertFalse(state.isCollapsed("Physics"))
        ui.assertExists(DebugWindowTags.control("Physics", "Gravity"))
    }

    @Test
    fun `a double click on the title bar folds it too`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        val at = ui.title().boundsInRoot.centre
        ui.click(at)
        ui.click(at)

        assertTrue(state.isCollapsed("Physics"))
    }

    @Test
    fun `the cross is only there when there is something to do about it`() {
        val physics = Physics()
        var closed = 0
        val ui = open { Screen(physics, DebugWindowsState(store), onClose = { closed++ }) }

        ui.click(DebugWindowTags.close("Physics"))

        assertEquals(1, closed)

        val without = open { Screen(Physics(), DebugWindowsState(MemoryDebugWindowStore())) }
        without.assertDoesNotExist(DebugWindowTags.close("Physics"))
    }

    // --- which one is in front ------------------------------------------------------------------

    @Composable
    private fun TwoWindows(state: DebugWindowsState, physics: Physics) {
        DebugWindowHost(state = state) {
            Button("PLAY", onClick = {}, modifier = Modifier.align(Alignment.BottomStart).testTag("play"))
            DebugWindow("One", initialPosition = Offset(20f, 20f)) {
                tweak("Gravity", physics::gravity, 0f..50f)
            }
            // Well clear of the first one, so a click on one is never a click on the other.
            DebugWindow("Two", initialPosition = Offset(420f, 300f)) {
                toggle("God mode", physics::godMode)
            }
        }
    }

    @Test
    fun `a click on a window behind brings it to the front`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }

        assertEquals(listOf("One", "Two"), state.windows)
        ui.click(ui.title("One").boundsInRoot.centre)

        assertEquals(listOf("Two", "One"), state.windows)
    }

    @Test
    fun `focus arriving in a window brings it to the front`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }

        ui.click(ui.control("Gravity", "One").boundsInRoot.centre)

        assertEquals(listOf("Two", "One"), state.windows)
        assertTrue(ui.focus.focused?.isInside(ui.window("One")) == true, ui.dump())
    }

    // --- which one is lit ------------------------------------------------------------------------
    //
    // The window in front is the one drawn lit, whichever way it got there. These drive the two ways
    // in — a press on the chrome, a press on a control, the keyboard — and read the answer off the
    // picture rather than off the focus, because a lit bar behind the window on top is the bug.

    /** A skin that says plainly which window is lit: the active title bar is green, the others grey. */
    private val litSkin = SkinFormat.read(
        """{ "styles": {
            "debugwindow.title": { "background": { "fill": "#202020" } },
            "debugwindow.title.active": { "background": { "fill": "#00FF00" } }
        } }""",
    )

    @Composable
    private fun TwoLitWindows(state: DebugWindowsState, physics: Physics) {
        DebugWindowHost(state = state) {
            Button("PLAY", onClick = {}, modifier = Modifier.align(Alignment.BottomStart).testTag("play"))
            SkinOverride(litSkin) {
                DebugWindow("One", initialPosition = Offset(20f, 20f)) {
                    tweak("Gravity", physics::gravity, 0f..50f)
                }
                DebugWindow("Two", initialPosition = Offset(420f, 300f)) {
                    toggle("God mode", physics::godMode)
                }
            }
        }
    }

    /** Which of [ids] are drawn with the lit title bar, as the player sees them. */
    private fun UiTest.lit(vararg ids: String): List<String> {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        val painted = canvas.calls.filterIsInstance<DrawCall.Rectangle>()
        return ids.filter { id ->
            val bar = title(id).boundsInRoot
            painted.any { it.rect == bar && it.colour == Colour.rgb(0x00FF00) }
        }
    }

    @Test
    fun `pressing the title bar of the window behind lights it and dims the one in front`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoLitWindows(state, Physics()) }
        ui.click(ui.control("Gravity", "One").boundsInRoot.centre)
        assertEquals(listOf("One"), ui.lit("One", "Two"), "the window clicked into is not the lit one")

        ui.click(ui.title("Two").boundsInRoot.centre)

        assertEquals("Two", state.windows.last(), "the window whose title bar was pressed is not in front")
        assertEquals(listOf("Two"), ui.lit("One", "Two"), "the lit window is not the one in front:\n" + ui.dump())
    }

    @Test
    fun `pressing a control in the window behind lights it as well`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoLitWindows(state, Physics()) }
        ui.click(ui.title("Two").boundsInRoot.centre)
        assertEquals(listOf("Two"), ui.lit("One", "Two"))

        ui.click(ui.control("Gravity", "One").boundsInRoot.centre)

        assertEquals("One", state.windows.last(), "the window the control is in is not in front")
        assertEquals(listOf("One"), ui.lit("One", "Two"), "the lit window is not the one in front:\n" + ui.dump())
    }

    @Test
    fun `the keyboard moving focus into a window never leaves a window behind lit`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoLitWindows(state, Physics()) }
        ui.click(ui.title("Two").boundsInRoot.centre)
        ui.click("play")

        ui.key(Key.F6)

        assertEquals("One", state.windows.last(), "the window focus went into is not in front")
        assertEquals(listOf("One"), ui.lit("One", "Two"), "the lit window is not the one in front:\n" + ui.dump())
    }

    @Test
    fun `pressing a title bar lights the window without arming a control in it`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }

        ui.click(ui.title().boundsInRoot.centre)
        ui.key(Key.Enter)

        assertFalse(state.isCollapsed("Physics"), "Enter folded the window, so the press armed the triangle")
        assertEquals(0, physics.waves, "Enter pressed a button in the window")
    }

    @Test
    fun `closing the window focus is in hands it to the window now in front`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        var showTwo by mutableStateOf(true)
        val ui = open {
            DebugWindowHost(state = state) {
                Button("PLAY", onClick = {}, modifier = Modifier.align(Alignment.BottomStart).testTag("play"))
                DebugWindow("One", initialPosition = Offset(20f, 20f)) { tweak("Gravity", physics::gravity, 0f..50f) }
                if (showTwo) DebugWindow("Two", initialPosition = Offset(420f, 300f)) { toggle("God mode", physics::godMode) }
            }
        }
        ui.click(ui.title("Two").boundsInRoot.centre)
        assertEquals("Two", state.windows.last())

        showTwo = false
        ui.settle()

        assertEquals(listOf("One"), state.windows)
        assertTrue(ui.focus.focused?.isInside(ui.window("One")) == true, "focus went nowhere sensible:\n" + ui.dump())
    }

    @Test
    fun `putting the windows away hands focus back to the game`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }
        ui.click("play")
        ui.click(ui.title().boundsInRoot.centre)
        assertTrue(ui.focus.focused?.isInside(ui.window()) == true, "the press did not take focus into the window")

        ui.key(Key.F9)

        assertTrue(state.hidden)
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus did not go back to the game:\n" + ui.dump())
    }

    @Test
    fun `a press on a window does not reach the game underneath`() {
        val physics = Physics()
        var plays = 0
        val ui = open(Size(400f, 300f)) {
            DebugWindowHost(state = DebugWindowsState(store)) {
                Button("PLAY", onClick = { plays++ }, modifier = Modifier.fillMaxSize().testTag("play"))
                DebugWindow("Physics", initialPosition = Offset(20f, 20f)) {
                    text("Waves", physics.waves.toString())
                }
            }
        }

        ui.click(ui.window().boundsInRoot.centre)

        assertEquals(0, plays, "the click went through the window to the game")
    }

    // --- the controls ---------------------------------------------------------------------------

    @Test
    fun `a slider changes the property it was given and the readout follows`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        val slider = ui.control("Gravity").boundsInRoot
        ui.click(Offset(slider.right - 2f, slider.centre.y))

        assertTrue(physics.gravity > 45f, "the slider was clicked near its end but gravity is ${physics.gravity}")
        assertEquals(formatTweak(physics.gravity, 0.1f), ui.text(DebugWindowTags.row("Physics", "Gravity")).split("\n").last())
    }

    @Test
    fun `the keyboard works a tweak like any other slider`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        ui.click(ui.control("Gravity").boundsInRoot.centre)
        val from = physics.gravity
        ui.key(Key.Right)

        assertTrue(physics.gravity > from, "Right did not move it from $from")
    }

    @Test
    fun `a whole number tweak steps in whole numbers`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        val slider = ui.control("Enemies").boundsInRoot
        ui.click(Offset(slider.left + slider.width * 0.5f, slider.centre.y))

        assertEquals(5, physics.enemies)
    }

    @Test
    fun `a toggle turns god mode on and a pad can press it`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        ui.click(ui.control("God mode").boundsInRoot.centre)
        assertTrue(physics.godMode)

        ui.pad(GamepadButton.South)
        assertFalse(physics.godMode, "the pad's South should have pressed the focused toggle")
    }

    @Test
    fun `a choice opens a list and picking one sets the property`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        ui.click(ui.control("Difficulty").boundsInRoot.centre)
        val options = ui.root.findAll(DebugWindowTags.option("Physics", "Difficulty", "Hard"))
        assertTrue(options.isNotEmpty(), "the list did not open:\n" + ui.dump())
        // The field shows the chosen option's label too, so the open list's copy is the last one.
        ui.click(options.last().boundsInRoot.centre)

        assertEquals(Difficulty.Hard, physics.difficulty)
    }

    @Test
    fun `a colour swatch opens the toolkit's colour picker and picking changes the colour`() {
        val physics = Physics()
        val was = physics.fog
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        assertNull(ui.picker(), "the picker is up before anything was pressed")
        ui.click(ui.control("Fog").boundsInRoot.centre)

        val square = assertNotNull(ui.picker(), "pressing the swatch did not open a picker:\n" + ui.dump()).boundsInRoot
        // The top-right corner of the square: the hue it is already on, at full saturation and value.
        ui.click(Offset(square.right - 4f, square.top + 4f))

        assertTrue(physics.fog != was, "the picked colour did not reach the property")
    }

    /** The open colour picker's square, or null when none is open. */
    private fun UiTest.picker(): UiNode? {
        var found: UiNode? = null
        root.forEach { if (found == null && it.name == "colourpicker.square") found = it }
        return found
    }

    @Test
    fun `a button does the thing it says`() {
        val physics = Physics()
        val ui = open { Screen(physics, DebugWindowsState(store)) }

        ui.click(ui.control("Spawn wave").boundsInRoot.centre)

        assertEquals(1, physics.waves)
    }

    @Test
    fun `a section folds its lines away and is remembered`() {
        val state = DebugWindowsState(store)
        val ui = open {
            DebugWindowHost(state = state) {
                DebugWindow("Physics") {
                    CollapsingHeader("Advanced") {
                        toggle("God mode", checked = false, onCheckedChange = {})
                    }
                }
            }
        }

        ui.assertDoesNotExist(DebugWindowTags.control("Physics", "God mode"))
        ui.click(ui.node(DebugWindowTags.row("Physics", "Advanced")).children[0].boundsInRoot.centre)
        ui.settle()

        ui.assertExists(DebugWindowTags.control("Physics", "God mode"))
        assertEquals("open", store.values["section:Physics:Advanced"])
    }

    // --- remembering ------------------------------------------------------------------------------

    @Test
    fun `a window opens where it was left the last time the game ran`() {
        val kept = MemoryDebugWindowStore(mapOf("window:Physics" to "300.0;120.0;260.0;180.0;collapsed"))
        val state = DebugWindowsState(kept)
        val ui = open { Screen(Physics(), state) }

        val window = ui.window().layoutBoundsInRoot
        assertEquals(300f, window.left)
        assertEquals(120f, window.top)
        assertEquals(260f, ui.window().width)
        assertTrue(state.isCollapsed("Physics"))
    }

    @Test
    fun `a saved line that makes no sense is ignored rather than fatal`() {
        val kept = MemoryDebugWindowStore(mapOf("window:Physics" to "not a placement"))
        val ui = open { Screen(Physics(), DebugWindowsState(kept)) }

        assertEquals(20f, ui.window().layoutBoundsInRoot.left)
    }

    @Test
    fun `resetting the layout puts every window back where the code put it`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        ui.drag(ui.title().boundsInRoot.centre, ui.title().boundsInRoot.centre + Offset(100f, 100f))

        state.resetLayout()
        ui.settle()

        assertEquals(Offset(20f, 20f), state.position("Physics"))
        assertEquals(20f, ui.window().layoutBoundsInRoot.left)
    }

    // --- the hotkey, the chord and the pad ---------------------------------------------------------

    @Test
    fun `the hotkey puts every window away and brings it back with what was in it`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        ui.drag(ui.title().boundsInRoot.centre, ui.title().boundsInRoot.centre + Offset(60f, 0f))

        ui.key(Key.F9)

        assertTrue(state.hidden)
        ui.assertDoesNotExist(DebugWindowTags.window("Physics"))

        ui.key(Key.F9)

        assertFalse(state.hidden)
        assertEquals(80f, ui.window().layoutBoundsInRoot.left, "it forgot where it was while it was away")
    }

    @Test
    fun `a hidden window is not in the way of the game under it`() {
        var plays = 0
        val ui = open(Size(400f, 300f)) {
            DebugWindowHost(state = DebugWindowsState(store)) {
                Button("PLAY", onClick = { plays++ }, modifier = Modifier.fillMaxSize().testTag("play"))
                DebugWindow("Physics", initialPosition = Offset(20f, 20f)) { text("Waves", "0") }
            }
        }
        val at = ui.window().boundsInRoot.centre

        ui.key(Key.F9)
        ui.click(at)

        assertEquals(1, plays, "the click should have reached the game where the window was")
    }

    @Test
    fun `a pad chord hides them too and one button of it on its own does not`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }

        ui.pad(GamepadButton.LeftStick)
        assertFalse(state.hidden, "one button of the chord should do nothing")

        ui.padDown(GamepadButton.LeftStick)
        ui.padDown(GamepadButton.RightStick)
        ui.padUp(GamepadButton.RightStick)
        ui.padUp(GamepadButton.LeftStick)
        ui.settle()

        assertTrue(state.hidden)
    }

    @Test
    fun `the pad cycles focus through the windows and back to the game`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }
        ui.click("play")
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true)

        ui.pad(GamepadButton.RightStick)
        assertTrue(ui.focus.focused?.isInside(ui.window("One")) == true, "not in the first window:\n" + ui.dump())
        assertEquals("One", state.windows.last(), "the window focus went to should be in front")

        ui.pad(GamepadButton.RightStick)
        assertTrue(ui.focus.focused?.isInside(ui.window("Two")) == true, "not in the second window:\n" + ui.dump())

        ui.pad(GamepadButton.RightStick)
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus did not go back to the game:\n" + ui.dump())
    }

    @Test
    fun `the cycle key walks focus through the windows and back to the game`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }
        ui.click("play")
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true)

        assertTrue(ui.key(Key.F6), "F6 went through to the game with a window to cycle to")
        assertTrue(ui.focus.focused?.isInside(ui.window("One")) == true, "not in the first window:\n" + ui.dump())
        assertEquals("One", state.windows.last(), "the window focus went to should be in front")

        ui.key(Key.F6)
        assertTrue(ui.focus.focused?.isInside(ui.window("Two")) == true, "not in the second window:\n" + ui.dump())

        ui.key(Key.F6)
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus did not go back to the game:\n" + ui.dump())
    }

    @Test
    fun `the cycle key is left to the game while every window is put away`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }
        ui.click("play")
        ui.key(Key.F9)
        assertTrue(state.hidden)

        assertFalse(ui.key(Key.F6), "there is nothing to cycle to but F6 was taken from the game anyway")
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus should not have moved:\n" + ui.dump())
    }

    @Test
    fun `the cycle button is left to the game while every window is put away`() {
        val state = DebugWindowsState(store)
        val ui = open { TwoWindows(state, Physics()) }
        ui.click("play")
        ui.key(Key.F9)
        assertTrue(state.hidden)

        assertFalse(ui.padDown(GamepadButton.RightStick), "the press was taken with nothing to cycle to")
        assertFalse(ui.padUp(GamepadButton.RightStick), "the release was taken with nothing to cycle to")
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus should not have moved:\n" + ui.dump())
    }

    @Test
    fun `the cycle button is left to the game while there are no windows at all`() {
        val state = DebugWindowsState(store)
        val ui = open {
            DebugWindowHost(state = state) {
                Button("PLAY", onClick = {}, modifier = Modifier.fillMaxSize().testTag("play"))
            }
        }
        ui.click("play")

        assertFalse(ui.padDown(GamepadButton.RightStick), "the press was taken with no window to cycle to")
        assertFalse(ui.padUp(GamepadButton.RightStick), "the release was taken with no window to cycle to")
        assertTrue(ui.focus.focused?.isInside(ui.node("play")) == true, "focus should not have moved:\n" + ui.dump())
    }

    @Test
    fun `the keyboard moves a window and resizes it`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        val was = ui.window().width
        ui.click(ui.control("God mode").boundsInRoot.centre)

        ui.key(Key.Right, Modifiers.Primary)
        assertEquals(36f, state.position("Physics")?.x)

        ui.key(Key.Right, Modifiers.Primary + Modifiers.Shift)
        assertEquals(was + 16f, ui.window().width)
    }

    @Test
    fun `the right stick moves the window focus is in`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open { Screen(physics, state) }
        ui.click(ui.control("God mode").boundsInRoot.centre)

        ui.stick(1f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        ui.advanceBy(100)
        ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        val x = assertNotNull(state.position("Physics")).x
        assertTrue(x > 20f, "the window did not move: at $x")
    }

    /**
     * A stick only reaches the window while focus is inside it, so a tilt the window never hears the
     * end of must not go on moving it. Each of these leaves the stick over and then takes the pad
     * away in a different way; the answer is the same every time — the window stops, and can still be
     * dragged back and left where it was put.
     */
    private fun tiltToTheEdge(ui: UiTest, state: DebugWindowsState) {
        ui.click(ui.control("God mode").boundsInRoot.centre)
        ui.stick(1f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        val x = assertNotNull(state.position("Physics")).x
        assertTrue(x > 700f, "the stick should have carried the window to the right edge: at $x")
    }

    /** Drags the sliver of title bar still on the screen back across and says where the window ended up. */
    private fun dragBackFromTheEdge(ui: UiTest, state: DebugWindowsState): Float {
        val bar = ui.title().boundsInRoot
        val grab = Offset(bar.left + 8f, bar.centre.y)
        ui.drag(grab, grab - Offset(400f, 0f))
        val landed = assertNotNull(state.position("Physics")).x
        assertTrue(landed < 400f, "the window could not be dragged back: at $landed")
        ui.advanceBy(200)
        assertEquals(landed, assertNotNull(state.position("Physics")).x, "the window is still drifting")
        return landed
    }

    @Test
    fun `focus going back to the game stops a window the stick was left pushing`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }
        tiltToTheEdge(ui, state)

        // The pad goes with focus: the stick coming back to centre is heard by the game now.
        ui.click("play")
        ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)

        dragBackFromTheEdge(ui, state)
    }

    @Test
    fun `a pad pulled out mid-tilt stops the window it was pushing`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }
        tiltToTheEdge(ui, state)

        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.settle()

        dragBackFromTheEdge(ui, state)
    }

    @Test
    fun `putting the windows away stops one the stick was pushing`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }
        tiltToTheEdge(ui, state)

        ui.key(Key.F9)
        assertTrue(state.hidden)
        ui.stick(0f, 0f, horizontal = GamepadAxis.RightX, vertical = GamepadAxis.RightY)
        ui.key(Key.F9)
        assertFalse(state.hidden)

        dragBackFromTheEdge(ui, state)
    }

    @Test
    fun `a pad pulled out mid-chord does not leave a button held down`() {
        val state = DebugWindowsState(store)
        val ui = open { Screen(Physics(), state) }
        ui.click("play")

        ui.padDown(GamepadButton.LeftStick)
        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.settle()

        // Half a chord left over from the last pad must not finish one on the next pad's first press.
        ui.pad(GamepadButton.RightStick)

        assertFalse(state.hidden, "one button of the chord hid every window on its own")
    }

    // --- right to left ----------------------------------------------------------------------------

    @Test
    fun `on a right-to-left screen a window is placed from the right and dragged the same way`() {
        val state = DebugWindowsState(store)
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Screen(Physics(), state)
            }
        }

        val window = ui.window().layoutBoundsInRoot
        assertEquals(800f - 20f, window.right, "measured from the right edge on this screen")

        ui.drag(ui.title().boundsInRoot.centre, ui.title().boundsInRoot.centre + Offset(-100f, 0f))

        assertEquals(120f, state.position("Physics")?.x)
        assertEquals(800f - 120f, ui.window().layoutBoundsInRoot.right)
    }

    @Test
    fun `on a right-to-left screen the keyboard resizes from the other bottom corner`() {
        val physics = Physics()
        val state = DebugWindowsState(store)
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Screen(physics, state)
            }
        }
        val was = ui.window().let { Size(it.width, it.height) }
        val right = ui.window().layoutBoundsInRoot.right
        ui.click(ui.control("God mode").boundsInRoot.centre)

        // The end side is the left here, so that is the way the window grows and where the grip is.
        ui.key(Key.Left, Modifiers.Primary + Modifiers.Shift)

        assertEquals(was.width + 16f, ui.window().width, "the window did not grow towards the end side")
        assertEquals(right, ui.window().layoutBoundsInRoot.right, "the right edge moved: it is the one that stays")
    }

    @Test
    fun `a folded window's triangle points along the line and the other way from right to left`() {
        val ltr = open { Screen(Physics(), DebugWindowsState(store)) }
        ltr.click(DebugWindowTags.collapse("Physics"))
        val rightwards = ltr.collapseTriangle()

        assertTrue(rightwards[1].x > rightwards[0].x, "the folded triangle does not point right: $rightwards")

        val rtl = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Screen(Physics(), DebugWindowsState(MemoryDebugWindowStore()))
            }
        }
        rtl.click(DebugWindowTags.collapse("Physics"))
        val leftwards = rtl.collapseTriangle()

        assertTrue(leftwards[1].x < leftwards[0].x, "the folded triangle does not point left: $leftwards")
    }

    /**
     * The corners of the triangle on the fold button, as it is really drawn. The middle one is the
     * point it makes; the other two are the edge across from it.
     */
    private fun UiTest.collapseTriangle(id: String = "Physics"): List<Offset> {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 800f, 600f))
        DrawPass(canvas).draw(root)
        val button = node(DebugWindowTags.collapse(id)).boundsInRoot
        val fans = canvas.calls.filterIsInstance<DrawCall.Fan>().filter { fan -> fan.points.all { it in button } }
        assertEquals(1, fans.size, "expected one triangle on the fold button but drew ${fans.size}")
        return fans[0].points
    }

    // --- the menu bar in a window -------------------------------------------------------------------

    @Test
    fun `a window can carry a menu bar of its own`() {
        var moon = 0
        val ui = open {
            DebugWindowHost(state = DebugWindowsState(store)) {
                DebugWindow(
                    "Physics",
                    menuBar = { Menu("Presets") { Item("Moon") { moon++ } } },
                ) {
                    text("Waves", "0")
                }
            }
        }

        val bar = ui.root.let { root ->
            val found = mutableListOf<UiNode>()
            root.forEach { node -> if (node.name == "menubar.title") found += node }
            found
        }
        assertEquals(1, bar.size, "no menu in the window:\n" + ui.dump())
        ui.click(bar[0].boundsInRoot.centre)
        val rows = ui.root.let { root ->
            val found = mutableListOf<UiNode>()
            root.forEach { node -> if (node.name == "menu.item") found += node }
            found
        }
        assertEquals(1, rows.size, "the menu did not open:\n" + ui.dump())
        ui.click(rows[0].boundsInRoot.centre)

        assertEquals(1, moon)
    }

    // --- a window that goes away ---------------------------------------------------------------------

    @Test
    fun `a window that stops being composed takes its nodes with it and is forgotten`() {
        var show by mutableStateOf(true)
        val state = DebugWindowsState(store)
        val ui = open {
            DebugWindowHost(state = state) {
                Box(Modifier.fillMaxSize())
                if (show) DebugWindow("Physics") { text("Waves", "0") }
            }
        }

        assertEquals(listOf("Physics"), state.windows)
        show = false
        ui.settle()

        assertEquals(emptyList(), state.windows)
        ui.assertDoesNotExist(DebugWindowTags.window("Physics"))
        assertNull(ui.root.findOrNull(DebugWindowTags.title("Physics")))
    }

    @Test
    fun `a state kept outside the composition lets go of the host when the host goes`() {
        var show by mutableStateOf(true)
        val state = DebugWindowsState(store)
        val ui = open {
            if (show) {
                DebugWindowHost(state = state) {
                    Box(Modifier.fillMaxSize())
                    DebugWindow("Physics") { text("Waves", "0") }
                }
            }
        }
        assertNotNull(state.hostNode, "the host never said where it was")

        show = false
        ui.settle()

        assertNull(state.hostNode, "the state is still holding a tree nobody draws")
        assertFalse(state.focusNextWindow(), "there is nothing left to focus into")
    }

    // --- composed where it was written -----------------------------------------------------------

    @Test
    fun `a window is drawn with the skin where it was written rather than the host's`() {
        // Both, since the window lights up while focus is in it and that is another style.
        val red = SkinFormat.read(
            """{ "styles": {
                "debugwindow": { "background": { "fill": "#FF0000" } },
                "debugwindow.active": { "background": { "fill": "#FF0000" } }
            } }""",
        )
        val ui = open {
            DebugWindowHost(state = DebugWindowsState(store)) {
                SkinOverride(red) {
                    DebugWindow("Physics") { text("Waves", "0") }
                }
            }
        }

        val canvas = RecordingCanvas(Rect.of(0f, 0f, 800f, 600f))
        DrawPass(canvas).draw(ui.root)
        val frame = ui.window().boundsInRoot
        val painted = canvas.calls.filterIsInstance<DrawCall.Rectangle>().filter { it.rect == frame }

        assertEquals(listOf(Colour.rgb(0xFF0000)), painted.map { it.colour }, "the window was not drawn with the skin where it was written")
    }

    @Test
    fun `a list opened in a window is drawn over the windows even when the game has a popup layer`() {
        val physics = Physics()
        val ui = open {
            DebugWindowHost(state = DebugWindowsState(store)) {
                // A game's own screen, with its own popup layer inside the host's.
                PopupHost {
                    Box(Modifier.fillMaxSize())
                    DebugWindow("Physics", initialPosition = Offset(20f, 20f)) {
                        choice("Difficulty", physics::difficulty, Difficulty.entries)
                    }
                }
            }
        }

        ui.click(ui.control("Difficulty").boundsInRoot.centre)
        val option = ui.root.findAll(DebugWindowTags.option("Physics", "Difficulty", "Hard")).last()

        // Drawn after the window's own nodes, so a click reaches the list rather than the window.
        assertTrue(option.boundsInRoot.width > 0f, "the list did not open:\n" + ui.dump())
        ui.click(option.boundsInRoot.centre)
        assertEquals(Difficulty.Hard, physics.difficulty)
    }

    @Test
    fun `a plain var with no state behind it still shows what the window set`() {
        val plain = PlainSettings()
        val ui = open {
            DebugWindowHost(state = DebugWindowsState(store)) {
                DebugWindow("Physics") { tweak("Speed", plain::speed, 0f..10f, step = 1f) }
            }
        }

        val slider = ui.control("Speed").boundsInRoot
        ui.click(Offset(slider.right - 2f, slider.centre.y))

        assertEquals(10f, plain.speed)
        assertEquals("10", ui.text(DebugWindowTags.row("Physics", "Speed")).split("\n").last())
    }

    /** Ordinary fields, the way a system written before any of this is. */
    private class PlainSettings {
        var speed = 5f
    }
}
