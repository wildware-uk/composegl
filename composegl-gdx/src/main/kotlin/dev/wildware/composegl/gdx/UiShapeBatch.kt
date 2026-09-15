package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.ui.graphics.BlendMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, through one shader.
 *
 * A rounded corner, a border and a soft shadow are three ways of asking the same question — how
 * far is this pixel from the edge of a rounded box — so they are one shader doing one distance
 * calculation, and a panel with all three is a handful of quads that batch together with every
 * other panel on screen.
 *
 * The shape is described per vertex rather than per uniform. That is the whole point: a uniform
 * would mean flushing between two widgets that happen to want different corner radii, and an
 * interface is nothing but widgets that want different corner radii.
 *
 * Text and pictures come through the same batch with the shape switched off. Give it the white
 * texel out of the glyph atlas and a label on a panel costs nothing at all, because the panel and
 * the letters are then the same texture.
 *
 * @param white where solid colour is sampled from. Pass [GdxAtlas.white] to share a texture with
 *   the fonts; leave it out and the batch keeps a one-pixel texture of its own, which works and
 *   costs a draw call every time the interface alternates between a box and a word.
 */
class UiShapeBatch(
    private val maxQuads: Int = 2048,
    private val white: TextureRegion? = null,
) : Disposable {

    private val mesh = Mesh(
        Mesh.VertexDataType.VertexArray,
        false,
        maxQuads * 4,
        maxQuads * 6,
        VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
        VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true, "a_color"),
        VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true, "a_borderColor"),
        VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true, "a_shadowColor"),
        VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
        VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_local"),
        VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_halfSize"),
        VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_shape"),
        VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_radii"),
    ).also { it.setIndices(quadIndices(maxQuads)) }

    private val shader = compile()

    private val vertices = FloatArray(maxQuads * 4 * FLOATS_PER_VERTEX)
    private var used = 0
    private var texture: Texture? = null
    private var drawing = false

    private val projection = Matrix4()

    /** How many times this batch talked to the driver since [begin]. */
    var renderCalls = 0
        private set

    fun begin(projection: Matrix4) {
        check(!drawing) { "begin() was called twice without an end()" }
        drawing = true
        renderCalls = 0
        this.projection.set(projection)
        setBlend(BlendMode.SourceOver, premultiplied = false)
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush()
        drawing = false
    }

    /**
     * Points the following quads somewhere else — an offscreen layer, and back again.
     *
     * Flushes first, because whatever is queued was queued for the old one and would otherwise be
     * drawn into the new one at the wrong size and in the wrong place.
     */
    fun projection(projection: Matrix4) {
        flush()
        this.projection.set(projection)
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
     */
    fun blend(mode: BlendMode, premultiplied: Boolean) {
        flush()
        setBlend(mode, premultiplied)
    }

    /** The old name, kept: it is exactly [blend] with the ordinary mode. */
    fun premultiplied(premultiplied: Boolean) = blend(BlendMode.SourceOver, premultiplied)

    private fun setBlend(mode: BlendMode, premultiplied: Boolean) {
        // Switched on here rather than once in [begin], because somebody else switches it off:
        // SpriteBatch.end() disables blending on its way out, so a frame that went through
        // [UiCanvas.raw] would draw everything after it flat and opaque. Whoever owns how this
        // batch blends owns whether it blends at all.
        Gdx.gl.glEnable(GL20.GL_BLEND)
        // Separate on purpose: the alpha half accumulates rather than being interpolated, so what
        // lands in a framebuffer a game is using as a texture is premultiplied. On a window it
        // changes nothing.
        val destination = if (mode == BlendMode.Additive) GL20.GL_ONE else GL20.GL_ONE_MINUS_SRC_ALPHA
        Gdx.gl.glBlendFuncSeparate(
            if (premultiplied) GL20.GL_ONE else GL20.GL_SRC_ALPHA,
            destination,
            GL20.GL_ONE,
            destination,
        )
    }

    fun flush() {
        if (used == 0) return
        val quads = used / (4 * FLOATS_PER_VERTEX)

        texture?.bind()
        shader.bind()
        shader.setUniformMatrix("u_projTrans", projection)
        shader.setUniformi("u_texture", 0)
        mesh.setVertices(vertices, 0, used)
        mesh.render(shader, GL20.GL_TRIANGLES, 0, quads * 6)

        renderCalls++
        used = 0
    }

    /**
     * One rounded box, with any of a fill, a border and a shadow.
     *
     * @param aa how wide the softened edge is, in the same units as everything else. It is passed
     *   in rather than worked out from a derivative because a design pixel is not a screen pixel:
     *   the caller knows the scale and the shader would have to guess.
     */
    @Suppress("LongParameterList")
    fun shape(
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        fill: Float,
        corner: Float,
        border: Float,
        borderWidth: Float,
        shadow: Float,
        shadowSpread: Float,
        aa: Float,
    ) = shape(
        left, bottom, width, height, fill,
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
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        fill: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
        border: Float,
        borderWidth: Float,
        shadow: Float,
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

        // The atlas's white texel if there is one, and the batch's own if there is not. Resolved
        // per call rather than at construction, because the atlas gets its texture when the first
        // font is registered, which may be after this batch was made.
        val source = white?.takeIf { it.texture != null }
        val u = source?.let { (it.u + it.u2) / 2f } ?: 0.5f
        val v = source?.let { (it.v + it.v2) / 2f } ?: 0.5f

        use(source?.texture ?: fallbackWhite())
        quad(
            left = left - margin,
            bottom = bottom - margin,
            right = left + width + margin,
            top = bottom + height + margin,
            centreX = left + halfWidth,
            centreY = bottom + halfHeight,
            u = u, v = v, u2 = u, v2 = v,
            colour = fill,
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
    fun fan(points: FloatArray, colour: Float) {
        if (points.size < 6) return
        val source = white?.takeIf { it.texture != null }
        val u = source?.let { (it.u + it.u2) / 2f } ?: 0.5f
        val v = source?.let { (it.v + it.v2) / 2f } ?: 0.5f
        val texture = source?.texture ?: fallbackWhite()
        val hubX = points[0]
        val hubY = points[1]

        var at = 2
        while (at + 3 < points.size) {
            val cx = if (at + 5 < points.size) points[at + 4] else points[at + 2]
            val cy = if (at + 5 < points.size) points[at + 5] else points[at + 3]
            use(texture)
            flat(hubX, hubY, u, v, colour)
            flat(points[at], points[at + 1], u, v, colour)
            flat(points[at + 2], points[at + 3], u, v, colour)
            flat(cx, cy, u, v, colour)
            at += 4
        }
    }

    /** One vertex of solid colour, with the distance field switched off. */
    private fun flat(x: Float, y: Float, u: Float, v: Float, colour: Float) {
        vertex(
            x = x, y = y, u = u, v = v,
            colour = colour, border = 0f, shadow = 0f,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            aa = 0f,
        )
    }

    /** A picture, or a glyph. No shape, no softened edge — whatever the texture says. */
    @Suppress("LongParameterList")
    fun textured(
        texture: Texture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        u: Float,
        v: Float,
        u2: Float,
        v2: Float,
        colour: Float,
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
            colour = colour,
            border = 0f,
            shadow = 0f,
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
        texture: Texture,
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
        colour: Float,
    ) {
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        val right = left + width
        val top = bottom + height

        use(texture)
        // Anticlockwise from the bottom-left, exactly as `quad` winds it, so the indices fit.
        turned(left, bottom, pivotX, pivotY, turnCos, turnSin, u, v2, colour)
        turned(left, top, pivotX, pivotY, turnCos, turnSin, u, v, colour)
        turned(right, top, pivotX, pivotY, turnCos, turnSin, u2, v, colour)
        turned(right, bottom, pivotX, pivotY, turnCos, turnSin, u2, v2, colour)
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
        colour: Float,
    ) {
        val acrossX = x - pivotX
        val acrossY = y - pivotY
        vertex(
            x = pivotX + acrossX * turnCos + acrossY * turnSin,
            y = pivotY - acrossX * turnSin + acrossY * turnCos,
            u = u, v = v,
            colour = colour, border = 0f, shadow = 0f,
            localX = 0f, localY = 0f,
            halfWidth = 0f, halfHeight = 0f,
            radii = noRadii, borderWidth = 0f, shadowSpread = 0f,
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
        )
    }

    /**
     * The fallback white pixel, made only if nobody supplied one.
     *
     * Held rather than created eagerly, so a batch sharing the glyph atlas never allocates a
     * texture it will not use — and so [dispose] knows whether there is one to let go of.
     */
    private var ownWhite: Texture? = null

    private fun fallbackWhite(): Texture = ownWhite ?: Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pixmap ->
        pixmap.setColor(1f, 1f, 1f, 1f)
        pixmap.fill()
        Texture(pixmap).also {
            pixmap.dispose()
            ownWhite = it
        }
    }

    private fun use(next: Texture) {
        if (texture !== next) {
            flush()
            texture = next
        } else if (used + 4 * FLOATS_PER_VERTEX > vertices.size) {
            flush()
        }
    }

    @Suppress("LongParameterList")
    private fun quad(
        left: Float, bottom: Float, right: Float, top: Float,
        centreX: Float, centreY: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        colour: Float, border: Float, shadow: Float,
        halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
    ) {
        vertex(left, bottom, u, v2, colour, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa)
        vertex(left, top, u, v, colour, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa)
        vertex(right, top, u2, v, colour, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa)
        vertex(right, bottom, u2, v2, colour, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        colour: Float, border: Float, shadow: Float,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float,
    ) {
        var at = used
        vertices[at++] = x
        vertices[at++] = y
        vertices[at++] = colour
        vertices[at++] = border
        vertices[at++] = shadow
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
        used = at
    }

    override fun dispose() {
        mesh.dispose()
        shader.dispose()
        ownWhite?.dispose()
    }

    private fun compile(): ShaderProgram {
        val program = ShaderProgram(VERTEX, FRAGMENT)
        check(program.isCompiled) { "the interface shader would not compile:\n${program.log}" }
        return program
    }

    private companion object {

        const val FLOATS_PER_VERTEX = 18

        fun quadIndices(quads: Int) = ShortArray(quads * 6).also { indices ->
            for (quad in 0 until quads) {
                val vertex = (quad * 4).toShort()
                val at = quad * 6
                indices[at] = vertex
                indices[at + 1] = (vertex + 1).toShort()
                indices[at + 2] = (vertex + 2).toShort()
                indices[at + 3] = (vertex + 2).toShort()
                indices[at + 4] = (vertex + 3).toShort()
                indices[at + 5] = vertex
            }
        }

        val VERTEX = """
            attribute vec2 a_position;
            attribute vec4 a_color;
            attribute vec4 a_borderColor;
            attribute vec4 a_shadowColor;
            attribute vec2 a_texCoord0;
            attribute vec2 a_local;
            attribute vec2 a_halfSize;
            attribute vec3 a_shape;
            attribute vec4 a_radii;

            uniform mat4 u_projTrans;

            varying vec4 v_color;
            varying vec4 v_borderColor;
            varying vec4 v_shadowColor;
            varying vec2 v_texCoord;
            varying vec2 v_local;
            varying vec2 v_halfSize;
            varying vec3 v_shape;
            varying vec4 v_radii;

            void main() {
                v_color = a_color;
                v_borderColor = a_borderColor;
                v_shadowColor = a_shadowColor;
                v_texCoord = a_texCoord0;
                v_local = a_local;
                v_halfSize = a_halfSize;
                v_shape = a_shape;
                v_radii = a_radii;
                gl_Position = u_projTrans * vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
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

                vec4 result = vec4(v_color.rgb, v_color.a * coverage) * vec4(sampled.rgb, sampled.a);

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
