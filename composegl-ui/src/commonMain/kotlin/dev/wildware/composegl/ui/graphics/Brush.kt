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

    /** A colour at a place along a gradient: [at] is 0 at the start of the run and 1 at its end. */
    data class Stop(val at: Float, val colour: Colour) {
        init {
            require(at in 0f..1f) { "a stop sits between the start and the end of the gradient, not at $at" }
        }
    }

    /**
     * A gradient through any number of colours, each at its own place along the run.
     *
     * The glassy look of a game button is three: a light top, a mid body, a darker bottom. Two
     * colours cannot say that, and stacking two gradients to fake it leaves a seam.
     *
     * [degrees] turns it the way [Linear] turns, and is ignored when [radial] is true, where the
     * run goes outwards from the middle as [Radial] does. The stops run in order and the ends are
     * held: anything before the first stop is the first colour, anything after the last is the last.
     *
     * A backend that cannot draw a run of stops draws the first colour flat, exactly as it does for
     * any other brush; see [UiCanvas.drawsGradients].
     */
    data class Ramp(val stops: List<Stop>, val degrees: Float = 90f, val radial: Boolean = false) : Brush {
        init {
            require(stops.size >= 2) { "a gradient needs at least two stops, was given ${stops.size}" }
            require(stops.zipWithNext().all { (a, b) -> a.at <= b.at }) { "a gradient's stops run in order: $stops" }
            require(!degrees.isNaN() && !degrees.isInfinite()) { "a gradient's angle has to be a number, was $degrees" }
        }

        override val first: Colour get() = stops.first().colour
        override val last: Colour get() = stops.last().colour

        /** The straight gradient this runs along, or null when it runs outwards from the middle. */
        val straight: Linear? get() = if (radial) null else Linear(first, last, degrees)

        override fun fractionAt(x: Float, y: Float, box: Rect): Float =
            if (radial) Radial(first, last).fractionAt(x, y, box) else Linear(first, last, degrees).fractionAt(x, y, box)

        override fun colourAt(x: Float, y: Float, box: Rect): Colour = at(fractionAt(x, y, box))

        /** The colour this paints [fraction] of the way along its run, with the ends held. */
        fun at(fraction: Float): Colour {
            val t = fraction.coerceIn(0f, 1f)
            if (t <= stops.first().at) return stops.first().colour
            if (t >= stops.last().at) return stops.last().colour
            val next = stops.indexOfFirst { it.at >= t }
            val before = stops[next - 1]
            val after = stops[next]
            val span = after.at - before.at
            // Two stops in the same place: the later one wins, which is how a hard edge is written.
            if (span <= 0f) return after.colour
            return between(before.colour, after.colour, (t - before.at) / span)
        }

        override fun scaleAlpha(factor: Float) = copy(stops = stops.map { it.copy(colour = it.colour.scaleAlpha(factor)) })
        override fun modulate(tint: Colour) = copy(stops = stops.map { it.copy(colour = it.colour.modulate(tint)) })
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

        /** A straight gradient through [stops], each at its own place along the run. See [Ramp]. */
        fun ramp(vararg stops: Stop, degrees: Float = 90f): Brush = Ramp(stops.toList(), degrees)

        /** The same, running outwards from the middle of the box. */
        fun radialRamp(vararg stops: Stop): Brush = Ramp(stops.toList(), radial = true)

        /**
         * A straight gradient through [colours], spaced evenly: three colours put the middle one
         * halfway. Say [ramp] instead when a colour belongs somewhere other than its even share.
         */
        fun evenly(colours: List<Colour>, degrees: Float = 90f): Brush {
            require(colours.size >= 2) { "a gradient needs at least two colours, was given ${colours.size}" }
            val last = colours.size - 1
            return Ramp(colours.mapIndexed { at, colour -> Stop(at / last.toFloat(), colour) }, degrees)
        }

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
