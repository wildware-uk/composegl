package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Dashed, dotted and one-sided borders, checked as the shapes they come out as.
 *
 * All of it is walked onto calls every backend already has — [UiCanvas.rect] for straight edges,
 * [UiCanvas.fan] round a curve and where a dash is trimmed to one — so a recording of those calls
 * is exactly what a renderer would have been handed.
 */
class BorderStyleTest {

    private val white = Colour.White
    private val red = Colour.rgb(0xFF0000)

    private fun near(expected: Float, actual: Float, because: String) =
        assertTrue(abs(expected - actual) < 0.01f, "$because: expected $expected, got $actual")

    @Test
    fun `a solid border is still the backend's own single border call`() {
        val canvas = RecordingCanvas()

        canvas.border(Rect.of(0f, 0f, 50f, 50f), white, 2f, 6f, BorderStyle.Solid)

        val border = canvas.only<DrawCall.Border>().single()
        assertEquals(6f, border.corner)
        assertEquals(1, canvas.calls.size, "and nothing else, so a plain border costs what it did")
    }

    @Test
    fun `a dashed square box is four edges that each start and end on a dash`() {
        val canvas = RecordingCanvas()
        val box = Rect.of(10f, 20f, 100f, 60f)

        canvas.border(box, white, 2f, 0f, BorderStyle.Dashed(on = 6f, off = 4f))

        val dashes = canvas.only<DrawCall.Rectangle>().map { it.rect }
        assertTrue(canvas.only<DrawCall.Border>().isEmpty(), "a dashed border is not a solid one")
        val top = dashes.filter { it.top == box.top }.sortedBy { it.left }
        near(box.left, top.first().left, "the top edge starts on a dash")
        near(box.right, top.last().right, "and ends on one, however the lengths had to stretch")
        assertEquals(10, top.size, "100 across at 6 on and 4 off is ten dashes")
        top.zipWithNext().forEach { (a, b) -> assertTrue(b.left > a.right, "with a gap between each: $a then $b") }
        assertTrue(dashes.any { it.bottom == box.bottom }, "the bottom edge is drawn")
        assertTrue(dashes.any { it.left == box.left && it.top > box.top + 1f }, "the left edge is drawn")
        assertTrue(dashes.any { it.right == box.right && it.top > box.top + 1f }, "the right edge is drawn")
        dashes.forEach {
            assertTrue(it.left >= box.left && it.top >= box.top && it.right <= box.right && it.bottom <= box.bottom, "every dash is inside the box: $it")
        }
    }

    @Test
    fun `dash lengths stretch so a whole number fits`() {
        val (on, off) = fitOpen(100f, 6f, 4f)
        near(100f, 10 * on + 9 * off, "ten dashes and nine gaps fill the edge")
        near(on / off, 1.5f, "in the proportions asked for")

        val (whole, none) = fitOpen(5f, 6f, 4f)
        assertEquals(5f to 0f, whole to none, "an edge too short for a gap is one dash")
    }

    @Test
    fun `dots are square and as far apart as the line is thick`() {
        val canvas = RecordingCanvas()

        canvas.borders(Rect.of(0f, 0f, 87f, 30f), null, BorderSide(3f, white, BorderStyle.Dotted), null, null)

        val dots = canvas.only<DrawCall.Rectangle>().map { it.rect }.sortedBy { it.left }
        assertEquals(15, dots.size, "87 across is fifteen dots of 3 and fourteen gaps of 3")
        dots.forEach {
            near(3f, it.width, "a dot is as long as the line is thick")
            near(3f, it.height, "and as tall")
        }
        near(3f, dots[1].left - dots[0].right, "and one dot apart")
    }

    /** The centre-line points each quad runs from and to, in the order they were drawn. */
    private fun DrawCall.Fan.from() = Offset((points[0].x + points[3].x) / 2f, (points[0].y + points[3].y) / 2f)
    private fun DrawCall.Fan.to() = Offset((points[1].x + points[2].x) / 2f, (points[1].y + points[2].y) / 2f)

