package dev.wildware.composegl.gdx

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.GLVersion
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

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

    /**
     * Nobody calls [GdxCanvas.contextLost] on a phone: the canvas notices by itself. On Android,
     * LibGDX makes a new [GLVersion] each time it gets a new context, so a frame that finds a
     * different one rebuilds. Here the app says it is Android and the version object is swapped,
     * and the count of programs made shows whether the canvas built its shaders again.
     */
    @Test
    fun `on Android a new GL version object makes the next frame rebuild`() = Gl.render {
        val realApp = Gdx.app
        val realGraphics = Gdx.graphics
        val realGl = Gdx.gl
        var version = GLVersion(Application.ApplicationType.Android, "OpenGL ES 3.0", "test", "test")
        var programs = 0

        Gdx.app = delegate(realApp) { name -> if (name == "getType") Application.ApplicationType.Android else null }
        Gdx.graphics = delegate(realGraphics) { name -> if (name == "getGLVersion") version else null }
        Gdx.gl = Proxy.newProxyInstance(javaClass.classLoader, realGl.javaClass.interfaces) { _, method, args ->
            if (method.name == "glCreateProgram") programs++
            try {
                method.invoke(realGl, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as com.badlogic.gdx.graphics.GL20
        val canvas = GdxCanvas()

        fun frame() {
            canvas.begin(viewport)
            canvas.rect(Rect.of(10f, 10f, 200f, 60f), Colour.rgb(0x3366CC), corner = 8f)
            canvas.end()
        }

        try {
            frame()
            val built = programs
            assertTrue(built > 0, "the first frame should build the shape program")
            frame()
            assertEquals(built, programs, "the same context should not be built for again")
            version = GLVersion(Application.ApplicationType.Android, "OpenGL ES 3.0", "test", "test")
            frame()
            assertEquals(built * 2, programs, "a new context should be built for again")
        } finally {
            canvas.dispose()
            Gdx.gl = realGl
            Gdx.graphics = realGraphics
            Gdx.app = realApp
        }
    }

    private inline fun <reified T : Any> delegate(real: T, crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name) ?: try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}
