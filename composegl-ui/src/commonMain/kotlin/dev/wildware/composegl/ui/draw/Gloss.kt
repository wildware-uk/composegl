package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.modifier.GlossElement

/**
 * The shine across the top of a box, as one gradient that fades away downwards.
 *
 * Its own file because it is the only painted modifier that is not one canvas call: the shine is a
 * box of its own, rounded by the top corners of the box it lies on and square along the bottom,
 * where it has already faded to nothing and no corner would be seen.
 */
internal object Gloss {

    fun draw(canvas: UiCanvas, rect: Rect, gloss: GlossElement) {
        val inset = gloss.inset.coerceAtMost(rect.width / 2f)
        val height = rect.height * gloss.fraction
        if (height <= 0f || rect.width - inset * 2f <= 0f) return
        val shine = Rect(rect.left + inset, rect.top, rect.right - inset, rect.top + height)
        // A radius no bigger than the shine itself, and pulled in with it: a shine inset from the
        // sides sits inside the curve rather than cutting across it.
        val most = minOf(shine.width, shine.height) / 2f
        val corners = Corners(
            topLeft = (gloss.corners.topLeft - inset).coerceIn(0f, most),
            topRight = (gloss.corners.topRight - inset).coerceIn(0f, most),
        )
        canvas.rect(shine, Brush.vertical(gloss.colour, Colour.Transparent), corners)
    }
}
