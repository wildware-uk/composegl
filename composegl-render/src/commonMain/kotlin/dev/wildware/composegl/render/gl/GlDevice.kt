package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.Blend
import dev.wildware.composegl.render.ClipMask
import dev.wildware.composegl.render.DeviceLimits
import dev.wildware.composegl.render.DeviceResource
import dev.wildware.composegl.render.DeviceTarget
import dev.wildware.composegl.render.DeviceTexture
import dev.wildware.composegl.render.EffectQuad
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.GpuDevice
import dev.wildware.composegl.render.ShapeProgram
import dev.wildware.composegl.render.ShapeVertex
import dev.wildware.composegl.render.VertexStream
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform

/**
 * The renderer's device for every OpenGL there is: GL 2.1, GL 3.2+ core, OpenGL ES 2 and 3, WebGL 1
 * and 2. It speaks only to [gl], which a backend implements.
 *
 * What it works out once, from the context: whether vertex array objects exist (and must be used —
 * a core context draws nothing without one), whether framebuffers exist, which GLSL dialect to
 * compile, and which internal format an offscreen picture takes. With [HostState.Leave], which
 * framebuffer is the engine's own is asked once too, on the first frame drawn into it.
 *
 * Nothing touches the driver until something is drawn, built or asked for, so a canvas holding one
 * can be made with no context anywhere.
 *
 * @param handOver how the context goes back to the engine — see [HostState].
 */
class GlDevice(private val gl: Gl, private val handOver: HostState = HostState.Leave) : GpuDevice {

    private class Caps(
        val profile: GlProfile,
        val vertexArrays: Boolean,
        val dialect: GlslDialect,
        val offscreenFormat: Int,
        val depthFormat: Int,
        val limits: DeviceLimits,
    )

    private var caps: Caps? = null

    private fun caps(): Caps = caps ?: read().also { caps = it }

    private fun read(): Caps {
        val profile = gl.profile
        val vertexArrays = when (profile.api) {
            GlApi.Desktop -> profile.core || profile.atLeast(3, 0) || gl.hasExtension("GL_ARB_vertex_array_object")
            GlApi.Es -> profile.major >= 3 || gl.hasExtension("GL_OES_vertex_array_object")
            GlApi.WebGl -> profile.major >= 2 || gl.hasExtension("OES_vertex_array_object")
        }
        val offscreen = when (profile.api) {
            GlApi.Desktop -> profile.atLeast(3, 0) ||
                gl.hasExtension("GL_ARB_framebuffer_object") || gl.hasExtension("GL_EXT_framebuffer_object")
            else -> true
        }
        // RGBA8 is not a format ES 2 or WebGL 1 accept for a texture's storage.
        val sized = profile.api == GlApi.Desktop ||
            (profile.api == GlApi.Es && profile.major >= 3) || (profile.api == GlApi.WebGl && profile.major >= 2)
        return Caps(
            profile = profile,
            vertexArrays = vertexArrays,
            dialect = GlslDialect.of(profile),
            offscreenFormat = if (sized) GlConst.RGBA8 else GlConst.RGBA,
            // Twenty-four bits where the context has them; ES 2 and WebGL 1 promise only sixteen.
            depthFormat = if (sized) GlConst.DEPTH_COMPONENT24 else GlConst.DEPTH_COMPONENT16,
            limits = DeviceLimits(maxTextureSize = gl.getInteger(GlConst.MAX_TEXTURE_SIZE), offscreen = offscreen),
        )
    }

    /** The dialect this device compiles, once it has asked the context. */
    val dialect: GlslDialect get() = caps().dialect

    /** Whether this device uses vertex array objects on its context. */
    val usesVertexArrays: Boolean get() = caps().vertexArrays

    override val limits: DeviceLimits get() = caps().limits

    // --- what gets built ---

    private var built = false

    /**
     * One linked shape program, where its uniforms are, and what they hold as last sent: a uniform
     * is the program's own, so it outlives a game's drawing, and each program keeps its own. Made
     * fresh with the program, so a new context starts it knowing nothing.
     */
    private class ShapeShader(val name: Int, gl: Gl) {
        val projectionAt = gl.getUniformLocation(name, "u_projTrans")
        val textureAt = gl.getUniformLocation(name, "u_texture")
        val maskBoxAt = gl.getUniformLocation(name, "u_maskBox")
        val maskRadiiAt = gl.getUniformLocation(name, "u_maskRadii")
        val maskScaleAt = gl.getUniformLocation(name, "u_maskScale")
        val maskModeAt = gl.getUniformLocation(name, "u_maskMode")

        var sampling = false
        val sentProjection = FloatArray(16)
        var projectionSent = false

        /** The mask's box, corners and scale as last sent, NaN for never: NaN equals nothing, so it always goes. */
        val sentMask = FloatArray(10) { Float.NaN }
        var sentMaskMode = Float.NaN
    }

    /** [ShapeProgram.Common], [ShapeProgram.Held] and [ShapeProgram.Full]: see [GlslSources.ShapeFragment]. */
    private var commonShape: ShapeShader? = null
    private var heldShape: ShapeShader? = null
    private var fullShape: ShapeShader? = null
    private var indexBuffer = 0
    private var indexQuads = 0
    private var shapeBuffer = 0
    private var shapeArray = 0
    private var effectBuffer = 0
    private var effectArray = 0
    private var effectFloats: GlFloats? = null

    /** One effect quad's corners, filled here and handed to [effectFloats] in one copy. */
    private val effectCorners = FloatArray(4 * ShapeVertex.EffectFloats)

