package dev.wildware.composegl.ui.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `SceneView` inside a [WorldPanel], against a canvas that writes down what it was asked for.
 *
 * The panel runs the same scene pass a screen does, before the frame its tree is drawn in opens: a
 * dirty scene is rendered, a clean one is not, and marking one dirty is what makes the panel need
 * drawing again at all. The real-GPU half of this lives in the frontends' GL suites.
 */
class WorldPanelSceneTest {

    private val panel = WorldPanel(320f, 200f)
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 320f, 200f))
    private var nanos = 0L

    @AfterTest
    fun tearDown() = panel.close()

    /** One turn of a game loop: draw the panel only if it says it needs it, in a frame of its own. */
    private fun frame(): Boolean {
        nanos += 16_666_667L
        if (!panel.needsRedraw(nanos)) return false
        canvas.clear(Rect.of(0f, 0f, 320f, 200f))
        panel.draw(canvas) { tree ->
            canvas.begin(Viewport.oneToOne(Size(320f, 200f)))
            tree()
            canvas.end()
        }
        canvas.assertBalanced()
        return true
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun showScene(state: SceneViewState, draw: () -> Unit = {}) = panel.setContent {
        Box(Modifier.size(320f, 200f)) {
            SceneView(state, Modifier.offset(40f, 20f).size(120f, 80f)) { draw() }
        }
    }

    @Test
    fun `a dirty scene view inside a world panel is rendered into its picture`() {
        val state = SceneViewState()
        showScene(state)

        frames(2)

        assertEquals(1L, state.draws, "the panel's scene pass renders it")
        assertEquals(1, canvas.scenes.size)
        assertEquals(120 to 80, state.width to state.height, "the picture is the panel's own pixels")
        val shown = canvas.only<DrawCall.Image>().filter { it.texture === state.texture }
        assertEquals(1, shown.size, "and the panel's tree draws it")
    }

    @Test
    fun `a clean scene view inside a world panel is not rendered again`() {
        val state = SceneViewState()
        var reading by mutableStateOf(400)
        panel.setContent {
            Box(Modifier.size(320f, 200f)) {
                SceneView(state, Modifier.size(120f, 80f)) { }
                // Something else in the panel that changes, so the panel redraws without the scene.
                Box(Modifier.offset(200f, 0f).size(reading.toFloat() / 10f, 20f).background(Colour.White))
            }
        }
        frames(3)
        assertEquals(1L, state.draws)

        reading = 380
        assertTrue(frame() || frame(), "the change reached the panel")
        frames(5)

        assertEquals(1L, state.draws, "a scene nobody marked dirty is not rendered again")
        assertEquals(1, canvas.scenes.size)
    }

    @Test
    fun `marking a scene view dirty makes the world panel need a redraw`() {
        val state = SceneViewState()
        showScene(state)
        frames(3)
        nanos += 16_666_667L
        assertFalse(panel.needsRedraw(nanos), "nothing changed, so the panel is left alone")

        state.invalidate()

        nanos += 16_666_667L
        assertTrue(panel.needsRedraw(nanos), "a dirty scene has to make its panel draw again")
        frame()
        assertEquals(2L, state.draws, "and the redraw renders the scene")
        assertFalse(frame(), "then the panel is quiet again")
    }

    @Test
    fun `a live scene view keeps its world panel redrawing every frame`() {
        val state = SceneViewState()
        showScene(state)
        frames(2)
        val before = panel.draws

        // A live camera feed: the game marks the scene dirty once a frame.
        repeat(10) {
            state.invalidate()
            assertTrue(frame(), "a scene marked dirty each frame redraws its panel each frame")
        }

        assertEquals(before + 10, panel.draws)
        assertEquals(11L, state.draws)
    }

    @Test
    fun `a world panel counts its scenes in the budget it is handed`() {
        var now = 0L
        val budget = FrameBudget(window = 4, publishEveryMillis = 0L, nanoTime = { now })
        budget.isOn = true
        panel.budget = budget
        val state = SceneViewState()
        showScene(state) { now += 3_000_000L }

        frame()
        budget.endFrame()

        assertEquals(1, budget.reading.scenes, "the panel's scene counts as a scene")
        assertEquals(3f, budget.reading.sceneMillis, 0.01f)
    }

    @Test
    fun `a world panel's scene is handed the frame time and kept under the device's biggest picture`() {
        canvas.maxSceneSize = 64
        val state = SceneViewState()
        var seen = -1L
        panel.setContent {
            SceneView(state, Modifier.size(120f, 80f)) { seen = this.nanos }
        }
        panel.warn = { }

        frame()

        assertEquals(nanos, seen, "the scene sees the time the panel was advanced to")
        assertTrue(state.clamped)
        assertEquals(64 to 43, state.width to state.height, "cut to the biggest picture, the same shape")
    }

    @Test
    fun `drawing a panel with a dirty scene inside a frame that is already open fails loudly`() {
        val state = SceneViewState()
        showScene(state)
        nanos += 16_666_667L
        panel.needsRedraw(nanos)

        canvas.begin(Viewport.oneToOne(Size(320f, 200f)))
        val failure = assertFailsWith<IllegalStateException> { panel.draw(canvas) }
        assertTrue("WorldPanel" in failure.message.orEmpty(), "the error names the fix: ${failure.message}")
    }

    @Test
    fun `a frame block that never draws the tree fails rather than leaving the panel blank`() {
        showScene(SceneViewState())
        nanos += 16_666_667L
        panel.needsRedraw(nanos)

        assertFailsWith<IllegalStateException> { panel.draw(canvas) { } }
        assertTrue(panel.dirty, "the panel still needs drawing")
    }
}
