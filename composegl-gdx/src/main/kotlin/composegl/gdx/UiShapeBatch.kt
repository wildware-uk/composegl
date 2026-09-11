package composegl.gdx

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
        VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_shape"),
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
        Gdx.gl.glEnable(GL20.GL_BLEND)
        // Separate on purpose: the colour half is the ordinary one, and the alpha half
        // accumulates rather than being interpolated, so what lands in a framebuffer a game is
        // using as a texture is premultiplied. On a window it changes nothing.
        Gdx.gl.glBlendFuncSeparate(
            GL20.GL_SRC_ALPHA,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
            GL20.GL_ONE,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
        )
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush()
        drawing = false
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
    ) {
        val margin = shadowSpread + aa
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val radius = corner.coerceIn(0f, minOf(halfWidth, halfHeight))

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
            radius = radius,
            borderWidth = borderWidth,
            shadowSpread = shadowSpread,
            aa = aa,
        )
    }

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
            radius = 0f, borderWidth = 0f, shadowSpread = 0f,
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
            radius = 0f,
            borderWidth = 0f,
            shadowSpread = 0f,
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
        radius: Float, borderWidth: Float, shadowSpread: Float, aa: Float,
    ) {
        vertex(left, bottom, u, v2, colour, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(left, top, u, v, colour, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(right, top, u2, v, colour, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(right, bottom, u2, v2, colour, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        colour: Float, border: Float, shadow: Float,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radius: Float, borderWidth: Float, shadowSpread: Float, aa: Float,
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
        vertices[at++] = radius
        vertices[at++] = borderWidth
        vertices[at++] = shadowSpread
        vertices[at++] = aa
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

        const val FLOATS_PER_VERTEX = 15

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
            attribute vec4 a_shape;

            uniform mat4 u_projTrans;

            varying vec4 v_color;
            varying vec4 v_borderColor;
            varying vec4 v_shadowColor;
            varying vec2 v_texCoord;
            varying vec2 v_local;
            varying vec2 v_halfSize;
            varying vec4 v_shape;

            void main() {
                v_color = a_color;
                v_borderColor = a_borderColor;
                v_shadowColor = a_shadowColor;
                v_texCoord = a_texCoord0;
                v_local = a_local;
                v_halfSize = a_halfSize;
                v_shape = a_shape;
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
            varying vec4 v_shape;

            // Distance from a point to the edge of a rounded box: negative inside, positive out.
            // One function answers all three questions this shader exists to answer.
            float roundedBox(vec2 point, vec2 extent, float radius) {
                vec2 q = abs(point) - extent + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            vec4 over(vec4 top, vec4 bottom) {
                float a = top.a + bottom.a * (1.0 - top.a);
                if (a <= 0.0) return vec4(0.0);
                return vec4((top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / a, a);
            }

            void main() {
                vec4 sampled = texture2D(u_texture, v_texCoord);
                float aa = v_shape.w;

                // A picture or a glyph: no shape to work out, just the texture.
                if (aa <= 0.0) {
                    gl_FragColor = v_color * sampled;
                    return;
                }

                float radius = v_shape.x;
                float borderWidth = v_shape.y;
                float spread = v_shape.z;

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
