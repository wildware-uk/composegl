package uk.wildware.composegl.ui.graphics

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.text.TextLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeText(override val text: String) : TextLayout {
    override val size = Size(text.length * 8f, 16f)
    override val lineCount = 1
    override val firstBaseline = 12f
}

private class FakeTexture(override val width: Int = 32, override val height: Int = 32) : TextureHandle

class CanvasStateTest {

    private val screen = Rect.of(0f, 0f, 100f, 100f)

    @Test
    fun `a nested clip is the intersection, not the inner one`() {
        val state = CanvasState(screen)

        state.pushClip(Rect.of(20f, 20f, 100f, 100f))
        state.pushClip(Rect.of(0f, 0f, 50f, 50f))

        assertEquals(Rect(20f, 20f, 50f, 50f), state.clip)
    }

    @Test
    fun `a nested alpha multiplies`() {
        val state = CanvasState(screen)

        state.pushAlpha(0.5f)
        state.pushAlpha(0.5f)

        assertEquals(0.25f, state.alpha, 0.0001f)
    }

    @Test
    fun `clipping to somewhere off screen hides everything`() {
        val state = CanvasState(screen)

        state.pushClip(Rect.of(500f, 500f, 10f, 10f))

        assertTrue(state.isHidden, "a backend can skip the whole subtree")
    }

    @Test
    fun `popping more than was pushed is a bug, not a shrug`() {
        val state = CanvasState(screen)

        assertThrows(IllegalStateException::class.java) { state.popClip() }
        assertThrows(IllegalStateException::class.java) { state.popAlpha() }
    }

    @Test
    fun `pushes and pops leave the stack where it started`() {
        val state = CanvasState(screen)

        state.pushClip(Rect.of(10f, 10f, 10f, 10f))
        state.pushAlpha(0.5f)
        state.popAlpha()
        state.popClip()

        assertTrue(state.isBalanced)
        assertEquals(screen, state.clip)
        assertEquals(1f, state.alpha)
    }
}

class RecordingCanvasTest {

    @Test
    fun `it records what it was asked to draw, in order`() {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 200f, 200f))

        canvas.shadow(Rect.of(10f, 10f, 100f, 40f), Colour.Black, spread = 6f, corner = 4f)
        canvas.rect(Rect.of(10f, 10f, 100f, 40f), Colour.rgb(0x203040), corner = 4f)
        canvas.text(FakeText("PLAY"), Offset(20f, 22f), Colour.White)

        assertEquals(3, canvas.calls.size)
        assertTrue(canvas.calls[0] is DrawCall.Shadow)
        assertTrue(canvas.calls[1] is DrawCall.Rectangle)
        assertEquals(listOf("PLAY"), canvas.texts())
    }

    @Test
    fun `every call carries the clip and opacity that applied to it`() {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 200f, 200f))

        canvas.pushClip(Rect.of(0f, 0f, 50f, 50f))
        canvas.pushAlpha(0.5f)
        canvas.rect(Rect.of(0f, 0f, 200f, 200f), Colour.White)
        canvas.popAlpha()
        canvas.popClip()
        canvas.rect(Rect.of(0f, 0f, 200f, 200f), Colour.White)

        val (clipped, plain) = canvas.only<DrawCall.Rectangle>()

        assertEquals(Rect(0f, 0f, 50f, 50f), clipped.clip)
        assertEquals(0.5f, clipped.alpha, 0.0001f)
        assertEquals(Rect(0f, 0f, 200f, 200f), plain.clip)
        assertEquals(1f, plain.alpha, 0.0001f)
    }

    @Test
    fun `something drawn entirely outside its clip is reported as invisible`() {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, 200f, 200f))

        canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))
        canvas.pushClip(Rect.of(100f, 100f, 10f, 10f))
        canvas.rect(Rect.of(100f, 100f, 10f, 10f), Colour.White)
        canvas.popClip()
        canvas.popClip()

        assertEquals(1, canvas.invisible().size, "the two clips do not overlap, so nothing showed")
    }

    @Test
    fun `an unbalanced canvas is caught rather than left to corrupt the next frame`() {
        val canvas = RecordingCanvas()

        canvas.pushClip(Rect.of(0f, 0f, 10f, 10f))

        assertThrows(IllegalStateException::class.java) { canvas.assertBalanced() }
    }

    @Test
    fun `clearing readies it for the next frame`() {
        val canvas = RecordingCanvas()
        canvas.rect(Rect.of(0f, 0f, 1f, 1f), Colour.White)
        canvas.pushAlpha(0.5f)

        canvas.clear()

        assertTrue(canvas.calls.isEmpty())
        canvas.assertBalanced()
    }

    @Test
    fun `images and raw blocks are recorded too`() {
        val canvas = RecordingCanvas()
        val texture = FakeTexture(width = 64, height = 64)

        canvas.image(texture, Rect.of(0f, 0f, 64f, 64f))
        canvas.raw { }

        assertEquals(64, canvas.only<DrawCall.Image>().single().texture.width)
        assertEquals(1, canvas.only<DrawCall.Raw>().size, "recorded, but not run: there is no backend")
    }

    @Test
    fun `its toString says what happened, so a failure is readable`() {
        val canvas = RecordingCanvas()
        assertTrue(canvas.toString().contains("nothing drawn"))

        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        assertTrue(canvas.toString().contains("Rectangle"))
    }
}