    /** Quads that carry straight on from the one before are the same dash. */
    private fun dashesIn(quads: List<DrawCall.Fan>): Int =
        if (quads.isEmpty()) 0
        else 1 + quads.zipWithNext().count { (a, b) -> abs(a.to().x - b.from().x) + abs(a.to().y - b.from().y) > 0.01f }

    @Test
    fun `a dashed rounded border is one ring that follows the curve and stays inside`() {
        val canvas = RecordingCanvas()
        val box = Rect.of(0f, 0f, 120f, 80f)

        canvas.border(box, white, 2f, 16f, BorderStyle.Dashed(on = 8f, off = 6f))

        assertTrue(canvas.only<DrawCall.Rectangle>().isEmpty(), "a rounded ring is not four straight edges")
        val quads = canvas.only<DrawCall.Fan>()
        val centres = quads.flatMap { listOf(it.from(), it.to()) }
        centres.forEach {
            assertTrue(it.x in 0.99f..119.01f && it.y in 0.99f..79.01f, "the line runs half a width inside the box: $it")
        }
        // At 45 degrees round the top-left curve the centre line is 15 from the curve's centre at
        // (16, 16), about (5.4, 5.4). A square ring would run through (1, 1) instead.
        assertTrue(centres.none { it.x < 4f && it.y < 4f }, "nothing is drawn into the square corner")
        assertTrue(
            quads.any { q -> q.from().x < 16f && q.from().y < 16f && q.from().x > 4f && q.from().y > 4f },
            "dashes are drawn round the curve itself",
        )
        assertTrue(centres.any { it.x > 119f - 0.02f } && centres.any { it.y > 79f - 0.02f }, "all the way round")
    }

    @Test
    fun `a rounded dashed ring is broken into the fitted number of dashes`() {
        // A box 64 across with a corner of 32 is a circle, radius 31 along its centre line, which
        // is about 194.8 round. At a period of 19.5 that is ten dashes, stretched a hair.
        val canvas = RecordingCanvas()

        canvas.border(Rect.of(0f, 0f, 64f, 64f), white, 2f, 32f, BorderStyle.Dashed(on = 10f, off = 9.5f))

        assertEquals(10, dashesIn(canvas.only<DrawCall.Fan>()))
    }

    @Test
    fun `a bottom-only border paints only a bottom strip`() {
        val canvas = RecordingCanvas()

        canvas.borders(Rect.of(10f, 10f, 80f, 40f), null, null, null, BorderSide(2f, red))

        assertEquals(Rect.of(10f, 48f, 80f, 2f), canvas.only<DrawCall.Rectangle>().single().rect)
    }

    @Test
    fun `a dashed ring rounds only the corners it is given`() {
        val canvas = RecordingCanvas()

        // A tab: round along the top, square along the bottom.
        canvas.border(Rect.of(0f, 0f, 120f, 80f), white, 2f, Corners.top(16f), BorderStyle.Dashed(on = 8f, off = 6f))

        assertTrue(canvas.only<DrawCall.Border>().isEmpty(), "a dashed border is not a solid one")
        val centres = canvas.only<DrawCall.Fan>().flatMap { listOf(it.from(), it.to()) }
        assertTrue(centres.isNotEmpty(), "the ring is drawn")
        assertTrue(centres.none { it.x < 4f && it.y < 4f }, "nothing in the top-left square corner, it is round")
        assertTrue(centres.none { it.x > 116f && it.y < 4f }, "nor the top-right")
        assertTrue(centres.any { it.x < 2f && it.y > 76f }, "the bottom-left runs right into its square corner")
        assertTrue(centres.any { it.x > 118f && it.y > 76f }, "and so does the bottom-right")
    }

