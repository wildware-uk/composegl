package dev.wildware.composegl.render.gl

/**
 * OpenGL that writes down every call and draws nothing: what the device's own tests run against.
 *
 * Every object is a fresh number. Queries answer from [integers], so a test can say what the
 * engine left bound and check that it comes back.
 */
class RecordingGl(
    override var profile: GlProfile = GlProfile(GlApi.Desktop, 2, 1),
    val extensions: Set<String> = emptySet(),
) : Gl {

    /** One line a call, in order. Uploads of vertex data are written down without their contents. */
    val calls = ArrayList<String>()

    /** What `getInteger` answers; zero for anything not here. */
    val integers = HashMap<Int, Int>().also { it[GlConst.MAX_TEXTURE_SIZE] = 8192 }

    /** What `getIntegers` fills for four-number queries. */
    val quads = HashMap<Int, IntArray>()

    /** Capabilities `isEnabled` says are on. */
    val enabled = HashSet<Int>()

    /** Each shader's source, by its name, as it was handed to the driver. */
    val sources = HashMap<Int, String>()

    /** Set to make the next compile fail with this log. */
    var failCompile: String? = null

    private var next = 1

    /** The calls whose names start with [prefix]. */
    fun named(prefix: String): List<String> = calls.filter { it.startsWith(prefix) }

    private fun log(call: String) {
        calls += call
    }

    private fun made(what: String): Int = next++.also { log("$what=$it") }

    override fun hasExtension(name: String): Boolean = name in extensions

    override fun floats(capacity: Int): GlFloats = Floats(capacity)
    override fun shorts(capacity: Int): GlShorts = Shorts(capacity)
    override fun bytes(capacity: Int): GlBytes = Bytes(capacity)

    private class Floats(override val capacity: Int) : GlFloats {
        val values = FloatArray(capacity)
        override fun set(index: Int, value: Float) {
            values[index] = value
        }
    }

    private class Shorts(override val capacity: Int) : GlShorts {
        val values = ShortArray(capacity)
        override fun set(index: Int, value: Short) {
            values[index] = value
        }
    }

    private class Bytes(override val capacity: Int) : GlBytes {
        val values = ByteArray(capacity)
        override fun get(index: Int) = values[index]
        override fun set(index: Int, value: Byte) {
            values[index] = value
        }
        override fun put(at: Int, from: ByteArray, offset: Int, count: Int) {
            from.copyInto(values, at, offset, offset + count)
        }
    }

    override fun enable(cap: Int) {
        enabled += cap
        log("enable($cap)")
    }

    override fun disable(cap: Int) {
        enabled -= cap
        log("disable($cap)")
    }

    override fun isEnabled(cap: Int): Boolean = (cap in enabled).also { log("isEnabled($cap)") }
    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) = log("blendFuncSeparate($srcRgb, $dstRgb, $srcAlpha, $dstAlpha)")
    override fun blendEquationSeparate(rgb: Int, alpha: Int) = log("blendEquationSeparate($rgb, $alpha)")
    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = log("colorMask($red, $green, $blue, $alpha)")
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = log("viewport($x, $y, $width, $height)")
    override fun scissor(x: Int, y: Int, width: Int, height: Int) = log("scissor($x, $y, $width, $height)")
    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = log("clearColor")
    override fun clearDepth(depth: Float) = log("clearDepth($depth)")
    override fun depthMask(write: Boolean) = log("depthMask($write)")
    override fun clear(mask: Int) = log("clear($mask)")
    override fun getInteger(name: Int): Int = (integers[name] ?: 0).also { log("getInteger($name)") }
    override fun getIntegers(name: Int, into: IntArray) {
        quads[name]?.copyInto(into)
        log("getIntegers($name)")
    }
    override fun pixelStorei(name: Int, value: Int) = log("pixelStorei($name, $value)")

    override fun createShader(type: Int): Int = made("createShader($type)")
    override fun shaderSource(shader: Int, source: String) {
        sources[shader] = source
        log("shaderSource($shader)")
    }
    override fun compileShader(shader: Int) = log("compileShader($shader)")
    override fun shaderCompiled(shader: Int): Boolean = failCompile == null
    override fun shaderInfoLog(shader: Int): String = failCompile.orEmpty().also { failCompile = null }
    override fun deleteShader(shader: Int) = log("deleteShader($shader)")
    override fun createProgram(): Int = made("createProgram")
    override fun attachShader(program: Int, shader: Int) = log("attachShader($program, $shader)")
    override fun bindAttribLocation(program: Int, index: Int, name: String) = log("bindAttribLocation($program, $index, $name)")
    override fun linkProgram(program: Int) = log("linkProgram($program)")
    override fun programLinked(program: Int): Boolean = true
    override fun programInfoLog(program: Int): String = ""
    override fun deleteProgram(program: Int) = log("deleteProgram($program)")
    override fun useProgram(program: Int) = log("useProgram($program)")
    override fun getUniformLocation(program: Int, name: String): Int = name.length
    override fun uniform1i(at: Int, value: Int) = log("uniform1i($at, $value)")
    override fun uniform1f(at: Int, value: Float) = log("uniform1f($at)")
    override fun uniform2f(at: Int, x: Float, y: Float) = log("uniform2f($at)")
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) = log("uniform3f($at)")
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = log("uniform4f($at)")
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = log("uniformMatrix4fv($at)")

    override fun createBuffer(): Int = made("createBuffer")
    override fun bindBuffer(target: Int, buffer: Int) = log("bindBuffer($target, $buffer)")
    override fun deleteBuffer(buffer: Int) = log("deleteBuffer($buffer)")
    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) = log("bufferData($target, floats $count)")
    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) = log("bufferData($target, shorts $count)")
    override fun enableVertexAttribArray(index: Int) = log("enableVertexAttribArray($index)")
    override fun disableVertexAttribArray(index: Int) = log("disableVertexAttribArray($index)")
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        log("vertexAttribPointer($index, $size, $stride, $offset)")
    override fun createVertexArray(): Int = made("createVertexArray")
    override fun bindVertexArray(array: Int) = log("bindVertexArray($array)")
    override fun deleteVertexArray(array: Int) = log("deleteVertexArray($array)")
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = log("drawElements($count)")

    override fun createTexture(): Int = made("createTexture")
    override fun bindTexture(target: Int, texture: Int) = log("bindTexture($texture)")
    override fun deleteTexture(texture: Int) = log("deleteTexture($texture)")
    override fun activeTexture(unit: Int) = log("activeTexture($unit)")
    override fun texParameteri(target: Int, name: Int, value: Int) = log("texParameteri($name, $value)")
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) =
        log("texImage2D($internalFormat, ${width}x$height)")
    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) =
        log("texSubImage2D($x, $y, ${width}x$height)")

    override fun createFramebuffer(): Int = made("createFramebuffer")
    override fun bindFramebuffer(target: Int, framebuffer: Int) = log("bindFramebuffer($framebuffer)")
    override fun deleteFramebuffer(framebuffer: Int) = log("deleteFramebuffer($framebuffer)")
    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        log("framebufferTexture2D($texture)")
    override fun checkFramebufferStatus(target: Int): Int = GlConst.FRAMEBUFFER_COMPLETE
    override fun createRenderbuffer(): Int = made("createRenderbuffer")
    override fun bindRenderbuffer(target: Int, renderbuffer: Int) = log("bindRenderbuffer($renderbuffer)")
    override fun deleteRenderbuffer(renderbuffer: Int) = log("deleteRenderbuffer($renderbuffer)")
    override fun renderbufferStorage(target: Int, format: Int, width: Int, height: Int) =
        log("renderbufferStorage($format, ${width}x$height)")
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        log("framebufferRenderbuffer($attachment, $renderbuffer)")
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) =
        log("readPixels($x, $y, $width, $height)")
}
