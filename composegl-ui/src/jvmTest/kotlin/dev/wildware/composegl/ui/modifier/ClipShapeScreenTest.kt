package dev.wildware.composegl.ui.modifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Modifier.clipShape` on a screen driven through the `uiTest` harness: the shape changing under a
 * player, shapes inside shapes, a portrait with no size, and a still screen that stays still.
 *
 * Every screen here is a 100 square button at 20, 20 with a 100 square portrait over it, so a click
 * the button gets is one the portrait's shape gave away.
 */
class ClipShapeScreenTest {

    private val backend = HeadlessBackend()
    private val canvas get() = backend.canvas

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    private var underClicks by mutableStateOf(0)
    private var portraitClicks by mutableStateOf(0)

    private fun screen(portrait: Modifier, inner: Modifier = Modifier.size(100f)): UiTest =
        uiTest(Size(400f, 400f), backend) {
            Box(Modifier.padding(20f)) {
                Box(Modifier.size(100f).background(blue).clickable { underClicks++ })
                Box(portrait) {
                    Box(inner.background(red).clickable { portraitClicks++ })
                }
            }
        }

    /** One frame drawn from scratch, and what it drew. */
    private fun UiTest.drawn(): List<DrawCall> {
        canvas.clear()
        render()
        return canvas.calls
    }

    @Test
    fun `swapping the circle for a rectangle gives the corners back to the portrait`() {
        var shape: Shape by mutableStateOf(Shapes.Circle)
        uiTest(Size(400f, 400f), backend) {
            Box(Modifier.padding(20f)) {
                Box(Modifier.size(100f).clickable { underClicks++ })
                Box(Modifier.size(100f).clipShape(shape)) {
                    Box(Modifier.size(100f).background(red).clickable { portraitClicks++ })
                }
            }
        }.use { ui ->
            assertEquals(1, ui.drawn().filterIsInstance<DrawCall.Layer>().size, "cut through a picture")
            ui.click(Offset(24f, 24f))
            assertEquals(1, underClicks, "the corner is cut away")

            shape = Shapes.Rectangle
            ui.settle()

            assertTrue(ui.drawn().none { it is DrawCall.Layer }, "a rectangle is a scissor again, with no picture")
            ui.click(Offset(24f, 24f))
            assertEquals(1, portraitClicks, "and the corner is the portrait's")
            assertEquals(1, underClicks)

            shape = Shapes.Diamond
            ui.settle()
            ui.click(Offset(24f, 24f))
            assertEquals(2, underClicks, "a diamond takes it away again")
            val outline = ui.drawn().filterIsInstance<DrawCall.Layer>().single().outline!!
            assertEquals(listOf(70f, 20f, 120f, 70f, 70f, 120f, 20f, 70f), outline, "drawn as the new shape")
        }
    }

    @Test
    fun `a circle inside a diamond is cut by both`() {
        screen(Modifier.size(100f).clipShape(Shapes.Diamond), inner = Modifier.size(100f).clipShape(Shapes.Circle)).use { ui ->
            assertEquals(2, ui.drawn().filterIsInstance<DrawCall.Layer>().size, "a picture inside a picture")

            // Inside the circle, but past the diamond's top-left edge.
            ui.click(Offset(35f, 45f))
            assertEquals(1, underClicks, "the outer shape cut it away")
            assertEquals(0, portraitClicks)

            ui.click(Offset(70f, 70f))
            assertEquals(1, portraitClicks, "inside both")
        }
    }

    @Test
    fun `a portrait with no size draws nothing and takes no clicks`() {
        screen(Modifier.size(0f).clipShape(Shapes.Circle), inner = Modifier.size(0f)).use { ui ->
            val calls = ui.drawn()
            assertTrue(calls.none { it is DrawCall.Layer }, "no picture of nothing")
            val art = calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == red }
            assertTrue(art.clip.isEmpty, "the art is clipped to nothing, got ${art.clip}")
            canvas.assertBalanced()

            ui.click(Offset(20f, 20f))
            assertEquals(1, underClicks)
            assertEquals(0, portraitClicks)
        }
    }

    @Test
    fun `a still screen with shaped clips recomposed with new but equal shapes does not redraw`() {
        var tick by mutableStateOf(0)
        uiTest(Size(400f, 400f), backend) {
            // Read here so this whole block recomposes, building every shape afresh.
            tick.let { }
            Box(
                Modifier.size(100f)
                    .clipShape(Shapes.polygon(0.5f, 0f, 1f, 1f, 0f, 1f))
                    .hitShape(Shapes.roundedRect(12f))
                    .background(red)
                    .testTag("tile"),
            )
        }.use { ui ->
            ui.render()
            assertFalse(ui.render(), "nothing changed")
            val before = ui.node("tile").resolved

            tick++
            ui.settle()
            // A modifier that compared unequal would be taken, and throw the resolution away with
            // it, marking the node dirty and redrawing a screen where nothing moved.
            assertSame(before, ui.node("tile").resolved, "the same shapes built again are the same modifier")
            assertFalse(ui.render())
        }
    }

    @Test
    fun `a shrunk portrait is cut and clicked round where it is drawn`() {
        // Half size about its middle: drawn from 45 to 95, a circle of radius 25 round 70, 70.
        screen(Modifier.size(100f).scale(0.5f).clipShape(Shapes.Circle)).use { ui ->
            assertEquals(2, ui.drawn().filterIsInstance<DrawCall.Layer>().size, "the scale's picture and the cut's")

            ui.click(Offset(48f, 48f))
            assertEquals(1, underClicks, "inside the shrunk box but outside its circle")
            assertEquals(0, portraitClicks)

            ui.click(Offset(70f, 93f))
            assertEquals(1, portraitClicks, "the bottom of the shrunk circle")
        }
    }

    @Test
    fun `a portrait scrolled into view is cut where it scrolled to`() {
        val scroll = ScrollState(initialY = 100f)
        uiTest(Size(400f, 400f), backend) {
            Box(Modifier.padding(20f)) {
                ScrollArea(Modifier.size(100f), scroll, bars = false) {
                    Column {
                        Box(Modifier.size(100f))
                        Box(Modifier.size(100f).clipShape(Shapes.Circle)) {
                            Box(Modifier.size(100f).background(red).clickable { portraitClicks++ })
                        }
                    }
                }
            }
        }.use { ui ->
            ui.settle()
            val outline = ui.drawn().filterIsInstance<DrawCall.Layer>().single().outline!!
            val xs = (outline.indices step 2).map { outline[it] }
            val ys = (outline.indices step 2).map { outline[it + 1] }
            assertEquals(70f, (xs.min() + xs.max()) / 2f, 0.01f, "the circle's middle, across")
            assertEquals(70f, (ys.min() + ys.max()) / 2f, 0.01f, "and down, where the scroll put it")

            // The scroll area covers the button, so the corner is only checked from the portrait's
            // side: cut away, it is not the portrait's.
            ui.click(Offset(24f, 24f))
            assertEquals(0, portraitClicks, "the corner the circle cut away")
            ui.click(Offset(70f, 70f))
            assertEquals(1, portraitClicks)
        }
    }

    @Test
    fun `a clip rounded only along its top cuts the top corners and keeps the bottom ones`() {
        screen(Modifier.size(100f).clip(Corners.top(40f))).use { ui ->
            assertEquals(1, ui.drawn().filterIsInstance<DrawCall.Layer>().size, "rounded, so cut through a picture")

            ui.click(Offset(22f, 22f))
            assertEquals(1, underClicks, "the top-left corner is cut away and goes to the button")
            assertEquals(0, portraitClicks)

            ui.click(Offset(22f, 118f))
            assertEquals(1, portraitClicks, "the bottom-left corner is square and still the portrait's")
            assertEquals(1, underClicks)
        }
    }

    @Test
    fun `the pad still presses a round button whose corners the pointer cannot`() {
        uiTest(Size(400f, 400f), backend) {
            Box(Modifier.padding(20f)) {
                Box(
                    Modifier.size(100f).clipShape(Shapes.Circle).hitShape(Shapes.Circle)
                        .focusable(initial = true).clickable { portraitClicks++ }.testTag("round"),
                )
            }
        }.use { ui ->
            ui.click(Offset(22f, 22f))
            assertEquals(0, portraitClicks, "the corner is not the button to the pointer")

            ui.assertFocused("round")
            ui.pad(GamepadButton.South)
            assertEquals(1, portraitClicks, "but focus does not care what shape it is")
        }
    }
}
