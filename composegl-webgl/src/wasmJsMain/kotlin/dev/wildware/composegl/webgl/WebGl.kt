package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.gl.Gl
import dev.wildware.composegl.render.gl.GlApi
import dev.wildware.composegl.render.gl.GlBytes
import dev.wildware.composegl.render.gl.GlFloats
import dev.wildware.composegl.render.gl.GlProfile
import dev.wildware.composegl.render.gl.GlShorts
import org.khronos.webgl.WebGLRenderingContext as GL
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.wasmMemory
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The renderer's [Gl], on a page's `WebGLRenderingContext` or `WebGL2RenderingContext`. One line a
 * call, and nothing else.
 *
 * WebGL's objects are JavaScript objects, not numbers, so they live in one table for the page and a
 * handle is a place in it. Each object remembers its own place, which is how a question like "which
 * framebuffer is bound" comes back as a number, and how a texture or framebuffer the page made itself
 * gets a handle the first time the renderer meets it.
 *
 * On WebGL 1 the vertex array objects come from `OES_vertex_array_object`, when the browser has it.
 */
class WebGl(val context: GL) : Gl {

    private val c: JsAny = binding(context, Table)

    override val profile: GlProfile = GlProfile(GlApi.WebGl, if (isWebGl2(context)) 2 else 1, 0)

    init {
        // A picture goes up exactly as its bytes say: not premultiplied, no colour profile applied.
        context.pixelStorei(GL.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)
        context.pixelStorei(GL.UNPACK_COLORSPACE_CONVERSION_WEBGL, GL.NONE)
    }

    /** After `webglcontextrestored`: extension objects from the lost context are dead too. */
    fun restored() = forgetExtensions(c)

    override fun hasExtension(name: String): Boolean = hasExtension(c, name)

    // Kotlin arrays, written with no call into the page at all, and handed over at upload through
    // the module's own memory in one copy. A call into the page per number is what costs on Wasm.
    override fun floats(capacity: Int): GlFloats = Floats(FloatArray(capacity))
    override fun shorts(capacity: Int): GlShorts = Shorts(ShortArray(capacity))
    override fun bytes(capacity: Int): GlBytes = Bytes(ByteArray(capacity))

