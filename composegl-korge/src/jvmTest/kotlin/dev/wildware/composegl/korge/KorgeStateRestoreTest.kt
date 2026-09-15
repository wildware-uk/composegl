package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.slice
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import org.junit.jupiter.api.Test

/**
 * KorGE remembers the GL state it last set and believes it. The interface draws straight through
 * OpenGL in between, so this is the proof it hands every piece back: KorGE sprites drawn before the
 * interface, and after it — with a layer, an effect and a game picture in between — all come out
 * where and how KorGE meant them.
 */
class KorgeStateRestoreTest {

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    private fun solid(colour: RGBA) = Bitmap32(4, 4, premultiplied = true).also { bitmap ->
        for (y in 0 until 4) for (x in 0 until 4) bitmap.setRgbaRaw(x, y, colour)
    }

    private fun sprite(ctx: RenderContext, bitmap: Bitmap32, x: Float, y: Float) {
        ctx.useBatcher { batch -> batch.drawQuad(ctx.getTex(bitmap.slice()), x = x, y = y, width = 40f, height = 40f, filtering = false) }
    }

    @Test
    fun `KorGE sprites before and after the interface come out right`() {
        val red = solid(RGBA(255, 0, 0, 255))
        val green = solid(RGBA(0, 255, 0, 255))
        val canvas = KorgeCanvas()
        try {
            val pixels = KorgeGl.picture { ctx ->
                sprite(ctx, red, 10f, 10f)

                canvas.begin(viewport, ctx)
                canvas.rect(Rect.of(100f, 10f, 40f, 40f), Colour.rgb(0x0000FF))
                canvas.pushClip(Rect.of(0f, 0f, 300f, 300f))
                canvas.image(KorgeTexture(green), Rect.of(160f, 10f, 20f, 20f))
                val bounds = Rect.of(200f, 10f, 40f, 40f)
                val layer = checkNotNull(canvas.layer(bounds) { canvas.rect(bounds, Colour.rgb(0xFFFF00)) })
                canvas.drawLayer(layer, bounds, passThrough)
                canvas.popClip()
                canvas.end()

                // The same texture KorGE bound before the interface, then a new one, then a quad past
                // the interface's clip: its texture units, scissor and blending must all be what it thinks.
                sprite(ctx, red, 10f, 100f)
                sprite(ctx, green, 100f, 100f)
                sprite(ctx, red, 340f, 340f)
                // Onto the picture before it is read back.
                ctx.flush()
            }

            assertColour(Red, pixels.at(30, 30), "the sprite before the interface")
            assertColour(Blue, pixels.at(120, 30), "the interface's box")
            assertColour(Green, pixels.at(170, 20), "the game picture drawn by the interface")
            assertColour(Rgb(1f, 1f, 0f), pixels.at(220, 30), "the layer through an effect")
            assertColour(Red, pixels.at(30, 120), "the same KorGE texture again, after the interface")
            assertColour(Green, pixels.at(120, 120), "another KorGE texture after the interface")
            assertColour(Red, pixels.at(360, 360), "outside the interface's last clip: KorGE's scissor is its own again")
            assertColour(Black, pixels.at(60, 60), "and nothing where nothing was drawn")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a raw block draws with KorGE's own state and the interface carries on after it`() {
        val red = solid(RGBA(255, 0, 0, 255))
        val canvas = KorgeCanvas()
        try {
            val pixels = KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.rect(Rect.of(10f, 10f, 40f, 40f), Colour.rgb(0x0000FF))
                canvas.raw { lent -> sprite(lent as RenderContext, red, 100f, 10f) }
                canvas.rect(Rect.of(200f, 10f, 40f, 40f), Colour.rgb(0x00FF00))
                canvas.end()
                ctx.flush()
            }

            assertColour(Blue, pixels.at(30, 30), "before raw")
            assertColour(Red, pixels.at(120, 30), "KorGE's sprite from inside raw")
            assertColour(Green, pixels.at(220, 30), "after raw")
        } finally {
            canvas.close()
        }
    }
}
