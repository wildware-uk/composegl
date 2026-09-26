package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size

/**
 * The sheet's buttons again, with nothing but a gradient and [moulded].
 *
 * [ArtSheet] paints the same page from the original's pixels: fourteen measured stops a piece, plus
 * a measured band for the shine. This one takes three colours off each piece — near the top, the
 * middle and the bottom — and gives the rest to one light on a surface with a chamfered edge. So it
 * is what a game gets by writing two lines, rather than what a measurement gets.
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
    // Three colours off the original's body, and the light does the rest. The body only: the bright
    // band along the top is the lit edge, and a run that included it would smooth out the crease
    // the chamfer is there to make.
    // Each colour stays where it was read, rather than being spread out evenly, so the run holds
    // its end colours past the last reading instead of carrying on down into the dark lip.
    val run = Brush.Ramp(
        listOf(0.18f, 0.5f, 0.8f).map { Brush.Stop(it, colourAt(piece, it)) },
        degrees = if (piece.across) 0f else 90f,
    )
    Box(
        Modifier
            .offset(piece.x + line, piece.y + line)
            .size(piece.width - line * 2f, piece.height - line * 2f)
            // A surface with a shape, lit — and the light is put on the face's own colour rather
            // than over it, so a highlight is that green made brighter instead of whiter.
            .relief(
                corners = Corners.all(corner),
                shape = if (Tuning.size > 5 && Tuning[5] > 0.5f) Relief.Dome else Relief.Chamfer,
                depth = Tuning[0],
                // A piece taller than it is wide is lit from the left on that page, not from above.
                light = if (piece.across) 0f else 90f,
                elevation = Tuning[1],
                strength = Tuning[2],
                gloss = Tuning[3],
                polish = Tuning[4],
                // The original's own body colours, lit: the graded body a painted button has is a
                // change of colour, not a change of brightness, so no amount of lighting one flat
                // green will make it. The light shapes the edge and puts the shine on top.
                faceRun = run,
            )
            .borderOutside(Colour.rgb(piece.ink), width = line, corners = Corners.all(corner)),
    )
}

/**
 * Depth, shine and strength, so the numbers can be tried against the original without a rebuild:
 * `COMPOSEGL_MOULD=depth,elevation,strength,gloss,polish`.
 *
 * The defaults are where a search against the original settled.
 */
private val Tuning: FloatArray = (System.getenv("COMPOSEGL_MOULD") ?: "0.12,42,0.4,0.3,0.6,0")
    .split(',').map { it.trim().toFloat() }.toFloatArray()

/** The colour a fraction of the way down the original's face. */
private fun colourAt(piece: Sheet.Piece, at: Float): Colour {
    val stop = piece.stops.minByOrNull { kotlin.math.abs(it.first - at) } ?: piece.stops.first()
    return Colour.rgb(stop.second)
}