    override fun enable(cap: Int) = context.enable(cap)
    override fun disable(cap: Int) = context.disable(cap)
    override fun isEnabled(cap: Int): Boolean = context.isEnabled(cap)
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) =
        context.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = context.blendEquationSeparate(rgb, alpha)
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = context.colorMask(red, green, blue, alpha)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = context.viewport(x, y, width, height)
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = context.scissor(x, y, width, height)
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = context.clearColor(red, green, blue, alpha)
    override fun clear(mask: Int) = context.clear(mask)
    override fun getInteger(name: Int): Int = getInteger(c, name)
    override fun getIntegers(name: Int, into: IntArray) {
        for (index in into.indices) into[index] = getIntegerAt(c, name, index)
    }
    override fun pixelStorei(name: Int, value: Int) = context.pixelStorei(name, value)

    override fun createShader(type: Int): Int = createShader(c, type)
    override fun shaderSource(shader: Int, source: String) = shaderSource(c, shader, source)
    override fun compileShader(shader: Int) = compileShader(c, shader)
    override fun shaderCompiled(shader: Int): Boolean = shaderCompiled(c, shader)
    override fun shaderInfoLog(shader: Int): String = shaderInfoLog(c, shader)
    override fun deleteShader(shader: Int) = deleteShader(c, shader)
    override fun createProgram(): Int = createProgram(c)
    override fun attachShader(program: Int, shader: Int) = attachShader(c, program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = bindAttribLocation(c, program, index, name)
    override fun linkProgram(program: Int) = linkProgram(c, program)
    override fun programLinked(program: Int): Boolean = programLinked(c, program)
    override fun programInfoLog(program: Int): String = programInfoLog(c, program)
    override fun deleteProgram(program: Int) = deleteProgram(c, program)
    override fun useProgram(program: Int) = useProgram(c, program)
    override fun getUniformLocation(program: Int, name: String): Int = getUniformLocation(c, program, name)
    override fun uniform1i(at: Int, value: Int) = uniform1i(c, at, value)
    override fun uniform1f(at: Int, value: Float) = uniform1f(c, at, value)
    override fun uniform2f(at: Int, x: Float, y: Float) = uniform2f(c, at, x, y)
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = uniform3f(c, at, x, y, z)
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = uniform4f(c, at, x, y, z, w)
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) {
        for (index in 0 until 16) matrixAt(c, index, matrix[index])
        uniformMatrix4fv(c, at)
    }

    override fun createBuffer(): Int = createBuffer(c)
    override fun bindBuffer(target: Int, buffer: Int) = bindBuffer(c, target, buffer)
    override fun deleteBuffer(buffer: Int) = deleteBuffer(c, buffer)
    @OptIn(UnsafeWasmMemoryApi::class)
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) = withScopedMemoryAllocator { allocator ->
        val floats = (data as Floats).array
        val pointer = allocator.allocate(count * 4)
        for (at in 0 until count) (pointer + at * 4).storeInt(floats[at].toRawBits())
        bufferData(c, target, wasmMemory, pointer.address.toInt(), count * 4, usage)
    }
    @OptIn(UnsafeWasmMemoryApi::class)
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) = withScopedMemoryAllocator { allocator ->
        val shorts = (data as Shorts).array
        val pointer = allocator.allocate(count * 2)
        for (at in 0 until count) (pointer + at * 2).storeShort(shorts[at])
        bufferData(c, target, wasmMemory, pointer.address.toInt(), count * 2, usage)
    }
    override fun enableVertexAttribArray(index: Int) = context.enableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = context.disableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        context.vertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun createVertexArray(): Int = createVertexArray(c)
    override fun bindVertexArray(array: Int) = bindVertexArray(c, array)
    override fun deleteVertexArray(array: Int) = deleteVertexArray(c, array)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = context.drawElements(mode, count, type, offset)

    override fun createTexture(): Int = createTexture(c)
    override fun bindTexture(target: Int, texture: Int) = bindTexture(c, target, texture)
    override fun deleteTexture(texture: Int) = deleteTexture(c, texture)
    override fun activeTexture(unit: Int) = context.activeTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = context.texParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) {
        if (pixels == null) return texImage2D(c, target, level, internalFormat, width, height, format, type, wasmMemory, 0, 0)
        withBytes((pixels as Bytes).array, width * height * 4) { address, bytes ->
            texImage2D(c, target, level, internalFormat, width, height, format, type, wasmMemory, address, bytes)
        }
    }
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        withBytes((pixels as Bytes).array, width * height * 4) { address, bytes ->
            texSubImage2D(c, target, level, x, y, width, height, format, type, wasmMemory, address, bytes)
        }

    override fun createFramebuffer(): Int = createFramebuffer(c)
    override fun bindFramebuffer(target: Int, framebuffer: Int) = bindFramebuffer(c, target, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = deleteFramebuffer(c, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        framebufferTexture2D(c, target, attachment, textureTarget, texture, level)
    override fun checkFramebufferStatus(target: Int): Int = context.checkFramebufferStatus(target)
    @OptIn(UnsafeWasmMemoryApi::class)
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) = withScopedMemoryAllocator { allocator ->
        val bytes = width * height * 4
        val pointer = allocator.allocate(bytes)
        readPixels(c, x, y, width, height, format, type, wasmMemory, pointer.address.toInt(), bytes)
        val array = (into as Bytes).array
        for (at in 0 until bytes) array[at] = (pointer + at).loadByte()
    }

    private class Floats(val array: FloatArray) : GlFloats {
        override val capacity: Int get() = array.size
        override fun set(index: Int, value: Float) = array.set(index, value)
    }

    private class Shorts(val array: ShortArray) : GlShorts {
        override val capacity: Int get() = array.size
        override fun set(index: Int, value: Short) = array.set(index, value)
    }

    private class Bytes(val array: ByteArray) : GlBytes {
        override val capacity: Int get() = array.size
        override fun get(index: Int): Byte = array[index]
        override fun set(index: Int, value: Byte) = array.set(index, value)
        override fun put(at: Int, from: ByteArray, offset: Int, count: Int) {
            from.copyInto(array, at, offset, offset + count)
        }
    }

    /** The first [bytes] of [array], copied into the module's memory, handed to [use] as an address. */
    @OptIn(UnsafeWasmMemoryApi::class)
    private inline fun withBytes(array: ByteArray, bytes: Int, use: (Int, Int) -> Unit) = withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(bytes)
        for (at in 0 until bytes) (pointer + at).storeByte(array[at])
        use(pointer.address.toInt(), bytes)
    }

    companion object {
        /** Every WebGL object the renderer has a handle for, on every context on the page. Place 0 is none. */
        private val Table: JsAny = newTable()

        /** The handle for [thing] — a `WebGLTexture`, a `WebGLFramebuffer` — giving it one if it has none. */
        internal fun handleOf(thing: JsAny?): Int = handleOf(Table, thing)

        /** The object behind [handle], or null. */
        internal fun <T : JsAny> objectOf(handle: Int): T? = objectOf(Table, handle)
    }
}

