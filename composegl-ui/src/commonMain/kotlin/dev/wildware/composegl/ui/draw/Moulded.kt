package dev.wildware.composegl.ui.draw

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.graphics.boxShadow
import dev.wildware.composegl.ui.modifier.MouldedElement
import kotlin.math.cos
import kotlin.math.sin

/**
 * A box lit from one direction, drawn in the order light falls on a real one.
 *
 * The shadow first, under everything, thrown away from the light. Then the edge facing the light,
 * lifted; then the far edge, shaded. Then the shine across the lit side, which is a run of colours
 * at the light's own angle rather than a band stuck to the top, so a box lit from the left has its
 * shine down the left. The line round the outside last, over all of it.
 *
 * Everything is a fraction of the shorter side, so one call dresses a button and a checkbox.
 */
internal object Moulded {

    fun draw(canvas: UiCanvas, rect: Rect, moulded: MouldedElement) {
        val across = minOf(rect.width, rect.height)
        if (across <= 0f) return
        val corners = moulded.corners
        val radians = moulded.light * PiOver180
        // Where the light falls, as a step across the box. Y counts down the screen, as angles turn.
        val fallX = cos(radians)
        val fallY = sin(radians)
        val depth = across * moulded.depth

        if (moulded.shadow > 0f) {
            canvas.boxShadow(rect, Colour.Black.scaleAlpha(0.45f * moulded.strength + 0.15f), across * moulded.shadow, corners)
        }
        if (depth > 0f && moulded.strength > 0f) {
            // An offset gathers shade along the edge it points away from, so the light's own step
            // lifts the edge it comes from and the opposite one darkens.
            canvas.innerShade(rect, Colour.White.scaleAlpha(moulded.strength * 0.8f), depth, corners, fallX * depth, fallY * depth)
            canvas.innerShade(rect, Colour.Black.scaleAlpha(moulded.strength), depth, corners, -fallX * depth, -fallY * depth)
        }
        if (moulded.shine > 0f && moulded.strength > 0f) {
            // Lighter than the face rather than whiter: a shine that washes the colour out of a
            // button is the thing that makes a drawn one look cheap.
            val lit = Colour.White.scaleAlpha(moulded.strength * 0.9f)
            canvas.rect(
                rect,
                Brush.Ramp(
                    listOf(
                        Brush.Stop(0f, lit),
                        Brush.Stop(moulded.shine * 0.7f, lit.scaleAlpha(0.8f)),
                        Brush.Stop(moulded.shine, Colour.Transparent),
                        Brush.Stop(1f, Colour.Transparent),
                    ),
                    degrees = moulded.light,
                ),
                corners,
            )
        }
        moulded.outline?.let { colour ->
            val width = across * moulded.outlineWidth
            if (width > 0f) canvas.borderOutside(rect, colour, width, corners)
        }
    }

    private const val PiOver180 = 0.017453292f
}
