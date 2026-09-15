package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.DrawCallTrace
import dev.wildware.composegl.ui.graphics.BlendMode
import korlibs.graphics.AGBlendFactor
import korlibs.graphics.AGBlending
import korlibs.graphics.AGBuffer
import korlibs.graphics.AGDrawType
import korlibs.graphics.AGIndexType
import korlibs.graphics.AGScissor
import korlibs.graphics.AGTextureUnitInfo
import korlibs.graphics.AGTextureUnits
import korlibs.graphics.AGVertexArrayObject
import korlibs.graphics.AGVertexData
import korlibs.graphics.DefaultShaders
import korlibs.graphics.FragmentShaderDefault
import korlibs.graphics.VertexShaderDefault
import korlibs.graphics.draw
import korlibs.graphics.shader.Attribute
import korlibs.graphics.shader.Operand
import korlibs.graphics.shader.Precision
import korlibs.graphics.shader.Program
import korlibs.graphics.shader.VarType
import korlibs.graphics.shader.Varying
import korlibs.graphics.shader.VertexLayout
import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every quad an interface draws, through one KorGE program.
 *
 * The same idea, and the same maths, as the LibGDX backend's `UiShapeBatch`: a rounded corner, a
 * border and a soft shadow are three ways of asking how far a pixel is from the edge of a rounded
 * box, so they are one shader doing one distance calculation, and a panel with all three is a handful
 * of quads that batch with every other panel on screen. Text and pictures come through the same
 * program with the shape switched off. The shape is described per vertex, never per uniform, so two
 * widgets that want different corners do not cost a draw call between them.
 *
 * KorGE's own `BatchBuilder2D` carries a position, a texture coordinate and two colours per vertex,
 * which is not enough to describe a rounded box, so this batch keeps its own vertex buffer and hands
 * it to `AG.draw` itself. That call is stateless in KorGE — every draw names its framebuffer, its
 * blending, its scissor and its textures — so nothing here can leave KorGE's own batch in a state it
 * did not expect, as long as KorGE's batch is flushed before this one draws.
 *
 * Coordinates arrive y down, in design units, exactly as the toolkit gave them: KorGE counts y down
 * too, so unlike the LibGDX batch there is no flip anywhere in this file. Where they land is decided
 * by [transform].
 *
 * @param white the bitmap solid colour is sampled from, and the texture coordinate of a white texel
 *   in it. The glyph atlas's white block, so that a label on a panel is one texture.
 */
