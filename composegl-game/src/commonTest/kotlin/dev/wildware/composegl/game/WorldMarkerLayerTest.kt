package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The world marker layer, on every platform the toolkit builds for.
 *
 * The thorough version of these lives in the JVM tests, with the pointer and the pad in it. This
 * is the part that has to hold on Linux and in a browser as well: the arithmetic that places a
 * marker, holds one at an edge and fades one out, which is all of it that could quietly depend on
 * something only a desktop has.
 */
class WorldMarkerLayerTest {

    private val opened = mutableListOf<UiTest>()

    private val headless = HeadlessBackend(Screen)

    /** Whether the whole world is behind the lens, the way turning round puts it. */
    private var behind = false

    /** The world is already the screen; a point's `z` is how far away it is. */
    private val camera = WorldProjection { point, _, onto ->
        onto.set(point.x, point.y, if (behind) -point.z else point.z)
        true
    }

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), backend = headless, content = content).also { opened += it }

    private fun UiTest.frame(): List<DrawCall> {
        headless.canvas.clear(Screen)
        render()
        return headless.canvas.calls
    }

    private fun UiTest.drawn(text: String): DrawCall.Text? =
        frame().filterIsInstance<DrawCall.Text>().firstOrNull { it.text == text }

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 0.5f, "$message: expected $expected but was $actual")

    /**
     * That an arrow was drawn and that every corner of it is on the layer.
     *
     * The second half is the part worth having: a triangle drawn nineteen pixels past the right
     * edge is recorded by the backend exactly like one on screen, and is no use to a player at all.
     * The half pixel is the float slack the rest of these tests allow — the arrow is meant to reach
     * the inset line and no further.
     */
    private fun UiTest.assertArrowsOnScreen() {
        val fans = frame().filterIsInstance<DrawCall.Fan>()
        assertTrue(fans.isNotEmpty(), "nothing was drawn to say which way to look")
        for (fan in fans) {
            for (point in fan.points) {
                assertTrue(
                    point.x >= -0.5f && point.x <= 400.5f && point.y >= -0.5f && point.y <= 300.5f,
                    "an arrow corner landed off the 400 by 300 layer: $point",
                )
            }
        }
    }

    @Test
    fun `a marker sits on the point the camera projected it to`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = 120f, y = 90f) { Text("NAME", Modifier.testTag("plate")) }
            }
        }

        val bounds = ui.node("plate").boundsInRoot
        assertNear(120f, bounds.centre.x, "not on its point across")
        assertNear(90f, bounds.centre.y, "not on its point down")
    }

    @Test
    fun `a marker off the edge is not drawn`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = 900f, y = 90f) { Text("GONE") }
            }
        }

        assertNull(ui.drawn("GONE"))
    }

    @Test
    fun `a marker that clamps is held inside the layer with an arrow beside it`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = 900f, y = 150f, offScreen = OffScreen.ClampToEdge()) {
                    Text("WAY", Modifier.testTag("way"))
                }
            }
        }

        val bounds = ui.node("way").boundsInRoot
        assertTrue(bounds.right <= 400f, "it hangs off the right edge: $bounds")
        ui.assertArrowsOnScreen()
    }

    @Test
    fun `the arrow at the left edge is on the layer too`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = -500f, y = 150f, offScreen = OffScreen.ClampToEdge()) {
                    Text("WAY", Modifier.testTag("way"))
                }
            }
        }

        val bounds = ui.node("way").boundsInRoot
        assertTrue(bounds.left >= 0f, "it hangs off the left edge: $bounds")
        ui.assertArrowsOnScreen()
    }

    @Test
    fun `the arrow for something behind the camera is on the layer too`() {
        behind = true
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = 210f, y = 150f, z = 12f, offScreen = OffScreen.ClampToEdge()) {
                    Text("WAY", Modifier.testTag("way"))
                }
            }
        }

        ui.assertArrowsOnScreen()
    }

    @Test
    fun `a marker half way through its fade is drawn half there`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "one", x = 120f, y = 90f, z = 30f, fadeDistance = 20f..40f) { Text("FAR") }
            }
        }

        val call = assertNotNull(ui.drawn("FAR"), "it should still be on the screen")
        assertNear(0.5f, call.alpha, "the fade is the wrong way round or the wrong width")
    }

    @Test
    fun `a layer with a limit drops the furthest markers`() {
        val ui = open {
            WorldMarkerLayer(projection = camera, maxVisible = 1) {
                marker(key = "near", x = 60f, y = 40f, z = 5f) { Text("NEAR") }
                marker(key = "far", x = 260f, y = 40f, z = 50f) { Text("FAR") }
            }
        }

        assertNotNull(ui.drawn("NEAR"))
        assertNull(ui.drawn("FAR"), "the furthest one should have given up its place")
    }

    @Test
    fun `the nearer of two markers is drawn over the further one`() {
        val ui = open {
            WorldMarkerLayer(projection = camera) {
                marker(key = "far", x = 100f, y = 80f, z = 40f) { Text("FAR") }
                marker(key = "near", x = 108f, y = 84f, z = 4f) { Text("NEAR") }
            }
        }

        val texts = ui.frame().filterIsInstance<DrawCall.Text>().map { it.text }
        assertTrue(texts.indexOf("NEAR") > texts.indexOf("FAR"), "the near one is behind: $texts")
    }

    private companion object {
        val Screen = Rect.of(0f, 0f, 400f, 300f)
    }
}
