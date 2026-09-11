package uk.wildware.composegl.effects

import uk.wildware.composegl.ui.effect.ShaderEffect
import uk.wildware.composegl.ui.effect.ShaderSource
import uk.wildware.composegl.ui.effect.Uniform
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.effect

/**
 * Eats a subtree away in patches, the way a burning photograph goes.
 *
 * A game's answer to a panel leaving the screen. Fading one out is what every form does; dissolving
 * it, with a glowing edge on the hole, is what a game does — and because the pattern is worked out
 * from the pixel's own position, the whole thing is one number an animation drives from 0 to 1.
 *
 * @param progress how far through, from 0 (whole) to 1 (gone).
 * @param scale how big the patches are, in design units. Small is grainy, large is blobby.
 * @param softness how blurred the edge of a hole is, as a fraction of the pattern. Zero is a hard
 *   cut, which on a still picture looks like a bad mask.
 * @param edge what colour to burn the rim of each hole. Transparent — the default — leaves the
 *   colours alone and simply takes pixels away.
 */
fun dissolve(
    progress: Float,
    scale: Float = 24f,
    softness: Float = 0.15f,
    edge: Colour = Colour.Transparent,
): ShaderEffect {
    require(scale > 0f) { "The dissolve pattern cannot be $scale design units across." }
    return ShaderEffect(
        source = DissolveShader,
        uniforms = mapOf(
            "u_progress" to Uniform.Number(progress.coerceIn(0f, 1f)),
            "u_scale" to Uniform.Number(scale),
            // Never quite zero: the shader divides the band by it, and a driver's answer to
            // dividing by zero is its own business.
            "u_softness" to Uniform.Number(softness.coerceIn(0.001f, 1f)),
            "u_edge" to Uniform.of(edge),
        ),
        bleed = 0f,
    )
}

/** @see dissolve */
// Qualified for the same reason as [Modifier.outline]: the extension and the effect share a name.
fun Modifier.dissolve(
    progress: Float,
    scale: Float = 24f,
    softness: Float = 0.15f,
    edge: Colour = Colour.Transparent,
): Modifier = effect(uk.wildware.composegl.effects.dissolve(progress, scale, softness, edge))

/**
 * Value noise, worked out from the pixel's position.
 *
 * Worked out rather than sampled from a noise texture, because a texture is a file to ship, a
 * second sampler to bind, and an asset pipeline for something that is nine lines of arithmetic. The
 * hash is the well-known `sin`-and-fract one: not a good random number generator, but it is stable,
 * it needs no state, and what it is for is deciding which crumb of a panel burns first.
 */
private val DissolveShader = ShaderSource(
    name = "dissolve",
    fragment = """
        uniform float u_progress;
        uniform float u_scale;
        uniform float u_softness;
        uniform vec4 u_edge;

        float hash(vec2 cell) {
            return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453123);
        }

        float noise(vec2 at) {
            vec2 cell = floor(at);
            vec2 within = fract(at);
            // Smoothed, so the patches have soft shoulders instead of the square grid the hash is
            // actually on.
            within = within * within * (3.0 - 2.0 * within);
            float corner00 = hash(cell);
            float corner10 = hash(cell + vec2(1.0, 0.0));
            float corner01 = hash(cell + vec2(0.0, 1.0));
            float corner11 = hash(cell + vec2(1.0, 1.0));
            return mix(mix(corner00, corner10, within.x), mix(corner01, corner11, within.x), within.y);
        }

        void main() {
            vec4 picture = texture2D(u_texture, v_texCoord);

            // In design units, so the patches are the same size whatever the screen is — and, more
            // to the point, the same size on a picture that a bleed made bigger.
            float pattern = noise(v_texCoord * u_size / u_scale);

            // Each pixel has its own moment to go, and progress walks past all of them.
            float keep = smoothstep(u_progress, u_progress + u_softness, pattern);
            // The rim: pixels that have only just survived. It follows the holes as they open,
            // which is what makes it look like burning rather than like fading.
            float rim = keep * (1.0 - smoothstep(u_progress + u_softness, u_progress + u_softness * 2.0, pattern));

            vec3 colour = picture.a > 0.0 ? picture.rgb / picture.a : vec3(0.0);
            colour = mix(colour, u_edge.rgb, rim * u_edge.a);

            float alpha = picture.a * keep;
            gl_FragColor = vec4(colour * alpha, alpha) * u_alpha;
        }
    """.trimIndent(),
)
