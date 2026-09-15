package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.graphics.BlendMode
import korlibs.graphics.AGScissor
import korlibs.graphics.AGTexture
import korlibs.graphics.AGTextureTargetKind
import korlibs.graphics.gl.AGOpengl
import korlibs.kgl.KmlGl
import korlibs.kgl.genBuffer
import korlibs.kgl.genVertexArray
import korlibs.kgl.getIntegerv
import korlibs.kgl.getProgramInfoLog
import korlibs.kgl.getProgramiv
import korlibs.kgl.getShaderInfoLog
import korlibs.kgl.getShaderiv
import korlibs.kgl.deleteBuffer
import korlibs.kgl.deleteVertexArray
import korlibs.korge.render.RenderContext
import korlibs.memory.Buffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everybody's shaders: the toolkit's [ShaderEffect] GLSL, compiled, kept, and pointed at a picture the
 * interface drew a moment ago.
 *
 * The same preamble, the same uniforms and the same quad as the LibGDX backend's `GdxEffects`, so an
 * effect written once — `u_texture`, `u_textureSize`, `u_size`, `u_alpha` and `v_texCoord` — means the
 * same thing on both. One program per shader, cached by its text, so a blur written inline in a widget
 * is compiled the first frame it is seen and never again.
 *
 * KorGE's own programs are written in its shader language, and a KorGE `Program` has no way to set a
 * uniform that is not in one of its uniform blocks — while an effect's uniforms are whatever its author
 * named. So this draws the way `GdxEffects` does, straight through the context's OpenGL, and puts back
 * every piece of GL state KorGE's renderer remembers — the program, the array buffer, the vertex array,
 * the texture unit and what is bound to it, and the blending — so KorGE's next draw finds what it left.
 * The framebuffer and the scissor are set through KorGE itself, so its record of those stays true.
 *
 * GL objects belong to the render thread. [close] can be called from anywhere, so it hands them to the
 * next effect drawn on the same context rather than deleting them there and then.
 */
internal class KorgeEffects : AutoCloseable {

    private class Compiled(val gl: KmlGl, val program: Int) {
        private val locations = HashMap<String, Int>()
        fun location(name: String): Int = locations.getOrPut(name) { gl.getUniformLocation(program, name) }
    }

    /** Keyed by the text, not by the object: a shader built fresh every recomposition still hits. */
    private val programs = HashMap<String, Compiled>()

    private var gl: KmlGl? = null
    private var buffer = 0
    private var vertexArray = 0
    private val vertices = Buffer(4 * FloatsPerVertex * 4, direct = true)

    /**
     * Draws [texture] over the quad given in clip-space corners, through [effect]'s shader, into
     * [context]'s current framebuffer, cut by [scissor].
     *
     * @param alpha the canvas's opacity, handed to the shader as `u_alpha`. It cannot be applied out
     *   here: the shader writes the final colour.
     * @param mode how the result is combined with what is already there, premultiplied, the same two
     *   ways the batch picks between.
     */
    @Suppress("LongParameterList")
    fun draw(
        context: RenderContext,
        effect: ShaderEffect,
        texture: AGTexture,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        textureWidth: Float,
        textureHeight: Float,
        designWidth: Float,
        designHeight: Float,
        alpha: Float,
        mode: BlendMode,
        scissor: AGScissor,
    ) {
        val ag = context.ag as? AGOpengl
            ?: error("shader effects draw through KorGE's OpenGL renderer, and this context has ${context.ag::class.simpleName}")
        val gl = ag.gl
        forgetOrphans(gl)
        if (this.gl !== gl) {
            // A new context: nothing made on the old one means anything here.
            programs.clear()
            buffer = 0
            vertexArray = 0
            this.gl = gl
        }

        val program = programs.getOrPut(effect.source.fragment) { compile(gl, effect.source) }

        // KorGE binds a framebuffer when it next draws into it. This draws by itself, so it asks KorGE to
        // bind it now, and to set the scissor, which keeps KorGE's own record of both correct.
        val frameBuffer = context.currentFrameBuffer
        ag.bindFrameBuffer(frameBuffer.base, frameBuffer.info)
        ag.setScissorState(scissor, frameBuffer.base, frameBuffer.info)

        val savedProgram = gl.getIntegerv(CURRENT_PROGRAM)
        val savedArrayBuffer = gl.getIntegerv(ARRAY_BUFFER_BINDING)
        val savedVertexArray = gl.getIntegerv(VERTEX_ARRAY_BINDING)
        val savedUnit = gl.getIntegerv(ACTIVE_TEXTURE)
        gl.activeTexture(TEXTURE0)
        val savedTexture = gl.getIntegerv(TEXTURE_BINDING_2D)
        val savedBlend = gl.isEnabled(BLEND)
        val savedSourceRgb = gl.getIntegerv(BLEND_SRC_RGB)
        val savedDestinationRgb = gl.getIntegerv(BLEND_DST_RGB)
        val savedSourceAlpha = gl.getIntegerv(BLEND_SRC_ALPHA)
        val savedDestinationAlpha = gl.getIntegerv(BLEND_DST_ALPHA)
        val savedEquationRgb = gl.getIntegerv(BLEND_EQUATION_RGB)
        val savedEquationAlpha = gl.getIntegerv(BLEND_EQUATION_ALPHA)
        try {
            gl.useProgram(program.program)

            ag.textureBind(texture, AGTextureTargetKind.TEXTURE_2D)
            // Stretched across a quad that is not always exactly its own size, and sampled near its
            // edges by a blur, so filtered and held at the edge.
            gl.texParameteri(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR)
            gl.texParameteri(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR)
            gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_S, CLAMP_TO_EDGE)
            gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_T, CLAMP_TO_EDGE)

