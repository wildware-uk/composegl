package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Issue #188: on GL 3 and OpenGL ES 3 the interface drew nothing at all.
 *
 * The batch handed its vertices to the driver as a pointer with no vertex array object bound, which
 * a GL 3.2 core context refuses — "GL_INVALID_OPERATION in glVertexAttribPointer(no array object
 * bound)" — so every quad was dropped. Run by `testGl30` on a core context and by `test` on GL 2,
 * where it has to go on drawing exactly as it always did.
 *
 * Drawn the way a game draws: its own `SpriteBatch` first, which on GL 3 leaves a buffer of its own
 * bound, and the interface on top.
 */
class Gl30Test {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private class Frame(val pixels: Pixmap, val errors: List<Int>)

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun draw(content: GdxCanvas.() -> Unit): Frame = Gl.render {
        val world = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply { setColor(Color.GREEN); fill() }
        val worldTexture = Texture(world)
        val batch = SpriteBatch()
        val canvas = GdxCanvas(batch)
        try {
            drainErrors()
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            // The game world.
            batch.begin()
            batch.draw(worldTexture, 300f, 300f, 50f, 50f)
            batch.end()

            canvas.begin(viewport)
            canvas.content()
            canvas.end()

            Frame(Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size), drainErrors())
        } finally {
            canvas.dispose()
            batch.dispose()
            worldTexture.dispose()
            world.dispose()
        }
    }

    private fun drainErrors(): List<Int> = generateSequence { Gdx.gl.glGetError().takeIf { it != GL20.GL_NO_ERROR } }
        .take(16)
        .toList()

    @Test
    fun `a panel draws over the game world with no GL error`() {
        val frame = draw { rect(Rect.of(10f, 20f, 100f, 50f), Colour.rgb(0xFF0000)) }

        assertEquals(emptyList<Int>(), frame.errors, "GL errors while drawing (gl30=${Gl.gl30})")
        val panel = frame.pixels.at(50, 40)
        assertTrue(panel.r > 0.9f && panel.g < 0.1f, "the panel should be red, got $panel (gl30=${Gl.gl30})")
        val world = frame.pixels.at(325, Gl.size - 325)
        assertTrue(world.g > 0.9f && world.r < 0.1f, "the game world should still be green, got $world")
    }

    @Test
    fun `an effect and a layer draw with no GL error`() {
        val passThrough = ShaderEffect(
            ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
        )
        val bounds = Rect.of(40f, 40f, 120f, 120f)
        val frame = draw {
            val picture = checkNotNull(layer(bounds) { rect(Rect.of(60f, 60f, 60f, 60f), Colour.rgb(0xFF0000)) })
            drawLayer(picture, bounds, passThrough)
        }

        assertEquals(emptyList<Int>(), frame.errors, "GL errors while drawing (gl30=${Gl.gl30})")
        val middle = frame.pixels.at(90, 90)
        assertTrue(middle.r > 0.9f, "the layer through the effect should be red, got $middle (gl30=${Gl.gl30})")
    }
}
