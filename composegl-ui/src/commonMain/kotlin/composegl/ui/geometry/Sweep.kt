package composegl.ui.geometry

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The clock-hand wedge over a rectangle, as a triangle fan.
 *
 * A cooldown, a capture meter, a bomb timer: a wedge that starts at twelve o'clock and sweeps
 * clockwise, covering the *whole* of the box it is over rather than a circle inscribed in it. A
 * disc would leave the corners of a square ability icon uncovered, which reads as a bug.
 *
 * It is exact, and that is why it is cheap. The edge of a rectangle is four straight lines, so a
 * wedge over one needs no arc and no smoothness setting: the hub, where the sweep starts, any
 * corners it has passed, and where it ends. Six points at the very most, and every one of them on
 * the rectangle itself however big it is drawn.
 *
 * Angles are in turns — one whole turn is 1f — measured clockwise from straight up, because that
 * is how a cooldown is described and it saves every caller a conversion.
 *
 * @return x, y pairs with the hub first, ready for [composegl.ui.graphics.UiCanvas.fan]. Empty if
 *   there is nothing to draw.
 */
fun boxSweep(bounds: Rect, fromTurns: Float, sweepTurns: Float): FloatArray {
    if (bounds.isEmpty || sweepTurns <= 0f) return FloatArray(0)

    val sweep = sweepTurns.coerceAtMost(1f)
    val from = fromTurns - kotlin.math.floor(fromTurns)
    val to = from + sweep

    val centre = bounds.centre
    val corners = CornerTurns.map { bounds.cornerTurn(it) }

    val angles = mutableListOf(from)
    // Every corner the sweep passes, in the order it passes them. Two turns' worth of candidates,
    // because a sweep starting late in one turn ends early in the next.
    corners.forEach { corner ->
        listOf(corner, corner + 1f).forEach { if (it > from && it < to) angles += it }
    }
    angles += to
    angles.sort()

    val points = FloatArray((angles.size + 1) * 2)
    points[0] = centre.x
    points[1] = centre.y
    angles.forEachIndexed { index, turn ->
        val at = bounds.edgeAt(turn)
        points[(index + 1) * 2] = at.x
        points[(index + 1) * 2 + 1] = at.y
    }
    return points
}

/** Which corner each of the four is, as a fraction of the way round from the top-right. */
private val CornerTurns = listOf(Corner.TopRight, Corner.BottomRight, Corner.BottomLeft, Corner.TopLeft)

private enum class Corner { TopRight, BottomRight, BottomLeft, TopLeft }

/** Where a corner sits on the clock, in turns clockwise from straight up. */
private fun Rect.cornerTurn(corner: Corner): Float {
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    return when (corner) {
        Corner.TopRight -> turnOf(halfWidth, -halfHeight)
        Corner.BottomRight -> turnOf(halfWidth, halfHeight)
        Corner.BottomLeft -> turnOf(-halfWidth, halfHeight)
        Corner.TopLeft -> turnOf(-halfWidth, -halfHeight)
    }
}

/**
 * Where the ray at [turn] leaves the rectangle.
 *
 * The ray is scaled until it meets whichever edge it reaches first, which is the one it is most
 * pointed at. A rectangle of no width or height never gets here; [boxSweep] refuses it.
 */
private fun Rect.edgeAt(turn: Float): Offset {
    val radians = turn * 2f * PI.toFloat()
    // y grows downwards, so "up" is negative and clockwise on screen is anticlockwise in maths.
    val dx = sin(radians)
    val dy = -cos(radians)
    val toSide = if (dx == 0f) Float.MAX_VALUE else (width / 2f) / kotlin.math.abs(dx)
    val toEnd = if (dy == 0f) Float.MAX_VALUE else (height / 2f) / kotlin.math.abs(dy)
    val distance = minOf(toSide, toEnd)
    // Snapped to the edge it met rather than trusted to the trigonometry. A sine is a few
    // millionths out, and a few millionths inside the box is a hairline of icon left uncovered.
    return if (toSide <= toEnd) {
        Offset(
            if (dx > 0f) right else left,
            (centre.y + dy * distance).coerceIn(top, bottom),
        )
    } else {
        Offset(
            (centre.x + dx * distance).coerceIn(left, right),
            if (dy > 0f) bottom else top,
        )
    }
}

/** The turn a direction points in, clockwise from straight up, in 0 until 1. */
private fun turnOf(x: Float, y: Float): Float {
    val turn = atan2(x, -y) / (2f * PI.toFloat())
    return if (turn < 0f) turn + 1f else turn
}