/** Draws nothing: the page's own canvas, cleared to one colour, before a frame goes on top. */
internal fun clearPage(gl: GL, width: Int, height: Int, red: Float, green: Float, blue: Float) {
    gl.bindFramebuffer(GL.FRAMEBUFFER, null)
    gl.disable(GL.SCISSOR_TEST)
    gl.viewport(0, 0, width, height)
    gl.clearColor(red, green, blue, 1f)
    gl.clear(GL.COLOR_BUFFER_BIT)
}

internal fun isWebGl2(gl: GL): Boolean = js("typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext")

private fun newTable(): JsAny = js("[null]")
private fun handleOf(t: JsAny, o: JsAny?): Int = js("o == null ? 0 : (o.__composegl || (o.__composegl = t.push(o) - 1))")
private fun <T : JsAny> objectOf(t: JsAny, id: Int): T? = js("t[id] || null")
private fun binding(gl: GL, t: JsAny): JsAny = js("({ gl: gl, t: t, two: typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext, v: undefined, m: new Float32Array(16) })")
private fun forgetExtensions(c: JsAny): Unit = js("{ c.v = undefined; }")
private fun hasExtension(c: JsAny, name: String): Boolean = js("c.gl.getExtension(name) != null")

/** A number, or for a bound object its handle. */
private fun getInteger(c: JsAny, name: Int): Int =
    js("(() => { const v = c.gl.getParameter(name); if (v == null) return 0; if (typeof v === 'object') return v.__composegl || (v.__composegl = c.t.push(v) - 1); return +v; })()")
private fun getIntegerAt(c: JsAny, name: Int, index: Int): Int = js("(() => { const v = c.gl.getParameter(name); return v == null ? 0 : +v[index]; })()")

private fun createShader(c: JsAny, type: Int): Int = js("(() => { const o = c.gl.createShader(type); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()")
private fun shaderSource(c: JsAny, id: Int, source: String): Unit = js("{ c.gl.shaderSource(c.t[id], source); }")
private fun compileShader(c: JsAny, id: Int): Unit = js("{ c.gl.compileShader(c.t[id]); }")
private fun shaderCompiled(c: JsAny, id: Int): Boolean = js("!!c.gl.getShaderParameter(c.t[id], c.gl.COMPILE_STATUS)")
private fun shaderInfoLog(c: JsAny, id: Int): String = js("c.gl.getShaderInfoLog(c.t[id]) || ''")
private fun deleteShader(c: JsAny, id: Int): Unit = js("{ c.gl.deleteShader(c.t[id] || null); if (id) c.t[id] = null; }")
private fun createProgram(c: JsAny): Int = js("(() => { const o = c.gl.createProgram(); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()")
private fun attachShader(c: JsAny, program: Int, shader: Int): Unit = js("{ c.gl.attachShader(c.t[program], c.t[shader]); }")
private fun bindAttribLocation(c: JsAny, program: Int, index: Int, name: String): Unit = js("{ c.gl.bindAttribLocation(c.t[program], index, name); }")
private fun linkProgram(c: JsAny, id: Int): Unit = js("{ c.gl.linkProgram(c.t[id]); }")
private fun programLinked(c: JsAny, id: Int): Boolean = js("!!c.gl.getProgramParameter(c.t[id], c.gl.LINK_STATUS)")
private fun programInfoLog(c: JsAny, id: Int): String = js("c.gl.getProgramInfoLog(c.t[id]) || ''")
private fun deleteProgram(c: JsAny, id: Int): Unit = js("{ c.gl.deleteProgram(c.t[id] || null); if (id) c.t[id] = null; }")
private fun useProgram(c: JsAny, id: Int): Unit = js("{ c.gl.useProgram(c.t[id] || null); }")
private fun getUniformLocation(c: JsAny, program: Int, name: String): Int =
    js("(() => { const o = c.gl.getUniformLocation(c.t[program], name); return o == null ? -1 : c.t.push(o) - 1; })()")
