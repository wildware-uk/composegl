package dev.wildware.composegl.ui.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * An outline that fits a box: a circle, a diamond, a hexagon, a rounded rectangle.
 *
 * Written against a width and a height rather than against numbers of its own, so one shape serves
 * every node it is put on and keeps fitting while a node grows. The same value answers the two
 * questions a node asks of a shape — what does it draw ([outline]) and what can be clicked
 * ([contains]) — so a portrait cut round by `Modifier.clipShape` and made round to the pointer by
 * `Modifier.hitShape` cannot disagree about where its edge is.
 *
 * **Convex is the contract.** The outline is drawn as a fan from its middle, and a shape that turns
 * back on itself comes out with its dents filled in; [Shapes.polygon] refuses one for that reason.
 */
interface Shape {

    /** Whether [point], in the box's own coordinates from its top-left, is inside the shape. */
    fun contains(point: Offset, size: Size): Boolean

    /**
     * The outline round a box of [width] by [height], as x, y, x, y… from the box's top-left.
     *
     * Clockwise on screen, without the first point repeated at the end. Curves are walked here, at
     * a smoothness that suits the size being asked for, because that is the only place that knows
     * how big the curve is.
     */
    fun outline(width: Float, height: Float): FloatArray
}

/** The shapes a game asks for most, and a way to make any other convex one. */
object Shapes {

    /**
     * The box itself. What `Modifier.clip()` has always been, and cheap: a clip to this is a
     * scissor, with no picture taken.
     */
    val Rectangle: Shape = object : Shape {
        override fun contains(point: Offset, size: Size) =
            point.x >= 0f && point.y >= 0f && point.x < size.width && point.y < size.height

        override fun outline(width: Float, height: Float) = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)

