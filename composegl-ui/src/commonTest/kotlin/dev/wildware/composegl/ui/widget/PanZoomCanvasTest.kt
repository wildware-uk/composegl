package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A skill tree on a plane, driven the way a player drives it: the mouse dragging and wheeling, two
 * fingers pinching, the pad's stick and triggers, the d-pad walking node to node, the keyboard.
 * Every answer is read off the screen — where a node was drawn, what took the click, where focus is.
 *
 * The screen is 400 by 300 onto a world of 0, 0 to 1000, 800, looking at the middle of it: world
 * (500, 400) is at screen (200, 150) to start with.
 */
class PanZoomCanvasTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private val world = Rect(0f, 0f, 1000f, 800f)

    private fun state(zoom: Float = 1f) = PanZoomState(zoom, 0.25f, 3f, world, Offset(500f, 400f))

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    private val clicked = mutableListOf<String>()

    /** Three nodes on the plane and one pinned label that keeps its size. */
    @Composable
    private fun Tree(
        camera: PanZoomState,
        background: (UiCanvas.(Rect) -> Unit)? = null,
        reset: PanZoomReset = PanZoomReset.Initial,
    ) {
        PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane"), background = background, reset = reset) {
            Node("root", 500f, 400f)
            Node("left", 300f, 400f)
            Node("far", 950f, 400f)
            Box(
                Modifier.size(20f, 10f)
                    .worldPosition(500f, 400f, anchor = Alignment.Centre, scaleWithZoom = false)
                    .testTag("pin"),
            )
        }
    }

    /** The crafting graph from the wiki: a chain whose steps sit up and down from each other. */
    private val graphWorld = Rect(0f, 0f, 700f, 300f)

    private fun graphState() = PanZoomState(1f, 0.5f, 2.5f, graphWorld, Offset(350f, 150f))

    /**
     * A crafting graph, laid out like the one in the wiki: every neighbour is a diagonal one, and
     * `ore` starts off the left of the view. Screen places at the starting camera, which looks at
     * world (350, 150) with the view's middle at (200, 112): `ore` at x -115..-65, `dust` at
     * x 15..65 y 20..44, `ingot` at x 15..65 y 180..204.
     */
    @Composable
    private fun Graph(camera: PanZoomState) {
        PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane")) {
            Node("ore", 60f, 150f)
            Node("dust", 190f, 70f)
            Node("ingot", 190f, 230f)
            Node("plate", 330f, 150f)
            Node("rod", 330f, 235f)
            Node("armour", 470f, 75f)
            Node("gear", 470f, 220f)
            Node("engine", 620f, 150f)
        }
    }

    @Composable
    private fun Node(tag: String, x: Float, y: Float) {
        Box(
            Modifier.size(40f, 20f)
                .worldPosition(x, y, anchor = Alignment.Centre)
                .focusable()
                .clickable { clicked += tag }
                .background(Colour.rgb(0x334455))
                .testTag(tag),
        )
    }

    private fun UiTest.drawn(canvas: UiCanvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))): RecordingCanvas {
        DrawPass(canvas).draw(root)
        return canvas as RecordingCanvas
    }

    private fun near(expected: Float, actual: Float, message: String? = null) =
        assertTrue(abs(expected - actual) < 1f, message ?: "expected $expected but was $actual")

    // --- where things are ---------------------------------------------------------------------------

    @Test
    fun `a node sits where the world puts it and says so`() {
        val camera = state()
        val ui = open { Tree(camera) }

        // World (500, 400) is the middle of the view, and the node is anchored by its middle.
        assertEquals(Rect(180f, 140f, 220f, 160f), ui.node("root").boundsInRoot)
        assertEquals(Rect(-20f, 140f, 20f, 160f), ui.node("left").boundsInRoot, "off the left of the view")
    }

    @Test
    fun `zooming draws a node bigger and where it says it is`() {
        val camera = state()
        val ui = open { Tree(camera) }

        camera.snapTo(Offset(500f, 400f), zoom = 2f)
        ui.settle()

        val box = ui.node("root").boundsInRoot
        assertEquals(Rect(160f, 130f, 240f, 170f), box, "twice the size about the middle of the view")
        val canvas = ui.drawn()
        val drawn = canvas.only<DrawCall.Rectangle>().first { it.colour == Colour.rgb(0x334455) }
        assertEquals(box, drawn.rect, "and drawn exactly where it says it is")
        assertEquals(2f, canvas.scaleOf(drawn), "grown by the camera rather than stretched from a picture")
    }

    @Test
    fun `a pinned label follows the camera but keeps its own size`() {
        val camera = state()
        val ui = open { Tree(camera) }
        assertEquals(Rect(190f, 145f, 210f, 155f), ui.node("pin").boundsInRoot)

        camera.snapTo(Offset(400f, 400f), zoom = 2f)
        ui.settle()

        val box = ui.node("pin").boundsInRoot
        assertEquals(20f, box.width, "it is still twenty across")
        assertEquals(Offset(400f, 150f), box.centre, "and its middle is still on its world point")
    }

    // --- the mouse ------------------------------------------------------------------------------------

    @Test
    fun `dragging empty space moves the world with the pointer`() {
        val camera = state()
        val ui = open { Tree(camera) }

        ui.press(Offset(40f, 40f))
        ui.dragTo(Offset(100f, 70f))
        ui.release()

        assertEquals(Rect(240f, 170f, 280f, 190f), ui.node("root").boundsInRoot, "the world followed the hand")
        assertEquals(emptyList(), clicked)
    }

    @Test
    fun `letting go of a fast drag flings the world on`() {
        val slowly = state()
        val slow = open { Tree(slowly) }
        slow.press(Offset(300f, 220f))
        for (step in 1..6) slow.dragTo(Offset(300f - step * 10f, 220f - step * 10f))
        slow.release()
        slow.settle()

        val camera = state()
        val ui = open { Tree(camera) }
        ui.flick(from = Offset(300f, 220f), to = Offset(240f, 160f))

        // The same path, fast enough to measure a speed: the camera carries on past where the hand
        // let go, on both axes, and the slow one stops dead where it was left.
        assertTrue(
            camera.centre.x > slowly.centre.x + 50f,
            "it should have flung sideways: ${camera.centre} against a slow drag's ${slowly.centre}",
        )
        assertTrue(
            camera.centre.y > slowly.centre.y + 50f,
            "and downwards: ${camera.centre} against a slow drag's ${slowly.centre}",
        )
    }

    @Test
    fun `a flick that is taken away does not fling`() {
        val camera = state()
        val ui = open { Tree(camera) }

        ui.flick(from = Offset(300f, 220f), to = Offset(240f, 160f))
        val flung = camera.centre

        val cancelled = state()
        val other = open { Tree(cancelled) }
        other.press(Offset(300f, 220f))
        for (step in 1..6) other.dragTo(Offset(300f - step * 10f, 220f - step * 10f))
        other.input.onPointer(PointerEvent.Cancel(PointerId.Mouse, Offset(240f, 160f)))
        other.settle()

        assertTrue(
            cancelled.centre.x < flung.x - 20f,
            "a gesture taken away stops where it is: ${cancelled.centre} against a fling's $flung",
        )
        assertFalse(cancelled.isFlinging, "and nothing is still moving")
    }

    @Test
    fun `a drag that starts on a node pans instead of clicking it`() {
        val camera = state()
        val ui = open { Tree(camera) }

        ui.press("root")
        ui.dragTo(Offset(300f, 150f))
        ui.release()

        assertEquals(emptyList(), clicked, "a drag is never a click")
        assertTrue(ui.node("root").boundsInRoot.left > 200f, "and it panned: ${ui.node("root").boundsInRoot}")
    }

    @Test
    fun `a click inside a zoomed plane lands on the node under the pointer`() {
        val camera = state()
        val ui = open { Tree(camera) }
        camera.snapTo(Offset(500f, 400f), zoom = 2f)
        ui.settle()

        ui.click(Offset(200f, 150f))

        assertEquals(listOf("root"), clicked)
    }

    @Test
    fun `the wheel zooms about the pointer`() {
        val camera = state()
        val ui = open { Tree(camera) }
        val at = Offset(320f, 80f)
        val under = camera.screenToWorld(at)

        ui.input.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        ui.input.onPointer(PointerEvent.Scroll(PointerId.Mouse, at, Offset(0f, -2f)))
        ui.settle()

        assertTrue(camera.zoom > 1.2f, "it should have zoomed in: ${camera.zoom}")
        val now = camera.worldToScreen(under)
        near(at.x, now.x, "what was under the pointer stayed under it: $now")
        near(at.y, now.y)
    }

    @Test
    fun `two fingers zoom about the middle of the pinch`() {
        val camera = state()
        val ui = open { Tree(camera) }
        val middle = Offset(200f, 150f)
        val under = camera.screenToWorld(middle)

        finger(ui, 1L, PointerEvent.Press(PointerId(1L), Offset(150f, 150f)))
        finger(ui, 2L, PointerEvent.Press(PointerId(2L), Offset(250f, 150f)))
        finger(ui, 1L, move(1L, Offset(100f, 150f)))
        finger(ui, 2L, move(2L, Offset(300f, 150f)))

        assertTrue(camera.zoom > 1.5f, "a pinch out should have zoomed in: ${camera.zoom}")
        val now = camera.worldToScreen(under)
        near(middle.x, now.x, "what was between the fingers stayed there: $now")
    }

    private fun move(id: Long, at: Offset) =
        PointerEvent.Move(PointerId(id), at, pressed = setOf(PointerButton.Primary))

    private fun finger(ui: UiTest, id: Long, event: PointerEvent) {
        ui.input.onPointer(event)
        ui.settle()
    }

    @Test
    fun `a double click puts the camera back where it started`() {
        val camera = state()
        val ui = open { Tree(camera) }
        camera.snapTo(Offset(900f, 700f), zoom = 3f)
        ui.settle()

        ui.click(Offset(40f, 40f))
        ui.click(Offset(40f, 40f))
        ui.advanceBy(600)

        assertEquals(1f, camera.zoom)
        assertEquals(Offset(500f, 400f), camera.centre)
    }

    // --- what is off the edge -------------------------------------------------------------------------

    @Test
    fun `a node outside the view is neither drawn nor clickable`() {
        val camera = state()
        val ui = open { Tree(camera) }

        // The far node is at world 950 and the view reaches 700: it is off the right edge.
        val far = ui.node("far")
        assertTrue(far.boundsInRoot.left > 400f, "it is off the screen: ${far.boundsInRoot}")
        val drawn = ui.drawn().only<DrawCall.Rectangle>()
        assertTrue(drawn.none { it.rect == far.boundsInRoot }, "it should not have been drawn at all")

        ui.click(Offset(399f, 150f))
        assertEquals(emptyList(), clicked, "and nothing out there takes a click")
    }

    // --- the pad and the keyboard ---------------------------------------------------------------------

    @Test
    fun `the stick pans and the triggers zoom`() {
        val camera = state()
        val ui = open { Tree(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()

        ui.holdStick(1f, 0f, millis = 300)
        assertTrue(camera.centre.x > 560f, "the stick should have panned: ${camera.centre}")

        ui.input.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 1f))
        ui.advanceBy(300)
        ui.input.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightTrigger, 0f))
        ui.settle()

        assertTrue(camera.zoom > 1.2f, "the right trigger should have zoomed in: ${camera.zoom}")
    }

    @Test
    fun `the pad button puts the camera back`() {
        val camera = state()
        val ui = open { Tree(camera) }
        ui.focus.focusOn(ui.node("plane"))
        camera.snapTo(Offset(900f, 700f), zoom = 2f)
        ui.settle()

        ui.pad(GamepadButton.RightStick)
        ui.advanceBy(600)

        assertEquals(1f, camera.zoom)
    }

    @Test
    fun `the d-pad walks from node to node and the camera follows`() {
        val camera = state()
        val ui = open { Tree(camera) }
        ui.focus.focusOn(ui.node("root"))
        ui.settle()

        ui.pad(GamepadButton.DpadLeft)
        ui.advanceBy(600)

        ui.assertFocused("left")
        val box = ui.node("left").boundsInRoot
        assertTrue(box.left >= 0f && box.right <= 400f, "the camera should have brought it into view: $box")
    }

    /**
     * Focus on `ore` with the camera panned back to the middle of the world, which leaves the
     * focused node hanging off the left of the view — a pan or a fling away from the focused node
     * is all it takes. The plane's own rectangle then wraps it on every side.
     */
    private fun graphWithOreOffTheLeft(camera: PanZoomState): UiTest {
        val ui = open { Graph(camera) }
        ui.focus.focusOn(ui.node("ore"))
        ui.settle()
        camera.snapTo(Offset(350f, 150f), 1f)
        ui.settle()
        assertTrue(ui.node("ore").boundsInRoot.left < 0f, "the test wants the focused node off the edge")
        return ui
    }

    @Test
    fun `the d-pad walks on from a node hanging off the edge of the view`() {
        val camera = graphState()
        val ui = graphWithOreOffTheLeft(camera)

        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)

        ui.assertFocused("plate")
        val box = ui.node("plate").boundsInRoot
        assertTrue(box.left >= 0f && box.right <= 400f, "the camera should have brought it into view: $box")
    }

    @Test
    fun `the d-pad reaches a neighbour that is up and to the right`() {
        val camera = graphState()
        val ui = graphWithOreOffTheLeft(camera)

        ui.pad(GamepadButton.DpadUp)
        ui.advanceBy(600)

        ui.assertFocused("dust")
    }

    @Test
    fun `walking on twice never drops focus onto the plane`() {
        val camera = graphState()
        val ui = graphWithOreOffTheLeft(camera)

        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)
        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)

        ui.assertFocused("engine")
    }

    @Test
    fun `a direction with nothing that way leaves focus where it is`() {
        val camera = graphState()
        val ui = open { Graph(camera) }
        ui.focus.focusOn(ui.node("engine"))
        ui.advanceBy(600)
        val centre = camera.centre

        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)

        ui.assertFocused("engine")
        near(centre.x, camera.centre.x, "the camera should not have wandered off")
    }

    @Test
    fun `with the plane focused a direction takes what is straight ahead`() {
        val camera = graphState()
        val ui = open { Graph(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()

        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)

        // `gear` is nearer, but it is off to one side; `engine` is level with the middle of the
        // view, and straight ahead wins here exactly as it does anywhere else on the screen.
        ui.assertFocused("engine")
    }

    @Test
    fun `with nothing focusable inside the pad still pans the plane`() {
        val camera = state()
        val ui = open {
            PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane")) {
                Box(Modifier.size(40f, 20f).worldPosition(500f, 400f, anchor = Alignment.Centre).testTag("scenery"))
            }
        }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()
        val centre = camera.centre

        ui.pad(GamepadButton.DpadRight)
        ui.advanceBy(600)

        ui.assertFocused("plane")
        assertTrue(camera.centre.x > centre.x + 10f, "right should have panned the world: ${camera.centre}")
    }

    @Test
    fun `with the plane itself focused a direction goes to the nearest node that way`() {
        val camera = state()
        val ui = open { Tree(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()

        ui.key(Key.Left)
        ui.advanceBy(600)

        ui.assertFocused("left")
    }

    @Test
    fun `keys zoom in and out and put the camera back`() {
        val camera = state()
        val ui = open { Tree(camera) }
        ui.focus.focusOn(ui.node("plane"))
        ui.settle()

        ui.key(Key.Equals)
        assertTrue(camera.zoom > 1.2f, "= should zoom in: ${camera.zoom}")
        ui.key(Key.Minus)
        near(1f, camera.zoom)

        ui.key(Key.Equals)
        ui.key(Key.Digit0)
        ui.advanceBy(600)
        assertEquals(1f, camera.zoom)
    }

    // --- drawing --------------------------------------------------------------------------------------

    @Test
    fun `the background is drawn under the children in world units`() {
        val camera = state()
        var seen: Rect? = null
        val ui = open {
            Tree(camera, background = { visible ->
                seen = visible
                rect(Rect(0f, 0f, 1000f, 800f), Colour.rgb(0x101010))
            })
        }

        val canvas = ui.drawn()
        assertEquals(Rect(300f, 250f, 700f, 550f), seen, "it is handed the part of the world in view")
        val painted = canvas.only<DrawCall.Rectangle>().first { it.colour == Colour.rgb(0x101010) }
        assertEquals(Rect(-300f, -250f, 700f, 550f), painted.rect, "and draws in world units")
        val node = canvas.only<DrawCall.Rectangle>().first { it.colour == Colour.rgb(0x334455) }
        assertTrue(canvas.calls.indexOf(painted) < canvas.calls.indexOf(node), "under the children")
    }

    @Test
    fun `a canvas that cannot zoom still pans and is clicked where it drew`() {
        val camera = state(zoom = 2f)
        val ui = open { Tree(camera) }
        val flat = FlatCanvas(RecordingCanvas(Rect.of(0f, 0f, 400f, 300f)))

        DrawPass(flat).draw(ui.root)

        assertEquals(40f, ui.node("root").boundsInRoot.width, "drawn at its own size rather than twice it")
        val drawn = flat.inner.only<DrawCall.Rectangle>().first { it.colour == Colour.rgb(0x334455) }
        assertEquals(ui.node("root").boundsInRoot, drawn.rect, "and found where it was drawn")
        assertEquals(Offset(200f, 150f), drawn.rect.centre, "still looking at the middle of what it was")
    }

    /** A backend that has never heard of a camera: it draws what it is given, unmoved. */
    private class FlatCanvas(val inner: RecordingCanvas) : UiCanvas by inner {
        override val transforms: Boolean get() = false
        override fun pushTransform(scale: Float, translateX: Float, translateY: Float) = Unit
        override fun pushTransform(scale: Float, translateX: Float, translateY: Float, textScale: Float) = Unit
        override fun popTransform() = Unit
    }

    // --- what it costs --------------------------------------------------------------------------------

    @Test
    fun `panning the camera composes nothing`() {
        val camera = state()
        var compositions = 0
        val ui = open {
            PanZoomCanvas(camera, Modifier.fillMaxSize().testTag("plane")) {
                compositions++
                Node("root", 500f, 400f)
            }
        }
        val before = compositions
        val panned = camera.pan.x

        ui.press(Offset(40f, 40f))
        repeat(10) { step -> ui.dragTo(Offset(40f + step * 5f, 40f)) }
        ui.release()

        assertTrue(camera.pan.x > panned + 10f, "it panned")
        assertEquals(before, compositions, "a camera move is a transform, not a recomposition")
    }

    // --- a world too big to compose -------------------------------------------------------------------

    @Test
    fun `a lazy plane composes only the tiles near the view`() {
        val camera = PanZoomState(1f, 0.25f, 3f, Rect(0f, 0f, 6400f, 6400f), Offset(0f, 0f))
        val tiles = (0 until 100).flatMap { row -> (0 until 100).map { column -> column to row } }
        val ui = open {
            LazyPanZoomCanvas(
                items = tiles,
                area = { Rect.of(it.first * 64f, it.second * 64f, 64f, 64f) },
                state = camera,
                modifier = Modifier.fillMaxSize().testTag("board"),
                key = { it },
            ) { tile -> Box(Modifier.size(64f).testTag("tile")) }
        }

        val composed = ui.root.findAll("tile").size
        assertTrue(composed in 1..200, "a 100 by 100 board should compose a few dozen tiles, not $composed")
    }

    // --- reading direction ------------------------------------------------------------------------------

    @Test
    fun `a right to left screen does not mirror the world`() {
        val camera = state()
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { Tree(camera) }
        }

        assertEquals(Rect(180f, 140f, 220f, 160f), ui.node("root").boundsInRoot, "a map has no reading order")

        ui.press(Offset(40f, 40f))
        ui.dragTo(Offset(80f, 40f))
        ui.release()

        assertTrue(ui.node("root").boundsInRoot.left > 200f, "and a drag right still moves the world right")
    }
}
