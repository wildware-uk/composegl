package dev.wildware.composegl.webgl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlslDialect
import dev.wildware.composegl.render.gl.HostState
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.Text
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A `SceneView` in a browser tab, judged by the pixels on the page's canvas. Run on WebGL 2 and,
 * by `wasmJsBrowserWebGl1Test`, on WebGL 1, whose depth buffer is the 16-bit kind.
 *
 * The WebGL frontend hands a scene the same [WebGlFrame] it hands `raw`, with the picture bound.
 * These are the proof that the colour lands in the panel the right way up, that the nearer of two
 * triangles wins, and that a widget drawn after a scene that left its state behind draws exactly as
 * it does with no scene at all.
 */
class WebGlSceneViewTest {

    private val width = 240
    private val height = 200

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)

    private val panel = Rect.of(20f, 30f, 120f, 80f)

    private suspend fun <T> withBackend(block: (WebGlBackend) -> T): T {
        val element = pageCanvas(width, height)
        val backend = WebGlBackend(element, testFonts(), preserveDrawingBuffer = true)
        try {
            return block(backend)
        } finally {
            backend.close()
            element.remove()
        }
    }

    private fun open(backend: UiBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(width.toFloat(), height.toFloat()), backend, content = content)

    /** The scene view where [panel] says, then a green box and a label drawn after it. */
    @Composable
    private fun Screen(state: SceneViewState?, draw: SceneDrawScope.() -> Unit) {
        Box(Modifier.size(width.toFloat(), height.toFloat())) {
            if (state != null) {
                SceneView(state, Modifier.offset(panel.left, panel.top).size(panel.width, panel.height).testTag("scene"), draw = draw)
            }
            Box(Modifier.offset(160f, 30f).size(60f, 60f).background(green))
            Text("After", Modifier.offset(160f, 120f), textStyle = TextStyle(family = "body", size = 16f), colour = white)
        }
    }

    private fun frame(ui: UiTest, backend: WebGlBackend): IntArray = frame(ui, backend.gl)

    private fun frame(ui: UiTest, gl: GL): IntArray {
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT)
        ui.render()
        return readFrame(gl, width, height)
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    @Test
    fun `a scene's colour fills its panel the right way up and nothing outside it`() = runTest(timeout = 2.minutes) {
        withBackend { backend ->
            val state = SceneViewState()
            var lent: Any? = null
            open(backend) {
                Screen(state) {
                    clear(red)
                    raw { frame ->
                        lent = frame
                        val gl = (frame as WebGlFrame).gl
                        // WebGL counts rows up from the bottom: the bottom quarter, in blue.
                        gl.enable(GL.SCISSOR_TEST)
                        gl.scissor(0, 0, width, height / 4)
                        gl.clearColor(0f, 0f, 1f, 1f)
                        gl.clear(GL.COLOR_BUFFER_BIT)
                    }
                }
            }.use { ui ->
                val pixels = frame(ui, backend)

                assertTrue(lent is WebGlFrame, "the WebGL frontend hands a scene a WebGlFrame, not $lent")
                assertEquals(GL.NO_ERROR, backend.gl.getError(), "a picture with depth, cleared and drawn, is legal WebGL")
                assertEquals(120 to 80, state.width to state.height)
                for (y in 31 until 89) for (x in 21 until 139) {
                    assertEquals(red.code(), pixels.at(x, y), "the top of the panel at $x, $y")
                }
                assertEquals(blue.code(), pixels.at(80, 105), "the bottom of the panel")
                for (y in 0 until height) for (x in 0 until width) {
                    val inside = x in 19..140 && y in 29..110
                    if (!inside) {
                        assertTrue(pixels.at(x, y) != red.code() && pixels.at(x, y) != blue.code(), "the scene leaked out at $x, $y")
                    }
                }
                assertEquals(green.code(), pixels.at(190, 60), "the box after it, whole: the scene's scissor went no further")
            }
            state.release()
        }
    }

    @Test
    fun `the nearer triangle wins inside a scene view whichever order they are drawn in`() = runTest(timeout = 2.minutes) {
        withBackend { backend ->
            val state = SceneViewState()
            var farFirst = true
            open(backend) {
                Screen(state) {
                    clear(green)
                    raw { frame ->
                        WebGlDepthScene(WebGl((frame as WebGlFrame).gl)).use { scene ->
                            if (farFirst) {
                                scene.triangle(depth = 0.6f, colour = blue)
                                scene.triangle(depth = -0.6f, colour = red)
                            } else {
                                scene.triangle(depth = -0.6f, colour = red)
                                scene.triangle(depth = 0.6f, colour = blue)
                            }
                        }
                    }
                }
            }.use { ui ->
                assertEquals(red.code(), frame(ui, backend).at(80, 70), "the far triangle was drawn first")
                farFirst = false
                state.invalidate()
                assertEquals(red.code(), frame(ui, backend).at(80, 70), "the far triangle was drawn last")
            }
            state.release()
        }
    }

    /** What a careless renderer leaves behind: its program, depth test, culling, a scissor, a viewport, a texture unit. */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw { frame ->
            val gl = (frame as WebGlFrame).gl
            WebGlDepthScene(WebGl(gl)).triangle(depth = 0f, colour = blue)
            gl.enable(GL.DEPTH_TEST)
            gl.enable(GL.CULL_FACE)
            gl.enable(GL.SCISSOR_TEST)
            gl.scissor(0, 0, 1, 1)
            gl.viewport(0, 0, 1, 1)
            gl.activeTexture(GL.TEXTURE3)
        }
    }

    @Test
    fun `a widget drawn after a careless scene is exactly what it is with no scene`() = runTest(timeout = 2.minutes) {
        val after = withBackend { backend ->
            val state = SceneViewState()
            open(backend) { Screen(state, careless) }.use { ui ->
                frame(ui, backend).also {
                    // What HostState.Leave promises the page once the frame is over.
                    assertFalse(backend.gl.isEnabled(GL.DEPTH_TEST), "depth test")
                    assertFalse(backend.gl.isEnabled(GL.CULL_FACE), "culling")
                    assertFalse(backend.gl.isEnabled(GL.SCISSOR_TEST), "scissor")
                    assertEquals(GL.NO_ERROR, backend.gl.getError())
                }
            }.also { state.release() }
        }
        val alone = withBackend { backend -> open(backend) { Screen(null) { } }.use { ui -> frame(ui, backend) } }

        assertEquals(blue.code(), after.at(80, 70), "the careless scene did draw")
        var lit = 0
        for (y in 0 until height) for (x in 150 until width) {
            assertEquals(alone.at(x, y), after.at(x, y), "the box and the label after the scene, at $x, $y")
            if (alone.at(x, y) == white.code()) lit++
        }
        assertTrue(lit > 10, "the label was drawn at all: $lit white pixels")
    }

    /**
     * A library sharing the page's context that remembers the WebGL state it set and skips setting it
     * again, as three.js does. This is the part of it that matters here.
     */
    private class Remembering(private val gl: GL) {
        var blend: Boolean? = null
            private set
        var depthTest: Boolean? = null
            private set

        fun blend(on: Boolean) {
            if (blend == on) return
            if (on) gl.enable(GL.BLEND) else gl.disable(GL.BLEND)
            blend = on
        }

        fun depthTest(on: Boolean) {
            if (depthTest == on) return
            if (on) gl.enable(GL.DEPTH_TEST) else gl.disable(GL.DEPTH_TEST)
            depthTest = on
        }
    }

    @Test
    fun `a library that remembers WebGL state is still right about it after a scene with HostState Restore`() = runTest(timeout = 2.minutes) {
        withBackend { backend ->
            val gl = backend.gl
            WebGlCanvas(gl, backend.fonts, HostState.Restore).use { canvas ->
                val sharing = object : UiBackend by backend {
                    override val canvas = canvas
                }
                val library = Remembering(gl)
                val state = SceneViewState()
                var blendInside: Boolean? = null
                open(sharing) {
                    Screen(state) {
                        clear(red)
                        raw {
                            // What the library believes has to be what is in force while it draws...
                            blendInside = gl.isEnabled(GL.BLEND)
                            // ...and what it sets has to stay set once the scene is over.
                            library.depthTest(true)
                        }
                    }
                }.use { ui ->
                    library.blend(false)
                    library.depthTest(false)

                    val pixels = frame(ui, gl)

                    assertEquals(false, blendInside, "the library switched blending off before the interface")
                    assertEquals(library.depthTest, gl.isEnabled(GL.DEPTH_TEST), "the depth test the library set inside the scene")
                    assertEquals(library.blend, gl.isEnabled(GL.BLEND), "the blending the library set before it")
                    assertEquals(red.code(), pixels.at(80, 70), "the scene")
                    assertEquals(green.code(), pixels.at(190, 60), "the box after it")
                    assertEquals(GL.NO_ERROR, gl.getError())
                }
                state.release()
            }
        }
    }

    @Test
    fun `a click or a pad press that asks for the scene again shows the new colour`() = runTest(timeout = 2.minutes) {
        withBackend { backend ->
            val state = SceneViewState()
            open(backend) {
                var colour by remember { mutableStateOf(red) }
                Screen(state) { clear(colour) }
                Box(
                    Modifier.offset(20f, 150f).size(60f, 30f).background(white)
                        .focusable(initial = true)
                        .clickable {
                            colour = if (colour == red) blue else red
                            state.invalidate()
                        }
                        .testTag("next"),
                )
            }.use { ui ->
                assertEquals(red.code(), frame(ui, backend).at(80, 70))

                ui.click("next")
                assertEquals(blue.code(), frame(ui, backend).at(80, 70), "after the click")
                assertEquals(blue.code(), frame(ui, backend).at(80, 70), "and it stays")
                assertEquals(2L, state.draws, "a frame nobody asked to redraw renders nothing")

                ui.pad(GamepadButton.South)
                assertEquals(red.code(), frame(ui, backend).at(80, 70), "after South on the pad")
                assertEquals(3L, state.draws)
            }
            state.release()
        }
    }
}

