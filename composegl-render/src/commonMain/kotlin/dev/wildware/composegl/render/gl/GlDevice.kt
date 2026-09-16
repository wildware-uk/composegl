package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.Blend
import dev.wildware.composegl.render.DeviceLimits
import dev.wildware.composegl.render.DeviceResource
import dev.wildware.composegl.render.DeviceTarget
import dev.wildware.composegl.render.DeviceTexture
import dev.wildware.composegl.render.EffectQuad
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.GpuDevice
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
 * compile, and which internal format an offscreen picture takes.
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
    private var shapeProgram = 0
    private var projectionAt = -1
    private var shapeTextureAt = -1
    private var indexBuffer = 0
    private var indexQuads = 0
    private var shapeBuffer = 0
    private var shapeArray = 0
    private var effectBuffer = 0
    private var effectArray = 0
    private var effectFloats: GlFloats? = null

    private class EffectProgram(val name: Int) {
        val uniforms = HashMap<String, Int>()
    }

    /** Keyed by the text, not the object: a shader built fresh every recomposition still hits. */
    private val effectPrograms = HashMap<String, EffectProgram>()

    private var scratch: GlBytes? = null

    override val prepared: Boolean get() = built

    override fun prepare() = build()

    private fun build() {
        if (built) return
        val caps = caps()
        shapeProgram = link(
            vertex = caps.dialect.vertex(GlslSources.ShapeVertex),
            fragment = caps.dialect.fragment(GlslSources.ShapeFragment, highPrecision = true),
            attributes = ShapeVertex.Attributes.map { it.name },
            what = "the interface",
        ) { message -> throw IllegalStateException(message) }
        projectionAt = gl.getUniformLocation(shapeProgram, "u_projTrans")
        shapeTextureAt = gl.getUniformLocation(shapeProgram, "u_texture")

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

    /**
     * Two triangles per quad, 0-1-2 then 2-3-0, for [quads] quads.
     *
     * Uploaded through the element slot, because WebGL refuses a buffer that has ever been bound to
     * the array slot as an index buffer. The element slot belongs to whichever vertex array object is
     * bound, so where there are vertex arrays the device binds its own first rather than writing into
     * the engine's.
     */
    private fun uploadIndices(quads: Int) {
        val indices = gl.shorts(quads * 6)
        for (quad in 0 until quads) {
            val vertex = quad * 4
            val at = quad * 6
            indices[at] = vertex.toShort()
            indices[at + 1] = (vertex + 1).toShort()
            indices[at + 2] = (vertex + 2).toShort()
            indices[at + 3] = (vertex + 2).toShort()
            indices[at + 4] = (vertex + 3).toShort()
            indices[at + 5] = vertex.toShort()
        }
        val vertexArrays = caps().vertexArrays
        if (vertexArrays) gl.bindVertexArray(shapeArray)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.bufferData(GlConst.ELEMENT_ARRAY_BUFFER, indices, quads * 6, GlConst.STATIC_DRAW)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        if (vertexArrays) gl.bindVertexArray(0)
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
    private var hostFramebuffer = 0
    private val hostViewport = IntArray(4)
    private val snapshot = GlSnapshot()

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
        } else {
            hostFramebuffer = gl.getInteger(GlConst.FRAMEBUFFER_BINDING)
            if (into !== FrameTarget.Host) gl.getIntegers(GlConst.VIEWPORT, hostViewport)
        }
        target = into
        scissorOn = false
        take()
    }

    override fun end() {
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

    override fun suspend() {
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
        if (scissorOn) {
            gl.enable(GlConst.SCISSOR_TEST)
            gl.scissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        } else {
            gl.disable(GlConst.SCISSOR_TEST)
        }
    }

    /** Everything this renderer relies on, set rather than assumed. */
    private fun take() {
        gl.disable(GlConst.DEPTH_TEST)
        gl.disable(GlConst.CULL_FACE)
        gl.disable(GlConst.STENCIL_TEST)
        gl.colorMask(true, true, true, true)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.blendEquationSeparate(GlConst.FUNC_ADD, GlConst.FUNC_ADD)
        gl.enable(GlConst.BLEND)
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
        gl.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        scissorOn = true
        scissorBox[0] = x
        scissorBox[1] = y
        scissorBox[2] = width
        scissorBox[3] = height
        gl.enable(GlConst.SCISSOR_TEST)
        gl.scissor(x, y, width, height)
    }

    override fun noScissor() {
        scissorOn = false
        gl.disable(GlConst.SCISSOR_TEST)
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
    }

    override fun vertices(quads: Int): VertexStream {
        if (quads > indexQuads) {
            indexQuads = quads
            if (built) uploadIndices(quads)
        }
        return Stream(gl.floats(quads * 4 * ShapeVertex.Floats), quads)
    }

    override fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray) {
        build()
        val stream = vertices as? Stream ?: error("these vertices were not made by this device")
        val vertexArrays = caps().vertexArrays

        bindPicture(texture)
        gl.useProgram(shapeProgram)
        gl.uniformMatrix4fv(projectionAt, projection)
        gl.uniform1i(shapeTextureAt, 0)

        if (vertexArrays) gl.bindVertexArray(shapeArray)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, shapeBuffer)
        gl.bufferData(GlConst.ARRAY_BUFFER, stream.floats, quads * 4 * ShapeVertex.Floats, GlConst.STREAM_DRAW)
        val stride = ShapeVertex.Floats * FloatBytes
        ShapeVertex.Attributes.forEachIndexed { index, attribute ->
            gl.enableVertexAttribArray(index)
            gl.vertexAttribPointer(index, attribute.size, GlConst.FLOAT, false, stride, attribute.offset * FloatBytes)
        }
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        applyBlend(blend)
        gl.drawElements(GlConst.TRIANGLES, quads * 6, GlConst.UNSIGNED_SHORT, 0)

        // With a vertex array object its attribute state is its own; without one, the engine's
        // attribute state is the context's, so what was switched on is switched off again.
        if (!vertexArrays) ShapeVertex.Attributes.indices.forEach { gl.disableVertexAttribArray(it) }
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        if (vertexArrays) gl.bindVertexArray(0)
    }

    override fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, quad: EffectQuad, blend: Blend) {
        build()
        val vertexArrays = caps().vertexArrays
        val program = effectPrograms.getOrPut(effect.source.fragment) { compileEffect(effect.source) }
        gl.useProgram(program.name)

        gl.uniform1i(uniform(program, "u_texture"), 0)
        gl.uniform2f(uniform(program, "u_textureSize"), quad.textureWidth, quad.textureHeight)
        gl.uniform2f(uniform(program, "u_size"), quad.width, quad.height)
        gl.uniform1f(uniform(program, "u_alpha"), quad.alpha)
        effect.uniforms.forEach { (name, value) -> set(uniform(program, name), value) }

        val floats = checkNotNull(effectFloats)
        put(floats, 0, quad.left, quad.top, quad.u, quad.v)
        put(floats, 1, quad.right, quad.top, quad.u2, quad.v)
        put(floats, 2, quad.right, quad.bottom, quad.u2, quad.v2)
        put(floats, 3, quad.left, quad.bottom, quad.u, quad.v2)

        gl.activeTexture(GlConst.TEXTURE0)
        bindPicture(picture)

        // Premultiplied, like every other way a layer reaches the screen, combined the way the
        // canvas's blend stack says.
        val destination = if (blend.additive) GlConst.ONE else GlConst.ONE_MINUS_SRC_ALPHA
        gl.enable(GlConst.BLEND)
        gl.blendFuncSeparate(GlConst.ONE, destination, GlConst.ONE, destination)

        if (vertexArrays) gl.bindVertexArray(effectArray)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, effectBuffer)
        gl.bufferData(GlConst.ARRAY_BUFFER, floats, 4 * ShapeVertex.EffectFloats, GlConst.STREAM_DRAW)
        val stride = ShapeVertex.EffectFloats * FloatBytes
        gl.enableVertexAttribArray(0)
        gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(0, 2, GlConst.FLOAT, false, stride, 0)
        gl.vertexAttribPointer(1, 2, GlConst.FLOAT, false, stride, 2 * FloatBytes)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.drawElements(GlConst.TRIANGLES, 6, GlConst.UNSIGNED_SHORT, 0)
        if (!vertexArrays) {
            gl.disableVertexAttribArray(0)
            gl.disableVertexAttribArray(1)
        }
        gl.bindBuffer(GlConst.ARRAY_BUFFER, 0)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, 0)
        if (vertexArrays) gl.bindVertexArray(0)
        gl.useProgram(0)
    }

    private fun uniform(program: EffectProgram, name: String): Int =
        program.uniforms.getOrPut(name) { gl.getUniformLocation(program.name, name) }

    private fun put(floats: GlFloats, corner: Int, x: Float, y: Float, u: Float, v: Float) {
        val at = corner * ShapeVertex.EffectFloats
        floats[at] = x
        floats[at + 1] = y
        floats[at + 2] = u
        floats[at + 3] = v
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
        val fragment = compile(GlConst.FRAGMENT_SHADER, dialect.fragment(GlslSources.EffectPreamble + source.fragment, highPrecision = true)) {
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
        val destination = if (blend.additive) GlConst.ONE else GlConst.ONE_MINUS_SRC_ALPHA
        gl.enable(GlConst.BLEND)
        gl.blendFuncSeparate(
            if (blend.premultiplied) GlConst.ONE else GlConst.SRC_ALPHA,
            destination,
            GlConst.ONE,
            destination,
        )
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
        val previous = gl.getInteger(GlConst.FRAMEBUFFER_BINDING)
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
        built = false
        shapeProgram = 0
        indexBuffer = 0
        shapeBuffer = 0
        shapeArray = 0
        effectBuffer = 0
        effectArray = 0
        effectPrograms.clear()
    }

    override fun close() {
        if (!built) return
        gl.deleteProgram(shapeProgram)
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
 *   called instead of `bindTexture` whenever the device binds this one.
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
