package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, through one shader, with nothing but OpenGL underneath.
 *
 * The same idea as the LibGDX backend's batch and deliberately not the same code: a rounded
 * corner, a border and a soft shadow are three ways of asking how far a pixel is from the edge of
 * a rounded box, so they are one shader doing one distance calculation, described per vertex so
 * that two widgets with different corner radii still batch together.
 *
 * The shader below is a copy of the LibGDX backend's, and that is on purpose. These two modules
 * share no code at all — that is what makes the second backend worth having — so what proves they
 * agree is the screenshot goldens, not a shared file.
 *
 * Colours reach the shader as four floats per vertex rather than four bytes squeezed into one.
 * The packed form saves memory an interface does not need and costs a bit trick that goes wrong
 * quietly whenever a particular colour happens to look like a NaN.
 *
 * Every colour arriving here has already had the canvas's alpha stack multiplied into it, so this
 * batch has no idea an alpha stack exists.
 */
class GlShapeBatch(private val maxQuads: Int = 2048) : AutoCloseable {

    private val program = compile()
    private val projectionUniform = GL20.glGetUniformLocation(program, "u_projTrans")
    private val textureUniform = GL20.glGetUniformLocation(program, "u_texture")

    private val vertexBuffer = GL15.glGenBuffers()
    private val indexBuffer = GL15.glGenBuffers()

    private val vertices = FloatArray(maxQuads * 4 * FloatsPerVertex)
    private val upload = BufferUtils.createFloatBuffer(vertices.size)
    private var used = 0

    private var texture = 0
    private var drawing = false

    private val projection = FloatArray(16)

    /** How many times this batch talked to the driver since [begin]. */
    var renderCalls = 0
        private set

    /** Told why, each time [renderCalls] goes up. Null tells nobody. */
    var trace: DrawCallTrace? = null