    @Test
    fun `a solid per-corner border is still the backend's own border call`() {
        val canvas = RecordingCanvas()

        canvas.border(Rect.of(0f, 0f, 50f, 50f), white, 2f, Corners.top(6f), BorderStyle.Solid)

        assertEquals(Corners.top(6f), canvas.only<DrawCall.CorneredBorder>().single().corners)
        assertEquals(1, canvas.calls.size)
    }

    @Test
    fun `four sides meet in square corners without painting anything twice`() {
        val canvas = RecordingCanvas()
        val box = Rect.of(0f, 0f, 40f, 30f)

        canvas.borders(box, BorderSide(1f, red), BorderSide(2f, white), BorderSide(3f, red), BorderSide(4f, white))

        val strips = canvas.only<DrawCall.Rectangle>().map { it.rect }
        assertEquals(
            listOf(Rect.of(0f, 0f, 40f, 2f), Rect.of(0f, 26f, 40f, 4f), Rect.of(0f, 2f, 1f, 24f), Rect.of(37f, 2f, 3f, 24f)),
            strips,
        )
        for (a in strips.indices) for (b in strips.indices) {
            if (a != b) assertTrue(strips[a].intersect(strips[b]).isEmpty, "${strips[a]} overlaps ${strips[b]}")
        }
    }

    @Test
    fun `a side thicker than the box fills it and no more`() {
        val canvas = RecordingCanvas()

        canvas.borders(Rect.of(0f, 0f, 20f, 10f), null, BorderSide(50f, red), null, BorderSide(50f, white))

        assertEquals(listOf(Rect.of(0f, 0f, 20f, 10f)), canvas.only<DrawCall.Rectangle>().map { it.rect })
    }

    @Test
    fun `a broken line thicker than its box fills it - square or rounded - and never throws`() {
        val box = Rect.of(0f, 0f, 20f, 20f)
        for (style in listOf(BorderStyle.Dashed(on = 6f, off = 4f), BorderStyle.Dotted)) {
            for (corners in listOf(Corners.None, Corners.single(8f), Corners.top(8f))) {
                val canvas = RecordingCanvas()

                canvas.border(box, white, 30f, corners, style)

                // A piece that reaches a rounded corner is trimmed to it, so comes out as a fan.
                val drawn = canvas.only<DrawCall.Rectangle>().map { it.rect } +
                    canvas.only<DrawCall.Fan>().map { fan -> Rect(fan.points.minOf { it.x }, fan.points.minOf { it.y }, fan.points.maxOf { it.x }, fan.points.maxOf { it.y }) }
                assertTrue(drawn.isNotEmpty(), "$style $corners: something is drawn")
                drawn.forEach {
                    // Trimmed, a piece's top is where the curve crosses its side: a hair below the box's.
                    assertTrue(abs(it.top - box.top) < 0.1f && abs(it.bottom - box.bottom) < 0.1f, "$style $corners: filling the box top to bottom: $it")
                    assertTrue(it.left >= box.left && it.right <= box.right, "$style $corners: and never spilling out: $it")
                }
                canvas.vertices().forEach { assertTrue(insideRounded(box, corners, it), "$style $corners: and round where the box is: $it") }
            }
        }
    }

    @Test
    fun `a dashed line thicker than a flat box is that box dashed along its length`() {
        val canvas = RecordingCanvas()
        val strip = Rect.of(0f, 0f, 100f, 3f)

        canvas.border(strip, white, 4f, 0f, BorderStyle.Dashed(on = 6f, off = 4f))

        val dashes = canvas.only<DrawCall.Rectangle>().map { it.rect }.sortedBy { it.left }
        assertEquals(10, dashes.size, "100 along at 6 on and 4 off is ten dashes, as a thinner line would be")
        dashes.forEach { assertEquals(3f, it.height, "each the strip's whole height: $it") }
        near(strip.left, dashes.first().left, "starting on a dash")
        near(strip.right, dashes.last().right, "and ending on one")
    }

