package dev.wildware.composegl.gdx

import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlslDialect
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which OpenGL the binding says this is, which decides the shaders and the vertex path.
 *
 * On GL 2 LibGDX offers no `Gdx.gl30`, so there is no call to make a vertex array object with and
 * the shaders compile as written, as they always have. On a GL 3.2 core context — `testGl30` — the
 * shaders must be `#version 150`, which is what a Mac insists on and Mesa merely forgives.
 */
class GdxGlTest {

    @Test
    fun `the binding picks the shaders and the vertex path for the context it is on`() = Gl.render {
        val canvas = GdxCanvas()
        try {
            canvas.begin(Viewport.oneToOne(Size(Gl.size.toFloat(), Gl.size.toFloat())))
            canvas.rect(Rect.of(0f, 0f, 10f, 10f), Colour.Red)
            canvas.end()
            val device = canvas.device as GlDevice

            assertEquals(if (Gl.gl30) GlslDialect.Core150 else GlslDialect.Legacy, device.dialect)
            assertEquals(Gl.gl30, device.usesVertexArrays)
        } finally {
            canvas.dispose()
        }
    }
}
