package dev.wildware.composegl.render

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, written into the device's vertex storage and handed over in as
 * few draw calls as the textures, blend modes and clips allow.
 *
 * One copy for every backend, so a draw-call trace means the same thing whichever one is drawing.
 * The coordinates reaching here already count y upwards: the canvas flips once on the way in.
 *
 * Colours are four floats per vertex. Every colour arriving has already had the canvas's alpha and
 * tint stacks multiplied into it, so this batch has no idea either exists.
 */
class QuadBatch(private val device: GpuDevice, private val maxQuads: Int = 2048) {

    private val vertices = device.vertices(maxQuads)
    private val capacity = maxQuads * 4 * ShapeVertex.Floats
    private var used = 0

    private var texture: DeviceTexture? = null
    private var blend = Blend.SourceOver
    private var drawing = false

    private val projection = FloatArray(16)

    /** How many times this batch talked to the device since [begin]. */
    var renderCalls = 0
        private set

    /** Told why, each time [renderCalls] goes up. Null tells nobody. */
    var trace: DrawCallTrace? = null

    /** Starts a frame. [projection] is a column-major 4x4, in design coordinates. */
    fun begin(projection: FloatArray) {
        check(!drawing) { "begin() was called twice without an end()" }
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        drawing = true
        renderCalls = 0
        projection.copyInto(this.projection)
        blend = Blend.SourceOver
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush(BatchBreak.End)
        drawing = false
    }

    /** Points the following quads somewhere else. Flushes first: what is queued was for the old one. */
    fun projection(projection: FloatArray) {
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        flush(BatchBreak.Layer)
        projection.copyInto(this.projection)
    }

    /**
     * How the following quads are combined with what is already there.
     *
     * The one place this batch's blending is decided. What is queued was queued to blend the old
     * way, so it goes first, blamed on [reason].
     *
     * @param premultiplied true for a layer being drawn back, whose colours are already multiplied
     *   by their own opacity.
     */
    fun blend(mode: BlendMode, premultiplied: Boolean, reason: BatchBreak = BatchBreak.Blend) {
        flush(reason)
        blend = Blend.of(mode, premultiplied)
    }

    /**
     * Hands what is queued to the device, blaming [reason] on the trace — but only when something
     * was queued, since an empty flush costs no draw call.
     */
    fun flush(reason: BatchBreak) {
        if (used == 0) return
        val quads = used / (4 * ShapeVertex.Floats)
        device.drawShapes(vertices, quads, checkNotNull(texture), blend, projection)
        renderCalls++
        trace?.record(reason)
        used = 0
    }

    /**
     * One rounded box with a radius for each corner, and any of a fill, a border and a shadow.
     *
     * The corners are named as they look on screen. Each is held to half the box's shorter side.
     *
     * @param white where solid colour is sampled from.
     * @param aa how wide the softened edge is, in the same units as everything else.
     */
    @Suppress("LongParameterList")
    fun shape(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        fill: Colour,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Colour,
        borderWidth: Float,
        shadow: Colour,
        shadowSpread: Float,
        aa: Float,
    ) {
        val margin = shadowSpread + aa
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
        val u = white.u
        val v = white.v

        use(white.texture)
        quad(
            left = left - margin,
            bottom = bottom - margin,
            right = left + width + margin,
            top = bottom + height + margin,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            u = u, v = v, u2 = u, v2 = v,
            fill = fill,
            border = border,
            shadow = shadow,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            radii = radii,
            borderWidth = borderWidth,
            shadowSpread = shadowSpread,
            aa = aa,
        )
    }

    /**
     * One rounded box filled with a gradient between [start] and [end]: the same quad and the same
     * distance field as [shape], so it batches with every flat panel. The end colour rides in the
     * border colour's slot.
     *
     * @param axisX how far along a straight gradient one unit across moves it, already divided by
     *   its length. Ignored when [radial].
     * @param axisY the same, in this batch's y-up coordinates.
     */
    @Suppress("LongParameterList")
    fun gradient(
        white: WhiteSpot,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        start: Colour,
        end: Colour,
        radial: Boolean,
        axisX: Float,
        axisY: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        aa: Float,
    ) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
        val u = white.u
        val v = white.v

