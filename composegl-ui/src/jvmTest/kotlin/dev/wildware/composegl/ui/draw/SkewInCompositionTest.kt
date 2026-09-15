package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.modifier.testTag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.tan

/**
 * A leaning banner on a real composed screen, drawn every frame through a recording canvas while
 * its lean animates. Driven by hand rather than through `uiTest`, which settles every animation
 * before a test can look, because the frames part way through the tween are the point here; the
 * rest of what a player does to a banner is in `SkewUiTest`.
 */
class SkewInCompositionTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)

    private val red = Colour.rgb(0xE5484D)
    private var clock = 0L
    private var clicks = 0

    /** The lean a title card settles at: twelve degrees, top forward. */
    private val lean = -12f

    @AfterEach
    fun tearDown() = host.dispose()

    private fun show() {
        host.setContent {
            var leaning by remember { mutableStateOf(false) }
            val angle by animateFloatAsState(if (leaning) lean else 0f, Tween(durationMillis = 200))
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.testTag("banner")
                        .size(120f, 40f)
                        .skew(x = angle)
                        .background(red)
                        .focusable(initial = true)
                        .clickable {
                            clicks++
                            leaning = true
                        },
                )
            }
        }
        frames(2)
    }

    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.fixed(400f, 300f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun click(x: Float, y: Float) {
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))
        frame()
    }

    private fun slant(): DrawCall.LayerOnto? = canvas.calls.filterIsInstance<DrawCall.LayerOnto>().singleOrNull()

    /** How far the banner's top-left corner has slid across from where it was laid out. */
    private fun topLeftShift(): Float = slant()?.corners?.get(0)?.x ?: 0f

    @Test
    fun `clicking the banner leans it over the next frames and it settles at its angle`() {
        show()

        click(60f, 20f)
        assertEquals(1, clicks, "the click reached the banner")

        // Part way through the tween the top has slid some of the way, and it keeps sliding.
        frames(3)
        val early = topLeftShift()
        frames(3)
        val later = topLeftShift()
        val settledShift = -tan(Math.toRadians(lean.toDouble())).toFloat() * 20f
        assertTrue(early > 0f && early < settledShift, "part way: $early of $settledShift")
        assertTrue(later > early, "still leaning further: $early then $later")

        frames(20)
        val settled = checkNotNull(slant()) { "a settled lean still slants" }
        assertEquals(lean, host.root.find("banner").resolved.skewX, 0.001f)
        assertEquals(Rect.of(0f, 0f, 120f, 40f), settled.bounds, "captured upright at its own size")
        assertEquals(settledShift, settled.corners[0].x, 0.01f, "top-left slid forward, to the right")
        assertEquals(120f + settledShift, settled.corners[1].x, 0.01f, "and the top-right with it")
        assertEquals(120f - settledShift, settled.corners[2].x, 0.01f, "the bottom slid back")
        assertEquals(-settledShift, settled.corners[3].x, 0.01f)
        assertEquals(0f, settled.corners[0].y, 0.01f, "a horizontal skew moves nothing up or down")
        assertEquals(40f, settled.corners[3].y, 0.01f)
    }
}
