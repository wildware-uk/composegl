package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the recording canvas writes down for a box whose corners differ, and for one whose do not. */
class RecordingCanvasCornersTest {

    private val canvas = RecordingCanvas()
    private val tab = Rect.of(0f, 0f, 60f, 24f)

    @Test
    fun `corners that differ are written down all four of them`() {
        canvas.rect(tab, Colour.White, Corners.top(8f))
        canvas.border(tab, Colour.Black, 2f, Corners.top(8f))
        canvas.shadow(tab, Colour.Black, 6f, Corners.top(8f))

        assertTrue(canvas.roundsCornersSeparately)
        assertEquals(
            DrawCall.CorneredRectangle(tab, Colour.White, Corners.top(8f), canvas.calls[0].clip, 1f),
            canvas.calls[0],
        )
        assertEquals(Corners.top(8f), (canvas.calls[1] as DrawCall.CorneredBorder).corners)
        assertEquals(2f, (canvas.calls[1] as DrawCall.CorneredBorder).width)
        assertEquals(Corners.top(8f), (canvas.calls[2] as DrawCall.CorneredShadow).corners)
        assertEquals(6f, (canvas.calls[2] as DrawCall.CorneredShadow).spread)
    }

    @Test
    fun `corners that agree are recorded exactly as one radius always was`() {
        // So a test written before four radii existed, asking for a Rectangle with a corner of 6,
        // keeps finding one.
        canvas.rect(tab, Colour.White, Corners.all(6f))
        canvas.border(tab, Colour.White, 1f, Corners.all(6f))
        canvas.shadow(tab, Colour.White, 4f, Corners.None)

        assertEquals(listOf("Rectangle", "Border", "Shadow"), canvas.calls.map { it::class.simpleName })
        assertEquals(6f, canvas.only<DrawCall.Rectangle>().single().corner)
        assertEquals(6f, canvas.only<DrawCall.Border>().single().corner)
        assertEquals(0f, canvas.only<DrawCall.Shadow>().single().corner)
    }

    @Test
    fun `the clip and the opacity in force are kept on a cornered box too`() {
        canvas.pushClip(Rect.of(0f, 0f, 30f, 30f))
        canvas.pushAlpha(0.5f)
        canvas.rect(tab, Colour.White, Corners.left(4f))
        canvas.popAlpha()
        canvas.popClip()

        val box = canvas.only<DrawCall.CorneredRectangle>().single()
        assertEquals(Rect.of(0f, 0f, 30f, 30f), box.clip)
        assertEquals(0.5f, box.alpha)
        canvas.assertBalanced()
    }
}