            // A driver throws away a uniform the shader does not read, and its location is then -1,
            // which GL quietly ignores: a name the shader does not have is not worth stopping a frame.
            gl.uniform1i(program.location("u_texture"), 0)
            gl.uniform2f(program.location("u_textureSize"), textureWidth, textureHeight)
            gl.uniform2f(program.location("u_size"), designWidth, designHeight)
            gl.uniform1f(program.location("u_alpha"), alpha)
            effect.uniforms.forEach { (name, value) -> set(gl, program.location(name), value) }

            // Its own vertex array, so the attributes set here never touch the ones KorGE draws with.
            if (vertexArray == 0) vertexArray = gl.genVertexArray()
            gl.bindVertexArray(vertexArray)
            if (buffer == 0) buffer = gl.genBuffer()
            gl.bindBuffer(ARRAY_BUFFER, buffer)
            put(0, left, top, 0f, 0f)
            put(1, right, top, 1f, 0f)
            put(2, right, bottom, 1f, 1f)
            put(3, left, bottom, 0f, 1f)
            gl.bufferData(ARRAY_BUFFER, 4 * FloatsPerVertex * 4, vertices, STREAM_DRAW)
            gl.enableVertexAttribArray(PositionLocation)
            gl.vertexAttribPointer(PositionLocation, 2, FLOAT, false, FloatsPerVertex * 4, 0L)
            gl.enableVertexAttribArray(TexCoordLocation)
            gl.vertexAttribPointer(TexCoordLocation, 2, FLOAT, false, FloatsPerVertex * 4, 8L)

