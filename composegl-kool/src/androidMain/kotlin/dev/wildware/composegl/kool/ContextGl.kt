package dev.wildware.composegl.kool

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Kool's Android OpenGL is OpenGL ES 3 on its `GLSurfaceView`'s context, current on the view's render
 * thread, so the calls are `android.opengl`'s. One line a call; Android names a count in bytes where
 * the renderer names one in elements, and hands object names back through an array.
 */
internal actual object ContextGl : Gl {

    actual val current: Boolean get() = EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT

    actual fun depthFunc(func: Int) = GLES20.glDepthFunc(func)

    actual fun cullFace(mode: Int) = GLES20.glCullFace(mode)

    actual fun lineWidth(width: Float) = GLES20.glLineWidth(width)

    actual fun currentLineWidth(): Float = FloatArray(1).also { GLES20.glGetFloatv(GLES20.GL_LINE_WIDTH, it, 0) }[0]

    override val profile: GlProfile
        get() {
            // "OpenGL ES 3.0 SwiftShader 4.0.0.1": the numbers come after the name.
            val numbers = Regex("(\\d+)\\.(\\d+)").find(GLES20.glGetString(GLES20.GL_VERSION).orEmpty())
            return GlProfile(GlApi.Es, numbers?.groupValues?.get(1)?.toInt() ?: 3, numbers?.groupValues?.get(2)?.toInt() ?: 0)
        }

    override fun hasExtension(name: String): Boolean =
        (0 until getInteger(GLES30.GL_NUM_EXTENSIONS)).any { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) == name }

    override fun floats(capacity: Int): GlFloats = Floats(direct(capacity * Float.SIZE_BYTES).asFloatBuffer())
    override fun shorts(capacity: Int): GlShorts = Shorts(direct(capacity * Short.SIZE_BYTES).asShortBuffer())
    override fun bytes(capacity: Int): GlBytes = Bytes(direct(capacity))

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
    override fun clearDepth(depth: Float) = GLES20.glClearDepthf(depth)
    override fun depthMask(write: Boolean) = GLES20.glDepthMask(write)
    override fun clear(mask: Int) = GLES20.glClear(mask)
    override fun getInteger(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]
    override fun getIntegers(name: Int, into: IntArray) = GLES20.glGetIntegerv(name, into, 0)
    override fun pixelStorei(name: Int, value: Int) = GLES20.glPixelStorei(name, value)

    override fun createShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean =
        IntArray(1).also { GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, it, 0) }[0] == GLES20.GL_TRUE
    override fun shaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)
    override fun createProgram(): Int = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GLES20.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean =
        IntArray(1).also { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, it, 0) }[0] == GLES20.GL_TRUE
    override fun programInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    override fun uniform1i(at: Int, value: Int) = GLES20.glUniform1i(at, value)
    override fun uniform1f(at: Int, value: Float) = GLES20.glUniform1f(at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = GLES20.glUniform2f(at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = GLES20.glUniform3f(at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = GLES20.glUniformMatrix4fv(at, 1, false, matrix, 0)

    override fun createBuffer(): Int = generated { n, names -> GLES20.glGenBuffers(n, names, 0) }
    override fun bindBuffer(target: Int, buffer: Int) = GLES20.glBindBuffer(target, buffer)
    override fun deleteBuffer(buffer: Int) = GLES20.glDeleteBuffers(1, intArrayOf(buffer), 0)
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) =
        GLES20.glBufferData(target, count * Float.SIZE_BYTES, rewound((data as Floats).buffer), usage)
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) =
        GLES20.glBufferData(target, count * Short.SIZE_BYTES, rewound((data as Shorts).buffer), usage)
    override fun enableVertexAttribArray(index: Int) = GLES20.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GLES20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun createVertexArray(): Int = generated { n, names -> GLES30.glGenVertexArrays(n, names, 0) }
    override fun bindVertexArray(array: Int) = GLES30.glBindVertexArray(array)
    override fun deleteVertexArray(array: Int) = GLES30.glDeleteVertexArrays(1, intArrayOf(array), 0)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GLES20.glDrawElements(mode, count, type, offset)

    override fun createTexture(): Int = generated { n, names -> GLES20.glGenTextures(n, names, 0) }
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun deleteTexture(texture: Int) = GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    override fun activeTexture(unit: Int) = GLES20.glActiveTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        GLES20.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, (pixels as Bytes?)?.buffer?.let(::rewound))
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        GLES20.glTexSubImage2D(target, level, x, y, width, height, format, type, rewound((pixels as Bytes).buffer))

    override fun createFramebuffer(): Int = generated { n, names -> GLES20.glGenFramebuffers(n, names, 0) }
    override fun bindFramebuffer(target: Int, framebuffer: Int) = GLES20.glBindFramebuffer(target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        GLES20.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = GLES20.glCheckFramebufferStatus(target)
    override fun createRenderbuffer(): Int = generated { n, names -> GLES20.glGenRenderbuffers(n, names, 0) }
    override fun bindRenderbuffer(target: Int, renderbuffer: Int) = GLES20.glBindRenderbuffer(target, renderbuffer)
    override fun deleteRenderbuffer(renderbuffer: Int) = GLES20.glDeleteRenderbuffers(1, intArrayOf(renderbuffer), 0)
    override fun renderbufferStorage(target: Int, format: Int, width: Int, height: Int) =
        GLES20.glRenderbufferStorage(target, format, width, height)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        GLES20.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        GLES20.glReadPixels(x, y, width, height, format, type, rewound((into as Bytes).buffer))

    /** One name from a `glGen*` call, which Android fills into an array. */
    private inline fun generated(gen: (Int, IntArray) -> Unit): Int = IntArray(1).also { gen(1, it) }[0]

    /**
     * The whole of [buffer], from its start. Called through [Buffer] because the narrower `clear` a
     * newer JDK declares on each buffer class is not on every Android version this runs on.
     */
    private fun rewound(buffer: Buffer): Buffer = buffer.clear()

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

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
            (buffer as Buffer).clear().position(at)
            buffer.put(from, offset, count)
            (buffer as Buffer).clear()
        }
    }
}
