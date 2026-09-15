package dev.wildware.composegl.webgl

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint16Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.WebGLShader
import org.khronos.webgl.WebGLTexture
import org.khronos.webgl.set
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, through one shader, with nothing but WebGL underneath.
 *
 * The browser's copy of the raw OpenGL backend's batch, and deliberately a copy rather than a shared
 * file: the backends share no code, so what proves they agree is the goldens. A rounded corner, a
 * border and a soft shadow are one distance calculation in one shader, described per vertex so two
 * widgets with different corners still batch together.
 *
 * The vertices are written straight into a [Float32Array] rather than into a Kotlin array that is
 * copied across at the flush. On WebAssembly every copy between the two is a call per number, so
 * writing once, where the driver will read it, is the cheaper of the two ways to be correct.
 *
 * Every colour arriving here has already had the canvas's alpha stack multiplied into it, so this
 * batch has no idea an alpha stack exists.
 */
class WebGlShapeBatch(private val gl: GL, private val maxQuads: Int = 2048) : AutoCloseable {

    private val program = compile()
    private val projectionUniform = gl.getUniformLocation(program, "u_projTrans")
    private val textureUniform = gl.getUniformLocation(program, "u_texture")

    private val vertexBuffer: WebGLBuffer = checkNotNull(gl.createBuffer()) { LostContext }
    private val indexBuffer: WebGLBuffer = checkNotNull(gl.createBuffer()) { LostContext }

    private val vertices = Float32Array(maxQuads * 4 * FloatsPerVertex)
    private var used = 0

    private var texture: WebGLTexture? = null
    private var drawing = false

    private val projection = Float32Array(16)

    /** How many times this batch talked to the driver since [begin]. */
    var renderCalls = 0
        private set

    /** Told why, each time [renderCalls] goes up. Null tells nobody. */
    var trace: DrawCallTrace? = null

