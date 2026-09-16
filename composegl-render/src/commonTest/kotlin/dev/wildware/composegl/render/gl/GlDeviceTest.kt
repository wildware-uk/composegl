package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.Blend
import dev.wildware.composegl.render.EffectQuad
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.ShapeVertex
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlDeviceTest {

    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private val glow = ShaderEffect(ShaderSource("glow", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"))

    /** One quad, drawn in a frame, on [gl]. */
    private fun drawOne(gl: RecordingGl, device: GlDevice = GlDevice(gl)): GlDevice {
        device.begin(FrameTarget.Host)
        val texture = device.texture(4, 4, smooth = true)
        val vertices = device.vertices(8)
        device.drawShapes(vertices, 1, texture, Blend.SourceOver, identity)
        device.end()
        return device
    }

    private fun List<String>.indexOfFirst(call: String) = indexOf(call).also { assertTrue(it >= 0, "no $call in $this") }

    @Test
    fun `a core context draws with a vertex array of its own bound only while it draws`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true))
        drawOne(gl)

        val array = gl.named("createVertexArray=").first().substringAfter('=')
        val bound = gl.calls.indexOfFirst("bindVertexArray($array)")
        val drawn = gl.calls.indexOfFirst("drawElements(6)")
        assertTrue(bound < drawn)
        assertEquals("bindVertexArray(0)", gl.calls.subList(drawn, gl.calls.size).first { it.startsWith("bindVertexArray") })
        assertEquals(0, gl.named("disableVertexAttribArray").size, "the array's attribute state is its own")
        assertTrue(gl.sources.values.all { it.startsWith("#version 150\n") }, "and the shaders are GLSL 1.50")
    }

    @Test
    fun `GL 2 without the extension has no vertex array and switches its attributes back off`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 2, 1))
        val device = drawOne(gl)

        assertFalse(device.usesVertexArrays)
        assertEquals(0, gl.named("createVertexArray").size + gl.named("bindVertexArray").size)
        val drawn = gl.calls.indexOfFirst("drawElements(6)")
        val after = gl.calls.subList(drawn, gl.calls.size)
        ShapeVertex.Attributes.indices.forEach { assertTrue("disableVertexAttribArray($it)" in after) }
        assertTrue(gl.sources.values.none { it.startsWith("#version") }, "GL 2 shaders go in exactly as written")
    }

    @Test
    fun `GL 2 with the vertex array extension uses one`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 2, 1), extensions = setOf("GL_ARB_vertex_array_object"))
        assertTrue(drawOne(gl).usesVertexArrays)
    }

    @Test
    fun `the index buffer is only ever an element buffer and goes up inside the device's own vertex array`() {
        listOf(GlProfile(GlApi.WebGl, 2, 0), GlProfile(GlApi.WebGl, 1, 0)).forEach { profile ->
            val gl = RecordingGl(profile)
            val device = drawOne(gl)
            // A second frame that needs more indices than were uploaded, so they go up again mid-frame.
            device.begin(FrameTarget.Host)
            device.drawShapes(device.vertices(4000), 1, device.texture(4, 4, smooth = true), Blend.SourceOver, identity)
            device.end()

            val index = gl.named("createBuffer=")[2].substringAfter('=')
            assertEquals(0, gl.named("bindBuffer(${GlConst.ARRAY_BUFFER}, $index)").size, "WebGL refuses an index buffer once it was an array buffer")
            assertEquals(0, gl.named("bufferData(${GlConst.ARRAY_BUFFER}, shorts").size)
            val uploads = gl.calls.indices.filter { gl.calls[it].startsWith("bufferData(${GlConst.ELEMENT_ARRAY_BUFFER}, shorts") }
            assertEquals(listOf("shorts 48", "shorts 24000"), uploads.map { gl.calls[it].substringAfter(", ").removeSuffix(")") })
            if (device.usesVertexArrays) {
                val shapeArray = gl.named("createVertexArray=").first().substringAfter('=')
                uploads.forEach { at ->
                    val lastArray = gl.calls.subList(0, at).last { it.startsWith("bindVertexArray") }
                    assertEquals("bindVertexArray($shapeArray)", lastArray, "not written into whatever array the engine had bound")
                }
            }
        }
    }

    @Test
    fun `ES 2 compiles version 100 and makes offscreen pictures of plain RGBA`() {
        val gl = RecordingGl(GlProfile(GlApi.Es, 2, 0))
        val device = drawOne(gl)
        device.offscreen(8, 8)

        assertTrue(gl.sources.values.all { it.startsWith("#version 100\n") })
        assertEquals("texImage2D(${GlConst.RGBA}, 8x8)", gl.named("texImage2D").last())
    }

    @Test
    fun `desktop GL makes offscreen pictures of sized RGBA8`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 3, core = true))
        GlDevice(gl).offscreen(8, 8)
        assertEquals("texImage2D(${GlConst.RGBA8}, 8x8)", gl.named("texImage2D").last())
    }

    @Test
    fun `an offscreen picture asked for depth gets a depth buffer of its own size`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 3, core = true))
        val target = GlDevice(gl).offscreen(16, 9, depth = true) as GlDeviceTarget

        assertTrue(target.depth)
        assertTrue(target.depthBuffer != 0, "a renderbuffer of its own")
        assertEquals("renderbufferStorage(${GlConst.DEPTH_COMPONENT24}, 16x9)", gl.named("renderbufferStorage").single())
        assertEquals(
            "framebufferRenderbuffer(${GlConst.DEPTH_ATTACHMENT}, ${target.depthBuffer})",
            gl.named("framebufferRenderbuffer").single(),
        )
    }

    @Test
    fun `ES 2 and WebGL 1 take the only depth format they have`() {
        listOf(GlProfile(GlApi.Es, 2, 0), GlProfile(GlApi.WebGl, 1, 0)).forEach { profile ->
            val gl = RecordingGl(profile)
            GlDevice(gl).offscreen(8, 8, depth = true)
            assertEquals("renderbufferStorage(${GlConst.DEPTH_COMPONENT16}, 8x8)", gl.named("renderbufferStorage").single(), "$profile")
        }
    }

    @Test
    fun `an offscreen picture without depth asks for no renderbuffer at all`() {
        val gl = RecordingGl()
        val target = GlDevice(gl).offscreen(8, 8) as GlDeviceTarget

        assertFalse(target.depth)
        assertEquals(0, gl.calls.count { it.startsWith("createRenderbuffer") || it.startsWith("renderbufferStorage") })
    }

    @Test
    fun `clearing a target that has depth clears the depth buffer with it`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        val target = device.offscreen(8, 8, depth = true)
        device.begin(target)
        device.target(target, 0, 0, 8, 8)
        val from = gl.calls.size
        device.clear(0f, 0f, 0f, 1f)
        val cleared = gl.calls.subList(from, gl.calls.size)

        assertTrue("depthMask(true)" in cleared, "a game that left depth writing off would clear nothing: $cleared")
        assertEquals("clear(${GlConst.COLOR_BUFFER_BIT or GlConst.DEPTH_BUFFER_BIT})", cleared.last())
    }

    @Test
    fun `clearing a target without depth clears colour only`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        val target = device.offscreen(8, 8)
        device.begin(target)
        device.target(target, 0, 0, 8, 8)
        device.clear(0f, 0f, 0f, 1f)

        assertEquals("clear(${GlConst.COLOR_BUFFER_BIT})", gl.named("clear(").last())
        assertEquals(0, gl.named("depthMask").size, "and nothing touches the engine's depth writing")
    }

    @Test
    fun `giving a target back gives its depth buffer back with it`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        val target = device.offscreen(8, 8, depth = true) as GlDeviceTarget
        device.delete(target)

        assertEquals("deleteRenderbuffer(${target.depthBuffer})", gl.named("deleteRenderbuffer").single())
        assertEquals(1, gl.named("deleteFramebuffer").size)
        assertEquals(1, gl.named("deleteTexture").size)
    }

    @Test
    fun `a game's own framebuffer can say it already has depth`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        val adopted = GlDeviceTarget.adopt(3, 42, 8, 8, depth = true)
        device.begin(adopted)
        device.target(adopted, 0, 0, 8, 8)
        device.clear(0f, 0f, 0f, 1f)
        device.delete(adopted)

        assertEquals("clear(${GlConst.COLOR_BUFFER_BIT or GlConst.DEPTH_BUFFER_BIT})", gl.named("clear(").last())
        assertEquals(0, gl.named("delete").size, "and nothing of the game's is deleted")
    }

    @Test
    fun `GL 2 without framebuffers cannot draw offscreen`() {
        assertFalse(GlDevice(RecordingGl(GlProfile(GlApi.Desktop, 2, 1))).limits.offscreen)
        assertTrue(GlDevice(RecordingGl(GlProfile(GlApi.Desktop, 2, 1), setOf("GL_EXT_framebuffer_object"))).limits.offscreen)
        assertTrue(GlDevice(RecordingGl(GlProfile(GlApi.WebGl, 1, 0))).limits.offscreen)
    }

    @Test
    fun `nothing touches the driver until something is asked for`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        device.vertices(16)
        device.close()
        assertEquals(emptyList<String>(), gl.calls)
    }

    @Test
    fun `a frame takes what the renderer relies on rather than assuming it`() {
        val gl = RecordingGl()
        gl.enabled += GlConst.DEPTH_TEST
        GlDevice(gl).begin(FrameTarget.Host)

        listOf(
            "disable(${GlConst.DEPTH_TEST})",
            "disable(${GlConst.CULL_FACE})",
            "disable(${GlConst.STENCIL_TEST})",
            "colorMask(true, true, true, true)",
            "activeTexture(${GlConst.TEXTURE0})",
            "blendEquationSeparate(${GlConst.FUNC_ADD}, ${GlConst.FUNC_ADD})",
            "enable(${GlConst.BLEND})",
        ).forEach { assertTrue(it in gl.calls, "missing $it") }
    }

    @Test
    fun `leaving hands back the documented end state`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true))
        val device = GlDevice(gl)
        device.begin(FrameTarget.Host)
        val from = gl.calls.size
        device.end()
        val end = gl.calls.subList(from, gl.calls.size)

        assertEquals(
            listOf(
                "disable(${GlConst.SCISSOR_TEST})",
                // Said again at the end rather than assumed from the start: the same state is what
                // a game's drawing inside a frame or a scene is handed.
                "disable(${GlConst.DEPTH_TEST})",
                "disable(${GlConst.CULL_FACE})",
                "disable(${GlConst.STENCIL_TEST})",
                "enable(${GlConst.BLEND})",
                "blendFuncSeparate(${GlConst.SRC_ALPHA}, ${GlConst.ONE_MINUS_SRC_ALPHA}, ${GlConst.SRC_ALPHA}, ${GlConst.ONE_MINUS_SRC_ALPHA})",
                "useProgram(0)",
                "bindVertexArray(0)",
                "bindBuffer(${GlConst.ARRAY_BUFFER}, 0)",
                "bindBuffer(${GlConst.ELEMENT_ARRAY_BUFFER}, 0)",
                "activeTexture(${GlConst.TEXTURE0})",
                "bindTexture(0)",
            ),
            end,
        )
    }

    @Test
    fun `a frame into an offscreen target puts the engine's framebuffer and viewport back`() {
        val gl = RecordingGl()
        gl.integers[GlConst.FRAMEBUFFER_BINDING] = 7
        gl.quads[GlConst.VIEWPORT] = intArrayOf(1, 2, 3, 4)
        val device = GlDevice(gl)
        val target = device.offscreen(16, 16) as GlDeviceTarget
        device.begin(target)
        device.target(target, 0, 0, 16, 16)
        device.end()

        assertEquals("bindFramebuffer(${target.framebuffer})", gl.named("bindFramebuffer").dropLast(1).last())
        assertEquals("bindFramebuffer(7)", gl.named("bindFramebuffer").last())
        assertEquals("viewport(1, 2, 3, 4)", gl.named("viewport").last())
    }

    @Test
    fun `an effect is compiled once for the same text and composites premultiplied`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true))
        val device = GlDevice(gl)
        device.begin(FrameTarget.Host)
        val picture = device.texture(4, 4, smooth = true)
        device.drawEffect(glow, picture, EffectQuad(), Blend.PremultipliedAdditive)
        device.drawEffect(glow.copy(source = glow.source.copy()), picture, EffectQuad(), Blend.PremultipliedSourceOver)
        device.end()

        assertEquals(2, gl.named("createProgram").size, "the shape program and one effect program")
        assertEquals(
            listOf(
                "blendFuncSeparate(${GlConst.ONE}, ${GlConst.ONE}, ${GlConst.ONE}, ${GlConst.ONE})",
                "blendFuncSeparate(${GlConst.ONE}, ${GlConst.ONE_MINUS_SRC_ALPHA}, ${GlConst.ONE}, ${GlConst.ONE_MINUS_SRC_ALPHA})",
            ),
            gl.named("blendFuncSeparate").take(2),
        )
        val effectFragment = gl.sources.values.single { "u_alpha;" in it }
        assertTrue(effectFragment.startsWith("#version 150\nout vec4 cg_FragColor;\nin vec2 v_texCoord;"), effectFragment)
        assertTrue("cg_FragColor = texture(u_texture, v_texCoord) * u_alpha;" in effectFragment)
    }

    @Test
    fun `an effect that will not compile says which one`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        device.prepare()
        gl.failCompile = "0:1: syntax error"
        val thrown = assertFailsWith<IllegalArgumentException> {
            device.drawEffect(glow, device.texture(1, 1, smooth = true), EffectQuad(), Blend.PremultipliedSourceOver)
        }
        assertTrue("glow" in thrown.message.orEmpty() && "syntax error" in thrown.message.orEmpty(), thrown.message)
    }

    @Test
    fun `a lost context is forgotten without deleting and rebuilt on the next draw`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true))
        val device = drawOne(gl)
        device.contextLost()
        assertFalse(device.prepared)
        drawOne(gl, device)

        assertEquals(2, gl.named("createProgram").size)
        assertEquals(0, gl.named("delete").count { !it.startsWith("deleteShader") }, "those names died with the context")
    }

    @Test
    fun `closing deletes what was built and only that`() {
        val gl = RecordingGl(GlProfile(GlApi.Desktop, 3, 2, core = true))
        val device = drawOne(gl)
        device.close()
        assertEquals(1, gl.named("deleteProgram").size)
        assertEquals(3, gl.named("deleteBuffer").size)
        assertEquals(2, gl.named("deleteVertexArray").size)
    }

    @Test
    fun `a game's own texture is drawn but never deleted`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        device.delete(GlDeviceTexture.adopt(42, 8, 8))
        device.delete(GlDeviceTarget.adopt(3, 42, 8, 8))
        assertEquals(0, gl.named("delete").size)
        assertEquals(GlDeviceTexture.adopt(42, 1, 1), GlDeviceTexture.adopt(42, 8, 8), "the same GL name batches as one texture")
    }

    @Test
    fun `a texture write sends only the rectangle asked for`() {
        val gl = RecordingGl()
        val device = GlDevice(gl)
        val texture = device.texture(16, 16, smooth = false)
        device.write(texture, 2, 3, 4, 5, ByteArray(16 * 16 * 4), 16)
        assertEquals("texSubImage2D(2, 3, 4x5)", gl.named("texSubImage2D").single())
        assertTrue("texParameteri(${GlConst.TEXTURE_MIN_FILTER}, ${GlConst.NEAREST})" in gl.calls)
    }
}