    private class EffectProgram(val name: Int) {
        val uniforms = HashMap<String, Int>()

        /** Whether its sampler has been pointed at unit 0, which it keeps for good. */
        var sampling = false
    }

    // --- what the context holds ---

    /*
     * What this device last set on the context, so a draw sends the driver only what changed: on
     * a phone every call is a trip into the driver. Unknown until set, and all of it forgotten
     * whenever the device takes the context, at [begin] and after a game's drawing, since the game
     * may have set anything. The texture is not remembered: an engine binds a game's texture its
     * own way, sometimes in the middle of a frame, so every draw binds its own.
     */
    private var usedProgram = Unknown
    private var usedArray = Unknown
    private var usedArrayBuffer = Unknown

    /** Without vertex arrays only: with them the element buffer belongs to the array, set in each once. */
    private var usedElementBuffer = Unknown
    private var blendSource = Unknown
    private var blendDestination = Unknown
    private var blendOn = false
    private var scissorTest = Unknown
    private var scissorSent = false
    private val sentScissorBox = IntArray(4)

    /** Without vertex arrays: the attributes this device switched on, one bit each. */
    private var attributesOn = 0

    /** Without vertex arrays: what each attribute points into — [ShapeLayout], [EffectLayout] or nothing yet. */
    private val pointers = IntArray(ShapeVertex.Attributes.size)

    /*
     * What belongs to the device's own objects rather than to the context, and so outlives a
     * hand-back: nobody else binds its vertex arrays or draws with its programs. Forgotten with the
     * context.
     */
    private var shapeArrayLaidOut = false
    private var effectArrayLaidOut = false

    /** Keyed by the text, not the object: a shader built fresh every recomposition still hits. */
    private val effectPrograms = HashMap<String, EffectProgram>()

    private var scratch: GlBytes? = null

    override val prepared: Boolean get() = built

    override fun prepare() = build()

    private fun build() {
        if (built) return
        val caps = caps()
        forgetObjects()
        // All three now rather than each when it is first needed: a shader compiled in the middle
        // of a frame is a stutter on a phone.
        val linked = ArrayList<ShapeShader>(3)
        try {
            linked += linkShape(caps.dialect, prefix = "", what = "the interface")
            linked += linkShape(caps.dialect, prefix = GlslSources.HeldShape, what = "the interface's held program")
            linked += linkShape(caps.dialect, prefix = GlslSources.FullShape, what = "the interface's full program")
        } catch (failed: IllegalStateException) {
            linked.forEach { gl.deleteProgram(it.name) }
            throw failed
        }
        commonShape = linked[0]
        heldShape = linked[1]
        fullShape = linked[2]

        shapeBuffer = gl.createBuffer()
        effectBuffer = gl.createBuffer()
        indexBuffer = gl.createBuffer()
        if (caps.vertexArrays) {
            shapeArray = gl.createVertexArray()
            effectArray = gl.createVertexArray()
        }
        uploadIndices(maxOf(indexQuads, 1))
        if (effectFloats == null) effectFloats = gl.floats(4 * ShapeVertex.EffectFloats)
        built = true
    }

    private fun linkShape(dialect: GlslDialect, prefix: String, what: String): ShapeShader {
        val program = link(
            vertex = dialect.vertex(prefix + GlslSources.ShapeVertex),
            fragment = dialect.fragment(prefix + GlslSources.ShapeFragment, highPrecision = true),
            attributes = ShapeVertex.Attributes.map { it.name },
            what = what,
        ) { message -> throw IllegalStateException(message) }
        return ShapeShader(program, gl)
    }

