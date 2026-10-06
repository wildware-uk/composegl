package dev.wildware.composegl.render

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a still frame through the renderer allocates, counted the way a phone counts it.
 *
 * Run by `jvmAllocationTest` with escape analysis off: the desktop JVM would otherwise quietly remove
 * the very objects Android's runtime keeps, a list's iterator above all, and these would pass on code
 * that makes thousands of them a frame on a phone.
 *
 * The frame itself still makes a handful of objects that do not depend on what is drawn: `begin`'s
 * state, a layer's state and a clip's corners (#252). So the frame is asked the question that does
 * not move with those: drawing eight times as much in it costs not one byte more. Every glyph and
 * every shape is a lookup of its page's texture, so anything made per lookup shows here at once.
 */
@Tag("allocation")
class FrameAllocationTest {

    /** A device that keeps the textures and pictures it makes and draws nothing: a frame costs only the canvas. */
    private class QuietDevice(inner: RecordingDevice = RecordingDevice()) : GpuDevice by inner {
        override fun begin(into: FrameTarget) = Unit
        override fun end() = Unit
        override fun target(target: FrameTarget, x: Int, y: Int, width: Int, height: Int) = Unit
        override fun scissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun noScissor() = Unit
        override fun clear(red: Float, green: Float, blue: Float, alpha: Float) = Unit

        override fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray) = Unit

        override fun drawShapes(
            vertices: VertexStream,
            quads: Int,
            texture: DeviceTexture,
            blend: Blend,
            projection: FloatArray,
            mask: ClipMask?,
        ) = Unit

        override fun drawShapes(
            vertices: VertexStream,
            quads: Int,
            texture: DeviceTexture,
            blend: Blend,
            projection: FloatArray,
            mask: ClipMask?,
            program: ShapeProgram,
        ) = Unit

        override fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend) = Unit
    }

    /** Every glyph a solid block, so the atlas has something real on it. */
    private class BlockFace(private val pixels: Int) : RasterFace {
        override val ascent = pixels * 0.75f
        override val descent = pixels * 0.25f
        override val capHeight = pixels * 0.7f

        override fun has(codepoint: Int) = true

        override fun advance(codepoint: Int) = pixels * 0.625f

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            into.resize(pixels / 2, pixels, GlyphKind.Coverage)
            into.pixels.fill(-1, 0, (pixels / 2) * pixels)
            into.yOffset = -ascent
            return true
        }
    }

    private class Fonts : AtlasFonts(GlyphRasteriser { _, size -> BlockFace(size) }, atlasOwner = "FrameAllocationTest") {
        init {
            registerFont("body", listOf(16))
        }
    }

    private val fonts = Fonts()
    private val device = QuietDevice()
    private val canvas = RenderCanvas(device, fonts)
    private val label = fonts.measure("Allocation free", TextStyle(family = "body", size = 16f))

    private val panel = Rect.of(10f, 10f, 200f, 40f)
    private val rounded = Rect.of(10f, 60f, 200f, 40f)
    private val clip = Rect.of(0f, 0f, 400f, 300f)
    private val layer = Rect.of(20f, 20f, 300f, 200f)

    /** How many times [unit] is drawn in each place, set before each frame; read by the layer's block. */
    private var times = 1

    /** Made once, so the frame does not make it. */
    private val insideLayer: () -> Unit = { repeat(times) { unit() } }

    /** One unit of a screen: a panel, a rounded panel with a border, and a label on each. */
    private fun unit() {
        canvas.rect(panel, Colour(0xFF203040.toInt()))
        canvas.text(label, 14f, 14f, Colour.White)
        canvas.rect(rounded, Colour(0xFF405060.toInt()), 8f)
        canvas.border(rounded, Colour.White, 2f, 8f)
        canvas.text(label, 14f, 64f, Colour.White)
    }

    /** A frame: [times] units on the screen, [times] inside a clip, [times] in a layer. */
    private fun frame(viewport: Viewport) {
        canvas.begin(viewport)
        repeat(times) { unit() }
        canvas.pushClip(clip)
        repeat(times) { unit() }
        canvas.popClip()
        canvas.layer(layer, insideLayer)
        canvas.end()
    }

    /** What one frame of [units] units costs, after the canvas has seen it a few times. */
    private fun perFrame(viewport: Viewport, units: Int): Long {
        times = units
        repeat(20) { frame(viewport) }
        return Allocation.leastOf { repeat(10) { frame(viewport) } } / 10
    }

    private fun viewport(scale: Float) = Viewport(Size(640f, 360f), Size(640f * scale, 360f * scale), ScalePolicy.Fit)

    @Test
    fun `a still frame costs the same however much is drawn in it`() {
        val viewport = viewport(1f)
        val one = perFrame(viewport, units = 1)
        val eight = perFrame(viewport, units = 8)

        assertEquals(one, eight, "eight times the drawing should cost not one byte more a frame")
    }

    @Test
    fun `a still frame scaled up costs the same however much is drawn in it`() {
        // Twice the design size: every glyph is a sharp copy made at the screen's size, and each is
        // drawn through its own lookup of its page's texture.
        val viewport = viewport(2f)
        val one = perFrame(viewport, units = 1)
        val eight = perFrame(viewport, units = 8)

        assertEquals(one, eight, "eight times the drawing should cost not one byte more a frame")
    }

    @Test
    fun `a page's texture is found without allocating`() {
        val page = fonts.atlas.white.page
        val other = RecordingDevice()
        // Two devices' textures on the page, so finding one is a search.
        page.texture(other)
        page.texture(device)
        repeat(100) { page.texture(device) }

        assertEquals(0L, Allocation.leastOf { repeat(1_000) { page.texture(device) } })
    }

    @Test
    fun `the layer pool hands pictures out and takes them back without allocating`() {
        val layers = LayerPool(device)
        val cycle = {
            val outer = layers.acquire(200, 100)
            val inner = layers.acquire(64, 64)
            layers.release(inner)
            layers.hold(outer)
            layers.release(outer)
            layers.release(outer)
            layers.trim()
        }
        repeat(100) { cycle() }

        assertEquals(0L, Allocation.leastOf { repeat(1_000) { cycle() } })
        assertEquals(2, layers.size, "the same two pictures, kept")
    }
}
