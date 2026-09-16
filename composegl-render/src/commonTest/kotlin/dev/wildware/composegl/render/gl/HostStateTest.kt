package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.FrameTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [HostState.Restore]: for an engine that believes its own cache of the GL state. */
class HostStateTest {

    private val texture3 = GlConst.TEXTURE0 + 3

    /** A context an engine left in a state of its own. */
    private fun engine() = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true)).also { gl ->
        gl.integers[GlConst.FRAMEBUFFER_BINDING] = 11
        gl.integers[GlConst.CURRENT_PROGRAM] = 5
        gl.integers[GlConst.VERTEX_ARRAY_BINDING] = 6
        gl.integers[GlConst.ARRAY_BUFFER_BINDING] = 8
        gl.integers[GlConst.ELEMENT_ARRAY_BUFFER_BINDING] = 7
        gl.integers[GlConst.ACTIVE_TEXTURE] = texture3
        gl.integers[GlConst.TEXTURE_BINDING_2D] = 9
        gl.integers[GlConst.BLEND_SRC_RGB] = GlConst.ONE
        gl.integers[GlConst.BLEND_DST_RGB] = GlConst.ZERO
        gl.integers[GlConst.BLEND_SRC_ALPHA] = GlConst.ONE
        gl.integers[GlConst.BLEND_DST_ALPHA] = GlConst.ZERO
        gl.integers[GlConst.BLEND_EQUATION_RGB] = GlConst.FUNC_ADD
        gl.integers[GlConst.BLEND_EQUATION_ALPHA] = GlConst.FUNC_ADD
        gl.quads[GlConst.VIEWPORT] = intArrayOf(0, 0, 640, 480)
        gl.quads[GlConst.SCISSOR_BOX] = intArrayOf(10, 20, 30, 40)
        gl.quads[GlConst.COLOR_WRITEMASK] = intArrayOf(1, 1, 1, 0)
        gl.enabled += GlConst.DEPTH_TEST
        gl.enabled += GlConst.CULL_FACE
    }

    private fun List<String>.at(call: String) = indexOf(call).also { assertTrue(it >= 0, "no $call in $this") }

    @Test
    fun `every value the engine had comes back when the frame ends`() {
        val gl = engine()
        val device = GlDevice(gl, HostState.Restore)
        device.begin(FrameTarget.Host)
        val from = gl.calls.size
        device.end()
        val end = gl.calls.subList(from, gl.calls.size)

        listOf(
            "bindFramebuffer(11)",
            "viewport(0, 0, 640, 480)",
            "scissor(10, 20, 30, 40)",
            "disable(${GlConst.SCISSOR_TEST})",
            "enable(${GlConst.DEPTH_TEST})",
            "enable(${GlConst.CULL_FACE})",
            "blendFuncSeparate(${GlConst.ONE}, ${GlConst.ZERO}, ${GlConst.ONE}, ${GlConst.ZERO})",
            "colorMask(true, true, true, false)",
            "useProgram(5)",
            "bindBuffer(${GlConst.ARRAY_BUFFER}, 8)",
            "bindTexture(9)",
        ).forEach { assertTrue(it in end, "missing $it in $end") }
    }

    @Test
    fun `an engine that had depth writing off gets it back off after a depth clear`() {
        val gl = engine()
        gl.integers[GlConst.DEPTH_WRITEMASK] = 0
        val device = GlDevice(gl, HostState.Restore)
        val target = device.offscreen(8, 8, depth = true)
        device.begin(target)
        device.target(target, 0, 0, 8, 8)
        device.clear(0f, 0f, 0f, 0f)
        val from = gl.calls.size
        device.end()
        val end = gl.calls.subList(from, gl.calls.size)

        assertTrue("depthMask(true)" in gl.calls.subList(0, from), "the clear needed it on")
        assertEquals(listOf("depthMask(false)"), end.filter { it.startsWith("depthMask") }, "and the engine's own comes back")
    }

    @Test
    fun `the vertex array goes back before the element buffer that belongs to it`() {
        val gl = engine()
        val device = GlDevice(gl, HostState.Restore)
        device.begin(FrameTarget.Host)
        val from = gl.calls.size
        device.end()
        val end = gl.calls.subList(from, gl.calls.size)

        assertTrue(end.at("bindVertexArray(6)") < end.at("bindBuffer(${GlConst.ELEMENT_ARRAY_BUFFER}, 7)"))
    }

    @Test
    fun `unit 0's texture goes back before the engine's active unit does`() {
        val gl = engine()
        val device = GlDevice(gl, HostState.Restore)
        device.begin(FrameTarget.Host)
        val from = gl.calls.size
        device.end()
        val end = gl.calls.subList(from, gl.calls.size)

        val unit0 = end.at("activeTexture(${GlConst.TEXTURE0})")
        assertTrue(unit0 < end.at("bindTexture(9)"))
        assertEquals("activeTexture($texture3)", end.last())
    }

    @Test
    fun `the texture saved is unit 0's whichever unit the engine had active`() {
        val gl = engine()
        GlDevice(gl, HostState.Restore).begin(FrameTarget.Host)

        val switched = gl.calls.at("activeTexture(${GlConst.TEXTURE0})")
        assertTrue(switched < gl.calls.at("getInteger(${GlConst.TEXTURE_BINDING_2D})"))
    }

    @Test
    fun `a game's drawing inside a frame gets the engine's state and the frame gets ours back`() {
        val gl = engine()
        val device = GlDevice(gl, HostState.Restore)
        device.begin(FrameTarget.Host)
        device.scissor(1, 2, 3, 4)

        val suspended = gl.calls.size
        device.suspend()
        assertTrue("useProgram(5)" in gl.calls.subList(suspended, gl.calls.size), "the engine's program for its own drawing")

        val resumed = gl.calls.size
        device.resume()
        val back = gl.calls.subList(resumed, gl.calls.size)
        assertTrue("disable(${GlConst.DEPTH_TEST})" in back)
        assertTrue(back.at("enable(${GlConst.SCISSOR_TEST})") < back.at("scissor(1, 2, 3, 4)"), "our scissor again")
    }

    @Test
    fun `leaving asks the driver for one value when the frame is the window`() {
        val gl = engine()
        GlDevice(gl).begin(FrameTarget.Host)
        assertEquals(listOf("getInteger(${GlConst.FRAMEBUFFER_BINDING})"), gl.named("get").filterNot { it.contains("${GlConst.MAX_TEXTURE_SIZE}") })
    }
}