        use(white.texture)
        quad(
            left = left - aa,
            bottom = bottom - aa,
            right = left + width + aa,
            top = bottom + height + aa,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            u = u, v = v, u2 = u, v2 = v,
            fill = start,
            border = end,
            shadow = Colour.Transparent,
            halfWidth = halfWidth,
            halfHeight = halfHeight,
            radii = radii,
            borderWidth = 0f,
            shadowSpread = 0f,
            aa = aa,
            gradient = if (radial) ShapeVertex.Radial else ShapeVertex.Linear,
            gradientX = axisX,
            gradientY = axisY,
        )
    }

    /** The four radii of the box being written, top-left then clockwise. Read straight after. */
    private val radii = FloatArray(4)

    /** What a picture, a glyph or a fan vertex carries: no corners, since it has no shape. */
    private val noRadii = FloatArray(4)

    /**
     * A triangle fan, in coordinates already flipped.
     *
     * The batch draws quads and nothing else, so each quad carries *two* of the fan's triangles:
     * the quad's own winding — 0,1,2 then 2,3,0 — is already hub, a, b and then b, c, hub. An odd
     * point at the end repeats, which draws a triangle of no area.
     */
    fun fan(white: WhiteSpot, points: FloatArray, colour: Colour) {
        if (points.size < 6) return
        val u = white.u
        val v = white.v
        val hubX = points[0]
        val hubY = points[1]

        var at = 2
        while (at + 3 < points.size) {
            val cx = if (at + 5 < points.size) points[at + 4] else points[at + 2]
            val cy = if (at + 5 < points.size) points[at + 5] else points[at + 3]
            use(white.texture)
            flat(hubX, hubY, u, v, colour)
            flat(points[at], points[at + 1], u, v, colour)
            flat(points[at + 2], points[at + 3], u, v, colour)
            flat(cx, cy, u, v, colour)
            at += 4
        }
    }

    /** A picture, or a glyph. No shape and no softened edge — whatever the texture says. */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
        premultiplied: Boolean = false,
    ) {
        use(texture)
        quad(
            left = left,
            bottom = bottom,
            right = left + width,
            top = bottom + height,
            centreX = 0f,
            centreY = 0f,
            u = u, v = v, u2 = u2, v2 = v2,
            fill = tint,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            halfWidth = 0f,
            halfHeight = 0f,
            radii = noRadii,
            borderWidth = 0f,
            shadowSpread = 0f,
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
            gradient = if (premultiplied) ShapeVertex.PremultipliedPicture else 0f,
        )
    }

    /**
     * The same picture, turned round a pivot, written as an ordinary quad so it batches with every
     * other quad from the same texture.
     *
     * [degrees] turns it clockwise as the toolkit's y-down coordinates see it; everything here is
     * already flipped, so the sign looks back to front on purpose. [pivotX] and [pivotY] are a point
     * in the same flipped coordinates as [left] and [bottom].
     */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        pivotX: Float,
        pivotY: Float,
        degrees: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
        premultiplied: Boolean = false,
    ) {
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        val right = left + width
        val top = bottom + height
        val kind = if (premultiplied) ShapeVertex.PremultipliedPicture else 0f

        use(texture)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        turned(left, bottom, pivotX, pivotY, turnCos, turnSin, u, v2, tint, kind)
        turned(left, top, pivotX, pivotY, turnCos, turnSin, u, v, tint, kind)
        turned(right, top, pivotX, pivotY, turnCos, turnSin, u2, v, tint, kind)
        turned(right, bottom, pivotX, pivotY, turnCos, turnSin, u2, v2, tint, kind)
    }

    /**
     * The same picture on four corners somebody else worked out: top-left, top-right, bottom-right,
     * bottom-left, each an x then a y in this batch's y-up coordinates.
     */
    @Suppress("LongParameterList")
    fun textured(
        texture: DeviceTexture,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(texture)
        flat(corners[6], corners[7], u, v2, tint)
        flat(corners[0], corners[1], u, v, tint)
        flat(corners[2], corners[3], u2, v, tint)
        flat(corners[4], corners[5], u2, v2, tint)
    }

    /**
     * The same picture on four corners that each carry a depth: twelve numbers, top-left, top-right,
     * bottom-right, bottom-left, each an x, a y and a w *before* the divide. The GPU divides per
     * pixel, so the picture does not bend along the diagonal.
     */
    @Suppress("LongParameterList")
    fun projected(
        texture: DeviceTexture,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(texture)
        deep(corners, 9, u, v2, tint)
        deep(corners, 0, u, v, tint)
        deep(corners, 3, u2, v, tint)
        deep(corners, 6, u2, v2, tint)
    }

    /**
     * Four corners of a picture, each with its own place, texture coordinate and colour: what a
     * picture cut to a shape is drawn with. In winding order, in coordinates already flipped.
     */
    @Suppress("LongParameterList")
    fun corners(
        texture: DeviceTexture,
        ax: Float, ay: Float, au: Float, av: Float, aColour: Colour,
        bx: Float, by: Float, bu: Float, bv: Float, bColour: Colour,
        cx: Float, cy: Float, cu: Float, cv: Float, cColour: Colour,
        dx: Float, dy: Float, du: Float, dv: Float, dColour: Colour,
    ) {
        use(texture)
        flat(ax, ay, au, av, aColour)
        flat(bx, by, bu, bv, bColour)
        flat(cx, cy, cu, cv, cColour)
        flat(dx, dy, du, dv, dColour)
    }

    /** One vertex of solid colour, with the distance field switched off. */
    private fun flat(x: Float, y: Float, u: Float, v: Float, colour: Colour) {
        vertex(
            x = x, y = y, u = u, v = v,
            fill = colour,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
        )
    }

    private fun deep(corners: FloatArray, at: Int, u: Float, v: Float, tint: Colour) {
        vertex(
            x = corners[at], y = corners[at + 1], u = u, v = v,
            fill = tint, border = Colour.Transparent, shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
            w = corners[at + 2],
        )
    }

    /** One corner of a turned picture. The minus on the sine is the y flip. */
    @Suppress("LongParameterList")
    private fun turned(
        x: Float, y: Float,
        pivotX: Float, pivotY: Float,
        turnCos: Float, turnSin: Float,
        u: Float, v: Float,
        tint: Colour,
        kind: Float,
    ) {
        val acrossX = x - pivotX
        val acrossY = y - pivotY
        vertex(
            x = pivotX + acrossX * turnCos + acrossY * turnSin,
            y = pivotY - acrossX * turnSin + acrossY * turnCos,
            u = u, v = v,
            fill = tint,
            border = Colour.Transparent,
            shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
            gradient = kind,
        )
    }

    private fun use(next: DeviceTexture) {
        if (texture != next) {
            flush(BatchBreak.Texture)
            texture = next
        } else if (used + 4 * ShapeVertex.Floats > capacity) {
            flush(BatchBreak.Full)
        }
    }

    @Suppress("LongParameterList")
    private fun quad(
        left: Float, bottom: Float, right: Float, top: Float,
        centreX: Float, centreY: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        fill: Colour, border: Colour, shadow: Colour,
        halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
    ) {
        // Wound anticlockwise from the bottom-left; `v` is the coordinate at the quad's *top*.
        vertex(left, bottom, u, v2, fill, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY)
        vertex(left, top, u, v, fill, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY)
        vertex(right, top, u2, v, fill, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY)
        vertex(right, bottom, u2, v2, fill, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, gradient, gradientX, gradientY)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        fill: Colour, border: Colour, shadow: Colour,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
        w: Float = 1f,
    ) {
        val out = vertices
        var at = used
        out[at++] = x
        out[at++] = y
        out[at++] = w
        at = writeColour(fill, at)
        at = writeColour(border, at)
        at = writeColour(shadow, at)
        out[at++] = u
        out[at++] = v
        out[at++] = localX
        out[at++] = localY
        out[at++] = halfWidth
        out[at++] = halfHeight
        out[at++] = borderWidth
        out[at++] = shadowSpread
        out[at++] = aa
        out[at++] = radii[0]
        out[at++] = radii[1]
        out[at++] = radii[2]
        out[at++] = radii[3]
        out[at++] = gradient
        out[at++] = gradientX
        out[at++] = gradientY
        used = at
    }

    /** Four floats, red first. The toolkit's packed integer undone once, here. */
    private fun writeColour(colour: Colour, at: Int): Int {
        vertices[at] = colour.red / 255f
        vertices[at + 1] = colour.green / 255f
        vertices[at + 2] = colour.blue / 255f
        vertices[at + 3] = colour.alphaFraction
        return at + 4
    }
}

/**
 * Where solid colour is sampled from: a texture and one texel-exact point inside a white block.
 *
 * The glyph atlas's white block when there are fonts, so a panel and its label are one texture and
 * one draw call.
 */
class WhiteSpot(val texture: DeviceTexture, val u: Float, val v: Float)
