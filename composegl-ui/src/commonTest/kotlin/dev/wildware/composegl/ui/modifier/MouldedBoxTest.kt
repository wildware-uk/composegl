package dev.wildware.composegl.ui.modifier

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three modifiers a chunky game button is made of — an outline outside the box, shade falling
 * inwards from an edge, and a shine across the top — in composed UI.
 *
 * What each one comes out as in pixels is the GPU tests' question. Which box was asked for, in
 * which colours and in what order, is this file's.
 */
class MouldedBoxTest {

    private val green = Colour.rgb(0x4CD964)
    private val ink = Colour.rgb(0x2B1B10)

    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    @Test
    fun `an outside border is drawn round the node it was asked for`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(green, corner = 12f).borderOutside(ink, width = 3f, corner = 12f))
    }.run {
        val drawn = frame().only<DrawCall.OutsideBorder>()

        assertEquals(1, drawn.size)
        assertEquals(3f, drawn.single().width)
        assertEquals(ink, drawn.single().colour)
        assertEquals(Corners.all(12f), drawn.single().corners)
        assertEquals(node("button").bounds, drawn.single().rect, "the node's own box: the line lies outside it")
    }

    @Test
    fun `shade falls inwards from the edge the offset points away from`() = uiTest {
        Box(
            Modifier.testTag("socket").size(120f, 40f)
                .background(green, corner = 12f)
                .innerShade(ink, depth = 6f, corner = 12f, offset = Offset(0f, -4f)),
        )
    }.run {
        val shade = frame().only<DrawCall.InnerShade>().single()

        assertEquals(6f, shade.depth)
        assertEquals(Offset(0f, -4f), shade.offset)
        assertEquals(node("socket").bounds, shade.rect)
    }

    @Test
    fun `a bevel is a light shade from below and a dark one from above`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(green, corner = 12f).bevel(depth = 5f, corner = 12f))
    }.run {
        val shades = frame().only<DrawCall.InnerShade>()

        assertEquals(2, shades.size, "one for the light edge and one for the dark")
        assertEquals(Offset(0f, 5f), shades[0].offset, "the light gathers along the top")
        assertEquals(Offset(0f, -5f), shades[1].offset, "and the dark along the bottom")
        assertTrue(shades[0].colour.red > shades[1].colour.red, "light first, then dark: ${shades.map { it.colour }}")
    }

    @Test
    fun `a gloss covers the top of the box and fades downwards`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(green, corner = 12f).gloss(fraction = 0.5f, corner = 12f))
    }.run {
        val shine = frame().only<DrawCall.CorneredGradientRectangle>().single()
        val box = node("button").bounds

        assertEquals(box.left, shine.rect.left)
        assertEquals(box.top, shine.rect.top)
        assertEquals(box.width, shine.rect.width)
        assertEquals(box.height / 2f, shine.rect.height, "half the box, as asked")
        assertEquals(Colour.Transparent, shine.brush.last, "it fades out rather than stopping")
        assertEquals(0f, shine.corners.bottomLeft, "square along the bottom, where nothing is left to see")
        // The box's radius where it fits: a shine 20 tall cannot curve by 12, so it curves by 10.
        assertEquals(10f, shine.corners.topLeft, "and round along the top, following the box")
    }

    @Test
    fun `a gloss inset from the sides keeps its corners inside the curve`() = uiTest {
        Box(Modifier.testTag("button").size(120f, 40f).background(green, corner = 12f).gloss(fraction = 0.4f, corner = 12f, inset = 4f))
    }.run {
        val shine = frame().only<DrawCall.CorneredGradientRectangle>().single()
        val box = node("button").bounds

        assertEquals(box.left + 4f, shine.rect.left)
        assertEquals(box.right - 4f, shine.rect.right)
        assertEquals(8f, shine.corners.topRight, "the radius comes in with the shine")
        assertTrue(shine.corners.topRight <= shine.rect.height / 2f, "and never more than the shine can curve by")
    }

    @Test
    fun `the parts of a button are drawn in the order they were written`() = uiTest {
        Box(
            Modifier.testTag("button").size(120f, 40f)
                .background(green, corner = 12f)
                .bevel(depth = 5f, corner = 12f)
                .gloss(corner = 12f)
                .borderOutside(ink, width = 3f, corner = 12f),
        )
    }.run {
        val kinds = frame().calls.mapNotNull { call ->
            when (call) {
                is DrawCall.Rectangle -> "fill"
                is DrawCall.InnerShade -> "shade"
                is DrawCall.CorneredGradientRectangle -> "gloss"
                is DrawCall.OutsideBorder -> "outline"
                else -> null
            }
        }

        assertEquals(listOf("fill", "shade", "shade", "gloss", "outline"), kinds)
    }
}
