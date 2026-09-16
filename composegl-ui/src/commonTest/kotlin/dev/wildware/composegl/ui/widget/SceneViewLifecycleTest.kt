package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The expensive parts of a scene view: when its picture is made, what a resize does to it, when it
 * is given back, how big it may get, and what it costs a frame — all against a canvas that writes
 * down what it was asked for, and a budget on a clock moved by hand.
 */
class SceneViewLifecycleTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))
    private var now = 0L
    private val budget = FrameBudget(window = 4, publishEveryMillis = 0L, nanoTime = { now })
    private val renderer = UiRenderer(host, canvas, budget)
    private val warnings = mutableListOf<String>()
    private var nanos = 0L

    private val plain = Viewport.oneToOne(Size(400f, 300f))

    init {
        renderer.scenes.warn = { warnings += it }
    }

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(viewport: Viewport = plain): Boolean {
        nanos += 16_666_667L
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
        return renderer.render(viewport, nanos)
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun show(content: @Composable () -> Unit) = host.setContent(content)

    private fun picture(state: SceneViewState): DrawCall.Image =
        canvas.only<DrawCall.Image>().single { it.texture === state.texture }

    // --- allocate ---------------------------------------------------------------------------------

    @Test
    fun `nothing is allocated before the first render`() {
        val state = SceneViewState()
        assertNull(state.texture)
        show { SceneView(state, Modifier.size(80f)) { } }

        assertTrue(canvas.scenes.isEmpty(), "composing alone makes no picture")
        frame()

        assertEquals(1, canvas.scenes.size)
        assertTrue(canvas.scenes.single().allocated)
    }

    @Test
    fun `a scene view laid out off the screen allocates nothing until it comes on`() {
        val state = SceneViewState()
        var left by mutableStateOf(1000f)
        var draws = 0
        show { Box(Modifier.size(400f, 300f)) { SceneView(state, Modifier.offset(left, 20f).size(80f)) { draws++ } } }

        frames(4)
        assertEquals(0, draws, "a panel nobody can see is not worth a picture")
        assertTrue(canvas.scenes.isEmpty())
        assertTrue(state.dirty, "still wanted, for when it can be seen")

        left = 20f
        frames(2)

        assertEquals(1, draws)
        assertNotNull(state.texture)
    }

    @Test
    fun `a live scene that scrolls off the screen stops rendering and keeps its picture`() {
        val state = SceneViewState()
        var left by mutableStateOf(20f)
        var draws = 0
        show { Box(Modifier.size(400f, 300f)) { SceneView(state, Modifier.offset(left, 20f).size(80f)) { draws++ } } }
        frames(2)
        val made = state.texture

        left = 1000f
        repeat(5) {
            state.invalidate()
            frame()
        }

        assertEquals(1, draws, "no GPU work for a camera nobody can see")
        assertSame(made, state.texture)
    }

    // --- resize -----------------------------------------------------------------------------------

    @Test
    fun `a drag stretches the old picture and a steady frame reallocates it sharp`() {
        val state = SceneViewState()
        var side by mutableStateOf(100f)
        var draws = 0
        show { SceneView(state, Modifier.size(side, 50f)) { draws++ } }
        frames(2)
        val first = assertNotNull(state.texture)

        // A splitter being dragged: a new width every frame.
        for (width in listOf(110f, 125f, 140f, 160f)) {
            side = width
            frame()
            assertSame(first, state.texture, "while the size is still changing the picture is kept")
            assertEquals(Rect.of(0f, 0f, width, 50f), picture(state).destination, "and stretched over the panel")
            assertEquals(100 to 50, state.width to state.height)
        }
        assertEquals(1, draws, "a drag does not reallocate or render a frame at a time")
        assertEquals(1, canvas.scenes.size)

        // Released: the size holds for a frame.
        frame()

        assertEquals(2, draws)
        assertEquals(160 to 50, state.width to state.height, "the picture lands at exactly the panel's size")
        assertTrue((first as dev.wildware.composegl.ui.graphics.SceneSurface).closed, "and the old one is given back")
        assertEquals(listOf(true, true), canvas.scenes.map { it.allocated })
        frames(3)
        assertEquals(2, draws, "and then it is still again")
    }

    @Test
    fun `a stretched frame asks for the frame that sharpens it`() {
        val state = SceneViewState()
        var side by mutableStateOf(100f)
        show { SceneView(state, Modifier.size(side)) { } }
        frames(3)
        assertFalse(frame())

        side = 140f
        assertTrue(frame())
        assertEquals(100, state.width, "this frame stretched the old picture")
        // What a game that skips unchanged frames reads before it bothers with the next one.
        assertTrue(host.tree.hasChanges, "a game that skips unchanged frames must still get the one that reallocates")
        assertTrue(frame())
        assertEquals(140, state.width)
        assertFalse(frame())
    }

    @Test
    fun `a live scene keeps rendering into the stretched picture during a drag`() {
        val state = SceneViewState()
        var side by mutableStateOf(100f)
        show { SceneView(state, Modifier.size(side)) { } }
        frames(2)

        for (width in listOf(120f, 140f, 160f)) {
            side = width
            state.invalidate()
            frame()
        }

        assertEquals(4, canvas.scenes.size, "a camera feed does not freeze while its panel is dragged")
        assertEquals(listOf(true, false, false, false), canvas.scenes.map { it.allocated })
        assertTrue(canvas.scenes.drop(1).all { it.width == 100 && it.height == 100 }, "into the picture it already has")
    }

    @Test
    fun `a new resolution scale on a still panel reallocates at once`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(200f, 100f)) { draws++ } }
        frames(2)

        state.resolutionScale = 0.5f
        frame()

        assertEquals(2, draws, "nothing is being dragged, so there is nothing to wait for")
        assertEquals(100 to 50, state.width to state.height)
    }

    // --- release ----------------------------------------------------------------------------------

    @Test
    fun `a scene view that leaves the composition gives back a state it did not remember`() {
        // Hoisted out of the composition entirely: a game object holding its own preview.
        val state = SceneViewState()
        var shown by mutableStateOf(true)
        show { if (shown) SceneView(state, Modifier.size(40f)) { } }
        frames(2)
        val surface = canvas.scenes.single().surface

        shown = false
        frames(2)

        assertTrue(surface.closed, "a row scrolled out of a list frees its picture")
        assertNull(state.texture)
        assertEquals(0 to 0, state.width to state.height)

        shown = true
        frames(2)
        assertNotNull(state.texture, "and a row scrolled back makes a new one")
        assertEquals(2, canvas.scenes.size)
    }

    // --- cap --------------------------------------------------------------------------------------

    @Test
    fun `a panel bigger than the device allows is cut down keeping its shape`() {
        canvas.maxSceneSize = 256
        val state = SceneViewState()
        var seen = 0 to 0
        show { SceneView(state, Modifier.size(400f, 200f)) { seen = width to height } }

        frame()

        assertEquals(256 to 128, seen, "the long side at the maximum and the short side in proportion")
        assertEquals(256 to 128, state.width to state.height)
        assertTrue(state.clamped)
        assertEquals(1, canvas.scenes.size)
    }

    @Test
    fun `a resolution scale cannot push past the maximum`() {
        canvas.maxSceneSize = 300
        val state = SceneViewState(resolutionScale = 4f)
        show { SceneView(state, Modifier.size(100f, 50f)) { } }

        frame()

        assertEquals(300 to 150, state.width to state.height)
        assertTrue(state.clamped)
    }

    @Test
    fun `a clamped scene warns once however often it renders`() {
        canvas.maxSceneSize = 64
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(200f, 100f)) { } }

        repeat(5) {
            state.invalidate()
            frame()
        }

        assertEquals(5L, state.draws, "clamping never fails a render")
        assertEquals(1, warnings.size, "one warning, not one a frame: $warnings")
        assertTrue("64" in warnings.single(), warnings.single())
    }

    @Test
    fun `a scene that fits is not clamped and does not warn`() {
        canvas.maxSceneSize = 4096
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(200f, 100f)) { } }

        frames(2)

        assertFalse(state.clamped)
        assertTrue(warnings.isEmpty())
    }

    // --- cost -------------------------------------------------------------------------------------

    @Test
    fun `a scene render is timed and counted in the frame budget`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(80f).testTag("preview")) { now += 4_000_000L } }

        frame()

        val reading = budget.reading
        assertEquals(1, reading.scenes)
        assertEquals(4f, reading.sceneMillis)
        assertTrue(reading.totalMillis >= 4f, "the scene is part of what the frame cost: $reading")
    }

    @Test
    fun `four live viewports show as four scene renders a frame`() {
        val states = List(4) { SceneViewState() }
        show { Box { states.forEachIndexed { at, state -> SceneView(state, Modifier.offset(at * 90f, 0f).size(80f)) { } } } }
        frames(2)

        states.forEach { it.invalidate() }
        frame()

        assertEquals(4, budget.reading.scenes)
        frame()
        assertEquals(0, budget.reading.scenes, "and a still frame shows none")
    }

    @Test
    fun `a scene render is in the draw call trace under its own node`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(80f).testTag("preview")) { } }

        frame()

        val culprit = budget.reading.culprits.single { it.reason == BatchBreak.Scene }
        assertEquals("scene view#preview", culprit.name)
        assertEquals(1, culprit.calls)
    }

    @Test
    fun `a budget switched off counts no scenes`() {
        budget.isOn = false
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(80f)) { draws++ } }

        frame()

        assertEquals(1, draws, "the scene still renders")
        assertEquals(0, budget.reading.scenes)
    }
}
