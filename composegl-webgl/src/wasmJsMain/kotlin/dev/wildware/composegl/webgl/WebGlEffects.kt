package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.graphics.BlendMode
import org.khronos.webgl.Float32Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.WebGLTexture
import org.khronos.webgl.WebGLUniformLocation
import org.khronos.webgl.set

/**
 * Everybody's shaders: the author's GLSL, compiled once, and pointed at a picture the interface drew
 * a moment ago.
 *
 * The same contract as the desktop backends — the same uniforms, the same preamble, one quad in clip
 * space — so a blur written for LibGDX runs here untouched. The shaders this toolkit ships are GLSL
 * that WebGL already speaks, which is not an accident: they were written for phones.
 */
internal class WebGlEffects(private val gl: GL) : AutoCloseable {

    private inner class Program(val name: WebGLProgram) {
        private val uniforms = HashMap<String, WebGLUniformLocation?>()

        /** Where a uniform lives, looked up once. Null is a uniform the compiler threw away. */
        fun uniform(name: String): WebGLUniformLocation? = uniforms.getOrPut(name) { gl.getUniformLocation(this.name, name) }
    }

    /** Keyed by the text, so a shader built fresh every recomposition still hits. */
    private val programs = HashMap<String, Program>()

    private val vertexBuffer: WebGLBuffer = checkNotNull(gl.createBuffer()) { LostContext }
    private val vertices = Float32Array(4 * FloatsPerVertex)

    /** Draws [texture] over a quad given in clip space, through [effect], combined the way [mode] says. */
    @Suppress("LongParameterList")
    fun draw(
        effect: ShaderEffect,
        texture: WebGLTexture,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        textureWidth: Float,
        textureHeight: Float,
        designWidth: Float,
        designHeight: Float,
        alpha: Float,
        mode: BlendMode,
    ) {
        val program = programs.getOrPut(effect.source.fragment) { compile(effect.source) }
        gl.useProgram(program.name)

        gl.uniform1i(program.uniform("u_texture"), 0)
        gl.uniform2f(program.uniform("u_textureSize"), textureWidth, textureHeight)
        gl.uniform2f(program.uniform("u_size"), designWidth, designHeight)
        gl.uniform1f(program.uniform("u_alpha"), alpha)
        effect.uniforms.forEach { (name, value) -> set(program.uniform(name), value) }

        put(0, left, top, u, v)
        put(1, right, top, u2, v)
        put(2, right, bottom, u2, v2)
        put(3, left, bottom, u, v2)

        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D, texture)

        // Premultiplied, like every other way a layer reaches the screen.
        val destination = if (mode == BlendMode.Additive) GL.ONE else GL.ONE_MINUS_SRC_ALPHA
        gl.enable(GL.BLEND)
        gl.blendFuncSeparate(GL.ONE, destination, GL.ONE, destination)

        gl.bindBuffer(GL.ARRAY_BUFFER, vertexBuffer)
        gl.bufferData(GL.ARRAY_BUFFER, vertices, GL.STREAM_DRAW)
        gl.enableVertexAttribArray(0)
        gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(0, 2, GL.FLOAT, false, FloatsPerVertex * 4, 0)
        gl.vertexAttribPointer(1, 2, GL.FLOAT, false, FloatsPerVertex * 4, 2 * 4)
        gl.drawArrays(GL.TRIANGLE_FAN, 0, 4)
        gl.disableVertexAttribArray(0)
        gl.disableVertexAttribArray(1)
        gl.bindBuffer(GL.ARRAY_BUFFER, null)
        gl.useProgram(null)
    }

    override fun close() {
        programs.values.forEach { gl.deleteProgram(it.name) }
        programs.clear()
        gl.deleteBuffer(vertexBuffer)
    }

    private fun put(corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * FloatsPerVertex
        vertices[at] = x
        vertices[at + 1] = y
        vertices[at + 2] = u
        vertices[at + 3] = v
    }

    private fun set(location: WebGLUniformLocation?, value: Uniform) {
        if (location == null) return
        when (value) {
            is Uniform.Number -> gl.uniform1f(location, value.value)
            is Uniform.Vector2 -> gl.uniform2f(location, value.x, value.y)
            is Uniform.Vector3 -> gl.uniform3f(location, value.x, value.y, value.z)
            is Uniform.Vector4 -> gl.uniform4f(location, value.x, value.y, value.z, value.w)
            is Uniform.Whole -> gl.uniform1i(location, value.value)
            is Uniform.Flag -> gl.uniform1i(location, if (value.value) 1 else 0)
        }
    }

    private fun compile(source: ShaderSource): Program {
        val vertex = shader(gl, GL.VERTEX_SHADER, Vertex, source.name)
        val fragment = try {
            shader(gl, GL.FRAGMENT_SHADER, Preamble + source.fragment, source.name)
        } catch (failure: IllegalArgumentException) {
            gl.deleteShader(vertex)
            throw failure
        }
        val program = checkNotNull(gl.createProgram()) { LostContext }
        gl.attachShader(program, vertex)
        gl.attachShader(program, fragment)
        gl.bindAttribLocation(program, 0, "a_position")
        gl.bindAttribLocation(program, 1, "a_texCoord0")
        gl.linkProgram(program)
        val ok = linked(gl, program)
        val log = gl.getProgramInfoLog(program)
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
        require(ok) { "the effect shader \"${source.name}\" would not link:\n$log" }
        return Program(program)
    }

    private companion object {

        const val FloatsPerVertex = 4

        val Vertex = """
            attribute vec2 a_position;
            attribute vec2 a_texCoord0;
            varying vec2 v_texCoord;

            void main() {
                v_texCoord = a_texCoord0;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent()

        /** The desktop preamble, with the precision a browser insists on and no `#ifdef` about it. */
        val Preamble = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D u_texture;
            uniform vec2 u_textureSize;
            uniform vec2 u_size;
            uniform float u_alpha;

        """.trimIndent() + "\n"
    }
}
