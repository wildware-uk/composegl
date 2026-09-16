package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The renderer's [Gl], on LWJGL's desktop OpenGL bindings. One line a call, and nothing else.
 *
 * Stateless: LWJGL's functions act on whichever context is current on the calling thread, so one
 * binding serves every window.
 */
object LwjglGl : Gl {

    override val profile: GlProfile
        get() {
            val caps = GL.getCapabilities()
            val version = GL11.glGetString(GL11.GL_VERSION).orEmpty()
            val numbers = Regex("^(\\d+)\\.(\\d+)").find(version)
            val major = numbers?.groupValues?.get(1)?.toInt() ?: 2
            val minor = numbers?.groupValues?.get(2)?.toInt() ?: 1
            val coreProfile = caps.OpenGL32 &&
                GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) and GL32.GL_CONTEXT_CORE_PROFILE_BIT != 0
            val forwardCompatible = caps.OpenGL30 &&
                GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) and GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT != 0
            return GlProfile(GlApi.Desktop, major, minor, core = coreProfile || forwardCompatible)
        }

    override fun hasExtension(name: String): Boolean {
        if (GL.getCapabilities().OpenGL30) {
            val count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS)
            return (0 until count).any { GL30.glGetStringi(GL11.GL_EXTENSIONS, it) == name }
        }
        return GL11.glGetString(GL11.GL_EXTENSIONS).orEmpty().split(' ').contains(name)
    }

    override fun floats(capacity: Int): GlFloats = Floats(BufferUtils.createFloatBuffer(capacity))
    override fun shorts(capacity: Int): GlShorts = Shorts(BufferUtils.createShortBuffer(capacity))
    override fun bytes(capacity: Int): GlBytes = Bytes(BufferUtils.createByteBuffer(capacity))

    override fun enable(cap: Int) = GL11.glEnable(cap)
    override fun disable(cap: Int) = GL11.glDisable(cap)
    override fun isEnabled(cap: Int): Boolean = GL11.glIsEnabled(cap)
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) =
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = GL20.glBlendEquationSeparate(rgb, alpha)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = GL11.glColorMask(red, green, blue, alpha)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GL11.glViewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = GL11.glScissor(x, y, width, height)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = GL11.glClearColor(red, green, blue, alpha)
    override fun clearDepth(depth: Float) = GL11.glClearDepth(depth.toDouble())
    override fun depthMask(write: Boolean) = GL11.glDepthMask(write)
    override fun clear(mask: Int) = GL11.glClear(mask)
    override fun getInteger(name: Int): Int = GL11.glGetInteger(name)
    override fun getIntegers(name: Int, into: IntArray) = GL11.glGetIntegerv(name, into)
    override fun pixelStorei(name: Int, value: Int) = GL11.glPixelStorei(name, value)

    override fun createShader(type: Int): Int = GL20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GL20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GL20.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE
    override fun shaderInfoLog(shader: Int): String = GL20.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GL20.glDeleteShader(shader)
    override fun createProgram(): Int = GL20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GL20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GL20.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GL20.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE
    override fun programInfoLog(program: Int): String = GL20.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GL20.glDeleteProgram(program)
    override fun useProgram(program: Int) = GL20.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = GL20.glGetUniformLocation(program, name)
    override fun uniform1i(at: Int, value: Int) = GL20.glUniform1i(at, value)
    override fun uniform1f(at: Int, value: Float) = GL20.glUniform1f(at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = GL20.glUniform2f(at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = GL20.glUniform3f(at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = GL20.glUniform4f(at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = GL20.glUniformMatrix4fv(at, false, matrix)

    override fun createBuffer(): Int = GL15.glGenBuffers()
    override fun bindBuffer(target: Int, buffer: Int) = GL15.glBindBuffer(target, buffer)
    override fun deleteBuffer(buffer: Int) = GL15.glDeleteBuffers(buffer)
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) =
        (data as Floats).buffer.let { GL15.glBufferData(target, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) =
        (data as Shorts).buffer.let { GL15.glBufferData(target, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun enableVertexAttribArray(index: Int) = GL20.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GL20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    override fun createVertexArray(): Int = GL30.glGenVertexArrays()
    override fun bindVertexArray(array: Int) = GL30.glBindVertexArray(array)
    override fun deleteVertexArray(array: Int) = GL30.glDeleteVertexArrays(array)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GL11.glDrawElements(mode, count, type, offset.toLong())

    override fun createTexture(): Int = GL11.glGenTextures()
    override fun bindTexture(target: Int, texture: Int) = GL11.glBindTexture(target, texture)
    override fun deleteTexture(texture: Int) = GL11.glDeleteTextures(texture)
    override fun activeTexture(unit: Int) = GL13.glActiveTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = GL11.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        GL11.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, (pixels as Bytes?)?.buffer?.clear())
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        GL11.glTexSubImage2D(target, level, x, y, width, height, format, type, (pixels as Bytes).buffer.clear())

    override fun createFramebuffer(): Int = GL30.glGenFramebuffers()
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GL30.glBindFramebuffer(target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = GL30.glDeleteFramebuffers(framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        GL30.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = GL30.glCheckFramebufferStatus(target)
    override fun createRenderbuffer(): Int = GL30.glGenRenderbuffers()
    override fun bindRenderbuffer(target: Int, renderbuffer: Int) = GL30.glBindRenderbuffer(target, renderbuffer)
    override fun deleteRenderbuffer(renderbuffer: Int) = GL30.glDeleteRenderbuffers(renderbuffer)
    override fun renderbufferStorage(target: Int, format: Int, width: Int, height: Int) =
        GL30.glRenderbufferStorage(target, format, width, height)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        GL30.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        GL11.glReadPixels(x, y, width, height, format, type, (into as Bytes).buffer.clear())

    /** Direct memory, written by absolute index: nothing is copied on its way to the driver. */
    internal class Floats(val buffer: FloatBuffer) : GlFloats {
        override val capacity: Int get() = buffer.capacity()
        override fun set(index: Int, value: Float) {
            buffer.put(index, value)
        }
    }

    internal class Shorts(val buffer: ShortBuffer) : GlShorts {
        override val capacity: Int get() = buffer.capacity()
        override fun set(index: Int, value: Short) {
            buffer.put(index, value)
        }
    }

    /** Bytes in direct memory. [wrap] lends a buffer a game already has, such as a decoded picture. */
    class Bytes internal constructor(val buffer: ByteBuffer) : GlBytes {
        override val capacity: Int get() = buffer.capacity()
        override fun get(index: Int): Byte = buffer.get(index)
        override fun set(index: Int, value: Byte) {
            buffer.put(index, value)
        }
        override fun put(at: Int, from: ByteArray, offset: Int, count: Int) {
            buffer.clear().position(at)
            buffer.put(from, offset, count)
            buffer.clear()
        }

        companion object {
            fun wrap(buffer: ByteBuffer): Bytes = Bytes(buffer)
        }
    }
}
