package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a border's line is drawn along its length: unbroken, in dashes, or in dots.
 *
 * ```kotlin
 * Modifier.border(colour, width = 2f, style = BorderStyle.Dashed(on = 6f, off = 4f))
 * ```
 */
sealed interface BorderStyle {

    /** One unbroken line. What a border is unless it says otherwise. */
    data object Solid : BorderStyle

    /**
     * Dashes [on] long with gaps [off] long between them.
     *
     * The two lengths are what is asked for, not what is guaranteed: they are stretched or squeezed
     * a little so a whole number of dashes fits, which is what keeps an edge from ending in a stub
     * and a ring from having one short dash where it meets itself.
     */
    data class Dashed(val on: Float, val off: Float) : BorderStyle {
        init {
            require(on > 0f && on.isFinite()) { "a dash has to have some length, was $on" }
            require(off >= 0f && off.isFinite()) { "the gap between dashes cannot be negative, was $off" }
        }
    }

    /** Square dots as long as the line is thick, the same distance apart. A "drop here" outline. */
    data object Dotted : BorderStyle
}

/**
 * One edge of a border: how thick, what colour, and whether it is broken up.
 *
 * ```kotlin
 * Modifier.border(bottom = BorderSide(1f, divider))
 * ```
 */
data class BorderSide(
    val width: Float,
    val colour: Colour,
    val style: BorderStyle = BorderStyle.Solid,
) {
    init { require(width >= 0f && width.isFinite()) { "border width cannot be negative, was $width" } }
}

/**
 * An outline drawn inside [rect], [width] thick, in [style].
 *
 * A solid one is the backend's own [UiCanvas.border], so nothing about a plain border changes. A
 * broken one is walked into short quads and fans here, so every backend draws dashes without being
 * asked to know what one is. Square corners are drawn as four edges each ending in a dash, the way
 * a dashed box is expected to look; rounded ones as one ring walked round the curve. A line at
 * least twice as thick as every corner is round is four edges too, with the dashes that reach a
 * corner trimmed to its curve. A broken line as thick as the box is short, or thicker, fills the
 * box, broken along its length. However thick, the outside edge is rounded as a solid border's
 * is, on every canvas.
 */
fun UiCanvas.border(rect: Rect, colour: Colour, width: Float, corner: Float, style: BorderStyle) =
    border(rect, colour, width, Corners.single(corner), style)

/** The same, with a radius for each of the four [corners]: a dashed tab rounds only its top. */
fun UiCanvas.border(rect: Rect, colour: Colour, width: Float, corners: Corners, style: BorderStyle) {
    if (style == BorderStyle.Solid) return boxBorder(rect, colour, width, corners)
    if (rect.isEmpty || width <= 0f) return
    val (on, off) = style.lengths(width)
    if (off <= 0f) return boxBorder(rect, colour, width, corners)
    val shorter = min(rect.width, rect.height)
    if (width >= shorter) return filled(rect, BorderSide(shorter, colour, style), corners)

    val outline = Outline(rect, corners, most = shorter / 2f)
    // The line is centred half a width in, so a corner no rounder than that has no curve left along
    // its centre. With none anywhere, the line is four straight edges.
    if (outline.radii.largest <= width / 2f) {
        val side = BorderSide(width, colour, style)
        return edges(rect, side, side, side, side, outline.takeIf { it.radii.largest > 0f })
    }
    dashRing(outline, width, colour, on, off)
}

/**
 * A broken line at least as thick as [rect] is short: the box itself, broken along its longer side.
 *
 * The line is [side]'s width, the box's shorter side, so dots stay square. There is no ring left
 * to walk, its centre being a point or a line, and four edges would cross each other's dashes.
 * Rounded [corners] stay round as a solid border's do: the pieces that reach one are trimmed to
 * its curve.
 */
private fun UiCanvas.filled(rect: Rect, side: BorderSide, corners: Corners) {
    val outline = Outline(rect, corners, most = side.width / 2f)
    edge(rect, side, horizontal = rect.width >= rect.height, outline.takeIf { it.radii.largest > 0f })
}