    /**
     * Two triangles per quad, 0-1-2 then 2-3-0, for [quads] quads.
     *
     * Uploaded through the element slot, because WebGL refuses a buffer that has ever been bound to
     * the array slot as an index buffer. The element slot belongs to whichever vertex array object is
     * bound, so where there are vertex arrays the device binds its own first rather than writing into
     * the engine's — and leaves the indices bound in it, where every shape draw wants them.
     */
    private fun uploadIndices(quads: Int) {
        val values = ShortArray(quads * 6)
        for (quad in 0 until quads) {
            val vertex = quad * 4
            val at = quad * 6
            values[at] = vertex.toShort()
            values[at + 1] = (vertex + 1).toShort()
            values[at + 2] = (vertex + 2).toShort()
            values[at + 3] = (vertex + 2).toShort()
            values[at + 4] = (vertex + 3).toShort()
            values[at + 5] = vertex.toShort()
        }
        val indices = gl.shorts(values.size)
        indices.put(0, values, 0, values.size)
        val vertexArrays = caps().vertexArrays
        if (vertexArrays) gl.bindVertexArray(shapeArray)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, indices, quads * 6, GlConst.STATIC_DRAW)
        if (vertexArrays) {
            gl.bindVertexArray(0)
            usedArray = 0
        } else {
            gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
            usedElementBuffer = 0
        }
        indexQuads = quads
    }

    @Suppress("LongParameterList")
    private inline fun link(
        vertex: String,
        fragment: String,
        attributes: List<String>,
        what: String,
        fail: (String) -> Nothing,
    ): Int {
        val vertexShader = compile(GlConst.VERTEX_SHADER, vertex) { fail("$what's vertex shader would not compile:\n$it") }
        val fragmentShader = compile(GlConst.FRAGMENT_SHADER, fragment) {
            gl.deleteShader(vertexShader)
            fail("$what's fragment shader would not compile:\n$it")
        }
        val program = gl.createProgram()
        gl.attachShader(program, vertexShader)
        gl.attachShader(program, fragmentShader)
        // Bound rather than looked up, so the vertex layout is the one the shader sees on every driver.
        attributes.forEachIndexed { index, name -> gl.bindAttribLocation(program, index, name) }
        gl.linkProgram(program)
        val linked = gl.programLinked(program)
        val log = if (linked) "" else gl.programInfoLog(program)
        // The program holds them now.
        gl.deleteShader(vertexShader)
        gl.deleteShader(fragmentShader)
        if (!linked) {
            gl.deleteProgram(program)
            fail("$what shader would not link:\n$log")
        }
        return program
    }

    private inline fun compile(type: Int, source: String, fail: (String) -> Nothing): Int {
        val shader = gl.createShader(type)
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        if (gl.shaderCompiled(shader)) return shader
        val log = gl.shaderInfoLog(shader)
        gl.deleteShader(shader)
        fail(log)
    }

    // --- frames ---

    private var frameTarget: FrameTarget = FrameTarget.Host

    /** What [FrameTarget.Host] binds in this frame. */
    private var hostFramebuffer = 0
    private val hostViewport = IntArray(4)
    private val snapshot = GlSnapshot()

    /**
     * The engine's own framebuffer, as the first frame on it found it, or [Unknown] until then.
     * Asked once and remembered rather than asked every frame: a query makes the CPU wait for the
     * driver to catch up, and on a threaded driver that wait was most of the render thread's time
     * in native code. Forgotten with the context.
     */
    private var engineFramebuffer = Unknown

    /**
     * The framebuffer this device knows is bound right now — one it asked about or bound itself —
     * so making a picture mid-frame need not ask. [Unknown] until then, between frames, and while
     * a game draws, when anything may have been bound.
     */
    private var bound = Unknown

    private var target: FrameTarget = FrameTarget.Host
    private val viewport = IntArray(4)
    private var scissorOn = false
    private val scissorBox = IntArray(4)

    override fun begin(into: FrameTarget) {
        val caps = caps()
        frameTarget = into
        if (handOver == HostState.Restore) {
            snapshot.capture(gl, caps.vertexArrays)
            hostFramebuffer = snapshot.framebuffer
            bound = hostFramebuffer
        } else if (into === FrameTarget.Host) {
            // Asked once. After that, what is bound is not known until the frame binds its target:
            // the engine should have its own bound, but nothing has asked.
            bound = if (engineFramebuffer == Unknown) {
                gl.getInteger(GlConst.FRAMEBUFFER_BINDING).also { engineFramebuffer = it }
            } else {
                Unknown
            }
            hostFramebuffer = engineFramebuffer
        } else {
            // A frame into a picture can begin anywhere in a game's own scene, inside a framebuffer
            // of the game's that this device has never seen, so what to put back is asked for.
            hostFramebuffer = gl.getInteger(GlConst.FRAMEBUFFER_BINDING)
            gl.getIntegers(GlConst.VIEWPORT, hostViewport)
            bound = hostFramebuffer
        }
        target = into
        scissorOn = false
        take()
    }

    override fun end() {
        bound = Unknown
        release()
        if (handOver == HostState.Restore) {
            snapshot.restore(gl, caps().vertexArrays)
            return
        }
        if (frameTarget !== FrameTarget.Host) {
            gl.bindFramebuffer(GlConst.FRAMEBUFFER, hostFramebuffer)
            gl.viewport(hostViewport[0], hostViewport[1], hostViewport[2], hostViewport[3])
        }
        gl.disable(GlConst.SCISSOR_TEST)
        leave()
    }

    override fun hostTargetChanged() {
        engineFramebuffer = Unknown
    }

    override fun suspend() {
        bound = Unknown
        release()
        if (handOver == HostState.Restore) snapshot.restore(gl, caps().vertexArrays) else leave()
    }

    override fun resume() {
        if (handOver == HostState.Restore) snapshot.capture(gl, caps().vertexArrays)
        retake()
    }

    /**
     * An engine that remembers the GL state it set — KorGE, three.js — has to find what it believes
     * while it draws, and keep what it set afterwards: otherwise it skips setting something it thinks
     * is already set, and draws wrongly later in the same frame. So the engine's own state goes back
     * round the block, as round a frame's `raw`, with the picture left bound.
     */
    override fun suspendInScene() {
        bound = Unknown
        release()
        if (handOver == HostState.Restore) snapshot.restore(gl, caps().vertexArrays, target = false) else leave()
    }

    override fun resumeInScene() {
        if (handOver == HostState.Restore) snapshot.capture(gl, caps().vertexArrays, target = false)
        retake()
    }

    /** Ours again after a game's drawing: what we rely on, the target, viewport and scissor. */
    private fun retake() {
        take()
        applyTarget()
        sendScissor()
    }

    /** Everything this renderer relies on, set rather than assumed, and nothing else assumed either. */
    private fun take() {
        forget()
        gl.disable(GlConst.DEPTH_TEST)
        gl.disable(GlConst.CULL_FACE)
        gl.disable(GlConst.STENCIL_TEST)
        gl.colorMask(true, true, true, true)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.blendEquationSeparate(GlConst.FUNC_ADD, GlConst.FUNC_ADD)
        gl.enable(GlConst.BLEND)
        blendOn = true
    }

    /**
     * The context is about to go back to the engine. Without vertex arrays, the attributes the
     * device switched on are switched off again, as the engine has always found them; with them,
     * the device's attribute state is its own array's and the engine never sees it.
     */
    private fun release() {
        var on = attributesOn
        var index = 0
        while (on != 0) {
            if (on and 1 != 0) gl.disableVertexAttribArray(index)
            on = on ushr 1
            index++
        }
        forget()
    }

    /** What the context holds is not known any more: the engine may have set anything. */
    private fun forget() {
        usedProgram = Unknown
        usedArray = Unknown
        usedArrayBuffer = Unknown
        usedElementBuffer = Unknown
        blendSource = Unknown
        blendDestination = Unknown
        blendOn = false
        scissorTest = Unknown
        scissorSent = false
        attributesOn = 0
        pointers.fill(Nowhere)
    }

    /** The device's own objects are new, or gone with the context: nothing set in them is known. */
    private fun forgetObjects() {
        shapeArrayLaidOut = false
        effectArrayLaidOut = false
    }

    /** [HostState.Leave]'s documented state, short of the framebuffer, viewport and scissor. */
    private fun leave() {
        // Said again rather than assumed from take(): this is also what a game's drawing inside a
        // frame or a scene is handed, and it ends with a state of its own.
        gl.disable(GlConst.DEPTH_TEST)
        gl.disable(GlConst.CULL_FACE)
        gl.disable(GlConst.STENCIL_TEST)
        gl.enable(GlConst.BLEND)
        gl.blendFuncSeparate(GlConst.SRC_ALPHA, GlConst.ONE_MINUS_SRC_ALPHA, GlConst.SRC_ALPHA, GlConst.ONE_MINUS_SRC_ALPHA)
        gl.useProgram(0)
        if (caps().vertexArrays) gl.bindVertexArray(0)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.bindTexture(GlConst.TEXTURE_2D, 0)
    }

    override fun target(target: FrameTarget, x: Int, y: Int, width: Int, height: Int) {
        this.target = target
        viewport[0] = x
        viewport[1] = y
        viewport[2] = width
        viewport[3] = height
        applyTarget()
    }

    private fun applyTarget() {
        val framebuffer = when (val into = target) {
            FrameTarget.Host -> hostFramebuffer
            is GlDeviceTarget -> into.framebuffer
            else -> error("this device can only draw into OpenGL targets, not ${into::class}")
        }
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, framebuffer)
        bound = framebuffer
        gl.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        scissorOn = true
        scissorBox[0] = x
        scissorBox[1] = y
        scissorBox[2] = width
        scissorBox[3] = height
        sendScissor()
    }

    override fun noScissor() {
        scissorOn = false
        sendScissor()
    }

    /** The scissor the device wants, sending only what the context does not already hold. */
    private fun sendScissor() {
        if (!scissorOn) {
            if (scissorTest != Off) gl.disable(GlConst.SCISSOR_TEST)
            scissorTest = Off
            return
        }
        if (scissorTest != On) gl.enable(GlConst.SCISSOR_TEST)
        scissorTest = On
        if (scissorSent && scissorBox.contentEquals(sentScissorBox)) return
        gl.scissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        scissorBox.copyInto(sentScissorBox)
        scissorSent = true
    }

    /**
     * A target with a depth buffer has it cleared with the colour, to the far plane.
     *
     * Depth writing is switched on first: a game that was drawing transparent things had it off,
     * and a depth clear while it is off writes nothing at all — a scene that then comes out
     * inside-out with no error anywhere to say why. It is left on, which is OpenGL's own default;
     * [HostState.Restore] puts back whatever the engine had.
     */
    override fun clear(red: Float, green: Float, blue: Float, alpha: Float) {
        gl.clearColor(red, green, blue, alpha)
        if ((target as? DeviceTarget)?.depth != true) return gl.clear(GlConst.COLOR_BUFFER_BIT)
        gl.depthMask(true)
        gl.clearDepth(1f)
        gl.clear(GlConst.COLOR_BUFFER_BIT or GlConst.DEPTH_BUFFER_BIT)
    }

    // --- drawing ---

    private class Stream(val floats: GlFloats, override val quads: Int) : VertexStream {
        override fun set(index: Int, value: Float) {
            floats[index] = value
        }

        override fun put(from: FloatArray, count: Int) = floats.put(0, from, 0, count)
    }

    override fun vertices(quads: Int): VertexStream {
        if (quads > indexQuads) {
            indexQuads = quads
            if (built) uploadIndices(quads)
        }
        return Stream(gl.floats(quads * 4 * ShapeVertex.Floats), quads)
    }

    /** Not told which quads are in it, so through the program that draws them all. */
    override fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray) =
        drawShapes(vertices, quads, texture, blend, projection, mask = null, ShapeProgram.Full)

    /** Every OpenGL the shape shader compiles on has `gl_FragCoord`, which is all a mask needs. */
    override val masks: Boolean get() = true

    /** The same: through the program that draws them all. */
    @Suppress("LongParameterList")
    override fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
    ) = drawShapes(vertices, quads, texture, blend, projection, mask, ShapeProgram.Full)

    @Suppress("LongParameterList")
    override fun drawShapes(
        vertices: VertexStream,
        quads: Int,
        texture: DeviceTexture,
        blend: Blend,
        projection: FloatArray,
        mask: ClipMask?,
        program: ShapeProgram,
    ) {
        build()
        val stream = vertices as? Stream ?: error("these vertices were not made by this device")
        val shader = checkNotNull(
            when (program) {
                ShapeProgram.Common -> commonShape
                ShapeProgram.Held -> heldShape
                ShapeProgram.Full -> fullShape
            },
        )

        bindPicture(texture)
        useProgram(shader.name)
        shapeUniforms(shader, projection, mask, blend)

        // With a vertex array object the layout and the indices are the array's own, set once; without
        // one they are the context's, and set again only where the engine or an effect moved them.
        if (caps().vertexArrays) {
            bindVertexArray(shapeArray)
            bindArrayBuffer(shapeBuffer)
            gl.bufferData(GlConst.ARRAY_BUFFER, stream.floats, quads * 4 * ShapeVertex.Floats, GlConst.STREAM_DRAW)
            if (!shapeArrayLaidOut) {
                ShapeVertex.Attributes.forEachIndexed { index, _ ->
                    gl.enableVertexAttribArray(index)
                    pointShape(index)
                }
                shapeArrayLaidOut = true
            }
        } else {
            bindArrayBuffer(shapeBuffer)
            gl.bufferData(GlConst.ARRAY_BUFFER, stream.floats, quads * 4 * ShapeVertex.Floats, GlConst.STREAM_DRAW)
            for (index in ShapeVertex.Attributes.indices) {
                switchOn(index)
                if (pointers[index] != ShapeLayout) pointShape(index)
                pointers[index] = ShapeLayout
            }
            bindElementBuffer(indexBuffer)
        }
        applyBlend(blend)
        gl.drawElements(GlConst.TRIANGLES, quads * 6, GlConst.UNSIGNED_SHORT, 0)
    }

    /**
     * [shader]'s uniforms, each sent only when it differs from what that program holds: a uniform
     * is the program's own, so it outlives a game's drawing and is lost only with the context.
     */
    private fun shapeUniforms(shader: ShapeShader, projection: FloatArray, mask: ClipMask?, blend: Blend) {
        if (!shader.sampling) {
            gl.uniform1i(shader.textureAt, 0)
            shader.sampling = true
        }
        if (!shader.projectionSent || !projection.contentEquals(shader.sentProjection)) {
            gl.uniformMatrix4fv(shader.projectionAt, projection)
            projection.copyInto(shader.sentProjection)
            shader.projectionSent = true
        }
        if (mask != null) {
            val sent = shader.sentMask
            if (sent[0] != mask.centreX || sent[1] != mask.centreY || sent[2] != mask.halfWidth || sent[3] != mask.halfHeight) {
                gl.uniform4f(shader.maskBoxAt, mask.centreX, mask.centreY, mask.halfWidth, mask.halfHeight)
                sent[0] = mask.centreX
                sent[1] = mask.centreY
                sent[2] = mask.halfWidth
                sent[3] = mask.halfHeight
            }
            if (sent[4] != mask.topLeft || sent[5] != mask.topRight || sent[6] != mask.bottomRight || sent[7] != mask.bottomLeft) {
                gl.uniform4f(shader.maskRadiiAt, mask.topLeft, mask.topRight, mask.bottomRight, mask.bottomLeft)
                sent[4] = mask.topLeft
                sent[5] = mask.topRight
                sent[6] = mask.bottomRight
                sent[7] = mask.bottomLeft
            }
            if (sent[8] != mask.pixelsAcross || sent[9] != mask.pixelsUp) {
                gl.uniform2f(shader.maskScaleAt, mask.pixelsAcross, mask.pixelsUp)
                sent[8] = mask.pixelsAcross
                sent[9] = mask.pixelsUp
            }
        }
        // Which half of the colour to trim depends on whether it arrives premultiplied.
        val mode = when {
            mask == null -> 0f
            blend.premultiplied -> 2f
            else -> 1f
        }
        if (mode != shader.sentMaskMode) {
            gl.uniform1f(shader.maskModeAt, mode)
            shader.sentMaskMode = mode
        }
    }

    private fun pointShape(index: Int) {
        val attribute = ShapeVertex.Attributes[index]
        gl.vertexAttribPointer(
            index,
            attribute.size,
            GlConst.FLOAT,
            false,
            ShapeVertex.Floats * FloatBytes,
            attribute.offset * FloatBytes,
        )
    }

    /** The effect quad's corner then its texture coordinate, into attributes 0 and 1. */
    private fun pointEffect(index: Int) {
        gl.vertexAttribPointer(index, 2, GlConst.FLOAT, false, ShapeVertex.EffectFloats * FloatBytes, index * 2 * FloatBytes)
    }

    /** Without vertex arrays: [index] switched on unless the device already did. */
    private fun switchOn(index: Int) {
        val bit = 1 shl index
        if (attributesOn and bit != 0) return
        gl.enableVertexAttribArray(index)
        attributesOn = attributesOn or bit
    }

    private fun useProgram(program: Int) {
        if (usedProgram == program) return
        gl.useProgram(program)
        usedProgram = program
    }

    private fun bindVertexArray(array: Int) {
        if (usedArray == array) return
        gl.bindVertexArray(array)
        usedArray = array
    }

    private fun bindArrayBuffer(buffer: Int) {
        if (usedArrayBuffer == buffer) return
        gl.bindBuffer(GlConst.ARRAY_BUFFER, buffer)
        usedArrayBuffer = buffer
    }

    private fun bindElementBuffer(buffer: Int) {
        if (usedElementBuffer == buffer) return
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, buffer)
        usedElementBuffer = buffer
    }

    override fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend) {
        build()
        val program = effectPrograms.getOrPut(effect.source.fragment) { compileEffect(effect.source) }
        useProgram(program.name)

        if (!program.sampling) {
            gl.uniform1i(uniform(program, "u_texture"), 0)
            program.sampling = true
        }
        gl.uniform2f(uniform(program, "u_textureSize"), quad.textureWidth, quad.textureHeight)
        gl.uniform2f(uniform(program, "u_size"), quad.width, quad.height)
        gl.uniform1f(uniform(program, "u_alpha"), quad.alpha)
        effect.uniforms.forEach { (name, value) -> set(uniform(program, name), value) }
        place(program, picture, quad)

        // The shader sees its picture from 0 to 1, the top at 1 as a framebuffer counts, wherever
        // the picture lies in the texture: cg_picture maps each read there.
        val floats = checkNotNull(effectFloats)
        corner(0, quad.left, quad.top, 0f, 1f)
        corner(1, quad.right, quad.top, 1f, 1f)
        corner(2, quad.right, quad.bottom, 1f, 0f)
        corner(3, quad.left, quad.bottom, 0f, 0f)
        floats.put(0, effectCorners, 0, effectCorners.size)

        gl.activeTexture(GlConst.TEXTURE0)
        bindPicture(picture)

        // Premultiplied, like every other way a layer reaches the screen, combined the way the
        // canvas's blend stack says.
        sendBlend(GlConst.ONE, if (blend.additive) GlConst.ONE else GlConst.ONE_MINUS_SRC_ALPHA)

        if (caps().vertexArrays) {
            bindVertexArray(effectArray)
            bindArrayBuffer(effectBuffer)
            gl.bufferData(GlConst.ARRAY_BUFFER, floats, 4 * ShapeVertex.EffectFloats, GlConst.STREAM_DRAW)
            if (!effectArrayLaidOut) {
                for (index in 0..1) {
                    gl.enableVertexAttribArray(index)
                    pointEffect(index)
                }
                gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
                effectArrayLaidOut = true
            }
        } else {
            bindArrayBuffer(effectBuffer)
            gl.bufferData(GlConst.ARRAY_BUFFER, floats, 4 * ShapeVertex.EffectFloats, GlConst.STREAM_DRAW)
            // The shapes' other attributes are left on and pointing into the shapes' buffer, which
            // holds at least a quad: the effect's program reads none of them.
            for (index in 0..1) {
                switchOn(index)
                if (pointers[index] != EffectLayout) pointEffect(index)
                pointers[index] = EffectLayout
            }
            bindElementBuffer(indexBuffer)
        }
        gl.drawElements(GlConst.TRIANGLES, 6, GlConst.UNSIGNED_SHORT, 0)
    }

    private fun uniform(program: EffectProgram, name: String): Int =
        program.uniforms.getOrPut(name) { gl.getUniformLocation(program.name, name) }

    /**
     * Where in [picture] the effect's picture lies, for `cg_picture`: the texture coordinate of its
     * bottom-left and how far it reaches, then the box a read is held inside, half a texel in from
     * each edge so a read past the edge gets the edge, as an exact-size texture's clamp gives.
     */
    private fun place(program: EffectProgram, picture: DeviceTexture, quad: EffectQuad) {
        gl.uniform4f(uniform(program, "cg_picturePlace"), quad.u, quad.v2, quad.u2 - quad.u, quad.v - quad.v2)
        val halfAcross = 0.5f / picture.width
        val halfUp = 0.5f / picture.height
        val left = minOf(quad.u, quad.u2) + halfAcross
        val right = maxOf(quad.u, quad.u2) - halfAcross
        val bottom = minOf(quad.v, quad.v2) + halfUp
        val top = maxOf(quad.v, quad.v2) - halfUp
        // Less than a texel across holds every read to its middle: GLSL's clamp is undefined the wrong way round.
        val acrossLow = if (left <= right) left else (left + right) / 2f
        val acrossHigh = if (left <= right) right else acrossLow
        val upLow = if (bottom <= top) bottom else (bottom + top) / 2f
        val upHigh = if (bottom <= top) top else upLow
        gl.uniform4f(uniform(program, "cg_pictureEdges"), acrossLow, upLow, acrossHigh, upHigh)
    }

    private fun corner(corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * ShapeVertex.EffectFloats
        effectCorners[at] = x
        effectCorners[at + 1] = y
        effectCorners[at + 2] = u
        effectCorners[at + 3] = v
    }

    private fun set(location: Int, value: Uniform) {
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

    /**
     * Thrown rather than swallowed, with the effect's name: a shader that does not compile is a
     * mistake in the source, and a widget that quietly draws nothing is the hardest bug to find.
     */
    private fun compileEffect(source: ShaderSource): EffectProgram {
        val dialect = caps().dialect
        val vertex = compile(GlConst.VERTEX_SHADER, dialect.vertex(GlslSources.EffectVertex)) {
            throw IllegalArgumentException("the effect shader \"${source.name}\" would not compile (vertex):\n$it")
        }
        // High precision where the device has it, as the shape shader does. A phone's medium
        // precision is 16 bits, and an effect's arithmetic runs out of it without saying so: the
        // dissolve's noise hash drew nothing at all on OpenGL ES 3 until this was highp.
        val text = dialect.fragment(GlslSources.effectFragment(source.fragment), highPrecision = true)
        val fragment = compile(GlConst.FRAGMENT_SHADER, text) {
            gl.deleteShader(vertex)
            throw IllegalArgumentException("the effect shader \"${source.name}\" would not compile (fragment):\n$it")
        }
        val program = gl.createProgram()
        gl.attachShader(program, vertex)
        gl.attachShader(program, fragment)
        gl.bindAttribLocation(program, 0, "a_position")
        gl.bindAttribLocation(program, 1, "a_texCoord0")
        gl.linkProgram(program)
        val linked = gl.programLinked(program)
        val log = if (linked) "" else gl.programInfoLog(program)
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
        if (!linked) {
            gl.deleteProgram(program)
            throw IllegalArgumentException("the effect shader \"${source.name}\" would not link:\n$log")
        }
        return EffectProgram(program)
    }

    private fun applyBlend(blend: Blend) {
        // The alpha half accumulates rather than interpolates, so what lands in an offscreen picture
        // is premultiplied and a game can put it on a quad without a shader of its own.
        sendBlend(
            source = if (blend.premultiplied) GlConst.ONE else GlConst.SRC_ALPHA,
            destination = if (blend.additive) GlConst.ONE else GlConst.ONE_MINUS_SRC_ALPHA,
        )
    }

    /** Blending on, colour from [source] onto [destination] and alpha from one onto it, unless it already is. */
    private fun sendBlend(source: Int, destination: Int) {
        if (!blendOn) {
            gl.enable(GlConst.BLEND)
            blendOn = true
        }
        if (source == blendSource && destination == blendDestination) return
        gl.blendFuncSeparate(source, destination, GlConst.ONE, destination)
        blendSource = source
        blendDestination = destination
    }

    private fun bindPicture(texture: DeviceTexture) {
        val picture = texture as? GlDeviceTexture ?: error("this device can only draw OpenGL textures, not ${texture::class}")
        val hook = picture.bind
        if (hook != null) hook() else gl.bindTexture(GlConst.TEXTURE_2D, picture.name)
    }

    // --- textures and targets ---

    override fun texture(width: Int, height: Int, smooth: Boolean): DeviceTexture {
        require(width > 0 && height > 0) { "a texture cannot be ${width}x$height" }
        val name = gl.createTexture()
        gl.bindTexture(GlConst.TEXTURE_2D, name)
        gl.texImage2D(GlConst.TEXTURE_2D, 0, GlConst.RGBA, width, height, GlConst.RGBA, GlConst.UNSIGNED_BYTE, null)
        parameters(if (smooth) GlConst.LINEAR else GlConst.NEAREST)
        gl.bindTexture(GlConst.TEXTURE_2D, 0)
        return GlDeviceTexture(name, width, height, owned = true)
    }

    /**
     * Filtered as asked and clamped, because every edge of every picture here is an edge, and
     * repeating it is how a nine-patch's corner gets a stripe of the opposite corner.
     */
    private fun parameters(filter: Int) {
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, filter)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, filter)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
        gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
    }

    private fun scratch(bytes: Int): GlBytes {
        val current = scratch
        if (current != null && current.capacity >= bytes) return current
        return gl.bytes(maxOf(bytes, (current?.capacity ?: 0) * 2)).also { scratch = it }
    }

    override fun write(texture: DeviceTexture, x: Int, y: Int, width: Int, height: Int, source: ByteArray, sourceWidth: Int) {
        if (width <= 0 || height <= 0) return
        val picture = texture as? GlDeviceTexture ?: error("this device can only write OpenGL textures")
        val bytes = scratch(width * height * 4)
        for (row in 0 until height) {
            bytes.put(row * width * 4, source, ((y + row) * sourceWidth + x) * 4, width * 4)
        }
        gl.bindTexture(GlConst.TEXTURE_2D, picture.name)
        gl.texSubImage2D(GlConst.TEXTURE_2D, 0, x, y, width, height, GlConst.RGBA, GlConst.UNSIGNED_BYTE, bytes)
        gl.bindTexture(GlConst.TEXTURE_2D, 0)
    }

    /**
     * A framebuffer with a colour texture, and a depth renderbuffer beside it when [depth] is asked
     * for. The depth buffer is made here and given back in [delete], always the picture's own size,
     * so a resize cannot leave a scene testing against the depth of the size before.
     *
     * A renderbuffer rather than a depth texture: every OpenGL there is has one, and nothing here
     * reads depth back.
     */
    override fun offscreen(width: Int, height: Int, depth: Boolean): DeviceTarget {
        require(width > 0 && height > 0) { "a render target is at least one pixel each way" }
        val caps = caps()
        val colour = gl.createTexture()
        gl.bindTexture(GlConst.TEXTURE_2D, colour)
        gl.texImage2D(GlConst.TEXTURE_2D, 0, caps.offscreenFormat, width, height, GlConst.RGBA, GlConst.UNSIGNED_BYTE, null)
        parameters(GlConst.LINEAR)
        gl.bindTexture(GlConst.TEXTURE_2D, 0)

        val depthBuffer = if (depth) depthBuffer(caps, width, height) else 0

        val framebuffer = gl.createFramebuffer()
        val previous = if (bound != Unknown) bound else gl.getInteger(GlConst.FRAMEBUFFER_BINDING)
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, framebuffer)
        gl.framebufferTexture2D(GlConst.FRAMEBUFFER, GlConst.COLOR_ATTACHMENT0, GlConst.TEXTURE_2D, colour, 0)
        if (depthBuffer != 0) {
            gl.framebufferRenderbuffer(GlConst.FRAMEBUFFER, GlConst.DEPTH_ATTACHMENT, GlConst.RENDERBUFFER, depthBuffer)
        }
        val status = gl.checkFramebufferStatus(GlConst.FRAMEBUFFER)
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, previous)
        if (status != GlConst.FRAMEBUFFER_COMPLETE) {
            gl.deleteFramebuffer(framebuffer)
            gl.deleteTexture(colour)
            if (depthBuffer != 0) gl.deleteRenderbuffer(depthBuffer)
            val what = if (depth) "render target with depth" else "render target"
            error("this driver would not give us a ${width}x$height $what (status $status)")
        }
        return GlDeviceTarget(framebuffer, GlDeviceTexture(colour, width, height, owned = true), owned = true, depthBuffer = depthBuffer)
    }

    /** Depth storage of exactly the picture's size, leaving bound whatever renderbuffer was bound. */
    private fun depthBuffer(caps: Caps, width: Int, height: Int): Int {
        val name = gl.createRenderbuffer()
        val previous = gl.getInteger(GlConst.RENDERBUFFER_BINDING)
        gl.bindRenderbuffer(GlConst.RENDERBUFFER, name)
        gl.renderbufferStorage(GlConst.RENDERBUFFER, caps.depthFormat, width, height)
        gl.bindRenderbuffer(GlConst.RENDERBUFFER, previous)
        return name
    }

    override fun delete(resource: DeviceResource) {
        when (resource) {
            is GlDeviceTarget -> if (resource.owned) {
                gl.deleteFramebuffer(resource.framebuffer)
                gl.deleteTexture(resource.texture.name)
                if (resource.depthBuffer != 0) gl.deleteRenderbuffer(resource.depthBuffer)
            }
            is GlDeviceTexture -> if (resource.owned) gl.deleteTexture(resource.name)
        }
    }

    override fun read(x: Int, y: Int, width: Int, height: Int, into: ByteArray) {
        val count = width * height * 4
        val bytes = scratch(count)
        gl.readPixels(x, y, width, height, GlConst.RGBA, GlConst.UNSIGNED_BYTE, bytes)
        for (index in 0 until count) into[index] = bytes[index]
    }

    override fun contextLost() {
        // A new context's own framebuffer need not have the old one's name.
        engineFramebuffer = Unknown
        bound = Unknown
        forget()
        forgetObjects()
        built = false
        commonShape = null
        heldShape = null
        fullShape = null
        indexBuffer = 0
        shapeBuffer = 0
        shapeArray = 0
        effectBuffer = 0
        effectArray = 0
        effectPrograms.clear()
    }

    override fun close() {
        if (!built) return
        commonShape?.let { gl.deleteProgram(it.name) }
        heldShape?.let { gl.deleteProgram(it.name) }
        fullShape?.let { gl.deleteProgram(it.name) }
        effectPrograms.values.forEach { gl.deleteProgram(it.name) }
        effectPrograms.clear()
        gl.deleteBuffer(shapeBuffer)
        gl.deleteBuffer(effectBuffer)
        gl.deleteBuffer(indexBuffer)
        if (shapeArray != 0) gl.deleteVertexArray(shapeArray)
        if (effectArray != 0) gl.deleteVertexArray(effectArray)
        contextLost()
    }

    private companion object {
        const val FloatBytes = 4

        /** No framebuffer, program or buffer is ever called this; it stands for one not yet asked about or set. */
        const val Unknown = -1

        /** Whether the scissor test is on, as last sent. */
        const val Off = 0
        const val On = 1

        /** What an attribute points into, without vertex arrays. */
        const val Nowhere = 0
        const val ShapeLayout = 1
        const val EffectLayout = 2
    }
}

