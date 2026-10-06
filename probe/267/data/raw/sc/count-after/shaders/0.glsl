#version 300 es
#if defined(GL_ES) && !defined(CG_FULL)
#define CG_SMALL mediump
#else
#define CG_SMALL
#endif

in vec3 a_position;
in vec4 a_color;
in vec4 a_borderColor;
in vec4 a_shadowColor;
in vec2 a_texCoord0;
in vec2 a_local;
in vec2 a_halfSize;
in vec3 a_shape;
in vec4 a_radii;
in vec3 a_gradient;

uniform mat4 u_projTrans;

out CG_SMALL vec4 v_color;
out CG_SMALL vec4 v_borderColor;
out CG_SMALL vec4 v_shadowColor;
out vec2 v_texCoord;
out vec2 v_local;
out vec2 v_halfSize;
out CG_SMALL vec3 v_shape;
out vec4 v_radii;
out CG_SMALL vec3 v_gradient;

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