/**
 * A border whose four edges are each their own, or absent: a divider under a header is
 * `bottom` alone, a tab's underline is `bottom` in the accent.
 *
 * Every edge is drawn inside [rect]. The top and bottom run its full width and the left and right
 * fill the height between them, so two edges of different colours meet in a square corner rather
 * than a mitre, and nothing is painted twice where they meet.
 */
fun UiCanvas.borders(rect: Rect, left: BorderSide?, top: BorderSide?, right: BorderSide?, bottom: BorderSide?) =
    edges(rect, left, top, right, bottom, outline = null)

/** [borders], with every piece that reaches into one of [outline]'s rounded corners trimmed to its curve. */
private fun UiCanvas.edges(rect: Rect, left: BorderSide?, top: BorderSide?, right: BorderSide?, bottom: BorderSide?, outline: Outline?) {
    if (rect.isEmpty) return
    // A side thicker than the box is as thick as the box: it fills it, and never spills out.
    val topWidth = min(top?.width ?: 0f, rect.height)
    val bottomWidth = min(bottom?.width ?: 0f, rect.height - topWidth)
    if (top != null && topWidth > 0f) {
        edge(Rect.of(rect.left, rect.top, rect.width, topWidth), top, horizontal = true, outline)
    }
    if (bottom != null && bottomWidth > 0f) {
        edge(Rect.of(rect.left, rect.bottom - bottomWidth, rect.width, bottomWidth), bottom, horizontal = true, outline)
    }
    val between = rect.height - topWidth - bottomWidth
    if (between <= 0f) return
    val leftWidth = min(left?.width ?: 0f, rect.width)
    val rightWidth = min(right?.width ?: 0f, rect.width - leftWidth)
    if (left != null && leftWidth > 0f) {
        edge(Rect.of(rect.left, rect.top + topWidth, leftWidth, between), left, horizontal = false, outline)
    }
    if (right != null && rightWidth > 0f) {
        edge(Rect.of(rect.right - rightWidth, rect.top + topWidth, rightWidth, between), right, horizontal = false, outline)
    }
}

/** The dash and gap lengths for a line this thick, before they are fitted to anything. */
private fun BorderStyle.lengths(width: Float): Pair<Float, Float> = when (this) {
    BorderStyle.Solid -> width to 0f
    is BorderStyle.Dashed -> on to off
    BorderStyle.Dotted -> width to width
}

/** One straight edge filling [area], solid or broken along its long axis, each piece trimmed to [outline]. */
private fun UiCanvas.edge(area: Rect, side: BorderSide, horizontal: Boolean, outline: Outline?) {
    val (on, off) = side.style.lengths(side.width)
    if (off <= 0f) return piece(area, side.colour, outline)

    val length = if (horizontal) area.width else area.height
    val thickness = if (horizontal) area.height else area.width
    val (dash, gap) = fitOpen(length, on, off)
    var at = 0f
    while (at < length - 0.001f) {
        val end = min(at + dash, length)
        if (horizontal) piece(Rect.of(area.left + at, area.top, end - at, thickness), side.colour, outline)
        else piece(Rect.of(area.left, area.top + at, thickness, end - at), side.colour, outline)
        at = end + gap
    }
}

/** [area] flat, or where it reaches into a rounded corner of [outline], only the part inside the curve. */
private fun UiCanvas.piece(area: Rect, colour: Colour, outline: Outline?) {
    if (outline == null || !outline.cuts(area)) return rect(area, colour)
    val inside = outline.trim(area)
    if (inside.size >= 6) fan(inside, colour)
}

/**
 * Dash and gap lengths stretched so an edge [length] long starts and ends on a dash.
 *
 * n dashes and n - 1 gaps, with n whatever is nearest to what was asked for. An edge too short for
 * even one gap is one dash, which is to say solid.
 */
internal fun fitOpen(length: Float, on: Float, off: Float): Pair<Float, Float> {
    val count = dashCount((length + off) / (on + off), length)
    if (count == 1) return length to 0f
    val scale = length / (count * on + (count - 1) * off)
    return on * scale to off * scale
}