/**
 * An OpenGL texture as the device binds it: a name and a size.
 *
 * Equal to any other handle with the same name, so pictures cut from one sheet batch together.
 * An adopted texture belongs to the game: the device never deletes it and never changes its
 * filtering or wrapping.
 *
 * @param bind for an engine that must bind its textures itself (KorGE uploads lazily at bind):
 *   called instead of `bindTexture` whenever the device binds this one, in the middle of a frame.
 *   It binds this texture on the active unit and may upload it and set its parameters; it must
 *   change nothing else, since the device remembers the rest of the GL state it set.
 */
class GlDeviceTexture internal constructor(
    val name: Int,
    override val width: Int,
    override val height: Int,
    internal val owned: Boolean,
    internal val bind: (() -> Unit)? = null,
) : DeviceTexture {

    override fun equals(other: Any?): Boolean = other is GlDeviceTexture && other.name == name

    override fun hashCode(): Int = name

    override fun toString(): String = "GlDeviceTexture($name, ${width}x$height)"

    companion object {
        /** A game's own texture, which the device draws and never deletes. */
        fun adopt(name: Int, width: Int, height: Int, bind: (() -> Unit)? = null): GlDeviceTexture =
            GlDeviceTexture(name, width, height, owned = false, bind = bind)
    }
}

/**
 * An OpenGL framebuffer with a colour texture, as the device draws into it, and a depth
 * renderbuffer beside it where one was asked for.
 *
 * @param depthBuffer the depth renderbuffer's GL name, or 0 for a picture with no depth of ours —
 *   including an adopted framebuffer, whose depth is the game's own to make and give back.
 */
class GlDeviceTarget internal constructor(
    val framebuffer: Int,
    override val texture: GlDeviceTexture,
    internal val owned: Boolean,
    val depthBuffer: Int = 0,
    override val depth: Boolean = depthBuffer != 0,
) : DeviceTarget {
    override val width: Int get() = texture.width
    override val height: Int get() = texture.height

    companion object {
        /**
         * A game's own framebuffer, drawn into and never deleted.
         *
         * @param depth whether it already carries a depth attachment. Says so and the toolkit
         *   clears it with the colour; leave it false and the game's depth is left alone.
         */
        fun adopt(framebuffer: Int, texture: Int, width: Int, height: Int, depth: Boolean = false): GlDeviceTarget =
            GlDeviceTarget(framebuffer, GlDeviceTexture.adopt(texture, width, height), owned = false, depth = depth)
    }
}
