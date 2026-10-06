package dev.wildware.composegl.render.gl

import kotlin.math.abs

/**
 * PROFILING ONLY (#242, #267): a [Gl] that counts what each frame asks of the driver, then passes every
 * call through unchanged.
 *
 * Counts every call by name, the state changes that changed something and the ones that did not,
 * the bytes uploaded, the offscreen pictures made, bound and cleared, and the pixels each draw
 * covers — worked out from the vertices actually uploaded, the projection and the viewport and
 * scissor in force — split by what kind of quad covered them.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ProbeGl(private val gl: Gl) : Gl {

    class Frame {
        var calls = 0
        val byName = HashMap<String, Int>()
        var draws = 0
        var drawsOffscreen = 0
        var effectDraws = 0
        var quads = 0
        var vertexBytes = 0L
        var bufferUploads = 0
        var textureUploads = 0
        var textureUploadBytes = 0L
        var textureAllocations = 0
        var textureAllocationBytes = 0L
        var framebuffersMade = 0
        var framebuffersDeleted = 0
        var texturesDeleted = 0
        var framebufferBinds = 0
        var framebufferSwitches = 0
        var programBinds = 0
        var programSwitches = 0
        var textureBinds = 0
        var textureSwitches = 0
        var blendCalls = 0
        var blendChanges = 0
        var capCalls = 0
        var capChanges = 0
        var scissorCalls = 0
        var scissorChanges = 0
        var viewportCalls = 0
        var viewportChanges = 0
        var uniformCalls = 0
        var uniformChanges = 0
        var queries = 0
        var readPixels = 0
        var clears = 0
        var clearedPixels = 0L
        var hostPixels = 0L
        var offscreenPixels = 0L
        var maskedPixels = 0L
        val pixelsByKind = HashMap<String, Long>()
        val quadsByKind = HashMap<String, Int>()
        var screen = 0L
        var phase = ""
        /** Times a framebuffer already drawn into this frame was bound again and drawn into without a clear. */
        var reloads = 0
        var reloadPixels = 0L
        var screenReloads = 0
        /** Quads uploaded that cover no pixel: off the target, outside the scissor, or of no size. */
        var quadsOutsideTarget = 0
        var quadsOutsideScissor = 0
        var quadsOfNoSize = 0
        val breaks = HashMap<String, Int>()
        /** Pixels re-loaded counting only the 16x16 tiles the resumed pass draws into, as a tiler that skips untouched tiles would. */
        var reloadTilePixels = 0L
        var screenReloadTilePixels = 0L
        /** The same, counting the bounding box of every tile the resumed pass touches. */
        var reloadBoxPixels = 0L
        /** Pixels of plain boxes (with and without a border) lying in the box's flat inside: clear of its edge band. */
        var insidePixels = 0L
        var insideBorderPixels = 0L
        var screenReloadBoxPixels = 0L
        /** Draw calls there would be if a batch could hold this many textures at once (2 and 4), all else equal. */
        var draws2 = 0
        var draws4 = 0
        /** For each picture put down: its size, the screen area it was put down over, and that area before the scissor. */
        val composited = HashMap<String, Long>()
        val compositedWhole = HashMap<String, Long>()
        var livePictureBytes = 0L
        /** Offscreen pictures drawn into this frame (distinct framebuffers), and their summed size in pixels. */
        var picturesDrawn = 0
        var pictureArea = 0L
        /** Pixels by shape program and kind, "program/kind". */
        val pixelsByProgramKind = HashMap<String, Long>()
        /** Distinct offscreen pictures drawn into, as width x height, and how many times. */
        val offscreenTargets = HashMap<String, Int>()
    }

    var frame = Frame()
        private set
    val frames = ArrayList<Frame>()

    /** Ends the frame being counted and starts the next. */
    private var lastScreen = 0L

    fun endFrame(screenPixels: Long, phase: String = "") {
        lastScreen = screenPixels
        frame.screen = screenPixels
        frame.phase = phase
        frame.livePictureBytes = livePictureBytes
        for (target in drawnThisFrame) {
            val size = framebufferTextures[target]?.let { textureSizes[it] } ?: continue
            frame.picturesDrawn++
            frame.pictureArea += size[0].toLong() * size[1]
        }
        finishPass()
        drawnThisFrame.clear()
        pendingReload = -1
        run2.clear()
        run4.clear()
        lastDrawFramebuffer = -2
        uniformChangesAtLastDraw = 0
        frames += frame
        frame = Frame()
    }

    private fun count(name: String) {
        frame.calls++
        frame.byName[name] = (frame.byName[name] ?: 0) + 1
    }

    // --- what is bound, as far as this probe has seen ---

    private var program = 0
    private val textures = HashMap<Int, Int>() // unit -> texture
    private var activeUnit = GlConst.TEXTURE0
    private var framebuffer = -1
    private val caps = HashMap<Int, Boolean>()
    private val blend = IntArray(4) { -1 }
    private val blendEq = IntArray(2) { -1 }
    private val scissorBox = IntArray(4) { -1 }
    private val viewportBox = IntArray(4)
    private var arrayBuffer = 0
    private val uploads = HashMap<Int, ProbeFloats>() // buffer -> what was last uploaded
    private val uploadCounts = HashMap<Int, Int>()
    private var stride0 = 0

    /** Attribute 0's stride as each vertex array was last set up: with vertex arrays a device lays one out once and then only binds it. */
    private var vertexArray = 0
    private val arrayStride0 = HashMap<Int, Int>()
    private val uniformNames = HashMap<Int, HashMap<Int, String>>() // program -> location -> name
    private val uniformValues = HashMap<Long, FloatArray>()
    private val projections = HashMap<Int, FloatArray>()
    private val maskModes = HashMap<Int, Float>()
    private val framebufferTextures = HashMap<Int, Int>() // framebuffer -> colour texture
    private val pictureTextures = HashSet<Int>() // textures that are an offscreen picture's colour
    private val textureSizes = HashMap<Int, IntArray>()
    private val drawnThisFrame = HashSet<Int>()

    /** Every shader text compiled, in order: what the Mali compiler is run on. */
    val shaderTexts = LinkedHashSet<String>()
    private val shaderTags = HashMap<Int, String>()
    private val programTags = HashMap<Int, String>()

    /** Who asked for a picture, by size, the first time each size was drawn into. Set [whoAsked] to fill it. */
    val attributions = LinkedHashMap<String, String>()
    var whoAsked: (() -> String)? = null
    var livePictureBytes = 0L
    private var pendingReload = -1
    private var lastDrawFramebuffer = -2
    private var lastDrawProgram = -1
    private var lastDrawTexture = -1
    private val lastDrawBlend = IntArray(4)
    private val lastDrawScissor = IntArray(5)
    private var uniformChangesAtLastDraw = 0
    private val passTiles = HashSet<Int>()
    private var handedBack = false
    private var passResumed = false
    private var passScreen = false
    private val run2 = HashSet<Int>()
    private val run4 = HashSet<Int>()

    private fun finishPass() {
        if (passResumed && passTiles.isNotEmpty()) {
            val px = passTiles.size * 256L
            frame.reloadTilePixels += px
            if (passScreen) frame.screenReloadTilePixels += px
            val xs = passTiles.map { it % 4096 }
            val ys = passTiles.map { it / 4096 }
            val box = (xs.max() - xs.min() + 1).toLong() * (ys.max() - ys.min() + 1) * 256L
            frame.reloadBoxPixels += box
            if (passScreen) frame.screenReloadBoxPixels += box
        }
        passTiles.clear()
        passResumed = false
    }

    private fun uniformKey(at: Int): Long = (program.toLong() shl 32) or (at.toLong() and 0xffffffffL)

    private fun uniform(at: Int, vararg values: Float) {
        frame.uniformCalls++
        val key = uniformKey(at)
        val previous = uniformValues[key]
        if (previous == null || !previous.contentEquals(values)) {
            frame.uniformChanges++
            uniformValues[key] = values.copyOf()
        }
        val name = uniformNames[program]?.get(at)
        if (name == "u_projTrans") projections[program] = values.copyOf()
        if (name == "u_maskMode") maskModes[program] = values[0]
    }

    // --- Gl ---

    override val profile: GlProfile get() = gl.profile
    override fun hasExtension(name: String) = gl.hasExtension(name)
    override fun floats(capacity: Int): GlFloats = ProbeFloats(gl.floats(capacity))
    override fun shorts(capacity: Int): GlShorts = gl.shorts(capacity)
    override fun bytes(capacity: Int): GlBytes = gl.bytes(capacity)

    private fun cap(cap: Int, on: Boolean) {
        frame.capCalls++
        if (caps[cap] != on) frame.capChanges++
        caps[cap] = on
    }

    override fun enable(cap: Int) { count("enable"); cap(cap, true); gl.enable(cap) }
    override fun disable(cap: Int) { count("disable"); cap(cap, false); gl.disable(cap) }
    override fun isEnabled(cap: Int): Boolean { count("isEnabled"); frame.queries++; return gl.isEnabled(cap) }

    override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) {
        count("blendFuncSeparate")
        frame.blendCalls++
        if (blend[0] != srcRgb || blend[1] != dstRgb || blend[2] != srcAlpha || blend[3] != dstAlpha) frame.blendChanges++
        blend[0] = srcRgb; blend[1] = dstRgb; blend[2] = srcAlpha; blend[3] = dstAlpha
        gl.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
    }

    override fun blendEquationSeparate(rgb: Int, alpha: Int) {
        count("blendEquationSeparate")
        frame.blendCalls++
        if (blendEq[0] != rgb || blendEq[1] != alpha) frame.blendChanges++
        blendEq[0] = rgb; blendEq[1] = alpha
        gl.blendEquationSeparate(rgb, alpha)
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) { count("colorMask"); gl.colorMask(red, green, blue, alpha) }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        count("viewport")
        frame.viewportCalls++
        if (viewportBox[0] != x || viewportBox[1] != y || viewportBox[2] != width || viewportBox[3] != height) frame.viewportChanges++
        viewportBox[0] = x; viewportBox[1] = y; viewportBox[2] = width; viewportBox[3] = height
        gl.viewport(x, y, width, height)
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        count("scissor")
        frame.scissorCalls++
        if (scissorBox[0] != x || scissorBox[1] != y || scissorBox[2] != width || scissorBox[3] != height) frame.scissorChanges++
        scissorBox[0] = x; scissorBox[1] = y; scissorBox[2] = width; scissorBox[3] = height
        gl.scissor(x, y, width, height)
    }

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) { count("clearColor"); gl.clearColor(red, green, blue, alpha) }
    override fun clearDepth(depth: Float) { count("clearDepth"); gl.clearDepth(depth) }
    override fun depthMask(write: Boolean) { count("depthMask"); gl.depthMask(write) }

    override fun clear(mask: Int) {
        count("clear")
        frame.clears++
        frame.clearedPixels += (viewportBox[2].toLong() * viewportBox[3].toLong())
        if (pendingReload == framebuffer) pendingReload = -1
        gl.clear(mask)
    }

    override fun getInteger(name: Int): Int { count("getInteger"); frame.queries++; return gl.getInteger(name) }
    override fun getIntegers(name: Int, into: IntArray) { count("getIntegers"); frame.queries++; gl.getIntegers(name, into) }
    override fun pixelStorei(name: Int, value: Int) { count("pixelStorei"); gl.pixelStorei(name, value) }

    override fun createShader(type: Int): Int { count("createShader"); return gl.createShader(type) }
    override fun shaderSource(shader: Int, source: String) {
        count("shaderSource")
        shaderTexts += source
        shaderTags[shader] = when {
            Regex("(?m)^#define CG_FULL").containsMatchIn(source) -> "full"
            Regex("(?m)^#define CG_HELD").containsMatchIn(source) -> "held"
            else -> "common"
        }
        gl.shaderSource(shader, source)
    }
    override fun compileShader(shader: Int) { count("compileShader"); gl.compileShader(shader) }
    override fun shaderCompiled(shader: Int): Boolean { count("shaderCompiled"); return gl.shaderCompiled(shader) }
    override fun shaderInfoLog(shader: Int): String = gl.shaderInfoLog(shader)
    override fun deleteShader(shader: Int) { count("deleteShader"); gl.deleteShader(shader) }
    override fun createProgram(): Int { count("createProgram"); return gl.createProgram() }
    override fun attachShader(program: Int, shader: Int) {
        count("attachShader")
        val tag = shaderTags[shader]
        if (tag != null && tag != "common") programTags[program] = tag
        else if (program !in programTags) programTags[program] = "common"
        gl.attachShader(program, shader)
    }
    override fun bindAttribLocation(program: Int, index: Int, name: String) { count("bindAttribLocation"); gl.bindAttribLocation(program, index, name) }
    override fun linkProgram(program: Int) { count("linkProgram"); gl.linkProgram(program) }
    override fun programLinked(program: Int): Boolean { count("programLinked"); return gl.programLinked(program) }
    override fun programInfoLog(program: Int): String = gl.programInfoLog(program)
    override fun deleteProgram(program: Int) { count("deleteProgram"); gl.deleteProgram(program) }

    override fun useProgram(program: Int) {
        count("useProgram")
        frame.programBinds++
        if (this.program != program) frame.programSwitches++
        if (program == 0) handedBack = true
        this.program = program
        gl.useProgram(program)
    }

    override fun getUniformLocation(program: Int, name: String): Int {
        count("getUniformLocation")
        val at = gl.getUniformLocation(program, name)
        uniformNames.getOrPut(program) { HashMap() }[at] = name
        return at
    }

    override fun uniform1i(at: Int, value: Int) { count("uniform1i"); uniform(at, value.toFloat()); gl.uniform1i(at, value) }
    override fun uniform1f(at: Int, value: Float) { count("uniform1f"); uniform(at, value); gl.uniform1f(at, value) }
    override fun uniform2f(at: Int, x: Float, y: Float) { count("uniform2f"); uniform(at, x, y); gl.uniform2f(at, x, y) }
    override fun uniform3f(at: Int, x: Float, y: Float, z: Float) { count("uniform3f"); uniform(at, x, y, z); gl.uniform3f(at, x, y, z) }
    override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) { count("uniform4f"); uniform(at, x, y, z, w); gl.uniform4f(at, x, y, z, w) }
    override fun uniformMatrix4fv(at: Int, matrix: FloatArray) { count("uniformMatrix4fv"); uniform(at, *matrix); gl.uniformMatrix4fv(at, matrix) }

    override fun createBuffer(): Int { count("createBuffer"); return gl.createBuffer() }

    override fun bindBuffer(target: Int, buffer: Int) {
        count("bindBuffer")
        if (target == GlConst.ARRAY_BUFFER) arrayBuffer = buffer
        gl.bindBuffer(target, buffer)
    }

    override fun deleteBuffer(buffer: Int) { count("deleteBuffer"); gl.deleteBuffer(buffer) }

    override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) {
        count("bufferData")
        frame.bufferUploads++
        frame.vertexBytes += count * 4L
        if (target == GlConst.ARRAY_BUFFER && data is ProbeFloats) {
            uploads[arrayBuffer] = data
            uploadCounts[arrayBuffer] = count
        }
        gl.bufferData(target, if (data is ProbeFloats) data.inner else data, count, usage)
    }

    override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) {
        count("bufferData(indices)")
        frame.bufferUploads++
        frame.vertexBytes += count * 2L
        gl.bufferData(target, data, count, usage)
    }

    override fun enableVertexAttribArray(index: Int) { count("enableVertexAttribArray"); gl.enableVertexAttribArray(index) }
    override fun disableVertexAttribArray(index: Int) { count("disableVertexAttribArray"); gl.disableVertexAttribArray(index) }

    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {
        count("vertexAttribPointer")
        if (index == 0) {
            stride0 = stride
            if (vertexArray != 0) arrayStride0[vertexArray] = stride
        }
        gl.vertexAttribPointer(index, size, type, normalized, stride, offset)
    }

    override fun createVertexArray(): Int { count("createVertexArray"); return gl.createVertexArray() }
    override fun bindVertexArray(array: Int) {
        count("bindVertexArray")
        vertexArray = array
        arrayStride0[array]?.let { stride0 = it }
        gl.bindVertexArray(array)
    }
    override fun deleteVertexArray(array: Int) { count("deleteVertexArray"); gl.deleteVertexArray(array) }

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        count("drawElements")
        frame.draws++
        val offscreen = framebuffer > 0 && framebufferTextures.containsKey(framebuffer)
        if (offscreen) {
            frame.drawsOffscreen++
            val size = framebufferTextures[framebuffer]?.let { textureSizes[it] }
            if (size != null) {
                val key = "${size[0]}x${size[1]}"
                frame.offscreenTargets[key] = (frame.offscreenTargets[key] ?: 0) + 1
            }
        }
        if (pendingReload == framebuffer) {
            frame.reloads++
            val picture = framebufferTextures[framebuffer]?.let { textureSizes[it] }
            if (picture != null) {
                frame.reloadPixels += picture[0].toLong() * picture[1]
            } else {
                frame.screenReloads++
                frame.reloadPixels += frame.screen.takeIf { it > 0 } ?: lastScreen
            }
            passResumed = true
            passScreen = picture == null
            pendingReload = -1
        }
        val texture = textures[GlConst.TEXTURE0] ?: 0
        val scissorOn = if (caps[GlConst.SCISSOR_TEST] == true) 1 else 0
        val changed = ArrayList<String>()
        if (handedBack) changed += "hand-back"
        handedBack = false
        if (lastDrawFramebuffer != framebuffer) changed += "target"
        if (lastDrawProgram != program) changed += "program"
        if (lastDrawTexture != texture) changed += "texture"
        if (!lastDrawBlend.contentEquals(blend)) changed += "blend"
        if (lastDrawScissor[0] != scissorOn || (scissorOn == 1 && (lastDrawScissor[1] != scissorBox[0] || lastDrawScissor[2] != scissorBox[1] || lastDrawScissor[3] != scissorBox[2] || lastDrawScissor[4] != scissorBox[3]))) changed += "scissor"
        if (frame.uniformChanges != uniformChangesAtLastDraw) changed += "uniform"
        val cause = if (changed.isEmpty()) "nothing" else changed.joinToString("+")
        frame.breaks[cause] = (frame.breaks[cause] ?: 0) + 1
        // The same draws, with batches that could hold 2 or 4 textures: only a change other than
        // the texture, or a texture past the limit, starts a new draw call.
        val other = changed.any { it != "texture" }  // a hand-back counts as a change too
        for ((set, limit) in listOf(run2 to 2, run4 to 4)) {
            val fresh = set.isEmpty() || other || (texture !in set && set.size >= limit)
            if (fresh) {
                set.clear()
                if (limit == 2) frame.draws2++ else frame.draws4++
            }
            set += texture
        }
        lastDrawFramebuffer = framebuffer
        lastDrawProgram = program
        lastDrawTexture = texture
        blend.copyInto(lastDrawBlend)
        lastDrawScissor[0] = scissorOn
        scissorBox.copyInto(lastDrawScissor, 1)
        uniformChangesAtLastDraw = frame.uniformChanges
        drawnThisFrame += framebuffer
        val quads = count / 6
        frame.quads += quads
        measure(quads, offscreen)
        gl.drawElements(mode, count, type, offset)
    }

    override fun createTexture(): Int { count("createTexture"); return gl.createTexture() }

    override fun bindTexture(target: Int, texture: Int) {
        count("bindTexture")
        frame.textureBinds++
        if (textures[activeUnit] != texture) frame.textureSwitches++
        textures[activeUnit] = texture
        gl.bindTexture(target, texture)
    }

    override fun deleteTexture(texture: Int) {
        count("deleteTexture")
        frame.texturesDeleted++
        if (pictureTextures.remove(texture)) textureSizes[texture]?.let { livePictureBytes -= it[0].toLong() * it[1] * 4 }
        textureSizes.remove(texture)
        gl.deleteTexture(texture)
    }

    override fun activeTexture(unit: Int) { count("activeTexture"); activeUnit = unit; gl.activeTexture(unit) }
    override fun texParameteri(target: Int, name: Int, value: Int) { count("texParameteri"); gl.texParameteri(target, name, value) }

    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?) {
        count("texImage2D")
        frame.textureAllocations++
        frame.textureAllocationBytes += width.toLong() * height * 4
        textures[activeUnit]?.let { textureSizes[it] = intArrayOf(width, height) }
        gl.texImage2D(target, level, internalFormat, width, height, format, type, pixels)
    }

    override fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes) {
        count("texSubImage2D")
        frame.textureUploads++
        frame.textureUploadBytes += width.toLong() * height * 4
        gl.texSubImage2D(target, level, x, y, width, height, format, type, pixels)
    }

    override fun createFramebuffer(): Int { count("createFramebuffer"); frame.framebuffersMade++; return gl.createFramebuffer() }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        count("bindFramebuffer")
        frame.framebufferBinds++
        val picture = framebufferTextures[framebuffer]?.let { textureSizes[it] }
        val asker = whoAsked
        if (picture != null && asker != null) {
            val key = "${picture[0]}x${picture[1]}"
            if (key !in attributions && attributions.size < 60) attributions[key] = asker()
        }
        if (this.framebuffer != framebuffer) {
            finishPass()
            frame.framebufferSwitches++
            pendingReload = if (framebuffer in drawnThisFrame) framebuffer else -1
        }
        this.framebuffer = framebuffer
        gl.bindFramebuffer(target, framebuffer)
    }

    override fun deleteFramebuffer(framebuffer: Int) {
        count("deleteFramebuffer")
        frame.framebuffersDeleted++
        framebufferTextures.remove(framebuffer)
        gl.deleteFramebuffer(framebuffer)
    }

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) {
        count("framebufferTexture2D")
        if (framebuffer > 0) framebufferTextures[framebuffer] = texture
        if (pictureTextures.add(texture)) textureSizes[texture]?.let { livePictureBytes += it[0].toLong() * it[1] * 4 }
        gl.framebufferTexture2D(target, attachment, textureTarget, texture, level)
    }

    override fun checkFramebufferStatus(target: Int): Int { count("checkFramebufferStatus"); frame.queries++; return gl.checkFramebufferStatus(target) }
    override fun createRenderbuffer(): Int { count("createRenderbuffer"); return gl.createRenderbuffer() }
    override fun bindRenderbuffer(target: Int, renderbuffer: Int) { count("bindRenderbuffer"); gl.bindRenderbuffer(target, renderbuffer) }
    override fun deleteRenderbuffer(renderbuffer: Int) { count("deleteRenderbuffer"); gl.deleteRenderbuffer(renderbuffer) }
    override fun renderbufferStorage(target: Int, format: Int, width: Int, height: Int) { count("renderbufferStorage"); gl.renderbufferStorage(target, format, width, height) }
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) {
        count("framebufferRenderbuffer"); gl.framebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer)
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes) {
        count("readPixels"); frame.readPixels++; gl.readPixels(x, y, width, height, format, type, into)
    }

    // --- pixels covered ---

    private val corner = FloatArray(8)
    private val polygon = FloatArray(32)
    private val scratch = FloatArray(32)

    /** Where a shape vertex keeps what the probe reads: 31 floats before #253, 22 slots after. */
    private class Layout(val perVertex: Int, val local: Int, val halfSize: Int, val shape: Int, val radii: Int, val kind: Int)

    private val oldLayout = Layout(31, local = 17, halfSize = 19, shape = 21, radii = 24, kind = 28)
    private val packedLayout = Layout(22, local = 8, halfSize = 10, shape = 12, radii = 15, kind = 19)

    private fun measure(quads: Int, offscreen: Boolean) {
        val upload = uploads[arrayBuffer] ?: return
        val floats = upload.shadow
        val layout = when (stride0) {
            31 * 4 -> oldLayout
            22 * 4 -> packedLayout
            else -> null
        }
        val shape = layout != null
        val effect = stride0 == 4 * 4
        if (!shape && !effect) return
        if (effect) frame.effectDraws++
        val perVertex = layout?.perVertex ?: 4
        val tag = if (effect) "effect" else programTags[program] ?: "?"
        val projection = if (shape) projections[program] ?: return else null
        val masked = shape && (maskModes[program] ?: 0f) > 0.5f

        // The rectangle a fragment can land in: the viewport, and the scissor if it is on.
        var left = viewportBox[0].toFloat()
        var bottom = viewportBox[1].toFloat()
        var right = left + viewportBox[2]
        var top = bottom + viewportBox[3]
        if (caps[GlConst.SCISSOR_TEST] == true) {
            left = maxOf(left, scissorBox[0].toFloat())
            bottom = maxOf(bottom, scissorBox[1].toFloat())
            right = minOf(right, (scissorBox[0] + scissorBox[2]).toFloat())
            top = minOf(top, (scissorBox[1] + scissorBox[3]).toFloat())
        }
        if (right <= left || top <= bottom) return
        val texture = textures[GlConst.TEXTURE0] ?: 0

        for (quad in 0 until quads) {
            val base = quad * 4 * perVertex
            if (base + 4 * perVertex > floats.size) break
            for (v in 0 until 4) {
                val at = base + v * perVertex
                val x = floats[at]
                val y = floats[at + 1]
                var nx: Float
                var ny: Float
                if (projection != null) {
                    val w = floats[at + 2]
                    val cx = projection[0] * x + projection[4] * y + projection[12] * w
                    val cy = projection[1] * x + projection[5] * y + projection[13] * w
                    val cw = projection[3] * x + projection[7] * y + projection[15] * w
                    nx = cx / cw
                    ny = cy / cw
                } else {
                    nx = x
                    ny = y
                }
                corner[v * 2] = (nx + 1f) / 2f * viewportBox[2] + viewportBox[0]
                corner[v * 2 + 1] = (ny + 1f) / 2f * viewportBox[3] + viewportBox[1]
            }
            val area = clippedArea(left, bottom, right, top).toLong()
            if (area <= 0) {
                val whole = clippedArea(-1e9f, -1e9f, 1e9f, 1e9f)
                val onTarget = clippedArea(viewportBox[0].toFloat(), viewportBox[1].toFloat(), (viewportBox[0] + viewportBox[2]).toFloat(), (viewportBox[1] + viewportBox[3]).toFloat())
                when {
                    whole < 0.5f -> frame.quadsOfNoSize++
                    onTarget < 0.5f -> frame.quadsOutsideTarget++
                    else -> frame.quadsOutsideScissor++
                }
                continue
            }
            val kind = if (effect) "effect" else kindOf(floats, base, texture, layout!!)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (v in 0 until 4) {
                minX = minOf(minX, corner[v * 2]); maxX = maxOf(maxX, corner[v * 2])
                minY = minOf(minY, corner[v * 2 + 1]); maxY = maxOf(maxY, corner[v * 2 + 1])
            }
            val tx0 = (maxOf(minX, left) / 16f).toInt()
            val tx1 = ((minOf(maxX, right) - 0.001f) / 16f).toInt()
            val ty0 = (maxOf(minY, bottom) / 16f).toInt()
            val ty1 = ((minOf(maxY, top) - 0.001f) / 16f).toInt()
            for (ty in ty0..ty1) for (tx in tx0..tx1) passTiles += ty * 4096 + tx
            if (kind == "layer picture") {
                val size = textureSizes[texture]
                val key = if (size != null) "${size[0]}x${size[1]}" else "?"
                frame.composited[key] = (frame.composited[key] ?: 0L) + area
                val whole = clippedArea(-1e9f, -1e9f, 1e9f, 1e9f).toLong()
                frame.compositedWhole[key] = (frame.compositedWhole[key] ?: 0L) + whole
            }
            frame.pixelsByKind[kind] = (frame.pixelsByKind[kind] ?: 0L) + area
            val programKind = if (masked) "$tag/$kind+mask" else "$tag/$kind"
            frame.pixelsByProgramKind[programKind] = (frame.pixelsByProgramKind[programKind] ?: 0L) + area
            if (layout != null && (kind == "shape" || kind == "shape with border")) {
                val at = base
                val hw = floats[at + layout.halfSize]
                val hh = floats[at + layout.halfSize + 1]
                val border = floats[at + layout.shape]
                val aa = floats[at + layout.shape + 2]
                val r = layout.radii
                val radius = maxOf(floats[at + r], floats[at + r + 1], floats[at + r + 2], floats[at + r + 3])
                val inset = radius + aa + maxOf(border, 0f)
                val quadW = abs(floats[base + 2 * perVertex + layout.local] - floats[base + layout.local])
                val quadH = abs(floats[base + 1 * perVertex + layout.local + 1] - floats[base + layout.local + 1])
                if (quadW > 0f && quadH > 0f) {
                    val inside = maxOf(0f, 2f * (hw - inset)) * maxOf(0f, 2f * (hh - inset)) / (quadW * quadH)
                    val px = (area * inside.coerceIn(0f, 1f)).toLong()
                    if (kind == "shape") frame.insidePixels += px else frame.insideBorderPixels += px
                }
            }
            frame.quadsByKind[kind] = (frame.quadsByKind[kind] ?: 0) + 1
            if (offscreen) frame.offscreenPixels += area else frame.hostPixels += area
            if (masked) frame.maskedPixels += area
        }
    }

    private fun kindOf(f: FloatArray, at: Int, texture: Int, layout: Layout): String {
        val border = f[at + layout.shape]
        val spread = f[at + layout.shape + 1]
        val aa = f[at + layout.shape + 2]
        val kind = f[at + layout.kind]
        if (aa <= 0f) {
            return when {
                texture in pictureTextures -> "layer picture"
                kind > 0.5f -> "premultiplied picture"
                else -> "picture or glyph"
            }
        }
        return when {
            // First: an inside shade carries its offset in the gradient's slots, on both layouts.
            spread < 0f -> "inner shade"
            kind > 4.5f -> "relief"
            kind > 2.5f -> "ramp gradient"
            kind > 0.5f -> "gradient"
            spread > 0f -> "shadow"
            border != 0f -> "shape with border"
            else -> "shape"
        }
    }

    /** The area of the quad in [corner], clipped to the rectangle, by Sutherland-Hodgman then the shoelace. */
    private fun clippedArea(left: Float, bottom: Float, right: Float, top: Float): Float {
        var count = 4
        corner.copyInto(polygon, 0, 0, 8)
        count = clip(count, 0, left, true)
        count = clip(count, 0, right, false)
        count = clip(count, 1, bottom, true)
        count = clip(count, 1, top, false)
        if (count < 3) return 0f
        var sum = 0f
        for (i in 0 until count) {
            val j = (i + 1) % count
            sum += polygon[i * 2] * polygon[j * 2 + 1] - polygon[j * 2] * polygon[i * 2 + 1]
        }
        return abs(sum) / 2f
    }

    private fun clip(count: Int, axis: Int, edge: Float, keepAbove: Boolean): Int {
        if (count == 0) return 0
        var out = 0
        for (i in 0 until count) {
            val ax = polygon[i * 2]
            val ay = polygon[i * 2 + 1]
            val j = (i + 1) % count
            val bx = polygon[j * 2]
            val by = polygon[j * 2 + 1]
            val a = if (axis == 0) ax else ay
            val b = if (axis == 0) bx else by
            val aIn = if (keepAbove) a >= edge else a <= edge
            val bIn = if (keepAbove) b >= edge else b <= edge
            if (aIn) {
                scratch[out * 2] = ax; scratch[out * 2 + 1] = ay; out++
            }
            if (aIn != bIn && out < 15) {
                val t = (edge - a) / (b - a)
                scratch[out * 2] = ax + (bx - ax) * t
                scratch[out * 2 + 1] = ay + (by - ay) * t
                out++
            }
        }
        scratch.copyInto(polygon, 0, 0, out * 2)
        return out
    }

    /** Float storage that keeps a copy the probe can read back. */
    class ProbeFloats(val inner: GlFloats) : GlFloats {
        val shadow = FloatArray(inner.capacity)
        override val capacity: Int get() = inner.capacity
        override fun set(index: Int, value: Float) {
            shadow[index] = value
            inner[index] = value
        }

        override fun put(at: Int, from: FloatArray, offset: Int, count: Int) {
            from.copyInto(shadow, at, offset, offset + count)
            inner.put(at, from, offset, count)
        }
    }
}

