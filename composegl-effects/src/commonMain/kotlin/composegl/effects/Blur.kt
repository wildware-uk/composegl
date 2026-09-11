package composegl.effects

import composegl.ui.effect.ShaderEffect
import composegl.ui.effect.ShaderSource
import composegl.ui.effect.Uniform
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.effect

/** Which way a one-pass effect works along. */
enum class Axis {
    Horizontal,
    Vertical,
}

/**
 * A gaussian blur of [radius] design units, in one direction.
 *
 * One direction, because a blur is separable: blurring sideways and then downwards gives the same
 * picture as blurring in every direction at once, and costs thirteen samples a pixel instead of a
 * hundred and sixty-nine. [Modifier.blur] does both passes and is what most callers want; this is
 * here for the one that wants only one — motion along a direction of travel, say.
 *
 * The radius is in design units, so a blur is the same softness on a phone as on a monitor, which
 * is the whole reason the shader is handed `u_size`.
 */
fun blur(radius: Float, axis: Axis): ShaderEffect {
    require(radius >= 0f) { "A blur radius cannot be negative, and $radius is." }
    return ShaderEffect(
        source = BlurShader,
        uniforms = mapOf(
            "u_radius" to Uniform.Number(radius),
            "u_direction" to when (axis) {
                Axis.Horizontal -> Uniform.Vector2(1f, 0f)
                Axis.Vertical -> Uniform.Vector2(0f, 1f)
            },
        ),
        // The blur reaches a radius past the widget in the direction it works along. Without the
        // margin the spread would be cut off square, which looks like a blurred picture in a sharp
        // frame.
        bleed = radius,
    )
}

/**
 * Blurs this node and everything under it by [radius] design units.
 *
 * ```kotlin
 * Panel(Modifier.blur(8f)) { … }
 * ```
 *
 * Two passes, so two offscreen pictures and two draw calls per frame. That is the cheap way to do
 * it and it is still not free: a blur behind a menu that is always on screen is a cost every frame,
 * and one that appears when the menu opens is not.
 */
fun Modifier.blur(radius: Float): Modifier =
    effect(blur(radius, Axis.Horizontal)).effect(blur(radius, Axis.Vertical))

/**
 * Thirteen taps, weighted by a gaussian the shader works out itself.
 *
 * Worked out rather than passed in, because the weights depend on the radius and the radius is a
 * uniform — a table of weights would mean recompiling the shader every time somebody animated a
 * blur open.
 */
private val BlurShader = ShaderSource(
    name = "blur",
    fragment = """
        uniform float u_radius;
        uniform vec2 u_direction;

        // Six each side of the middle. Enough that the steps land inside a pixel or two of each
        // other at the radii an interface actually uses, and few enough to stay cheap on a phone.
        const int TAPS = 6;

        $OutsideIsClear

        void main() {
            // One design unit is 1.0 / u_size of the picture, so a step measured in design units
            // is the same distance whatever the screen is.
            float spacing = u_radius / float(TAPS);
            vec2 stride = u_direction * spacing / u_size;
            // Half the radius, which puts the furthest tap two standard deviations out: past there
            // a gaussian has nothing left worth sampling.
            float sigma = max(u_radius * 0.5, 0.0001);

            vec4 total = texture2D(u_texture, v_texCoord);
            float weight = 1.0;
            for (int i = 1; i <= TAPS; i++) {
                float distance = spacing * float(i);
                float w = exp(-(distance * distance) / (2.0 * sigma * sigma));
                total += clearOutside(v_texCoord + stride * float(i)) * w;
                total += clearOutside(v_texCoord - stride * float(i)) * w;
                weight += 2.0 * w;
            }

            gl_FragColor = (total / weight) * u_alpha;
        }
    """.trimIndent(),
)
