package dev.wildware.composegl.korge

import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import korlibs.kgl.KmlGl
import korlibs.kgl.deleteBuffer
import korlibs.kgl.deleteFramebuffer
import korlibs.kgl.deleteTexture
import korlibs.kgl.deleteVertexArray
import korlibs.kgl.genBuffer
import korlibs.kgl.genFramebuffer
import korlibs.kgl.genTexture
import korlibs.kgl.genVertexArray
import korlibs.kgl.getIntegerv
import korlibs.kgl.getProgramInfoLog
import korlibs.kgl.getProgramiv
import korlibs.kgl.getShaderInfoLog
import korlibs.kgl.getShaderiv
import korlibs.memory.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The renderer's [Gl], on the `KmlGl` of the KorGE frame being drawn. One line a call.
 *
 * KorGE lends its context only during a render, so [gl] is set by [KorgeCanvas] for the length of a
 * frame. A delete that arrives outside one — a canvas closed from a game's own thread — waits for
 * the next frame on the same context, which is also how KorGE deletes its own objects.
 */
internal class KorgeKmlGl : Gl {

    /** The context of the frame being drawn, or null between frames. */
    var gl: KmlGl? = null
        set(value) {
            field = value
            if (value != null) last = value
            value?.let(::deleteWaiting)
        }

    /** The context most recently drawn with, which a delete between frames belongs to. */
    private var last: KmlGl? = null

    private val kml: KmlGl get() = checkNotNull(gl) { "KorGE's OpenGL is only there during a render" }

    override val profile: GlProfile
        get() {
            val numbers = Regex("^(\\d+)\\.(\\d+)").find(kml.getString(KmlGl.VERSION).orEmpty())
            val major = numbers?.groupValues?.get(1)?.toInt() ?: 2
            val minor = numbers?.groupValues?.get(2)?.toInt() ?: 1
            val atLeast32 = major > 3 || (major == 3 && minor >= 2)
            val core = atLeast32 && kml.getIntegerv(ContextProfileMask) and 1 != 0
            val forwardCompatible = major >= 3 && kml.getIntegerv(ContextFlags) and 1 != 0
            // Desktop: this backend runs on the JVM, where KorGE opens a desktop OpenGL context.
            return GlProfile(GlApi.Desktop, major, minor, core = core || forwardCompatible)
        }

    override fun hasExtension(name: String): Boolean = name in kml.extensions

    override fun floats(capacity: Int): GlFloats = Floats(capacity)
    override fun shorts(capacity: Int): GlShorts = Shorts(capacity)
    override fun bytes(capacity: Int): GlBytes = Bytes(capacity)