    init {
        val indices = BufferUtils.createShortBuffer(maxQuads * 6)
        for (quad in 0 until maxQuads) {
            val vertex = (quad * 4).toShort()
            indices.put(vertex)
            indices.put((vertex + 1).toShort())
            indices.put((vertex + 2).toShort())
            indices.put((vertex + 2).toShort())
            indices.put((vertex + 3).toShort())
            indices.put(vertex)
        }
        indices.flip()
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Starts a frame. [projection] is a column-major 4x4, in design coordinates. */
    fun begin(projection: FloatArray) {
        check(!drawing) { "begin() was called twice without an end()" }
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        drawing = true
        renderCalls = 0
        projection.copyInto(this.projection)
        setBlend(BlendMode.SourceOver, premultiplied = false)
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush(BatchBreak.End)
        drawing = false
    }

    /**
     * Points the following quads somewhere else — an offscreen layer, and back again.
     *
     * Flushes first, because whatever is queued was queued for the old one and would otherwise be
     * drawn into the new one at the wrong size and in the wrong place.
     */
    fun projection(projection: FloatArray) {
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        flush(BatchBreak.Layer)
        projection.copyInto(this.projection)
    }

    /**
     * How the following quads are combined with what is already there.
     *
     * The one place this batch's blending is decided, on purpose. Two questions arrive at the same
     * piece of GL state — what the canvas's blend stack currently says, and whether *this*
     * particular quad's colours are already multiplied by their own opacity — and a batch with two
     * writers of one setting would have them undoing each other: a layer composited inside an
     * additive group would land source-over, or a glow after a layer would land as paint.
     *
     * Applied at once rather than remembered and applied at the next quad. A shader effect changes
     * the blend function behind this batch's back, so a remembered "current mode" is stale the
     * moment an effect draws, and the call that put it right would be skipped as a no-op. It is
     * one `glBlendFuncSeparate`, and the flush before it costs nothing when nothing is queued.
     *
     * @param premultiplied true for a layer being drawn back onto the screen, since that is what
     *   the blending above produced when the layer was drawn. Blending it the ordinary way would
     *   multiply by the opacity a second time and edge every soft thing in black.
     * @param reason what the flush this makes is blamed on: a layer's composite switching to
     *   premultiplied is the layer's cost, not a blend anybody asked for.
     */
    fun blend(mode: BlendMode, premultiplied: Boolean, reason: BatchBreak = BatchBreak.Blend) {
        flush(reason)
        setBlend(mode, premultiplied)
    }

    /** The old name, kept: it is exactly [blend] with the ordinary mode. */
    fun premultiplied(premultiplied: Boolean) = blend(BlendMode.SourceOver, premultiplied)

    private fun setBlend(mode: BlendMode, premultiplied: Boolean) {
        // Switched on here rather than once in [begin], so that whoever owns how this batch blends
        // owns whether it blends at all. The other backend needs that — a SpriteBatch turns
        // blending off behind it — and there is no reason for the two to differ.
        GL11.glEnable(GL11.GL_BLEND)
        // Separate on purpose. The alpha half accumulates rather than being interpolated, so what
        // lands in a render target is premultiplied and a game can put it on a quad — or add it,
        // for a hologram — without a shader of its own. On a window it changes nothing: nobody
        // reads the alpha of the thing on screen.
        val destination = if (mode == BlendMode.Additive) GL11.GL_ONE else GL11.GL_ONE_MINUS_SRC_ALPHA
        GL14.glBlendFuncSeparate(
            if (premultiplied) GL11.GL_ONE else GL11.GL_SRC_ALPHA,
            destination,
            GL11.GL_ONE,
            destination,
        )
    }

    /**
     * Hands what is queued to the GPU, blaming [reason] on the trace — but only when something was
     * queued, since an empty flush costs no draw call.
     */
    fun flush(reason: BatchBreak) {
        if (used == 0) return
        val quads = used / (4 * FloatsPerVertex)

        upload.clear()
        upload.put(vertices, 0, used)
        upload.flip()

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUseProgram(program)
        GL20.glUniformMatrix4fv(projectionUniform, false, projection)
        GL20.glUniform1i(textureUniform, 0)

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW)
        Attributes.forEachIndexed { index, attribute ->
            GL20.glEnableVertexAttribArray(index)
            GL20.glVertexAttribPointer(
                index,
                attribute.size,
                GL11.GL_FLOAT,
                false,
                FloatsPerVertex * Float.SIZE_BYTES,
                attribute.offset.toLong() * Float.SIZE_BYTES,
            )
        }

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GL11.glDrawElements(GL11.GL_TRIANGLES, quads * 6, GL11.GL_UNSIGNED_SHORT, 0L)

        Attributes.indices.forEach { GL20.glDisableVertexAttribArray(it) }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)

