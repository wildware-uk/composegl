package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDevice
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlslDialect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.render.gl.Gl as GlBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A game's own 3D drawing inside an offscreen target: does the nearer thing win?
 *
 * Only a GPU can answer it. Two triangles cover the whole picture at different depths, drawn in
 * both orders; with a depth buffer the near one wins either way, and without one the last one
 * drawn wins and the second half of each assertion fails. That is the whole of the bug this exists
 * to catch: a scene that comes out inside-out with no error anywhere to say why.
 */
class GlRenderTargetDepthTest {

    /** The same skip as the rest of the suite: a target built with no context aborts the JVM. */
    @BeforeEach
    fun requireDisplay() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val size = 32

    private val near = Colour.rgb(0xFF0000)
    private val far = Colour.rgb(0x0000FF)

    @Test
    fun `the nearer triangle wins whichever order they are drawn in`() = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(size, size, depth = true)
        try {
            assertTrue(target.depth, "the target was asked for depth")
            assertTrue(target.depthBufferName != 0, "and got a depth buffer of its own")

            assertEquals(near.code(), middleOf(draw(canvas, target, farFirst = true)), "the far triangle was drawn first")
            assertEquals(near.code(), middleOf(draw(canvas, target, farFirst = false)), "the far triangle was drawn last")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `without a depth buffer the last triangle drawn wins`() = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(size, size)
        try {
            assertTrue(!target.depth)
            // The same two draws, the same depth test asked for: with nothing to test against, all
            // that is left is the order. This is what every SceneView looked like before #224.
            assertEquals(near.code(), middleOf(draw(canvas, target, farFirst = true)))
            assertEquals(far.code(), middleOf(draw(canvas, target, farFirst = false)))
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `a resize gives the target a depth buffer of the new size`() = Gl.render {
        val canvas = GlCanvas()
        val target = GlRenderTarget(size, size, depth = true)
        try {
            val first = target.depthBufferName
            repeat(20) { target.resize(size + it, size + it) }

            assertTrue(target.depthBufferName <= first + 1, "twenty resizes left depth buffers behind: ${target.depthBufferName}")
            assertTrue(target.depth)
            assertEquals(near.code(), middleOf(draw(canvas, target, farFirst = true)), "and it still depth-tests at the new size")
        } finally {
            target.close()
            canvas.close()
        }
    }

    @Test
    fun `a size bigger than the GPU makes is cut down to the biggest it does and says so`() = Gl.render {
        val most = Gl.gl.getInteger(GlConst.MAX_TEXTURE_SIZE)
        val target = GlRenderTarget(most + 100, 16, depth = true)
        try {
            assertEquals(most, target.width)
            assertEquals(16, target.height)
            assertTrue(target.clamped)
            assertEquals(most, target.texture.width, "the handle the game holds is the size it really got")
            assertTrue(Gl.isFramebuffer(target.framebufferName), "and the allocation did not fail")
        } finally {
            target.close()
        }
    }

    /** One frame into [target]: cleared, then two triangles a game draws itself through `raw`. */
    private fun draw(canvas: GlCanvas, target: GlRenderTarget, farFirst: Boolean): ByteArray {
        target.draw(canvas, clear = Colour.rgb(0x00FF00)) {
            canvas.raw {
                DepthScene(Gl.gl).use { scene ->
                    if (farFirst) {
                        scene.triangle(depth = 0.6f, colour = far)
                        scene.triangle(depth = -0.6f, colour = near)
                    } else {
                        scene.triangle(depth = -0.6f, colour = near)
                        scene.triangle(depth = 0.6f, colour = far)
                    }
                }
            }
        }
        return target.readPixels()
    }

    /** The middle pixel of a read-back picture as `0xRRGGBB`. Opaque, so premultiplied is plain. */
    private fun middleOf(pixels: ByteArray): Int {
        val at = ((size / 2) * size + size / 2) * 4
        return (pixels[at].toInt() and 0xFF shl 16) or
            (pixels[at + 1].toInt() and 0xFF shl 8) or
            (pixels[at + 2].toInt() and 0xFF)
    }

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue
}

/**
 * A game's own renderer, in miniature: flat triangles at a depth, through a shader of its own,
 * with the depth test the toolkit leaves switched off switched on.
 *
 * Written against the toolkit's `Gl` binding rather than LWJGL's, so the same scene runs on desktop
 * GL, on OpenGL ES 2 and on ES 3, which is what the suite's three test tasks give it.
 */
private class DepthScene(private val gl: GlBinding) : AutoCloseable {

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

    /** Everything back, including the depth test, which the toolkit's own frames do without. */
    override fun close() {
        gl.disable(GlConst.DEPTH_TEST)
        gl.useProgram(0)
        gl.deleteProgram(program)
        gl.deleteBuffer(vertices)
        gl.deleteBuffer(indices)
        if (arrays) gl.deleteVertexArray(array)
    }
}