/**
 * A game's own renderer, in miniature: flat triangles at a depth, through a shader of its own, with
 * the depth test switched on. The desktop suites have the same one.
 */
private class WebGlDepthScene(private val gl: WebGl) : AutoCloseable {

    private val dialect = GlslDialect.of(gl.profile)
    private val arrays = GlDevice(gl).usesVertexArrays

    private val program = link()
    private val colourAt = gl.getUniformLocation(program, "u_colour")
    private val array = if (arrays) gl.createVertexArray() else 0
    private val vertices = gl.createBuffer()
    private val indices = gl.createBuffer()

    init {
        if (arrays) gl.bindVertexArray(array)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indices)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, gl.shorts(3).also { it[0] = 0; it[1] = 1; it[2] = 2 }, 3, GlConst.STATIC_DRAW)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        if (arrays) gl.bindVertexArray(0)
        gl.enable(GlConst.DEPTH_TEST)
        gl.depthMask(true)
    }

    fun triangle(depth: Float, colour: Colour) {
        val points = gl.floats(9)
        put(points, 0, -1f, -1f, depth)
        put(points, 1, 3f, -1f, depth)
        put(points, 2, -1f, 3f, depth)

        gl.useProgram(program)
        gl.uniform4f(colourAt, colour.red / 255f, colour.green / 255f, colour.blue / 255f, 1f)
        if (arrays) gl.bindVertexArray(array)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, vertices)
        gl.bufferData(GlConst.ARRAY_BUFFER, points, 9, GlConst.STREAM_DRAW)
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 3, GlConst.FLOAT, false, 3 * 4, 0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indices)
        gl.drawElements(GlConst.TRIANGLES, 3, GlConst.UNSIGNED_SHORT, 0)
        if (!arrays) gl.disableVertexAttribArray(0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        if (arrays) gl.bindVertexArray(0)
    }

    private fun put(points: GlFloats, corner: Int, x: Float, y: Float, z: Float) {
        points[corner * 3] = x
        points[corner * 3 + 1] = y
        points[corner * 3 + 2] = z
    }

    private fun link(): Int {
        val vertex = compile(GlConst.VERTEX_SHADER, dialect.vertex("attribute vec3 a_position;\nvoid main() { gl_Position = vec4(a_position, 1.0); }"))
        val fragment = compile(
            GlConst.FRAGMENT_SHADER,
            dialect.fragment("uniform vec4 u_colour;\nvoid main() { gl_FragColor = u_colour; }", highPrecision = true),
        )
        val program = gl.createProgram()
        gl.attachShader(program, vertex)
        gl.attachShader(program, fragment)
        gl.bindAttribLocation(program, 0, "a_position")
        gl.linkProgram(program)
        check(gl.programLinked(program)) { "the test's own shader would not link: ${gl.programInfoLog(program)}" }
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = gl.createShader(type)
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        check(gl.shaderCompiled(shader)) { "the test's own shader would not compile: ${gl.shaderInfoLog(shader)}\n$source" }
        return shader
    }

    override fun close() {
        gl.disable(GlConst.DEPTH_TEST)
        gl.useProgram(0)
        gl.deleteProgram(program)
        gl.deleteBuffer(vertices)
        gl.deleteBuffer(indices)
        if (arrays) gl.deleteVertexArray(array)
    }
}
