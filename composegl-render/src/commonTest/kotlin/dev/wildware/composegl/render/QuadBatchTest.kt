package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.node.UiNode
import kotlin.test.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `the vertex layout is as many slots as its attributes add up to`() {
        assertEquals(ShapeVertex.Attributes.sumOf { it.slots }, ShapeVertex.Floats)
        var offset = 0
        ShapeVertex.Attributes.forEach {
            assertEquals(offset, it.offset, "${it.name} starts where the one before it ends")
            offset += it.slots
        }
    }

    @Test
    fun `the fill - border and shadow are four bytes in one slot each`() {
        assertEquals(22, ShapeVertex.Floats, "88 bytes a vertex")
        assertEquals(listOf("a_color", "a_borderColor", "a_shadowColor"), ShapeVertex.Attributes.filter { it.packed }.map { it.name })
        ShapeVertex.Attributes.filter { it.packed }.forEach { assertEquals(4, it.size, "${it.name} is red, green, blue and alpha") }
    }

    @Test
    fun `a quad's fill - border and shadow are its colours' bytes red first at every corner`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.shape(
            white, left = 0f, bottom = 0f, width = 10f, height = 10f,
            fill = Colour(alpha = 0x40, red = 0x10, green = 0x20, blue = 0x30),
            topLeft = 2f, topRight = 2f, bottomRight = 2f, bottomLeft = 2f,
            border = Colour(alpha = 0x80, red = 0x50, green = 0x60, blue = 0x70), borderWidth = 1f,
            shadow = Colour(alpha = 0xFF, red = 0xFF, green = 0x00, blue = 0x00), shadowSpread = 3f, aa = 1f,
        )
        batch.textured(sheet, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, Colour(alpha = 0xFF, red = 0x01, green = 0x02, blue = 0x03))
        batch.end()

        val draw = device.draws.single()
        (0 until 4).forEach { corner ->
            // Memory holds red, green, blue, alpha: the bits of a little-endian 0xAABBGGRR.
            assertEquals(0x40302010, draw.bits(corner, "a_color"), "fill at corner $corner")
            assertEquals(0x80706050.toInt(), draw.bits(corner, "a_borderColor"), "border at corner $corner")
            assertEquals(0xFF0000FF.toInt(), draw.bits(corner, "a_shadowColor"), "shadow at corner $corner")
        }
        assertEquals(0xFF030201.toInt(), draw.bits(4, "a_color"), "a picture's tint, in the next quad")
        assertEquals(0, draw.bits(4, "a_borderColor"), "and no border")
        assertEquals(0, draw.bits(4, "a_shadowColor"), "and no shadow")
        assertEquals(listOf(1f, 0f, 0f, 1f), draw.shadow(0), "the shadow read back as the GPU reads it")
        assertEquals(listOf(0x50 / 255f, 0x60 / 255f, 0x70 / 255f, 0x80 / 255f), draw.border(0), "read back as the GPU reads it")
    }

    @Test
    fun `a colour whose bits are a NaN comes out of the batch exactly as it went in`() {
        // Opaque with a blue of 0x80 or more is a NaN when read as a float: white is one. Neither
        // of these may be made the canonical NaN on the way through, on any platform.
        val signalling = Colour(alpha = 0xFF, red = 0x01, green = 0x00, blue = 0x80)
        val quiet = Colour(alpha = 0x7F, red = 0x45, green = 0x23, blue = 0xC1)
        assertTrue(Float.fromBits(0xFF800001.toInt()).isNaN() && Float.fromBits(0x7FC12345).isNaN())
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.shape(
            white, left = 0f, bottom = 0f, width = 10f, height = 10f,
            fill = signalling, topLeft = 0f, topRight = 0f, bottomRight = 0f, bottomLeft = 0f,
            border = quiet, borderWidth = 1f, shadow = Colour.Transparent, shadowSpread = 0f, aa = 1f,
        )
        batch.textured(sheet, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, Colour.White)
        batch.end()

        val draw = device.draws.single()
        assertEquals(0xFF800001.toInt(), draw.bits(0, "a_color"))
        assertEquals(0x7FC12345, draw.bits(0, "a_borderColor"))
        assertEquals(-1, draw.bits(draw.quads * 4 - 1, "a_color"), "white is every bit set")
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

        val radii = device.draws.map { draw -> (0 until 4).map { draw.at(0, "a_radii", it) } }
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
        assertEquals(listOf(0.25f, 0.5f), listOf(draw.at(0, "a_texCoord0"), draw.at(0, "a_texCoord0", 1)), "sampled at the white spot")
        assertEquals(listOf(2f, 4f, 1f), (0 until 3).map { draw.at(1, "a_shape", it) }, "border, spread, soft edge")
        // Each radius is held to half the shorter side, 20.
        assertEquals(listOf(5f, 20f, 0f, 3f), (0 until 4).map { draw.at(3, "a_radii", it) })
    }

    @Test
    fun `a premultiplied picture is flagged for the shader to straighten`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.textured(sheet, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, Colour.White, premultiplied = true)
        batch.picture(sheet)
        batch.end()

        val draw = device.draws.single()
        assertEquals(ShapeVertex.PremultipliedPicture, draw.at(0, "a_gradient"))
        assertEquals(0f, draw.at(4, "a_gradient"))
        assertEquals(0f, draw.at(0, "a_shape", 2), "a picture has no soft edge")
    }

    private val white = WhiteSpot(sheet, 0.25f, 0.5f)

    /** A plain box, or with [spread] a shadow outside it or, negative, a shade inside it. */
    private fun QuadBatch.box(spread: Float = 0f, border: Float = 0f) = shape(
        white, left = 0f, bottom = 0f, width = 100f, height = 40f,
        fill = Colour.Red, topLeft = 4f, topRight = 4f, bottomRight = 4f, bottomLeft = 4f,
        border = Colour.Blue, borderWidth = border, shadow = Colour.Black, shadowSpread = spread, aa = 1f,
    )

    private fun QuadBatch.lit() = relief(
        white, left = 0f, bottom = 0f, width = 100f, height = 40f,
        topLeft = 4f, topRight = 4f, bottomRight = 4f, bottomLeft = 4f,
        kind = ShapeVertex.ReliefFillet, bevel = 6f, strength = 0.5f, lightX = 0f, lightY = 1f, lightZ = 0.5f,
        gloss = 0.3f, polish = 0.5f, face = Colour.Green, faceU = 0f, faceV = 0f, faceWidth = 0f, faceTiles = 0f, aa = 1f,
    )

    private fun QuadBatch.ramp() = rampGradient(
        white, left = 0f, bottom = 0f, width = 100f, height = 40f, tint = Colour.White, radial = false,
        axisX = 0.01f, axisY = 0f, u = 0f, v = 0f, u2 = 0.5f, v2 = 0f,
        topLeft = 0f, topRight = 0f, bottomRight = 0f, bottomLeft = 0f, border = Colour.Transparent, borderWidth = 0f, aa = 1f,
    )

    private fun QuadBatch.twoColours(axisX: Float = 0f, axisY: Float = 0.025f, width: Float = 100f, height: Float = 40f, radial: Boolean = false) =
        gradient(
            white, left = 0f, bottom = 0f, width = width, height = height, start = Colour.Red, end = Colour.Blue,
            radial = radial, axisX = axisX, axisY = axisY, topLeft = 0f, topRight = 0f, bottomRight = 0f, bottomLeft = 0f, aa = 1f,
        )

    /** The program each draw went through, one draw per call of [draw]. */
    private fun programsOf(vararg draw: QuadBatch.() -> Unit): List<ShapeProgram> = draw.map { one ->
        val batch = QuadBatch(device)
        batch.begin(identity)
        one(batch)
        batch.end()
        device.draws.last().program
    }

    @Test
    fun `a lit surface - a run of stops and a shade inside a shape need the full program`() {
        assertEquals(
            List(3) { ShapeProgram.Full },
            programsOf({ lit() }, { ramp() }, { box(spread = -6f) }),
        )
    }

    @Test
    fun `a run of stops carries its strip's start in the texture coordinate and its end in the spread`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.rampGradient(
            white, left = 0f, bottom = 0f, width = 100f, height = 40f, tint = Colour.White, radial = false,
            axisX = 0.01f, axisY = 0f, u = 0.125f, v = 0.375f, u2 = 0.625f, v2 = 0.375f,
            topLeft = 0f, topRight = 0f, bottomRight = 0f, bottomLeft = 0f, border = Colour.Blue, borderWidth = 2f, aa = 1f,
        )
        batch.end()

        val draw = device.draws.single()
        assertSame(sheet, draw.texture, "the strip is read from the white spot's texture")
        (0 until 4).forEach { corner ->
            assertEquals(listOf(0.125f, 0.375f), listOf(draw.at(corner, "a_texCoord0"), draw.at(corner, "a_texCoord0", 1)), "the start at corner $corner")
            assertEquals(0.625f, draw.at(corner, "a_shape", 1), "the end's u at corner $corner: it lies on the start's row")
            assertEquals(0, draw.bits(corner, "a_shadowColor"), "a run of stops casts no shadow")
            assertEquals(2f, draw.at(corner, "a_shape", 0), "and keeps its outline")
            assertEquals(listOf(0f, 0f, 1f, 1f), draw.border(corner), "in its own colour")
        }
    }

    @Test
    fun `a run of stops off one row of the atlas is refused`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        assertFailsWith<IllegalArgumentException> {
            batch.rampGradient(
                white, left = 0f, bottom = 0f, width = 100f, height = 40f, tint = Colour.White, radial = false,
                axisX = 0.01f, axisY = 0f, u = 0f, v = 0.25f, u2 = 0.5f, v2 = 0.5f,
                topLeft = 0f, topRight = 0f, bottomRight = 0f, bottomLeft = 0f, border = Colour.Transparent, borderWidth = 0f, aa = 1f,
            )
        }
    }

    @Test
    fun `a lit surface carries its light and gloss as the shadow's bytes`() {
        val batch = QuadBatch(device)
        batch.begin(identity)
        batch.lit()
        batch.end()

        val draw = device.draws.single()
        // Red, green and blue are the light's direction from minus one to one written zero to one; alpha is the gloss.
        (0 until 4).forEach { assertEquals(listOf(127, 255, 191, 76), draw.shadow(it).map { part -> (part * 255f).roundToInt() }, "corner $it") }
    }

    @Test
    fun `everything else goes through the common program`() {
        assertEquals(
            List(8) { ShapeProgram.Common },
            programsOf(
                { box() },
                { box(spread = 6f) },
                { box(border = 2f) },
                { box(border = -2f) },
                { twoColours() },
                { twoColours(radial = true) },
                { picture(sheet) },
                { fan(white, floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), Colour.Red) },
            ),
        )
    }

    @Test
    fun `a switch of program flushes and is blamed on the program`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.box()
        batch.picture(sheet)
        batch.lit()
        batch.box(spread = -6f)
        batch.ramp()
        batch.picture(sheet)
        batch.end()

        assertEquals(listOf(2, 3, 1), device.draws.map { it.quads }, "each run of one program is one draw")
        assertEquals(listOf(ShapeProgram.Common, ShapeProgram.Full, ShapeProgram.Common), device.draws.map { it.program })
        assertEquals(listOf(BatchBreak.Program to 2, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a change of texture with a change of program is one draw call blamed on the texture`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(other)
        batch.lit()
        batch.end()

        assertEquals(listOf(ShapeProgram.Common, ShapeProgram.Full), device.draws.map { it.program })
        assertEquals(listOf(BatchBreak.Texture to 1, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a picture held inside a pooled corner goes through the held program and only while held`() {
        val trace = DrawCallTrace()
        val batch = QuadBatch(device).also { it.trace = trace }
        batch.begin(identity)
        batch.picture(sheet)
        batch.holdInside(0.1f, 0.2f, 0.3f, 0.4f)
        batch.picture(sheet)
        batch.textured(sheet, 0f, 0f, 10f, 10f, 5f, 5f, 30f, 0f, 0f, 1f, 1f, Colour.White)
        batch.textured(sheet, floatArrayOf(0f, 10f, 10f, 10f, 10f, 0f, 0f, 0f), 0f, 0f, 1f, 1f, Colour.White)
        batch.projected(sheet, floatArrayOf(0f, 10f, 1f, 10f, 10f, 1f, 10f, 0f, 1f, 0f, 0f, 1f), 0f, 0f, 1f, 1f, Colour.White)
        batch.box()
        batch.letGo()
        batch.picture(sheet)
        batch.end()

        assertEquals(
            listOf(ShapeProgram.Common to 1, ShapeProgram.Held to 4, ShapeProgram.Common to 2),
            device.draws.map { it.program to it.quads },
            "every way a picture is written, while held; a box is never held",
        )
        assertEquals(listOf(BatchBreak.Program to 2, BatchBreak.End to 1), reasons(trace))
    }

    @Test
    fun `a gradient stretched past what mediump can carry across a wide box takes the full program`() {
        // 2^-15 a unit is a gradient 32,768 units long: too small an axis for mediump to promise,
        // and across a box 4,000 wide it moves the gradient by a sixteenth.
        val stretched = 1f / 32768f
        assertEquals(
            listOf(ShapeProgram.Full, ShapeProgram.Full),
            programsOf({ twoColours(axisX = stretched, axisY = 0f, width = 4000f) }, { twoColours(axisX = 0f, axisY = stretched, height = 4000f) }),
        )
        // The same axis across a small box, or the dust a turned axis leaves in its other half, moves
        // it by less than a thousandth wherever it is rounded: still the common program.
        assertEquals(
            listOf(ShapeProgram.Common, ShapeProgram.Common, ShapeProgram.Common),
            programsOf(
                { twoColours(axisX = stretched, axisY = 0f, width = 40f) },
                { twoColours(axisX = -4.37e-8f, axisY = 0.025f, width = 4000f) },
                { twoColours(axisX = stretched, axisY = 0f, width = 4000f, radial = true) },
            ),
        )
    }
}
