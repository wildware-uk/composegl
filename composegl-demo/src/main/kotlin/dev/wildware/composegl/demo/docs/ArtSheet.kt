package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.gloss
import dev.wildware.composegl.ui.modifier.innerShade
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size

/**
 * A bought art sheet of casual-game interface pieces, drawn again with modifiers and no art.
 *
 * Every piece here — the wooden bars, the capsules, the square buttons, the plates and banners, the
 * progress bars, the checkboxes, the switches — is a box with a fill, some shade and an outline on
 * it. Nothing on this page is a texture, and the only thing drawn by hand is the tick, which is two
 * lines.
 *
 * The recipe is the same everywhere and measured in fractions of a piece's height, so one [moulded]
 * draws a capsule 44 tall and a checkbox 30 tall without being retuned.
 */
@Composable
internal fun ArtSheetButtons() {
    Column(
        Modifier.background(Colour.White).padding(14f),
        verticalArrangement = Arrangement.spacedBy(10f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
            Woods.forEach { Piece(it, 188f, 38f, corner = 11f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
            Greens.forEach { Piece(it, 92f, 30f) }
            Blues.forEach { Piece(it, 92f, 30f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
            Yellows.forEach { Piece(it, 92f, 30f) }
            Reds.forEach { Piece(it, 92f, 30f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
            Greys.forEach { Piece(it, 92f, 30f) }
            listOf(Woods[0], Yellows[0], Blues[0], Reds[0], Greys[0]).forEach { Piece(it, 34f, 34f, corner = 9f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
            Woods.forEach { Piece(it, 44f, 80f, corner = 12f) }
            Plate(Woods[0], 150f)
            Plate(Woods[1], 150f)
            Banner(Woods[0], 130f)
        }
    }
}

@Composable
internal fun ArtSheetControls() {
    Column(
        Modifier.background(Colour.White).padding(14f),
        verticalArrangement = Arrangement.spacedBy(12f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                listOf(Yellows[0] to 0.78f, Blues[0] to 0.62f, Greens[0] to 0.5f, Reds[0] to 0.42f)
                    .forEach { (face, filled) -> Progress(face, 260f, 18f, filled) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                listOf(Yellows[0] to 0.55f, Blues[0] to 0.45f, Greens[0] to 0.7f, Reds[0] to 0.6f)
                    .forEach { (face, filled) -> Progress(face, 130f, 18f, filled) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
                listOf(Yellows[0] to 0.7f, Blues[0] to 0.45f, Greens[0] to 0.85f, Reds[0] to 0.55f).forEach { (face, filled) ->
                    Upright(face, 18f, 104f, filled)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
            CheckBox(ticked = false)
            CheckBox(ticked = true)
            Piece(Greys[0], 30f, 30f, corner = 8f)
            Radio(Cream)
            Radio(Yellows[0])
            Radio(Greys[0])
            Switch(on = false, face = Cream)
            Switch(on = true, face = Greens[0])
            Switch(on = true, face = Greys[0])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
            Knob(Woods[0])
            Knob(Yellows[0])
            Knob(Blues[0])
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Progress(Yellows[0], 380f, 22f, 0f)
                Progress(Yellows[0], 380f, 22f, 0.55f)
                Progress(Greens[0], 380f, 22f, 0.8f)
            }
        }
    }
}

/** One piece's face: the three colours down it, light at the top. */
internal class SheetFace(val light: Colour, val mid: Colour, val dark: Colour)

/** The line the whole sheet is drawn with: a very dark warm brown, never black. */
private val Ink = Colour.rgb(0x2B1D12)

private val Cream = SheetFace(Colour.rgb(0xFFF6E2), Colour.rgb(0xF6E7C8), Colour.rgb(0xE8D3A8))

private val Woods = listOf(
    SheetFace(Colour.rgb(0x9C6B45), Colour.rgb(0x7E5334), Colour.rgb(0x6E472C)),
    SheetFace(Colour.rgb(0xE0A567), Colour.rgb(0xC9884A), Colour.rgb(0xB9773C)),
    SheetFace(Colour.rgb(0x5C3B25), Colour.rgb(0x482D1C), Colour.rgb(0x3E2617)),
)

private val Greens = listOf(
    SheetFace(Colour.rgb(0x74CB3C), Colour.rgb(0x4FAF28), Colour.rgb(0x41A122)),
    SheetFace(Colour.rgb(0xA8E24A), Colour.rgb(0x8CCF2A), Colour.rgb(0x7CC01E)),
    SheetFace(Colour.rgb(0x3FA84B), Colour.rgb(0x22852F), Colour.rgb(0x1B7827)),
)

private val Blues = listOf(
    SheetFace(Colour.rgb(0x5CC6F2), Colour.rgb(0x1E97E0), Colour.rgb(0x1888D4)),
    SheetFace(Colour.rgb(0x86DBFB), Colour.rgb(0x35B6EE), Colour.rgb(0x25A7E4)),
    SheetFace(Colour.rgb(0x3D8FE0), Colour.rgb(0x1668C4), Colour.rgb(0x105AB2)),
)

private val Yellows = listOf(
    SheetFace(Colour.rgb(0xFFD257), Colour.rgb(0xF2B41C), Colour.rgb(0xEAA910)),
    SheetFace(Colour.rgb(0xFFE785), Colour.rgb(0xFBD14A), Colour.rgb(0xF5C534)),
    SheetFace(Colour.rgb(0xFFB63C), Colour.rgb(0xF09006), Colour.rgb(0xE08200)),
)

private val Reds = listOf(
    SheetFace(Colour.rgb(0xFF6A66), Colour.rgb(0xE5293C), Colour.rgb(0xD81E33)),
    SheetFace(Colour.rgb(0xFF9AA0), Colour.rgb(0xF4606E), Colour.rgb(0xEC4F5F)),
    SheetFace(Colour.rgb(0xD3454C), Colour.rgb(0xAF1523), Colour.rgb(0xA00F1D)),
)

private val Greys = listOf(
    SheetFace(Colour.rgb(0xCFCFCF), Colour.rgb(0xA6A6A6), Colour.rgb(0x9A9A9A)),
    SheetFace(Colour.rgb(0xF2F2F2), Colour.rgb(0xDADADA), Colour.rgb(0xCFCFCF)),
    SheetFace(Colour.rgb(0x9B9B9B), Colour.rgb(0x6F6F6F), Colour.rgb(0x646464)),
)

/**
 * The sheet's recipe, in fractions of [height]: a run of three colours down the face, a dark line
 * just inside the outline, a darker band along the bottom, a light lip under that, the shine, and
 * the heavy line outside.
 */
private fun Modifier.moulded(face: SheetFace, height: Float, corners: Corners, shine: Float = 0.44f): Modifier =
    background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corners = corners)
        .innerShade(Colour.Black.scaleAlpha(0.16f), depth = height * 0.07f, corners = corners)
        .innerShade(Colour.Black.scaleAlpha(0.22f), depth = height * 0.26f, corners = corners, offset = Offset(0f, -height * 0.26f))
        .innerShade(Colour.White.scaleAlpha(0.3f), depth = height * 0.1f, corners = corners, offset = Offset(0f, -height * 0.1f))
        .gloss(fraction = shine, corners = corners, colour = Colour.White.scaleAlpha(0.7f), inset = height * 0.14f)
        .borderOutside(Ink, width = height * 0.07f, corners = corners)

/** A capsule, a square button or a wooden bar: the same piece at different sizes and radii. */
@Composable
private fun Piece(face: SheetFace, width: Float, height: Float, corner: Float = height / 2f) {
    Box(Modifier.size(width, height).moulded(face, height, Corners.all(corner)))
}

/** A plate with a tab on its top edge, as the sheet's headings have. */
@Composable
private fun Plate(face: SheetFace, width: Float) {
    Column(horizontalAlignment = dev.wildware.composegl.ui.layout.HorizontalAlignment.Centre) {
        Box(Modifier.size(width * 0.42f, 26f).moulded(face, 26f, Corners.all(9f), shine = 0.5f))
        Box(Modifier.size(width, 48f).moulded(face, 48f, Corners.all(14f)))
    }
}

/**
 * A banner with pointed ends. The only piece here that is not a rounded box: its fill is a polygon
 * drawn behind the node, and its outline is the same polygon drawn a little larger under it — an
 * outline outside the shape, by hand, because [borderOutside] follows a rounded box.
 */
@Composable
private fun Banner(face: SheetFace, width: Float) {
    val height = 44f
    val point = height * 0.55f
    Box(
        Modifier.size(width, height).drawBehind { bounds ->
            fun shape(box: Rect, tip: Float) = floatArrayOf(
                box.left + tip, box.top,
                box.right - tip, box.top,
                box.right, box.centre.y,
                box.right - tip, box.bottom,
                box.left + tip, box.bottom,
                box.left, box.centre.y,
            )
            val outline = Rect(bounds.left - 3f, bounds.top - 3f, bounds.right + 3f, bounds.bottom + 3f)
            polygon(shape(outline, point + 3f), Ink)
            polygon(shape(bounds, point), face.mid)
            // The shine, kept inside the pointed ends rather than run to them.
            val shine = Rect(bounds.left + point, bounds.top + 4f, bounds.right - point, bounds.top + height * 0.42f)
            rect(shine, Brush.vertical(Colour.White.scaleAlpha(0.35f), Colour.Transparent), corner = 4f)
        },
    )
}

/** A track with a fill in it, the way every bar on that sheet is drawn. */
@Composable
private fun Progress(face: SheetFace, width: Float, height: Float, filled: Float) {
    Box(
        Modifier
            .size(width, height)
            .background(Colour.rgb(0x5C3B25), corner = height / 2f)
            .innerShade(Colour.Black.scaleAlpha(0.45f), depth = height * 0.35f, corner = height / 2f, offset = Offset(0f, height * 0.35f))
            .borderOutside(Ink, width = height * 0.14f, corner = height / 2f),
        contentAlignment = Alignment.CentreStart,
    ) {
        if (filled > 0f) {
            val inner = height - 8f
            Box(
                Modifier
                    .padding(left = 4f)
                    .size((width - 8f) * filled, inner)
                    .background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corner = inner / 2f)
                    .innerShade(Colour.White.scaleAlpha(0.35f), depth = inner * 0.2f, corner = inner / 2f, offset = Offset(0f, -inner * 0.2f))
                    .gloss(fraction = 0.5f, corner = inner / 2f, colour = Colour.White.scaleAlpha(0.6f), inset = 3f),
            )
        }
    }
}

/** The same bar stood on end, filled from the bottom. */
@Composable
private fun Upright(face: SheetFace, width: Float, height: Float, filled: Float) {
    Box(
        Modifier
            .size(width, height)
            .background(Colour.rgb(0x5C3B25), corner = width / 2f)
            .innerShade(Colour.Black.scaleAlpha(0.45f), depth = width * 0.3f, corner = width / 2f)
            .borderOutside(Ink, width = width * 0.14f, corner = width / 2f),
        contentAlignment = Alignment.BottomCentre,
    ) {
        val inner = width - 8f
        Box(
            Modifier
                .padding(bottom = 4f)
                .size(inner, (height - 8f) * filled)
                .background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corner = inner / 2f)
                .gloss(fraction = 0.3f, corner = inner / 2f, colour = Colour.White.scaleAlpha(0.5f), inset = 2f),
        )
    }
}

/** A slider's knob on its track: a ball on a post. */
@Composable
private fun Knob(face: SheetFace) {
    Column(horizontalAlignment = dev.wildware.composegl.ui.layout.HorizontalAlignment.Centre) {
        Box(Modifier.size(34f, 34f).moulded(face, 34f, Corners.all(17f), shine = 0.5f))
        Box(
            Modifier.size(14f, 52f)
                .background(Colour.rgb(0x5C3B25), corner = 7f)
                .innerShade(Colour.Black.scaleAlpha(0.4f), depth = 4f, corner = 7f)
                .borderOutside(Ink, width = 2f, corner = 7f),
        )
    }
}

/** A checkbox, and the tick in it: two lines, the only thing on this page drawn by hand. */
@Composable
private fun CheckBox(ticked: Boolean) {
    Box(
        Modifier.size(38f, 38f)
            .background(Colour.rgb(0x7E5334), corner = 10f)
            .borderOutside(Ink, width = 3f, corner = 10f)
            .padding(6f),
    ) {
        Box(
            Modifier.size(26f, 26f)
                .background(Brush.evenly(listOf(Cream.light, Cream.mid, Cream.dark)), corner = 6f)
                .innerShade(Colour.Black.scaleAlpha(0.3f), depth = 5f, corner = 6f, offset = Offset(0f, 5f))
                .borderOutside(Ink.scaleAlpha(0.75f), width = 2f, corner = 6f)
                .drawInFront { bounds ->
                    if (!ticked) return@drawInFront
                    val tick = Colour.rgb(0x3FA84B)
                    line(
                        Offset(bounds.left + 4f, bounds.centre.y + 1f),
                        Offset(bounds.centre.x - 1f, bounds.bottom - 6f),
                        width = 5f,
                        colour = tick,
                    )
                    line(
                        Offset(bounds.centre.x - 2f, bounds.bottom - 6f),
                        Offset(bounds.right - 3f, bounds.top + 1f),
                        width = 5f,
                        colour = tick,
                    )
                },
        )
    }
}

/** A round one, which is the same piece with its radius at half its height. */
@Composable
private fun Radio(face: SheetFace) {
    Box(
        Modifier.size(34f, 34f)
            .background(Colour.rgb(0x7E5334), corner = 17f)
            .borderOutside(Ink, width = 3f, corner = 17f)
            .padding(5f),
    ) {
        Box(Modifier.size(24f, 24f).moulded(face, 24f, Corners.all(12f), shine = 0.5f))
    }
}

/** A switch: a sunken track with the knob at one end of it. */
@Composable
private fun Switch(on: Boolean, face: SheetFace) {
    Box(
        Modifier
            .size(76f, 38f)
            .background(if (on) face.mid else Colour.rgb(0x6E472C), corner = 19f)
            .innerShade(Colour.Black.scaleAlpha(0.45f), depth = 10f, corner = 19f, offset = Offset(0f, 10f))
            .borderOutside(Ink, width = 3f, corner = 19f),
        contentAlignment = if (on) Alignment.CentreEnd else Alignment.CentreStart,
    ) {
        Box(
            Modifier.padding(left = 4f, right = 4f)
                .size(28f, 28f)
                .moulded(Cream, 28f, Corners.all(14f), shine = 0.5f),
        )
    }
}
