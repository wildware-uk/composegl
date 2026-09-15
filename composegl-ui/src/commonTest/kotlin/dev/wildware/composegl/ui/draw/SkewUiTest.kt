package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.skew
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A leaning banner on a composed screen, driven through `uiTest` the way a player drives it — a
 * click, the pad, the keyboard — and judged by what the next frame drew.
 */
class SkewUiTest {

    private val red = Colour.rgb(0xE5484D)
    private var clicks = 0

    /** The lean a title card settles at: twelve degrees, top forward. */
    private val lean = -12f

    /** How far the top-left corner of a 40-tall banner slides once it has settled at [lean]. */
    private val settledShift = tan(-lean * PI.toFloat() / 180f) * 20f

    /**
     * A banner that leans when it is pressed, stands up on Escape, and leaves the screen on Delete.
     * The lean is animated, so settling the screen is what gets it all the way over.
     */
    private fun banner(): UiTest = uiTest(Size(400f, 300f)) {
        var leaning by remember { mutableStateOf(false) }
        var shown by remember { mutableStateOf(true) }
        val angle by animateFloatAsState(if (leaning) lean else 0f, Tween(durationMillis = 200))
        Box(Modifier.fillMaxSize()) {
            if (shown) {
                Box(
                    Modifier.testTag("banner")
                        .size(120f, 40f)
                        .skew(x = angle)
                        .background(red)
                        .focusable(initial = true)
                        .clickable {
                            clicks++
                            leaning = true
                        }
                        .onKeyEvent { event ->
                            when {
                                event.type != KeyEventType.Down -> false
                                event.key == Key.Escape -> { leaning = false; true }
                                event.key == Key.Delete -> { shown = false; true }
                                else -> false
                            }
                        },
                )
            }
        }
    }

    /** One frame drawn from scratch into the headless canvas. */
    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    private fun RecordingCanvas.slant(): DrawCall.LayerOnto? = only<DrawCall.LayerOnto>().singleOrNull()

    private fun RecordingCanvas.redBox(): Rect = only<DrawCall.Rectangle>().single { it.colour == red }.rect

    private inline fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun assertLeaning(canvas: RecordingCanvas, because: String) {
        val slant = checkNotNull(canvas.slant()) { "$because: nothing was slanted" }
        assertEquals(Rect.of(0f, 0f, 120f, 40f), slant.bounds, "$because: captured upright at its own size")
        val expected = listOf(
            Offset(settledShift, 0f),
            Offset(120f + settledShift, 0f),
            Offset(120f - settledShift, 40f),
            Offset(-settledShift, 40f),
        )
        for (index in 0 until 4) {
            assertEquals(expected[index].x, slant.corners[index].x, 0.01f, "$because: corner $index x")
            assertEquals(expected[index].y, slant.corners[index].y, 0.01f, "$because: corner $index y")
        }
    }

    @Test
    fun `a banner that is not leaning draws upright with no picture`() = banner().using { ui ->
        val canvas = ui.drawn()

        assertNull(canvas.slant(), "nothing to slant yet")
        assertEquals(Rect.of(0f, 0f, 120f, 40f), canvas.redBox())
    }

    @Test
    fun `clicking the banner leans it over to its angle`() = banner().using { ui ->
        ui.click("banner")

        assertEquals(1, clicks, "the click reached the banner")
        assertEquals(lean, ui.node("banner").resolved.skewX, 0.001f)
        assertLeaning(ui.drawn(), "after the click")
    }

    @Test
    fun `south on the pad leans the focused banner the same way`() = banner().using { ui ->
        ui.assertFocused("banner")

        ui.pad(GamepadButton.South)

        assertLeaning(ui.drawn(), "after South")
    }

    @Test
    fun `escape on the focused banner stands it back up`() = banner().using { ui ->
        ui.click("banner")
        assertLeaning(ui.drawn(), "leaning first")

        ui.key(Key.Escape)

        val canvas = ui.drawn()
        assertEquals(0f, ui.node("banner").resolved.skewX, 0.001f)
        assertNull(canvas.slant(), "upright again, and back to costing no picture")
        assertEquals(Rect.of(0f, 0f, 120f, 40f), canvas.redBox())
    }

    @Test
    fun `clicks on a leaning banner land in its upright box not its slanted shape`() = banner().using { ui ->
        ui.click("banner")
        assertLeaning(ui.drawn(), "leaning before the clicks that matter")

        // Inside the box the banner was laid out in, but left of where its slanted top edge begins.
        assertTrue(ui.click(Offset(1f, 1f)), "the upright box still owns its corner")
        assertEquals(2, clicks)

        // Past the box's right edge, but under the slanted top edge, which reaches past 124.
        ui.click(Offset(122f, 2f))
        assertEquals(2, clicks, "the part that leans out of the box is not clickable")
    }

    @Test
    fun `a leaning banner taken off the screen leaves no slant behind`() = banner().using { ui ->
        ui.click("banner")
        assertLeaning(ui.drawn(), "leaning first")

        ui.key(Key.Delete)

        ui.assertDoesNotExist("banner")
        val canvas = ui.drawn()
        assertNull(canvas.slant(), "no picture of a banner that is not there")
        assertTrue(canvas.only<DrawCall.Rectangle>().none { it.colour == red }, "and nothing red at all")
    }

    @Test
    fun `a slant inside a slant is a picture inside a picture`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.testTag("outer").size(100f).skew(x = -10f)) {
            Box(Modifier.testTag("inner").size(40f).skew(y = 10f).background(red))
        }
    }.using { ui ->
        val canvas = ui.drawn()

        val slants = canvas.only<DrawCall.LayerOnto>()
        assertEquals(
            listOf(Rect.of(0f, 0f, 40f, 40f), Rect.of(0f, 0f, 100f, 100f)),
            slants.map { it.bounds },
            "the inner one is put down inside the outer picture, before the outer one is",
        )
        assertTrue(slants[0].corners[1].y > slants[0].corners[0].y, "the inner one slides down")
        assertTrue(slants[1].corners[0].x > 0f, "the outer one leans forward")
    }

    @Test
    fun `a slanted node with no size draws without failing`() = uiTest(Size(400f, 300f)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.testTag("empty").size(0f).skew(x = -12f, y = 5f).background(red))
        }
    }.using { ui ->
        val canvas = ui.drawn()

        assertEquals(1, canvas.frames, "a whole frame came out")
        assertTrue(
            canvas.only<DrawCall.Rectangle>().none { !it.rect.isEmpty },
            "and nothing with any area was drawn for it",
        )
    }
}