    init {
        val indices = Uint16Array(maxQuads * 6)
        for (quad in 0 until maxQuads) {
            val vertex = (quad * 4).toShort()
            val at = quad * 6
            indices[at] = vertex
            indices[at + 1] = (vertex + 1).toShort()
            indices[at + 2] = (vertex + 2).toShort()
            indices[at + 3] = (vertex + 2).toShort()
            indices[at + 4] = (vertex + 3).toShort()
            indices[at + 5] = vertex
        }
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, indices, GL.STATIC_DRAW)
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, null)
    }

    /** Starts a frame. [projection] is a column-major 4x4, in design coordinates. */
    fun begin(projection: FloatArray) {
        check(!drawing) { "begin() was called twice without an end()" }
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        drawing = true
        renderCalls = 0
        setProjection(projection)
        setBlend(BlendMode.SourceOver, premultiplied = false)
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush(BatchBreak.End)
        drawing = false
    }

    /** Points the following quads somewhere else — an offscreen layer, and back. Flushes first. */
    fun projection(projection: FloatArray) {
        require(projection.size == 16) { "a projection is sixteen floats, not ${projection.size}" }
        flush(BatchBreak.Layer)
        setProjection(projection)
    }

    private fun setProjection(from: FloatArray) {
        for (at in 0 until 16) projection[at] = from[at]
    }

    /**
     * How the following quads are combined with what is already there.
     *
     * Applied at once rather than remembered, for the reason the desktop batch gives: a shader
     * effect changes the blend function behind this batch's back, so a remembered mode goes stale.
     *
     * @param premultiplied true for a layer being drawn back, since its colours already carry their
     *   own opacity.
     * @param reason what the flush this makes is blamed on: a layer's composite switching to
     *   premultiplied is the layer's cost, not a blend anybody asked for.
     */
    fun blend(mode: BlendMode, premultiplied: Boolean, reason: BatchBreak = BatchBreak.Blend) {
        flush(reason)
        setBlend(mode, premultiplied)
    }

    private fun setBlend(mode: BlendMode, premultiplied: Boolean) {
        gl.enable(GL.BLEND)
        // Separate, so what lands in a layer is premultiplied — exactly as on the desktop.
        val destination = if (mode == BlendMode.Additive) GL.ONE else GL.ONE_MINUS_SRC_ALPHA
        gl.blendFuncSeparate(if (premultiplied) GL.ONE else GL.SRC_ALPHA, destination, GL.ONE, destination)
    }

    /**
     * Hands what is queued to the GPU, blaming [reason] on the trace — but only when something was
     * queued, since an empty flush costs no draw call.
     */
    fun flush(reason: BatchBreak) {
        if (used == 0) return
        val quads = used / (4 * FloatsPerVertex)

        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D, texture)
        gl.useProgram(program)
        gl.uniformMatrix4fv(projectionUniform, false, projection)
        gl.uniform1i(textureUniform, 0)

        gl.bindBuffer(GL.ARRAY_BUFFER, vertexBuffer)
        // Only the part that was written: a quarter-full batch uploads a quarter of the buffer.
        gl.bufferData(GL.ARRAY_BUFFER, vertices.subarray(0, used), GL.STREAM_DRAW)
        Attributes.forEachIndexed { index, attribute ->
            gl.enableVertexAttribArray(index)
            gl.vertexAttribPointer(index, attribute.size, GL.FLOAT, false, FloatsPerVertex * 4, attribute.offset * 4)
        }

        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, indexBuffer)
        gl.drawElements(GL.TRIANGLES, quads * 6, GL.UNSIGNED_SHORT, 0)

        Attributes.indices.forEach { gl.disableVertexAttribArray(it) }
        gl.bindBuffer(GL.ARRAY_BUFFER, null)
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, null)

        renderCalls++
        trace?.record(reason)
        used = 0
    }

    /** One rounded box, with any of a fill, a border and a shadow, a radius for each corner. */
    @Suppress("LongParameterList")
    fun shape(
        white: WebGlTexture,
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
        holdRadii(halfWidth, halfHeight, topLeft, topRight, bottomRight, bottomLeft)
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
            borderWidth = borderWidth,
            shadowSpread = shadowSpread,
            aa = aa,
        )
    }

    /**
     * One rounded box filled with a gradient. The end colour rides in the border's slot, which a
     * filled box has no other use for, so it batches with every flat panel on screen.
     */
    @Suppress("LongParameterList")
    fun gradient(
        white: WebGlTexture,
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
        holdRadii(halfWidth, halfHeight, topLeft, topRight, bottomRight, bottomLeft)
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
            borderWidth = 0f,
            shadowSpread = 0f,
            aa = aa,
            gradient = if (radial) Radial else Linear,
            gradientX = axisX,
            gradientY = axisY,
        )
    }

    /** Each corner held to half the box's shorter side, the rule a single radius has always had. */
    @Suppress("LongParameterList")
    private fun holdRadii(halfWidth: Float, halfHeight: Float, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        val most = minOf(halfWidth, halfHeight).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
    }

    /** The four radii of the box being written, top-left then clockwise. Read straight after. */
    private val radii = FloatArray(4)

    /** What a picture, a glyph or a fan vertex carries: no corners, since it has no shape. */
    private val noRadii = FloatArray(4)

    /**
     * A triangle fan, in coordinates the caller has already flipped. Each quad carries two of the
     * fan's triangles — hub, a, b and then b, c, hub — and an odd point at the end repeats.
     */
    fun fan(white: WebGlTexture, points: FloatArray, colour: Colour) {
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
            flat(hubX, hubY, u, v, colour)
            flat(points[at], points[at + 1], u, v, colour)
            flat(points[at + 2], points[at + 3], u, v, colour)
            flat(cx, cy, u, v, colour)
            at += 4
        }
    }

    /** One vertex with the distance field switched off: solid colour, or whatever the texture says. */
    private fun flat(x: Float, y: Float, u: Float, v: Float, colour: Colour) {
        vertex(
            x, y, u, v, colour, Colour.Transparent, Colour.Transparent,
            0f, 0f, 0f, 0f, noRadii, 0f, 0f, 0f,
        )
    }

    /** A picture, or a glyph. No shape and no softened edge. */
    @Suppress("LongParameterList")
    fun textured(
        name: WebGLTexture,
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
        val right = left + width
        val top = bottom + height
        // Anticlockwise from the bottom-left, and `v` is the coordinate at the quad's top.
        flat(left, bottom, u, v2, tint)
        flat(left, top, u, v, tint)
        flat(right, top, u2, v, tint)
        flat(right, bottom, u2, v2, tint)
    }

    /**
     * The same picture, turned round a pivot given in the same flipped coordinates. The minus on the
     * sine is the y flip: clockwise on the toolkit's screen is anticlockwise up here.
     */
    @Suppress("LongParameterList")
    fun textured(
        name: WebGLTexture,
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

        fun corner(x: Float, y: Float, cu: Float, cv: Float) {
            val acrossX = x - pivotX
            val acrossY = y - pivotY
            flat(pivotX + acrossX * turnCos + acrossY * turnSin, pivotY - acrossX * turnSin + acrossY * turnCos, cu, cv, tint)
        }

        use(name)
        corner(left, bottom, u, v2)
        corner(left, top, u, v)
        corner(right, top, u2, v)
        corner(right, bottom, u2, v2)
    }

    /** The same picture on four corners somebody else worked out: top-left, top-right, bottom-right, bottom-left. */
    fun textured(name: WebGLTexture, corners: FloatArray, u: Float, v: Float, u2: Float, v2: Float, tint: Colour) {
        use(name)
        flat(corners[6], corners[7], u, v2, tint)
        flat(corners[0], corners[1], u, v, tint)
        flat(corners[2], corners[3], u2, v, tint)
        flat(corners[4], corners[5], u2, v2, tint)
    }

    /** Four corners of a picture, each with its own place, texture coordinate and colour, in winding order. */
    @Suppress("LongParameterList")
    fun corners(
        name: WebGLTexture,
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

    /**
     * The same picture, on four corners that each carry a depth.
     *
     * What a tilted layer is written as. [corners] is twelve numbers — top-left, top-right,
     * bottom-right, bottom-left — each an x, a y and a w *before* the divide, in this batch's y-up
     * coordinates. Handing the GPU the undivided numbers lets it divide per pixel, so the picture
     * does not bend along the diagonal. A corner behind the camera has a negative w, and the GPU
     * clips it away rather than drawing the picture inside out.
     */
    @Suppress("LongParameterList")
    fun projected(name: WebGLTexture, corners: FloatArray, u: Float, v: Float, u2: Float, v2: Float, tint: Colour) {
        require(corners.size == 12) { "four deep corners are twelve numbers, not ${corners.size}" }
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
            corners[at], corners[at + 1], u, v, tint, Colour.Transparent, Colour.Transparent,
            0f, 0f, 0f, 0f, noRadii, 0f, 0f, 0f,
            w = corners[at + 2],
        )
    }

    private fun use(next: WebGLTexture) {
        if (texture != next) {
            flush(BatchBreak.Texture)
            texture = next
        } else if (used + 4 * FloatsPerVertex > maxQuads * 4 * FloatsPerVertex) {
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
        borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
    ) {
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
        gl.deleteProgram(program)
        gl.deleteBuffer(vertexBuffer)
        gl.deleteBuffer(indexBuffer)
    }

    private fun compile(): WebGLProgram {
        val vertex = shader(gl, GL.VERTEX_SHADER, Vertex, "interface")
        val fragment = shader(gl, GL.FRAGMENT_SHADER, Fragment, "interface")
        val program = checkNotNull(gl.createProgram()) { LostContext }
        gl.attachShader(program, vertex)
        gl.attachShader(program, fragment)
        // Bound rather than looked up, so the layout below is the one the shader sees.
        Attributes.forEachIndexed { index, attribute -> gl.bindAttribLocation(program, index, attribute.name) }
        gl.linkProgram(program)
        check(linked(gl, program)) { "the interface shader would not link:\n${gl.getProgramInfoLog(program)}" }
        gl.deleteShader(vertex)
        gl.deleteShader(fragment)
        return program
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
                // nothing on the screen, but the GPU then interpolates across the triangle divided
                // by w, which is what makes a tilted picture perspective-correct.
                gl_Position = u_projTrans * vec4(a_position.xy, 0.0, a_position.z);
            }
        """.trimIndent()

        /**
         * The desktop shader, word for word, apart from the precision line.
         *
         * High precision where the device has it: a phone's medium precision runs out long before a
         * panel's width does, and the distance field turns to steps across a wide box.
         */
        val Fragment = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
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

            float roundedBox(vec2 point, vec2 extent, float radius) {
                vec2 q = abs(point) - extent + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float cornerRadius(vec2 point, vec4 radii) {
                if (point.y >= 0.0) return point.x < 0.0 ? radii.x : radii.y;
                return point.x < 0.0 ? radii.w : radii.z;
            }

            vec4 over(vec4 top, vec4 bottom) {
                float a = top.a + bottom.a * (1.0 - top.a);
                if (a <= 0.0) return vec4(0.0);
                return vec4((top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / a, a);
            }

            vec4 between(vec4 from, vec4 to, float t) {
                float a = mix(from.a, to.a, t);
                if (a <= 0.0) return vec4(0.0);
                return vec4(mix(from.rgb * from.a, to.rgb * to.a, t) / a, a);
            }

            void main() {
                vec4 sampled = texture2D(u_texture, v_texCoord);
                float aa = v_shape.z;

                if (aa <= 0.0) {
                    gl_FragColor = v_color * sampled;
                    return;
                }

                float radius = cornerRadius(v_local, v_radii);
                float borderWidth = v_shape.x;
                float spread = v_shape.y;

                float distance = roundedBox(v_local, v_halfSize, radius);
                float coverage = 1.0 - smoothstep(-aa, aa, distance);

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
                    float shade = (1.0 - smoothstep(0.0, spread, max(distance, 0.0)));
                    result = over(result, vec4(v_shadowColor.rgb, v_shadowColor.a * shade));
                }

                gl_FragColor = result;
            }
        """.trimIndent()
    }
}

/** What every "WebGL would not make one" says: a context that has been lost hands out nulls. */
internal const val LostContext = "WebGL handed back nothing; the context has probably been lost"

/** Compiles one shader, and says which and why when it will not. */
internal fun shader(gl: GL, type: Int, source: String, name: String): WebGLShader {
    val shader = checkNotNull(gl.createShader(type)) { LostContext }
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    if (compiled(gl, shader)) return shader
    val log = gl.getShaderInfoLog(shader)
    gl.deleteShader(shader)
    val what = if (type == GL.VERTEX_SHADER) "vertex" else "fragment"
    throw IllegalArgumentException("the shader \"$name\" would not compile ($what):\n$log")
}

/** `getShaderParameter` answers with a JavaScript boolean, which is simplest read on that side. */
private fun compiled(gl: GL, shader: WebGLShader): Boolean =
    js("gl.getShaderParameter(shader, gl.COMPILE_STATUS) === true")

internal fun linked(gl: GL, program: WebGLProgram): Boolean =
    js("gl.getProgramParameter(program, gl.LINK_STATUS) === true")
