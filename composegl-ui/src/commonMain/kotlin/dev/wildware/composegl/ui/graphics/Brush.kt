package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A fill that changes colour across the box it is painted into.
 *
 * A health bar that runs from green to red, a vignette behind a menu, a sky panel, the fade at the
 * edge of a scroll area. Two colours and a direction, rather than a list of stops: that is what the
 * rounded-box shader can carry per vertex without flushing between two widgets that happen to want
 * different gradients, and it is what nearly every one of those examples actually is.
 *
 * Everything is measured against the box a gradient is drawn into, not against the screen. The
 * same brush on a small button and a full-screen panel runs edge to edge on both, so a skin can
 * name one without knowing how big the widget wearing it will be.
 *
 * Colours are mixed with their opacity taken into account. A gradient from red to
 * [Colour.Transparent] fades out as red all the way, rather than passing through a murky dark red
 * on the way — which is what mixing the four channels separately does, because transparent is
 * black with nothing showing.
 *
 * A canvas that cannot draw one draws [first] flat instead; see [UiCanvas.drawsGradients].
 */
sealed interface Brush {

    /**
     * The colour a gradient starts from, and what a canvas with no gradients draws instead.
     *
     * The start rather than an average, so that a backend that falls back is predictable: a skin
     * author reading the file can see which colour they will get.
     */
    val first: Colour

    /** The colour it ends at. */
    val last: Colour

    /** How far along the gradient a point is, from 0 at [first] to 1 at [last], for a brush in [box]. */
    fun fractionAt(x: Float, y: Float, box: Rect): Float

    /**
     * The colour this paints at a point inside [box].
     *
     * The same arithmetic the shaders do, on the CPU. Here so a test can say what a pixel ought to
     * be without writing the maths out again, and so a widget that wants the colour at a place — the
     * tip of a bar, say — can ask rather than guess.
     */
    fun colourAt(x: Float, y: Float, box: Rect): Colour = between(first, last, fractionAt(x, y, box))

    /** Both colours at a different opacity. What the canvas's alpha stack does to a brush. */
    fun scaleAlpha(factor: Float): Brush

    /** Both colours seen through [tint], as [Colour.modulate] does for one. What a skin's tint does. */
    fun modulate(tint: Colour): Brush

    /**
     * A straight gradient across the box, from [start] to [end].
     *
     * [degrees] is the direction it runs, clockwise from pointing right, the same way every other
     * angle in this toolkit turns: 0 is left to right, 90 is top to bottom, 45 is top-left to
     * bottom-right. Clockwise on screen because y grows downwards.
     *
     * It reaches the box's corners rather than its edges, so at 45 degrees the top-left corner is
     * exactly [start] and the bottom-right exactly [end] — the same rule CSS uses, and the one that
     * makes an angled gradient on a long thin bar use its whole range.
     */
    data class Linear(val start: Colour, val end: Colour, val degrees: Float = 90f) : Brush {
        init {
            require(!degrees.isNaN() && !degrees.isInfinite()) { "a gradient's angle has to be a number, was $degrees" }
        }

        override val first: Colour get() = start
        override val last: Colour get() = end

        override fun fractionAt(x: Float, y: Float, box: Rect): Float {
            val axis = axis(box.width, box.height)
            if (axis.x == 0f && axis.y == 0f) return 0f
            val t =(x - box.centre.x) * axis.x + (y - box.centre.y) * axis.y + 0.5f
            return t.coerceIn(0f, 1f)
        }

        /**
         * How far along the gradient one unit of travel moves, as x and y, in a box this size.
         *
         * The direction scaled down by the gradient's length, so that a point's offset from the
         * middle of the box, dotted with this, is its fraction less a half. That one dot product
         * is all a shader has to do per pixel, and handing it over already worked out is what
         * keeps two backends from each deriving it slightly differently.
         *
         * In the toolkit's coordinates, y downwards. A backend that counts y upwards flips [Offset.y].
         * A box with no size has no gradient to travel along, and gets zero: all [start].
         */
        fun axis(width: Float, height: Float): Offset {
            val radians = degrees * PI_OVER_180
            val across = cos(radians)
            val down = sin(radians)
            val length = abs(width * across) + abs(height * down)
            if (length <= 0f) return Offset(0f, 0f)
            return Offset(across / length, down / length)
        }

        override fun scaleAlpha(factor: Float) = copy(start = start.scaleAlpha(factor), end = end.scaleAlpha(factor))
        override fun modulate(tint: Colour) = copy(start = start.modulate(tint), end = end.modulate(tint))
    }

    /**
     * A gradient outwards from the middle of the box, [centre] in the middle and [edge] at its sides.
     *
     * An ellipse that fits the box, so a square gets a circle and a wide panel a wide oval, and it
     * meets the edge colour at the middle of each side. The corners lie further out than that and
     * stay at [edge], which is what a vignette wants: dark all round the rim, not only at four points.
     */
    data class Radial(val centre: Colour, val edge: Colour) : Brush {
        override val first: Colour get() = centre
        override val last: Colour get() = edge

        override fun fractionAt(x: Float, y: Float, box: Rect): Float {
            val halfWidth = box.width / 2f
            val halfHeight = box.height / 2f
            if (halfWidth <= 0f || halfHeight <= 0f) return 0f
            val across = (x - box.centre.x) / halfWidth
            val down = (y - box.centre.y) / halfHeight
            return sqrt(across * across + down * down).coerceIn(0f, 1f)
        }

        override fun scaleAlpha(factor: Float) = copy(centre = centre.scaleAlpha(factor), edge = edge.scaleAlpha(factor))
        override fun modulate(tint: Colour) = copy(centre = centre.modulate(tint), edge = edge.modulate(tint))
    }

    companion object {

        /** [top] along the top edge, [bottom] along the bottom. */
        fun vertical(top: Colour, bottom: Colour): Brush = Linear(top, bottom, degrees = 90f)

        /** [left] down the left edge, [right] down the right. */
        fun horizontal(left: Colour, right: Colour): Brush = Linear(left, right, degrees = 0f)

        /** [start] to [end] in the direction [degrees] points: clockwise from right. See [Linear]. */
        fun linear(start: Colour, end: Colour, degrees: Float): Brush = Linear(start, end, degrees)

        /** [centre] in the middle, [edge] round the rim. See [Radial]. */
        fun radial(centre: Colour, edge: Colour): Brush = Radial(centre, edge)

        /**
         * The colour [fraction] of the way from [from] to [to], mixed the way a gradient mixes.
         *
         * Premultiplied: each colour is weighted by its own opacity before the two are mixed, and
         * the answer is divided back out. So fading to transparent keeps the hue instead of
         * darkening towards black, which is what [Colour.lerp] does and why a gradient cannot use it.
         */
        fun between(from: Colour, to: Colour, fraction: Float): Colour {
            val t = fraction.coerceIn(0f, 1f)
            val fromAlpha = from.alpha / 255f
            val toAlpha = to.alpha / 255f
            val alpha = fromAlpha + (toAlpha - fromAlpha) * t
            if (alpha <= 0f) return Colour.Transparent
            fun mix(a: Int, b: Int) =
                ((a * fromAlpha + (b * toAlpha - a * fromAlpha) * t) / alpha).roundToInt().coerceIn(0, 255)
            return Colour(
                (alpha * 255f).roundToInt(),
                mix(from.red, to.red),
                mix(from.green, to.green),
                mix(from.blue, to.blue),
            )
        }

        private const val PI_OVER_180 = 0.017453292f
    }
}
