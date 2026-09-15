package dev.wildware.composegl.render.gl

/**
 * The OpenGL numbers the renderer uses. Desktop GL, OpenGL ES and WebGL share every one of them, so
 * one table serves every binding.
 */
@Suppress("unused")
object GlConst {
    const val TRIANGLES = 0x0004
    const val UNSIGNED_BYTE = 0x1401
    const val UNSIGNED_SHORT = 0x1403
    const val FLOAT = 0x1406

    const val BLEND = 0x0BE2
    const val SCISSOR_TEST = 0x0C11
    const val DEPTH_TEST = 0x0B71
    const val CULL_FACE = 0x0B44
    const val STENCIL_TEST = 0x0B90

    const val ZERO = 0
    const val ONE = 1
    const val SRC_ALPHA = 0x0302
    const val ONE_MINUS_SRC_ALPHA = 0x0303
    const val FUNC_ADD = 0x8006

    const val COLOR_BUFFER_BIT = 0x4000

    const val ARRAY_BUFFER = 0x8892
    const val ELEMENT_ARRAY_BUFFER = 0x8893
    const val STATIC_DRAW = 0x88E4
    const val STREAM_DRAW = 0x88E0

    const val VERTEX_SHADER = 0x8B31
    const val FRAGMENT_SHADER = 0x8B30

    const val TEXTURE_2D = 0x0DE1
    const val TEXTURE0 = 0x84C0
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val LINEAR = 0x2601
    const val NEAREST = 0x2600
    const val CLAMP_TO_EDGE = 0x812F
    const val RGBA = 0x1908
    const val RGBA8 = 0x8058
    const val UNPACK_ALIGNMENT = 0x0CF5
    const val PACK_ALIGNMENT = 0x0D05

    const val FRAMEBUFFER = 0x8D40
    const val COLOR_ATTACHMENT0 = 0x8CE0
    const val FRAMEBUFFER_COMPLETE = 0x8CD5

    // queries
    const val FRAMEBUFFER_BINDING = 0x8CA6
    const val VIEWPORT = 0x0BA2
    const val SCISSOR_BOX = 0x0C10
    const val CURRENT_PROGRAM = 0x8B8D
    const val ARRAY_BUFFER_BINDING = 0x8894
    const val ELEMENT_ARRAY_BUFFER_BINDING = 0x8895
    const val VERTEX_ARRAY_BINDING = 0x85B5
    const val ACTIVE_TEXTURE = 0x84E0
    const val TEXTURE_BINDING_2D = 0x8069
    const val BLEND_SRC_RGB = 0x80C9
    const val BLEND_DST_RGB = 0x80C8
    const val BLEND_SRC_ALPHA = 0x80CB
    const val BLEND_DST_ALPHA = 0x80CA
    const val BLEND_EQUATION_RGB = 0x8009
    const val BLEND_EQUATION_ALPHA = 0x883D
    const val COLOR_WRITEMASK = 0x0C23
    const val MAX_TEXTURE_SIZE = 0x0D33
}
