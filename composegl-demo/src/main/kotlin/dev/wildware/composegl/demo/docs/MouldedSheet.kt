package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.moulded
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size

/**
 * The sheet's buttons again, with nothing but a gradient and [moulded].
 *
 * [ArtSheet] paints the same page from the original's pixels: fourteen measured stops a piece, plus
 * a measured band for the shine. This one takes three colours off each piece — near the top, the
 * middle and the bottom — and hands everything else to the modifier's own light. So it is what a
 * game gets by writing two lines, rather than what a measurement gets.
 *
 * Only the pieces that are one rounded box: the wooden bars, the capsules, the squares and the
 * tall buttons. A bar with a fill in it, a banner, a gem and a berry are other shapes' business.
 */
@Composable
internal fun MouldedSheet() {
    Box(Modifier.fillMaxSize().background(Colour.White)) {
        Sheet.pieces
            .filter { it.kind == Sheet.Kind.Box && it.fill == null && it.y < 660f }
            .forEach { piece -> Button(piece) }
    }
}

@Composable
private fun Button(piece: Sheet.Piece) {
    val line = piece.outline
    val corner = (piece.corner - line).coerceAtLeast(0f)
    // Three colours off the original's face, and the light does the rest.
    val face = listOf(colourAt(piece, 0.16f), colourAt(piece, 0.55f), colourAt(piece, 0.9f))
    val run = Brush.Ramp(
        Brush.evenly(face).let { (it as Brush.Ramp).stops },
        degrees = if (piece.across) 0f else 90f,
    )
    Box(
        Modifier
            .offset(piece.x + line, piece.y + line)
            .size(piece.width - line * 2f, piece.height - line * 2f)
            .background(run, corners = Corners.all(corner))
            .moulded(
                corners = Corners.all(corner),
                // A piece taller than it is wide is lit from the left on that page, not from above.
                light = if (piece.across) 0f else 90f,
                depth = Tuning[0],
                shine = Tuning[1],
                strength = Tuning[2],
                outline = Colour.rgb(piece.ink),
                outlineWidth = line / minOf(piece.width, piece.height),
            ),
    )
}

/**
 * Depth, shine and strength, so the numbers can be tried against the original without a rebuild:
 * `COMPOSEGL_MOULD=0.22,0.4,0.3`.
 *
 * The defaults are where searching against the original settled: a lighter touch than the eye first
 * reaches for. Pushed further the buttons look glossier and measure worse, because a strong light
 * moves every pixel of a big flat face and only flatters the small bright band.
 */
private val Tuning: FloatArray = (System.getenv("COMPOSEGL_MOULD") ?: "0.10,0.30,0.18")
    .split(',').map { it.trim().toFloat() }.toFloatArray()

/** The colour a fraction of the way down the original's face. */
private fun colourAt(piece: Sheet.Piece, at: Float): Colour {
    val stop = piece.stops.minByOrNull { kotlin.math.abs(it.first - at) } ?: piece.stops.first()
    return Colour.rgb(stop.second)
}
