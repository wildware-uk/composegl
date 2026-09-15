package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.graphics.readColor
import korlibs.image.bitmap.Bitmap32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Drawing the interface into a texture a KorGE game puts in its world, instead of into the window.
 *
 * The questions the LibGDX render target answers, asked again: is a panel in the world the same pixels
 * as the same panel on the HUD, does what is inside it stay inside it, is it premultiplied, does
 * resizing leave textures behind — and, for KorGE, does a view show it where the game put it.
 */
class KorgeRenderTargetTest {

    private val size = 128

    private val panel = Rect.of(20f, 16f, 88f, 72f)

    /** The same drawing either way: an edge, a corner and an overlap. */
    private fun KorgeCanvas.scene() {
        rect(panel, Colour.rgb(0x3366CC), corner = 8f)
        rect(Rect.of(40f, 30f, 108f, 44f), Colour.rgb(0xCC5522), corner = 0f)
        border(panel, Colour.rgb(0xFFFFFF), width = 2f, corner = 8f)
    }

    private fun assertClose(expected: Int, actual: Int, because: String) {
        val off = (0..2).maxOf { channel -> abs(((expected shr (channel * 8)) and 0xFF) - ((actual shr (channel * 8)) and 0xFF)) }
        assertTrue(off <= 2, "$because: expected about ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}")
    }

    /** RGB of a pixel as 0xRRGGBB, from a picture read top row first. */
    private fun Bitmap32.rgb(x: Int, y: Int): Int = getRgbaRaw(x, y).let { (it.r shl 16) or (it.g shl 8) or it.b }

    @Test
    fun `a panel in the world is the same pixels as the same panel on the hud`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(size, size)
        try {
            val stretch = Viewport(Size(size.toFloat(), size.toFloat()), Size(size.toFloat(), size.toFloat()), ScalePolicy.Stretch)
            val onScreen = KorgeGl.picture(size, size) { ctx ->
                canvas.begin(stretch, ctx)
                canvas.scene()
                canvas.end()
            }
            val inTheWorld = KorgeGl.render { ctx ->
                target.draw(canvas, ctx, clear = Colour.rgb(0x000000)) { canvas.scene() }
                target.read(ctx)
            }

            for (y in 0 until size) for (x in 0 until size) {
                assertClose(onScreen.rgb(x, y), inTheWorld.rgb(x, y), "pixel ($x, $y)")
            }
            assertClose(0x3366CC, inTheWorld.rgb(24, 80), "and it is not a blank match")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `an effect inside a panel in the world draws into the panel, not the window`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(size, size)
        try {
            val (inside, window) = KorgeGl.render { ctx ->
                target.draw(canvas, ctx, clear = Colour.rgb(0x000000)) {
                    val bounds = Rect.of(0f, 0f, size.toFloat(), size.toFloat())
                    val picture = canvas.layer(bounds) { canvas.rect(panel, Colour.rgb(0x3366CC)) }
                    canvas.drawLayer(checkNotNull(picture) { "this driver gave us no layer" }, bounds)
                }
                // Read in the same frame, before the stage is drawn over it again.
                val window = Bitmap32(ctx.mainFrameBuffer.width, ctx.mainFrameBuffer.height, premultiplied = false)
                ctx.ag.readColor(ctx.mainFrameBuffer, window)
                target.read(ctx) to window
            }

            assertClose(0x3366CC, inside.rgb(60, 50), "the rectangle should have landed in the panel")
            assertClose(0x000000, window.rgb(60, 50), "and nothing should have landed on the window")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `what comes out is premultiplied`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(32, 32)
        try {
            val pixel = KorgeGl.render { ctx ->
                target.draw(canvas, ctx) {
                    canvas.rect(Rect.of(0f, 0f, 32f, 32f), Colour.White.scaleAlpha(0.5f), corner = 0f)
                }
                target.read(ctx).getRgbaRaw(16, 16)
            }

            assertTrue(abs(pixel.a - 128) <= 4, "half transparent should stay half transparent: ${pixel.a}")
            assertTrue(abs(pixel.r - 128) <= 6, "the colour should already be scaled by the alpha: ${pixel.r}")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `the picture comes out the right way up`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(64, 64)
        try {
            val picture = KorgeGl.render { ctx ->
                target.draw(canvas, ctx, clear = Colour.rgb(0x000000)) {
                    canvas.rect(Rect.of(0f, 0f, 64f, 16f), Colour.rgb(0xFF0000))
                }
                target.read(ctx)
            }

            assertClose(0xFF0000, picture.rgb(32, 4), "the top row read first is the top of the design")
            assertClose(0x000000, picture.rgb(32, 60), "and the bottom is empty")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `resizing reuses one framebuffer and draws at the new size`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(64, 64)
        try {
            val buffer = target.frameBuffer
            // A window being dragged by a corner, fifty frames of it.
            repeat(50) { target.resize(64 + it, 48 + it) }

            assertEquals(113, target.width)
            assertEquals(97, target.height)
            assertTrue(buffer === target.frameBuffer, "the same framebuffer, resized, rather than fifty left behind")

            val picture = KorgeGl.render { ctx ->
                target.draw(canvas, ctx, clear = Colour.rgb(0x000000)) {
                    canvas.rect(Rect.of(0f, 0f, 113f, 97f), Colour.rgb(0xFF0000))
                }
                target.read(ctx)
            }
            assertEquals(113, picture.width)
            assertEquals(97, picture.height)
            assertClose(0xFF0000, picture.rgb(110, 94), "the far corner of the new size is drawn")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `a KorGE view shows the picture where the game put it, the right way up`() {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(100, 100)
        val view = KorgeRenderTargetView(target)
        try {
            KorgeGl.render { ctx ->
                target.draw(canvas, ctx, clear = Colour.rgb(0x000000)) {
                    canvas.rect(Rect.of(0f, 0f, 100f, 50f), Colour.rgb(0xFF0000))
                    canvas.rect(Rect.of(0f, 50f, 100f, 50f), Colour.rgb(0x0000FF))
                }
                view.x = 150.0
                view.y = 150.0
                KorgeGl.stage.addChild(view)
            }
            KorgeGl.frames(3)
            val window = KorgeGl.window()

            assertColour(Red, window.at(200, 170), "the top half of the picture at the top of the view")
            assertColour(Blue, window.at(200, 230), "the bottom half underneath")
            assertColour(Black, window.at(140, 200), "nothing left of the view")
        } finally {
            KorgeGl.render { view.removeFromParent() }
            target.close()
            canvas.close()
        }
    }
}
