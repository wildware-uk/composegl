package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The layer that puts interface at points in the game's world.
 *
 * Driven the way a game drives it: a camera the test moves, markers that follow things that move,
 * and real pointer and key events through a composed screen. The interesting ones are the four the
 * issue asks for — off-screen clamping with an arrow, distance fade, decluttering, and drawing in
 * depth order — plus the promise that moving a marker recomposes nothing inside it.
 */
class WorldMarkersTest {

    /**
     * A camera that can be moved from a test.
     *
     * The world is already the screen, plus wherever the camera has been panned to, and a point's
     * `z` is how far away it is — which is what a marker's fade, size and place in the pile read.
     * [behind] makes the whole world be behind the lens, the way turning round does.
     */
    private class TestCamera {
        var panX = 0f
        var panY = 0f
        var behind = false

        /** The size the last projection was handed, which is the layer's own box. */
        var view = Size(0f, 0f)
            private set

        val projection = WorldProjection { point, size, onto ->
            view = size
            onto.set(point.x + panX, point.y + panY, if (behind) -point.z else point.z)
            true
        }
    }

    private val camera = TestCamera()

    /** Kept here rather than let [uiTest] make one, so a test can read back what was drawn. */
    private val headless = HeadlessBackend(Screen)

    /** A 400 by 300 screen, which is small enough that a test can say what is off the edge of it. */
    private fun screen(content: @Composable () -> Unit) =
        uiTest(size = Size(400f, 300f), backend = headless, content = content)

    /** One frame, with whatever the last one recorded thrown away first. */
    private fun UiTest.frame(): List<DrawCall> {
        headless.canvas.clear(Screen)
        render()
        return headless.canvas.calls
    }

    /** The run of text [text], as it was drawn this frame, or null when it was not drawn at all. */
    private fun UiTest.textCall(text: String): DrawCall.Text? =
        frame().filterIsInstance<DrawCall.Text>().firstOrNull { it.text == text }

    private fun UiTest.fans(): List<DrawCall.Fan> = frame().filterIsInstance<DrawCall.Fan>()

    /**
     * That an arrow was drawn and that every corner of it is on the layer.
     *
     * The second half is the part worth having: an arrow is only any use if a player can see it,
     * and a triangle drawn nineteen pixels past the right edge is recorded by the backend exactly
     * like one on screen. The half pixel is the float slack the rest of these tests allow — the
     * arrow is meant to reach the inset line and no further.
     */
    private fun UiTest.assertArrowsOnScreen(): List<DrawCall.Fan> {
        val fans = fans()
        assertTrue(fans.isNotEmpty(), "nothing was drawn to say which way to look")
        for (fan in fans) {
            for (point in fan.points) {
                assertTrue(
                    point.x >= -0.5f && point.x <= 400.5f && point.y >= -0.5f && point.y <= 300.5f,
                    "an arrow corner landed off the 400 by 300 layer: $point",
                )
            }
        }
        return fans
    }

    /** Every run of text drawn this frame, in the order it was drawn. */
    private fun UiTest.drawnTexts(): List<String> =
        frame().filterIsInstance<DrawCall.Text>().map { it.text }

    // --- where a marker goes ------------------------------------------------------------------