/**
 * How many dashes go along a line [length] long, when [ideal] is how many the lengths asked for.
 *
 * Never fewer than one, and never more than one a unit: a hairline dotted round a whole screen
 * would otherwise be a million quads a frame, each thinner than a pixel and together a grey line.
 */
internal fun dashCount(ideal: Float, length: Float): Int {
    val most = if (length.isFinite()) max(1f, min(length, MaxDashes.toFloat())).toInt() else 1
    if (ideal.isNaN()) return 1
    return ideal.roundToInt().coerceIn(1, most)
}

/** The most dashes one edge or ring is ever broken into, however long it is. */
private const val MaxDashes = 4096

/**
 * A dashed ring round a rounded rectangle, walked as one path so the pattern carries on through
 * the corners.
 *
 * The path is the line's centre, half a width inside [outline]. Every dash is the stretch between
 * two distances along it, drawn as the four-sided pieces between [Ring] stations, so the pieces
 * round a curve share their edges and leave no slivers between them however thick the line. The
 * period is fitted so a whole number of dashes goes round, and the first dash is centred on where
 * the walk starts — the top edge meeting its right-hand curve — so the seam never shows.
 */
private fun UiCanvas.dashRing(outline: Outline, width: Float, colour: Colour, on: Float, off: Float) {
    val box = outline.box
    val radii = outline.radii
    val ring = Ring(outline, width)
    // Clockwise on screen from the end of the top edge, the same way round as the angles.
    ring.corner(box.right, box.top, radii.topRight, 270f)
    ring.corner(box.right, box.bottom, radii.bottomRight, 0f)
    ring.corner(box.left, box.bottom, radii.bottomLeft, 90f)
    ring.corner(box.left, box.top, radii.topLeft, 180f)
    ring.close()

    val perimeter = ring.length
    if (perimeter <= 0f) return
    val count = dashCount(perimeter / (on + off), perimeter)
    val period = perimeter / count
    val dash = period * on / (on + off)
    for (k in 0 until count) {
        val start = k * period - dash / 2f
        val end = start + dash
        if (start < 0f) {
            ring.draw(this, perimeter + start, perimeter, colour)
            ring.draw(this, 0f, end, colour)
        } else {
            ring.draw(this, start, end, colour)
        }
    }
}

/**
 * The path a [dashRing] walks: stations along the line's centre, each with the point across from
 * it on the outside edge and the one on the inside edge.
 *
 * On a straight edge the two are half a width either side of the centre. Round a curve the outside
 * one is on [Outline]'s own curve, so the outside edge is as round as a solid border's however thick
 * the line, and the inside one is on the curve a line's width in, or at the curve's centre once the
 * line is thicker than the corner is round. A corner no rounder than half the line has no curve
 * left along the centre: the line turns square there, and the outside of the turn, trimmed to the
 * corner's curve, is drawn whole by whichever dash runs through it.
 */
private class Ring(private val outline: Outline, private val width: Float) {
    private val half = width / 2f

    /** Per station: the distance along the centre, then the centre, the outside and the inside point. */
    private val stations = FloatArray(MaxStations * Stride)
    private var size = 0

    private val turnsAt = FloatArray(4)
    private val turnShapes = arrayOfNulls<FloatArray>(4)
    private var turns = 0

    /** How far round the ring is, so far. */
    val length: Float get() = if (size == 0) 0f else stations[(size - 1) * Stride]

    /**
     * The corner at ([x], [y]), rounded by [radius], whose curve starts [from] degrees round: the
     * edge coming into it, the curve, and the edge leaving it.
     */
    fun corner(x: Float, y: Float, radius: Float, from: Float) {
        // Which way is out from the edge coming in, and from the one going out: up, then right, at
        // the top-right. Their sum points out of the corner.
        val incoming = axis(from)
        val outgoing = axis(from + 90f)
        val curve = radius - half
        if (curve > 0f) {
            val centreX = x - (incoming.x + outgoing.x) * radius
            val centreY = y - (incoming.y + outgoing.y) * radius
            edge(centreX + incoming.x * curve, centreY + incoming.y * curve, incoming)
            val inside = max(radius - width, 0f)
            val steps = steps(radius)
            for (i in 0..steps) {
                val angle = (from + 90f * i / steps) * (PI.toFloat() / 180f)
                val ux = cos(angle)
                val uy = sin(angle)
                station(
                    centreX + ux * curve, centreY + uy * curve,
                    centreX + ux * radius, centreY + uy * radius,
                    centreX + ux * inside, centreY + uy * inside,
                )
            }
            edge(centreX + outgoing.x * curve, centreY + outgoing.y * curve, outgoing)
        } else {
            val turnX = x - (incoming.x + outgoing.x) * half
            val turnY = y - (incoming.y + outgoing.y) * half
            edge(turnX, turnY, incoming)
            turnsAt[turns] = length
            turnShapes[turns] = outline.trim(Rect(min(x, turnX), min(y, turnY), max(x, turnX), max(y, turnY)))
            turns++
            edge(turnX, turnY, outgoing)
        }
    }

