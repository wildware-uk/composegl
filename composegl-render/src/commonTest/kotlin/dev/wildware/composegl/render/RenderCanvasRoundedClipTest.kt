package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A rounded clip drawn in place: the shape shader trims what lands, and no picture is taken.
 *
 * What the GL canvas does for `Modifier.clip(corner = …)` and a round `clipShape`, asserted against a
 * device that writes down the mask each draw was kept inside.
 */
class RenderCanvasRoundedClipTest {

    private val device = RecordingDevice()
    private val design = Viewport.oneToOne(Size(400f, 300f))

    private fun canvas(on: GpuDevice = device, trace: DrawCallTrace? = null) =
        RenderCanvas(on).also { it.traceDrawCalls(trace) }

    private fun frame(canvas: RenderCanvas = canvas(), viewport: Viewport = design, block: RenderCanvas.() -> Unit): RenderCanvas {
        canvas.begin(viewport)
        canvas.block()
        canvas.end()
        return canvas
    }

    private val card = Rect.of(100f, 50f, 200f, 150f)

    /** What the draw pass does for a rounded clip: an ordinary push, then its corners rounded. */
    private fun RenderCanvas.rounded(rect: Rect, corners: Corners) {
        pushClip(rect)
        roundClip(corners)
    }

    @Test
    fun `a rounded clip is drawn in place through a mask and takes no picture`() {
        frame {
            assertTrue(roundsClips)
            rounded(card, Corners(10f, 20f, 30f, 40f))
            rect(Rect.of(0f, 0f, 400f, 300f), Colour.Red)
            popClip()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Blue)
        }

