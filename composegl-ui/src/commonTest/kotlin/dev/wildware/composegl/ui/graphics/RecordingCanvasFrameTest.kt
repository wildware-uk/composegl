package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The frame boundary on the canvas every test uses.
 *
 * [UiCanvas] lets `begin` and `end` do nothing, and taking that default made this the most
 * forgiving canvas in the toolkit: a class that ended a frame it never began, or left a clip
 * pushed, passed here and failed on a screen. Now it complains in the same words the canvases that
 * draw complain in, and counts what it has been through, so a test can ask whether an object that
 * owns a canvas actually rendered.
 */
class RecordingCanvasFrameTest {

    private val viewport = Viewport.oneToOne(Size(640f, 360f))

    @Test
    fun `a frame is counted when it closes`() {
        val canvas = RecordingCanvas()
        assertEquals(0, canvas.frames)

        canvas.begin(viewport)
        assertEquals(0, canvas.frames, "an open frame is not a frame yet")
        canvas.end()

        assertEquals(1, canvas.frames)
    }

    @Test
    fun `two begins without an end is the same complaint a real canvas makes`() {
        val canvas = RecordingCanvas()
        canvas.begin(viewport)
        assertFailsWith<IllegalStateException> { canvas.begin(viewport) }
    }

    @Test
    fun `an end without a begin is refused`() {
        assertFailsWith<IllegalStateException> { RecordingCanvas().end() }
    }

    @Test
    fun `a clip left pushed fails the frame it was pushed in`() {
        val canvas = RecordingCanvas()
        canvas.begin(viewport)
        canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))

        assertFailsWith<IllegalStateException> { canvas.end() }
        // Closed anyway, so the next frame can begin — the canvases that draw end theirs first too.
        canvas.begin(viewport)
    }

    @Test
    fun `a frame that failed its balance check is not counted as rendered`() {
        val canvas = RecordingCanvas()
        canvas.begin(viewport)
        canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))

        assertFailsWith<IllegalStateException> { canvas.end() }

        assertEquals(0, canvas.frames, "a frame that threw on the way out did not render")
    }

    /**
     * The shape a test takes after asserting a draw-time refusal: catch it, clear, render again.
     * A [RecordingCanvas.clear] that left the failed frame open would meet the next render with
     * "begin() was called twice", which points at the wrong bug entirely.
     */
    @Test
    fun `clear closes a frame that failed halfway through`() {
        val canvas = RecordingCanvas()
        canvas.begin(viewport)
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)

        // A draw that threw, somewhere inside the frame: begin() ran, end() never did.
        canvas.clear()

        canvas.begin(viewport)
        canvas.end()
        assertEquals(1, canvas.frames, "only the frame that closed counts")
        assertEquals(0, canvas.calls.size, "and clear threw away what the failed frame drew")
    }

    @Test
    fun `a frame clips to the design area rather than to the bounds the canvas was made with`() {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 4000f, 4000f))
        canvas.begin(viewport)
        canvas.rect(Rect.of(0f, 0f, 20f, 20f), Colour.White)
        canvas.end()

        assertEquals(Rect.of(0f, 0f, 640f, 360f), canvas.calls.single().clip)
    }

    /**
     * The reason any of this exists: an object that owns a canvas, a host and a renderer, built and
     * rendered with no GPU anywhere — which is the shape the issue's reporter could not test.
     */
    @Test
    fun `an object that owns a canvas can be rendered headlessly`() {
        val backend = HeadlessBackend()
        val host = UiHost()
        host.setContent { LeafLayout(Modifier.size(40f, 20f).background(Colour.White), name = "block") }
        val renderer = UiRenderer(host, backend.canvas)

        renderer.render(Viewport.oneToOne(Size(1280f, 720f)), nanos = 0L)

        assertEquals(1, backend.canvas.frames, "the app object rendered a frame")
        assertEquals(1, backend.canvas.only<DrawCall.Rectangle>().size)
    }
}
