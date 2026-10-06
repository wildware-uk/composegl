#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
out vec4 cg_FragColor;
#if defined(GL_ES) && !defined(CG_FULL)
#define CG_SMALL mediump
#else
#define CG_SMALL
#endif

uniform sampler2D u_texture;

// The rounded clip in force, if any: see ClipMask. The middle in pixels of the target, the
// half size and the corners (top-left, then clockwise, top meaning up) in units, how many
// pixels a unit is each way, and whether to trim at all: 0 no, 1 a straight colour, 2 a
// premultiplied one.
uniform vec4 u_maskBox;
uniform vec4 u_maskRadii;
uniform vec2 u_maskScale;
uniform float u_maskMode;

// CG_SMALL is mediump in the common program on an ES device: a colour, and the border width,
// spread, softened edge and gradient kind and axis, which never need more than three figures.
in CG_SMALL vec4 v_color;
in CG_SMALL vec4 v_borderColor;
in CG_SMALL vec4 v_shadowColor;
in vec2 v_texCoord;
in vec2 v_local;
in vec2 v_halfSize;
in CG_SMALL vec3 v_shape;
in vec4 v_radii;
in CG_SMALL vec3 v_gradient;

// Distance from a point to the edge of a rounded box: negative inside, positive out.
float roundedBox(vec2 point, vec2 extent, float radius) {
    vec2 q = abs(point) - extent + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

#ifdef CG_FULL
// Which way the edge lies from a point inside a rounded box: the way the distance grows.
// The same arithmetic as roundedBox, differentiated by hand rather than sampled, so a
// normal costs no extra texture reads and is exact on the straight sides.
vec2 towardsEdge(vec2 point, vec2 extent, float radius) {
    vec2 q = abs(point) - extent + radius;
    vec2 way = max(q, 0.0);
    if (way.x + way.y <= 0.0) {
        // Between the corners: the nearer side is the one whose edge is closest.
        way = q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    }
    return normalize(way) * sign(point + vec2(0.0001, 0.0001));
}

#endif

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

#ifdef CG_FULL
// How steeply the surface climbs a fraction [along] of the way up the edge, for each of the
// three shapes an edge can be. A chamfer climbs at a constant angle, a fillet rolls over,
// and a dome keeps curving across the whole face.
float slopeOf(float kind, float along) {
    float rest = clamp(1.0 - along, 0.0, 1.0);
    // A chamfer climbs at one angle and then stops dead, which is the crease along the top
    // of it; softened over the last of the climb so the crease is a line, not a staircase.
    if (kind < 5.5) return 1.0 - smoothstep(0.96, 1.0, along);
    // A fillet rolls over, and at the very edge of the roll the surface is nearly on its
    // side. Left alone that is a normal pointing almost outwards, which catches the light
    // in a hard white line along the rim; held to a sane angle it is a rolled edge.
    if (kind < 6.5) return rest / max(sqrt(1.0 - rest * rest), 0.42);
    return rest * 1.6;                                           // dome: curving all the way
}

// Where a lit surface stops climbing straight towards white and starts easing into it.
const float Knee = 0.8;
#endif

vec4 shaded() {
    vec4 sampled = texture(u_texture, v_texCoord);
    float aa = v_shape.z;

    // A picture or a glyph: no shape to work out, just the texture. A premultiplied
    // picture is straightened first, since everything here works in straight alpha.
    if (aa <= 0.0) {
        // A picture whose radii hold a box is a layer in the corner of a bigger pooled
        // picture: a read within half a texel of the corner's edge is made again, held inside
        // it, as a picture its own size is clamped at its edge, rather than reading the clear
        // strip round it. Only there: everywhere else, and for text and shapes, the one plain
        // read straight from the in stands. See holdInside.
        //
        // Not in the common program: a GPU reads every in a path might need before it
        // starts, so the radii would cost every letter a third more. Held pictures take the
        // held program, which is the common one with this read put back.
        #if defined(CG_FULL) || defined(CG_HELD)
        if (v_radii.z > 0.0) {
            vec2 held = clamp(v_texCoord, v_radii.xy, v_radii.zw);
            if (held != v_texCoord) sampled = texture(u_texture, held);
        }
        #endif
        if (v_gradient.x > 0.5 && sampled.a > 0.0) sampled = vec4(sampled.rgb / sampled.a, sampled.a);
        return v_color * sampled;
    }

    float radius = cornerRadius(v_local, v_radii);
    float borderWidth = v_shape.x;
    float spread = v_shape.y;

    float distance = roundedBox(v_local, v_halfSize, radius);
    float coverage = 1.0 - smoothstep(-aa, aa, distance);

    #ifdef CG_FULL
    // A lit surface rather than a filled one: the shape is given a height along its edge,
    // the normal of that height is worked out here, and one light is shone on it. What
    // comes out is the difference the light makes — dark where it falls away, bright where
    // it faces the light — which lies over whatever fill is underneath.
    if (v_gradient.x > 4.5) {
        float bevel = max(v_gradient.y, 0.0001);
        float along = clamp(max(-distance, 0.0) / bevel, 0.0, 1.0);
        vec2 out2 = towardsEdge(v_local, v_halfSize, radius);
        vec3 normal = normalize(vec3(-out2 * slopeOf(v_gradient.x, along), 1.0));
        vec3 light = normalize(v_shadowColor.xyz * 2.0 - 1.0);
        float lit = dot(normal, light);
        float facing = clamp(lit, -1.0, 1.0);
        // Straight on, so the half vector between the eye and the light is all it takes.
        // "half" is a reserved word in this dialect, so the half vector is halfway.
        vec3 halfway = normalize(light + vec3(0.0, 0.0, 1.0));
        // How polished the surface is decides how tight the shine is: a low number spreads
        // it across the whole lit side, which is wet-looking plastic, and a high one draws
        // it to a point, which is glass. It rides in the fill colour's red, unused here.
        float tightness = mix(2.0, 60.0, clamp(v_color.r, 0.0, 1.0));
        float shine = pow(max(dot(normal, halfway), 0.0), tightness) * v_shadowColor.w;
        float strength = v_gradient.z;
        // Measured against what facing the light squarely would give, rather than against
        // nothing: a flat face is neither lit nor shaded whatever height the light is at,
        // and a surface turned fully towards it gets the whole of the strength asked for.
        // Without this the light's own elevation quietly decides how much difference it
        // makes, and a high light leaves everything nearly flat.
        float reach = max(1.0 - light.z, 0.2);
        float shade = clamp((facing - light.z) / reach, -1.0, 1.0) * strength;

        // Given a colour of its own, the light is put on that colour: a lit face is the
        // same green made brighter, which is what a painted button is. Laid over somebody
        // else's fill instead, all that can be added is white and black, and white takes
        // the colour out of a highlight — the thing that makes a drawn button look washed.
        if (v_shape.x > 0.5) {
            // The face's own colour, which may be a run of colours rather than one. Painted
            // art almost always grades the body of a button from light at the top to deep at
            // the bottom, and that grade is not a brightness: the light end is a different,
            // yellower green. Lighting cannot invent that, so the artist names it and the
            // light goes on top. The run lies along one row of the atlas, so only u moves,
            // and it runs the way the light falls — the same light that shapes the edge.
            vec3 base = v_borderColor.rgb;
            if (v_shape.y < 0.0) {
                // A material rather than a colour: wood, paper, brushed metal. It is laid
                // across the face and multiplied into the face's colour, so one grey grain
                // makes oak or walnut depending only on what it is tinted with — and the
                // light still shapes the edge over the top of it.
                vec2 grainAt = v_local / (2.0 * v_halfSize) + 0.5;
                grainAt.y = 1.0 - grainAt.y;      // pictures count down; this quad counts up
                vec4 grain = texture(u_texture, fract(grainAt * -v_shape.y));
                base = base * (grain.a > 0.0 ? grain.rgb / grain.a : grain.rgb);
            } else if (v_shape.y > 0.0) {
                // Zero at the lit end of the box and one at the far end, measured along the
                // light rather than down the screen, so turning the light turns the run too.
                vec2 axis = light.xy;
                float span = max(length(axis), 0.0001);
                vec2 unit = axis / span;
                float across = max(abs(unit.x) * v_halfSize.x + abs(unit.y) * v_halfSize.y, 0.0001);
                float down = clamp(0.5 + dot(v_local, unit) / across * 0.5, 0.0, 1.0);
                vec2 at = vec2(v_texCoord.x + v_shape.y * down, v_texCoord.y);
                vec4 strip = texture(u_texture, at);
                base = strip.a > 0.0 ? strip.rgb / strip.a : strip.rgb;
            }
            // Lighting a colour is not one multiplication. A lit face climbs fast and
            // carries a little white with it, the way a bright surface washes towards the
            // colour of the light; a shaded one falls away more gently and keeps its hue,
            // because nothing is washing it out. Painted art does both, and a single
            // multiply in either direction is what makes a drawn button look plastic.
            float gain = shade > 0.0 ? 1.0 + shade * 1.6 : 1.0 + shade * 0.9;
            vec3 face = base * clamp(gain, 0.0, 4.0);
            face = mix(face, vec3(1.0), clamp(shade, 0.0, 1.0) * 0.25);
            // Screened rather than added: a shine climbs towards white and slows as it
            // gets there, instead of clipping to a flat white stripe with an edge on it.
            face = face + (1.0 - face) * shine;
            // Bright ends roll off instead of stopping dead at white. Without this the
            // channel nearest full — green, in almost every green button — flattens to 255
            // while the others keep climbing, so a lit edge slides towards cyan and then
            // ends in a hard white bar with a visible edge on it. Below the knee nothing
            // changes at all, so ordinary colour is untouched.
            vec3 over = max(face - Knee, 0.0) / (1.0 - Knee);
            face = min(face, 1.0 - (1.0 - Knee) * exp(-over));
            return vec4(clamp(face, 0.0, 1.0), v_borderColor.a * coverage);
        }

        vec4 lift = vec4(1.0, 1.0, 1.0, clamp(shade, 0.0, 1.0));
        vec4 dark = vec4(0.0, 0.0, 0.0, clamp(-shade, 0.0, 1.0));
        vec4 relief = over(lift, dark);
        relief.a = max(relief.a, clamp(shine, 0.0, 0.8));
        relief.rgb = mix(relief.rgb, vec3(1.0), clamp(shine, 0.0, 1.0));
        return vec4(relief.rgb, relief.a * coverage);
    }
    #endif

    // A gradient. Two colours mix in the vertex, the end riding in the border's slot; a run
    // of stops is a strip along one row of the atlas, starting at the quad's own texture
    // coordinate and ending at the u that rides in the spread.
    vec4 fill = v_color;
    // What the texture contributes: the atlas's white block for a flat fill. A run of stops
    // reads the strip itself, and the quad's own texture coordinate is that strip's start,
    // so it takes its colour from the strip alone rather than multiplying by it twice. It
    // casts no shadow: its spread is where the strip ends.
    vec4 texel = sampled;
    #ifdef CG_FULL
    if (v_gradient.x > 2.5) {
        texel = vec4(1.0);
        spread = 0.0;
    }
    #endif
    if (v_gradient.x > 0.5) {
        bool outwards = v_gradient.x > 1.5 && v_gradient.x < 2.5 || v_gradient.x > 3.5;
        float along = outwards
            ? length(v_local / max(v_halfSize, vec2(0.0001)))
            : dot(v_local, v_gradient.yz) + 0.5;
        along = clamp(along, 0.0, 1.0);
        #ifdef CG_FULL
        if (v_gradient.x > 2.5) {
            vec2 to = vec2(v_shape.y, v_texCoord.y);
            vec4 strip = texture(u_texture, mix(v_texCoord, to, along));
            // The strip is premultiplied, so that the GPU's own mixing is the right mix.
            if (strip.a > 0.0) strip = vec4(strip.rgb / strip.a, strip.a);
            fill = strip * v_color;
        } else
        #endif
        {
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
    }
    #ifdef CG_FULL
    else if (spread < 0.0) {
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
    #endif

    return result;
}

// How much of a pixel is inside the rounded clip in force: the box's own distance, in its
// units, turned into one pixel of soft edge centred on the line, as a cut picture's feather is.
// gl_FragCoord is the pixel's middle, counted up from the bottom of the target.
float masked() {
    vec2 point = (gl_FragCoord.xy - u_maskBox.xy) / u_maskScale;
    float distance = roundedBox(point, u_maskBox.zw, cornerRadius(point, u_maskRadii));
    return clamp(0.5 - distance * min(u_maskScale.x, u_maskScale.y), 0.0, 1.0);
}

void main() {
    vec4 colour = shaded();
    // A rounded clip in force trims what lands. A premultiplied colour is trimmed in all four
    // channels and a straight one in its opacity alone, which is the same thing once blended.
    if (u_maskMode > 0.5) {
        float kept = masked();
        colour = u_maskMode > 1.5 ? colour * kept : vec4(colour.rgb, colour.a * kept);
    }
    cg_FragColor = colour;
}