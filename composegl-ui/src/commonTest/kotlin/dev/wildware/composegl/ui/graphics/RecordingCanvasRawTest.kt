package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What a recording canvas says about the escape hatch, and what it writes down when one is used.
 *
 * The recording canvas is the one every test draws through, so it is also the one that decides
 * whether a widget's composed fallback is reachable in a test at all. It answers no to
 * [UiCanvas.handsOverRaw] - honestly, because it has no backend object and never runs the block -
 * and that is what a test of the fallback depends on.
 */
class RecordingCanvasRawTest {

    private val viewport = Viewport.oneToOne(Size(100f, 100f))

    @Test
    fun `it says it has nothing to hand over`() {
        val canvas = RecordingCanvas()

        assertFalse(canvas.handsOverRaw, "there is no backend object here, and the block never runs")
        assertFalse(canvas.movesRawOrigin, "so there is no origin to move either")
    }

    @Test
    fun `a widget that asks first takes its fallback here`() {
        val canvas = RecordingCanvas()

        canvas.begin(viewport)
        // The shape a widget is told to use: ask, and draw something composed when the answer is no.
        if (canvas.handsOverRaw) canvas.raw { } else canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        canvas.end()

        assertEquals(0, canvas.only<DrawCall.Raw>().size, "the hatch was not used")
        assertEquals(1, canvas.only<DrawCall.Rectangle>().size, "the fallback was")
    }

    @Test
    fun `the rectangle a raw block was pointed at is written down`() {
        val canvas = RecordingCanvas()
        val node = Rect.of(12f, 34f, 56f, 78f)

        canvas.begin(viewport)
        canvas.raw(node) { }
        canvas.raw { }
        canvas.end()

        val (aimed, plain) = canvas.only<DrawCall.Raw>()
        assertEquals(node, aimed.destination, "a widget pointing the hatch at itself is the assertable half")
        assertNull(plain.destination, "and a plain raw is drawing against the layer's own origin")
    }
}
