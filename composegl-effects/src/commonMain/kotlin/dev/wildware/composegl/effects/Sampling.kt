package dev.wildware.composegl.effects

/**
 * A sampler that treats everything outside the picture as clear, shared by the effects that read
 * more than one pixel.
 *
 * Needed because a blur reaching past the edge of its picture otherwise reads whatever the driver
 * decides is out there — the edge pixel smeared, or the opposite edge wrapped around — and the two
 * backends this toolkit ships do not have to agree on which. Reading clear instead means a blurred
 * panel fades out at its own edge, which is what a blur is supposed to look like, and it means both
 * backends draw the same picture.
 *
 * Pair it with an effect's bleed: the bleed makes the picture bigger than the widget, so the fade
 * happens in the empty margin rather than across the widget's own edge.
 */
internal val OutsideIsClear = """
    vec4 clearOutside(vec2 at) {
        vec2 inside = step(vec2(0.0), at) * step(at, vec2(1.0));
        return texture2D(u_texture, clamp(at, 0.0, 1.0)) * inside.x * inside.y;
    }
""".trimIndent()
