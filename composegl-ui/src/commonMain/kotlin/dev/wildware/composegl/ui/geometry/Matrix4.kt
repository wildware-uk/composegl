package dev.wildware.composegl.ui.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val DegreesToRadians = (PI / 180.0).toFloat()

/**
 * A 4×4 transform: where a point of a flat picture lands once it has been turned in depth and
 * seen through a camera.
 *
 * What `Modifier.rotate3d` hands a canvas. Held in rows, [values] being row 0 left to right, then
 * row 1, and so on, and applied to a column `(x, y, z, 1)` — so `a * b` applied to a point is `b`
 * first, then `a`, the way the matrix is written in maths.
 *
 * The coordinates are the toolkit's own: x right, y *down*, and z towards whoever is looking at
 * the screen. Every angle has the sign CSS gives it in those coordinates, so a positive [rotationZ]
 * is clockwise like `Modifier.rotate`, a positive [rotationY] sends the right edge away, and a
 * positive [rotationX] sends the top edge away.
 *
 * Immutable, and equal to another with the same sixteen numbers, so a node whose transform did
 * not change compares equal and redraws nothing.
 */
class Matrix4 private constructor(private val values: FloatArray) {

    /** The number at [row] and [column], both counted from zero. */
    operator fun get(row: Int, column: Int): Float = values[row * 4 + column]

    /** This transform applied after [other]. */
    operator fun times(other: Matrix4): Matrix4 {
        val out = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += values[row * 4 + k] * other.values[k * 4 + column]
                out[row * 4 + column] = sum
            }
        }
        return Matrix4(out)
    }

    /**
     * Where the flat point ([x], [y]) lands, *before* the divide by depth: an x, a y and a w,
     * written into [into] at [at].
     *
     * The form a GPU wants. Handed the three numbers rather than the answer, it divides by w itself,
     * per pixel, which is what keeps a picture on a tilted quad from bending along its diagonal. A
     * w of zero or less is a point level with or behind the camera; the GPU clips those away.
     */
    fun project(x: Float, y: Float, into: FloatArray, at: Int = 0) {
        into[at] = values[0] * x + values[1] * y + values[3]
        into[at + 1] = values[4] * x + values[5] * y + values[7]
        into[at + 2] = values[12] * x + values[13] * y + values[15]
    }

    /**
     * Where the flat point ([x], [y]) lands on the screen, with the divide done.
     *
     * For hit testing, recording and anything else on the CPU. A point level with or behind the
     * camera has no place on the screen and comes back with infinite or flipped coordinates, so
     * ask [depthOf] first where that can happen.
     */
    fun map(x: Float, y: Float): Offset {
        val w = depthOf(x, y)
        return Offset(
            (values[0] * x + values[1] * y + values[3]) / w,
            (values[4] * x + values[5] * y + values[7]) / w,
        )
    }

    /** The w of the flat point ([x], [y]): one on the picture's own plane, more the further away. */
    fun depthOf(x: Float, y: Float): Float = values[12] * x + values[13] * y + values[15]

    /** Whether this does anything at all. */
    val isIdentity: Boolean get() = values.contentEquals(Identity.values)

    override fun equals(other: Any?): Boolean = other is Matrix4 && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String =
        (0 until 4).joinToString(prefix = "Matrix4(", postfix = ")", separator = " / ") { row ->
            (0 until 4).joinToString(" ") { column -> this[row, column].toString() }
        }

    companion object {

        /** Changes nothing. */
        val Identity = Matrix4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** Sixteen numbers, row by row. */
        fun of(vararg values: Float): Matrix4 {
            require(values.size == 16) { "a 4×4 matrix is sixteen numbers, not ${values.size}" }
            return Matrix4(values.copyOf())
        }

        /** Moves every point by ([x], [y], [z]). */
        fun translation(x: Float, y: Float, z: Float = 0f) = Matrix4(
            floatArrayOf(
                1f, 0f, 0f, x,
                0f, 1f, 0f, y,
                0f, 0f, 1f, z,
                0f, 0f, 0f, 1f,
            ),
        )

        /** Turns about the x axis, the top edge going away for a positive angle. */
        fun rotationX(degrees: Float): Matrix4 {
            val c = cosOf(degrees)
            val s = sinOf(degrees)
            return Matrix4(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, c, -s, 0f,
                    0f, s, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** Turns about the y axis, the right edge going away for a positive angle. */
        fun rotationY(degrees: Float): Matrix4 {
            val c = cosOf(degrees)
            val s = sinOf(degrees)
            return Matrix4(
                floatArrayOf(
                    c, 0f, s, 0f,
                    0f, 1f, 0f, 0f,
                    -s, 0f, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** Turns in the plane of the screen, clockwise for a positive angle — `Modifier.rotate`'s turn. */
        fun rotationZ(degrees: Float): Matrix4 {
            val c = cosOf(degrees)
            val s = sinOf(degrees)
            return Matrix4(
                floatArrayOf(
                    c, -s, 0f, 0f,
                    s, c, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** Slides x by [slopeX] for every unit of y, and y by [slopeY] for every unit of x. */
        fun shear(slopeX: Float, slopeY: Float) = Matrix4(
            floatArrayOf(
                1f, slopeX, 0f, 0f,
                slopeY, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /**
         * A camera [distance] in front of the origin, looking straight at it.
         *
         * A point at depth z is seen at `distance / (distance - z)` times its size: nearer is
         * bigger, further is smaller, and the plane z = 0 is exactly its own size. CSS's
         * `perspective()`, in the same units.
         */
        fun perspective(distance: Float): Matrix4 {
            require(distance > 0f && distance.isFinite()) { "a camera distance must be positive, was $distance" }
            return Matrix4(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, -1f / distance, 1f,
                ),
            )
        }

        // Exact at the quarter turns, so a card at 90 or 180 degrees is exactly edge-on or exactly
        // reversed rather than off by the float error in π.
        private fun cosOf(degrees: Float): Float = when (val turn = ((degrees % 360f) + 360f) % 360f) {
            0f -> 1f
            90f, 270f -> 0f
            180f -> -1f
            else -> cos(turn * DegreesToRadians)
        }

        private fun sinOf(degrees: Float): Float = when (val turn = ((degrees % 360f) + 360f) % 360f) {
            0f, 180f -> 0f
            90f -> 1f
            270f -> -1f
            else -> sin(turn * DegreesToRadians)
        }
    }
}
