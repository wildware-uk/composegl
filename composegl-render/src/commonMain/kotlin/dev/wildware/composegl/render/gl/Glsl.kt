package dev.wildware.composegl.render.gl

/**
 * Which GLSL a context compiles, and how a source written once becomes it.
 *
 * Every shader here, and every [dev.wildware.composegl.ui.effect.ShaderEffect] a game writes, is
 * GLSL ES 1.00 style: `attribute`, `varying`, `texture2D`, `gl_FragColor`. That is the public effect
 * contract, and it compiles unchanged on GL 2.1 and on the ES 2 / WebGL 1 family. A core context and
 * the ES 3 / WebGL 2 family need a version line and a few words changed, which is done here in
 * Kotlin rather than with `#define`: names starting `gl_` are reserved in GLSL ES 3.00.
 *
 * Documented limit: an effect cannot use `in`, `out` or `texture` as its own identifiers.
 */
enum class GlslDialect(
    private val version: String,
    private val precision: Boolean,
    private val modern: Boolean,
) {
    /** GL 2.1 and GL 3.x/4.x compatibility profiles: GLSL 1.10, no header, exactly as written. */
    Legacy(version = "", precision = false, modern = false),

    /** A GL 3.0 or 3.1 forward-compatible context. */
    Core130(version = "#version 130", precision = false, modern = true),

    /** GL 3.2+ core, macOS's only kind, and `MESA_GL_VERSION_OVERRIDE=3.2FC`. */
    Core150(version = "#version 150", precision = false, modern = true),

    /** OpenGL ES 2 and WebGL 1. */
    Es100(version = "#version 100", precision = true, modern = false),

    /** OpenGL ES 3 and WebGL 2. */
    Es300(version = "#version 300 es", precision = true, modern = true),
    ;

    /** [source], a vertex shader, as this dialect compiles it. */
    fun vertex(source: String): String {
        if (version.isEmpty()) return source
        val body = if (modern) source.replaceWord("attribute", "in").replaceWord("varying", "out") else source
        return "$version\n$body"
    }

    /**
     * [source], a fragment shader, as this dialect compiles it. The ES family gets a default float
     * precision, which a fragment shader there cannot compile without.
     *
     * @param highPrecision ask for `highp` where the device has it, and `mediump` where it does not.
     *   The device asks for it for every shader: a phone's medium precision runs out long before a
     *   wide panel does, the rounded-box distance turns to visible steps, and an effect's noise hash
     *   comes out as nothing.
     */
    fun fragment(source: String, highPrecision: Boolean = false): String {
        if (version.isEmpty()) return source
        val header = buildString {
            append(version).append('\n')
            if (precision && highPrecision) append(HighPrecision)
            if (precision && !highPrecision) append("precision mediump float;\n")
            if (modern) append("out vec4 ").append(FragColour).append(";\n")
        }
        val body = if (modern) {
            source.replaceWord("varying", "in").replaceWord("texture2D", "texture").replaceWord("gl_FragColor", FragColour)
        } else {
            source
        }
        return header + body
    }

    companion object {

        /** What `gl_FragColor` becomes where it no longer exists. Not `gl_`: that prefix is reserved. */
        const val FragColour = "cg_FragColor"

        /** `highp` where a fragment shader can have it, `mediump` where it cannot. */
        const val HighPrecision =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"

        /** The dialect a context with [profile] compiles. */
        fun of(profile: GlProfile): GlslDialect = when (profile.api) {
            GlApi.Desktop -> when {
                !profile.core -> Legacy
                profile.atLeast(3, 2) -> Core150
                else -> Core130
            }
            GlApi.Es -> if (profile.major >= 3) Es300 else Es100
            GlApi.WebGl -> if (profile.major >= 2) Es300 else Es100
        }

        private fun String.replaceWord(word: String, with: String): String =
            Regex("\\b$word\\b").replace(this, with)
    }
}

/** The shaders every device on OpenGL compiles, written once as GLSL ES 1.00 style source. */
object GlslSources {

    val ShapeVertex = """
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

    val ShapeFragment = """
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
        float roundedBox(vec2 point, vec2 extent, float radius) {
            vec2 q = abs(point) - extent + radius;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
        }

        // Which corner's radius a point is under: the one in the same quarter of the box. The
        // radii are top-left, then clockwise, and y counts upwards here.
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

