package composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import composegl.ui.effect.ShaderEffect
import composegl.ui.effect.ShaderSource
import composegl.ui.effect.Uniform

/**
 * Everybody's shaders: the user's GLSL, compiled, kept, and pointed at a picture the interface
 * drew a moment ago.
 *
 * One quad, one program per shader, and a cache so that a blur written inline in a widget is
 * compiled the first frame it is seen and never again. Nothing here knows what a blur is — the
 * effects this toolkit ships are written against the same public API as anybody else's, and they
 * arrive here as text like everything else.
 */
internal class GdxEffects : Disposable {

    /** Keyed by the text, not by the object: a shader built fresh every recomposition still hits. */
    private val programs = HashMap<String, ShaderProgram>()

    private val mesh = Mesh(
        Mesh.VertexDataType.VertexArray,
        false,
        4,
        0,
        VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
        VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
    )

    private val vertices = FloatArray(4 * FLOATS_PER_VERTEX)

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
        texture: Texture,
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
        program.bind()

        texture.bind(0)
        // Every one of these is asked for rather than assumed: a driver throws away a uniform the
        // shader does not read, and LibGDX treats setting one that is gone as an error.
        if (program.hasUniform("u_texture")) program.setUniformi("u_texture", 0)
        if (program.hasUniform("u_textureSize")) program.setUniformf("u_textureSize", textureWidth, textureHeight)
        if (program.hasUniform("u_size")) program.setUniformf("u_size", designWidth, designHeight)
        if (program.hasUniform("u_alpha")) program.setUniformf("u_alpha", alpha)
        effect.uniforms.forEach { (name, value) -> set(program, name, value) }

        // Two triangles, in clip space, so there is no projection to set and nothing to get wrong
        // about where the quad is.
        put(0, left, top, u, v)
        put(1, right, top, u2, v)
        put(2, right, bottom, u2, v2)
        put(3, left, bottom, u, v2)
        mesh.setVertices(vertices)

        // Premultiplied, like every other way a layer reaches the screen.
        Gdx.gl.glBlendFuncSeparate(
            GL20.GL_ONE,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
            GL20.GL_ONE,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
        )
        mesh.render(program, GL20.GL_TRIANGLE_FAN, 0, 4)
    }

    override fun dispose() {
        programs.values.forEach { it.dispose() }
        programs.clear()
        mesh.dispose()
    }

    private fun put(corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * FLOATS_PER_VERTEX
        vertices[at] = x
        vertices[at + 1] = y
        vertices[at + 2] = u
        vertices[at + 3] = v
    }

    private fun set(program: ShaderProgram, name: String, value: Uniform) {
        // A driver throws away a uniform nothing reads, so a name the shader does not have looks
        // exactly like a typo and neither is worth stopping a frame for.
        if (!program.hasUniform(name)) return
        when (value) {
            is Uniform.Number -> program.setUniformf(name, value.value)
            is Uniform.Vector2 -> program.setUniformf(name, value.x, value.y)
            is Uniform.Vector3 -> program.setUniformf(name, value.x, value.y, value.z)
            is Uniform.Vector4 -> program.setUniformf(name, value.x, value.y, value.z, value.w)
            is Uniform.Whole -> program.setUniformi(name, value.value)
            is Uniform.Flag -> program.setUniformi(name, if (value.value) 1 else 0)
        }
    }

    private fun compile(source: ShaderSource): ShaderProgram {
        val program = ShaderProgram(VERTEX, PREAMBLE + source.fragment)
        // Thrown rather than swallowed. A shader that does not compile is a mistake in the source,
        // and the alternative — a widget that quietly draws nothing — is the hardest bug in this
        // toolkit to find.
        require(program.isCompiled) {
            val log = program.log
            program.dispose()
            "the effect shader \"${source.name}\" would not compile:\n$log"
        }
        return program
    }

    private companion object {

        const val FLOATS_PER_VERTEX = 4

        /**
         * The quad, in clip space.
         *
         * The canvas has already worked out where the picture goes, so there is no projection
         * here: an effect is one rectangle, and the arithmetic that puts it somewhere belongs with
         * the code that knows about design coordinates.
         */
        val VERTEX = """
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
        val PREAMBLE = """
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
