package composegl.effects

import composegl.ui.effect.ShaderEffect
import composegl.ui.effect.ShaderSource
import composegl.ui.effect.Uniform
import composegl.ui.graphics.Colour
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.effect

/**
 * A line of [colour], [width] design units thick, drawn round whatever the node draws.
 *
 * Round the shape rather than round the box: an outline follows the edge of what is actually
 * opaque, so a rounded panel gets a rounded outline and a piece of text gets outlined letters. That
 * is what makes it useful in a game — a selected unit, a highlighted card, readable text over a
 * bright background — and it is the one effect here that a border modifier cannot do.
 */
fun outline(colour: Colour, width: Float = 2f): ShaderEffect {
    require(width >= 0f) { "An outline cannot be $width thick." }
    return ShaderEffect(
        source = OutlineShader,
        uniforms = mapOf(
            "u_outline" to Uniform.of(colour),
            "u_width" to Uniform.Number(width),
        ),
        // The line sits outside the shape, so the picture has to be bigger than the widget by the
        // whole of its thickness or the outline is clipped away on the side that needs it most.
        bleed = width,
    )
}

/** @see outline */
// Qualified, because the name on the left of the dot is this same name: an unqualified call here
// would find the extension again rather than the effect it is wrapping.
fun Modifier.outline(colour: Colour, width: Float = 2f): Modifier =
    effect(composegl.effects.outline(colour, width))

/**
 * A ring of samples round each pixel: where the neighbourhood is opaque and this pixel is not, the
 * pixel is edge.
 *
 * Twelve samples on one ring rather than a proper distance field, because a distance field needs a
 * pass of its own and this has to run inside one draw call. Twelve is where a diagonal edge stops
 * looking like a row of dots at the widths an interface uses.
 */
private val OutlineShader = ShaderSource(
    name = "outline",
    fragment = """
        uniform vec4 u_outline;
        uniform float u_width;

        const int SAMPLES = 12;
        const float TURN = 0.5235987756;

        $OutsideIsClear

        void main() {
            vec4 picture = texture2D(u_texture, v_texCoord);

            // Design units to texture coordinates, per axis: a picture that is wider than it is
            // tall would otherwise get an oval outline.
            vec2 reach = vec2(u_width) / u_size;
            float around = 0.0;
            for (int i = 0; i < SAMPLES; i++) {
                float angle = float(i) * TURN;
                around = max(around, clearOutside(v_texCoord + vec2(cos(angle), sin(angle)) * reach).a);
            }

            // Only where the shape is not already: an outline drawn under a solid widget is paint
            // nobody sees, and drawn over it would eat the widget's own edge.
            float edge = clamp(around - picture.a, 0.0, 1.0) * u_outline.a;
            vec4 line = vec4(u_outline.rgb * edge, edge);

            // The picture over the line, both premultiplied.
            gl_FragColor = (picture + line * (1.0 - picture.a)) * u_alpha;
        }
    """.trimIndent(),
)
