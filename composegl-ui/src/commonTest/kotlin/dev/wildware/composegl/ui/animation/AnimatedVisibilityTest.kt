package dev.wildware.composegl.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyHandler
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A panel that animates out before it disappears, driven the way a player drives it.
 *
 * Every test here composes a real screen, draws it into a recording canvas through the same one-call
 * frame a game uses, and moves time on a frame at a time. What is asserted is what a player would
 * see: whether the panel is in the tree, where it is drawn, and how opaque the drawing was.
 */
class AnimatedVisibilityTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private val pointer = PointerRouter(host.root, focus)

    private var wall = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    /** One frame, [millis] after the last, drawn from scratch. */
    private fun frame(millis: Long = 16L): Boolean {
        wall += millis * 1_000_000L
        canvas.clear()
        return renderer.render(viewport, wall)
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    /** Plays [millis] of real time at sixty frames a second. */
    private fun play(millis: Int) = frames((millis + 15) / 16)

    private fun click(tag: String) {
        val at = host.root.find(tag).boundsInRoot.centre
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))
    }

    /** The panel's fill, as the canvas was last asked to draw it, or null when it was not drawn. */
    private fun panelDrawn(): DrawCall.Rectangle? =
        canvas.only<DrawCall.Rectangle>().firstOrNull { it.colour == PanelColour }

    private val panelAlpha: Float get() = assertNotNull(panelDrawn(), "the panel was not drawn").alpha

    // --- leaving ------------------------------------------------------------------------------------

    @Test
    fun `a panel closed by its own button fades out on screen before it leaves the tree`() {
        var open by mutableStateOf(true)
        show {
            AnimatedVisibility(open, exit = fadeOut(spec = Tween(200, easing = Easings.Linear))) {
                Column(Modifier.testTag("panel").background(PanelColour)) {
                    Button("CLOSE", onClick = { open = false }, modifier = Modifier.testTag("close"))
                }
            }
        }
        assertEquals(1f, panelAlpha, "open and at rest")

        click("close")
        assertFalse(open, "the click reached the button")
        play(100)

        assertNotNull(host.root.findOrNull("panel"), "half way out it is still in the tree")
        assertTrue(panelAlpha in 0.2f..0.8f, "and drawn half faded, at $panelAlpha")

        play(200)
        assertNull(host.root.findOrNull("panel"), "the exit finished and the panel went with it")
        assertNull(panelDrawn(), "and nothing of it is drawn")
    }

    @Test
    fun `no exit takes it away the frame it is hidden`() {
        var open by mutableStateOf(true)
        show {
            AnimatedVisibility(open, exit = ExitTransition.None) {
                Box(Modifier.size(40f).testTag("panel").background(PanelColour))
            }
        }

        open = false
        frame()

        assertNull(host.root.findOrNull("panel"), "gone on the very frame it was hidden, as with an if")
        assertNull(panelDrawn(), "and not drawn on that frame either")
    }

    @Test
    fun `no exit takes it away the frame it is hidden even part way through arriving`() {
        var open by mutableStateOf(false)
        show {
            Column {
                Button("TOGGLE", onClick = { open = !open }, modifier = Modifier.testTag("toggle"))
                AnimatedVisibility(
                    open,
                    enter = fadeIn(spec = Tween(300, easing = Easings.Linear)),
                    exit = ExitTransition.None,
                ) {
                    Box(Modifier.size(40f).testTag("panel").background(PanelColour))
                }
            }
        }

        click("toggle")
        play(100)
        assertTrue(panelAlpha in 0.1f..0.9f, "part way in, at $panelAlpha")

        click("toggle")
        frame()
        assertNull(host.root.findOrNull("panel"), "gone on the frame it was hidden, not played back to rest first")
        assertNull(panelDrawn(), "and not drawn on that frame")
        frames(2)
        assertFalse(frame(), "and nothing is left playing")

        // Opened again, it starts its enter from the beginning rather than from where it was cut off.
        click("toggle")
        frames(3)
        assertTrue(panelAlpha < 0.2f, "arrives from nothing again, at $panelAlpha")
    }

    @Test
    fun `a panel opened by a click is on screen where its enter starts on the very next frame`() {
        var open by mutableStateOf(false)
        show {
            Column {
                Button("OPEN", onClick = { open = true }, modifier = Modifier.testTag("open"))
                AnimatedVisibility(open, enter = slideIn(Offset(0f, 50f), Tween(200, easing = Easings.Linear))) {
                    Box(Modifier.size(100f, 40f).testTag("panel").background(PanelColour))
                }
            }
        }

        click("open")
        frame()

        val button = host.root.find("open").boundsInRoot
        val panel = assertNotNull(host.root.findOrNull("panel"), "added the frame the click asked, as with an if")
        assertEquals(50f, panel.boundsInRoot.top - button.bottom, 0.5f, "at the start of its slide, not at rest")
        assertNotNull(panelDrawn(), "and drawn on that frame")

        play(300)
        assertEquals(0f, panel.boundsInRoot.top - button.bottom, 0.5f, "and it slides home")
    }

    @Test
    fun `showing it again part way out turns round without it ever leaving`() {
        var open by mutableStateOf(true)
        val seen = mutableListOf<Float>()
        show {
            val fade = Tween(300, easing = Easings.Linear)
            AnimatedVisibility(open, enter = fadeIn(spec = fade), exit = fadeOut(spec = fade)) {
                Box(Modifier.size(40f).testTag("panel").background(PanelColour))
            }
        }

        open = false
        repeat(10) {
            frame()
            seen += panelAlpha
        }
        val turningPoint = seen.last()
        assertTrue(turningPoint in 0.2f..0.8f, "it should be part way out, and it was at $turningPoint")

        open = true
        repeat(30) {
            frame()
            assertNotNull(host.root.findOrNull("panel"), "it left the tree on frame $it after being reopened")
            seen += panelAlpha
        }

        assertEquals(1f, seen.last(), "it came all the way back")
        assertTrue(seen.min() >= turningPoint - 0.1f, "it turned round rather than finishing the exit: ${seen.min()}")
        val biggestStep = seen.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(biggestStep < 0.15f, "something jumped by $biggestStep")
    }

    // --- arriving -----------------------------------------------------------------------------------

    @Test
    fun `a panel scaled in from nothing is clicked where it is drawn`() {
        var open by mutableStateOf(false)
        var clicks = 0
        show {
            Box(Modifier.fillMaxSize()) {
                Button("OPEN", onClick = { open = true }, modifier = Modifier.testTag("open"))
                AnimatedVisibility(
                    open,
                    Modifier.align(Alignment.Centre),
                    enter = scaleIn(from = 0f, spec = Tween(200, easing = Easings.Linear)),
                ) {
                    Box(Modifier.size(200f, 100f).testTag("panel").background(PanelColour)) {
                        Button("GO", onClick = { clicks++ }, modifier = Modifier.testTag("go"))
                    }
                }
            }
        }
        assertNull(host.root.findOrNull("panel"), "hidden is not composed at all")

        click("open")
        play(100)

        val panel = host.root.find("panel")
        val drawn = panel.boundsInRoot
        val laidOut = panel.layoutBoundsInRoot
        assertTrue(drawn.width in 60f..140f, "half way it is drawn about half size: $drawn")
        assertEquals(laidOut.centre.x, drawn.centre.x, 0.5f, "about its own centre")
        assertTrue(canvas.only<DrawCall.Layer>().isNotEmpty(), "a scale mid-flight is drawn through a picture")

        play(200)
        assertEquals(laidOut, panel.boundsInRoot, "arrived, it is drawn where it was laid out")
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "and at rest it takes no picture")

        click("go")
        assertEquals(1, clicks, "and the button inside it takes the click")
    }

    @Test
    fun `a slide moves the panel and leaves its neighbours where they are`() {
        var open by mutableStateOf(false)
        show {
            Column {
                AnimatedVisibility(open, enter = slideIn(Offset(0f, 80f), Tween(200, easing = Easings.Linear))) {
                    Box(Modifier.size(100f, 40f).testTag("panel").background(PanelColour))
                }
                Box(Modifier.size(100f, 20f).testTag("below"))
            }
        }

        open = true
        frames(2)
        val below = host.root.find("below").boundsInRoot
        play(100)

        val panel = host.root.find("panel").boundsInRoot
        assertTrue(panel.top in 20f..60f, "half way down its slide, at ${panel.top}")
        assertEquals(below, host.root.find("below").boundsInRoot, "a slide is not a layout change")

        play(200)
        assertEquals(0f, host.root.find("panel").boundsInRoot.top)
        assertEquals(PanelColour, panelDrawn()?.colour)
    }

    @Test
    fun `a relative slide moves the panel by its own size and back out the other way`() {
        var open by mutableStateOf(false)
        val spec = Tween(200, easing = Easings.Linear)
        show {
            Column {
                AnimatedVisibility(
                    open,
                    enter = slideInRelative(Offset(1f, 0f), spec),
                    exit = slideOutRelative(Offset(0f, -1f), spec),
                ) {
                    Box(Modifier.size(120f, 40f).testTag("panel").background(PanelColour))
                }
                Box(Modifier.size(100f, 20f).testTag("below"))
            }
        }

        open = true
        frame()
        assertEquals(120f, host.root.find("panel").boundsInRoot.left, "arriving one whole width to the right")
        val below = host.root.find("below").boundsInRoot
        play(100)
        val arriving = host.root.find("panel").boundsInRoot.left
        assertTrue(arriving in 40f..80f, "half way in, at $arriving")
        assertEquals(below, host.root.find("below").boundsInRoot, "a slide is not a layout change")
        play(200)
        assertEquals(0f, host.root.find("panel").boundsInRoot.left)

        open = false
        play(100)
        val leaving = host.root.find("panel").boundsInRoot.top
        assertTrue(leaving in -30f..-10f, "half its own height up and away, at $leaving")
        play(200)
        assertNull(host.root.findOrNull("panel"))
    }

    @Test
    fun `content aligned in a sliding panel keeps its alignment the whole way in`() {
        var open by mutableStateOf(false)
        show {
            AnimatedVisibility(
                open,
                modifier = Modifier.size(200f, 100f),
                enter = slideInRelative(Offset(1f, 0f), Tween(200, easing = Easings.Linear)),
            ) {
                Box(Modifier.align(Alignment.Centre).size(50f).testTag("badge"))
            }
        }

        open = true
        frame()
        assertEquals(275f, host.root.find("badge").boundsInRoot.left, "centred, one whole width to the right")
        assertEquals(25f, host.root.find("badge").boundsInRoot.top, "and centred top to bottom")
        play(100)
        val half = host.root.find("badge").boundsInRoot.left
        assertTrue(half in 145f..205f, "half way in and still centred, at $half")
        play(200)
        assertEquals(75f, host.root.find("badge").boundsInRoot.left, "and at rest exactly where it was sliding to")
    }

    @Test
    fun `a panel that opens with the screen is simply there`() {
        show {
            AnimatedVisibility(true, enter = fadeIn() + scaleIn()) {
                Box(Modifier.size(40f).testTag("panel").background(PanelColour))
            }
        }

        assertEquals(1f, panelAlpha)
        assertTrue(canvas.only<DrawCall.Layer>().isEmpty(), "no scale to watch")
        assertFalse(frame(), "and nothing asks for another frame")
    }

    @Test
    fun `a toast told it starts hidden animates in the moment it is added`() {
        show {
            AnimatedVisibility(true, initiallyVisible = false, enter = fadeIn(spec = Tween(200, easing = Easings.Linear))) {
                Box(Modifier.size(40f).testTag("toast").background(PanelColour))
            }
        }
        play(100)

        assertTrue(panelAlpha in 0.2f..0.8f, "half way in, at $panelAlpha")
        play(200)
        assertEquals(1f, panelAlpha)
    }

    // --- every way in -------------------------------------------------------------------------------

    @Test
    fun `Escape closes a menu that plays out and focus stays on the button that opened it`() {
        var open by mutableStateOf(false)
        show {
            val escape = remember {
                KeyHandler { event ->
                    val used = event.key == Key.Escape && event.type == KeyEventType.Down && open
                    if (used) open = false
                    used
                }
            }
            Column(Modifier.onKeyEvent(escape)) {
                Button("MENU", onClick = { open = true }, initialFocus = true, modifier = Modifier.testTag("menu"))
                AnimatedVisibility(open, exit = fadeOut(spec = Tween(200)) + slideOut(Offset(0f, 30f), Tween(200))) {
                    Box(Modifier.size(120f, 60f).testTag("panel").background(PanelColour))
                }
            }
        }
        val menuButton = host.root.find("menu")
        assertSame(menuButton, focus.focused)

        // The keyboard opens it.
        val keys = KeyNavigator(focus)
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        play(300)
        assertEquals(1f, panelAlpha, "open")

        // And closes it.
        assertTrue(KeyRouter(focus, host.root).onKey(KeyEvent(Key.Escape, KeyEventType.Down)))
        play(100)
        val leaving = host.root.find("panel").boundsInRoot
        assertTrue(leaving.top > menuButton.boundsInRoot.bottom, "sliding down as it goes: $leaving")
        assertTrue(panelAlpha < 1f)

        play(200)
        assertNull(host.root.findOrNull("panel"))
        assertSame(menuButton, focus.focused, "focus never moved")

        // A pad opens it again, and it comes in from nothing rather than from where it left.
        val pad = GamepadNavigator(focus)
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        frames(3)
        assertTrue(open, "the pad pressed the button")
        assertNotNull(host.root.findOrNull("panel"))
        assertEquals(0f, host.root.find("panel").boundsInRoot.top - menuButton.boundsInRoot.bottom, 0.5f, "the slide was the exit's alone, so it comes back at rest")
        assertTrue(panelAlpha < 0.5f, "the default enter fades in from nothing, at $panelAlpha")
        play(300)
        assertEquals(1f, panelAlpha)
    }

    @Test
    fun `an exit on the world clock stops half way while the game is paused`() {
        host.clocks.register(Clock.World)
        var open by mutableStateOf(true)
        show {
            AnimatedVisibility(open, exit = fadeOut(spec = Tween(400, easing = Easings.Linear)), clock = Clock.World) {
                Box(Modifier.size(40f).testTag("panel").background(PanelColour))
            }
        }

        open = false
        play(200)
        host.clocks.stop(Clock.World)
        frames(2)
        val frozen = panelAlpha
        assertTrue(frozen in 0.2f..0.8f, "under way before the pause, at $frozen")

        play(2000)
        assertEquals(frozen, panelAlpha, "the world is paused, so the exit is")
        assertNotNull(host.root.findOrNull("panel"))

        host.clocks.start(Clock.World)
        play(400)
        assertNull(host.root.findOrNull("panel"), "and it finishes once the game carries on")
    }

    @Test
    fun `a pad presses close inside a menu and the menu plays out and goes`() {
        var open by mutableStateOf(false)
        uiTest(Size(400f, 300f)) {
            Column {
                Button("MENU", onClick = { open = true }, initialFocus = true, modifier = Modifier.testTag("menu"))
                AnimatedVisibility(
                    open,
                    exit = fadeOut(spec = Tween(300)) + scaleOut(to = 0.5f, spec = Tween(300)),
                    clock = Clock.World,
                ) {
                    Column(Modifier.testTag("panel")) {
                        Button("CLOSE", onClick = { open = false }, modifier = Modifier.testTag("close"))
                    }
                }
            }
        }.use { ui ->
            ui.assertDoesNotExist("panel")
            ui.click("menu")
            ui.assertExists("close")

            ui.pad(GamepadButton.DpadDown)
            ui.assertFocused("close")
            // Every action waits out whatever is playing, so pause the world to catch it leaving.
            ui.host.clocks.register(Clock.World)
            ui.host.clocks.stop(Clock.World)
            ui.pad(GamepadButton.South)
            assertFalse(open, "the pad pressed close")
            ui.assertText("panel", "CLOSE")

            ui.host.clocks.start(Clock.World)
            ui.advanceBy(400)
            ui.assertDoesNotExist("panel")
            ui.assertDoesNotExist("close")

            // The menu button is still there to open it again, and it comes back at rest.
            ui.click("menu")
            assertEquals(ui.node("panel").layoutBoundsInRoot, ui.node("panel").boundsInRoot)
        }
    }

    @Test
    fun `an empty panel and a panel with no size scale in and out without trouble`() {
        var open by mutableStateOf(true)
        uiTest(Size(400f, 300f)) {
            Column {
                val both = scaleOut() + fadeOut()
                AnimatedVisibility(open, Modifier.testTag("empty"), enter = scaleIn() + fadeIn(), exit = both) {}
                AnimatedVisibility(open, enter = scaleIn() + fadeIn(), exit = both) {
                    Box(Modifier.size(0f).testTag("nothing").background(PanelColour))
                }
                Button("TOGGLE", onClick = { open = !open }, modifier = Modifier.testTag("toggle"))
            }
        }.use { ui ->
            ui.click("toggle")
            ui.assertDoesNotExist("empty")
            ui.assertDoesNotExist("nothing")
            assertFalse(ui.render(), "gone, it asks for nothing")

            ui.click("toggle")
            ui.assertExists("empty")
            ui.assertExists("nothing")
            ui.render()
            assertFalse(ui.render(), "back and at rest, it asks for nothing")
        }
    }

    @Test
    fun `a panel inside a leaving panel goes with it and comes back as it was`() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(true)
        uiTest(Size(400f, 300f)) {
            Column {
                Button("OUTER", onClick = { outer = !outer }, modifier = Modifier.testTag("outer-toggle"))
                Button("INNER", onClick = { inner = !inner }, modifier = Modifier.testTag("inner-toggle"))
                AnimatedVisibility(outer, exit = fadeOut(spec = Tween(200))) {
                    Column(Modifier.testTag("outer")) {
                        AnimatedVisibility(inner, exit = scaleOut(spec = Tween(400))) {
                            Box(Modifier.size(40f).testTag("inner").background(PanelColour))
                        }
                    }
                }
            }
        }.use { ui ->
            // Closing the inner one alone leaves the outer one where it is.
            ui.click("inner-toggle")
            ui.assertDoesNotExist("inner")
            ui.assertExists("outer")

            ui.click("inner-toggle")
            ui.assertExists("inner")

            // Closing the outer one while the inner one is part way out takes both, and nothing is
            // left playing on the clock for the inner exit that never finished.
            // The inner exit is twice as long as the outer one, so the outer one finishes first.
            inner = false
            ui.click("outer-toggle")
            ui.assertDoesNotExist("outer")
            ui.assertDoesNotExist("inner")
            assertFalse(ui.host.clocks.isAnimating)

            // Brought back, the inner one is composed fresh from what it is told now: hidden.
            ui.click("outer-toggle")
            ui.assertExists("outer")
            ui.assertDoesNotExist("inner")
            ui.click("inner-toggle")
            val box = ui.node("inner")
            assertEquals(box.layoutBoundsInRoot, box.boundsInRoot, "and it arrives at full size")
        }
    }

    // --- what it costs ------------------------------------------------------------------------------

    @Test
    fun `settled shown or settled hidden it costs no frames`() {
        var open by mutableStateOf(true)
        show {
            AnimatedVisibility(open, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Box(Modifier.size(40f).background(PanelColour))
            }
        }
        repeat(60) { assertFalse(frame(), "frame $it redrew a settled open panel") }

        open = false
        play(400)
        repeat(60) { assertFalse(frame(), "frame $it redrew a settled closed panel") }

        open = true
        play(400)
        repeat(60) { assertFalse(frame(), "frame $it redrew a panel that had come back") }
    }

    @Test
    fun `a bouncy spring out never hands the scale a negative factor`() {
        var open by mutableStateOf(true)
        show {
            AnimatedVisibility(open, exit = scaleOut(spec = Spring(damping = Spring.Bouncy, stiffness = Spring.High))) {
                Box(Modifier.size(40f).testTag("panel").background(PanelColour))
            }
        }

        open = false
        // Without the clamp this throws on the first frame the spring dips below zero.
        play(2000)

        assertNull(host.root.findOrNull("panel"))
    }

    @Test
    fun `the later of two of the same part is the one that counts`() {
        val quick = Tween(50)
        val parts = (fadeIn(from = 0.5f) + scaleIn(from = 0.2f) + fadeIn(from = 0.1f, spec = quick)).parts

        assertEquals(0.1f, parts.fade?.alpha)
        assertSame(quick, parts.fade?.spec)
        assertEquals(0.2f, parts.scale?.factor)
        assertNull(parts.slide)
        assertEquals(TransitionParts(), (ExitTransition.None + ExitTransition.None).parts)
    }

    private companion object {
        val PanelColour = Colour.rgb(0x3366CC)
    }
}