        assertTrue(device.named("offscreen").isEmpty(), "no picture: ${device.calls}")
        assertEquals(2, device.draws.size)
        val mask = assertNotNull(device.draws[0].mask, "the fill inside the clip is kept inside the mask")
        // 100 to 300 across and 50 to 200 down a design 300 tall: the middle is 200 across, 175 up.
        assertEquals(200f, mask.centreX)
        assertEquals(175f, mask.centreY)
        assertEquals(100f, mask.halfWidth)
        assertEquals(75f, mask.halfHeight)
        assertEquals(listOf(10f, 20f, 30f, 40f), mask.corners)
        assertEquals(1f, mask.pixelsAcross)
        assertEquals(1f, mask.pixelsUp)
        assertTrue("scissor(100, 100, 200, 150)" in device.calls, "and the square edges are a scissor: ${device.calls}")
        assertNull(device.draws[1].mask, "what comes after the clip is not trimmed")
    }

    @Test
    fun `a still rounded clip takes no picture on its second frame either`() {
        val canvas = canvas()
        repeat(2) {
            frame(canvas) {
                rounded(card, Corners.all(12f))
                rect(card, Colour.Red)
                popClip()
            }
        }

        assertTrue(device.named("offscreen").isEmpty(), device.calls.toString())
        assertEquals(2, device.draws.count { it.mask != null })
    }

    @Test
    fun `on a bigger window the mask is in its pixels and the corners stay in design units`() {
        val letterboxed = Viewport(Size(400f, 300f), Size(800f, 700f), ScalePolicy.Fit)
        frame(viewport = letterboxed) {
            rounded(Rect.of(10f, 20f, 100f, 50f), Corners.all(8f))
            rect(Rect.of(0f, 0f, 400f, 300f), Colour.Red)
            popClip()
        }

        val mask = assertNotNull(device.draws.single().mask)
        // The middle is 60 across and 45 down the design. Twice the size, under a 50 pixel bar, is
        // 120 across and 140 down a window 700 tall, which is 560 up.
        assertEquals(120f, mask.centreX)
        assertEquals(560f, mask.centreY)
        assertEquals(50f, mask.halfWidth)
        assertEquals(25f, mask.halfHeight)
        assertEquals(listOf(8f, 8f, 8f, 8f), mask.corners)
        assertEquals(2f, mask.pixelsAcross)
        assertEquals(2f, mask.pixelsUp)
    }

    @Test
    fun `corners are held to half the shorter side`() {
        frame {
            rounded(Rect.of(0f, 0f, 100f, 40f), Corners(30f, 0f, 50f, 5f))
            rect(Rect.of(0f, 0f, 100f, 40f), Colour.Red)
            popClip()
        }

        assertEquals(listOf(20f, 0f, 20f, 5f), assertNotNull(device.draws.single().mask).corners)
    }

    @Test
    fun `a target that keeps its top row first has its rounded corners turned over`() {
        val canvas = canvas()
        val tall = Viewport(Size(400f, 300f), Size(800f, 800f), ScalePolicy.Fit)
        canvas.begin(tall, FrameTarget.Host, clear = null, topRowFirst = true)
        canvas.rounded(Rect.of(10f, 20f, 100f, 50f), Corners(1f, 2f, 3f, 4f))
        canvas.rect(Rect.of(10f, 20f, 100f, 50f), Colour.Red)
        canvas.popClip()
        canvas.end()

        val mask = assertNotNull(device.draws.single().mask)
        // Rows count down from the top here: 100 rows of bar, then the middle 45 down at twice the size.
        assertEquals(120f, mask.centreX)
        assertEquals(190f, mask.centreY)
        assertEquals(listOf(4f, 3f, 2f, 1f), mask.corners, "the design's bottom corners are the target's top ones")
    }

    @Test
    fun `a pushed transform moves and grows the mask with what it clips`() {
        frame {
            pushTransform(2f, 10f, 0f)
            rounded(Rect.of(0f, 0f, 50f, 50f), Corners.all(5f))
            rect(Rect.of(0f, 0f, 50f, 50f), Colour.Red)
            popClip()
            popTransform()
        }

        val mask = assertNotNull(device.draws.single().mask)
        assertEquals(60f, mask.centreX)
        assertEquals(250f, mask.centreY)
        assertEquals(50f, mask.halfWidth)
        assertEquals(listOf(10f, 10f, 10f, 10f), mask.corners)
    }

    @Test
    fun `a rounded clip inside another is left to a picture`() {
        frame {
            rounded(card, Corners.all(10f))
            assertFalse(roundsClips, "one at a time")
            pushClip(Rect.of(110f, 60f, 20f, 20f))
            assertFalse(roundsClips, "a square clip inside does not change that")
            popClip()
            popClip()
            assertTrue(roundsClips)
        }
    }

    @Test
    fun `a rounded clip that fades as one piece is left to a picture`() {
        frame {
            pushAlpha(0.5f)
            assertFalse(roundsClips, "faded")
            popAlpha()
            assertTrue(roundsClips)
        }
    }

    @Test
    fun `a rounded clip pushed under an additive blend is trimmed in place and adds`() {
        // A glow cut to a card's corners: added part by part, as the same glow with no clip is.
        frame {
            pushBlend(BlendMode.Additive)
            assertTrue(roundsClips, "added")
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            popClip()
            popBlend()
        }

        val drawn = device.draws.single()
        assertEquals(Blend.Additive, drawn.blend)
        assertNotNull(drawn.mask, "trimmed by the shader")
        assertEquals(0, device.named("offscreen").size, "no picture")
    }

    @Test
    fun `plain drawing round an added rounded clip is cut from it going in and coming out`() {
        // The blend and the mask both wait for the next quad: the card's glow must still be one
        // draw call of its own, added and trimmed, between two plain untrimmed ones.
        frame {
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            pushBlend(BlendMode.Additive)
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            popClip()
            popBlend()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
        }

        assertEquals(
            listOf(Blend.SourceOver to false, Blend.Additive to true, Blend.SourceOver to false),
            device.draws.map { it.blend to (it.mask != null) },
        )
    }

    @Test
    fun `a game's drawing in a rounded clip pushed under an additive blend is added as the clip is`() {
        // The picture it opens is put down in the mode the clip was pushed under: added, as the cut
        // picture of the whole clip is from the next frame on. Not identically: in the opened picture
        // whatever the clip draws after the game's drawing is added onto it, where the cut picture
        // lays it over it, so that one frame differs wherever the two overlap.
        frame {
            pushBlend(BlendMode.Additive)
            rounded(card, Corners.all(10f))
            raw {}
            popClip()
            popBlend()
        }

        val composite = device.draws.single()
        assertEquals(Blend.PremultipliedAdditive, composite.blend)
        assertNotNull(composite.mask)
    }

    @Test
    fun `a device that cannot mask does not round clips and rounding one anyway leaves it square`() {
        val plain = RecordingDevice(masks = false)
        frame(canvas(plain)) {
            assertFalse(roundsClips)
            rounded(card, Corners.all(10f))
            rect(Rect.of(0f, 0f, 400f, 300f), Colour.Red)
            popClip()
        }

        assertNull(plain.draws.single().mask)
        assertTrue("scissor(100, 100, 200, 150)" in plain.calls, plain.calls.toString())
    }

    @Test
    fun `a canvas outside a frame does not round clips`() {
        assertFalse(canvas().roundsClips)
    }

    @Test
    fun `the mask comes off with its own clip and not with a square one inside it`() {
        val trace = DrawCallTrace()
        frame(canvas(trace = trace)) {
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            pushClip(Rect.of(110f, 60f, 20f, 20f))
            rect(card, Colour.Red)
            popClip()
            rect(card, Colour.Red)
            popClip()
            rect(card, Colour.Red)
        }

        assertEquals(listOf(true, true, true, false), device.draws.map { it.mask != null })
        assertEquals(4, trace.culprits(withEnd = true).sumOf { it.calls })
    }

    @Test
    fun `a picture inside a rounded clip is drawn without the mask and put down through it`() {
        frame {
            rounded(card, Corners.all(10f))
            val picture = assertNotNull(
                layer(card) {
                    assertTrue(roundsClips, "a picture is a clean slate of its own")
                    rect(card, Colour.Red)
                },
            )
            assertFalse(roundsClips, "and outside it the clip is still in force")
            drawLayer(picture, card)
            popClip()
        }

        assertEquals(1, device.named("offscreen").size)
        val (inside, composite) = device.draws
        assertNull(inside.mask, "the picture is drawn whole")
        assertNotNull(composite.mask, "and trimmed as it lands")
        assertEquals(Blend.PremultipliedSourceOver, composite.blend)
    }

    @Test
    fun `a rounded clip inside a picture is in the picture's pixels`() {
        frame {
            layer(Rect.of(100f, 100f, 50f, 40f)) {
                rounded(Rect.of(110f, 104f, 20f, 10f), Corners(1f, 2f, 3f, 4f))
                rect(Rect.of(100f, 100f, 50f, 40f), Colour.Red)
                popClip()
            }
        }

        val mask = assertNotNull(device.draws.first().mask)
        // 20 across and 9 down the picture's own corner, in a picture 40 pixels tall: 31 up.
        assertEquals(20f, mask.centreX)
        assertEquals(31f, mask.centreY)
        assertEquals(listOf(1f, 2f, 3f, 4f), mask.corners, "a picture counts up, so its corners stay the right way round")
    }

    @Test
    fun `a game's own drawing inside a rounded clip goes through a picture and back through the mask`() {
        var lent: Any? = null
        frame {
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            raw { lent = it }
            popClip()
        }

        assertTrue(lent is RenderFrame, "the game still drew")
        val target = device.named("offscreen").single().substringAfter("offscreen(").substringBefore(",")
        val into = device.calls.indexOf("target(target$target, 0, 0, 200, 150)")
        val suspended = device.calls.indexOf("suspend")
        assertTrue(into in 0 until suspended, "into a picture of the clip first: ${device.calls}")
        val composite = device.draws.last()
        assertEquals(Blend.PremultipliedSourceOver, composite.blend)
        assertNotNull(composite.mask, "and trimmed as it is put down")
        assertTrue(device.draws.first().mask != null, "the fill before it is trimmed too")
    }

    @Test
    fun `a game's own drawing inside a faded child of a rounded clip is not faded by the picture`() {
        frame {
            rounded(card, Corners.all(10f))
            pushAlpha(0.5f)
            raw {}
            popAlpha()
            popClip()
        }

        val composite = device.draws.single()
        assertEquals(listOf(1f, 1f, 1f, 1f), composite.fill(0), "a game's drawing knows no fade, here or anywhere")
    }

    @Test
    fun `an effect inside a rounded clip goes through a picture and back through the mask`() {
        val effect = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord); }"))
        frame {
            rounded(card, Corners.all(10f))
            val picture = assertNotNull(layer(card) { rect(card, Colour.Red) })
            drawLayer(picture, card, effect)
            popClip()
        }

        assertEquals(2, device.named("offscreen").size, "the effect's own picture and one to trim it in")
        val effectAt = device.calls.indexOfFirst { it.startsWith("drawEffect") }
        val second = device.named("offscreen")[1].substringAfter("offscreen(").substringBefore(",")
        assertTrue(device.calls.indexOf("target(target$second, 0, 0, 200, 150)") in 0 until effectAt, device.calls.toString())
        assertNotNull(device.draws.last().mask, "the effect is trimmed as it is put down")
    }

    @Test
    fun `a flush inside a rounded clip is blamed on the clip`() {
        val trace = DrawCallTrace()
        frame(canvas(trace = trace)) {
            rect(card, Colour.Red)
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            popClip()
            // Something after it, or leaving the clip would cut nothing: the frame's end draws it.
            rect(card, Colour.Red)
        }

        assertEquals(2, trace.culprits().single { it.reason == BatchBreak.Clip }.calls)
    }

    @Test
    fun `an effect near a corner of a rounded clip is trimmed in a picture of its own size`() {
        val effect = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord); }"))
        val icon = Rect.of(100f, 50f, 20f, 20f)
        frame {
            rounded(card, Corners.all(10f))
            repeat(3) {
                val picture = assertNotNull(layer(icon) { rect(icon, Colour.Red) })
                drawLayer(picture, icon, effect)
            }
            popClip()
        }

        val sizes = device.named("offscreen").map { it.substringAfter(", ").removeSuffix(")") }
        assertEquals(listOf("64x64", "64x64"), sizes, "the icon's own picture and one to trim it in, reused: ${device.calls}")
        assertTrue(device.named("target(target").all { it.endsWith("0, 0, 20, 20)") }, "each drawn at the icon's size")
        assertEquals(3, device.effects.size)
        assertTrue(device.named("target(target").none { it.endsWith("200, 150)") }, "never a picture of the whole card")
    }

    @Test
    fun `an effect clear of every rounded corner is drawn straight with no picture to trim it`() {
        val effect = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord); }"))
        val icon = Rect.of(150f, 100f, 20f, 20f)
        frame {
            rounded(card, Corners.all(10f))
            val picture = assertNotNull(layer(icon) { rect(icon, Colour.Red) })
            drawLayer(picture, icon, effect)
            popClip()
        }

        assertEquals(1, device.named("offscreen").size, "the icon's own picture only")
        assertEquals(1, device.effects.size)
    }

    @Test
    fun `a game's own drawing given a place is trimmed in a picture of the whole clip since it may draw past that place`() {
        frame {
            rounded(card, Corners.all(10f))
            // Well clear of every corner, but the place only moves the game's origin: it can draw
            // anywhere, corners included.
            raw(Rect.of(190f, 115f, 20f, 20f)) {}
            popClip()
        }

        assertEquals(listOf("256x192"), device.named("offscreen").map { it.substringAfter(", ").removeSuffix(")") })
        val target = device.named("offscreen").single().substringAfter("offscreen(").substringBefore(",")
        assertTrue("target(target$target, 0, 0, 200, 150)" in device.calls, "drawn at the whole clip's size: ${device.calls}")
    }

    @Test
    fun `games' drawings in one rounded clip share one picture of it and what comes after lands on top`() {
        frame {
            rounded(card, Corners.all(10f))
            rect(card, Colour.Red)
            raw {}
            rect(Rect.of(150f, 100f, 20f, 20f), Colour.Blue)
            raw(Rect.of(190f, 115f, 20f, 20f)) {}
            raw {}
            popClip()
        }

        val binds = device.calls.withIndex().filter { it.value.startsWith("target(target") && it.value.endsWith(", 0, 0, 200, 150)") }
        assertEquals(1, binds.size, "one picture for all three: ${device.calls}")
        assertEquals(1, device.calls.count { it == "clear(0.0, 0.0, 0.0, 0.0)" }, "cleared once")
        assertEquals(listOf(true, false, true), device.draws.map { it.mask != null }, "the fill in place, the box in the picture, the picture trimmed")
        val blue = device.calls.indexOfFirst { it.startsWith("drawShapes") && !it.contains("masked") }
        val back = device.calls.withIndex().indexOfFirst { it.index > binds.single().index && it.value.startsWith("target(") && it.value != binds.single().value }
        assertTrue(blue in binds.single().index until back, "the box after the game's drawing goes into its picture: ${device.calls}")
    }

    @Test
    fun `a game's drawing in a rounded clip is put down plainly whatever mode is in force`() {
        frame {
            rounded(card, Corners.all(10f))
            pushBlend(BlendMode.Additive)
            raw {}
            popBlend()
            popClip()
        }

        val composite = device.draws.single()
        assertEquals(Blend.PremultipliedSourceOver, composite.blend, "a game's drawing knows no mode, here or anywhere")
        assertNotNull(composite.mask)
    }

    @Test
    fun `a picture taken inside an opened one is drawn into it untrimmed`() {
        val small = Rect.of(150f, 100f, 20f, 20f)
        frame {
            rounded(card, Corners.all(10f))
            raw {}
            val picture = assertNotNull(layer(small) { rect(small, Colour.Red) })
            drawLayer(picture, small)
            rect(Rect.of(110f, 60f, 10f, 10f), Colour.Blue)
            popClip()
        }

        assertTrue(device.draws.dropLast(1).all { it.mask == null }, "nothing trimmed on its way into the picture")
        assertNotNull(device.draws.last().mask, "the picture is, as it is put down")
    }

    /** A canvas in front of another that counts its clips, the way a test or a tool wraps one. */
    private class Counting(val inner: RenderCanvas) : UiCanvas by inner {
        var pushes = 0
        var pops = 0

        override fun pushClip(rect: Rect) {
            pushes++
            inner.pushClip(rect)
        }

        override fun popClip() {
            pops++
            inner.popClip()
        }
    }

    @Test
    fun `a canvas wrapped in one that counts clips sees each rounded clip pushed and popped`() {
        val inner = canvas()
        val counting = Counting(inner)
        inner.begin(design)
        assertTrue(counting.roundsClips)
        counting.pushClip(card)
        counting.roundClip(Corners.all(10f))
        counting.rect(card, Colour.Red)
        counting.popClip()
        counting.rect(card, Colour.Red)
        inner.end()

        assertEquals(1, counting.pushes)
        assertEquals(1, counting.pops)
        assertEquals(listOf(true, false), device.draws.map { it.mask != null }, "trimmed inside and not after")
    }

    @Test
    fun `rounding a clip that is no longer the innermost one does nothing`() {
        frame {
            pushClip(card)
            pushClip(Rect.of(110f, 60f, 20f, 20f))
            popClip()
            roundClip(Corners.all(10f))
            rect(card, Colour.Red)
            popClip()
        }

        assertNull(device.draws.single().mask)
    }
}
