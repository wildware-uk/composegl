package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextLayout
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
        assertThrows(IllegalStateException::class.java) { state.popBlend() }
    }

    @Test
    fun `pushes and pops leave the stack where it started`() {
        val state = CanvasState(screen)

        state.pushClip(Rect.of(10f, 10f, 10f, 10f))
        state.pushAlpha(0.5f)
        state.pushBlend(BlendMode.Additive)
        state.popBlend()
        state.popAlpha()
        state.popClip()

        assertTrue(state.isBalanced)
        assertEquals(screen, state.clip)
        assertEquals(1f, state.alpha)
        assertEquals(BlendMode.SourceOver, state.blend)
    }

    @Test
    fun `a nested blend replaces rather than composing, and popping goes back`() {
        val state = CanvasState(screen)

        state.pushBlend(BlendMode.Additive)
        state.pushBlend(BlendMode.SourceOver)

        assertEquals(BlendMode.SourceOver, state.blend, "the innermost one wins outright")

        state.popBlend()
        assertEquals(BlendMode.Additive, state.blend, "and popping goes back to the one underneath")
    }

    @Test
    fun `a blend left pushed is not balanced`() {
        val state = CanvasState(screen)

        state.pushBlend(BlendMode.Additive)

        assertTrue(!state.isBalanced, "a leaked blend mode is as much a leak as a leaked clip")
    }

    @Test
    fun `reset clears the blend stack too`() {
        val state = CanvasState(screen)

        state.pushBlend(BlendMode.Additive)
        state.reset(screen)

        assertTrue(state.isBalanced)
        assertEquals(BlendMode.SourceOver, state.blend)
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
    fun `every call carries the blend mode that applied to it`() {
        val canvas = RecordingCanvas()

        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        canvas.pushBlend(BlendMode.Additive)
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        canvas.popBlend()
        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)

        assertEquals(
            listOf(BlendMode.SourceOver, BlendMode.Additive, BlendMode.SourceOver),
            canvas.blends,
        )
    }

    @Test
    fun `the mode can be asked for one call at a time, after filtering`() {
        // What `blends` alone cannot answer: only<T>() and calls(mode) hand back filtered lists,
        // with no index left to look a mode up by.
        val canvas = RecordingCanvas()
        val texture = FakeTexture()

        canvas.image(texture, Rect.of(0f, 0f, 8f, 8f))
        canvas.pushBlend(BlendMode.Additive)
        canvas.image(texture, Rect.of(20f, 20f, 8f, 8f))
        canvas.popBlend()

        val (plain, glowing) = canvas.only<DrawCall.Image>()

        assertEquals(BlendMode.SourceOver, canvas.blendOf(plain))
        assertEquals(BlendMode.Additive, canvas.blendOf(glowing))
        assertEquals(listOf(glowing), canvas.calls(BlendMode.Additive))
    }

    @Test
    fun `a layer draws plainly however the blend was set outside it, and the mode comes back`() {
        val canvas = RecordingCanvas()

        canvas.pushBlend(BlendMode.Additive)
        canvas.layer(Rect.of(0f, 0f, 40f, 40f)) {
            canvas.rect(Rect.of(0f, 0f, 40f, 40f), Colour.White)
        }
        canvas.drawLayer(FakeTexture(), Rect.of(0f, 0f, 40f, 40f))
        canvas.popBlend()

        assertEquals(
            BlendMode.SourceOver,
            canvas.blendOf(canvas.only<DrawCall.Rectangle>().single()),
            "a layer's picture starts transparent, so adding into it is not adding onto the screen",
        )
        assertEquals(
            BlendMode.Additive,
            canvas.blendOf(canvas.only<DrawCall.Layer>().single()),
            "the mode in force applies to the composite, which is how a whole group glows",
        )
    }

    @Test
    fun `a blend pushed inside a layer and never popped is caught there`() {
        val canvas = RecordingCanvas()

        assertThrows(IllegalStateException::class.java) {
            canvas.layer(Rect.of(0f, 0f, 40f, 40f)) { canvas.pushBlend(BlendMode.Additive) }
        }
    }

    @Test
    fun `a blend left pushed fails the frame it was pushed in`() {
        val canvas = RecordingCanvas()
        canvas.begin(Viewport.oneToOne(Size(100f, 100f)))
        canvas.pushBlend(BlendMode.Additive)

        assertThrows(IllegalStateException::class.java) { canvas.end() }
        assertEquals(0, canvas.frames, "a frame that leaked state is not a frame that rendered")
    }

    @Test
    fun `a turned picture is recorded with its angle and pivot`() {
        val canvas = RecordingCanvas()
        val texture = FakeTexture()

        canvas.image(texture, Rect.of(10f, 10f, 40f, 4f), degrees = 30f, pivotX = 0f, pivotY = 0.5f)

        val turned = canvas.only<DrawCall.RotatedImage>().single()
        assertEquals(30f, turned.degrees)
        assertEquals(0f, turned.pivotX)
        assertEquals(0.5f, turned.pivotY)
        assertEquals(Rect(10f, 10f, 50f, 14f), turned.destination, "the box before turning")
    }

    @Test
    fun `a turned picture records the tint, the sub-rectangle, the clip and the fade as well`() {
        // The angle is the new part, but a call that wrote the angle down and lost everything else
        // would be no use to the widget test that is the whole point of this canvas.
        val canvas = RecordingCanvas()
        val texture = FakeTexture()
        canvas.pushClip(Rect.of(0f, 0f, 50f, 50f))
        canvas.pushAlpha(0.5f)

        canvas.image(
            texture,
            Rect.of(10f, 10f, 40f, 4f),
            degrees = 30f,
            tint = Colour.Black,
            source = Rect.of(2f, 2f, 8f, 8f),
        )

        val turned = canvas.only<DrawCall.RotatedImage>().single()
        assertEquals(Colour.Black, turned.tint, "the tint")
        assertEquals(Rect(2f, 2f, 10f, 10f), turned.source, "the part of the texture asked for")
        assertEquals(Rect(0f, 0f, 50f, 50f), turned.clip, "the clip in force")
        assertEquals(0.5f, turned.alpha, "and the fade in force")
    }

    @Test
    fun `nine separately-cut pieces are refused at an angle too`() {
        // The upright call refuses them because no backend that draws would put them on a screen.
        // A recording test that got away with it here would be passing against art that cannot be
        // drawn anywhere.
        val canvas = RecordingCanvas()

        assertThrows(IllegalArgumentException::class.java) {
            canvas.image(pieces(), Rect.of(0f, 0f, 8f, 8f), degrees = 45f)
        }
    }

    /** A frame the host cut for itself: nine handles, and so not a picture anything can draw. */
    private fun pieces() = NineRegions(
        topLeft = FakeTexture(8, 8), top = FakeTexture(8, 8), topRight = FakeTexture(8, 8),
        left = FakeTexture(8, 8), centre = FakeTexture(8, 8), right = FakeTexture(8, 8),
        bottomLeft = FakeTexture(8, 8), bottom = FakeTexture(8, 8), bottomRight = FakeTexture(8, 8),
    )

    @Test
    fun `two calls that look identical are two calls, each with its own mode`() {
        // Exactly what an additive mode makes common: draw the shape, then draw the same shape
        // again to put light on it. Matching a call by value would answer the first one's mode
        // for both of them.
        val canvas = RecordingCanvas()
        val box = Rect.of(0f, 0f, 10f, 10f)

        canvas.rect(box, Colour.White)
        canvas.pushBlend(BlendMode.Additive)
        canvas.rect(box, Colour.White)
        canvas.popBlend()

        val (plain, glowing) = canvas.only<DrawCall.Rectangle>()
        assertEquals(plain, glowing, "the two are equal as values; that is the point of the test")
        assertEquals(BlendMode.SourceOver, canvas.blendOf(plain))
        assertEquals(BlendMode.Additive, canvas.blendOf(glowing), "and the second one glows")
    }

    @Test
    fun `this canvas says yes to both, because writing a thing down costs the same either way`() {
        val canvas = RecordingCanvas()

        assertTrue(canvas.rotatesImages, "it records the angle, so it really turns one")
        assertTrue(canvas.supports(BlendMode.Additive), "and it records the mode beside the call")
    }

    @Test
    fun `no turn at all is recorded exactly as the upright call records it`() {
        val canvas = RecordingCanvas()
        val texture = FakeTexture()

        canvas.image(texture, Rect.of(0f, 0f, 8f, 8f), degrees = 0f)

        assertEquals(1, canvas.only<DrawCall.Image>().size, "a widget passing a variable zero")
        assertTrue(canvas.only<DrawCall.RotatedImage>().isEmpty())
    }

    @Test
    fun `a test that only cares that a picture was drawn finds both kinds`() {
        val canvas = RecordingCanvas()
        val texture = FakeTexture()

        canvas.image(texture, Rect.of(0f, 0f, 8f, 8f))
        canvas.image(texture, Rect.of(0f, 0f, 8f, 8f), degrees = 45f)

        assertEquals(2, canvas.only<DrawCall.Pictured>().size)
    }

    @Test
    fun `its toString says what happened, so a failure is readable`() {
        val canvas = RecordingCanvas()
        assertTrue(canvas.toString().contains("nothing drawn"))

        canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        assertTrue(canvas.toString().contains("Rectangle"))
    }
}