private fun uniform1i(c: JsAny, at: Int, v: Int): Unit = js("{ c.gl.uniform1i(c.t[at] || null, v); }")
private fun uniform1f(c: JsAny, at: Int, v: Float): Unit = js("{ c.gl.uniform1f(c.t[at] || null, v); }")
private fun uniform2f(c: JsAny, at: Int, x: Float, y: Float): Unit = js("{ c.gl.uniform2f(c.t[at] || null, x, y); }")
private fun uniform3f(c: JsAny, at: Int, x: Float, y: Float, z: Float): Unit = js("{ c.gl.uniform3f(c.t[at] || null, x, y, z); }")
private fun uniform4f(c: JsAny, at: Int, x: Float, y: Float, z: Float, w: Float): Unit = js("{ c.gl.uniform4f(c.t[at] || null, x, y, z, w); }")
private fun matrixAt(c: JsAny, index: Int, value: Float): Unit = js("{ c.m[index] = value; }")
private fun uniformMatrix4fv(c: JsAny, at: Int): Unit = js("{ c.gl.uniformMatrix4fv(c.t[at] || null, false, c.m); }")

private fun createBuffer(c: JsAny): Int = js("(() => { const o = c.gl.createBuffer(); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()")
private fun bindBuffer(c: JsAny, target: Int, id: Int): Unit = js("{ c.gl.bindBuffer(target, c.t[id] || null); }")
private fun deleteBuffer(c: JsAny, id: Int): Unit = js("{ c.gl.deleteBuffer(c.t[id] || null); if (id) c.t[id] = null; }")
private fun bufferData(c: JsAny, target: Int, memory: JsAny, address: Int, bytes: Int, usage: Int): Unit =
    js("{ c.gl.bufferData(target, new Uint8Array(memory.buffer, address, bytes), usage); }")

private fun createVertexArray(c: JsAny): Int = js(
    "(() => { if (!c.two && c.v === undefined) c.v = c.gl.getExtension('OES_vertex_array_object'); const o = c.two ? c.gl.createVertexArray() : c.v.createVertexArrayOES(); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()",
)
private fun bindVertexArray(c: JsAny, id: Int): Unit = js(
    "{ const o = c.t[id] || null; if (c.two) c.gl.bindVertexArray(o); else { if (c.v === undefined) c.v = c.gl.getExtension('OES_vertex_array_object'); c.v.bindVertexArrayOES(o); } }",
)
private fun deleteVertexArray(c: JsAny, id: Int): Unit = js(
    "{ const o = c.t[id] || null; if (c.two) c.gl.deleteVertexArray(o); else { if (c.v === undefined) c.v = c.gl.getExtension('OES_vertex_array_object'); c.v.deleteVertexArrayOES(o); } if (id) c.t[id] = null; }",
)

private fun createTexture(c: JsAny): Int = js("(() => { const o = c.gl.createTexture(); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()")
private fun bindTexture(c: JsAny, target: Int, id: Int): Unit = js("{ c.gl.bindTexture(target, c.t[id] || null); }")
private fun deleteTexture(c: JsAny, id: Int): Unit = js("{ c.gl.deleteTexture(c.t[id] || null); if (id) c.t[id] = null; }")
@Suppress("LongParameterList")
private fun texImage2D(c: JsAny, target: Int, level: Int, internal: Int, width: Int, height: Int, format: Int, type: Int, memory: JsAny, address: Int, bytes: Int): Unit =
    js("{ c.gl.texImage2D(target, level, internal, width, height, 0, format, type, bytes ? new Uint8Array(memory.buffer, address, bytes) : null); }")
@Suppress("LongParameterList")
private fun texSubImage2D(c: JsAny, target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, memory: JsAny, address: Int, bytes: Int): Unit =
    js("{ c.gl.texSubImage2D(target, level, x, y, width, height, format, type, new Uint8Array(memory.buffer, address, bytes)); }")
@Suppress("LongParameterList")
private fun readPixels(c: JsAny, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, memory: JsAny, address: Int, bytes: Int): Unit =
    js("{ c.gl.readPixels(x, y, width, height, format, type, new Uint8Array(memory.buffer, address, bytes)); }")

private fun createFramebuffer(c: JsAny): Int = js("(() => { const o = c.gl.createFramebuffer(); return o == null ? 0 : (o.__composegl = c.t.push(o) - 1); })()")
private fun bindFramebuffer(c: JsAny, target: Int, id: Int): Unit = js("{ c.gl.bindFramebuffer(target, c.t[id] || null); }")
private fun deleteFramebuffer(c: JsAny, id: Int): Unit = js("{ c.gl.deleteFramebuffer(c.t[id] || null); if (id) c.t[id] = null; }")
@Suppress("LongParameterList")
private fun framebufferTexture2D(c: JsAny, target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int): Unit =
    js("{ c.gl.framebufferTexture2D(target, attachment, textureTarget, c.t[texture] || null, level); }")