    /** Back to the first station, closing the ring. */
    fun close() {
        station(stations[1], stations[2], stations[3], stations[4], stations[5], stations[6])
    }

    /** The stretch of the ring between two distances along it, in [colour]. */
    fun draw(canvas: UiCanvas, from: Float, to: Float, colour: Colour) {
        if (to <= from) return
        for (i in 1 until size) {
            val a = stations[(i - 1) * Stride]
            val b = stations[i * Stride]
            if (b <= from || a >= to || b <= a) continue
            val t0 = (max(from, a) - a) / (b - a)
            val t1 = (min(to, b) - a) / (b - a)
            val p = (i - 1) * Stride
            val q = i * Stride
            fun along(offset: Int, t: Float) = stations[p + offset] + (stations[q + offset] - stations[p + offset]) * t
            // Inside edge first, then back along the outside, the way round UiCanvas.line lays a quad.
            canvas.fan(
                floatArrayOf(
                    along(5, t0), along(6, t0),
                    along(5, t1), along(6, t1),
                    along(3, t1), along(4, t1),
                    along(3, t0), along(4, t0),
                ),
                colour,
            )
        }
        for (k in 0 until turns) {
            val shape = turnShapes[k] ?: continue
            if (turnsAt[k] >= from && turnsAt[k] < to && shape.size >= 6) canvas.fan(shape, colour)
        }
    }

    /** A station on a straight edge whose outward side is [outward]: half a width out, half in. */
    private fun edge(x: Float, y: Float, outward: Offset) =
        station(x, y, x + outward.x * half, y + outward.y * half, x - outward.x * half, y - outward.y * half)

    private fun station(x: Float, y: Float, outsideX: Float, outsideY: Float, insideX: Float, insideY: Float) {
        val at = size * Stride
        stations[at] = if (size == 0) 0f else {
            val dx = x - stations[at - Stride + 1]
            val dy = y - stations[at - Stride + 2]
            stations[at - Stride] + sqrt(dx * dx + dy * dy)
        }
        stations[at + 1] = x
        stations[at + 2] = y
        stations[at + 3] = outsideX
        stations[at + 4] = outsideY
        stations[at + 5] = insideX
        stations[at + 6] = insideY
        size++
    }

    private companion object {
        const val Stride = 7

        /** A curve's steps and one more, an edge station either side, four corners, and the close. */
        const val MaxStations = 4 * (MaxSteps + 3) + 1
    }
}

/**
 * The outside edge a border is drawn inside: [box], each corner rounded as a solid border's is,
 * never more than [most].
 *
 * What the pieces of a broken line too thick to bend round a corner are trimmed to, so its outside
 * edge follows the box's curve rather than standing square past it.
 */
private class Outline(val box: Rect, corners: Corners, most: Float) {

    val radii = Corners(min(corners.topLeft, most), min(corners.topRight, most), min(corners.bottomRight, most), min(corners.bottomLeft, most))

    /** Whether [area] reaches into the square a rounded corner takes its curve out of. */
    fun cuts(area: Rect): Boolean =
        reaches(area, radii.topLeft, box.left, box.top, box.left + radii.topLeft, box.top + radii.topLeft) ||
            reaches(area, radii.topRight, box.right - radii.topRight, box.top, box.right, box.top + radii.topRight) ||
            reaches(area, radii.bottomRight, box.right - radii.bottomRight, box.bottom - radii.bottomRight, box.right, box.bottom) ||
            reaches(area, radii.bottomLeft, box.left, box.bottom - radii.bottomLeft, box.left + radii.bottomLeft, box.bottom)

