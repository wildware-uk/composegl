package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One light, and everything a box does about it: the lifted edge, the shaded one, the shine and
 * the shadow.
 *
 * The point of the modifier is that they agree, so these tests turn the light and check that all
 * of them turned with it — which is exactly what writing the four by hand gets wrong.
 */
class MouldedLightTest {

    private val face = Colour.rgb(0x4FAF28)

    /** The shine, whichever way the recorder wrote it down: four equal corners are a plain box. */
    private fun RecordingCanvas.shine(): Brush.Ramp {
        val brushes = only<DrawCall.GradientRectangle>().map { it.brush } +
            only<DrawCall.CorneredGradientRectangle>().map { it.brush }
        return brushes.filterIsInstance<Brush.Ramp>().single()
    }

    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    @Test
    fun `a box lit from above lifts its top edge and shades its bottom`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(face, corner = 12f).moulded(corner = 12f))
    }.run {
        val shades = frame().only<DrawCall.InnerShade>()

        assertEquals(2, shades.size)
        val lift = shades.first { it.colour.red > 200 }
        val shade = shades.first { it.colour.red < 60 }
        assertTrue(lift.offset.y > 0f, "the lift gathers along the top, which an offset downwards does")
        assertTrue(shade.offset.y < 0f, "and the dark along the bottom")
        assertEquals(0f, abs(lift.offset.x), 0.001f, "nothing sideways: the light is straight above")
    }

    @Test
    fun `turning the light turns the lift the shade and the shine together`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(face, corner = 12f).moulded(corner = 12f, light = 0f))
    }.run {
        val drawn = frame()
        val shades = drawn.only<DrawCall.InnerShade>()
        val shine = drawn.shine()

        val lift = shades.first { it.colour.red > 200 }
        assertTrue(lift.offset.x > 0f, "lit from the left, so the left edge is the lifted one")
        assertEquals(0f, abs(lift.offset.y), 0.001f, "and nothing up or down")
        assertEquals(0f, shine.degrees, "the shine runs the way the light falls")
    }

    @Test
    fun `the shine stops where it was told to and holds the ends`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(face, corner = 12f).moulded(corner = 12f, shine = 0.5f))
    }.run {
        val shine = frame().shine()

        assertEquals(0.5f, shine.stops[2].at, "it is out by half way down")
        assertEquals(Colour.Transparent, shine.stops.last().colour, "and stays out")
        assertTrue(shine.stops.first().colour.alpha > 0, "and starts lit")
    }

    @Test
    fun `everything is a fraction of the box so a checkbox is dressed like a button`() = uiTest {
        Box(Modifier.testTag("small").size(24f, 24f).background(face, corner = 6f).moulded(corner = 6f, depth = 0.25f))
    }.run {
        val shade = frame().only<DrawCall.InnerShade>().first()

        assertEquals(6f, shade.depth, 0.001f, "a quarter of the shorter side, which is 24")
    }

    @Test
    fun `a light that is turned off draws nothing but the outline`() = uiTest {
        Box(
            Modifier.testTag("flat").size(120f, 40f).background(face, corner = 12f)
                .moulded(corner = 12f, strength = 0f, shine = 0f, outline = Colour.Black),
        )
    }.run {
        val drawn = frame()

        assertEquals(0, drawn.only<DrawCall.InnerShade>().size)
        assertEquals(1, drawn.only<DrawCall.OutsideBorder>().size)
    }

    @Test
    fun `a shadow is cast only when one is asked for`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(face, corner = 12f).moulded(corner = 12f, shadow = 0.2f))
    }.run {
        val shadows = frame().only<DrawCall.CorneredShadow>() + frame().only<DrawCall.Shadow>().map { it }

        assertTrue(shadows.isNotEmpty(), "a shadow was asked for and drawn")
    }
}