/** PROFILING ONLY (#242): a frame list summed up as medians, one line per number. */
fun ProbeGl.report(label: String, skip: Int = 0): String = probeReport(label, frames.drop(skip))

/** PROFILING ONLY (#242): [list] summed up as medians. */
fun probeReport(label: String, list: List<ProbeGl.Frame>): String {
    if (list.isEmpty()) return "$label: no frames\n"
    fun med(pick: (ProbeGl.Frame) -> Number): String {
        val values = list.map { pick(it).toDouble() }.sorted()
        val median = values[values.size / 2]
        val max = values.last()
        return if (median == max) fmt(median) else "${fmt(median)} (max ${fmt(max)})"
    }
    return buildString {
        appendLine("== $label: ${list.size} frames, medians")
        appendLine("GL calls per draw call ${med { if (it.draws > 0) it.calls.toDouble() / it.draws else 0.0 }}")
        appendLine("GL calls ${med { it.calls }}")
        appendLine("draw calls ${med { it.draws }} (into pictures ${med { it.drawsOffscreen }}, effects ${med { it.effectDraws }}), quads ${med { it.quads }}")
        appendLine("vertex upload bytes ${med { it.vertexBytes }} in ${med { it.bufferUploads }} bufferData")
        appendLine("texture uploads ${med { it.textureUploads }} (${med { it.textureUploadBytes }} bytes), texture storage made ${med { it.textureAllocations }} (${med { it.textureAllocationBytes }} bytes)")
        appendLine("pictures drawn into ${med { it.picturesDrawn }}, their area ${med { it.pictureArea }} px")
        appendLine("offscreen picture memory alive at frame end ${med { it.livePictureBytes }} bytes")
        appendLine("framebuffers made ${med { it.framebuffersMade }} deleted ${med { it.framebuffersDeleted }}, textures deleted ${med { it.texturesDeleted }}")
        appendLine("framebuffer binds ${med { it.framebufferBinds }} (switches ${med { it.framebufferSwitches }}), clears ${med { it.clears }} (${med { it.clearedPixels }} px)")
        appendLine("program binds ${med { it.programBinds }} (switches ${med { it.programSwitches }}), texture binds ${med { it.textureBinds }} (switches ${med { it.textureSwitches }})")
        appendLine("blend calls ${med { it.blendCalls }} (changes ${med { it.blendChanges }}), enable/disable ${med { it.capCalls }} (changes ${med { it.capChanges }})")
        appendLine("scissor ${med { it.scissorCalls }} (changes ${med { it.scissorChanges }}), viewport ${med { it.viewportCalls }} (changes ${med { it.viewportChanges }})")
        appendLine("uniform calls ${med { it.uniformCalls }} (changes ${med { it.uniformChanges }})")
        val causes = list.flatMap { it.breaks.keys }.toSet().sortedByDescending { c -> list.map { it.breaks[c] ?: 0 }.sorted()[list.size / 2] }
        appendLine("draw calls by why they broke from the last: " + causes.joinToString { c -> "$c ${med { it.breaks[c] ?: 0 }}" })
        appendLine("draw calls if a batch held 2 textures ${med { it.draws2 }}, 4 textures ${med { it.draws4 }}")
        appendLine("re-loaded pixels counting only tiles the resumed passes touch: ${med { it.reloadTilePixels }} (screen ${med { it.screenReloadTilePixels }})")
        appendLine("plain-box pixels in the flat inside: ${med { it.insidePixels }}, bordered-box pixels in the flat inside: ${med { it.insideBorderPixels }}")
        appendLine("re-loaded pixels counting the bounding box of those tiles: ${med { it.reloadBoxPixels }} (screen ${med { it.screenReloadBoxPixels }})")
        val comps = list.flatMap { it.composited.keys }.toSet()
        if (comps.isNotEmpty()) appendLine("pictures put down (picture size: px landed, px before the scissor): " + comps.joinToString { k -> "$k: ${med { it.composited[k] ?: 0L }}, ${med { it.compositedWhole[k] ?: 0L }}" })
        appendLine("render pass reloads ${med { it.reloads }} (of the screen ${med { it.screenReloads }}), ${med { it.reloadPixels }} px stored and read back again")
        appendLine("quads covering nothing: off the target ${med { it.quadsOutsideTarget }}, outside the scissor ${med { it.quadsOutsideScissor }}, no size ${med { it.quadsOfNoSize }}")
        appendLine("queries that wait for the driver ${med { it.queries }}, readPixels ${med { it.readPixels }}")
        val screen = list.first().screen
        appendLine("pixels covered: screen ${med { it.hostPixels }}, pictures ${med { it.offscreenPixels }}, masked ${med { it.maskedPixels }}; screen is $screen px, overdraw ${med { if (it.screen > 0) (it.hostPixels + it.offscreenPixels).toDouble() / it.screen else 0.0 }}x")
        val kinds = list.flatMap { it.pixelsByKind.keys }.toSet().sortedByDescending { k -> list.map { it.pixelsByKind[k] ?: 0L }.sorted()[list.size / 2] }
        kinds.forEach { k -> appendLine("  $k: ${med { it.pixelsByKind[k] ?: 0L }} px in ${med { it.quadsByKind[k] ?: 0 }} quads") }
        val programKinds = list.flatMap { it.pixelsByProgramKind.keys }.toSet().sorted()
        programKinds.forEach { k -> appendLine("  by program $k: ${med { it.pixelsByProgramKind[k] ?: 0L }} px") }
        val targets = list.flatMap { it.offscreenTargets.keys }.toSet()
        if (targets.isNotEmpty()) appendLine("pictures drawn into (size: draws, frames seen): " + targets.joinToString { t -> "$t: ${list.count { t in it.offscreenTargets }}" })
        val names = list.flatMap { it.byName.keys }.toSet().sortedByDescending { n -> list.map { it.byName[n] ?: 0 }.sorted()[list.size / 2] }
        appendLine("calls by name: " + names.take(30).joinToString { n -> "$n ${med { it.byName[n] ?: 0 }}" })
    }
}

private fun fmt(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString() else ((value * 100).toLong() / 100.0).toString()
