#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
out vec4 cg_FragColor;
in vec2 v_texCoord;
uniform sampler2D u_texture;
uniform vec2 u_textureSize;
uniform vec2 u_size;
uniform float u_alpha;

        uniform float u_radius;
        uniform vec2 u_direction;

        // Six each side of the middle. Enough that the steps land inside a pixel or two of each
        // other at the radii an interface actually uses, and few enough to stay cheap on a phone.
        const int TAPS = 6;

        vec4 clearOutside(vec2 at) {
    vec2 inside = step(vec2(0.0), at) * step(at, vec2(1.0));
    return texture(u_texture, clamp(at, 0.0, 1.0)) * inside.x * inside.y;
}

        void main() {
            // One design unit is 1.0 / u_size of the picture, so a step measured in design units
            // is the same distance whatever the screen is.
            float spacing = u_radius / float(TAPS);
            vec2 stride = u_direction * spacing / u_size;
            // Half the radius, which puts the furthest tap two standard deviations out: past there
            // a gaussian has nothing left worth sampling.
            float sigma = max(u_radius * 0.5, 0.0001);

            vec4 total = texture(u_texture, v_texCoord);
            float weight = 1.0;
            for (int i = 1; i <= TAPS; i++) {
                float distance = spacing * float(i);
                float w = exp(-(distance * distance) / (2.0 * sigma * sigma));
                total += clearOutside(v_texCoord + stride * float(i)) * w;
                total += clearOutside(v_texCoord - stride * float(i)) * w;
                weight += 2.0 * w;
            }

            cg_FragColor = (total / weight) * u_alpha;
        }