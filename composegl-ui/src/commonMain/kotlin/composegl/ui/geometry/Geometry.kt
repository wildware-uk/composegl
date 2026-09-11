package composegl.ui.geometry

/**
 * A point, in virtual pixels, measured from the top-left of the interface.
 *
 * Y grows downwards, because that is how interfaces are described. OpenGL disagrees, and the
 * argument is settled once, inside a backend, rather than everywhere.
 */
data class Offset(val x: Float, val y: Float) {

    operator fun plus(other: Offset) = Offset(x + other.x, y + other.y)
    operator fun minus(other: Offset) = Offset(x - other.x, y - other.y)
    operator fun times(scale: Float) = Offset(x * scale, y * scale)

    fun distanceTo(other: Offset): Float {
        val dx = other.x - x
        val dy = other.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        val Zero = Offset(0f, 0f)
    }
}

/** A width and a height, in virtual pixels. Never negative. */
data class Size(val width: Float, val height: Float) {

    init {
        require(width >= 0f && height >= 0f) { "a size cannot be negative, was ${width}x$height" }
    }

    val isEmpty: Boolean get() = width == 0f || height == 0f

    companion object {
        val Zero = Size(0f, 0f)
    }
}

/**
 * A rectangle, held as its edges rather than as a corner and a size.
 *
 * Edges are what nearly every operation on a rectangle actually wants — intersecting, testing a
 * point, insetting — and holding them directly keeps those operations free of arithmetic that can
 * be got subtly wrong.
 */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val size: Size get() = Size(width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val topLeft: Offset get() = Offset(left, top)
    val centre: Offset get() = Offset((left + right) / 2f, (top + bottom) / 2f)

    /** True when the rectangle has no area at all, which an intersection often produces. */
    val isEmpty: Boolean get() = right <= left || bottom <= top

    operator fun contains(point: Offset): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom

    fun translate(by: Offset) = Rect(left + by.x, top + by.y, right + by.x, bottom + by.y)

    /** Shrunk on every side. A negative amount grows it. */
    fun inset(by: Float) = Rect(left + by, top + by, right - by, bottom - by)

    fun inset(left: Float, top: Float, right: Float, bottom: Float): Rect {
        // Inset by nothing is this rectangle. Worth the line: a draw pass asks this of every node
        // it paints, every frame, and most nodes have no padding at all.
        if (left == 0f && top == 0f && right == 0f && bottom == 0f) return this
        return Rect(this.left + left, this.top + top, this.right - right, this.bottom - bottom)
    }

    /**
     * The overlap of two rectangles.
     *
     * The result can be empty — that is the answer, not an error, and callers are expected to ask
     * [isEmpty] rather than to assume. Nested clipping depends on it.
     */
    fun intersect(other: Rect) = Rect(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    )

    fun overlaps(other: Rect): Boolean = !intersect(other).isEmpty

    companion object {
        val Zero = Rect(0f, 0f, 0f, 0f)

        fun of(topLeft: Offset, size: Size) =
            Rect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height)

        fun of(x: Float, y: Float, width: Float, height: Float) =
            Rect(x, y, x + width, y + height)
    }
}