    @Test
    fun `a broken line thicker than a tall box is broken down its length`() {
        val dotted = RecordingCanvas()

        dotted.border(Rect.of(0f, 0f, 20f, 220f), white, 30f, 0f, BorderStyle.Dotted)

        val dots = dotted.only<DrawCall.Rectangle>().map { it.rect }
        assertEquals(6, dots.size, "220 down is six dots of 20 and five gaps of 20: $dots")
        dots.forEach {
            near(20f, it.width, "square dots as wide as the box: $it")
            near(20f, it.height, "and as tall: $it")
        }

        val dashed = RecordingCanvas()
        val strip = Rect.of(0f, 0f, 3f, 100f)

        dashed.border(strip, white, 4f, 0f, BorderStyle.Dashed(on = 6f, off = 4f))

        val dashes = dashed.only<DrawCall.Rectangle>().map { it.rect }.sortedBy { it.top }
        assertEquals(10, dashes.size, "100 down at 6 on and 4 off is ten dashes: $dashes")
        dashes.forEach {
            assertTrue(it.left == strip.left && it.right == strip.right, "each the strip's whole width: $it")
            near(dashes.first().height, it.height, "and all as long as each other: $it")
        }
        near(strip.top, dashes.first().top, "starting on a dash")
        near(strip.bottom, dashes.last().bottom, "and ending on one")
    }

    @Test
    fun `a rounded box a broken line swallows keeps its round corners on a canvas that cannot round a clip`() {
        val box = Rect.of(10f, 10f, 60f, 60f)
        // The recording canvas rounds no clips, as the GL one cannot while a box fades.
        val canvas = RecordingCanvas()

        canvas.border(box, white, 80f, Corners.top(20f), BorderStyle.Dashed(on = 6f, off = 4f))

        assertTrue(canvas.only<DrawCall.Rectangle>().size + canvas.only<DrawCall.Fan>().size > 1, "broken into dashes")
        canvas.vertices().forEach { assertTrue(insideRounded(box, Corners.top(20f), it), "nothing drawn past the round top corners: $it") }
        assertTrue(canvas.covers(Offset(12f, 30f)), "the corner dash still runs down to where the curve starts")
        assertTrue(canvas.covers(Offset(11f, 69f)), "and the square bottom corners are filled right in")
        canvas.assertBalanced()
    }

    /** Whether [point] is inside the box [box] with [radii], the way a solid border's outside edge runs. */
    private fun insideRounded(box: Rect, radii: Corners, point: Offset, slack: Float = 0.01f): Boolean {
        if (point.x < box.left - slack || point.x > box.right + slack || point.y < box.top - slack || point.y > box.bottom + slack) return false
        fun outside(radius: Float, cx: Float, cy: Float, beyondX: Boolean, beyondY: Boolean) =
            radius > 0f && beyondX && beyondY && Offset(cx, cy).distanceTo(point) > radius + slack
        return !(
            outside(radii.topLeft, box.left + radii.topLeft, box.top + radii.topLeft, point.x < box.left + radii.topLeft, point.y < box.top + radii.topLeft) ||
                outside(radii.topRight, box.right - radii.topRight, box.top + radii.topRight, point.x > box.right - radii.topRight, point.y < box.top + radii.topRight) ||
                outside(radii.bottomRight, box.right - radii.bottomRight, box.bottom - radii.bottomRight, point.x > box.right - radii.bottomRight, point.y > box.bottom - radii.bottomRight) ||
                outside(radii.bottomLeft, box.left + radii.bottomLeft, box.bottom - radii.bottomLeft, point.x < box.left + radii.bottomLeft, point.y > box.bottom - radii.bottomLeft)
            )
    }

    /** Every corner of every piece drawn: a piece is convex, so these inside means all of it is. */
    private fun RecordingCanvas.vertices(): List<Offset> =
        only<DrawCall.Rectangle>().flatMap { listOf(Offset(it.rect.left, it.rect.top), Offset(it.rect.right, it.rect.top), Offset(it.rect.right, it.rect.bottom), Offset(it.rect.left, it.rect.bottom)) } +
            only<DrawCall.Fan>().flatMap { it.points }

