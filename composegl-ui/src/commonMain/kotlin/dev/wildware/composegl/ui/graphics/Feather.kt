package dev.wildware.composegl.ui.graphics

import kotlin.math.sqrt

/**
 * A convex outline cut into quads, with an edge that fades out rather than stopping dead.
 *
 * What a backend's [UiCanvas.cutLayer] is made of, kept here so that every backend cuts the same
 * shape the same way and the arithmetic is tested once, without a GPU.
 *
 * A picture drawn through a hard-edged fan comes out with a staircase round every curve — a round
 * portrait looks like it was cut out with blunt scissors. So the outline is drawn twice over: once
 * pulled in by half of [feather] and filled solid, and once as a thin ring out to half of [feather]
 * beyond it, whose inner corners are covered and whose outer corners are not. Interpolating that
 * coverage across one screen pixel is the antialiasing, and it needs nothing from the shader.
 *
 * Each point moves along the line from the outline's middle, which is exact for a circle and near
 * enough for a polygon's corner. Convex is required: the middle has to be inside.
 *
 * [quad] is handed four corners as x, y and a coverage of 1 or 0, in the order a batch winds them
 * — 0, 1, 2 then 2, 3, 0 — so each call is two triangles and nothing else. [outline] is x, y pairs
 * in whatever coordinates the caller wants back; fewer than three points hands back nothing.
 *
 * Inline, so a backend's lambda costs no object. The one allocation is the scratch ring.
 */
@Suppress("LongParameterList")
inline fun featherOutline(
    outline: FloatArray,
    feather: Float,
    quad: (
        ax: Float, ay: Float, aCover: Float,
        bx: Float, by: Float, bCover: Float,
        cx: Float, cy: Float, cCover: Float,
        dx: Float, dy: Float, dCover: Float,
    ) -> Unit,
) {
    val count = outline.size / 2
    if (count < 3) return

    var hubX = 0f
    var hubY = 0f
    for (i in 0 until count) {
        hubX += outline[i * 2]
        hubY += outline[i * 2 + 1]
    }
    hubX /= count
    hubY /= count

    // Inner x, y then outer x, y, per point.
    val ring = FloatArray(count * 4)
    val half = feather / 2f
    for (i in 0 until count) {
        val x = outline[i * 2]
        val y = outline[i * 2 + 1]
        val awayX = x - hubX
        val awayY = y - hubY
        val length = sqrt(awayX * awayX + awayY * awayY)
        // Never pulled in past the middle: a shape smaller than its own feather is all edge.
        val step = if (length > 0f) minOf(half, length) / length else 0f
        val out = if (length > 0f) half / length else 0f
        ring[i * 4] = x - awayX * step
        ring[i * 4 + 1] = y - awayY * step
        ring[i * 4 + 2] = x + awayX * out
        ring[i * 4 + 3] = y + awayY * out
    }

    for (i in 0 until count) {
        val a = i * 4
        val b = (i + 1) % count * 4
        // The solid middle: the hub and one edge, with its last corner repeated into a triangle of
        // no area, since the batch draws nothing but quads.
        quad(
            hubX, hubY, 1f,
            ring[a], ring[a + 1], 1f,
            ring[b], ring[b + 1], 1f,
            ring[b], ring[b + 1], 1f,
        )
        // The soft edge.
        quad(
            ring[a], ring[a + 1], 1f,
            ring[a + 2], ring[a + 3], 0f,
            ring[b + 2], ring[b + 3], 0f,
            ring[b], ring[b + 1], 1f,
        )
    }
}
