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
 * broken one is walked into short quads on [UiCanvas.line] here, so every backend draws dashes
 * without being asked to know what one is. Square corners are drawn as four edges each ending in a
 * dash, the way a dashed box is expected to look; rounded ones as one ring walked round the curve.
 */
fun UiCanvas.border(rect: Rect, colour: Colour, width: Float, corner: Float, style: BorderStyle) =
    border(rect, colour, width, Corners.single(corner), style)

/** The same, with a radius for each of the four [corners]: a dashed tab rounds only its top. */
fun UiCanvas.border(rect: Rect, colour: Colour, width: Float, corners: Corners, style: BorderStyle) {
    if (style == BorderStyle.Solid) return boxBorder(rect, colour, width, corners)
    if (rect.isEmpty || width <= 0f) return
    val (on, off) = style.lengths(width)
    if (off <= 0f) return boxBorder(rect, colour, width, corners)

    // The line is centred half a width in, so its outside edge is the rectangle's.
    val half = width / 2f
    val most = min(rect.width, rect.height) / 2f - half
    fun inner(radius: Float) = min(max(radius - half, 0f), most)
    val radii = Corners(inner(corners.topLeft), inner(corners.topRight), inner(corners.bottomRight), inner(corners.bottomLeft))
    if (radii.largest <= 0f) {
        val side = BorderSide(width, colour, style)
        return borders(rect, side, side, side, side)
    }
    dashRing(rect, half, radii, width, colour, on, off)
}

/**
 * A border whose four edges are each their own, or absent: a divider under a header is
 * `bottom` alone, a tab's underline is `bottom` in the accent.
 *
 * Every edge is drawn inside [rect]. The top and bottom run its full width and the left and right
 * fill the height between them, so two edges of different colours meet in a square corner rather
 * than a mitre, and nothing is painted twice where they meet.
 */
fun UiCanvas.borders(rect: Rect, left: BorderSide?, top: BorderSide?, right: BorderSide?, bottom: BorderSide?) {
    if (rect.isEmpty) return
    // A side thicker than the box is as thick as the box: it fills it, and never spills out.
    val topWidth = min(top?.width ?: 0f, rect.height)
    val bottomWidth = min(bottom?.width ?: 0f, rect.height - topWidth)
    if (top != null && topWidth > 0f) {
        edge(Rect.of(rect.left, rect.top, rect.width, topWidth), top, horizontal = true)
    }
    if (bottom != null && bottomWidth > 0f) {
        edge(Rect.of(rect.left, rect.bottom - bottomWidth, rect.width, bottomWidth), bottom, horizontal = true)
    }
    val between = rect.height - topWidth - bottomWidth
    if (between <= 0f) return
    val leftWidth = min(left?.width ?: 0f, rect.width)
    val rightWidth = min(right?.width ?: 0f, rect.width - leftWidth)
    if (left != null && leftWidth > 0f) {
        edge(Rect.of(rect.left, rect.top + topWidth, leftWidth, between), left, horizontal = false)
    }
    if (right != null && rightWidth > 0f) {
        edge(Rect.of(rect.right - rightWidth, rect.top + topWidth, rightWidth, between), right, horizontal = false)
    }
}

/** The dash and gap lengths for a line this thick, before they are fitted to anything. */
private fun BorderStyle.lengths(width: Float): Pair<Float, Float> = when (this) {
    BorderStyle.Solid -> width to 0f
    is BorderStyle.Dashed -> on to off
    BorderStyle.Dotted -> width to width
}

/** One straight edge filling [area], solid or broken along its long axis. */
private fun UiCanvas.edge(area: Rect, side: BorderSide, horizontal: Boolean) {
    val (on, off) = side.style.lengths(side.width)
    if (off <= 0f) return rect(area, side.colour)

    val length = if (horizontal) area.width else area.height
    val thickness = if (horizontal) area.height else area.width
    val (dash, gap) = fitOpen(length, on, off)
    var at = 0f
    while (at < length - 0.001f) {
        val end = min(at + dash, length)
        if (horizontal) rect(Rect.of(area.left + at, area.top, end - at, thickness), side.colour)
        else rect(Rect.of(area.left, area.top + at, thickness, end - at), side.colour)
        at = end + gap
    }
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
 * The path is the line's centre: [rect] inset by [half], with corners of [radii]. It is turned into
 * a closed list of points, and every dash is the stretch of that list between two distances along
 * it. The period is fitted so a whole number of dashes goes round, and the first dash is centred on
 * where the walk starts — the top edge meeting its right-hand curve — so the seam never shows.
 */
private fun UiCanvas.dashRing(rect: Rect, half: Float, radii: Corners, width: Float, colour: Colour, on: Float, off: Float) {
    val left = rect.left + half
    val top = rect.top + half
    val right = rect.right - half
    val bottom = rect.bottom - half

    val path = ArrayList<Offset>()
    // Each corner its own radius; a square one is a single point the walk turns at.
    fun corner(cx: Float, cy: Float, radius: Float, fromDegrees: Float) {
        if (radius <= 0f) {
            path += Offset(cx, cy)
            return
        }
        // About one piece per two units of curve, like a circle, so a tight corner is not a polygon.
        val steps = (radius * PI.toFloat() / 4f).toInt().coerceIn(2, 45)
        for (i in 0..steps) {
            val angle = (fromDegrees + 90f * i / steps) * (PI.toFloat() / 180f)
            path += Offset(cx + cos(angle) * radius, cy + sin(angle) * radius)
        }
    }
    // Clockwise on screen from the end of the top-left curve, the same way round as the angles.
    corner(right - radii.topRight, top + radii.topRight, radii.topRight, 270f)
    corner(right - radii.bottomRight, bottom - radii.bottomRight, radii.bottomRight, 0f)
    corner(left + radii.bottomLeft, bottom - radii.bottomLeft, radii.bottomLeft, 90f)
    corner(left + radii.topLeft, top + radii.topLeft, radii.topLeft, 180f)
    path += path.first()

    val distances = FloatArray(path.size)
    for (i in 1 until path.size) distances[i] = distances[i - 1] + path[i - 1].distanceTo(path[i])
    val perimeter = distances.last()
    if (perimeter <= 0f) return

    val count = dashCount(perimeter / (on + off), perimeter)
    val period = perimeter / count
    val dash = period * on / (on + off)
    for (k in 0 until count) {
        val start = k * period - dash / 2f
        val end = start + dash
        if (start < 0f) {
            stroke(path, distances, perimeter + start, perimeter, width, colour)
            stroke(path, distances, 0f, end, width, colour)
        } else {
            stroke(path, distances, start, end, width, colour)
        }
    }
}

/** The part of [path] between two distances along it, as one quad per straight piece. */
private fun UiCanvas.stroke(path: List<Offset>, distances: FloatArray, from: Float, to: Float, width: Float, colour: Colour) {
    if (to <= from) return
    for (i in 1 until path.size) {
        val a = distances[i - 1]
        val b = distances[i]
        if (b <= from || a >= to || b <= a) continue
        val p = path[i - 1]
        val q = path[i]
        val t0 = (max(from, a) - a) / (b - a)
        val t1 = (min(to, b) - a) / (b - a)
        line(
            Offset(p.x + (q.x - p.x) * t0, p.y + (q.y - p.y) * t0),
            Offset(p.x + (q.x - p.x) * t1, p.y + (q.y - p.y) * t1),
            width,
            colour,
        )
    }
}
