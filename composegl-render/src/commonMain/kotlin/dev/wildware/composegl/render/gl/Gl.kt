package dev.wildware.composegl.render.gl

/**
 * OpenGL, as the renderer calls it: the whole of what a backend implements to draw.
 *
 * One set of calls for desktop GL 2.1 and 3.2+ core, OpenGL ES 2 and 3, and WebGL 1 and 2. Each
 * member is meant to be a one-liner onto the backend's own binding — LWJGL, `Gdx.gl`, `KmlGl`, a
 * `WebGLRenderingContext`. Logic does not belong in an implementation; `GlDevice` holds all of it.
 *
 * Handles are `Int`, and 0 means none. A binding whose objects are not integers (WebGL) keeps a
 * table. Constants are OpenGL's own numbers — see [GlConst].
 */
interface Gl {

    /** Which OpenGL this is. Read once, when the device first needs it. */
    val profile: GlProfile

    fun hasExtension(name: String): Boolean

    // Memory the binding allocates, so each platform picks storage it can upload without a copy.
    fun floats(capacity: Int): GlFloats
    fun shorts(capacity: Int): GlShorts
    fun bytes(capacity: Int): GlBytes

    // state
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun isEnabled(cap: Int): Boolean
    fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int)
    fun blendEquationSeparate(rgb: Int, alpha: Int)
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun clearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun clear(mask: Int)
    fun getInteger(name: Int): Int

    /** For the queries that answer several numbers: `VIEWPORT`, `SCISSOR_BOX`, `COLOR_WRITEMASK`. */
    fun getIntegers(name: Int, into: IntArray)
    fun pixelStorei(name: Int, value: Int)

    // shaders
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun shaderCompiled(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun programLinked(program: Int): Boolean
    fun programInfoLog(program: Int): String
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)

    /** -1 when the program has no such uniform, which is not an error: drivers drop unused ones. */
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(at: Int, value: Int)
    fun uniform1f(at: Int, value: Float)
    fun uniform2f(at: Int, x: Float, y: Float)
    fun uniform3f(at: Int, x: Float, y: Float, z: Float)
    fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float)

    /** Sixteen floats, column-major, not transposed. */
    fun uniformMatrix4fv(at: Int, matrix: FloatArray)

    // buffers and vertex arrays
    fun createBuffer(): Int
    fun bindBuffer(target: Int, buffer: Int)
    fun deleteBuffer(buffer: Int)

    /** The first [count] floats of [data]. */
    fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int)

    /** The first [count] shorts of [data]. */
    fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)

    /** Only called when the device decided the context has vertex array objects. */
    fun createVertexArray(): Int
    fun bindVertexArray(array: Int)
    fun deleteVertexArray(array: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)

    // textures
    fun createTexture(): Int
    fun bindTexture(target: Int, texture: Int)
    fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun texParameteri(target: Int, name: Int, value: Int)

    /** Null [pixels] makes the storage without filling it. */
    @Suppress("LongParameterList")
    fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?)

    @Suppress("LongParameterList")
    fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes)

    // framebuffers
    fun createFramebuffer(): Int
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun deleteFramebuffer(framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int

    @Suppress("LongParameterList")
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes)
}

/** Which family of OpenGL a context is. */
enum class GlApi { Desktop, Es, WebGl }

/**
 * The context's OpenGL: its family, its version, and whether it is a core (or forward-compatible)
 * profile that has dropped the old ways — no vertex array object means nothing is drawn.
 *
 * WebGL 1 is `WebGl` 1.0 and WebGL 2 is `WebGl` 2.0.
 */
data class GlProfile(val api: GlApi, val major: Int, val minor: Int, val core: Boolean = false) {

    /** At least [major].[minor]. */
    fun atLeast(major: Int, minor: Int): Boolean =
        this.major > major || (this.major == major && this.minor >= minor)
}

/** Float storage a binding can upload directly. Written by absolute index. */
interface GlFloats {
    val capacity: Int
    operator fun set(index: Int, value: Float)
}

/** Short storage a binding can upload directly. */
interface GlShorts {
    val capacity: Int
    operator fun set(index: Int, value: Short)
}

/** Byte storage a binding can upload and read back directly. */
interface GlBytes {
    val capacity: Int
    operator fun get(index: Int): Byte
    operator fun set(index: Int, value: Byte)

    /** [count] bytes of [from], starting at [offset], written from [at]. */
    fun put(at: Int, from: ByteArray, offset: Int, count: Int)
}
