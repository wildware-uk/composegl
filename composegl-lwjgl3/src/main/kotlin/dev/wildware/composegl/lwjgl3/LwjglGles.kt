package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20
import org.lwjgl.opengles.GLES30
import org.lwjgl.opengles.OESVertexArrayObject

/**
 * The renderer's [Gl], on LWJGL's OpenGL ES bindings: what a window asking for an ES 2 or ES 3
 * context gets. One line a call, like [LwjglGl], whose direct buffers it shares.
 *
 * ES 2 has vertex array objects only as `GL_OES_vertex_array_object`; the device asks for them only
 * when the context says it has them.
 */
object LwjglGles : Gl {

    override val profile: GlProfile
        get() {
            // "OpenGL ES 3.2 Mesa 25.2.8": the numbers come after the name.
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            val numbers = Regex("(\\d+)\\.(\\d+)").find(version)
            return GlProfile(GlApi.Es, numbers?.groupValues?.get(1)?.toInt() ?: 2, numbers?.groupValues?.get(2)?.toInt() ?: 0)
        }

    private val es3: Boolean get() = GLES.getCapabilities().GLES30

    override fun hasExtension(name: String): Boolean {
        if (es3) {
            val count = GLES20.glGetInteger(GLES30.GL_NUM_EXTENSIONS)
            return (0 until count).any { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) == name }
        }
        return GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ').contains(name)
    }

    override fun floats(capacity: Int): GlFloats = LwjglGl.floats(capacity)
    override fun shorts(capacity: Int): GlShorts = LwjglGl.shorts(capacity)
    override fun bytes(capacity: Int): GlBytes = LwjglGl.bytes(capacity)

    override fun enable(cap: Int) = GLES20.glEnable(cap)
    override fun disable(cap: Int) = GLES20.glDisable(cap)
    override fun isEnabled(cap: Int): Boolean = GLES20.glIsEnabled(cap)
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) =
        GLES20.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = GLES20.glBlendEquationSeparate(rgb, alpha)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = GLES20.glColorMask(red, green, blue, alpha)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES20.glViewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = GLES20.glScissor(x, y, width, height)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = GLES20.glClearColor(red, green, blue, alpha)
    override fun clear(mask: Int) = GLES20.glClear(mask)
    override fun getInteger(name: Int): Int = GLES20.glGetInteger(name)
    override fun getIntegers(name: Int, into: IntArray) = GLES20.glGetIntegerv(name, into)
    override fun pixelStorei(name: Int, value: Int) = GLES20.glPixelStorei(name, value)

    override fun createShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean = GLES20.glGetShaderi(shader, GLES20.GL_COMPILE_STATUS) == GLES20.GL_TRUE
    override fun shaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)
    override fun createProgram(): Int = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GLES20.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean = GLES20.glGetProgrami(program, GLES20.GL_LINK_STATUS) == GLES20.GL_TRUE
    override fun programInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    override fun uniform1i(at: Int, value: Int) = GLES20.glUniform1i(at, value)
    override fun uniform1f(at: Int, value: Float) = GLES20.glUniform1f(at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = GLES20.glUniform2f(at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = GLES20.glUniform3f(at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = GLES20.glUniformMatrix4fv(at, false, matrix)

    override fun createBuffer(): Int = GLES20.glGenBuffers()
    override fun bindBuffer(target: Int, buffer: Int) = GLES20.glBindBuffer(target, buffer)
    override fun deleteBuffer(buffer: Int) = GLES20.glDeleteBuffers(buffer)
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) =
        (data as LwjglGl.Floats).buffer.let { GLES20.glBufferData(target, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) =
        (data as LwjglGl.Shorts).buffer.let { GLES20.glBufferData(target, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun enableVertexAttribArray(index: Int) = GLES20.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GLES20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    override fun createVertexArray(): Int = if (es3) GLES30.glGenVertexArrays() else OESVertexArrayObject.glGenVertexArraysOES()
    override fun bindVertexArray(array: Int) = if (es3) GLES30.glBindVertexArray(array) else OESVertexArrayObject.glBindVertexArrayOES(array)
    override fun deleteVertexArray(array: Int) = if (es3) GLES30.glDeleteVertexArrays(array) else OESVertexArrayObject.glDeleteVertexArraysOES(array)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GLES20.glDrawElements(mode, count, type, offset.toLong())

    override fun createTexture(): Int = GLES20.glGenTextures()
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun deleteTexture(texture: Int) = GLES20.glDeleteTextures(texture)
    override fun activeTexture(unit: Int) = GLES20.glActiveTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        GLES20.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, (pixels as LwjglGl.Bytes?)?.buffer?.clear())
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        GLES20.glTexSubImage2D(target, level, x, y, width, height, format, type, (pixels as LwjglGl.Bytes).buffer.clear())

    override fun createFramebuffer(): Int = GLES20.glGenFramebuffers()
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GLES20.glBindFramebuffer(target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = GLES20.glDeleteFramebuffers(framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        GLES20.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = GLES20.glCheckFramebufferStatus(target)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        GLES20.glReadPixels(x, y, width, height, format, type, (into as LwjglGl.Bytes).buffer.clear())
}
