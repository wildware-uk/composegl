package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlslDialect
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
import korlibs.graphics.gl.AGOpengl
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.slice
import korlibs.image.color.RGBA
import korlibs.korge.blend.BlendMode
import korlibs.korge.render.RenderContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `SceneView` inside a KorGE render, judged by the pixels.
 *
 * KorGE hands a scene the frame's [RenderContext], as it does for `raw`, with the picture on top of
 * the context's framebuffer stack: so KorGE's own batch, and anything else that draws through the
 * context, lands in the panel. KorGE believes the GL state it last set, so these also prove it is
 * handed every piece back: KorGE sprites drawn before and after the interface, with a careless scene
 * in between, come out where KorGE meant them.
 */
class KorgeSceneViewTest {

    private val size = KorgeGl.size

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)

    private val panel = Rect.of(40f, 60f, 120f, 80f)

    private fun backend() = KorgeBackend(KorgeFonts().also { it.registerTrueType("default", TestFonts.dejaVu(), listOf(16)) })

    private fun open(backend: KorgeBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(size.toFloat(), size.toFloat()), backend, content = content)

    /** The scene view where [panel] says, then a green box and a label drawn after it. */
    @Composable
    private fun Screen(state: SceneViewState?, draw: SceneDrawScope.() -> Unit) {
        Box(Modifier.size(size.toFloat(), size.toFloat())) {
            if (state != null) {
                SceneView(state, Modifier.offset(panel.left, panel.top).size(panel.width, panel.height).testTag("scene"), draw = draw)
            }
            Box(Modifier.offset(200f, 60f).size(80f, 80f).background(green))
            Text("After", Modifier.offset(200f, 160f), textStyle = TextStyle(family = "default", size = 16f), colour = white)
        }
    }

    /** One frame of [ui] inside a KorGE render, with [before] and [after] drawn by KorGE around it. */
    private fun frame(
        ui: UiTest,
        backend: KorgeBackend,
        before: (RenderContext) -> Unit = {},
        after: (RenderContext) -> Unit = {},
    ): Bitmap32 = KorgeGl.picture { ctx ->
        before(ctx)
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
        after(ctx)
        ctx.flush()
    }

    private fun Bitmap32.code(x: Int, y: Int): Int = this[x, y].let { (it.r shl 16) or (it.g shl 8) or it.b }

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    private fun solid(colour: RGBA) = Bitmap32(4, 4, premultiplied = true).also { bitmap ->
        for (y in 0 until 4) for (x in 0 until 4) bitmap.setRgbaRaw(x, y, colour)
    }

    private fun sprite(
        ctx: RenderContext,
        bitmap: Bitmap32,
        x: Float,
        y: Float,
        width: Float = 40f,
        height: Float = 40f,
        blend: BlendMode = BlendMode.NORMAL,
    ) {
        ctx.useBatcher { batch ->
            batch.drawQuad(ctx.getTex(bitmap.slice()), x = x, y = y, width = width, height = height, filtering = false, blendMode = blend)
        }
    }

    @Test
    fun `it says it renders scenes`() {
        KorgeCanvas().use { assertTrue(it.drawsScenes) }
    }

    @Test
    fun `a scene's colour fills its panel and nothing outside it`() {
        val backend = backend()
        val state = SceneViewState()
        try {
            open(backend) { Screen(state) { clear(red) } }.use { ui ->
                val pixels = frame(ui, backend)

                assertEquals(1L, state.draws)
                assertEquals(120 to 80, state.width to state.height)
                for (y in 61 until 139) for (x in 41 until 159) {
                    assertEquals(red.code(), pixels.code(x, y), "inside the panel at $x, $y")
                }
                for (y in 0 until size) for (x in 0 until size) {
                    val inside = x in 39..160 && y in 59..140
                    if (!inside) assertTrue(pixels.code(x, y) != red.code(), "the scene leaked out of its panel at $x, $y")
                }
            }
        } finally {
            state.release()
            backend.close()
        }
    }

    @Test
    fun `KorGE's own batch draws into the picture through the render context, the way up KorGE draws`() {
        val backend = backend()
        val state = SceneViewState()
        val blueBitmap = solid(RGBA(0, 0, 255, 255))
        var lent: Any? = null
        var stackTop = 0 to 0
        try {
            open(backend) {
                Screen(state) {
                    clear(red)
                    raw { handed ->
                        lent = handed
                        val ctx = handed as RenderContext
                        stackTop = ctx.currentFrameBuffer.width to ctx.currentFrameBuffer.height
                        // KorGE counts down from the top: this is the top half of the picture.
                        sprite(ctx, blueBitmap, 0f, 0f, width.toFloat(), height / 2f)
                    }
                }
            }.use { ui ->
                var context: RenderContext? = null
                val pixels = frame(ui, backend, before = { context = it })

                assertSame(context, lent, "the frame's own render context")
                assertEquals(120 to 80, stackTop, "with the picture on top of its framebuffer stack")
                assertEquals(blue.code(), pixels.code(100, 70), "the top of the panel, where KorGE drew")
                assertEquals(red.code(), pixels.code(100, 130), "the bottom of the panel")
            }
        } finally {
            state.release()
            backend.close()
        }
    }

    @Test
    fun `the nearer triangle wins inside a scene view whichever order they are drawn in`() {
        val backend = backend()
        val state = SceneViewState()
        var farFirst = true
        try {
            open(backend) {
                Screen(state) {
                    clear(green)
                    raw { handed ->
                        val binding = KorgeKmlGl().also { it.gl = ((handed as RenderContext).ag as AGOpengl).gl }
                        KorgeDepthScene(binding).use { scene ->
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
                assertEquals(red.code(), frame(ui, backend).code(100, 100), "the far triangle was drawn first")
                farFirst = false
                state.invalidate()
                assertEquals(red.code(), frame(ui, backend).code(100, 100), "the far triangle was drawn last")
            }
        } finally {
            state.release()
            backend.close()
        }
    }

    /** What a careless renderer leaves behind, through KorGE's GL behind KorGE's back. */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw { handed ->
            val gl = ((handed as RenderContext).ag as AGOpengl).gl
            val binding = KorgeKmlGl().also { it.gl = gl }
            KorgeDepthScene(binding).triangle(depth = 0f, colour = blue)
            gl.enable(GlConst.DEPTH_TEST)
            gl.enable(GlConst.CULL_FACE)
            gl.enable(GlConst.SCISSOR_TEST)
            gl.scissor(0, 0, 1, 1)
            gl.viewport(0, 0, 1, 1)
            gl.activeTexture(GlConst.TEXTURE0 + 3)
        }
    }

    @Test
    fun `KorGE sprites and a widget after a careless scene draw as they do with no scene`() {
        val redBitmap = solid(RGBA(255, 0, 0, 255))
        val greenBitmap = solid(RGBA(0, 255, 0, 255))
        val before = { ctx: RenderContext -> sprite(ctx, redBitmap, 300f, 10f) }
        // The same texture KorGE bound before the interface, then a new one, then one past the
        // interface's last clip: its texture units, scissor and blending must all be what it thinks.
        val after = { ctx: RenderContext ->
            sprite(ctx, redBitmap, 300f, 300f)
            sprite(ctx, greenBitmap, 340f, 340f)
        }

        fun draw(state: SceneViewState?): Bitmap32 {
            val backend = backend()
            try {
                return open(backend) { Screen(state, careless) }.use { ui -> frame(ui, backend, before, after) }
            } finally {
                state?.release()
                backend.close()
            }
        }

        val withScene = draw(SceneViewState())
        val alone = draw(null)

        assertEquals(blue.code(), withScene.code(100, 100), "the careless scene did draw")
        var lit = 0
        for (y in 0 until size) for (x in 190 until size) {
            assertEquals(alone.code(x, y), withScene.code(x, y), "the box, the label and KorGE's sprites at $x, $y")
            if (alone.code(x, y) == white.code()) lit++
        }
        assertTrue(lit > 20, "the label was drawn at all: $lit white pixels")
        assertEquals(red.code(), withScene.code(320, 30), "KorGE's sprite before the interface")
        assertEquals(red.code(), withScene.code(310, 310), "KorGE's sprite after it")
        assertEquals(green.code(), withScene.code(370, 370), "and another texture after it")
    }

    @Test
    fun `KorGE's own blending inside a scene does not fool KorGE's drawing after the interface`() {
        val redBitmap = solid(RGBA(255, 0, 0, 255))
        val greyBitmap = solid(RGBA(128, 128, 128, 255))
        // Before the interface KorGE blends normally, and remembers that.
        val before = { ctx: RenderContext -> sprite(ctx, greyBitmap, 300f, 300f) }
        // Inside the scene it adds, and remembers ADD as the blending in force.
        val additiveScene: SceneDrawScope.() -> Unit = {
            clear(blue)
            raw { handed -> sprite(handed as RenderContext, redBitmap, 0f, 0f, width.toFloat(), height.toFloat(), BlendMode.ADD) }
        }
        // After the interface it adds red to the grey. Grey plus red is ff8080; red drawn over it is ff0000.
        val after = { ctx: RenderContext -> sprite(ctx, redBitmap, 300f, 300f, blend = BlendMode.ADD) }

        fun draw(state: SceneViewState?): Bitmap32 {
            val backend = backend()
            try {
                return open(backend) { Screen(state, additiveScene) }.use { ui -> frame(ui, backend, before, after) }
            } finally {
                state?.release()
                backend.close()
            }
        }

        val alone = draw(null)
        val withScene = draw(SceneViewState())

        assertEquals(0xFF8080, alone.code(320, 320), "red added to grey with no scene")
        assertEquals(0xFF00FF, withScene.code(100, 100), "the scene added red to its blue")
        assertEquals(0xFF8080, withScene.code(320, 320), "red added to grey after a scene that drew with ADD")
    }

    @Test
    fun `a click or a pad press that asks for the scene again shows the new colour`() {
        val backend = backend()
        val state = SceneViewState()
        try {
            open(backend) {
                var colour by remember { mutableStateOf(red) }
                Screen(state) { clear(colour) }
                Box(
                    Modifier.offset(40f, 300f).size(80f, 40f).background(white)
                        .focusable(initial = true)
                        .clickable {
                            colour = if (colour == red) blue else red
                            state.invalidate()
                        }
                        .testTag("next"),
                )
            }.use { ui ->
                assertEquals(red.code(), frame(ui, backend).code(100, 100))

                ui.click("next")
                assertEquals(blue.code(), frame(ui, backend).code(100, 100), "after the click")
                assertEquals(blue.code(), frame(ui, backend).code(100, 100), "and it stays")
                assertEquals(2L, state.draws, "a frame nobody asked to redraw renders nothing")

                ui.pad(GamepadButton.South)
                assertEquals(red.code(), frame(ui, backend).code(100, 100), "after South on the pad")
                assertEquals(3L, state.draws)
            }
        } finally {
            state.release()
            backend.close()
        }
    }
}

/**
 * A game's own renderer, in miniature: flat triangles at a depth, through a shader of its own, with
 * the depth test switched on. The raw OpenGL and LibGDX suites have the same one.
 */
internal class KorgeDepthScene(private val gl: KorgeKmlGl) : AutoCloseable {

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
        // Flat colour, replacing what is there: a raw block gets the engine's blending, whatever it is.
        gl.disable(GlConst.BLEND)
    }

    /** A triangle over the whole picture at [depth], where -1 is the near plane and 1 the far one. */
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
