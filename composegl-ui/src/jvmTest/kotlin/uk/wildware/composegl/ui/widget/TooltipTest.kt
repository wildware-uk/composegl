package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.backend.MonospaceFontProvider
import uk.wildware.composegl.ui.draw.DrawPass
import uk.wildware.composegl.ui.focus.FocusManager
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.graphics.DrawCall
import uk.wildware.composegl.ui.graphics.RecordingCanvas
import uk.wildware.composegl.ui.host.UiHost
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyNavigator
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerId
import uk.wildware.composegl.ui.input.PointerRouter
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.MeasurePass
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.align
import uk.wildware.composegl.ui.modifier.focusable
import uk.wildware.composegl.ui.modifier.offset
import uk.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tooltip, and the three things the issue says it has to get right: it flips rather than being
 * clipped at an edge, moving from one to the next does not wait all over again, and it works from
 * a pad, where nothing is ever hovered.
 */
class TooltipTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas(Rect(0f, 0f, 400f, 300f))
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)

    private var wall = 0L

    @AfterEach
    fun tearDown() = host.dispose()

    private fun frame(millis: Long = 16L) {
        wall += millis * 1_000_000L
        host.frame(wall)
        canvas.clear(Rect(0f, 0f, 400f, 300f))
        MeasurePass().run(host.root, Constraints.atMost(400f, 300f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int, millis: Long = 16L) = repeat(count) { frame(millis) }

    private fun show(content: @Composable () -> Unit) {
        host.setContent {
            ProvideFonts(MonospaceFontProvider()) {
                TooltipHost(delayMillis = 500, fadeMillis = 100, content = content)
            }
        }
        frames(2)
    }

    private fun move(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))

    private fun tip(): DrawCall.Text? =
        canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text.startsWith("tip") }

    /** The box behind the tooltip's text: the last rectangle drawn, since it is drawn over everything. */
    private fun box(): DrawCall.Rectangle? {
        val text = tip() ?: return null
        return canvas.calls.filterIsInstance<DrawCall.Rectangle>()
            .lastOrNull { it.rect.left <= text.at.x && it.rect.right >= text.at.x }
    }

    /** Two things with tooltips, side by side near the top left. */
    @Composable
    private fun twoItems() {
        Tooltip("tip one") { Box(Modifier.size(40f)) }
        Tooltip("tip two", Modifier.offset(80f, 0f)) { Box(Modifier.size(40f)) }
    }

    // --- the issue's three ----------------------------------------------------------------------

    @Test
    fun `moving from one to the next does not wait all over again`() {
        show { twoItems() }

        move(20f, 20f)
        frames(40, 20)
        assertEquals("tip one", tip()?.text, "the first one should be up by now")

        move(100f, 20f)
        frames(4, 16)

        assertEquals("tip two", tip()?.text, "the second waited again instead of swapping straight over")
    }

    @Test
    fun `a tooltip at the bottom of the screen goes above the thing instead of off the edge`() {
        show {
            Tooltip("tip low", Modifier.offset(20f, 270f)) { Box(Modifier.size(30f)) }
        }

        move(30f, 280f)
        frames(40, 20)

        val box = checkNotNull(box())
        assertTrue(box.rect.bottom <= 300f, "it hangs off the bottom: ${box.rect}")
        assertTrue(box.rect.bottom <= 270f, "it should be above the thing it belongs to: ${box.rect}")
    }

    @Test
    fun `a tooltip at the right hand edge slides back on screen`() {
        show {
            Tooltip("tip wide enough to matter", Modifier.offset(370f, 40f)) { Box(Modifier.size(30f)) }
        }

        move(380f, 50f)
        frames(40, 20)

        val box = checkNotNull(box())
        assertTrue(box.rect.right <= 400f, "it is cut off at the right: ${box.rect}")
        assertTrue(box.rect.left >= 0f, "and it should not have been pushed off the other side")
    }

    @Test
    fun `focus shows a tooltip so a pad player sees one`() {
        show {
            Tooltip("tip one") { Box(Modifier.size(40f).focusable()) }
        }

        keys.onKey(uk.wildware.composegl.ui.input.KeyEvent(Key.Tab, uk.wildware.composegl.ui.input.KeyEventType.Down))
        frames(40, 20)

        assertEquals("tip one", tip()?.text, "nothing is ever hovered on a pad")
    }

    // --- the rest of it -------------------------------------------------------------------------

    @Test
    fun `nothing is shown before the delay is up`() {
        show { twoItems() }

        move(20f, 20f)
        frames(6, 16)

        assertNull(tip(), "it appeared straight away, which makes a screen flicker as a pointer crosses it")
    }

    @Test
    fun `it fades in rather than appearing`() {
        show { twoItems() }

        move(20f, 20f)
        frames(32, 20)
        val early = checkNotNull(canvas.alphaOfTip()) { "nothing was drawn at all" }

        frames(10, 20)
        val later = checkNotNull(canvas.alphaOfTip())

        assertTrue(early < later, "it should still have been fading: $early then $later")
        assertEquals(1f, later, 0.01f, "and then it has arrived")
    }

    @Test
    fun `leaving takes it away`() {
        show { twoItems() }

        move(20f, 20f)
        frames(40, 20)
        assertNotNull(tip())

        move(300f, 200f)
        frames(20, 20)

        assertNull(tip(), "it should have gone when the pointer left")
    }

    @Test
    fun `a tooltip that follows the pointer is drawn under it rather than under the thing`() {
        show {
            Tooltip("tip follow", follow = true) { Box(Modifier.size(200f, 100f)) }
        }

        move(150f, 80f)
        frames(40, 20)
        val first = checkNotNull(box()).rect.left

        move(60f, 80f)
        frames(2, 16)

        assertTrue(checkNotNull(box()).rect.left < first, "it should have moved with the pointer")
    }

    private fun RecordingCanvas.alphaOfTip(): Float? {
        val text = calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text.startsWith("tip") } ?: return null
        return text.alpha
    }
}
