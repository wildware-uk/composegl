package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size

/**
 * A bought art sheet of casual-game interface pieces, drawn again with modifiers and no art.
 *
 * Every number here was read out of the sheet's own pixels — where each piece sits, how big it is,
 * how round its corners are, how thick its line is and the colours down the middle of it; see
 * [Sheet]. So this is not a drawing in the same style. It is that page, measured, and painted again
 * out of `background`, `borderOutside` and a [Brush.Ramp] of fourteen stops.
 *
 * Three shapes on the page are not rounded boxes and are drawn onto the canvas instead: the banner
 * with pointed ends, the cut gem and the berry. Each is a polygon with a larger dark one under it,
 * which is an outline outside a shape that [borderOutside] cannot follow.
 */
@Composable
internal fun ArtSheet() {
    Box(Modifier.fillMaxSize().background(Colour.White)) {
        Sheet.pieces.forEach { piece ->
            Piece(piece)
            piece.gloss?.let { Shine(it) }
            piece.fill?.let { fill ->
                Piece(fill)
                fill.gloss?.let { Shine(it) }
            }
        }
    }
}

@Composable
private fun Piece(piece: Sheet.Piece) {
    // The measured box includes the piece's own line, and borderOutside draws outside the node, so
    // the node is that box less the line: the drawn piece then covers exactly what the original did.
    val line = if (piece.kind == Sheet.Kind.Box || piece.kind == Sheet.Kind.Plate) piece.outline else 0f
    val place = Modifier
        .offset(piece.x + line, piece.y + line)
        .size(piece.width - line * 2f, piece.height - line * 2f)
    when (piece.kind) {
        Sheet.Kind.Box, Sheet.Kind.Plate -> Box(
            place
                .background(ramp(piece), corner = piece.corner - line)
                .borderOutside(Colour.rgb(piece.ink), width = piece.outline, corner = piece.corner - line),
        )
        Sheet.Kind.Banner -> Box(place.drawBehind { bounds -> banner(bounds, piece) })
        Sheet.Kind.Gem -> Box(place.drawBehind { bounds -> gem(bounds, piece) })
        Sheet.Kind.Berry -> Box(place.drawBehind { bounds -> berry(bounds, piece) })
    }
}

/**
 * The bright band across the top of a piece, where the sheet puts it.
 *
 * Its own box rather than part of the run down the piece: on the page the shine is narrower than
 * the piece and rounded at its ends, and a run down the whole face would carry it into the corners.
 */
@Composable
private fun Shine(gloss: Sheet.Gloss) {
    Box(
        Modifier
            .offset(gloss.x, gloss.y)
            .size(gloss.width, gloss.height)
            .background(
                Brush.Ramp(gloss.stops.map { (at, colour) -> Brush.Stop(at, Colour.rgb(colour)) }),
                corner = gloss.height / 2f,
            ),
    )
}

/** The colours read off the piece, as the gradient that paints it again. */
private fun ramp(piece: Sheet.Piece): Brush = Brush.Ramp(
    piece.stops.map { (at, colour) -> Brush.Stop(at, Colour.rgb(colour)) },
    degrees = if (piece.across) 0f else 90f,
)

/** The colour halfway down a piece, for the shapes that are painted flat rather than run. */
private fun Sheet.Piece.middle(): Colour = Colour.rgb(stops[stops.size / 2].second)

private fun Sheet.Piece.top(): Colour = Colour.rgb(stops[1].second)

private fun dev.wildware.composegl.ui.graphics.UiCanvas.banner(bounds: Rect, piece: Sheet.Piece) {
    val point = bounds.height * 0.5f
    fun shape(box: Rect, tip: Float) = floatArrayOf(
        box.left + tip, box.top,
        box.right - tip, box.top,
        box.right, box.centre.y,
        box.right - tip, box.bottom,
        box.left + tip, box.bottom,
        box.left, box.centre.y,
    )
    val ink = Colour.rgb(piece.ink)
    val grow = piece.outline
    polygon(shape(Rect(bounds.left - grow, bounds.top - grow, bounds.right + grow, bounds.bottom + grow), point + grow), ink)
    polygon(shape(bounds, point), piece.middle())
    val shine = Rect(bounds.left + point, bounds.top + grow, bounds.right - point, bounds.top + bounds.height * 0.34f)
    rect(shine, Brush.vertical(piece.top(), Colour.Transparent), corner = shine.height / 2f)
}

private fun dev.wildware.composegl.ui.graphics.UiCanvas.gem(bounds: Rect, piece: Sheet.Piece) {
    fun cut(box: Rect) = floatArrayOf(
        box.centre.x, box.top,
        box.right, box.top + box.height * 0.34f,
        box.centre.x, box.bottom,
        box.left, box.top + box.height * 0.34f,
    )
    val grow = piece.outline
    polygon(cut(Rect(bounds.left - grow, bounds.top - grow, bounds.right + grow, bounds.bottom + grow)), Colour.rgb(piece.ink))
    polygon(cut(bounds), piece.middle())
    // The crown, lit: two facets off the top point.
    polygon(
        floatArrayOf(
            bounds.centre.x, bounds.top + grow,
            bounds.centre.x, bounds.top + bounds.height * 0.36f,
            bounds.left + bounds.width * 0.16f, bounds.top + bounds.height * 0.32f,
        ),
        piece.top(),
    )
    polygon(
        floatArrayOf(
            bounds.centre.x, bounds.top + grow,
            bounds.right - bounds.width * 0.16f, bounds.top + bounds.height * 0.32f,
            bounds.centre.x, bounds.top + bounds.height * 0.36f,
        ),
        piece.top().scaleAlpha(0.75f),
    )
}

private fun dev.wildware.composegl.ui.graphics.UiCanvas.berry(bounds: Rect, piece: Sheet.Piece) {
    val body = Rect(bounds.left, bounds.top + bounds.height * 0.22f, bounds.right, bounds.bottom)
    val around = listOf(
        0.10f to 0.24f, 0.26f to 0.06f, 0.50f to 0.00f, 0.74f to 0.06f, 0.90f to 0.24f,
        0.92f to 0.48f, 0.74f to 0.80f, 0.50f to 1.00f, 0.26f to 0.80f, 0.08f to 0.48f,
    )
    fun fruit(box: Rect) = FloatArray(around.size * 2) { at ->
        val (x, y) = around[at / 2]
        if (at % 2 == 0) box.left + box.width * x else box.top + box.height * y
    }
    val grow = piece.outline
    polygon(fruit(Rect(body.left - grow, body.top - grow, body.right + grow, body.bottom + grow)), Colour.rgb(piece.ink))
    polygon(fruit(body), piece.middle())
    listOf(0.34f to 0.42f, 0.62f to 0.38f, 0.5f to 0.64f, 0.44f to 0.8f).forEach { (x, y) ->
        circle(Offset(body.left + body.width * x, body.top + body.height * y), body.width * 0.045f, Colour.rgb(0xFFE08A))
    }
    val hull = Rect(bounds.left + bounds.width * 0.14f, bounds.top, bounds.right - bounds.width * 0.14f, bounds.top + bounds.height * 0.3f)
    val leaf = Colour.rgb(0x3FA84B)
    listOf(0f to 0.32f, 0.5f to 0f, 1f to 0.32f).forEach { (x, lift) ->
        polygon(
            floatArrayOf(
                hull.centre.x, hull.bottom,
                hull.left + hull.width * x, hull.top + hull.height * lift,
                hull.centre.x + (x - 0.5f) * hull.width * 0.35f, hull.bottom - hull.height * 0.1f,
            ),
            leaf,
        )
    }
}
