package dev.wildware.composegl.effects

import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.effect

/**
 * Turns the colours of a whole subtree up, down or grey.
 *
 * The effect a game reaches for when the state of the game changes rather than the state of a
 * widget: everything behind a pause menu goes dark and grey, a disabled panel loses its colour, a
 * damaged screen goes red. Doing that with a modifier on each widget means every widget has to know
 * about pausing; doing it here means the screen knows and the widgets do not.
 *
 * Every number is a multiplier with 1 meaning unchanged, so the default of this function is a
 * no-op — an animation can start from it.
 *
 * @param brightness multiplies the colour. Below one darkens, above one blows out.
 * @param contrast pushes away from mid grey. Zero is flat grey, one is unchanged.
 * @param saturation mixes towards grey. Zero is black and white, above one is lurid.
 * @param tint multiplies each channel, and its own alpha fades the whole thing.
 */
fun colourGrade(
    brightness: Float = 1f,
    contrast: Float = 1f,
    saturation: Float = 1f,
    tint: Colour = Colour.White,
): ShaderEffect = ShaderEffect(
    source = ColourGradeShader,
    uniforms = mapOf(
        "u_brightness" to Uniform.Number(brightness),
        "u_contrast" to Uniform.Number(contrast),
        "u_saturation" to Uniform.Number(saturation),
        "u_tint" to Uniform.of(tint),
    ),
    // Every pixel answers for itself, so nothing spreads and the picture is the widget's own size.
    bleed = 0f,
)

/** @see colourGrade */
// Qualified for the same reason as [Modifier.outline]: the extension and the effect share a name.
fun Modifier.colourGrade(
    brightness: Float = 1f,
    contrast: Float = 1f,
    saturation: Float = 1f,
    tint: Colour = Colour.White,
): Modifier = effect(dev.wildware.composegl.effects.colourGrade(brightness, contrast, saturation, tint))

private val ColourGradeShader = ShaderSource(
    name = "colour grade",
    fragment = """
        uniform float u_brightness;
        uniform float u_contrast;
        uniform float u_saturation;
        uniform vec4 u_tint;

        void main() {
            vec4 picture = texture2D(u_texture, v_texCoord);

            // Undo the premultiply first. Grading the stored value instead would make a
            // half-transparent red grade differently from an opaque one, which is not what anybody
            // means by turning the brightness down.
            vec3 colour = picture.a > 0.0 ? picture.rgb / picture.a : vec3(0.0);

            colour *= u_brightness;
            colour = (colour - 0.5) * u_contrast + 0.5;
            // The eye's own weighting: green carries most of what we read as brightness, blue
            // almost none. An even third each turns a red panel into a much lighter grey than it
            // looked.
            float grey = dot(colour, vec3(0.299, 0.587, 0.114));
            colour = mix(vec3(grey), colour, u_saturation);
            colour *= u_tint.rgb;
            colour = clamp(colour, 0.0, 1.0);

            float alpha = picture.a * u_tint.a;
            gl_FragColor = vec4(colour * alpha, alpha) * u_alpha;
        }
    """.trimIndent(),
)
