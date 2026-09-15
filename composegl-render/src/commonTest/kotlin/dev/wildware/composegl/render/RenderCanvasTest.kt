package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderCanvasTest {

    private val device = RecordingDevice()
    private val design = Viewport.oneToOne(Size(400f, 300f))

    /** A game's texture, as a backend would resolve it. */
    private class Sprite(override val width: Int, override val height: Int) : TextureHandle

    private val sheet = device.texture(128, 128, smooth = true)
    private val sprite = Sprite(128, 128)
    private val resolver = TextureResolver { handle -> if (handle === sprite) BoundPicture(sheet) else null }

    init {
        // The game's own sheet is not something a canvas did.
        device.calls.clear()
    }

    private fun canvas(trace: DrawCallTrace? = null) = RenderCanvas(device, textures = resolver).also { it.traceDrawCalls(trace) }

    private fun frame(canvas: RenderCanvas = canvas(), viewport: Viewport = design, block: RenderCanvas.() -> Unit): RenderCanvas {
        canvas.begin(viewport)
        canvas.block()
        canvas.end()
        return canvas
    }

    private fun near(expected: Float, actual: Float) = abs(expected - actual) < 0.005f

    @Test
    fun `a canvas that has drawn nothing has asked the device for nothing`() {
        val fresh = RecordingDevice()
        val canvas = RenderCanvas(fresh)
        canvas.pushBlend(BlendMode.Additive)
        canvas.popBlend()
        assertEquals(0, canvas.drawCalls)
        assertTrue(canvas.drawsLayers && canvas.rotatesImages && canvas.supports(BlendMode.Additive))
        canvas.close()

        assertEquals(listOf("close"), fresh.calls, "nothing built, so nothing but the device's own close")
    }

    @Test
    fun `a frame takes the device then gives it back`() {
        frame { rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red) }

        assertEquals("begin(host)", device.calls.first())
        assertEquals("end", device.calls.last())
        assertTrue(device.calls.indexOf("target(host, 0, 0, 400, 300)") in 1 until device.calls.size - 1)
    }

    @Test
    fun `y is flipped once so the device counts up from the bottom`() {
        frame { rect(Rect.of(10f, 20f, 100f, 40f), Colour.Red) }

        val draw = device.draws.single()
        // The design is 300 tall, so a box from 20 to 60 down is from 240 to 280 up, grown by the
        // one pixel soft edge.
        assertEquals(239f, draw.at(0, 1))
        assertEquals(281f, draw.at(1, 1))
        assertEquals(2f / 400f, draw.projection[0])
        assertEquals(2f / 300f, draw.projection[5])
    }

    @Test
    fun `a clip is a scissor in window pixels and each change flushes`() {
        val trace = DrawCallTrace()
        val canvas = canvas(trace)
        val letterboxed = Viewport(Size(400f, 300f), Size(800f, 700f), ScalePolicy.Fit)
        frame(canvas, letterboxed) {
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            pushClip(Rect.of(10f, 20f, 100f, 50f))
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popClip()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
        }

        // Twice the size, with 50 pixels of bar above and below: the clip's top at 20 is 90 down
        // the window, so its bottom edge at 70 is 190 down, which is 510 up.
        assertTrue("scissor(20, 510, 200, 100)" in device.calls, device.calls.toString())
        assertEquals(3, canvas.drawCalls)
        assertEquals(2, trace.culprits().single { it.reason == BatchBreak.Clip }.calls)
    }

    @Test
    fun `nested clips intersect`() {
        frame {
            pushClip(Rect.of(0f, 0f, 100f, 100f))
            pushClip(Rect.of(50f, 50f, 100f, 100f))
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popClip()
            popClip()
        }

        assertTrue("scissor(50, 200, 50, 50)" in device.calls, device.calls.toString())
        assertEquals("noScissor", device.calls.last { it.startsWith("scissor") || it == "noScissor" })
    }

    @Test
    fun `opacity multiplies down the stack into the vertex colour`() {
        frame {
            pushAlpha(0.5f)
            pushAlpha(0.5f)
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popAlpha()
            popAlpha()
        }

        val fill = device.draws.single().fill(0)
        assertEquals(1f, fill[0])
        assertTrue(near(0.25f, fill[3]), "a quarter opaque, got ${fill[3]}")
    }

    @Test
    fun `a tint multiplies the colour and costs no draw call`() {
        val canvas = frame {
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
            pushTint(Colour.argb(0xFF808080))
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
            popTint()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.White)
        }

        val draw = device.draws.single()
        assertEquals(1, canvas.drawCalls)
        assertEquals(1f, draw.fill(0)[0])
        assertTrue(near(0x80 / 255f, draw.fill(4)[0]), "tinted grey, got ${draw.fill(4)[0]}")
        assertEquals(1f, draw.fill(8)[0], "and nothing after the pop")
    }

    @Test
    fun `the innermost blend wins and popping puts the outer one back`() {
        frame {
            pushBlend(BlendMode.Additive)
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            pushBlend(BlendMode.SourceOver)
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popBlend()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            popBlend()
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
        }

        assertEquals(
            listOf(Blend.Additive, Blend.SourceOver, Blend.Additive, Blend.SourceOver),
            device.draws.map { it.blend },
        )
    }

    @Test
    fun `an unbalanced frame is caught after the device is given back`() {
        val canvas = canvas()
        canvas.begin(design)
        canvas.pushAlpha(0.5f)
        assertFailsWith<IllegalStateException> { canvas.end() }
        assertEquals("end", device.calls.last())
    }

    @Test
    fun `a layer draws into an offscreen picture then puts everything back`() {
        val trace = DrawCallTrace()
        val canvas = canvas(trace)
        var picture: TextureHandle? = null
        frame(canvas) {
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            picture = layer(Rect.of(100f, 100f, 50f, 20f)) {
                rect(Rect.of(100f, 100f, 50f, 20f), Colour.Blue)
            }
            drawLayer(assertNotNull(picture), Rect.of(100f, 100f, 50f, 20f))
        }

        val target = canvasTarget(picture) as RecordingDevice.FakeTarget
        assertEquals("offscreen(${target.id}, 50x20)", device.named("offscreen").single())
        val into = device.calls.indexOf("target(target${target.id}, 0, 0, 50, 20)")
        assertTrue(into > 0, device.calls.toString())
        assertEquals("noScissor", device.calls[into + 1])
        assertTrue(device.calls[into + 2].startsWith("clear("), "cleared with the scissor off")
        assertTrue(device.calls.subList(into, device.calls.size).contains("target(host, 0, 0, 400, 300)"))

        // Drawn inside the layer with the layer's own projection: its left edge is its origin.
        val inside = device.draws[1]
        assertEquals(2f / 50f, inside.projection[0])
        assertTrue(near(-1f - 100f * 2f / 50f, inside.projection[12]))
        // Composited premultiplied, texture coordinates flipped, from the layer's own texture.
        val composite = device.draws[2]
        assertEquals(Blend.PremultipliedSourceOver, composite.blend)
        assertSame(target.texture, composite.texture)
        assertEquals(1f, composite.at(1, 16), "the top of the quad reads v = 1, the framebuffer's top row")
        // Into the layer, out of it, and back from the premultiplied composite.
        assertEquals(3, trace.culprits().single { it.reason == BatchBreak.Layer }.calls)
    }

    private fun canvasTarget(picture: TextureHandle?) = (picture as LayerPicture).target

    @Test
    fun `layers are reused frame after frame and let go when nobody wants them`() {
        val canvas = canvas()
        repeat(3) {
            frame(canvas) { layer(Rect.of(0f, 0f, 64f, 64f)) { rect(Rect.of(0f, 0f, 1f, 1f), Colour.Red) } }
        }
        assertEquals(1, device.named("offscreen").size, "one picture for three frames")

        repeat(LayerPool.SpareFrames + 1) { frame(canvas) {} }
        assertEquals(1, device.deleted.size, "and it goes back after a quiet second")
    }

    @Test
    fun `a layer inside a layer gets a picture of its own`() {
        frame {
            layer(Rect.of(0f, 0f, 64f, 64f)) {
                layer(Rect.of(0f, 0f, 64f, 64f)) { rect(Rect.of(0f, 0f, 1f, 1f), Colour.Red) }
            }
        }
        assertEquals(2, device.named("offscreen").size)
    }

    @Test
    fun `a device that cannot draw offscreen says so and makes no layer`() {
        val flat = RecordingDevice(offscreen = false)
        val canvas = RenderCanvas(flat)
        canvas.begin(design)
        assertNull(canvas.layer(Rect.of(0f, 0f, 10f, 10f)) {})
        canvas.end()
        assertFalse(canvas.drawsLayers)
        assertFalse(canvas.tiltsLayers)
    }

    @Test
    fun `a layer bigger than the device allows is refused`() {
        val small = RecordingDevice(maxTextureSize = 32)
        val canvas = RenderCanvas(small)
        canvas.begin(design)
        assertNull(canvas.layer(Rect.of(0f, 0f, 64f, 10f)) {})
        canvas.end()
        assertEquals(0, small.named("offscreen").size)
    }

    @Test
    fun `an effect flushes first and composites premultiplied in the blend in force`() {
        val trace = DrawCallTrace()
        val effect = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord); }"))
        frame(canvas(trace)) {
            val picture = assertNotNull(layer(Rect.of(0f, 0f, 200f, 150f)) { rect(Rect.of(0f, 0f, 1f, 1f), Colour.Red) })
            pushBlend(BlendMode.Additive)
            pushAlpha(0.5f)
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            drawLayer(picture, Rect.of(0f, 0f, 200f, 150f), effect)
            popAlpha()
            popBlend()
        }

        val drawn = device.effects.single()
        assertEquals(Blend.PremultipliedAdditive, drawn.blend)
        assertEquals(0.5f, drawn.alpha)
        // The top-left quarter of the design, in clip space.
        assertEquals(listOf(-1f, 1f, 0f, 0f), listOf(drawn.left, drawn.top, drawn.right, drawn.bottom))
        val effectAt = device.calls.indexOfFirst { it.startsWith("drawEffect") }
        assertEquals(2, device.calls.subList(0, effectAt).count { it.startsWith("drawShapes") }, "the rect under it went first")
        assertEquals(1, trace.culprits(withEnd = true).single { it.reason == BatchBreak.Shader }.calls)
    }

    @Test
    fun `raw lends the engine its state and takes it back`() {
        var lent: Any? = null
        frame {
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            raw { lent = it }
        }

        val flushed = device.calls.indexOfFirst { it.startsWith("drawShapes") }
        val suspended = device.calls.indexOf("suspend")
        assertTrue(flushed in 0 until suspended, "our quads go down before the game's")
        assertEquals("resume", device.calls[suspended + 1])
        assertTrue(lent is RenderFrame)
    }

    /** A backend whose drawing object is opened round a block, the way a sprite batch is. */
    private class Lending(device: GpuDevice, val log: MutableList<String>) : RenderCanvas(device) {
        override fun handOver(projection: FloatArray, viewport: Viewport): Any = "batch"

        override fun lend(lent: Any, projection: FloatArray, block: (Any) -> Unit) {
            log += "open"
            block(lent)
            log += "close"
        }
    }

    @Test
    fun `a backend opens its drawing object inside the lent state and closes it before taking it back`() {
        val log = device.calls
        val canvas = Lending(device, log)
        canvas.begin(design)
        canvas.raw { log += "block with $it" }
        canvas.end()

        val suspended = log.indexOf("suspend")
        assertEquals(listOf("open", "block with batch", "close", "resume"), log.subList(suspended + 1, suspended + 5))
    }

    @Test
    fun `all of a rotated picture draws and part of one is refused`() {
        val turned = Sprite(64, 128)
        val canvas = RenderCanvas(device, textures = TextureResolver { if (it === turned) BoundPicture(sheet, rotated = true) else null })
        canvas.begin(design)
        canvas.image(turned, Rect.of(0f, 0f, 10f, 10f))
        val thrown = assertFailsWith<IllegalStateException> {
            canvas.image(turned, Rect.of(0f, 0f, 10f, 10f), source = Rect.of(0f, 0f, 8f, 8f))
        }
        assertTrue("rotated" in thrown.message.orEmpty(), thrown.message)
    }

    @Test
    fun `a picture the resolver does not know is refused by name`() {
        val canvas = canvas()
        canvas.begin(design)
        val stranger = Sprite(1, 1)
        val thrown = assertFailsWith<IllegalStateException> { canvas.image(stranger, Rect.of(0f, 0f, 1f, 1f)) }
        assertTrue("textures it made" in thrown.message.orEmpty())
    }

    @Test
    fun `a game's picture draws from its own texture and batches with itself`() {
        val canvas = frame {
            repeat(5) { image(sprite, Rect.of(0f, 0f, 10f, 10f)) }
            image(sprite, Rect.of(0f, 0f, 10f, 10f), degrees = 45f, pivotX = 0.5f, pivotY = 0.5f)
        }
        assertEquals(1, canvas.drawCalls)
        assertSame(sheet, device.draws.single().texture)
        assertEquals(6, device.draws.single().quads)
    }

    @Test
    fun `a lost context forgets the white texture and layers and rebuilds on the next frame`() {
        val canvas = canvas()
        frame(canvas) {
            layer(Rect.of(0f, 0f, 8f, 8f)) {}
            rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
        }
        canvas.contextLost()
        assertTrue("contextLost" in device.calls)
        frame(canvas) { rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red) }

        assertEquals(2, device.named("texture(").size, "the white texture was made again")
        assertEquals(0, device.deleted.size, "nothing from the lost context was deleted")
    }

    @Test
    fun `warming up prepares the device and makes the white texture without drawing`() {
        val canvas = canvas()
        assertFalse(canvas.warmedUp)
        canvas.warmUp()
        canvas.warmUp()
        assertTrue(canvas.warmedUp)
        assertEquals(listOf("prepare"), device.named("prepare"))
        assertEquals(0, device.draws.size)
    }
}
