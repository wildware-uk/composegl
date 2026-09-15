package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * Android takes the GL context away when an app is paused, and LibGDX rebuilds only what it
 * manages. The shared renderer's programs, buffers and atlas pages are not LibGDX's, so a canvas
 * told the context was lost has to build them all again from memory — and draw the same picture.
 */
class GdxContextLossTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    @Test
    fun `a canvas told its context was lost rebuilds and draws the same frame`() = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
        val canvas = GdxCanvas(fonts = fonts)

        fun frame(): ByteArray {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            canvas.rect(Rect.of(10f, 10f, 200f, 60f), Colour.rgb(0x3366CC), corner = 8f)
            canvas.text(fonts.measure("Resumed", TextStyle(family = "body", size = 16f)), Offset(20f, 30f), Colour.White)
            val layer = checkNotNull(canvas.layer(Rect.of(10f, 100f, 80f, 80f)) { canvas.rect(Rect.of(20f, 110f, 40f, 40f), Colour.Red) })
            canvas.drawLayer(layer, Rect.of(10f, 100f, 80f, 80f))
            canvas.end()
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            try {
                return ByteArray(pixmap.pixels.remaining()).also { pixmap.pixels.duplicate().get(it) }
            } finally {
                pixmap.dispose()
            }
        }

        try {
            val before = frame()
            canvas.contextLost()
            val after = frame()
            assertArrayEquals(before, after, "the frame after a lost context should be pixel for pixel the same")
        } finally {
            canvas.dispose()
            fonts.dispose()
        }
    }
}
