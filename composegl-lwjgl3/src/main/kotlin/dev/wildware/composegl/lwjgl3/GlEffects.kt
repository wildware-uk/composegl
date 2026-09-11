package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20

/**
 * Everybody's shaders: the user's GLSL, compiled, kept, and pointed at a picture the interface
 * drew a moment ago.
 *
 * One quad, one program per shader, and a cache so that a blur written inline in a widget is
 * compiled the first frame it is seen and never again. Nothing here knows what a blur is — the
 * effects this toolkit ships are written against the same public API as anybody else's, and they
 * arrive here as text like everything else.
 */
internal class GlEffects : AutoCloseable {

    private class Program(val name: Int) {
        private val uniforms = HashMap<String, Int>()

        /**
         * Where a uniform lives, looked up once.
         *
         * -1 means the shader does not have it, which is not an error: a driver throws away a
         * uniform nothing reads, so a name that is set but unused looks exactly like a typo and
         * neither is worth stopping a frame for.
         */
        fun uniform(name: String): Int = uniforms.getOrPut(name) { GL20.glGetUniformLocation(this.name, name) }
    }

    /** Keyed by the text, not by the object: a shader built fresh every recomposition still hits. */
    private val programs = HashMap<String, Program>()

    private val vertexBuffer = GL15.glGenBuffers()
    private val vertices = FloatArray(4 * FloatsPerVertex)
    private val upload = BufferUtils.createFloatBuffer(vertices.size)

    /**
     * Draws [texture] over the quad given in clip-space corners, through [effect]'s shader.
     *
     * The corners arrive already worked out because the canvas is the thing that knows where a
     * design coordinate ends up — this class only knows how to put a picture through a shader.
     *
     * @param alpha the canvas's opacity, handed to the shader as `u_alpha`. It cannot be applied
     *   out here: the shader writes the final colour and nothing downstream can multiply it.
     */
    @Suppress("LongParameterList")
    fun draw(
        effect: ShaderEffect,
        texture: Int,
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
    ) {
        val program = programs.getOrPut(effect.source.fragment) { compile(effect.source) }
        GL20.glUseProgram(program.name)

        GL20.glUniform1i(program.uniform("u_texture"), 0)
        GL20.glUniform2f(program.uniform("u_textureSize"), textureWidth, textureHeight)
        GL20.glUniform2f(program.uniform("u_size"), designWidth, designHeight)
        GL20.glUniform1f(program.uniform("u_alpha"), alpha)
        effect.uniforms.forEach { (name, value) -> set(program.uniform(name), value) }

        // Two triangles, in clip space, so there is no projection to set and nothing to get wrong
        // about where the quad is.
        put(0, left, top, u, v)
        put(1, right, top, u2, v)
        put(2, right, bottom, u2, v2)
        put(3, left, bottom, u, v2)

        upload.clear()
        upload.put(vertices)
        upload.flip()

        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)

        // Premultiplied, like every other way a layer reaches the screen.
        GL14.glBlendFuncSeparate(
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
        )

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(0)
        GL20.glEnableVertexAttribArray(1)
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, FloatsPerVertex * Float.SIZE_BYTES, 0L)
        GL20.glVertexAttribPointer(
            1,
            2,
            GL11.GL_FLOAT,
            false,
            FloatsPerVertex * Float.SIZE_BYTES,
            2L * Float.SIZE_BYTES,
        )
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4)
        GL20.glDisableVertexAttribArray(0)
        GL20.glDisableVertexAttribArray(1)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL20.glUseProgram(0)
    }

    override fun close() {
        programs.values.forEach { GL20.glDeleteProgram(it.name) }
        programs.clear()
        GL15.glDeleteBuffers(vertexBuffer)
    }

    private fun put(corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * FloatsPerVertex
        vertices[at] = x
        vertices[at + 1] = y
        vertices[at + 2] = u
        vertices[at + 3] = v
    }

    private fun set(location: Int, value: Uniform) {
        if (location < 0) return
        when (value) {
            is Uniform.Number -> GL20.glUniform1f(location, value.value)
            is Uniform.Vector2 -> GL20.glUniform2f(location, value.x, value.y)
            is Uniform.Vector3 -> GL20.glUniform3f(location, value.x, value.y, value.z)
            is Uniform.Vector4 -> GL20.glUniform4f(location, value.x, value.y, value.z, value.w)
            is Uniform.Whole -> GL20.glUniform1i(location, value.value)
            is Uniform.Flag -> GL20.glUniform1i(location, if (value.value) 1 else 0)
        }
    }

    private fun compile(source: ShaderSource): Program {
        val vertex = shader(GL20.GL_VERTEX_SHADER, Vertex, source.name)
        val fragment = shader(GL20.GL_FRAGMENT_SHADER, Preamble + source.fragment, source.name)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        GL20.glBindAttribLocation(program, 0, "a_position")
        GL20.glBindAttribLocation(program, 1, "a_texCoord0")
        GL20.glLinkProgram(program)
        val linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE
        val log = GL20.glGetProgramInfoLog(program)
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)
        require(linked) { "the effect shader \"${source.name}\" would not link:\n$log" }
        return Program(program)
    }

    private fun shader(type: Int, source: String, name: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) return shader

        val log = GL20.glGetShaderInfoLog(shader)
        GL20.glDeleteShader(shader)
        val what = if (type == GL20.GL_VERTEX_SHADER) "vertex" else "fragment"
        // Thrown rather than swallowed. A shader that does not compile is a mistake in the source,
        // and the alternative — a widget that quietly draws nothing — is the hardest bug in this
        // toolkit to find.
        throw IllegalArgumentException("the effect shader \"$name\" would not compile ($what):\n$log")
    }

    private companion object {

        const val FloatsPerVertex = 4

        /**
         * The quad, in clip space.
         *
         * The canvas has already worked out where the picture goes, so there is no projection
         * here: an effect is one rectangle, and the arithmetic that puts it somewhere belongs with
         * the code that knows about design coordinates.
         */
        val Vertex = """
            attribute vec2 a_position;
            attribute vec2 a_texCoord0;
            varying vec2 v_texCoord;

            void main() {
                v_texCoord = a_texCoord0;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent()

        /**
         * What every effect shader gets for nothing.
         *
         * Written on the front of the author's own text, so that a shader is the part somebody
         * actually meant to write. The precision line is for phones, where a fragment shader
         * without one does not compile at all; desktop drivers ignore it.
         */
        val Preamble = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 v_texCoord;
            uniform sampler2D u_texture;
            uniform vec2 u_textureSize;
            uniform vec2 u_size;
            uniform float u_alpha;

        """.trimIndent() + "\n"
    }
}
