package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.SceneSurface
import dev.wildware.composegl.ui.graphics.SceneTarget
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.layout.Alignment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The scene view, its state and the prepass, against a canvas that writes down what it was asked
 * for: no draw while clean, one draw per invalidate, the target at the panel's pixels times the
 * scale, the picture where the node was laid out, and nothing at all from a panel of no size.
 */
class SceneViewTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))
    private val renderer = UiRenderer(host, canvas)
    private var nanos = 0L

    /** Twice as many pixels as design units, both ways. */
    private val doubled = Viewport(Size(400f, 300f), Size(800f, 600f))
    private val plain = Viewport.oneToOne(Size(400f, 300f))

    @AfterTest
    fun tearDown() = host.dispose()

    private fun frame(viewport: Viewport = plain): Boolean {
        nanos += 16_666_667L
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
        return renderer.render(viewport, nanos)
    }

    private fun frames(count: Int, viewport: Viewport = plain) = repeat(count) { frame(viewport) }

    private fun show(content: @Composable () -> Unit) = host.setContent(content)

    private fun sceneImages(state: SceneViewState): List<DrawCall.Image> =
        canvas.only<DrawCall.Image>().filter { it.texture === state.texture }

    @Test
    fun `a scene is drawn once and then not again while nothing marks it dirty`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(120f, 80f)) { draws++ } }

        frames(10)

        assertEquals(1, draws, "a clean scene view must cost no draw")
        assertFalse(state.dirty)
        assertEquals(1L, state.draws)
    }

    @Test
    fun `each invalidate is exactly one more draw`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(120f, 80f)) { draws++ } }
        frames(3)

        state.invalidate()
        assertTrue(state.dirty)
        frames(5)
        assertEquals(2, draws)

        state.invalidate()
        state.invalidate()
        frames(5)
        assertEquals(3, draws, "two invalidates before a frame are still one redraw")
    }

    @Test
    fun `an invalidate alone makes the frame count as changed`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(120f, 80f)) { } }
        frames(3)
        assertFalse(frame(), "nothing changed, so the frame should say so")

        state.invalidate()
        assertTrue(frame(), "a game that skips an unchanged frame would never show the new scene")
    }

    @Test
    fun `the target is the panel in real pixels`() {
        val state = SceneViewState()
        var seen = 0 to 0
        show { SceneView(state, Modifier.size(120f, 80f)) { seen = width to height } }

        frame(doubled)

        assertEquals(240 to 160, seen, "the scope's size is pixels, not design units")
        assertEquals(240, state.width)
        assertEquals(160, state.height)
        val scene = canvas.scenes.single()
        assertEquals(240, scene.width)
        assertEquals(160, scene.height)
    }

    @Test
    fun `a resolution scale of a half renders at half the pixels`() {
        val state = SceneViewState(resolutionScale = 0.5f)
        show { SceneView(state, Modifier.size(120f, 80f)) { } }

        frame(doubled)

        assertEquals(120, state.width)
        assertEquals(80, state.height)
    }

    @Test
    fun `changing the resolution scale renders again at the new size`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(100f, 50f)) { draws++ } }
        frames(2)

        state.resolutionScale = 0.25f
        frames(2)

        assertEquals(2, draws)
        assertEquals(25, state.width)
        assertEquals(13, state.height, "a part pixel rounds up rather than losing a row")
    }

    @Test
    fun `the picture is drawn where the node was laid out`() {
        val state = SceneViewState()
        show {
            Box(Modifier.size(400f, 300f)) {
                SceneView(state, Modifier.offset(30f, 40f).padding(5f).size(120f, 80f)) { clear(Colour.Black) }
            }
        }

        frames(2)

        val image = sceneImages(state).single()
        // The padding is inside the size, so the picture fills what is left of it.
        assertEquals(Rect.of(35f, 45f, 110f, 70f), image.destination)
        assertEquals(110 to 70, state.width to state.height, "the target is the content box, not the padding")
        assertEquals(Colour.Black, canvas.scenes.single().clears.single())
    }

    @Test
    fun `a clean scene view still draws its picture every frame`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(60f)) { } }
        frames(3)

        state.invalidate()
        frame()
        canvas.clear(Rect.of(0f, 0f, 400f, 300f))
        // A frame the tree is drawn in for some other reason.
        renderer.render(plain, nanos + 1)

        assertEquals(1, sceneImages(state).size)
    }

    @Test
    fun `the picture goes through rounded corners and opacity like any other`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(100f, 50f).clip(8f).alpha(0.5f)) { } }

        frames(2)

        assertEquals(1, sceneImages(state).size)
        val layer = canvas.only<DrawCall.Layer>().singleOrNull()
        assertNotNull(layer, "rounded corners cut the picture through a layer: $canvas")
        assertEquals(0.5f, layer.alpha, "and the fade applies to what comes out")
    }

    @Test
    fun `a scaled panel renders at the pixels it is drawn at`() {
        val state = SceneViewState()
        show { SceneView(state, Modifier.size(100f, 50f).scale(2f)) { } }

        frames(2)

        assertEquals(200 to 100, state.width to state.height)
    }

    @Test
    fun `a zero sized scene view draws nothing and does not throw`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(0f, 80f)) { draws++ } }

        frames(3)

        assertEquals(0, draws)
        assertNull(state.texture)
        assertTrue(canvas.scenes.isEmpty())
        assertTrue(canvas.only<DrawCall.Image>().isEmpty())
        assertTrue(state.dirty, "still wanted, so it draws the moment it has some room")
    }

    @Test
    fun `a scene view that grows from nothing draws once it has room`() {
        val state = SceneViewState()
        var side by mutableStateOf(0f)
        var draws = 0
        show { SceneView(state, Modifier.size(side)) { draws++ } }
        frames(2)

        side = 50f
        frames(2)

        assertEquals(1, draws)
        assertEquals(50, state.width)
    }

    @Test
    fun `a recomposition keeps the target`() {
        var label by mutableStateOf(0)
        val seen = mutableListOf<SceneViewState>()
        show {
            val remembered = rememberSceneViewState()
            seen += remembered
            // Read so this scope recomposes.
            if (label >= 0) SceneView(remembered, Modifier.size(64f)) { }
        }
        frames(2)
        val before = assertNotNull(seen.last().texture)

        label = 1
        frames(2)

        assertTrue(seen.size > 1, "the test is about a recomposition, so there has to have been one")
        assertSame(seen.first(), seen.last())
        assertSame(before, seen.last().texture, "a recomposition must not throw the texture away")
        assertEquals(1, canvas.scenes.size)
    }

    @Test
    fun `a resize reallocates at the new size and draws again`() {
        val state = SceneViewState()
        var side by mutableStateOf(40f)
        var draws = 0
        show { SceneView(state, Modifier.size(side)) { draws++ } }
        frames(2)

        side = 90f
        frames(2)

        assertEquals(2, draws)
        assertEquals(90, state.width)
        assertEquals(listOf(true, true), canvas.scenes.map { it.allocated })
        assertTrue(canvas.scenes.first().surface.closed, "the old target is given back")
    }

    @Test
    fun `release gives the target back and the next frame makes a new one`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(40f)) { draws++ } }
        frames(2)
        val first = canvas.scenes.single().surface

        state.release()
        assertTrue(first.closed)
        assertNull(state.texture)
        frames(2)

        assertEquals(2, draws)
        assertNotNull(state.texture)
    }

    @Test
    fun `a remembered state gives its target back when it leaves the composition`() {
        var shown by mutableStateOf(true)
        show { if (shown) SceneView(rememberSceneViewState(), Modifier.size(40f)) { } }
        frames(2)
        val surface = canvas.scenes.single().surface

        shown = false
        frames(2)

        assertTrue(surface.closed)
    }

    @Test
    fun `the scope hands over the frame time and the raw hatch`() {
        val state = SceneViewState()
        var at = -1L
        show {
            SceneView(state, Modifier.size(40f)) {
                at = nanos
                raw { }
            }
        }

        frame()

        assertEquals(nanos, at)
        assertEquals(1, canvas.scenes.single().raws)
    }

    @Test
    fun `a game can drive the prepass itself`() {
        val state = SceneViewState()
        var draws = 0
        show { SceneView(state, Modifier.size(40f)) { draws++ } }
        renderer.renderScenes = false

        frames(3)
        assertEquals(0, draws, "switched off, the renderer leaves the scenes to the game")

        val drawn = renderer.scenes.render(plain, nanos)
        assertEquals(1, drawn)
        assertEquals(1, draws)
        assertEquals(0, renderer.scenes.render(plain, nanos), "clean now, so a second pass does nothing")

        frame()
        assertEquals(1, sceneImages(state).size, "the tree draws what the game's own pass made")
    }

    @Test
    fun `right to left places the panel on the other side without mirroring the picture`() {
        val state = SceneViewState()
        show {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.size(400f, 300f)) {
                    SceneView(state, Modifier.align(Alignment.TopStart).size(100f, 50f)) { }
                }
            }
        }

        frames(2)

        val image = sceneImages(state).single()
        assertEquals(Rect.of(300f, 0f, 100f, 50f), image.destination)
        assertEquals(null, image.source, "the whole picture, the right way round")
    }

    @Test
    fun `a canvas that cannot draw scenes draws nothing and asks again later`() {
        val state = SceneViewState()
        val silent = UiRenderer(host, NoScenes(RecordingCanvas()))
        var draws = 0
        show { SceneView(state, Modifier.size(40f)) { draws++ } }

        repeat(3) { silent.render(plain, nanos + it) }

        assertEquals(0, draws)
        assertNull(state.texture)
    }

    /** A canvas that has never heard of a scene: the interface's own defaults. */
    private class NoScenes(inner: RecordingCanvas) : UiCanvas by inner {
        override val drawsScenes: Boolean get() = false

        override fun scene(surface: SceneSurface?, width: Int, height: Int, draw: (SceneTarget) -> Unit): SceneSurface? = null
    }
}
