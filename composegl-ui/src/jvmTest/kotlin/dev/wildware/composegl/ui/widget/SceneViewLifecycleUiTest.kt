package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.RecordedSceneSurface
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.GamepadNavigator
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The resize, release and cost rules on a composed screen, driven the way a player drives it: a
 * splitter dragged with the mouse, stepped with the keys and with the pad; a list of previews
 * scrolled with the wheel; and a button that sets four viewports going, read back off the budget.
 */
class SceneViewLifecycleUiTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)
    private var now = 0L
    private val budget = FrameBudget(window = 4, publishEveryMillis = 0L, nanoTime = { now })
    private val renderer = UiRenderer(host, canvas, budget).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private var nanos = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(): Boolean {
        nanos += 16_666_667L
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
        val changed = renderer.render(viewport, nanos)
        canvas.assertBalanced()
        return changed
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frames(3)
    }

    private fun node(tag: String): UiNode = checkNotNull(host.root.findOrNull(tag)) { "no $tag in\n${host.root}" }

    /** A width in design units as the prepass rounds it to pixels on a one-to-one viewport: up. */
    private fun pixels(width: Float): Int = kotlin.math.ceil(width - 0.01f).toInt()

    private fun key(key: Key) {
        keys.onKey(KeyEvent(key, KeyEventType.Down))
        keys.onKey(KeyEvent(key, KeyEventType.Up))
    }

    // --- resize -----------------------------------------------------------------------------------

    private val scene = SceneViewState()
    private var draws = 0
    private var fraction by mutableStateOf(0.5f)

    @Composable
    private fun editor() {
        Splitter(
            fraction,
            onFractionChange = { fraction = it },
            modifier = Modifier.size(400f, 200f),
            thickness = 10f,
            initialFocus = true,
            first = { SceneView(scene, Modifier.fillMaxSize().testTag("scene")) { draws++ } },
            second = { Box(Modifier.fillMaxSize()) },
        )
    }

    @Test
    fun `a mouse drag across a splitter stretches the scene and lands it sharp on release`() {
        show { editor() }
        val first = scene.texture
        val startWidth = scene.width

        val grip = Offset(node("scene").boundsInRoot.right + 5f, 100f)
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, grip))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, grip, PointerButton.Primary))
        frame()
        for (step in 1..6) {
            pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(grip.x - step * 15f, 100f), setOf(PointerButton.Primary)))
            frame()
            assertSame(first, scene.texture, "step $step: the picture is kept while the panel moves")
            assertEquals(startWidth, scene.width)
            val picture = canvas.only<DrawCall.Image>().single { it.texture === scene.texture }
            assertEquals(node("scene").boundsInRoot, picture.destination, "step $step: stretched over the panel")
        }
        assertEquals(1, draws, "no reallocation and no render during the drag")

        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(grip.x - 90f, 100f), PointerButton.Primary))
        frames(3)

        assertEquals(2, draws, "one render when the size held")
        assertEquals(pixels(node("scene").width), scene.width)
        assertTrue((first as RecordedSceneSurface).closed)
    }

    @Test
    fun `stepping a splitter with the keys reallocates once the steps stop`() {
        show { editor() }
        assertTrue(node("scene").boundsInRoot.width > 0f)

        // A held arrow key: a step every frame.
        repeat(4) {
            key(Key.Left)
            frame()
        }
        assertEquals(1, draws, "held down, the key is a drag")

        frames(2)
        assertEquals(2, draws)
        assertEquals(pixels(node("scene").width), scene.width)
    }

    @Test
    fun `a single pad step on a splitter is sharp one frame later`() {
        show { editor() }
        val before = scene.width

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.DpadRight))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.DpadRight))
        frame()
        assertEquals(before, scene.width, "the frame the size changed is stretched")
        frame()

        assertTrue(scene.width > before, "and the next is reallocated: $before to ${scene.width}")
        assertEquals(pixels(node("scene").width), scene.width)
    }

    // --- release ----------------------------------------------------------------------------------

    @Test
    fun `a list of previews frees them as rows scroll away`() {
        val states = List(40) { SceneViewState() }
        show {
            LazyColumn(count = states.size, modifier = Modifier.size(200f).testTag("list"), key = { it }) { index ->
                SceneView(states[index], Modifier.fillMaxWidth().height(40f).testTag("row$index")) { }
            }
        }
        val firstRow = canvas.scenes.first { it.surface === states[0].texture }.surface
        assertFalse(firstRow.closed)

        repeat(20) {
            pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(100f, 100f), Offset(0f, 1f)))
            frame()
        }
        frames(2)

        assertTrue(firstRow.closed, "the first row is long gone, and so is its picture")

        // The column is 200 units tall in a window 300 tall, so the rows the list composes below
        // its edge are still inside the window. Its clip hides them, and a picture for one would
        // be a picture nobody sees.
        val list = node("list").boundsInRoot
        val composed = states.indices.mapNotNull { index -> host.root.findOrNull("row$index")?.let { index to it.boundsInRoot } }
        val visible = composed.filter { (_, bounds) -> bounds.bottom > list.top && bounds.top < list.bottom }.map { it.first }
        val below = composed.filter { (_, bounds) -> bounds.top >= list.bottom }.map { it.first }
        assertTrue(below.isNotEmpty(), "the list composes rows below its edge: $composed")
        assertTrue(composed.any { (index, bounds) -> index in below && bounds.top < 300f }, "and some of them inside the window")
        for (index in below) assertEquals(null, states[index].texture, "row $index is below the column's edge")

        val open = states.indices.filter { states[it].texture != null }
        // What can still hold a picture: the rows in view, and the overscan above them that the
        // list keeps composed after they scrolled out.
        assertTrue(open.size <= visible.size + 2, "rows in view $visible, rows with a picture $open")
        assertTrue(visible.all { states[it].texture != null }, "every row in view has its picture: $open")
    }

    @Test
    fun `a scene below the fold of a scroll area renders nothing until it is scrolled into view`() {
        val view = SceneViewState()
        var draws = 0
        show {
            ScrollArea(Modifier.size(400f, 100f).testTag("area"), bars = false) {
                Column {
                    Box(Modifier.fillMaxWidth().height(150f))
                    SceneView(view, Modifier.fillMaxWidth().height(50f).testTag("scene")) { draws++ }
                }
            }
        }
        // A live scene: dirty every frame, so only being hidden can stop it rendering.
        renderer.onLaidOut = { view.invalidate() }
        frames(3)

        val area = node("area").boundsInRoot
        assertTrue(node("scene").boundsInRoot.bottom < 300f, "inside the window")
        assertTrue(node("scene").boundsInRoot.top >= area.bottom, "and below the area's edge")
        assertEquals(null, view.texture, "never seen, so no picture")
        assertEquals(0, draws, "and no renders")

        // One notch is not quite enough: still below the edge, still nothing.
        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(200f, 50f), Offset(0f, 1f)))
        frames(2)
        assertTrue(node("scene").boundsInRoot.top >= area.bottom, "one notch leaves it just below the edge")
        assertEquals(0, draws)

        pointer.onPointer(PointerEvent.Scroll(PointerId.Mouse, Offset(200f, 50f), Offset(0f, 1f)))
        frames(2)

        assertTrue(node("scene").boundsInRoot.top < area.bottom, "scrolled into view")
        assertTrue(view.texture != null, "a picture once it can be seen")
        assertTrue(draws > 0)
    }

    // --- cost -------------------------------------------------------------------------------------

    @Test
    fun `four live viewports started with a click show in the overlay numbers`() {
        val views = List(4) { SceneViewState() }
        var live by mutableStateOf(false)
        show {
            Column {
                Button("PLAY", onClick = { live = true }, modifier = Modifier.testTag("play"))
                Box(Modifier.size(400f, 100f)) {
                    views.forEachIndexed { at, view ->
                        SceneView(view, Modifier.offset(at * 100f, 0f).size(90f)) {
                            now += 2_000_000L
                        }
                    }
                }
            }
        }
        renderer.onLaidOut = { if (live) views.forEach { it.invalidate() } }
        frame()
        assertEquals(0, budget.reading.scenes, "still, nothing renders")

        val at = node("play").boundsInRoot.let { Offset(it.left + it.width / 2f, it.top + it.height / 2f) }
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))
        frames(4)

        assertEquals(4, budget.reading.scenes)
        assertEquals(8f, budget.reading.sceneMillis, 0.001f, "four renders at two milliseconds each")
    }
}
