package dev.wildware.composegl.kool

import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlslDialect
import dev.wildware.composegl.ui.graphics.Colour
import org.lwjgl.opengl.GL11

/**
 * A game's own renderer, in miniature, on Kool's context: flat triangles at a depth, through a shader
 * of its own, with depth testing on. What a Kool game's `raw` block draws with is OpenGL behind Kool's
 * back, so this is that.
 *
 * The raw OpenGL frontend's test helper of the same shape, on [KoolGl]; Kool's desktop context is
 * OpenGL 3.3 core, so vertex arrays always.
 */
internal class KoolDepthScene : AutoCloseable {

    private val gl = KoolGl
    private val dialect = GlslDialect.of(gl.profile)

    private val program = link()
    private val colourAt = gl.getUniformLocation(program, "u_colour")
    private val array = gl.createVertexArray()
    private val vertices = gl.createBuffer()
    private val indices = gl.createBuffer()

    init {
        gl.bindVertexArray(array)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indices)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, gl.shorts(3).also { it[0] = 0; it[1] = 1; it[2] = 2 }, 3, GlConst.STATIC_DRAW)
        gl.bindVertexArray(0)
        gl.enable(GlConst.DEPTH_TEST)
        gl.depthMask(true)
        // Kool leaves its own comparison in force, reversed for reversed depth; this scene counts the usual way.
        GL11.glDepthFunc(GL11.GL_LESS)
        // Flat colour, replacing what is there.
        gl.disable(GlConst.BLEND)
    }

    /**
     * A triangle over the whole picture at [depth], nearer the smaller it is. Kept between 0 and 1, which
     * is inside the view both with and without clip control.
     */
    fun triangle(depth: Float, colour: Colour) {
        val points = gl.floats(9)
        put(points, 0, -1f, -1f, depth)
        put(points, 1, 3f, -1f, depth)
        put(points, 2, -1f, 3f, depth)

        gl.useProgram(program)
        gl.uniform4f(colourAt, colour.red / 255f, colour.green / 255f, colour.blue / 255f, 1f)
        gl.bindVertexArray(array)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, vertices)
        gl.bufferData(GlConst.ARRAY_BUFFER, points, 9, GlConst.STREAM_DRAW)
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 3, GlConst.FLOAT, false, 3 * 4, 0)
        gl.drawElements(GlConst.TRIANGLES, 3, GlConst.UNSIGNED_SHORT, 0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        gl.bindVertexArray(0)
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
        gl.deleteVertexArray(array)
    }
}
