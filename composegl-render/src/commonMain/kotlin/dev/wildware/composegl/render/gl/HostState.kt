package dev.wildware.composegl.render.gl

/**
 * How the OpenGL device hands the context back to the engine it shares it with, at the end of a
 * frame and around a game's own drawing inside one.
 */
enum class HostState {

    /**
     * Leaves a documented end state and asks the driver almost nothing: the frame's framebuffer,
     * scissor off, blend on with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, program 0, vertex array 0,
     * buffers 0, texture 0 on unit 0, and depth, cull and stencil tests off. A frame drawn into an
     * offscreen target also gets its framebuffer and viewport put back. For an engine that sets
     * what it needs before it draws: raw LWJGL, LibGDX, a browser page that owns its context.
     *
     * One thing it does not put back: clearing a target that has a depth buffer leaves depth
     * writing switched on, which is OpenGL's own default. An engine that turns it off for a pass of
     * its own turns it off again anyway; one that cannot wants [Restore].
     */
    Leave,

    /**
     * Asks the driver for about twenty values when a frame begins and puts every one back when it
     * ends. For an engine that caches GL state and believes its cache: KorGE, three.js.
     */
    Restore,
}

/**
 * The engine's state, as [HostState.Restore] saves it. One is kept per device and refilled per
 * frame, so saving costs queries and no allocation.
 */
internal class GlSnapshot {

    var framebuffer = 0
    val viewport = IntArray(4)
    val scissorBox = IntArray(4)
    var scissorTest = false
    var blend = false
    var depthTest = false
    var depthMask = true
    var cullFace = false
    var stencilTest = false
    var blendSrcRgb = GlConst.ONE
    var blendDstRgb = GlConst.ZERO
    var blendSrcAlpha = GlConst.ONE
    var blendDstAlpha = GlConst.ZERO
    var blendEquationRgb = GlConst.FUNC_ADD
    var blendEquationAlpha = GlConst.FUNC_ADD
    private val colourMask = IntArray(4)
    var program = 0
    var vertexArray = 0
    var arrayBuffer = 0
    var elementBuffer = 0
    var activeTexture = GlConst.TEXTURE0
    var texture = 0

    fun capture(gl: Gl, vertexArrays: Boolean) {
        framebuffer = gl.getInteger(GlConst.FRAMEBUFFER_BINDING)
        gl.getIntegers(GlConst.VIEWPORT, viewport)
        gl.getIntegers(GlConst.SCISSOR_BOX, scissorBox)
        scissorTest = gl.isEnabled(GlConst.SCISSOR_TEST)
        blend = gl.isEnabled(GlConst.BLEND)
        depthTest = gl.isEnabled(GlConst.DEPTH_TEST)
        // Clearing an offscreen picture's depth buffer switches depth writing on, so what the
        // engine had is saved with everything else.
        depthMask = gl.getInteger(GlConst.DEPTH_WRITEMASK) != 0
        cullFace = gl.isEnabled(GlConst.CULL_FACE)
        stencilTest = gl.isEnabled(GlConst.STENCIL_TEST)
        blendSrcRgb = gl.getInteger(GlConst.BLEND_SRC_RGB)
        blendDstRgb = gl.getInteger(GlConst.BLEND_DST_RGB)
        blendSrcAlpha = gl.getInteger(GlConst.BLEND_SRC_ALPHA)
        blendDstAlpha = gl.getInteger(GlConst.BLEND_DST_ALPHA)
        blendEquationRgb = gl.getInteger(GlConst.BLEND_EQUATION_RGB)
        blendEquationAlpha = gl.getInteger(GlConst.BLEND_EQUATION_ALPHA)
        gl.getIntegers(GlConst.COLOR_WRITEMASK, colourMask)
        program = gl.getInteger(GlConst.CURRENT_PROGRAM)
        vertexArray = if (vertexArrays) gl.getInteger(GlConst.VERTEX_ARRAY_BINDING) else 0
        arrayBuffer = gl.getInteger(GlConst.ARRAY_BUFFER_BINDING)
        elementBuffer = gl.getInteger(GlConst.ELEMENT_ARRAY_BUFFER_BINDING)
        activeTexture = gl.getInteger(GlConst.ACTIVE_TEXTURE)
        gl.activeTexture(GlConst.TEXTURE0)
        texture = gl.getInteger(GlConst.TEXTURE_BINDING_2D)
    }

    /**
     * Puts it all back. The order matters in two places: the vertex array goes back before the
     * element buffer, because that binding belongs to the vertex array; and unit 0's texture goes
     * back before the active unit does.
     */
    fun restore(gl: Gl, vertexArrays: Boolean) {
        gl.bindFramebuffer(GlConst.FRAMEBUFFER, framebuffer)
        gl.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        gl.scissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        set(gl, GlConst.SCISSOR_TEST, scissorTest)
        set(gl, GlConst.BLEND, blend)
        set(gl, GlConst.DEPTH_TEST, depthTest)
        gl.depthMask(depthMask)
        set(gl, GlConst.CULL_FACE, cullFace)
        set(gl, GlConst.STENCIL_TEST, stencilTest)
        gl.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        gl.blendEquationSeparate(blendEquationRgb, blendEquationAlpha)
        gl.colorMask(colourMask[0] != 0, colourMask[1] != 0, colourMask[2] != 0, colourMask[3] != 0)
        gl.useProgram(program)
        if (vertexArrays) gl.bindVertexArray(vertexArray)
        gl.bindBuffer(GlConst.ARRAY_BUFFER, arrayBuffer)
        gl.bindBuffer(GlConst.ELEMENT_ARRAY_BUFFER, elementBuffer)
        gl.activeTexture(GlConst.TEXTURE0)
        gl.bindTexture(GlConst.TEXTURE_2D, texture)
        gl.activeTexture(activeTexture)
    }

    private fun set(gl: Gl, cap: Int, on: Boolean) = if (on) gl.enable(cap) else gl.disable(cap)
}
