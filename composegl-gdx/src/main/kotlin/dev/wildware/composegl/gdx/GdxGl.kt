package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.GLVersion
import com.badlogic.gdx.utils.BufferUtils
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/**
 * The renderer's [Gl], on `Gdx.gl` and `Gdx.gl30`: desktop, Android and iOS alike. One line a call.
 *
 * `Gdx.gl30` is what decides which OpenGL this is. A game on GL 2 or OpenGL ES 2 has none, so the
 * binding says so — version 2 — whatever the driver underneath could do, and the renderer never asks
 * for a vertex array object it has no call to make one with.
 *
 * Memory comes from LibGDX's `BufferUtils`: direct and in native order, which Android's GL needs.
 */
object GdxGl : Gl {

    private val gl: GL20 get() = Gdx.gl

    /** For the queries that answer through a buffer. The GL thread is the only caller. */
    private val ints: IntBuffer = BufferUtils.newIntBuffer(16)

    private val handle = IntArray(1)

    override val profile: GlProfile
        get() {
            val version = Gdx.graphics.glVersion
            val api = when (version.type) {
                GLVersion.Type.GLES -> GlApi.Es
                GLVersion.Type.WebGL -> GlApi.WebGl
                else -> GlApi.Desktop
            }
            if (Gdx.gl30 == null) return GlProfile(api, 2, 0)
            val core = api == GlApi.Desktop && (
                (version.isVersionEqualToOrHigher(3, 2) && getInteger(ContextProfileMask) and CoreProfileBit != 0) ||
                    getInteger(ContextFlags) and ForwardCompatibleBit != 0
                )
            return GlProfile(api, version.majorVersion, version.minorVersion, core)
        }

    override fun hasExtension(name: String): Boolean =
        !(Gdx.gl30 == null && name.endsWith("vertex_array_object")) && Gdx.graphics.supportsExtension(name)

    override fun floats(capacity: Int): GlFloats = Floats(BufferUtils.newFloatBuffer(capacity))
    override fun shorts(capacity: Int): GlShorts = Shorts(BufferUtils.newShortBuffer(capacity))
    override fun bytes(capacity: Int): GlBytes = Bytes(BufferUtils.newByteBuffer(capacity))

    override fun enable(cap: Int) = gl.glEnable(cap)
    override fun disable(cap: Int) = gl.glDisable(cap)
    override fun isEnabled(cap: Int): Boolean = gl.glIsEnabled(cap)
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) =
        gl.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = gl.glBlendEquationSeparate(rgb, alpha)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = gl.glColorMask(red, green, blue, alpha)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = gl.glViewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = gl.glScissor(x, y, width, height)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = gl.glClearColor(red, green, blue, alpha)
    override fun clear(mask: Int) = gl.glClear(mask)
    override fun getInteger(name: Int): Int = ints.also { it.clear(); gl.glGetIntegerv(name, it) }.get(0)
    override fun getIntegers(name: Int, into: IntArray) {
        ints.clear()
        gl.glGetIntegerv(name, ints)
        for (index in into.indices) into[index] = ints.get(index)
    }
    override fun pixelStorei(name: Int, value: Int) = gl.glPixelStorei(name, value)

    override fun createShader(type: Int): Int = gl.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = gl.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = gl.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean =
        ints.also { it.clear(); gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, it) }.get(0) != 0
    override fun shaderInfoLog(shader: Int): String = gl.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = gl.glDeleteShader(shader)
    override fun createProgram(): Int = gl.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = gl.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = gl.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = gl.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean =
        ints.also { it.clear(); gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, it) }.get(0) != 0
    override fun programInfoLog(program: Int): String = gl.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = gl.glDeleteProgram(program)
    override fun useProgram(program: Int) = gl.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = gl.glGetUniformLocation(program, name)
    override fun uniform1i(at: Int, value: Int) = gl.glUniform1i(at, value)
    override fun uniform1f(at: Int, value: Float) = gl.glUniform1f(at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = gl.glUniform2f(at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = gl.glUniform3f(at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = gl.glUniform4f(at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = gl.glUniformMatrix4fv(at, 1, false, matrix, 0)

    override fun createBuffer(): Int = gl.glGenBuffer()
    override fun bindBuffer(target: Int, buffer: Int) = gl.glBindBuffer(target, buffer)
    override fun deleteBuffer(buffer: Int) = gl.glDeleteBuffer(buffer)

    // The limit is what LWJGL uploads and the size is what Android does, so both are set.
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) =
        (data as Floats).buffer.let { gl.glBufferData(target, count * 4, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) =
        (data as Shorts).buffer.let { gl.glBufferData(target, count * 2, it.limit(count).position(0), usage); it.clear() }.let { }
    override fun enableVertexAttribArray(index: Int) = gl.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = gl.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        gl.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun createVertexArray(): Int = handle.also { Gdx.gl30.glGenVertexArrays(1, it, 0) }[0]
    override fun bindVertexArray(array: Int) = Gdx.gl30.glBindVertexArray(array)
    override fun deleteVertexArray(array: Int) = Gdx.gl30.glDeleteVertexArrays(1, handle.also { it[0] = array }, 0)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = gl.glDrawElements(mode, count, type, offset)

    override fun createTexture(): Int = gl.glGenTexture()
    override fun bindTexture(target: Int, texture: Int) = gl.glBindTexture(target, texture)
    override fun deleteTexture(texture: Int) = gl.glDeleteTexture(texture)
    override fun activeTexture(unit: Int) = gl.glActiveTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = gl.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        gl.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, (pixels as Bytes?)?.buffer?.clear())
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        gl.glTexSubImage2D(target, level, x, y, width, height, format, type, (pixels as Bytes).buffer.clear())

    override fun createFramebuffer(): Int = gl.glGenFramebuffer()
    override fun bindFramebuffer(target: Int, framebuffer: Int) = gl.glBindFramebuffer(target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = gl.glDeleteFramebuffer(framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        gl.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = gl.glCheckFramebufferStatus(target)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        gl.glReadPixels(x, y, width, height, format, type, (into as Bytes).buffer.clear())

    private const val ContextFlags = 0x821E
    private const val ForwardCompatibleBit = 1
    private const val ContextProfileMask = 0x9126
    private const val CoreProfileBit = 1

    /** Direct memory, written by absolute index: nothing is copied on its way to the driver. */
    private class Floats(val buffer: FloatBuffer) : GlFloats {
        override val capacity: Int get() = buffer.capacity()
        override fun set(index: Int, value: Float) {
            buffer.put(index, value)
        }
    }

    private class Shorts(val buffer: ShortBuffer) : GlShorts {
        override val capacity: Int get() = buffer.capacity()
        override fun set(index: Int, value: Short) {
            buffer.put(index, value)
        }
    }

    private class Bytes(val buffer: ByteBuffer) : GlBytes {
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
    }
}