    /** Whether any piece drawn covers [point]: a flat rectangle, or a convex fan either way round. */
    private fun RecordingCanvas.covers(point: Offset): Boolean =
        only<DrawCall.Rectangle>().any { point.x >= it.rect.left && point.x <= it.rect.right && point.y >= it.rect.top && point.y <= it.rect.bottom } ||
            only<DrawCall.Fan>().any { fan ->
                val turns = fan.points.indices.map { i ->
                    val a = fan.points[i]
                    val b = fan.points[(i + 1) % fan.points.size]
                    (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
                }
                turns.all { it >= -0.001f } || turns.all { it <= 0.001f }
            }

    @Test
    fun `a broken line at least twice as thick as its corner keeps the corners round`() {
        val box = Rect.of(10f, 10f, 60f, 60f)
        for (style in listOf(BorderStyle.Dashed(on = 6f, off = 4f), BorderStyle.Dotted)) {
            for (width in listOf(40f, 45f, 50f, 59f)) {
                val canvas = RecordingCanvas()

                canvas.border(box, white, width, 20f, style)

                val corners = Corners.all(20f)
                canvas.vertices().forEach {
                    assertTrue(insideRounded(box, corners, it), "$style $width: nothing drawn past the round corners, as a solid border's: $it")
                }
                assertTrue(canvas.covers(Offset(box.left + 2f, box.top + 20f)), "$style $width: the corner's dash still runs down to where the curve starts")
            }
        }
    }

    @Test
    fun `a broken line twice as thick as only some of its corners keeps those round and the square ones square`() {
        val box = Rect.of(0f, 0f, 100f, 100f)
        val corners = Corners(topLeft = 0f, topRight = 10f, bottomRight = 30f, bottomLeft = 30f)
        val canvas = RecordingCanvas()

        // One dash all the way round but for a sliver halfway, round the bottom-left curve, so the
        // top two corners are drawn whole.
        canvas.border(box, white, 30f, corners, BorderStyle.Dashed(on = 1000f, off = 1f))

        canvas.vertices().forEach { assertTrue(insideRounded(box, corners, it), "nothing drawn past the round corners: $it") }
        assertTrue(canvas.covers(Offset(98f, 12f)), "the top-right, curved tighter than half the line, is filled out to its curve")
        assertTrue(canvas.covers(Offset(93f, 7f)), "all the way round it")
        assertTrue(canvas.covers(Offset(1f, 1f)), "the square top-left is filled right into its corner")
    }

    @Test
    fun `a broken line round a thick tight curve leaves no slivers in it`() {
        // Corners of 20 under a line 30 thick: the line's centre turns on a curve of 5, so the
        // pieces round it each turn a long way, and each has to meet the next all across the line.
        val box = Rect.of(0f, 0f, 60f, 60f)
        val canvas = RecordingCanvas()

        canvas.border(box, white, 30f, 20f, BorderStyle.Dashed(on = 1000f, off = 1f))

        // The one gap is opposite where the ring starts, about the bottom-left; the other three
        // corners are under the dash, so every point just inside their curves is covered.
        val centres = listOf(Offset(20f, 20f) to 180f, Offset(40f, 20f) to 270f, Offset(40f, 40f) to 0f)
        for ((centre, from) in centres) {
            for (step in 1 until 18) {
                val angle = (from + step * 5f) * kotlin.math.PI.toFloat() / 180f
                val point = Offset(centre.x + kotlin.math.cos(angle) * 19f, centre.y + kotlin.math.sin(angle) * 19f)
                assertTrue(canvas.covers(point), "the curve about $centre is covered at ${from + step * 5f} degrees: $point")
            }
        }
        canvas.vertices().forEach { assertTrue(insideRounded(box, Corners.all(20f), it), "and nothing is drawn past it: $it") }
    }

    @Test
    fun `a broken line of any width up to its box stays inside the round corners`() {
        val box = Rect.of(0f, 0f, 60f, 60f)
        for (corners in listOf(Corners.all(20f), Corners.top(20f), Corners(topLeft = 25f, topRight = 5f, bottomRight = 12f))) {
            for (style in listOf(BorderStyle.Dashed(on = 6f, off = 4f), BorderStyle.Dotted)) {
                for (width in 1..59) {
                    val canvas = RecordingCanvas()

                    canvas.border(box, white, width.toFloat(), corners, style)

                    canvas.vertices().forEach {
                        assertTrue(insideRounded(box, corners, it, slack = 0.05f), "$corners $style $width: inside the round corners: $it")
                    }
                }
            }
        }
    }

    @Test
    fun `dots on a line thicker than its box are as big as the box is thin`() {
        val canvas = RecordingCanvas()

        canvas.border(Rect.of(0f, 0f, 220f, 20f), white, 30f, 0f, BorderStyle.Dotted)

        val dots = canvas.only<DrawCall.Rectangle>().map { it.rect }
        assertEquals(6, dots.size, "220 along is six dots of 20 and five gaps of 20")
        dots.forEach {
            near(20f, it.width, "square dots, as long as the line drawn is thick")
            near(20f, it.height, "which is the box's height, not the 30 asked for")
        }
    }

    @Test
    fun `nothing is drawn for no width or an empty box`() {
        val canvas = RecordingCanvas()

        canvas.border(Rect.of(0f, 0f, 20f, 20f), white, 0f, 0f, BorderStyle.Dotted)
        canvas.border(Rect.of(0f, 0f, 0f, 20f), white, 2f, 4f, BorderStyle.Dashed(4f, 2f))
        canvas.borders(Rect.of(0f, 0f, 20f, 20f), BorderSide(0f, white), null, null, null)

        assertEquals(emptyList(), canvas.calls)
    }

    @Test
    fun `nonsense lengths are refused where they are written`() {
        assertFailsWith<IllegalArgumentException> { BorderStyle.Dashed(on = 0f, off = 4f) }
        assertFailsWith<IllegalArgumentException> { BorderStyle.Dashed(on = 4f, off = -1f) }
        assertFailsWith<IllegalArgumentException> { BorderSide(-1f, white) }
        assertFailsWith<IllegalArgumentException> { BorderSide(Float.POSITIVE_INFINITY, white) }
        assertFailsWith<IllegalArgumentException> { BorderStyle.Dashed(on = Float.NaN, off = 4f) }
        assertFailsWith<IllegalArgumentException> { BorderStyle.Dashed(on = 4f, off = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `a hairline dotted round a huge box is never more than one dot a unit`() {
        val canvas = RecordingCanvas()

        canvas.borders(Rect.of(0f, 0f, 100f, 1f), null, BorderSide(0.01f, white, BorderStyle.Dotted), null, null)

        assertEquals(100, canvas.only<DrawCall.Rectangle>().size, "a hundred across is at most a hundred dots")

        val ring = RecordingCanvas()
        ring.border(Rect.of(0f, 0f, 1_000_000f, 1_000_000f), white, 0.01f, 0f, BorderStyle.Dotted)
        assertTrue(ring.calls.size <= 4 * 4096, "a million-unit box stays a bounded number of dots, got ${ring.calls.size}")
    }

    @Test
    fun `the dash count stays between one and one a unit`() {
        assertEquals(1, dashCount(Float.NaN, 50f))
        assertEquals(1, dashCount(0.2f, 50f))
        assertEquals(7, dashCount(7.4f, 50f))
        assertEquals(50, dashCount(5000f, 50f))
        assertEquals(4096, dashCount(1e9f, 1e9f))
    }
}