    @Test
    fun `a marker is placed where the camera says its point is`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 100f, y = 80f) {
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            val bounds = ui.node("plate").boundsInRoot
            assertEquals(100f, bounds.centre.x, 0.5f, "not on its point across: $bounds")
            assertEquals(80f, bounds.centre.y, 0.5f, "not on its point down: $bounds")
        }
    }

    @Test
    fun `the anchor says which part of the marker sits on the point`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 100f, y = 80f, anchor = Alignment.BottomCentre) {
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            val bounds = ui.node("plate").boundsInRoot
            assertEquals(80f, bounds.bottom, 0.5f, "a label on a post stands on its point: $bounds")
            assertEquals(100f, bounds.centre.x, 0.5f)
        }
    }

    @Test
    fun `a marker that moves is placed again without recomposing what is inside it`() {
        var compositions = 0
        var enemyX = 100f
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "enemy", position = { it.set(enemyX, 80f) }) {
                    compositions++
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            val settled = compositions
            val before = ui.node("plate").boundsInRoot.centre.x

            enemyX = 260f
            ui.settle()

            assertEquals(260f, ui.node("plate").boundsInRoot.centre.x, 0.5f, "it stayed at $before")
            assertEquals(settled, compositions, "the nameplate was built again just to be moved")
        }
    }

    @Test
    fun `moving the camera moves every marker with it`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 100f, y = 80f) { Box(Modifier.size(20f).testTag("plate")) }
            }
        }.use { ui ->
            camera.panX = -40f
            ui.settle()

            assertEquals(60f, ui.node("plate").boundsInRoot.centre.x, 0.5f)
        }
    }

    // --- off the edge -------------------------------------------------------------------------

    @Test
    fun `a marker off the screen is not drawn`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 900f, y = 80f) { Text("GONE", Modifier.testTag("plate")) }
            }
        }.use { ui ->
            assertEquals("", ui.text("plate"), "a name over something nobody can see is noise")
            assertNull(ui.textCall("GONE"))
        }
    }

    @Test
    fun `a marker that clamps is held inside the layer with the whole of it on screen`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = 900f,
                    y = 150f,
                    offScreen = OffScreen.ClampToEdge(arrow = true, inset = 8f),
                ) {
                    Box(Modifier.size(40f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            val bounds = ui.node("way").boundsInRoot
            assertTrue(bounds.right <= 392f, "it hangs off the right edge: $bounds")
            assertTrue(bounds.left >= 8f, "and it should not have been pushed off the other side")
            assertEquals(150f, bounds.centre.y, 0.5f, "it should only have moved sideways")
        }
    }

    @Test
    fun `a wide marker is held inside while its point is still on the screen`() {
        var pointX = 398f
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    position = { it.set(pointX, 150f, 1f) },
                    offScreen = OffScreen.ClampToEdge(arrow = true),
                ) {
                    Box(Modifier.size(100f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            val before = ui.node("way").boundsInRoot
            assertTrue(before.right <= 400.5f, "its point is on screen but half of it is not: $before")

            // And across the edge, which is where the old point-only test jumped it half a box
            // sideways. Four pixels of point is four pixels of marker.
            pointX = 402f
            ui.settle()
            val after = ui.node("way").boundsInRoot
            assertTrue(
                abs(after.left - before.left) <= 4.5f,
                "it jumped when its point crossed the edge: $before then $after",
            )
        }
    }

    @Test
    fun `the arrow beside a clamped marker points at the thing`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = 900f,
                    y = 150f,
                    offScreen = OffScreen.ClampToEdge(arrow = true),
                ) {
                    Box(Modifier.size(40f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            val arrow = ui.assertArrowsOnScreen().first()
            val marker = ui.node("way").boundsInRoot
            // The tip is the point furthest along, which for a thing off to the right is rightmost.
            val tip = arrow.points.maxByOrNull { it.x }!!
            assertTrue(tip.x > marker.right, "the arrow should be past the marker, not under it: $tip")
            val spread = arrow.points.maxOf { it.y } - arrow.points.minOf { it.y }
            assertTrue(spread < 20f, "an arrow pointing right is not tall: $spread")
        }
    }

    @Test
    fun `the arrow beside a marker held at the left edge is on screen as well`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = -500f,
                    y = 150f,
                    offScreen = OffScreen.ClampToEdge(arrow = true),
                ) {
                    Box(Modifier.size(40f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            val arrow = ui.assertArrowsOnScreen().first()
            val marker = ui.node("way").boundsInRoot
            val tip = arrow.points.minByOrNull { it.x }!!
            assertTrue(tip.x < marker.left, "the arrow should be past the marker: $tip")
        }
    }

    @Test
    fun `the arrow sits beside the middle of a tall marker rather than its anchor`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = 900f,
                    y = 150f,
                    offScreen = OffScreen.ClampToEdge(arrow = true),
                    // The anchor the documentation recommends for a nameplate: it hangs above its
                    // point, so the bottom edge is what the world point holds.
                    anchor = Alignment.BottomCentre,
                ) {
                    Box(Modifier.size(20f, 100f).testTag("way"))
                }
            }
        }.use { ui ->
            val arrow = ui.assertArrowsOnScreen().first()
            val marker = ui.node("way").boundsInRoot
            val middle = arrow.points.map { it.y }.average().toFloat()
            assertEquals(
                marker.centre.y,
                middle,
                4f,
                "the arrow should be level with the middle of the box rather than its anchor: $marker",
            )
            assertTrue(
                arrow.points.minOf { it.x } > marker.right,
                "and just clear of the box rather than half the box's height away: $marker",
            )
        }
    }

    @Test
    fun `a marker with no arrow asked for draws none`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = 900f,
                    y = 150f,
                    offScreen = OffScreen.ClampToEdge(arrow = false),
                ) {
                    Box(Modifier.size(40f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            assertTrue(ui.fans().isEmpty(), "an arrow was drawn for a marker that did not ask")
        }
    }

    @Test
    fun `something behind the camera is held at an edge rather than in the middle`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "objective",
                    x = 210f,
                    y = 150f,
                    z = 12f,
                    offScreen = OffScreen.ClampToEdge(arrow = true),
                ) {
                    Box(Modifier.size(40f, 20f).testTag("way"))
                }
            }
        }.use { ui ->
            camera.behind = true
            ui.settle()

            val bounds = ui.node("way").boundsInRoot
            assertTrue(
                bounds.left <= 40f || bounds.right >= 360f || bounds.top <= 40f || bounds.bottom >= 260f,
                "a thing behind the player was drawn in the middle of their screen: $bounds",
            )
            // And the arrow saying which way to turn is on the screen where it can be seen.
            ui.assertArrowsOnScreen()
        }
    }

    @Test
    fun `a marker set to show stays where it landed`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 430f, y = 150f, offScreen = OffScreen.Show) {
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            assertEquals(430f, ui.node("plate").boundsInRoot.centre.x, 0.5f, "it was pulled back on screen")
        }
    }

    @Test
    fun `a camera that says no draws nothing at all`() {
        val blind = WorldProjection { _, _, _ -> false }
        screen {
            WorldMarkerLayer(projection = blind) {
                marker(key = "one", x = 100f, y = 80f, offScreen = OffScreen.ClampToEdge()) {
                    Text("GONE", Modifier.testTag("plate"))
                }
            }
        }.use { ui ->
            assertEquals("", ui.text("plate"))
            assertTrue(ui.fans().isEmpty(), "not even an arrow, since there is no direction to give")
        }
    }

    // --- distance -----------------------------------------------------------------------------

    @Test
    fun `a marker further away is fainter`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "near", x = 100f, y = 80f, z = 10f, fadeDistance = 20f..40f) {
                    Text("NEAR", Modifier.testTag("near"))
                }
                marker(key = "far", x = 260f, y = 80f, z = 30f, fadeDistance = 20f..40f) {
                    Text("FAR", Modifier.testTag("far"))
                }
            }
        }.use { ui ->
            val near = checkNotNull(ui.textCall("NEAR"))
            val far = checkNotNull(ui.textCall("FAR"))
            assertEquals(1f, near.alpha, 0.01f, "something inside the near end of the fade is solid")
            assertEquals(0.5f, far.alpha, 0.02f, "half way through the fade it is half there")
        }
    }

    @Test
    fun `a marker past the far end of its fade is gone`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "far", x = 100f, y = 80f, z = 60f, fadeDistance = 20f..40f) {
                    Text("FAR", Modifier.testTag("far"))
                }
            }
        }.use { ui ->
            assertNull(ui.textCall("FAR"), "it should have faded away entirely")
        }
    }

    @Test
    fun `a marker further away is drawn smaller`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "far",
                    x = 200f,
                    y = 150f,
                    z = 40f,
                    scaleDistance = 0f..40f,
                    farScale = 0.5f,
                ) {
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            val bounds = ui.node("plate").boundsInRoot
            assertEquals(20f, bounds.width, 0.5f, "at the far end it is drawn at half size: $bounds")
        }
    }

    @Test
    fun `something far behind the camera fades like something far in front`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "behind",
                    x = 200f,
                    y = 150f,
                    z = -30f,
                    fadeDistance = 20f..40f,
                    offScreen = OffScreen.Show,
                ) {
                    Text("BEHIND", Modifier.testTag("behind"))
                }
            }
        }.use { ui ->
            val call = checkNotNull(ui.textCall("BEHIND"))
            assertEquals(
                0.5f,
                call.alpha,
                0.02f,
                "thirty metres behind the player was drawn as solidly as one at arm's length",
            )
        }
    }

    @Test
    fun `something far behind the camera is drawn smaller as well`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(
                    key = "behind",
                    x = 200f,
                    y = 150f,
                    z = -40f,
                    scaleDistance = 0f..40f,
                    farScale = 0.5f,
                    offScreen = OffScreen.Show,
                ) {
                    Box(Modifier.size(40f, 20f).testTag("plate"))
                }
            }
        }.use { ui ->
            val bounds = ui.node("plate").boundsInRoot
            assertEquals(20f, bounds.width, 0.5f, "it should be half size at the far end: $bounds")
        }
    }

    // --- which markers are worth showing --------------------------------------------------------

    @Test
    fun `a layer with a limit keeps the nearest markers`() {
        screen {
            WorldMarkerLayer(projection = camera.projection, maxVisible = 2) {
                marker(key = "a", x = 60f, y = 40f, z = 5f) { Text("A", Modifier.testTag("a")) }
                marker(key = "b", x = 160f, y = 40f, z = 50f) { Text("B", Modifier.testTag("b")) }
                marker(key = "c", x = 260f, y = 40f, z = 15f) { Text("C", Modifier.testTag("c")) }
            }
        }.use { ui ->
            assertNotNull(ui.textCall("A"))
            assertNotNull(ui.textCall("C"))
            assertNull(ui.textCall("B"), "the furthest one should have given up its place")
        }
    }

    @Test
    fun `priority beats distance when there is not room for everything`() {
        screen {
            WorldMarkerLayer(projection = camera.projection, maxVisible = 1) {
                marker(key = "near", x = 60f, y = 40f, z = 5f) { Text("NEAR", Modifier.testTag("near")) }
                marker(key = "quest", x = 260f, y = 40f, z = 90f, priority = 1f) {
                    Text("QUEST", Modifier.testTag("quest"))
                }
            }
        }.use { ui ->
            assertNotNull(ui.textCall("QUEST"), "the objective is what the player came for")
            assertNull(ui.textCall("NEAR"))
        }
    }

    @Test
    fun `a marker behind the camera does not take the place of one in front`() {
        screen {
            WorldMarkerLayer(projection = camera.projection, maxVisible = 1) {
                marker(key = "front", x = 100f, y = 80f, z = 2f) { Text("FRONT", Modifier.testTag("front")) }
                marker(key = "behind", x = 200f, y = 150f, z = -100f, offScreen = OffScreen.Show) {
                    Text("BEHIND", Modifier.testTag("behind"))
                }
            }
        }.use { ui ->
            assertNotNull(ui.textCall("FRONT"), "the enemy in front lost its place to a waypoint behind the player")
            assertNull(ui.textCall("BEHIND"), "a hundred metres behind is a hundred metres away")
        }
    }

    @Test
    fun `decluttering drops the marker that would sit on top of another`() {
        screen {
            WorldMarkerLayer(projection = camera.projection, declutter = true) {
                marker(key = "near", x = 100f, y = 80f, z = 5f) { Text("NEAR", Modifier.testTag("near")) }
                marker(key = "over", x = 110f, y = 84f, z = 25f) { Text("OVER", Modifier.testTag("over")) }
                marker(key = "clear", x = 300f, y = 220f, z = 25f) { Text("CLEAR", Modifier.testTag("clear")) }
            }
        }.use { ui ->
            assertNotNull(ui.textCall("NEAR"), "the nearer of the two overlapping ones stays")
            assertNull(ui.textCall("OVER"), "the one underneath should have given way")
            assertNotNull(ui.textCall("CLEAR"), "and one nowhere near it should be untouched")
        }
    }

    @Test
    fun `without decluttering every marker is shown`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "near", x = 100f, y = 80f, z = 5f) { Text("NEAR", Modifier.testTag("near")) }
                marker(key = "over", x = 110f, y = 84f, z = 25f) { Text("OVER", Modifier.testTag("over")) }
            }
        }.use { ui ->
            assertNotNull(ui.textCall("OVER"), "a nameplate is expected over every enemy")
        }
    }

    @Test
    fun `the nearer marker is drawn over the further one`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "far", x = 100f, y = 80f, z = 40f) { Text("FAR", Modifier.testTag("far")) }
                marker(key = "near", x = 110f, y = 84f, z = 4f) { Text("NEAR", Modifier.testTag("near")) }
            }
        }.use { ui ->
            val texts = ui.drawnTexts()
            assertTrue(
                texts.indexOf("NEAR") > texts.indexOf("FAR"),
                "the near one is behind the far one: $texts",
            )
        }
    }

    @Test
    fun `a marker behind the camera is drawn under one in front`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "front", x = 100f, y = 80f, z = 1f) { Text("FRONT", Modifier.testTag("front")) }
                marker(key = "behind", x = 110f, y = 84f, z = -100f, offScreen = OffScreen.Show) {
                    Text("BEHIND", Modifier.testTag("behind"))
                }
            }
        }.use { ui ->
            val texts = ui.drawnTexts()
            assertTrue(
                texts.indexOf("FRONT") > texts.indexOf("BEHIND"),
                "the thing behind the player covered the thing in front of them: $texts",
            )
        }
    }

    @Test
    fun `two markers that cross swap over`() {
        var nearDepth = 40f
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", position = { it.set(100f, 80f, nearDepth) }) { Text("ONE") }
                marker(key = "two", position = { it.set(110f, 84f, 20f) }) { Text("TWO") }
            }
        }.use { ui ->
            val before = ui.drawnTexts()
            assertTrue(before.indexOf("TWO") > before.indexOf("ONE"))

            nearDepth = 5f
            ui.settle()
            val after = ui.drawnTexts()

            assertTrue(after.indexOf("ONE") > after.indexOf("TWO"), "they should have swapped: $after")
        }
    }

    // --- markers are real interface -------------------------------------------------------------

    @Test
    fun `a marker can be clicked`() {
        var clicks = 0
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 200f, y = 150f) {
                    Box(Modifier.size(40f).clickable { clicks++ }.testTag("plate"))
                }
            }
        }.use { ui ->
            ui.click("plate")

            assertEquals(1, clicks, "a marker is a real node and a player can point at it")
        }
    }

    @Test
    fun `a marker that is not shown cannot be clicked`() {
        var clicks = 0
        screen {
            WorldMarkerLayer(projection = camera.projection, maxVisible = 0) {
                marker(key = "one", x = 200f, y = 150f) {
                    Box(Modifier.size(40f).clickable { clicks++ }.testTag("plate"))
                }
            }
        }.use { ui ->
            ui.click(Offset(200f, 150f))

            assertEquals(0, clicks, "something drawn at nothing must not take a click either")
        }
    }

    @Test
    fun `focus reaches a marker and skips one that is not shown`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "shown", x = 100f, y = 150f) { Box(Modifier.size(40f).focusable().testTag("shown")) }
                marker(key = "gone", x = 900f, y = 150f) { Box(Modifier.size(40f).focusable().testTag("gone")) }
            }
        }.use { ui ->
            ui.key(Key.Tab)
            ui.assertFocused("shown")

            ui.key(Key.Tab)
            ui.assertFocused("shown")
        }
    }

    @Test
    fun `a pad reaches a marker and presses it`() {
        var presses = 0
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 200f, y = 150f) {
                    Box(Modifier.size(40f).focusable().clickable { presses++ }.testTag("plate"))
                }
            }
        }.use { ui ->
            ui.pad(GamepadButton.DpadDown)
            ui.assertFocused("plate")

            ui.pad(GamepadButton.South)

            assertEquals(1, presses, "a marker is reached by a pad the way anything else is")
        }
    }

    @Test
    fun `a pad skips a marker that is not shown`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "shown", x = 100f, y = 150f) { Box(Modifier.size(40f).focusable().testTag("shown")) }
                marker(key = "gone", x = 900f, y = 150f) { Box(Modifier.size(40f).focusable().testTag("gone")) }
            }
        }.use { ui ->
            ui.pad(GamepadButton.DpadDown)
            ui.assertFocused("shown")

            // Nowhere to go: the other marker is off the edge and so is not there to be reached.
            ui.pad(GamepadButton.DpadRight)
            ui.assertFocused("shown")
        }
    }

    @Test
    fun `a marker keeps its state while the list around it changes`() {
        val keys = mutableStateListOf("one")
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                keys.forEachIndexed { index, name ->
                    marker(key = name, x = 60f + index * 80f, y = 150f) { Counter(name) }
                }
            }
        }.use { ui ->
            ui.click("count-one")
            ui.click("count-one")
            assertEquals("one 2", ui.text("count-one"))

            keys.add(0, "two")
            ui.settle()

            assertEquals("one 2", ui.text("count-one"), "the marker was rebuilt when its neighbour arrived")
        }
    }

    /** A marker with something to remember, so a test can see whether its node was kept. */
    @Composable
    private fun Counter(name: String) {
        var count by remember { mutableIntStateOf(0) }
        Text("$name $count", Modifier.size(70f, 20f).clickable { count++ }.testTag("count-$name"))
    }

    @Test
    fun `two markers sharing a key are refused`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            screen {
                WorldMarkerLayer(projection = camera.projection) {
                    marker(key = "same", x = 60f, y = 40f) { Text("ONE") }
                    marker(key = "same", x = 260f, y = 40f) { Text("TWO") }
                }
            }.close()
        }

        assertTrue(failure.message!!.contains("same"), "it should say which key: ${failure.message}")
    }

    @Test
    fun `a list with two of one key is refused again when it is handed back`() {
        val markers = WorldMarkers()
        val clashing = listOf(declaration("same"), declaration("same"))

        assertThrows(IllegalArgumentException::class.java) { markers.sync(clashing, Int.MAX_VALUE, false) }
        // The same list object a second time, which is what the next composition hands over. A
        // layer that had already remembered it would take its early return and quietly carry on
        // with one slot standing in for two markers.
        assertThrows(IllegalArgumentException::class.java) { markers.sync(clashing, Int.MAX_VALUE, false) }
        assertTrue(markers.slots.isEmpty(), "a refused list must not have left slots behind")
    }

    /** The plainest marker there is, for the tests that talk to [WorldMarkers] rather than a screen. */
    private fun declaration(key: Any) = MarkerDeclaration(
        key = key,
        anchor = null,
        x = 0f,
        y = 0f,
        z = 0f,
        offScreen = OffScreen.Hide,
        fadeNear = Float.NaN,
        fadeFar = Float.NaN,
        scaleNear = Float.NaN,
        scaleFar = Float.NaN,
        farScale = 1f,
        priority = 0f,
        alignment = Alignment.Centre,
        content = {},
    )

    // --- what it costs ---------------------------------------------------------------------------

    @Test
    fun `a still camera redraws nothing`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {
                marker(key = "one", x = 100f, y = 80f) { Box(Modifier.size(20f).testTag("plate")) }
            }
        }.use { ui ->
            repeat(20) { assertFalse(ui.render(), "frame $it redrew a screen where nothing had moved") }
        }
    }

    @Test
    fun `a layer with nothing on it costs nothing`() {
        screen {
            WorldMarkerLayer(projection = camera.projection) {}
        }.use { ui ->
            repeat(20) { assertFalse(ui.render(), "frame $it redrew an empty layer") }
        }
    }

    // --- split screen ------------------------------------------------------------------------------

    @Test
    fun `each layer projects into its own box rather than the window`() {
        val left = TestCamera()
        val right = TestCamera()
        screen {
            Row(Modifier.size(400f, 300f)) {
                Box(Modifier.weight(1f)) {
                    WorldMarkerLayer(projection = left.projection) {
                        marker(key = "l", x = 10f, y = 10f) { Box(Modifier.size(10f).testTag("l")) }
                    }
                }
                Box(Modifier.weight(3f)) {
                    WorldMarkerLayer(projection = right.projection) {
                        marker(key = "r", x = 10f, y = 10f) { Box(Modifier.size(10f).testTag("r")) }
                    }
                }
            }
        }.use { ui ->
            assertEquals(100f, left.view.width, 0.5f, "a player's camera is handed their own quarter")
            assertEquals(300f, right.view.width, 0.5f)
            // And each marker lands inside its own part of the window, not at the window's corner.
            assertEquals(110f, ui.node("r").boundsInRoot.centre.x, 0.5f)
        }
    }

    private companion object {
        /** Small enough that a test can say what is off the edge of it. */
        val Screen = Rect.of(0f, 0f, 400f, 300f)
    }
}