            // A picture or a glyph: no shape to work out, just the texture. A premultiplied
            // picture is straightened first, since everything here works in straight alpha.
            if (aa <= 0.0) {
                if (v_gradient.x > 0.5 && sampled.a > 0.0) sampled = vec4(sampled.rgb / sampled.a, sampled.a);
                gl_FragColor = v_color * sampled;
                return;
            }

            float radius = cornerRadius(v_local, v_radii);
            float borderWidth = v_shape.x;
            float spread = v_shape.y;

            float distance = roundedBox(v_local, v_halfSize, radius);
            float coverage = 1.0 - smoothstep(-aa, aa, distance);

            // A gradient. Two colours mix in the vertex, the end riding in the border's slot; a run
            // of stops is a strip of the atlas, and where it is rides in the shadow's slot.
            vec4 fill = v_color;
            // What the texture contributes: the atlas's white block for a flat fill. A run of stops
            // reads the strip itself, and the quad's own texture coordinate is that strip's start,
            // so it takes its colour from the strip alone rather than multiplying by it twice.
            vec4 texel = sampled;
            if (v_gradient.x > 2.5) texel = vec4(1.0);
            if (v_gradient.x > 0.5) {
                bool outwards = v_gradient.x > 1.5 && v_gradient.x < 2.5 || v_gradient.x > 3.5;
                float along = outwards
                    ? length(v_local / max(v_halfSize, vec2(0.0001)))
                    : dot(v_local, v_gradient.yz) + 0.5;
                along = clamp(along, 0.0, 1.0);
                if (v_gradient.x > 2.5) {
                    vec2 from = v_shadowColor.xy;
                    vec2 to = v_shadowColor.zw;
                    vec4 strip = texture2D(u_texture, mix(from, to, along));
                    // The strip is premultiplied, so that the GPU's own mixing is the right mix.
                    if (strip.a > 0.0) strip = vec4(strip.rgb / strip.a, strip.a);
                    fill = strip * v_color;
                } else {
                    fill = between(v_color, v_borderColor, along);
                }
            }

            vec4 result = vec4(fill.rgb, fill.a * coverage) * vec4(texel.rgb, texel.a);

            // A width draws the border inside the edge; a negative one draws it outside, where the
            // shape's own coverage is nothing. Either way it is the band between the two edges.
            if (borderWidth != 0.0) {
                float other = 1.0 - smoothstep(-aa, aa, distance + borderWidth);
                float band = abs(coverage - other);
                result = over(vec4(v_borderColor.rgb, v_borderColor.a * band), result);
            }

            if (spread > 0.0) {
                // Outside the shape only, falling off across the spread.
                float shade = (1.0 - smoothstep(0.0, spread, max(distance, 0.0)));
                result = over(result, vec4(v_shadowColor.rgb, v_shadowColor.a * shade));
            } else if (spread < 0.0) {
                // Inside the shape, falling off inwards from the edge, and moved by the offset that
                // rides in the gradient's axis: a shade along one edge is what makes a box look
                // moulded rather than flat. Kept to the shape by its own coverage.
                //
                // How hard that fall is rides in the gradient's kind, as a negative number, since a
                // shade never has a gradient. At zero the shade fades the whole way in, which is a
                // fillet; near one it holds and then drops, which is a chamfer with an edge to it.
                float depth = -spread;
                float hard = clamp(-v_gradient.x, 0.0, 0.95);
                float from = roundedBox(v_local - v_gradient.yz, v_halfSize, radius);
                float along = clamp(max(-from, 0.0) / depth, 0.0, 1.0);
                float shade = 1.0 - smoothstep(hard, 1.0, along);
                result = over(vec4(v_shadowColor.rgb, v_shadowColor.a * shade * coverage), result);
            }

            gl_FragColor = result;
        }
    """.trimIndent()

    /** An effect's quad, already in clip space: no projection to get wrong. */
    val EffectVertex = """
        attribute vec2 a_position;
        attribute vec2 a_texCoord0;
        varying vec2 v_texCoord;

        void main() {
            v_texCoord = a_texCoord0;
            gl_Position = vec4(a_position, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * What every effect shader gets for nothing, written on the front of the author's own text.
     * The same on every backend; it used to be copied four times.
     */
    val EffectPreamble = """
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform vec2 u_textureSize;
        uniform vec2 u_size;
        uniform float u_alpha;

    """.trimIndent() + "\n"
}