        renderCalls++
        trace?.record(reason)
        used = 0
    }

    /**
     * One rounded box, with any of a fill, a border and a shadow.
     *
     * @param white the texture solid colour is sampled from — the glyph atlas's white block when
     *   there is one, so a label on a panel is the same texture as the panel.
     * @param aa how wide the softened edge is, in the same units as everything else. Passed in
     *   because a design pixel is not a screen pixel and only the caller knows the scale.
     */
    @Suppress("LongParameterList")
    fun shape(
        white: GlTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        fill: Colour,
        corner: Float,
        border: Colour,
        borderWidth: Float,
        shadow: Colour,
        shadowSpread: Float,
        aa: Float,
    ) = shape(
        white, left, bottom, width, height, fill,
        corner, corner, corner, corner,
        border, borderWidth, shadow, shadowSpread, aa,
    )

    /**
     * The same box with a radius for each corner.
     *
     * The corners are named as they look on screen: [topLeft] is the top-left of the box that
     * appears, whichever way up the coordinates reaching this batch count. Each is held to half the
     * box's shorter side on its own, the rule a single radius has always had.
     */
    @Suppress("LongParameterList")
    fun shape(
        white: GlTexture,
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
        val u = (white.u + white.u2) / 2f
        val v = (white.v + white.v2) / 2f

        use(white.name)
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
     * One rounded box, filled with a gradient between [start] and [end].
     *
     * The same quad and the same distance field as [shape], so it batches with every flat panel on
     * screen: the gradient is described per vertex, never as a uniform. The end colour rides in the
     * border colour's slot, which a filled box has no other use for.
     *
     * @param radial true for a gradient outwards from the middle; false for a straight one.
     * @param axisX how far along a straight gradient one unit across moves it, already divided by
     *   its length. Ignored when [radial].
     * @param axisY the same, downwards — in *this* batch's coordinates, so already flipped.
     */
    @Suppress("LongParameterList")
    fun gradient(
        white: GlTexture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        start: Colour,
        end: Colour,
        radial: Boolean,
        axisX: Float,
        axisY: Float,
        corner: Float,
        aa: Float,
    ) = gradient(white, left, bottom, width, height, start, end, radial, axisX, axisY, corner, corner, corner, corner, aa)

    /** The same gradient box with a radius for each corner, named as they look on screen. */
    @Suppress("LongParameterList")
    fun gradient(
        white: GlTexture,
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
        val u = (white.u + white.u2) / 2f
        val v = (white.v + white.v2) / 2f

        use(white.name)
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
            gradient = if (radial) Radial else Linear,
            gradientX = axisX,
            gradientY = axisY,
        )
    }

    /**
     * The four radii of the box being written, top-left then clockwise.
     *
     * One array held for the batch's life rather than four more parameters on every vertex call
     * below, and rather than an object per box. Read straight after it is filled; nothing keeps it.
     */
    private val radii = FloatArray(4)

    /** What a picture, a glyph or a fan vertex carries: no corners, since it has no shape. */
    private val noRadii = FloatArray(4)

    /**
     * A triangle fan, in whatever coordinates the caller has already flipped.
     *
     * The batch draws quads and nothing else, so each quad here carries *two* of the fan's
     * triangles: the quad's own winding — 0,1,2 then 2,3,0 — is already hub, a, b and then b, c,
     * hub. An odd point at the end repeats, which draws a triangle of no area.
     *
     * [points] is x, y pairs with the hub first, exactly as it reached the canvas.
     */
    fun fan(white: GlTexture, points: FloatArray, colour: Colour) {
        if (points.size < 6) return
        val u = (white.u + white.u2) / 2f
        val v = (white.v + white.v2) / 2f
        val hubX = points[0]
        val hubY = points[1]

        var at = 2
        while (at + 3 < points.size) {
            val cx = if (at + 5 < points.size) points[at + 4] else points[at + 2]
            val cy = if (at + 5 < points.size) points[at + 5] else points[at + 3]
            use(white.name)
            fanQuad(
                hubX, hubY,
                points[at], points[at + 1],
                points[at + 2], points[at + 3],
                cx, cy,
                u, v, colour,
            )
            at += 4
        }
    }

    @Suppress("LongParameterList")
    private fun fanQuad(
        hubX: Float, hubY: Float,
        aX: Float, aY: Float,
        bX: Float, bY: Float,
        cX: Float, cY: Float,
        u: Float, v: Float,
        colour: Colour,
    ) {
        flat(hubX, hubY, u, v, colour)
        flat(aX, aY, u, v, colour)
        flat(bX, bY, u, v, colour)
        flat(cX, cY, u, v, colour)
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

    /** A picture, or a glyph. No shape and no softened edge — whatever the texture says. */
    @Suppress("LongParameterList")
    fun textured(
        name: Int,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(name)
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
        )
    }

    /**
     * The same picture, turned round a pivot.
     *
     * Four corners worked out here and written as an ordinary quad: the mesh does not care whether
     * they happen to be axis aligned, the shader has the shape maths switched off for a picture
     * either way, and no GL state changes. So a turned picture batches with every other quad from
     * the same texture — a sunburst of fourteen rays is one draw call, not fourteen.
     *
     * [degrees] turns it clockwise as the toolkit's y-down coordinates see it. Everything reaching
     * this batch has already been flipped the other way up, so the sign here looks back to front
     * on purpose — that is the whole of the difference, in one place.
     *
     * [pivotX] and [pivotY] are a point, in the same flipped coordinates as [left] and [bottom].
     */
    @Suppress("LongParameterList")
    fun textured(
        name: Int,
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
    ) {
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        val right = left + width
        val top = bottom + height

        use(name)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        turned(left, bottom, pivotX, pivotY, turnCos, turnSin, u, v2, tint)
        turned(left, top, pivotX, pivotY, turnCos, turnSin, u, v, tint)
        turned(right, top, pivotX, pivotY, turnCos, turnSin, u2, v, tint)
        turned(right, bottom, pivotX, pivotY, turnCos, turnSin, u2, v2, tint)
    }

    /**
     * The same picture, on four corners somebody else worked out.
     *
     * What a slanted layer is written as. [corners] is top-left, top-right, bottom-right,
     * bottom-left of the picture, each an x then a y in this batch's own y-up coordinates — the
     * caller has done the flip. Written as an ordinary quad for the reasons the turned one is, so
     * it batches with everything else from the same texture.
     */
    @Suppress("LongParameterList")
    fun textured(
        name: Int,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(name)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        flat(corners[6], corners[7], u, v2, tint)
        flat(corners[0], corners[1], u, v, tint)
        flat(corners[2], corners[3], u2, v, tint)
        flat(corners[4], corners[5], u2, v2, tint)
    }

    /**
     * The same picture, on four corners that each carry a depth.
     *
     * What a tilted layer is written as. [corners] is twelve numbers — top-left, top-right,
     * bottom-right, bottom-left — each an x, a y and a w *before* the divide, in this batch's y-up
     * coordinates, so a corner seen at (x / w, y / w). Handing the GPU the undivided numbers is the
     * whole trick: it divides per pixel, so the picture does not bend along the diagonal the way
     * it would across two flat triangles. Still an ordinary quad, so it batches with the rest.
     */
    @Suppress("LongParameterList")
    fun projected(
        name: Int,
        corners: FloatArray,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        tint: Colour,
    ) {
        use(name)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        deep(corners, 9, u, v2, tint)
        deep(corners, 0, u, v, tint)
        deep(corners, 3, u2, v, tint)
        deep(corners, 6, u2, v2, tint)
    }

    /** One corner of a [projected] picture, read from [corners] at [at]. */
    private fun deep(corners: FloatArray, at: Int, u: Float, v: Float, tint: Colour) {
        vertex(
            x = corners[at], y = corners[at + 1], u = u, v = v,
            fill = tint, border = Colour.Transparent, shadow = Colour.Transparent,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
            w = corners[at + 2],
        )
    }

    /**
     * One corner of a turned picture.
     *
     * The minus on the sine is the y flip: this batch counts y upwards and the toolkit counts it
     * downwards, so turning clockwise up here means turning anticlockwise down there.
     */
    @Suppress("LongParameterList")
    private fun turned(
        x: Float, y: Float,
        pivotX: Float, pivotY: Float,
        turnCos: Float, turnSin: Float,
        u: Float, v: Float,
        tint: Colour,
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
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
        )
    }

    /**
     * Four corners of a picture, each with its own place, texture coordinate and colour.
     *
     * What a picture cut to a shape is drawn with — see `GlCanvas.cutLayer`. The corners go in the
     * order the quad is wound, 0, 1, 2 then 2, 3, 0, in coordinates already flipped. No shape
     * maths: the colour at each corner is what softens the edge, and the texture is the picture.
     */
    @Suppress("LongParameterList")
    fun corners(
        name: Int,
        ax: Float, ay: Float, au: Float, av: Float, aColour: Colour,
        bx: Float, by: Float, bu: Float, bv: Float, bColour: Colour,
        cx: Float, cy: Float, cu: Float, cv: Float, cColour: Colour,
        dx: Float, dy: Float, du: Float, dv: Float, dColour: Colour,
    ) {
        use(name)
        flat(ax, ay, au, av, aColour)
        flat(bx, by, bu, bv, bColour)
        flat(cx, cy, cu, cv, cColour)
        flat(dx, dy, du, dv, dColour)
    }

    private fun use(next: Int) {
        if (texture != next) {
            flush(BatchBreak.Texture)
            texture = next
        } else if (used + 4 * FloatsPerVertex > vertices.size) {
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
        // The quad is wound anticlockwise from its bottom-left, and `v` is the coordinate at the
        // quad's *top*. Every caller here counts y downwards and flips once on the way in.
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
        var at = used
        vertices[at++] = x
        vertices[at++] = y
        vertices[at++] = w
        at = writeColour(fill, at)
        at = writeColour(border, at)
        at = writeColour(shadow, at)
        vertices[at++] = u
        vertices[at++] = v
        vertices[at++] = localX
        vertices[at++] = localY
        vertices[at++] = halfWidth
        vertices[at++] = halfHeight
        vertices[at++] = borderWidth
        vertices[at++] = shadowSpread
        vertices[at++] = aa
        vertices[at++] = radii[0]
        vertices[at++] = radii[1]
        vertices[at++] = radii[2]
        vertices[at++] = radii[3]
        vertices[at++] = gradient
        vertices[at++] = gradientX
        vertices[at++] = gradientY
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

    override fun close() {
        GL20.glDeleteProgram(program)
        GL15.glDeleteBuffers(vertexBuffer)
        GL15.glDeleteBuffers(indexBuffer)
    }

    private fun compile(): Int {
        val vertex = shader(GL20.GL_VERTEX_SHADER, Vertex)
        val fragment = shader(GL20.GL_FRAGMENT_SHADER, Fragment)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        // Bound rather than looked up, so the vertex layout below is the one the shader sees even
        // on a driver that would have ordered the attributes differently.
        Attributes.forEachIndexed { index, attribute -> GL20.glBindAttribLocation(program, index, attribute.name) }
        GL20.glLinkProgram(program)
        check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
            "the interface shader would not link:\n${GL20.glGetProgramInfoLog(program)}"
        }
        // The program holds them now.
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
            val what = if (type == GL20.GL_VERTEX_SHADER) "vertex" else "fragment"
            "the interface's $what shader would not compile:\n${GL20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private class Attribute(val name: String, val size: Int, val offset: Int)

    private companion object {

        val Attributes = listOf(
            // x, y and a w that is one for everything except a tilted picture — see `projected`.
            Attribute("a_position", 3, 0),
            Attribute("a_color", 4, 3),
            Attribute("a_borderColor", 4, 7),
            Attribute("a_shadowColor", 4, 11),
            Attribute("a_texCoord0", 2, 15),
            Attribute("a_local", 2, 17),
            Attribute("a_halfSize", 2, 19),
            Attribute("a_shape", 3, 21),
            Attribute("a_radii", 4, 24),
            Attribute("a_gradient", 3, 28),
        )

        val FloatsPerVertex = Attributes.sumOf { it.size }

        /** What the first gradient float says: a straight gradient, or one outwards from the middle. */
        const val Linear = 1f
        const val Radial = 2f

        val Vertex = """
            attribute vec3 a_position;
            attribute vec4 a_color;
            attribute vec4 a_borderColor;
            attribute vec4 a_shadowColor;
            attribute vec2 a_texCoord0;
            attribute vec2 a_local;
            attribute vec2 a_halfSize;
            attribute vec3 a_shape;
            attribute vec4 a_radii;
            attribute vec3 a_gradient;

            uniform mat4 u_projTrans;

            varying vec4 v_color;
            varying vec4 v_borderColor;
            varying vec4 v_shadowColor;
            varying vec2 v_texCoord;
            varying vec2 v_local;
            varying vec2 v_halfSize;
            varying vec3 v_shape;
            varying vec4 v_radii;
            varying vec3 v_gradient;

            void main() {
                v_color = a_color;
                v_borderColor = a_borderColor;
                v_shadowColor = a_shadowColor;
                v_texCoord = a_texCoord0;
                v_local = a_local;
                v_halfSize = a_halfSize;
                v_shape = a_shape;
                v_radii = a_radii;
                v_gradient = a_gradient;
                // The third number is w. The projection is flat, so scaling a position by w moves
                // nothing on the screen — but the GPU then interpolates everything across the
                // triangle divided by w, which is what makes a tilted picture perspective-correct.
                gl_Position = u_projTrans * vec4(a_position.xy, 0.0, a_position.z);
            }
        """.trimIndent()

        val Fragment = """
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform sampler2D u_texture;

            varying vec4 v_color;
            varying vec4 v_borderColor;
            varying vec4 v_shadowColor;
            varying vec2 v_texCoord;
            varying vec2 v_local;
            varying vec2 v_halfSize;
            varying vec3 v_shape;
            varying vec4 v_radii;
            varying vec3 v_gradient;

            // Distance from a point to the edge of a rounded box: negative inside, positive out.
            // One function answers all three questions this shader exists to answer.
            float roundedBox(vec2 point, vec2 extent, float radius) {
                vec2 q = abs(point) - extent + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            // Which corner's radius a point is under: the one in the same quarter of the box. The
            // radii are top-left, then clockwise, and y counts upwards here, so the top half is
            // the half with a positive y. A box whose corners agree gets the same answer in all
            // four quarters, which is exactly the single radius it always had.
            float cornerRadius(vec2 point, vec4 radii) {
                if (point.y >= 0.0) return point.x < 0.0 ? radii.x : radii.y;
                return point.x < 0.0 ? radii.w : radii.z;
            }

            vec4 over(vec4 top, vec4 bottom) {
                float a = top.a + bottom.a * (1.0 - top.a);
                if (a <= 0.0) return vec4(0.0);
                return vec4((top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / a, a);
            }

            // Two colours mixed weighted by their own opacity, so fading to transparent keeps the
            // hue rather than darkening towards black. Brush.between is the same sum on the CPU.
            vec4 between(vec4 from, vec4 to, float t) {
                float a = mix(from.a, to.a, t);
                if (a <= 0.0) return vec4(0.0);
                return vec4(mix(from.rgb * from.a, to.rgb * to.a, t) / a, a);
            }

            void main() {
                vec4 sampled = texture2D(u_texture, v_texCoord);
                float aa = v_shape.z;

                // A picture or a glyph: no shape to work out, just the texture.
                if (aa <= 0.0) {
                    gl_FragColor = v_color * sampled;
                    return;
                }

                float radius = cornerRadius(v_local, v_radii);
                float borderWidth = v_shape.x;
                float spread = v_shape.y;

                float distance = roundedBox(v_local, v_halfSize, radius);
                float coverage = 1.0 - smoothstep(-aa, aa, distance);

                // A gradient: the end colour is in the border's slot, and how far along this pixel
                // is comes from its offset from the middle of the box. A box with a gradient has
                // no border, so the slot is free.
                vec4 fill = v_color;
                if (v_gradient.x > 0.5) {
                    float along = v_gradient.x < 1.5
                        ? dot(v_local, v_gradient.yz) + 0.5
                        : length(v_local / max(v_halfSize, vec2(0.0001)));
                    fill = between(v_color, v_borderColor, clamp(along, 0.0, 1.0));
                }

                vec4 result = vec4(fill.rgb, fill.a * coverage) * vec4(sampled.rgb, sampled.a);

                if (borderWidth > 0.0) {
                    float inside = 1.0 - smoothstep(-aa, aa, distance + borderWidth);
                    float band = max(coverage - inside, 0.0);
                    result = over(vec4(v_borderColor.rgb, v_borderColor.a * band), result);
                }

                if (spread > 0.0) {
                    // Outside the shape only, falling off across the spread.
                    float shade = (1.0 - smoothstep(0.0, spread, max(distance, 0.0)));
                    result = over(result, vec4(v_shadowColor.rgb, v_shadowColor.a * shade));
                }

                gl_FragColor = result;
            }
        """.trimIndent()
    }
}