internal class KorgeShapeBatch(
    private val white: Bitmap,
    private val whiteU: Float,
    private val whiteV: Float,
    private val maxQuads: Int = 2048,
) : AutoCloseable {

    private val vertices = FloatArray(maxQuads * 4 * FloatsPerVertex)
    private val vertexBuffer = AGBuffer()
    private val indexBuffer = AGBuffer().upload(quadIndices(maxQuads))
    private val vertexData = AGVertexArrayObject(AGVertexData(Layout, vertexBuffer), isDynamic = false)
    private val units = AGTextureUnits()

    private var used = 0
    private var texture: Bitmap? = null
    private var smooth = false
    private var context: RenderContext? = null
    private var scissor: AGScissor = AGScissor.NIL
    private var blending: AGBlending = blendingFor(BlendMode.SourceOver, premultiplied = false)
    private val transform = FloatArray(4)

    /** How many times this batch talked to the driver since [begin]. */
    var renderCalls = 0
        private set

    /** Told why, each time [renderCalls] goes up. Null tells nobody. */
    var trace: DrawCallTrace? = null

    fun begin(context: RenderContext, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        check(this.context == null) { "begin() was called twice without an end()" }
        this.context = context
        renderCalls = 0
        scissor = AGScissor.NIL
        blending = blendingFor(BlendMode.SourceOver, premultiplied = false)
        setTransform(scaleX, scaleY, offsetX, offsetY)
    }

    fun end() {
        checkNotNull(context) { "end() without a begin()" }
        flush(BatchBreak.End)
        context = null
        texture = null
    }

    /**
     * Points the following quads somewhere else — an offscreen picture, and back again. Flushes
     * first: whatever is queued was queued for the old place.
     */
    fun transform(scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        flush(BatchBreak.Layer)
        setTransform(scaleX, scaleY, offsetX, offsetY)
    }

    private fun setTransform(scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        transform[0] = scaleX
        transform[1] = scaleY
        transform[2] = offsetX
        transform[3] = offsetY
    }

    /** The scissor box, in framebuffer pixels y down, or [AGScissor.NIL] for none. */
    fun scissor(box: AGScissor) {
        if (box == scissor) return
        flush(BatchBreak.Clip)
        scissor = box
    }

    /**
     * How the following quads are combined with what is there. See the LibGDX batch for why the
     * alpha half accumulates: what lands in an offscreen picture is then premultiplied.
     */
    fun blend(mode: BlendMode, premultiplied: Boolean, reason: BatchBreak = BatchBreak.Blend) {
        val next = blendingFor(mode, premultiplied)
        if (next == blending) return
        flush(reason)
        blending = next
    }

    /** Hands what is queued to the GPU, blaming [reason] — but only when something was queued. */
    fun flush(reason: BatchBreak) {
        if (used == 0) return
        val context = checkNotNull(context) { "drawing outside begin() and end()" }
        val bitmap = checkNotNull(texture)
        val quads = used / (4 * FloatsPerVertex)

        vertexBuffer.upload(vertices, 0, used)
        // KorGE uploads the bitmap here if it has never seen it, and again if its contents changed —
        // which is how a glyph packed during measuring reaches the GPU with no call of its own.
        units.set(DefaultShaders.u_Tex, context.getTex(bitmap).base, AGTextureUnitInfo(linear = smooth))
        context.ag.draw(
            context.currentFrameBuffer,
            vertexData = vertexData,
            program = UiProgram,
            drawType = AGDrawType.TRIANGLES,
            vertexCount = quads * 6,
            indices = indexBuffer,
            indexType = AGIndexType.USHORT,
            blending = blending,
            uniformBlocks = context.createCurrentUniformsRef(UiProgram),
            textureUnits = units.clone(),
            scissor = scissor,
        )

        renderCalls++
        trace?.record(reason)
        used = 0
    }

    /**
     * One rounded box, with any of a fill, a border and a shadow, and a radius for each corner named
     * as it looks on screen. Colours are ARGB with the opacity already in their alpha.
     *
     * @param aa how wide the softened edge is, in design units: one screen pixel, which only the
     *   caller knows the size of.
     */
    @Suppress("LongParameterList")
    fun shape(
        left: Float, top: Float, width: Float, height: Float,
        fill: Int,
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
        border: Int, borderWidth: Float,
        shadow: Int, shadowSpread: Float,
        aa: Float,
    ) {
        radii(width, height, topLeft, topRight, bottomRight, bottomLeft)
        val margin = shadowSpread + aa
        use(white, smooth = false)
        quad(
            left - margin, top - margin, left + width + margin, top + height + margin,
            centreX = left + width / 2f, centreY = top + height / 2f,
            u = whiteU, v = whiteV, u2 = whiteU, v2 = whiteV,
            colour = fill, border = border, shadow = shadow,
            halfWidth = width / 2f, halfHeight = height / 2f,
            borderWidth = borderWidth, shadowSpread = shadowSpread, aa = aa,
        )
    }

    /**
     * One rounded box filled with a gradient between [start] and [end], the end colour riding in the
     * border's slot. Not reached by the canvas yet — see the design note — but the shader already
     * answers it, so that turning gradients on is a canvas change and not a shader one.
     *
     * @param radial true for outwards from the middle; false for straight along ([axisX], [axisY]),
     *   already divided by its length, y down.
     */
    @Suppress("LongParameterList")
    fun gradient(
        left: Float, top: Float, width: Float, height: Float,
        start: Int, end: Int,
        radial: Boolean, axisX: Float, axisY: Float,
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
        aa: Float,
    ) {
        radii(width, height, topLeft, topRight, bottomRight, bottomLeft)
        use(white, smooth = false)
        quad(
            left - aa, top - aa, left + width + aa, top + height + aa,
            centreX = left + width / 2f, centreY = top + height / 2f,
            u = whiteU, v = whiteV, u2 = whiteU, v2 = whiteV,
            colour = start, border = end, shadow = 0,
            halfWidth = width / 2f, halfHeight = height / 2f,
            borderWidth = 0f, shadowSpread = 0f, aa = aa,
            gradient = if (radial) Radial else Linear, gradientX = axisX, gradientY = axisY,
        )
    }

    /**
     * A triangle fan, hub first. The batch draws quads and nothing else, so each quad carries two of
     * the fan's triangles: its own winding, 0, 1, 2 then 2, 3, 0, is hub, a, b and then b, c, hub.
     */
    fun fan(points: FloatArray, colour: Int) {
        if (points.size < 6) return
        val hubX = points[0]
        val hubY = points[1]
        var at = 2
        while (at + 3 < points.size) {
            val cx = if (at + 5 < points.size) points[at + 4] else points[at + 2]
            val cy = if (at + 5 < points.size) points[at + 5] else points[at + 3]
            use(white, smooth = false)
            flat(hubX, hubY, whiteU, whiteV, colour)
            flat(points[at], points[at + 1], whiteU, whiteV, colour)
            flat(points[at + 2], points[at + 3], whiteU, whiteV, colour)
            flat(cx, cy, whiteU, whiteV, colour)
            at += 4
        }
    }

    /**
     * A picture, or a glyph. No shape and no softened edge: whatever the texture says, multiplied by
     * [colour].
     *
     * @param premultiplied whether [bitmap]'s colours are already multiplied by its alpha, which the
     *   shader undoes so every picture blends the same way.
     */
    @Suppress("LongParameterList")
    fun textured(
        bitmap: Bitmap,
        smooth: Boolean,
        left: Float, top: Float, width: Float, height: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        colour: Int,
    ) {
        use(bitmap, smooth)
        val straighten = if (bitmap.premultiplied) 1f else 0f
        val right = left + width
        val bottom = top + height
        picture(left, top, u, v, colour, straighten)
        picture(right, top, u2, v, colour, straighten)
        picture(right, bottom, u2, v2, colour, straighten)
        picture(left, bottom, u, v2, colour, straighten)
    }

    /**
     * The same picture, turned clockwise on screen by [degrees] round ([pivotX], [pivotY]). Four
     * corners worked out here and written as an ordinary quad, so a turned picture batches with
     * everything else drawn from the same texture.
     */
    @Suppress("LongParameterList")
    fun textured(
        bitmap: Bitmap,
        smooth: Boolean,
        left: Float, top: Float, width: Float, height: Float,
        pivotX: Float, pivotY: Float, degrees: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        colour: Int,
    ) {
        use(bitmap, smooth)
        val straighten = if (bitmap.premultiplied) 1f else 0f
        val radians = degrees * PI.toFloat() / 180f
        val turnCos = cos(radians)
        val turnSin = sin(radians)
        fun corner(x: Float, y: Float, cu: Float, cv: Float) {
            val acrossX = x - pivotX
            val acrossY = y - pivotY
            // y grows downwards, so this is the ordinary rotation and a positive angle turns clockwise.
            picture(pivotX + acrossX * turnCos - acrossY * turnSin, pivotY + acrossX * turnSin + acrossY * turnCos, cu, cv, colour, straighten)
        }
        corner(left, top, u, v)
        corner(left + width, top, u2, v)
        corner(left + width, top + height, u2, v2)
        corner(left, top + height, u, v2)
    }

    private val radii = FloatArray(4)
    private val noRadii = FloatArray(4)

    /** Each radius held to half the box's shorter side on its own, the rule a single radius has always had. */
    private fun radii(width: Float, height: Float, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        val most = minOf(width / 2f, height / 2f).coerceAtLeast(0f)
        radii[0] = topLeft.coerceIn(0f, most)
        radii[1] = topRight.coerceIn(0f, most)
        radii[2] = bottomRight.coerceIn(0f, most)
        radii[3] = bottomLeft.coerceIn(0f, most)
    }

    private fun use(next: Bitmap, smooth: Boolean) {
        if (texture !== next || this.smooth != smooth) {
            flush(BatchBreak.Texture)
            texture = next
            this.smooth = smooth
        } else if (used + 4 * FloatsPerVertex > vertices.size) {
            flush(BatchBreak.Full)
        }
    }

    private fun flat(x: Float, y: Float, u: Float, v: Float, colour: Int) =
        vertex(x, y, u, v, colour, 0, 0, 0f, 0f, 0f, 0f, noRadii, 0f, 0f, 0f, 0f)

    private fun picture(x: Float, y: Float, u: Float, v: Float, colour: Int, straighten: Float) =
        vertex(x, y, u, v, colour, 0, 0, 0f, 0f, 0f, 0f, noRadii, 0f, 0f, 0f, straighten)

    /** Clockwise from the top-left, so the indices 0, 1, 2 then 2, 3, 0 cover it. */
    @Suppress("LongParameterList")
    private fun quad(
        left: Float, top: Float, right: Float, bottom: Float,
        centreX: Float, centreY: Float,
        u: Float, v: Float, u2: Float, v2: Float,
        colour: Int, border: Int, shadow: Int,
        halfWidth: Float, halfHeight: Float,
        borderWidth: Float, shadowSpread: Float, aa: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
    ) {
        vertex(left, top, u, v, colour, border, shadow, left - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, 0f, gradient, gradientX, gradientY)
        vertex(right, top, u2, v, colour, border, shadow, right - centreX, top - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, 0f, gradient, gradientX, gradientY)
        vertex(right, bottom, u2, v2, colour, border, shadow, right - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, 0f, gradient, gradientX, gradientY)
        vertex(left, bottom, u, v2, colour, border, shadow, left - centreX, bottom - centreY, halfWidth, halfHeight, radii, borderWidth, shadowSpread, aa, 0f, gradient, gradientX, gradientY)
    }

    @Suppress("LongParameterList")
    private fun vertex(
        x: Float, y: Float, u: Float, v: Float,
        colour: Int, border: Int, shadow: Int,
        localX: Float, localY: Float, halfWidth: Float, halfHeight: Float,
        radii: FloatArray, borderWidth: Float, shadowSpread: Float, aa: Float, straighten: Float,
        gradient: Float = 0f, gradientX: Float = 0f, gradientY: Float = 0f,
        w: Float = 1f,
    ) {
        var at = used
        // Into clip space here rather than in the shader: `clip = design * scale + offset`, the offset
        // scaled by w so a vertex with a depth divides back to the same place. Four multiplies a
        // vertex, and no uniform for the program to depend on.
        vertices[at++] = x * transform[0] + transform[2] * w
        vertices[at++] = y * transform[1] + transform[3] * w
        vertices[at++] = w
        at = colour(at, colour)
        at = colour(at, border)
        at = colour(at, shadow)
        vertices[at++] = u
        vertices[at++] = v
        vertices[at++] = localX
        vertices[at++] = localY
        vertices[at++] = halfWidth
        vertices[at++] = halfHeight
        vertices[at++] = borderWidth
        vertices[at++] = shadowSpread
        vertices[at++] = aa
        vertices[at++] = straighten
        vertices[at++] = radii[0]
        vertices[at++] = radii[1]
        vertices[at++] = radii[2]
        vertices[at++] = radii[3]
        vertices[at++] = gradient
        vertices[at++] = gradientX
        vertices[at++] = gradientY
        used = at
    }

    /** Four floats from 0 to 1, red first, straight alpha. */
    private fun colour(at: Int, argb: Int): Int {
        vertices[at] = ((argb shr 16) and 0xFF) / 255f
        vertices[at + 1] = ((argb shr 8) and 0xFF) / 255f
        vertices[at + 2] = (argb and 0xFF) / 255f
        vertices[at + 3] = ((argb ushr 24) and 0xFF) / 255f
        return at + 4
    }

    override fun close() {
        vertexBuffer.close()
        indexBuffer.close()
    }

    companion object {

        const val FloatsPerVertex = 32

        /** What the first gradient float says: a straight gradient, or one outwards from the middle. */
        const val Linear = 1f
        const val Radial = 2f

        /** A one-pixel white bitmap, for a canvas made without an atlas. Premultiplied, as KorGE prefers. */
        fun ownWhite(): Bitmap32 = Bitmap32(1, 1, premultiplied = true).also { it.setRgbaRaw(0, 0, RGBA(255, 255, 255, 255)) }

        private fun blendingFor(mode: BlendMode, premultiplied: Boolean): AGBlending {
            val destination = if (mode == BlendMode.Additive) AGBlendFactor.ONE else AGBlendFactor.ONE_MINUS_SOURCE_ALPHA
            return AGBlending(
                srcRGB = if (premultiplied) AGBlendFactor.ONE else AGBlendFactor.SOURCE_ALPHA,
                dstRGB = destination,
                srcA = AGBlendFactor.ONE,
                dstA = destination,
            )
        }

        private fun quadIndices(quads: Int) = ShortArray(quads * 6).also { indices ->
            for (quad in 0 until quads) {
                val vertex = quad * 4
                val at = quad * 6
                indices[at] = vertex.toShort()
                indices[at + 1] = (vertex + 1).toShort()
                indices[at + 2] = (vertex + 2).toShort()
                indices[at + 3] = (vertex + 2).toShort()
                indices[at + 4] = (vertex + 3).toShort()
                indices[at + 5] = vertex.toShort()
            }
        }

        private val a_pos = Attribute("a_uiPos", VarType.Float3, normalized = false, precision = Precision.HIGH, fixedLocation = 0)
        private val a_col = Attribute("a_uiColor", VarType.Float4, normalized = false, fixedLocation = 1)
        private val a_border = Attribute("a_uiBorder", VarType.Float4, normalized = false, fixedLocation = 2)
        private val a_shadow = Attribute("a_uiShadow", VarType.Float4, normalized = false, fixedLocation = 3)
        private val a_tex = Attribute("a_uiTex", VarType.Float2, normalized = false, precision = Precision.HIGH, fixedLocation = 4)
        private val a_local = Attribute("a_uiLocal", VarType.Float2, normalized = false, precision = Precision.HIGH, fixedLocation = 5)
        private val a_half = Attribute("a_uiHalf", VarType.Float2, normalized = false, precision = Precision.HIGH, fixedLocation = 6)
        /** Border width, shadow spread, softened edge, and whether the picture is premultiplied. */
        private val a_shape = Attribute("a_uiShape", VarType.Float4, normalized = false, precision = Precision.HIGH, fixedLocation = 7)
        /** Top-left, top-right, bottom-right, bottom-left. */
        private val a_radii = Attribute("a_uiRadii", VarType.Float4, normalized = false, precision = Precision.HIGH, fixedLocation = 8)
        private val a_gradient = Attribute("a_uiGradient", VarType.Float3, normalized = false, fixedLocation = 9)

        private val Layout = VertexLayout(a_pos, a_col, a_border, a_shadow, a_tex, a_local, a_half, a_shape, a_radii, a_gradient)

        private val v_col = Varying("v_uiColor", VarType.Float4)
        private val v_border = Varying("v_uiBorder", VarType.Float4)
        private val v_shadow = Varying("v_uiShadow", VarType.Float4)
        private val v_tex = Varying("v_uiTex", VarType.Float2, Precision.HIGH)
        private val v_local = Varying("v_uiLocal", VarType.Float2, Precision.HIGH)
        private val v_half = Varying("v_uiHalf", VarType.Float2, Precision.HIGH)
        private val v_shape = Varying("v_uiShape", VarType.Float4, Precision.HIGH)
        private val v_radii = Varying("v_uiRadii", VarType.Float4, Precision.HIGH)
        private val v_gradient = Varying("v_uiGradient", VarType.Float3)

        /**
         * The LibGDX backend's shader, written in KorGE's shader language so KorGE writes the GLSL
         * for whichever version the context wants. Branch-free where the original branches — a
         * `step` picks between the two answers — because the language's `if` is a statement and
         * every answer here is an expression.
         */
        val UiProgram: Program = Program(
            vertex = VertexShaderDefault {
                SET(v_col, a_col)
                SET(v_border, a_border)
                SET(v_shadow, a_shadow)
                SET(v_tex, a_tex)
                SET(v_local, a_local)
                SET(v_half, a_half)
                SET(v_shape, a_shape)
                SET(v_radii, a_radii)
                SET(v_gradient, a_gradient)
                // Already in clip space. The third number is w: the GPU interpolates across the
                // triangle divided by it, which is what a tilted picture needs.
                SET(out, vec4(a_pos["x"], a_pos["y"], 0f.lit, a_pos["z"]))
            },
            fragment = FragmentShaderDefault {
                fun over(top: Operand, bottom: Operand): Operand {
                    val alpha = top["a"] + bottom["a"] * (1f.lit - top["a"])
                    val rgb = (top["rgb"] * top["a"] + bottom["rgb"] * bottom["a"] * (1f.lit - top["a"])) / max(alpha, 0.00001f.lit)
                    return vec4(rgb, alpha)
                }

                val sampled = TEMP(VarType.Float4)
                SET(sampled, texture2D(u_Tex, v_tex["xy"]))
                // A premultiplied picture is put back to straight alpha, so every picture blends alike.
                SET(sampled, mix(sampled, vec4(sampled["rgb"] / max(sampled["a"], 0.00001f.lit), sampled["a"]), v_shape["w"]))

                val aa = TEMP(VarType.Float1)
                SET(aa, max(v_shape["z"], 0.00001f.lit))

                // Which corner's radius a point is under: the one in the same quarter of the box.
                val right = step(0f.lit, v_local["x"])
                val lower = step(0f.lit, v_local["y"])
                val radius = TEMP(VarType.Float1)
                SET(radius, mix(mix(v_radii["x"], v_radii["y"], right), mix(v_radii["w"], v_radii["z"], right), lower))

                // Distance from the edge of the rounded box: negative inside, positive out.
                val q = TEMP(VarType.Float2)
                SET(q, abs(v_local) - v_half + vec2(radius, radius))
                val distance = TEMP(VarType.Float1)
                SET(distance, min(max(q["x"], q["y"]), 0f.lit) + length(max(q, vec2(0f, 0f))) - radius)
                val coverage = TEMP(VarType.Float1)
                SET(coverage, 1f.lit - smoothstep(-aa, aa, distance))

                // A gradient: how far along this pixel is, from its offset from the middle of the box.
                val along = TEMP(VarType.Float1)
                SET(
                    along,
                    clamp(
                        mix(
                            dot(v_local, v_gradient["yz"]) + 0.5f.lit,
                            length(v_local / max(v_half, vec2(0.0001f, 0.0001f))),
                            step(1.5f.lit, v_gradient["x"]),
                        ),
                        0f.lit, 1f.lit,
                    ),
                )
                // Weighted by each colour's own opacity, so fading to transparent keeps the hue.
                val mixedAlpha = mix(v_col["a"], v_border["a"], along)
                val mixed = vec4(
                    mix(v_col["rgb"] * v_col["a"], v_border["rgb"] * v_border["a"], along) / max(mixedAlpha, 0.00001f.lit),
                    mixedAlpha,
                )
                val fill = TEMP(VarType.Float4)
                SET(fill, mix(v_col, mixed, step(0.5f.lit, v_gradient["x"])))

                val result = TEMP(VarType.Float4)
                SET(result, vec4(fill["rgb"], fill["a"] * coverage) * sampled)

                val inside = 1f.lit - smoothstep(-aa, aa, distance + v_shape["x"])
                val band = max(coverage - inside, 0f.lit) * step(0.00001f.lit, v_shape["x"])
                SET(result, over(vec4(v_border["rgb"], v_border["a"] * band), result))

                // Outside the shape only, falling off across the spread.
                val shade = (1f.lit - smoothstep(0f.lit, max(v_shape["y"], 0.00001f.lit), max(distance, 0f.lit))) *
                    step(0.00001f.lit, v_shape["y"])
                SET(result, over(result, vec4(v_shadow["rgb"], v_shadow["a"] * shade)))

                // A picture or a glyph has no shape: just the texture, times its colour.
                SET(out, mix(v_col * sampled, result, step(0.00001f.lit, v_shape["z"])))
            },
            name = "composegl.UiShapes",
        )
    }
}
