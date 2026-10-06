package dev.wildware.composegl.render.gl

import dev.wildware.composegl.render.Allocation
import dev.wildware.composegl.render.Blend
import dev.wildware.composegl.render.FrameTarget
import dev.wildware.composegl.render.ShapeProgram
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frame of draw calls through the OpenGL device allocates nothing, counted the way a phone counts.
 *
 * Every flush is a draw call, so anything the device made per call — an iterator over the vertex
 * layout's attributes was one (#248) — is made hundreds of times a frame on a busy screen. Run by
 * `jvmAllocationTest` with escape analysis off; see [dev.wildware.composegl.render.FrameAllocationTest].
 */
@Tag("allocation")
class GlDeviceAllocationTest {

    /** OpenGL that makes its objects as [RecordingGl] does and writes nothing down while drawing. */
    private class QuietGl(profile: GlProfile, inner: RecordingGl = RecordingGl(profile)) : Gl by inner {
        override fun floats(capacity: Int): GlFloats = QuietFloats(capacity)

        override fun enable(cap: Int) = Unit
        override fun disable(cap: Int) = Unit
        override fun isEnabled(cap: Int) = false
        override fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int) = Unit
        override fun blendEquationSeparate(rgb: Int, alpha: Int) = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = Unit
        override fun viewport(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun scissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) = Unit
        override fun clear(mask: Int) = Unit
        override fun depthMask(write: Boolean) = Unit
        override fun useProgram(program: Int) = Unit
        override fun uniform1i(at: Int, value: Int) = Unit
        override fun uniform1f(at: Int, value: Float) = Unit
        override fun uniform2f(at: Int, x: Float, y: Float) = Unit
        override fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float) = Unit
        override fun uniformMatrix4fv(at: Int, matrix: FloatArray) = Unit
        override fun bindBuffer(target: Int, buffer: Int) = Unit
        override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) = Unit
        override fun enableVertexAttribArray(index: Int) = Unit
        override fun disableVertexAttribArray(index: Int) = Unit
        override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = Unit
        override fun bindVertexArray(array: Int) = Unit
        override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = Unit
        override fun bindTexture(target: Int, texture: Int) = Unit
        override fun activeTexture(unit: Int) = Unit
        override fun bindFramebuffer(target: Int, framebuffer: Int) = Unit
    }

    private class QuietFloats(override val capacity: Int) : GlFloats {
        private val values = FloatArray(capacity)

        override fun set(index: Int, value: Float) {
            values[index] = value
        }

        override fun put(at: Int, from: FloatArray, offset: Int, count: Int) {
            from.copyInto(values, at, offset, offset + count)
        }
    }

    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** OpenGL ES 2 without vertex arrays, a phone's path, and a core context with them. */
    private val profiles = listOf(GlProfile(GlApi.Es, 2, 0), GlProfile(GlApi.Desktop, 3, 2, core = true))

    @Test
    fun `a frame of draw calls through the OpenGL device allocates nothing`() {
        profiles.forEach { profile ->
            val device = GlDevice(QuietGl(profile))
            device.prepare()
            device.begin(FrameTarget.Host)
            val textures = List(2) { device.texture(4, 4, smooth = true) }
            val vertices = device.vertices(8)
            device.end()

            val frame = {
                device.begin(FrameTarget.Host)
                for (draw in 0 until 10) {
                    if (draw == 5) device.scissor(0, 0, 32, 32)
                    device.drawShapes(vertices, 2, textures[draw % 2], Blend.SourceOver, identity, null, ShapeProgram.Common)
                }
                device.noScissor()
                device.end()
            }
            repeat(50) { frame() }

            assertEquals(0L, Allocation.leastOf { repeat(100) { frame() } }, "$profile")
        }
    }
}
