package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tint stack, below the modifier: what a colour means as a tint, how two of them meet, and
 * where one stops.
 */
class CanvasTintTest {

    private val screen = Rect.of(0f, 0f, 100f, 100f)

    @Test
    fun `an opaque tint is itself`() {
        assertEquals(Colour.Red, Colour.Red.asTint())
    }

    @Test
    fun `a tint with no alpha is white and so changes nothing`() {
        assertEquals(Colour.White, Colour.Red.withAlpha(0).asTint())
    }

    @Test
    fun `half a tint is halfway from white to the colour and still opaque`() {
        val half = Colour.rgb(0x204080).withAlpha(128).asTint()

        assertEquals(255, half.alpha, "a tint never fades what it is on")
        assertEquals(255 - (255 - 0x20) * 128 / 255, half.red)
        assertEquals(255 - (255 - 0x40) * 128 / 255, half.green)
        assertEquals(255 - (255 - 0x80) * 128 / 255, half.blue)
    }

    @Test
    fun `no tint pushed is white`() {
        assertEquals(Colour.White, CanvasState(screen).tint)
    }

    @Test
    fun `a nested tint multiplies and popping goes back`() {
        val state = CanvasState(screen)

        state.pushTint(Colour.rgb(0x808080))
        state.pushTint(Colour.rgb(0xFF0000))

        assertEquals(Colour.rgb(0x800000), state.tint, "a grey and a red are a dark red")

        state.popTint()
        assertEquals(Colour.rgb(0x808080), state.tint, "and popping goes back to the grey")
    }

    @Test
    fun `a pushed tint is read for its strength`() {
        val state = CanvasState(screen)

        state.pushTint(Colour.Red.withAlpha(0))

        assertEquals(Colour.White, state.tint, "a flash at zero is no flash")
    }

    @Test
    fun `a tint left pushed is not balanced and reset clears it`() {
        val state = CanvasState(screen)

        state.pushTint(Colour.Red)
        assertFalse(state.isBalanced, "a leaked tint is as much a leak as a leaked clip")

        state.reset(screen)
        assertTrue(state.isBalanced)
        assertEquals(Colour.White, state.tint)
    }

    @Test
    fun `popping a tint nobody pushed is a bug`() {
        assertFailsWith<IllegalStateException> { CanvasState(screen).popTint() }
    }

    @Test
    fun `a layer starts with a fresh clip and opacity but keeps the tint`() {
        val state = CanvasState(screen)
        state.pushAlpha(0.5f)
        state.pushBlend(BlendMode.Additive)
        state.pushTint(Colour.Green)

        val inner = state.forLayer(Rect.of(10f, 10f, 20f, 20f))

        assertEquals(Rect.of(10f, 10f, 20f, 20f), inner.clip)
        assertEquals(1f, inner.alpha)
        assertEquals(BlendMode.SourceOver, inner.blend)
        assertEquals(Colour.Green, inner.tint, "a multiply is the same done to the parts")
        assertTrue(inner.isBalanced, "the tint carried in is its floor, not something to pop")
    }

    @Test
    fun `a recording canvas writes down the tint each call was drawn under`() {
        val canvas = RecordingCanvas()

        canvas.rect(screen, Colour.White)
        canvas.pushTint(Colour.Red)
        canvas.text(FakeLayout("hit"), 0f, 0f, Colour.White)
        canvas.popTint()

        val (plain, tinted) = canvas.calls
        assertEquals(Colour.White, canvas.tintOf(plain))
        assertEquals(Colour.Red, canvas.tintOf(tinted))
        assertTrue(canvas.tints)
        canvas.assertBalanced()
    }

    @Test
    fun `inside a layer the tint is still in force and the picture is not tinted again`() {
        val canvas = RecordingCanvas()

        canvas.pushTint(Colour.Red)
        val picture = canvas.layer(screen) { canvas.rect(screen, Colour.White) }
        canvas.drawLayer(picture ?: FakeTexture(100, 100), screen)
        canvas.popTint()

        assertEquals(Colour.Red, canvas.tintOf(canvas.only<DrawCall.Rectangle>().single()))
        assertEquals(Colour.White, canvas.tintOf(canvas.only<DrawCall.Layer>().single()))
    }

    @Test
    fun `a tint pushed inside a layer and never popped is caught there`() {
        val canvas = RecordingCanvas()

        assertFailsWith<IllegalStateException> { canvas.layer(screen) { canvas.pushTint(Colour.Red) } }
    }

    @Test
    fun `its toString names the tint so a failure is readable`() {
        val canvas = RecordingCanvas()

        canvas.pushTint(Colour.Red)
        canvas.rect(screen, Colour.White)
        canvas.popTint()

        assertTrue("tint Colour(#FFFF0000)" in canvas.toString(), canvas.toString())
    }

    @Test
    fun `a canvas written before tints existed says it cannot and pushing does nothing`() {
        val old = object : UiCanvas by RecordingCanvas() {
            override val tints: Boolean get() = super.tints
            override fun pushTint(tint: Colour) = super.pushTint(tint)
            override fun popTint() = super.popTint()
        }

        assertFalse(old.tints)
        old.pushTint(Colour.Red)
        old.popTint()
    }

    private class FakeLayout(override val text: String) : dev.wildware.composegl.ui.text.TextLayout {
        override val size = dev.wildware.composegl.ui.geometry.Size(text.length * 8f, 16f)
        override val lineCount = 1
        override val firstBaseline = 12f
    }
}