            // Premultiplied, like every other way a layer reaches the screen, combined the way the
            // canvas's blend stack says, so a group that glows goes on glowing through a blur.
            val destination = if (mode == BlendMode.Additive) ONE else ONE_MINUS_SRC_ALPHA
            gl.enable(BLEND)
            gl.blendEquationSeparate(FUNC_ADD, FUNC_ADD)
            gl.blendFuncSeparate(ONE, destination, ONE, destination)
            gl.drawArrays(TRIANGLE_FAN, 0, 4)
        } finally {
            gl.bindVertexArray(savedVertexArray)
            gl.bindBuffer(ARRAY_BUFFER, savedArrayBuffer)
            gl.bindTexture(TEXTURE_2D, savedTexture)
            gl.activeTexture(savedUnit)
            gl.useProgram(savedProgram)
            if (savedBlend) gl.enable(BLEND) else gl.disable(BLEND)
            gl.blendEquationSeparate(savedEquationRgb, savedEquationAlpha)
            gl.blendFuncSeparate(savedSourceRgb, savedDestinationRgb, savedSourceAlpha, savedDestinationAlpha)
        }
    }

    /** Hands every GL object this made to the next effect drawn on the same context, to delete there. */
    override fun close() {
        val gl = gl ?: return
        programs.values.forEach { compiled -> orphans += gl to { it.deleteProgram(compiled.program) } }
        if (buffer != 0) buffer.let { id -> orphans += gl to { it.deleteBuffer(id) } }
        if (vertexArray != 0) vertexArray.let { id -> orphans += gl to { it.deleteVertexArray(id) } }
        programs.clear()
        buffer = 0
        vertexArray = 0
        this.gl = null
    }

    private fun put(corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * FloatsPerVertex * 4
        vertices.setF32LE(at, x)
        vertices.setF32LE(at + 4, y)
        vertices.setF32LE(at + 8, u)
        vertices.setF32LE(at + 12, v)
    }

    private fun set(gl: KmlGl, location: Int, value: Uniform) {
        if (location < 0) return
        when (value) {
            is Uniform.Number -> gl.uniform1f(location, value.value)
            is Uniform.Vector2 -> gl.uniform2f(location, value.x, value.y)
            is Uniform.Vector3 -> gl.uniform3f(location, value.x, value.y, value.z)
            is Uniform.Vector4 -> gl.uniform4f(location, value.x, value.y, value.z, value.w)
            is Uniform.Whole -> gl.uniform1i(location, value.value)
            is Uniform.Flag -> gl.uniform1i(location, if (value.value) 1 else 0)
        }
    }

    private companion object {

        const val FloatsPerVertex = 4
        const val PositionLocation = 0
        const val TexCoordLocation = 1

        /** GL objects from closed effects, with the context they belong to. */
        val orphans = ConcurrentLinkedQueue<Pair<KmlGl, (KmlGl) -> Unit>>()

        fun forgetOrphans(gl: KmlGl) {
            if (orphans.isEmpty()) return
            val mine = orphans.filter { it.first === gl }
            orphans.removeAll(mine.toSet())
            mine.forEach { (_, delete) -> delete(gl) }
        }

        /**
         * Thrown rather than swallowed. A shader that does not compile is a mistake in the source, and
         * the alternative — a widget that quietly draws nothing — is the hardest bug to find.
         */
        fun compile(gl: KmlGl, source: ShaderSource): Compiled {
            val vertex = shader(gl, VERTEX_SHADER, Vertex, source.name)
            val fragment = shader(gl, FRAGMENT_SHADER, Preamble + source.fragment, source.name)
            val program = gl.createProgram()
            gl.attachShader(program, vertex)
            gl.attachShader(program, fragment)
            gl.bindAttribLocation(program, PositionLocation, "a_position")
            gl.bindAttribLocation(program, TexCoordLocation, "a_texCoord0")
            gl.linkProgram(program)
            gl.deleteShader(vertex)
            gl.deleteShader(fragment)
            if (gl.getProgramiv(program, LINK_STATUS) == 0) {
                val log = gl.getProgramInfoLog(program)
                gl.deleteProgram(program)
                throw IllegalArgumentException("the effect shader \"${source.name}\" would not link:\n$log")
            }
            return Compiled(gl, program)
        }

        fun shader(gl: KmlGl, type: Int, text: String, name: String): Int {
            val shader = gl.createShader(type)
            // ASCII only. KorGE's JVM binding hands GL the string's length in characters, so a comment
            // with an em dash in it is a source a few bytes short, and the driver says "unexpected end".
            // GLSL itself is ASCII, so outside a comment nothing is lost.
            gl.shaderSource(shader, text.map { if (it.code < 0x80) it else ' ' }.joinToString(""))
            gl.compileShader(shader)
            if (gl.getShaderiv(shader, COMPILE_STATUS) == 0) {
                val log = gl.getShaderInfoLog(shader)
                gl.deleteShader(shader)
                throw IllegalArgumentException("the effect shader \"$name\" would not compile:\n$log")
            }
            return shader
        }

        /** The quad, in clip space: the canvas has already worked out where the picture goes. */
        val Vertex = """
            attribute vec2 a_position;
            attribute vec2 a_texCoord0;
            varying vec2 v_texCoord;

            void main() {
                v_texCoord = a_texCoord0;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent()

        /** What every effect shader gets for nothing — word for word the LibGDX backend's. */
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

        const val CURRENT_PROGRAM = 0x8B8D
        const val ARRAY_BUFFER_BINDING = 0x8894
        const val VERTEX_ARRAY_BINDING = 0x85B5
        const val ACTIVE_TEXTURE = 0x84E0
        const val TEXTURE0 = 0x84C0
        const val TEXTURE_BINDING_2D = 0x8069
        const val TEXTURE_2D = 0x0DE1
        const val TEXTURE_MIN_FILTER = 0x2801
        const val TEXTURE_MAG_FILTER = 0x2800
        const val TEXTURE_WRAP_S = 0x2802
        const val TEXTURE_WRAP_T = 0x2803
        const val LINEAR = 0x2601
        const val CLAMP_TO_EDGE = 0x812F
        const val BLEND = 0x0BE2
        const val BLEND_SRC_RGB = 0x80C9
        const val BLEND_DST_RGB = 0x80C8
        const val BLEND_SRC_ALPHA = 0x80CB
        const val BLEND_DST_ALPHA = 0x80CA
        const val BLEND_EQUATION_RGB = 0x8009
        const val BLEND_EQUATION_ALPHA = 0x883D
        const val FUNC_ADD = 0x8006
        const val ONE = 1
        const val ONE_MINUS_SRC_ALPHA = 0x0303
        const val ARRAY_BUFFER = 0x8892
        const val STREAM_DRAW = 0x88E4
        const val FLOAT = 0x1406
        const val TRIANGLE_FAN = 0x0006
        const val VERTEX_SHADER = 0x8B31
        const val FRAGMENT_SHADER = 0x8B30
        const val COMPILE_STATUS = 0x8B81
        const val LINK_STATUS = 0x8B82
    }
}
