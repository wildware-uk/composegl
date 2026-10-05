package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.node.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuadBatchTest {

    private val device = RecordingDevice()
    private val sheet = device.texture(64, 64, smooth = true)
    private val other = device.texture(64, 64, smooth = true)
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private fun QuadBatch.picture(texture: DeviceTexture) =
        textured(texture, 0f, 0f, 10f, 10f, 0f, 0f, 1f, 1f, Colour.White)

    /** The reasons a trace heard, in the order drawn, without the frame's own end. */
    private fun reasons(trace: DrawCallTrace) = trace.culprits(withEnd = true).map { it.reason to it.calls }

    @Test
    fun `the vertex layout is as many floats as its attributes add up to`() {
        assertEquals(ShapeVertex.Attributes.sumOf { it.size }, ShapeVertex.Floats)
        var offset = 0
        ShapeVertex.Attributes.forEach {
            assertEquals(offset, it.offset, "${it.name} starts where the one before it ends")
            offset += it.size
        }
    }

    @Test
    fun `a picture held inside a box carries it and a frame that never let go does not pass it on`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.holdInside(0.1f, 0.2f, 0.3f, 0.4f)
        batch.picture(sheet)
        // Never let go: the frame threw part way through a composite.
        batch.end()
        batch.begin(identity)
        batch.picture(sheet)
        batch.end()

        val radii = device.draws.map { draw -> (24 until 28).map { draw.at(0, it) } }
        assertEquals(listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f), listOf(0f, 0f, 0f, 0f)), radii)
    }

    @Test
    fun `quads from one texture are one draw call`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        repeat(50) { batch.picture(sheet) }
        batch.end()

        assertEquals(1, batch.renderCalls)
        assertEquals(50, device.draws.single().quads)
        assertSame(sheet, device.draws.single().texture)
    }

    @Test
    fun `a different texture flushes and says so`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.picture(other)
        batch.end()

        assertEquals(2, batch.renderCalls)
        assertEquals(listOf(BatchBreak.Texture to 1, BatchBreak.End to 1), reasons(trace))
        assertEquals(2, trace.total)
    }

    @Test
    fun `a blend change flushes what was queued the old way`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.blend(BlendMode.Additive, premultiplied = false)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(Blend.SourceOver, Blend.Additive), device.draws.map { it.blend })
        assertEquals(listOf(BatchBreak.Blend to 1, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a blend set to the one in force flushes nothing`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.blend(BlendMode.SourceOver, premultiplied = false)
        batch.picture(sheet)
        batch.blend(BlendMode.Additive, premultiplied = false)
        batch.blend(BlendMode.Additive, premultiplied = false)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(Blend.SourceOver, Blend.Additive), device.draws.map { it.blend })
        assertEquals(listOf(BatchBreak.Blend to 1, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a blend or a clip changed and changed back before anything is drawn cuts nothing`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.blend(BlendMode.Additive, premultiplied = false)
        batch.scissor(1, 2, 3, 4)
        // A list scrolled out of sight: its clip and its glow go on and come off with nothing drawn.
        batch.noScissor()
        batch.blend(BlendMode.SourceOver, premultiplied = false)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(2), device.draws.map { it.quads })
        assertEquals(emptyList(), device.calls.filter { it.startsWith("scissor") || it == "noScissor" })
        assertEquals(listOf(BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a clip reaches the device when something is drawn under it and the cut is blamed on who asked`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        val clipper = UiNode("clipper")
        val child = UiNode("child")
        batch.begin(identity)
        trace.node = clipper
        batch.picture(sheet)
        batch.scissor(1, 2, 3, 4)
        assertEquals(emptyList(), device.calls.filter { it.startsWith("scissor") }, "nothing drawn under it yet")

        trace.node = child
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(1, 1), device.draws.map { it.quads })
        assertEquals(1, device.calls.count { it == "scissor(1, 2, 3, 4)" })
        assertTrue(device.calls.indexOf("scissor(1, 2, 3, 4)") > device.calls.indexOfFirst { it.startsWith("drawShapes") }, "after what was queued outside it")
        val cut = trace.culprits().single()
        assertEquals(BatchBreak.Clip to clipper, cut.reason to cut.node)
    }

    @Test
    fun `a clip reaches the device before drawing that does not go through the batch`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.picture(sheet)
        batch.scissor(1, 2, 3, 4)
        batch.flushForDevice(BatchBreak.Raw)

        assertEquals(1, device.draws.size)
        assertEquals("scissor(1, 2, 3, 4)", device.calls.last())
        batch.end()
    }

    @Test
    fun `a rounded clip put on and taken off before anything is drawn cuts nothing`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.picture(sheet)
        batch.mask(ClipMask())
        batch.mask(null)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(2), device.draws.map { it.quads })
    }

    @Test
    fun `a rounded clip filled in again draws what was queued inside it as it was`() {
        val batch = QuadBatch(device)
        val mask = ClipMask().apply { centreX = 1f }
        batch.begin(identity)
        batch.mask(mask)
        batch.picture(sheet)
        // Taken off with nothing drawn since, so the queue still holds a quad inside it.
        batch.mask(null)
        batch.refilling(mask)
        mask.centreX = 2f
        batch.mask(mask)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(1f, 2f), device.draws.map { it.mask?.centreX })
    }

    @Test
    fun `the rounded clip in force set again flushes nothing`() {
        val batch = QuadBatch(device)
        val mask = ClipMask()
        batch.begin(identity)
        batch.mask(mask)
        batch.picture(sheet)
        batch.mask(mask)
        batch.picture(sheet)
        batch.mask(null)
        batch.mask(null)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(2, 1), device.draws.map { it.quads })
    }

    @Test
    fun `a layer composite blames the layer and draws premultiplied`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.blend(BlendMode.SourceOver, premultiplied = true, reason = BatchBreak.Layer)
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(Blend.SourceOver, Blend.PremultipliedSourceOver), device.draws.map { it.blend })
        assertEquals(listOf(BatchBreak.Layer to 1, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a full batch flushes itself and carries on`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device, maxQuads = 4).also { it.trace = trace }
        batch.begin(identity)
        repeat(10) { batch.picture(sheet) }
        batch.end()

        assertEquals(listOf(4, 4, 2), device.draws.map { it.quads })
        assertEquals(listOf(BatchBreak.Full to 2, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `an empty flush costs nothing`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.flush(BatchBreak.Clip)
        batch.blend(BlendMode.Additive, premultiplied = false)
        batch.end()

        assertEquals(0, batch.renderCalls)
        assertEquals(0, trace.total)
        assertEquals(0, device.draws.size)
    }

    @Test
    fun `a new projection flushes as a layer and the next draw uses it`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        val moved = identity.copyOf().also { it[12] = 5f }
        batch.projection(moved)
        batch.picture(sheet)
        batch.end()

        assertEquals(0f, device.draws[0].projection[12])
        assertEquals(5f, device.draws[1].projection[12])
        assertEquals(listOf(BatchBreak.Layer to 1, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `every frame counts its own draw calls from zero`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.picture(sheet)
        batch.picture(other)
        batch.end()
        batch.begin(identity)
        batch.picture(sheet)
        batch.end()

        assertEquals(1, batch.renderCalls)
    }

    @Test
    fun `a box is written as a quad with its shape per vertex`() {
        val white = WhiteSpot(sheet, 0.25f, 0.5f)
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.shape(
            white, left = 10f, bottom = 20f, width = 100f, height = 40f,
            fill = Colour.Red, topLeft = 5f, topRight = 60f, bottomRight = 0f, bottomLeft = 3f,
            border = Colour.Blue, borderWidth = 2f, shadow = Colour.Transparent, shadowSpread = 4f, aa = 1f,
        )
        batch.end()

        val draw = device.draws.single()
        // Wound from the bottom-left, grown by the shadow and the soft edge.
        assertEquals(listOf(5f, 15f), listOf(draw.at(0, 0), draw.at(0, 1)))
        assertEquals(listOf(115f, 65f), listOf(draw.at(2, 0), draw.at(2, 1)))
        assertEquals(listOf(1f, 0f, 0f, 1f), draw.fill(0))
        assertEquals(listOf(0.25f, 0.5f), listOf(draw.at(0, 15), draw.at(0, 16)), "sampled at the white spot")
        assertEquals(listOf(2f, 4f, 1f), (21 until 24).map { draw.at(1, it) }, "border, spread, soft edge")
        // Each radius is held to half the shorter side, 20.
        assertEquals(listOf(5f, 20f, 0f, 3f), (24 until 28).map { draw.at(3, it) })
    }

    @Test
    fun `a premultiplied picture is flagged for the shader to straighten`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.textured(sheet, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, Colour.White, premultiplied = true)
        batch.picture(sheet)
        batch.end()

        val draw = device.draws.single()
        assertEquals(ShapeVertex.PremultipliedPicture, draw.at(0, 28))
        assertEquals(0f, draw.at(4, 28))
        assertEquals(0f, draw.at(0, 23), "a picture has no soft edge")
    }
}
