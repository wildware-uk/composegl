package composegl.ui.effect

import composegl.ui.graphics.Colour

/**
 * A fragment shader, as text.
 *
 * Text rather than anything cleverer because a shader has to cross from common code, where there
 * is no OpenGL, into a backend, where there is — and a string is the only thing that crosses that
 * line without dragging an engine type back with it.
 *
 * ## What to write
 *
 * A fragment shader in the old dialect — `varying`, `texture2D`, `gl_FragColor` — which is what
 * both of this toolkit's own shaders are written in, and what compiles on a desktop driver and on
 * a phone without being written twice. The backend puts the `#version` and precision lines on the
 * front; do not write them.
 *
 * These are declared for you, so declare only your own uniforms on top of them:
 *
 * | name | what it is |
 * |---|---|
 * | `v_texCoord` | where in the picture this pixel is, 0 to 1 |
 * | `u_texture` | the picture: what the interface drew, before the effect |
 * | `u_textureSize` | its size, in real pixels — a blur's step is `1.0 / u_textureSize` |
 * | `u_size` | the area the effect covers, in design units |
 * | `u_alpha` | the opacity in force, which your last line should multiply by |
 *
 * ```kotlin
 * val invert = ShaderSource(
 *     name = "invert",
 *     fragment = """
 *         void main() {
 *             vec4 picture = texture2D(u_texture, v_texCoord);
 *             // Premultiplied: the colours are already multiplied by their own opacity, so the
 *             // inversion is of the colour rather than of what is stored.
 *             vec3 colour = picture.a > 0.0 ? picture.rgb / picture.a : vec3(0.0);
 *             gl_FragColor = vec4((1.0 - colour) * picture.a, picture.a) * u_alpha;
 *         }
 *     """,
 * )
 * ```
 *
 * ## Premultiplied
 *
 * What you sample has its colours already multiplied by its own opacity, and what you write must
 * be the same — that is what makes a soft edge blend without a dark halo round it. In practice:
 * multiply the whole of your answer by its own alpha, and by `u_alpha`.
 *
 * ## When it does not compile
 *
 * The backend throws, with the driver's own message and [name] in it. A shader that does not
 * compile is a mistake in the source rather than a condition to recover from, and a silent black
 * rectangle is the hardest bug in this toolkit to find.
 *
 * @param name what to call it in an error message. Nothing reads it but a person.
 * @param fragment the shader's own text: its uniforms, its functions, and its `main`.
 */
data class ShaderSource(val name: String, val fragment: String)

/**
 * One value a shader reads.
 *
 * Typed rather than a bag of floats, so that passing a size where a colour was meant is a compile
 * error in Kotlin rather than a wrong picture on a screen.
 */
sealed interface Uniform {

    /** `uniform float` */
    data class Number(val value: Float) : Uniform

    /** `uniform vec2` */
    data class Vector2(val x: Float, val y: Float) : Uniform

    /** `uniform vec3` */
    data class Vector3(val x: Float, val y: Float, val z: Float) : Uniform

    /** `uniform vec4` */
    data class Vector4(val x: Float, val y: Float, val z: Float, val w: Float) : Uniform

    /** `uniform int` */
    data class Whole(val value: Int) : Uniform

    /** `uniform bool` */
    data class Flag(val value: Boolean) : Uniform

    companion object {
        /**
         * A colour as a `vec4`, each channel from zero to one, **not** premultiplied.
         *
         * Premultiplying is the shader's job, because a shader that wants a hue to tint by wants
         * the hue, and one that wants a colour to blend towards wants it multiplied — and only the
         * shader knows which.
         */
        fun of(colour: Colour) = Vector4(
            colour.red / 255f,
            colour.green / 255f,
            colour.blue / 255f,
            colour.alphaFraction,
        )
    }
}

/**
 * A shader and the values it is being given: one effect, ready to draw.
 *
 * A data class, because a modifier chain is compared on every recomposition and an effect whose
 * numbers have not changed must not look like a new one.
 *
 * @param source the shader itself. The backend compiles it once and keeps it, keyed by its text,
 *   so building this object every frame costs nothing.
 * @param uniforms the values it reads, by name. A name the shader does not declare is ignored —
 *   drivers throw away a uniform nothing uses, so there is nothing to complain to.
 * @param bleed how far outside the widget the effect reaches, in design units. A blur spreads, an
 *   outline sits outside the edge, a glow goes further still; without this the spread is cut off
 *   square at the widget's edge. Costs a bigger picture, so it is zero unless asked for.
 */
data class ShaderEffect(
    val source: ShaderSource,
    val uniforms: Map<String, Uniform> = emptyMap(),
    val bleed: Float = 0f,
)