    override fun enable(cap: Int) = kml.enable(cap)
    override fun disable(cap: Int) = kml.disable(cap)
    override fun isEnabled(cap: Int): Boolean = kml.isEnabled(cap)
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) = kml.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = kml.blendEquationSeparate(rgb, alpha)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = kml.colorMask(red, green, blue, alpha)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = kml.viewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = kml.scissor(x, y, width, height)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = kml.clearColor(red, green, blue, alpha)
    override fun clear(mask: Int) = kml.clear(mask)
    override fun getInteger(name: Int): Int = kml.getIntegerv(name)
    override fun getIntegers(name: Int, into: IntArray) {
        kml.getIntegerv(name, integers)
        for (index in into.indices) into[index] = integerBytes.getInt(index * 4)
    }
    override fun pixelStorei(name: Int, value: Int) = kml.pixelStorei(name, value)

    override fun createShader(type: Int): Int = kml.createShader(type)
    // ASCII only: KorGE's JVM binding hands the driver the length in characters, not bytes, so a
    // comment with an em dash in it would arrive a few bytes short.
    override fun shaderSource(shader: Int, source: String) = kml.shaderSource(shader, String(CharArray(source.length) { source[it].takeIf { c -> c.code < 0x80 } ?: ' ' }))
    override fun compileShader(shader: Int) = kml.compileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean = kml.getShaderiv(shader, KmlGl.COMPILE_STATUS) != 0
    override fun shaderInfoLog(shader: Int): String = kml.getShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = kml.deleteShader(shader)
    override fun createProgram(): Int = kml.createProgram()
    override fun attachShader(program: Int, shader: Int) = kml.attachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = kml.bindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = kml.linkProgram(program)
    override fun programLinked(program: Int): Boolean = kml.getProgramiv(program, KmlGl.LINK_STATUS) != 0
    override fun programInfoLog(program: Int): String = kml.getProgramInfoLog(program)
    override fun deleteProgram(program: Int) = later { it.deleteProgram(program) }
    override fun useProgram(program: Int) = kml.useProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = kml.getUniformLocation(program, name)
    override fun uniform1i(at: Int, value: Int) = kml.uniform1i(at, value)
    override fun uniform1f(at: Int, value: Float) = kml.uniform1f(at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = kml.uniform2f(at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = kml.uniform3f(at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = kml.uniform4f(at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) {
        for (index in 0 until 16) matrixBytes.putFloat(index * 4, matrix[index])
        kml.uniformMatrix4fv(at, 1, false, this.matrix)
    }

    override fun createBuffer(): Int = kml.genBuffer()
    override fun bindBuffer(target: Int, buffer: Int) = kml.bindBuffer(target, buffer)
    override fun deleteBuffer(buffer: Int) = later { it.deleteBuffer(buffer) }
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) = kml.bufferData(target, count * 4, (data as Floats).buffer, usage)
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) = kml.bufferData(target, count * 2, (data as Shorts).buffer, usage)
    override fun enableVertexAttribArray(index: Int) = kml.enableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = kml.disableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        kml.vertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    override fun createVertexArray(): Int = kml.genVertexArray()
    override fun bindVertexArray(array: Int) = kml.bindVertexArray(array)
    override fun deleteVertexArray(array: Int) = later { it.deleteVertexArray(array) }
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = kml.drawElements(mode, count, type, offset)

    override fun createTexture(): Int = kml.genTexture()
    override fun bindTexture(target: Int, texture: Int) = kml.bindTexture(target, texture)
    override fun deleteTexture(texture: Int) = later { it.deleteTexture(texture) }
    override fun activeTexture(unit: Int) = kml.activeTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = kml.texParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        kml.texImage2D(target, level, internalFormat, width, height, 0, format, type, (pixels as Bytes?)?.buffer)
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        kml.texSubImage2D(target, level, x, y, width, height, format, type, (pixels as Bytes).buffer)

    override fun createFramebuffer(): Int = kml.genFramebuffer()
    override fun bindFramebuffer(target: Int, framebuffer: Int) = kml.bindFramebuffer(target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = later { it.deleteFramebuffer(framebuffer) }
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        kml.framebufferTexture2D(target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = kml.checkFramebufferStatus(target)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        kml.readPixels(x, y, width, height, format, type, (into as Bytes).buffer)

    private val integerBytes = native(16)
    private val integers = Buffer(integerBytes)
    private val matrixBytes = native(64)
    private val matrix = Buffer(matrixBytes)

    /** Now, inside a frame; otherwise on the next frame drawn on the context it belongs to. */
    private inline fun later(crossinline delete: (KmlGl) -> Unit) {
        val now = gl
        if (now != null) delete(now) else last?.let { owner -> waiting += owner to { delete(it) } }
    }

    private fun deleteWaiting(on: KmlGl) {
        if (waiting.isEmpty()) return
        val mine = waiting.filter { it.first === on }
        waiting.removeAll(mine.toSet())
        mine.forEach { (_, delete) -> delete(on) }
    }

    /** Direct memory in the platform's order, written by absolute index: nothing copied on its way to the driver. */
    private class Floats(override val capacity: Int) : GlFloats {
        private val bytes = native(capacity * 4)
        val buffer = Buffer(bytes)
        override fun set(index: Int, value: Float) {
            bytes.putFloat(index * 4, value)
        }
    }

    private class Shorts(override val capacity: Int) : GlShorts {
        private val bytes = native(capacity * 2)
        val buffer = Buffer(bytes)
        override fun set(index: Int, value: Short) {
            bytes.putShort(index * 2, value)
        }
    }

    private class Bytes(override val capacity: Int) : GlBytes {
        private val bytes = native(capacity)
        val buffer = Buffer(bytes)
        override fun get(index: Int): Byte = bytes.get(index)
        override fun set(index: Int, value: Byte) {
            bytes.put(index, value)
        }
        override fun put(at: Int, from: ByteArray, offset: Int, count: Int) {
            bytes.clear().position(at)
            bytes.put(from, offset, count)
            bytes.clear()
        }
    }

    private companion object {
        const val ContextProfileMask = 0x9126
        const val ContextFlags = 0x821E

        /** GL objects let go of between frames, with the context they belong to. */
        val waiting = ConcurrentLinkedQueue<Pair<KmlGl, (KmlGl) -> Unit>>()

        fun native(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(maxOf(bytes, 1)).order(ByteOrder.nativeOrder())
    }
}