    /** The part of [area] inside the outline: a convex polygon, as x, y pairs. */
    fun trim(area: Rect): FloatArray {
        var points = clip(polygon, area.left, vertical = true, keepAbove = true)
        points = clip(points, area.right, vertical = true, keepAbove = false)
        points = clip(points, area.top, vertical = false, keepAbove = true)
        return clip(points, area.bottom, vertical = false, keepAbove = false)
    }

    /** The outline itself, each curve walked in as many steps as a ring's. Made the first time it is needed. */
    private val polygon: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        fun count(radius: Float) = if (radius <= 0f) 1 else steps(radius) + 1
        val points = FloatArray(2 * (count(radii.topLeft) + count(radii.topRight) + count(radii.bottomRight) + count(radii.bottomLeft)))
        var size = 0
        // Clockwise on screen from the top of the left edge, each corner about its curve's centre.
        fun corner(x: Float, y: Float, radius: Float, from: Float) {
            if (radius <= 0f) {
                points[size++] = x
                points[size++] = y
                return
            }
            val steps = steps(radius)
            for (i in 0..steps) {
                val angle = (from + 90f * i / steps) * (PI.toFloat() / 180f)
                points[size++] = x + cos(angle) * radius
                points[size++] = y + sin(angle) * radius
            }
        }
        corner(box.left + radii.topLeft, box.top + radii.topLeft, radii.topLeft, 180f)
        corner(box.right - radii.topRight, box.top + radii.topRight, radii.topRight, 270f)
        corner(box.right - radii.bottomRight, box.bottom - radii.bottomRight, radii.bottomRight, 0f)
        corner(box.left + radii.bottomLeft, box.bottom - radii.bottomLeft, radii.bottomLeft, 90f)
        points
    }

    private fun reaches(area: Rect, radius: Float, left: Float, top: Float, right: Float, bottom: Float) =
        radius > 0f && area.left < right && area.right > left && area.top < bottom && area.bottom > top
}

/**
 * The part of the convex polygon [points] on one side of a line: x = [at] when [vertical], y = [at]
 * when not, keeping what is above it when [keepAbove] and below it when not. Still convex.
 */
private fun clip(points: FloatArray, at: Float, vertical: Boolean, keepAbove: Boolean): FloatArray {
    val count = points.size / 2
    if (count == 0) return points
    val axis = if (vertical) 0 else 1
    fun side(i: Int) = if (keepAbove) points[2 * i + axis] - at else at - points[2 * i + axis]
    val kept = FloatArray((count + 1) * 2)
    var size = 0
    for (i in 0 until count) {
        val j = (i + 1) % count
        val a = side(i)
        val b = side(j)
        if (a >= 0f) {
            kept[size++] = points[2 * i]
            kept[size++] = points[2 * i + 1]
        }
        if ((a >= 0f) != (b >= 0f)) {
            val t = a / (a - b)
            kept[size++] = points[2 * i] + (points[2 * j] - points[2 * i]) * t
            kept[size++] = points[2 * i + 1] + (points[2 * j + 1] - points[2 * i + 1]) * t
        }
    }
    return kept.copyOf(size)
}

/** How many straight steps a quarter curve of [radius] is walked in: about one per two units, like a circle. */
private fun steps(radius: Float) = (radius * PI.toFloat() / 4f).toInt().coerceIn(2, MaxSteps)

/** The most steps one quarter curve is walked in, so a huge corner is not thousands of pieces. */
private const val MaxSteps = 45

/** The unit vector [degrees] round, clockwise on screen from the right, for a whole number of quarter turns. */
private fun axis(degrees: Float): Offset = when (((degrees / 90f).roundToInt() % 4 + 4) % 4) {
    0 -> Offset(1f, 0f)
    1 -> Offset(0f, 1f)
    2 -> Offset(-1f, 0f)
    else -> Offset(0f, -1f)
}