        override fun toString() = "Rectangle"
    }

    /**
     * The biggest circle that fits, in the middle of the box.
     *
     * A circle rather than an ellipse, so a portrait laid out a little wider than it is tall stays
     * round rather than turning into an egg. [Ellipse] is the one that stretches.
     */
    val Circle: Shape = object : Shape {
        override fun contains(point: Offset, size: Size): Boolean {
            val radius = minOf(size.width, size.height) / 2f
            val dx = point.x - size.width / 2f
            val dy = point.y - size.height / 2f
            return dx * dx + dy * dy <= radius * radius
        }

        override fun outline(width: Float, height: Float): FloatArray {
            val radius = minOf(width, height) / 2f
            return ellipse(width / 2f, height / 2f, radius, radius)
        }

        override fun toString() = "Circle"
    }

    /** An ellipse touching all four sides of the box. */
    val Ellipse: Shape = object : Shape {
        override fun contains(point: Offset, size: Size): Boolean {
            if (size.isEmpty) return false
            val nx = (point.x - size.width / 2f) / (size.width / 2f)
            val ny = (point.y - size.height / 2f) / (size.height / 2f)
            return nx * nx + ny * ny <= 1f
        }

        override fun outline(width: Float, height: Float) = ellipse(width / 2f, height / 2f, width / 2f, height / 2f)

        override fun toString() = "Ellipse"
    }

    /** A square turned on its point: the middle of each side of the box. A minimap frame. */
    val Diamond: Shape = polygon(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)

    /** Six sides, points at the top and the bottom. A tile in a honeycomb laid out in rows. */
    val Hexagon: Shape = polygon(0.5f, 0f, 1f, 0.25f, 1f, 0.75f, 0.5f, 1f, 0f, 0.75f, 0f, 0.25f)

    /**
     * A rectangle with its corners rounded by [corner], held to half the shorter side — so a
     * corner bigger than the box makes a pill, not a mess.
     */
    fun roundedRect(corner: Float): Shape {
        require(corner >= 0f) { "a corner cannot be negative, was $corner" }
        return roundedRect(Corners.all(corner))
    }

    /**
     * The same with a radius per corner, so a clip can follow a tab rounded only along its top.
     *
     * No rounding at all is [Rectangle] itself, so a corner radius that animates down to nothing
     * ends up a scissor again rather than a picture of a square.
     */
    fun roundedRect(corners: Corners): Shape = if (corners == Corners.None) Rectangle else RoundedRect(corners)

    /**
     * Any convex outline, as x, y, x, y… **fractions of the box**: 0, 0 is its top-left and 1, 1 its
     * bottom-right.
     *
     * Fractions so the shape is written once and fits every node it is put on. A convex outline
     * because that is what can be drawn as one fan; a concave one, or one that crosses itself like a
     * star, throws here, naming the problem, rather than drawing with its dents filled in.
     */
    fun polygon(vararg points: Float): Shape = Polygon(points.copyOf())

    internal data class RoundedRect(val corners: Corners) : Shape {
        override fun contains(point: Offset, size: Size): Boolean {
            if (!Rectangle.contains(point, size)) return false
            val half = minOf(size.width, size.height) / 2f
            // Only the corner square of the quarter the point is in can say no, and it asks its own
            // circle.
            val right = point.x >= size.width / 2f
            val bottom = point.y >= size.height / 2f
            val radius = when {
                !right && !bottom -> corners.topLeft
                right && !bottom -> corners.topRight
                right -> corners.bottomRight
                else -> corners.bottomLeft
            }.coerceAtMost(half)
            val cx = if (right) minOf(point.x, size.width - radius) else maxOf(point.x, radius)
            val cy = if (bottom) minOf(point.y, size.height - radius) else maxOf(point.y, radius)
            val dx = point.x - cx
            val dy = point.y - cy
            return dx * dx + dy * dy <= radius * radius
        }

        override fun outline(width: Float, height: Float): FloatArray {
            val half = minOf(width, height) / 2f
            // Clockwise on screen from the top-right corner, each corner a quarter turn about its
            // own centre. Angles count clockwise from three o'clock because y grows downwards.
            val radii = floatArrayOf(corners.topRight, corners.bottomRight, corners.bottomLeft, corners.topLeft)
            for (at in radii.indices) radii[at] = radii[at].coerceAtMost(half)
            if (radii.all { it <= 0f }) return Rectangle.outline(width, height)
            // A square corner is one point, not a curve of radius nothing walked a few times over.
            val steps = IntArray(4) { if (radii[it] > 0f) curveSteps(radii[it], quarter = true) else 0 }
            val points = FloatArray(steps.sumOf { it + 1 } * 2)
            var at = 0
            for (corner in 0 until 4) {
                val radius = radii[corner]
                val centreX = if (corner < 2) width - radius else radius
                val centreY = if (corner == 0 || corner == 3) radius else height - radius
                val start = -HALF_PI + corner * HALF_PI
                val count = steps[corner]
                for (step in 0..count) {
                    val angle = if (count == 0) start + HALF_PI / 2f else start + HALF_PI * step / count
                    points[at++] = centreX + cos(angle) * radius
                    points[at++] = centreY + sin(angle) * radius
                }
            }
            return points
        }
    }

    private class Polygon(private val fractions: FloatArray) : Shape {
        init {
            require(fractions.size >= 6 && fractions.size % 2 == 0) {
                "a polygon is at least three x, y pairs, was ${fractions.size} numbers"
            }
            require(isConvex(fractions)) {
                "this polygon turns back on itself, and only a convex outline can be drawn as one " +
                    "fan: split it into convex pieces"
            }
        }

        override fun contains(point: Offset, size: Size): Boolean {
            if (size.isEmpty) return false
            val x = point.x / size.width
            val y = point.y / size.height
            // Convex, so inside is on the same side of every edge. Zero is on the edge, and counts.
            var sign = 0f
            val count = fractions.size / 2
            for (i in 0 until count) {
                val ax = fractions[i * 2]
                val ay = fractions[i * 2 + 1]
                val bx = fractions[(i + 1) % count * 2]
                val by = fractions[(i + 1) % count * 2 + 1]
                val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                if (cross == 0f) continue
                if (sign == 0f) sign = cross else if (cross * sign < 0f) return false
            }
            return true
        }

        override fun outline(width: Float, height: Float) = FloatArray(fractions.size) { at ->
            fractions[at] * if (at % 2 == 0) width else height
        }

        override fun equals(other: Any?) = other is Polygon && fractions.contentEquals(other.fractions)

        override fun hashCode() = fractions.contentHashCode()

        override fun toString() = "Polygon(${fractions.joinToString()})"
    }

    private const val HALF_PI = (PI / 2).toFloat()

    private fun ellipse(centreX: Float, centreY: Float, radiusX: Float, radiusY: Float): FloatArray {
        val steps = curveSteps(maxOf(radiusX, radiusY), quarter = false)
        val points = FloatArray(steps * 2)
        for (step in 0 until steps) {
            val angle = 2f * PI.toFloat() * step / steps
            points[step * 2] = centreX + cos(angle) * radiusX
            points[step * 2 + 1] = centreY + sin(angle) * radiusY
        }
        return points
    }

    /**
     * How many straight pieces a curve of this radius is worth: about one per two units of its
     * length, the same smoothness `UiCanvas.circle` walks at, and never fewer than a shape that
     * still reads as round.
     */
    private fun curveSteps(radius: Float, quarter: Boolean): Int {
        val length = radius * if (quarter) HALF_PI else 4f * HALF_PI
        return if (quarter) (length / 2f).toInt().coerceIn(3, 45) else (length / 2f).toInt().coerceIn(16, 180)
    }

    /**
     * Every corner turns the same way, and all the turns add up to one trip round. The second half
     * is what refuses a star: a pentagram turns the same way at every point too, but goes round
     * twice, and its fan would draw the middle twice over while [Polygon.contains] kept only it.
     */
    private fun isConvex(points: FloatArray): Boolean {
        val count = points.size / 2
        var sign = 0f
        var turned = 0.0
        for (i in 0 until count) {
            val ax = points[i * 2]
            val ay = points[i * 2 + 1]
            val bx = points[(i + 1) % count * 2]
            val by = points[(i + 1) % count * 2 + 1]
            val cx = points[(i + 2) % count * 2]
            val cy = points[(i + 2) % count * 2 + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            val dot = (bx - ax) * (cx - bx) + (by - ay) * (cy - by)
            turned += atan2(cross.toDouble(), dot.toDouble())
            if (abs(cross) < 1e-6f) continue
            if (sign == 0f) sign = cross else if (cross * sign < 0f) return false
        }
        return sign != 0f && abs(abs(turned) - 2.0 * PI) < 1e-3
    }
}
