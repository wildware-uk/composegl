package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Input on a scene view, driven the way a player drives it.
 *
 * Most of it is the one conversion this widget exists to get right: `(0, 0)` is the corner of the
 * picture on screen, and a position is in the picture's own pixels — the units the draw block was
 * given — whether the panel sits in a scrolled column, a splitter pane, under the viewport's
 * scaling, a scale modifier, padding, a resolution scale, or right to left. The rest is the
 * plumbing round it: focus reaches it from Tab and the pad, a drag that leaves it keeps coming, and
 * what it does not take reaches whatever is behind.
 */
class SceneViewPanelInputTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keyRouter = KeyRouter(focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)

    /**
     * The largest picture the "GPU" will make, each way. A real device caps a texture at its own
     * limit; zero stands for a backend that has not made one yet.
     */
    private var maxScenePixels = Int.MAX_VALUE
    private val gpu = object : UiCanvas by canvas {
        override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? =
            canvas.scene(surface, minOf(width, maxScenePixels), minOf(height, maxScenePixels), draw)
    }
    private val renderer = UiRenderer(host, gpu).also { it.focus = focus }
    private var viewport = Viewport.oneToOne(Size(400f, 300f))
    private var nanos = 0L

    private val scene = SceneViewState()
    private val pointerEvents = mutableListOf<PointerEvent>()
    private val keyEvents = mutableListOf<KeyEvent>()
    private val padEvents = mutableListOf<GamepadEvent>()

    /** What the handlers answer. Everything by default; a test turns one off to see it pass through. */
    private var takePointer = true
    private var takeKeys = true
    private var takePad = true

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame() {
        nanos += 16_666_667L
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
        renderer.render(viewport, nanos)
        canvas.assertBalanced()
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    /** The scene view every test uses, writing down what reaches it. */
    @Composable
    private fun sceneView(modifier: Modifier = Modifier.size(160f, 90f)) {
        SceneView(
            scene,
            modifier.testTag("scene"),
            onPointer = { e -> pointerEvents += e; takePointer },
            onKey = { e -> keyEvents += e; takeKeys },
            onPad = { e -> padEvents += e; takePad },
        ) { }
    }

    private fun node(tag: String): UiNode = checkNotNull(host.root.findOrNull(tag)) { "no $tag in\n${host.root}" }

    /**
     * Where the picture of the scene is on screen, in design units: the panel's drawn rectangle less
     * its padding, which is drawn scaled with it.
     */
    private fun picture(): Rect {
        val panel = node("scene")
        val drawn = panel.boundsInRoot
        val scale = panel.scaleInRoot
        val padding = panel.resolved.padding
        return Rect(
            drawn.left + padding.left * scale,
            drawn.top + padding.top * scale,
            drawn.right - padding.right * scale,
            drawn.bottom - padding.bottom * scale,
        )
    }

    /** The rectangle the tree handed the canvas for the picture: where it is when nothing above it scales. */
    private fun drawnImage(): Rect = canvas.only<DrawCall.Image>().single { it.texture === scene.texture }.destination

    /** A press where the pointer is in design units, as a backend reports it after [Viewport.toDesign]. */
    private fun press(at: Offset) {
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
    }

    private fun release(at: Offset) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))

    private fun lastPress(): Offset = pointerEvents.filterIsInstance<PointerEvent.Press>().last().position

    /** A key down and up, offered to the focused node and outwards first, then to the navigator. */
    private fun key(key: Key) {
        for (type in listOf(KeyEventType.Down, KeyEventType.Up)) {
            val event = KeyEvent(key, type)
            if (!keyRouter.onKey(event)) keys.onKey(event)
        }
    }

    private fun button(button: GamepadButton) {
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, button))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, button))
    }

    private fun assertNear(expected: Offset, actual: Offset, message: String) {
        assertEquals(expected.x, actual.x, 0.01f, "$message: x of $actual")
        assertEquals(expected.y, actual.y, 0.01f, "$message: y of $actual")
    }

    /**
     * Presses the picture's top-left corner and a point well inside it, and checks both land where
     * they should in the picture's own pixels.
     */
    private fun assertCornerIsOrigin(what: String) {
        assertNotNull(scene.texture, "the scene was rendered")
        val box = picture()
        press(Offset(box.left, box.top))
        release(Offset(box.left, box.top))
        assertNear(Offset.Zero, lastPress(), "the corner of the panel $what")

        val inside = Offset(box.left + box.width * 0.25f, box.top + box.height * 0.5f)
        press(inside)
        release(inside)
        assertNear(Offset(scene.width * 0.25f, scene.height * 0.5f), lastPress(), "a quarter across and half down $what")
    }

    // --- where (0, 0) is ---------------------------------------------------------------------------

    @Test
    fun `the corner of a panel placed below and beside other things is zero`() {
        show {
            Column(Modifier.padding(left = 37f, top = 11f)) {
                Box(Modifier.size(50f, 23f))
                sceneView()
            }
        }

        assertEquals(drawnImage(), picture(), "the picture is drawn where the panel is")
        assertCornerIsOrigin("offset in a column")
    }

    @Test
    fun `the corner of a panel in a scrolled column is zero`() {
        val scroll = ScrollState()
        show {
            ScrollArea(Modifier.size(300f, 200f), state = scroll) {
                Column {
                    Box(Modifier.size(100f, 150f))
                    sceneView()
                    Box(Modifier.size(100f, 400f))
                }
            }
        }
        scroll.scrollTo(y = 120f)
        frames(3)
        assertEquals(30f, picture().top, 0.01f, "the column really is scrolled")

        assertCornerIsOrigin("in a scrolled column")
    }

    @Test
    fun `the corner of a panel in the second pane of a splitter is zero`() {
        show {
            Splitter(
                0.4f,
                onFractionChange = {},
                modifier = Modifier.size(400f, 200f),
                thickness = 10f,
                first = { Box(Modifier.fillMaxSize()) },
                second = { sceneView(Modifier.fillMaxSize().padding(0f)) },
            )
        }
        assertTrue(picture().left > 100f, "the pane is to the right: ${picture()}")

        assertCornerIsOrigin("in a splitter pane")
    }

    @Test
    fun `under the viewport's scaling a position is in real pixels`() {
        viewport = Viewport(Size(400f, 300f), Size(800f, 600f))
        show {
            Column(Modifier.padding(left = 30f, top = 20f)) { sceneView() }
        }
        assertEquals(320, scene.width, "twice the panel's design width")

        // Where a backend's mouse is, in screen pixels, turned into design units the way it does it.
        val cornerOnScreen = viewport.toScreen(Offset(picture().left, picture().top))
        press(viewport.toDesign(cornerOnScreen + Offset(10f, 6f)))
        assertNear(Offset(10f, 6f), lastPress(), "ten real pixels right and six down")

        release(viewport.toDesign(cornerOnScreen))
        assertCornerIsOrigin("under a doubled viewport")
    }

    @Test
    fun `right to left the corner is still the picture's top left on screen`() {
        show {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Row(Modifier.size(400f, 200f)) {
                    Box(Modifier.size(60f, 60f))
                    sceneView()
                }
            }
        }
        assertEquals(drawnImage(), picture())
        assertTrue(picture().left > 100f, "right to left puts the panel on the right: ${picture()}")

        assertCornerIsOrigin("right to left")
        val box = picture()
        press(Offset(box.right - 1f, box.top + 1f))
        assertNear(Offset(scene.width - 1f, 1f), lastPress(), "the right edge is the far end, not the start")
    }

    @Test
    fun `padding and a scale modifier and a resolution scale are all taken out`() {
        scene.resolutionScale = 0.5f
        show {
            Box(Modifier.padding(left = 40f, top = 30f)) {
                Box(Modifier.scale(1.5f)) {
                    sceneView(Modifier.size(160f, 90f).padding(10f))
                }
            }
        }
        val box = picture()
        assertEquals(scene.width.toFloat(), 140f * 1.5f * 0.5f, 1f, "the picture is the content box's real pixels, halved")

        assertCornerIsOrigin("with padding, a scale and half resolution")
        press(Offset(box.right, box.bottom))
        assertNear(Offset(scene.width.toFloat(), scene.height.toFloat()), lastPress(), "the far corner is the picture's size")
    }

    @Test
    fun `a picture a small GPU cut down is measured as it is and not as it was asked for`() {
        viewport = Viewport(Size(400f, 300f), Size(800f, 600f))
        maxScenePixels = 200
        show { Column(Modifier.padding(left = 30f, top = 20f)) { sceneView() } }
        assertEquals(200, scene.width, "asked for 320 and given 200")
        assertEquals(180, scene.height, "the height fitted")

        // Two real pixels a unit were asked for; the picture has 1.25 across. A position in the
        // picture has to use what is there, or a click lands off the edge of the scene.
        assertCornerIsOrigin("in a picture cut down by the GPU")
        val box = picture()
        press(Offset(box.left + box.width * 0.75f, box.top + box.height * 0.75f))
        assertNear(Offset(150f, 135f), lastPress(), "three quarters of the picture's real size, not of 320 by 180")
    }

    @Test
    fun `before there is a picture a position uses the scale the prepass measured`() {
        viewport = Viewport(Size(400f, 300f), Size(800f, 600f))
        maxScenePixels = 0
        show { Column(Modifier.padding(left = 30f, top = 20f)) { Box(Modifier.scale(1.5f)) { sceneView() } } }
        assertNull(scene.texture, "no picture has been made")
        assertEquals(0, scene.width)

        val box = picture()
        press(Offset(box.left + box.width * 0.25f, box.top + box.height * 0.5f))
        // Two pixels a unit from the viewport, times the 1.5 scale: 160 units is 480 pixels.
        assertNear(Offset(120f, 135f), lastPress(), "a quarter across and half down of what will be drawn")
    }

    // --- the drag rule -------------------------------------------------------------------------------

    @Test
    fun `a drag that starts in the panel keeps being delivered after it leaves`() {
        show { Column(Modifier.padding(20f)) { sceneView() } }
        val box = picture()

        press(Offset(box.left + 10f, box.top + 10f))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(box.right + 50f, box.bottom + 40f), setOf(PointerButton.Primary)))
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(box.left - 15f, box.top - 5f), setOf(PointerButton.Primary)))
        release(Offset(box.left - 15f, box.top - 5f))

        val moves = pointerEvents.filterIsInstance<PointerEvent.Move>().filter { it.pressed.isNotEmpty() }
        assertEquals(2, moves.size, "both moves outside the panel reached it: $pointerEvents")
        assertNear(Offset(box.width + 50f, box.height + 40f), moves[0].position, "past the far corner")
        assertNear(Offset(-15f, -5f), moves[1].position, "before the near corner, so negative")
        val up = pointerEvents.last()
        assertTrue(up is PointerEvent.Release, "the release came too: $up")
        assertNear(Offset(-15f, -5f), up.position, "the release where it happened")
    }

    // --- focus -----------------------------------------------------------------------------------------

    @Composable
    private fun buttonThenScene() {
        Column {
            Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"), initialFocus = true)
            sceneView()
            Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
        }
    }

    @Test
    fun `Tab reaches the panel and its keys go to onKey`() {
        show { buttonThenScene() }
        assertSame(node("play"), focus.focused)

        key(Key.Tab)
        frame()
        assertSame(node("scene"), focus.focused, "Tab stops on the scene view")

        key(Key.W)
        // Not the release of the Tab that brought focus here: its press went to the navigator.
        assertEquals(listOf(Key.W, Key.W), keyEvents.map { it.key }, "down and up of W, and nothing else")
        assertEquals(listOf(KeyEventType.Down, KeyEventType.Up), keyEvents.map { it.type })
    }

    @Test
    fun `a key the panel does not take still moves focus on`() {
        show { buttonThenScene() }
        key(Key.Tab)
        frame()
        takeKeys = false

        key(Key.Tab)
        frame()

        assertTrue(keyEvents.any { it.key == Key.Tab }, "the panel was asked first")
        assertSame(node("quit"), focus.focused, "and then Tab did what Tab does")
    }

    @Test
    fun `the pad reaches the panel and its buttons go to onPad`() {
        show { buttonThenScene() }

        button(GamepadButton.DpadDown)
        frame()
        assertSame(node("scene"), focus.focused, "down from PLAY is the scene view")

        button(GamepadButton.South)
        // Not the release of the d-pad press that brought focus here, which was the navigator's.
        assertEquals(
            listOf<GamepadEvent>(
                GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South),
                GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South),
            ),
            padEvents,
        )
    }

    @Test
    fun `a pad direction the panel does not take moves focus out of it`() {
        show { buttonThenScene() }
        button(GamepadButton.DpadDown)
        frame()
        takePad = false

        button(GamepadButton.DpadDown)
        frame()

        assertSame(node("quit"), focus.focused)
    }

    @Test
    fun `a stick that moves focus onto the panel lets go when it comes back even if the panel takes it`() {
        show { buttonThenScene() }
        pad.frame(0L)

        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, 1f))
        frame()
        assertSame(node("scene"), focus.focused, "down from PLAY is the scene view")

        // The panel's handler takes every stick event: a camera flying on the left stick.
        pad.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, 0f))
        assertEquals(
            listOf<GamepadEvent>(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, 0f)),
            padEvents,
            "the panel heard the stick come back",
        )

        for (ms in listOf(500L, 700L, 1_000L, 3_000L)) {
            pad.frame(ms)
            frame()
        }
        assertSame(node("scene"), focus.focused, "the push that brought focus here does not keep repeating")
        assertNull(pad.direction)
    }

    @Test
    fun `a key or button held when focus leaves is forgotten`() {
        show { buttonThenScene() }
        key(Key.Tab)
        frame()
        assertSame(node("scene"), focus.focused)

        keyRouter.onKey(KeyEvent(Key.W, KeyEventType.Down))
        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.North))
        val play = node("play").boundsInRoot
        press(Offset(play.left + 2f, play.top + 2f))
        release(Offset(play.left + 2f, play.top + 2f))
        frame()
        assertSame(node("play"), focus.focused, "the click moved focus away with W still down")

        key(Key.Tab)
        frame()
        assertSame(node("scene"), focus.focused, "and Tab brought it back")
        keyEvents.clear()
        padEvents.clear()

        // Their downs were heard before focus left. These ups came after it came back, with no down
        // of their own, so they are not the panel's: the same rule as the Tab that brought it here.
        assertFalse(keyRouter.onKey(KeyEvent(Key.W, KeyEventType.Up)))
        assertFalse(pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.North)))
        assertEquals(emptyList<KeyEvent>(), keyEvents)
        assertEquals(emptyList<GamepadEvent>(), padEvents)
    }

    @Test
    fun `a press the panel takes focuses it so the keyboard follows the click`() {
        show { buttonThenScene() }
        val box = picture()

        press(Offset(box.left + 5f, box.top + 5f))
        release(Offset(box.left + 5f, box.top + 5f))
        frame()

        assertSame(node("scene"), focus.focused)
    }

    @Test
    fun `a preview with no handlers is not a stop for Tab`() {
        show {
            Column {
                Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"), initialFocus = true)
                SceneView(scene, Modifier.size(160f, 90f).testTag("scene")) { }
                Button("QUIT", onClick = {}, modifier = Modifier.testTag("quit"))
            }
        }

        key(Key.Tab)
        frame()

        assertSame(node("quit"), focus.focused, "an inventory slot's spinning model is a picture, not a control")
    }

    @Test
    fun `a focused panel shows a focus ring and an unfocused one does not`() {
        show { buttonThenScene() }
        val box = picture()
        assertEquals(0, canvas.only<DrawCall.Border>().count { it.rect == box }, "no ring before it has focus")

        key(Key.Tab)
        frames(2)

        val calls = canvas.calls
        val ring = calls.indexOfLast { it is DrawCall.Border && it.rect == box }
        val image = calls.indexOfFirst { it is DrawCall.Image && it.texture === scene.texture }
        assertTrue(ring >= 0, "a keyboard player can see where focus went: $calls")
        assertTrue(ring > image, "the ring is over the picture, not hidden under it")
    }

    // --- what it does not take -------------------------------------------------------------------------

    @Test
    fun `a press the panel does not take reaches what is behind it`() {
        var clicks = 0
        takePointer = false
        show {
            Box(Modifier.size(300f, 200f).clickable { clicks++ }) {
                Column(Modifier.padding(20f)) { sceneView() }
            }
        }
        val at = Offset(picture().left + 10f, picture().top + 10f)

        press(at)
        release(at)

        assertEquals(1, clicks, "the card behind the preview was clicked")
        assertTrue(pointerEvents.any { it is PointerEvent.Press }, "the panel was asked first")
    }

    @Test
    fun `a scroll wheel the panel does not take scrolls the column it is in`() {
        takePointer = false
        val scroll = ScrollState()
        show {
            ScrollArea(Modifier.size(300f, 200f), state = scroll) {
                Column {
                    sceneView()
                    Box(Modifier.size(100f, 600f))
                }
            }
        }
        val at = Offset(picture().left + 10f, picture().top + 10f)

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, at, Offset(0f, 3f)))
        frames(10)

        assertTrue(pointerEvents.any { it is PointerEvent.Scroll }, "the panel was asked first")
        assertTrue(scroll.y > 0f, "the column scrolled: ${scroll.y}")
    }

    @Test
    fun `a scroll wheel the panel takes does not scroll the column`() {
        val scroll = ScrollState()
        show {
            ScrollArea(Modifier.size(300f, 200f), state = scroll) {
                Column {
                    sceneView()
                    Box(Modifier.size(100f, 600f))
                }
            }
        }
        val at = Offset(picture().left + 10f, picture().top + 10f)

        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, at, Offset(0f, 3f)))
        frames(10)

        assertEquals(0f, scroll.y, "a zoom on the wheel is the scene's, not the list's")
        assertNotNull(pointerEvents.filterIsInstance<PointerEvent.Scroll>().singleOrNull())
    }

    @Test
    fun `the newest handler is the one asked after a recomposition`() {
        var generation by mutableStateOf(1)
        val heard = mutableListOf<Int>()
        show {
            val mine = generation
            Column(Modifier.padding(20f)) {
                SceneView(scene, Modifier.size(160f, 90f).testTag("scene"), onPointer = { heard += mine; true }) { }
            }
        }
        val at = Offset(picture().left + 5f, picture().top + 5f)
        press(at)
        release(at)

        generation = 2
        frames(2)
        press(at)
        release(at)

        assertEquals(2, heard.last(), "a handler that closes over state sees the state it was composed with")
    }
}
