package composegl.lwjgl3

import composegl.ui.graphics.Colour
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20

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
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun end() {
        check(drawing) { "end() without a begin()" }
        flush()
        drawing = false
    }

    fun flush() {
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
    ) {
        val margin = shadowSpread + aa
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val radius = corner.coerceIn(0f, minOf(halfWidth, halfHeight))
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
            radius = radius,
            borderWidth = borderWidth,
            shadowSpread = shadowSpread,
            aa = aa,
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
            radius = 0f,
            borderWidth = 0f,
            shadowSpread = 0f,
            // Zero says "this is a picture": the shader skips the distance field entirely.
            aa = 0f,
        )
    }

    private fun use(next: Int) {
        if (texture != next) {
            flush()
            texture = next
        } else if (used + 4 * FloatsPerVertex > vertices.size) {
            flush()
        }
    }

    @Suppress("LongParameterList")
    private fun quad(
        left: Float, bottom: Float, right: Float, top: Float,
        centreX: Float, centreY: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        fill: Colour, border: Colour, shadow: Colour,
        halfWidth: Float, halfHeight: Float,
        radius: Float, borderWidth: Float, shadowSpread: Float, aa: Float,
    ) {
        // The quad is wound anticlockwise from its bottom-left, and `v` is the coordinate at the
        // quad's *top*. Every caller here counts y downwards and flips once on the way in.
        vertex(left, bottom, u, v2, fill, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(left, top, u, v, fill, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(right, top, u2, v, fill, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
        vertex(right, bottom, u2, v2, fill, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radius, borderWidth, shadowSpread, aa)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        fill: Colour, border: Colour, shadow: Colour,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radius: Float, borderWidth: Float, shadowSpread: Float, aa: Float,
    ) {
        var at = used
        vertices[at++] = x
        vertices[at++] = y
        at = writeColour(fill, at)
        at = writeColour(border, at)
        at = writeColour(shadow, at)
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
            Attribute("a_position", 2, 0),
            Attribute("a_color", 4, 2),
            Attribute("a_borderColor", 4, 6),
            Attribute("a_shadowColor", 4, 10),
            Attribute("a_texCoord0", 2, 14),
            Attribute("a_local", 2, 16),
            Attribute("a_halfSize", 2, 18),
            Attribute("a_shape", 4, 20),
        )

        val FloatsPerVertex = Attributes.sumOf { it.size }

        val Vertex = """
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
