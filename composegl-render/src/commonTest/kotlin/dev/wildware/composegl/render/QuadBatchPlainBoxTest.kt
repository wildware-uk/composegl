package dev.wildware.composegl.render

import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plain box — one fill colour, nothing cast — is drawn as a flat quad over its middle, which the
 * shader treats as a picture, and shape quads round its edge, which keep the whole distance field.
 * Most of what a screen paints is the middle of big panels, where no corner, border or soft edge
 * reaches, and the picture path is the cheapest thing the shader does.
 */
class QuadBatchPlainBoxTest {

    private val device = RecordingDevice()
    private val sheet = device.texture(64, 64, smooth = true)
    private val white = WhiteSpot(sheet, 0.25f, 0.5f)
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private class Box(
        val left: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
        val topLeft: Float = 0f,
        val topRight: Float = 0f,
        val bottomRight: Float = 0f,
        val bottomLeft: Float = 0f,
        val fill: Colour = Colour.Red,
        val border: Colour = Colour.Transparent,
        val borderWidth: Float = 0f,
        val shadow: Colour = Colour.Transparent,
        val shadowSpread: Float = 0f,
        val aa: Float = 1f,
    )

    private fun draw(vararg boxes: Box, maxQuads: Int = 2048, premultiplied: Boolean = false): List<RecordingDevice.Draw> {
        val batch = QuadBatch(device, maxQuads)
        batch.begin(identity)
        if (premultiplied) batch.blend(BlendMode.SourceOver, premultiplied = true)
        boxes.forEach {
            batch.shape(
                white, it.left, it.bottom, it.width, it.height,
                fill = it.fill,
                topLeft = it.topLeft, topRight = it.topRight, bottomRight = it.bottomRight, bottomLeft = it.bottomLeft,
                border = it.border, borderWidth = it.borderWidth,
                shadow = it.shadow, shadowSpread = it.shadowSpread,
                aa = it.aa,
            )
        }
        batch.end()
        return device.draws
    }

    /** One quad as it was written: where it lies, and the soft edge the shader is told. */
    private class Quad(val left: Float, val bottom: Float, val right: Float, val top: Float, val softEdge: Float, val fill: List<Float>) {
        val area: Float get() = (right - left) * (top - bottom)
        val flat: Boolean get() = softEdge == 0f

        fun overlaps(other: Quad): Boolean =
            left < other.right && other.left < right && bottom < other.top && other.bottom < top
    }

    private fun List<RecordingDevice.Draw>.quads(): List<Quad> = flatMap { draw ->
        (0 until draw.quads).map { quad ->
            val first = quad * 4
            // Wound from the bottom-left, so the first corner is that and the third the top-right.
            Quad(
                draw.at(first, 0), draw.at(first, 1), draw.at(first + 2, 0), draw.at(first + 2, 1),
                draw.at(first, "a_shape", 2), draw.fill(first),
            )
        }
    }

    /**
     * The shader's own distance from a point to the box's edge — negative inside — worked out the
     * same way: the radius of the quarter the point is in, then the rounded-box distance with it.
     */
    private fun Box.distance(x: Float, y: Float): Float {
        val most = min(width, height) / 2f
        val acrossX = x - (left + width / 2f)
        val acrossY = y - (bottom + height / 2f)
        val radius = when {
            acrossY >= 0f -> if (acrossX < 0f) topLeft else topRight
            else -> if (acrossX < 0f) bottomLeft else bottomRight
        }.coerceIn(0f, most)
        val qx = abs(acrossX) - width / 2f + radius
        val qy = abs(acrossY) - height / 2f + radius
        val outX = max(qx, 0f)
        val outY = max(qy, 0f)
        return min(max(qx, qy), 0f) + sqrt(outX * outX + outY * outY) - radius
    }

    /** How far in the shape path stops changing anything: past the soft edge and an inside border. */
    private val Box.flatFrom: Float get() = aa + max(borderWidth, 0f)

    @Test
    fun `a full-screen plain box is one flat inside quad and four edge quads`() {
        val box = Box(0f, 0f, 1080f, 2400f)
        val quads = draw(box).quads()

        assertEquals(1, device.draws.size, "still one draw call")
        assertEquals(5, quads.size)
        val inside = quads.filter { it.flat }
        assertEquals(1, inside.size, "one quad takes the picture path")
        val middle = inside.single()
        assertEquals(listOf(1f, 1f, 1079f, 2399f), listOf(middle.left, middle.bottom, middle.right, middle.top))
        assertEquals(listOf(1f, 0f, 0f, 1f), middle.fill, "the box's own colour")
        quads.filterNot { it.flat }.forEach { assertEquals(1f, it.softEdge, "an edge quad keeps the distance field") }
    }

    @Test
    fun `the quads of a split box cover what the one quad did exactly once`() {
        val box = Box(10f, 20f, 300f, 200f, topLeft = 24f, topRight = 4f, bottomRight = 60f, bottomLeft = 0f, borderWidth = 3f, border = Colour.Blue)
        val quads = draw(box).quads()

        assertEquals(5, quads.size)
        // The one quad it used to be: grown by the soft edge on every side.
        assertEquals((300f + 2f) * (200f + 2f), quads.sumOf { it.area.toDouble() }.toFloat(), 0.01f)
        assertEquals(9f, quads.minOf { it.left })
        assertEquals(19f, quads.minOf { it.bottom })
        assertEquals(311f, quads.maxOf { it.right })
        assertEquals(221f, quads.maxOf { it.top })
        quads.forEachIndexed { index, quad ->
            quads.drop(index + 1).forEach { other -> assertTrue(!quad.overlaps(other), "no pixel is drawn twice") }
        }
    }

