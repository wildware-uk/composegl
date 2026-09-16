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
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scene view in a composed screen, driven the way a player drives it: a click, a key and a pad
 * button on a control that marks the scene dirty each redraw it exactly once, in the same frame;
 * and a splitter dragged across it renders it again at the size it was dragged to.
 */
class SceneViewInputTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 300f))
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)
    private val pad = GamepadNavigator(focus)
    private val renderer = UiRenderer(host, canvas).also { it.focus = focus }
    private val viewport = Viewport.oneToOne(Size(400f, 300f))
    private var nanos = 0L

    private val scene = SceneViewState()
    private var draws = 0

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

    /** A spin button over a preview: pressing it turns the model a notch, which is a redraw. */
    @Composable
    private fun preview() {
        Column {
            Button("SPIN", onClick = { scene.invalidate() }, modifier = Modifier.testTag("spin"), initialFocus = true)
            SceneView(scene, Modifier.size(160f, 90f).testTag("scene")) { draws++ }
        }
    }

    private fun node(tag: String): UiNode = checkNotNull(host.root.findOrNull(tag)) { "no $tag in\n${host.root}" }

    private fun centre(tag: String): Offset = node(tag).boundsInRoot.let { Offset(it.left + it.width / 2f, it.top + it.height / 2f) }

    @Test
    fun `a click on a control that invalidates the scene redraws it once`() {
        show { preview() }
        assertEquals(1, draws, "drawn once when it first had a size")

        val at = centre("spin")
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at, PointerButton.Primary))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at, PointerButton.Primary))
        assertTrue(frame(), "the frame after the click has a new scene in it")
        frames(5)

        assertEquals(2, draws)
        val picture = canvas.only<DrawCall.Image>().single { it.texture === scene.texture }
        assertEquals(node("scene").boundsInRoot, picture.destination)
    }

    @Test
    fun `the keyboard presses the same control and the scene redraws once`() {
        show { preview() }

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        frames(5)

        assertEquals(2, draws)
    }

    @Test
    fun `the pad presses the same control and the scene redraws once`() {
        show { preview() }

        pad.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        pad.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        frames(5)

        assertEquals(2, draws)
    }

    @Test
    fun `a click that invalidates inside onLaidOut is drawn in that same frame`() {
        show { preview() }
        var clicked = false
        renderer.onLaidOut = { _ ->
            if (clicked) {
                clicked = false
                scene.invalidate()
            }
        }

        clicked = true
        frame()

        assertEquals(2, draws, "input handled after layout still reaches this frame's prepass")
    }

    @Test
    fun `dragging a splitter renders the scene again at the size it was dragged to`() {
        var fraction by mutableStateOf(0.5f)
        show {
            Splitter(
                fraction,
                onFractionChange = { fraction = it },
                modifier = Modifier.size(400f, 200f),
                thickness = 10f,
                first = { SceneView(scene, Modifier.fillMaxSize().testTag("scene")) { draws++ } },
                second = { Box(Modifier.fillMaxSize()) },
            )
        }
        val before = scene.width
        assertEquals(node("scene").width.toInt(), before)

        val grip = Offset(node("scene").boundsInRoot.right + 5f, 100f)
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, grip))
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, grip, PointerButton.Primary))
        frame()
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(grip.x - 100f, 100f), setOf(PointerButton.Primary)))
        frame()
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(grip.x - 100f, 100f), PointerButton.Primary))
        frames(3)

        assertTrue(fraction < 0.5f, "the drag moved the splitter: $fraction")
        assertEquals(before - 100, scene.width, "the target follows the panel")
        assertEquals(node("scene").width.toInt(), scene.width)
        assertEquals(2, draws, "one render at the old size and one at the new")
        assertNotNull(canvas.only<DrawCall.Image>().singleOrNull { it.texture === scene.texture })
    }
}