    @Test
    fun `every pixel of the inside quad is one the shape path paints flat`() {
        val radii = listOf(0f, 1f, 3f, 7.5f, 12f, 40f, 500f)
        val borders = listOf(0f, 2f, -4f)
        val softEdges = listOf(1f, 0.5f, 1f / 3f)
        var split = 0
        for (corner in radii) for (other in radii) for (border in borders) for (aa in softEdges) {
            val box = Box(
                5f, 7f, 400f, 260f,
                topLeft = corner, topRight = other, bottomRight = corner / 2f, bottomLeft = other * 2f,
                border = Colour.Blue, borderWidth = border, aa = aa,
            )
            device.draws.clear()
            val middle = draw(box).quads().singleOrNull { it.flat } ?: continue
            split++
            // Round the inside quad's edge, densely: every point there is past the soft edge and the border.
            val steps = 64
            for (step in 0..steps) {
                val x = middle.left + (middle.right - middle.left) * step / steps
                val y = middle.bottom + (middle.top - middle.bottom) * step / steps
                for ((px, py) in listOf(x to middle.bottom, x to middle.top, middle.left to y, middle.right to y)) {
                    val distance = box.distance(px, py)
                    assertTrue(
                        distance <= -box.flatFrom + 0.001f,
                        "corners $corner/$other, border $border, aa $aa: ($px, $py) is $distance from the edge",
                    )
                }
            }
            // And it is no smaller than the box less its largest radius, border and soft edge.
            val most = box.flatFrom + listOf(corner, other, corner / 2f, other * 2f).max().coerceAtMost(130f)
            assertTrue(middle.left <= box.left + most + 0.001f && middle.right >= box.left + box.width - most - 0.001f)
            assertTrue(middle.bottom <= box.bottom + most + 0.001f && middle.top >= box.bottom + box.height - most - 0.001f)
        }
        assertTrue(split > 100, "most of these boxes are split, so the checks above ran: $split")
    }

    @Test
    fun `a box smaller than its corners and antialias draws as one quad as today`() {
        val quads = draw(Box(0f, 0f, 20f, 20f, topLeft = 10f, topRight = 10f, bottomRight = 10f, bottomLeft = 10f)).quads()

        assertEquals(1, quads.size)
        assertEquals(1f, quads.single().softEdge)
    }

    @Test
    fun `a box whose middle is too small to pay for the extra quads stays one quad`() {
        // A 58 by 58 pixel middle is fewer pixels than four more quads cost.
        assertEquals(1, draw(Box(0f, 0f, 60f, 60f)).quads().size)
        device.draws.clear()
        // The same box on a screen with twice the pixels each way has four times the middle.
        assertEquals(5, draw(Box(0f, 0f, 60f, 60f, aa = 0.5f)).quads().size)
    }

    @Test
    fun `an outline's empty middle is not drawn at all`() {
        val outline = Box(0f, 0f, 400f, 300f, fill = Colour.Transparent, border = Colour.Blue, borderWidth = 2f, topLeft = 8f)
        val quads = draw(outline).quads()

        assertEquals(4, quads.size)
        assertTrue(quads.none { it.flat }, "only the edge, through the distance field")
        val hole = 402f * 302f - quads.sumOf { it.area.toDouble() }.toFloat()
        assertTrue(hole > 390f * 290f, "the middle is left out: $hole")
    }

    @Test
    fun `under a premultiplied blend a box keeps its one quad`() {
        // There a colour with no opacity is not nothing, so the box is drawn exactly as it always was.
        val outline = Box(0f, 0f, 400f, 300f, fill = Colour(alpha = 0, red = 255, green = 0, blue = 0), border = Colour.Blue, borderWidth = 2f)
        assertEquals(1, draw(outline, premultiplied = true).quads().size)
        device.draws.clear()
        assertEquals(1, draw(Box(0f, 0f, 400f, 300f), premultiplied = true).quads().size)
    }

    @Test
    fun `a box casting a shadow or shading inside keeps its one quad`() {
        assertEquals(1, draw(Box(0f, 0f, 400f, 300f, shadow = Colour.Black, shadowSpread = 8f)).quads().size)
        device.draws.clear()
        assertEquals(1, draw(Box(0f, 0f, 400f, 300f, shadow = Colour.Black, shadowSpread = -8f)).quads().size)
        device.draws.clear()
        // A spread with nothing to cast changes no pixel, so that box is plain.
        assertEquals(5, draw(Box(0f, 0f, 400f, 300f, shadow = Colour.Transparent, shadowSpread = 8f)).quads().size)
    }

    @Test
    fun `every quad of a split box goes through the box's own program in one draw call`() {
        val common = draw(Box(0f, 0f, 400f, 300f))
        assertEquals(listOf(ShapeProgram.Common to 5), common.map { it.program to it.quads })
        device.draws.clear()
        // A shade inside with nothing to show is plain, but the shade path is the full program's.
        val full = draw(Box(0f, 0f, 400f, 300f, shadow = Colour.Transparent, shadowSpread = -8f))
        assertEquals(listOf(ShapeProgram.Full to 5), full.map { it.program to it.quads })
    }

    @Test
    fun `a split box that does not fit in what is left of the batch is drawn whole`() {
        val draws = draw(Box(0f, 0f, 400f, 300f), Box(0f, 0f, 400f, 300f), maxQuads = 3)

        assertEquals(10, draws.sumOf { it.quads })
        assertEquals(2, draws.quads().count { it.flat })
    }
}
