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
internal fun ArtSheet() {
    Column(
        Modifier.background(Colour.White).padding(16f),
        verticalArrangement = Arrangement.spacedBy(10f),
    ) {
        // Wooden bars, with the grain the sheet draws on them.
        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
            Woods.forEach { Piece(it, 228f, 54f, corner = 16f, grain = true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
            Greens.forEach { Piece(it, 190f, 54f) }
            Blues.forEach { Piece(it, 190f, 54f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
            Yellows.forEach { Piece(it, 190f, 54f) }
            Reds.forEach { Piece(it, 190f, 54f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
            Greys.forEach { Piece(it, 190f, 54f) }
            listOf(Woods[0], Yellows[0], Blues[0], Reds[0], Greys[0]).forEach { Piece(it, 64f, 64f, corner = 16f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Top) {
            Woods.forEach { Piece(it, 78f, 130f, corner = 18f, grain = true) }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Bottom) {
                    Plate(Woods[0], 232f)
                    Plate(Cream, 232f)
                    Plate(Woods[2], 232f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
                    Banner(Woods[0], 232f)
                    Banner(Cream, 232f)
                    Banner(Woods[2], 232f)
                }
            }
        }
        // Bars, and the knobs that ride on them.
        Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Progress(Yellows[0], 350f, 20f, 0f)
                Progress(Yellows[0], 350f, 20f, 0.8f)
                Progress(Blues[0], 350f, 20f, 0.72f)
                Progress(Greens[0], 350f, 20f, 0.68f)
                Progress(Reds[0], 350f, 20f, 0.62f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Progress(Yellows[0], 120f, 20f, 0f)
                Progress(Yellows[0], 120f, 20f, 0.65f)
                Progress(Blues[0], 120f, 20f, 0.6f)
                Progress(Greens[0], 120f, 20f, 0.55f)
                Progress(Reds[0], 120f, 20f, 0.5f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
                Upright(Yellows[0], 18f, 150f, 0f)
                Upright(Yellows[0], 18f, 150f, 0.85f)
                Upright(Blues[0], 18f, 150f, 0.75f)
                Upright(Greens[0], 18f, 150f, 0.7f)
                Upright(Reds[0], 18f, 150f, 0.6f)
                Upright(Yellows[0], 18f, 150f, 0.5f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Bottom) {
                    Knob(Knobs.Ball(Woods[0]))
                    Knob(Knobs.Ball(Yellows[0]))
                    Knob(Knobs.Gem)
                    Knob(Knobs.Berry)
                    Knob(Knobs.Pill)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
                    SliderKnob(Knobs.Ball(Woods[0]))
                    SliderKnob(Knobs.Ball(Yellows[0]))
                    SliderKnob(Knobs.Gem)
                    SliderKnob(Knobs.Berry)
                    SliderKnob(Knobs.Pill)
                }
            }
        }
        // Boxes to tick, circles to choose and switches to flick.
        Row(horizontalArrangement = Arrangement.spacedBy(14f), verticalAlignment = VerticalAlignment.Centre) {
            CheckBox(ticked = false)
            CheckBox(ticked = true)
            CheckBox(ticked = false)
            Piece(Greys[0], 42f, 42f, corner = 11f)
            Spacer(14f)
            Radio(Cream)
            Radio(Yellows[0])
            Radio(Greys[1])
            Spacer(14f)
            Switch(on = false, face = Cream)
            Switch(on = true, face = Greens[0])
            Switch(on = true, face = Greys[2])
        }
        // The long bars at the foot of the sheet.
        Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Yellows[0], 400f, 26f, 0f)
                Progress(Yellows[0], 400f, 26f, 0.72f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Yellows[0], 230f, 26f, 0.45f)
                Progress(Greens[0], 230f, 26f, 0.62f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Yellows[0], 150f, 26f, 0f)
                Progress(Yellows[0], 150f, 26f, 0.5f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Blues[0], 110f, 26f, 0f)
                Progress(Blues[0], 110f, 26f, 0.7f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Greens[0], 110f, 26f, 0f)
                Progress(Greens[0], 110f, 26f, 0.8f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10f)) {
                Progress(Reds[0], 110f, 26f, 0f)
                Progress(Reds[0], 110f, 26f, 0.85f)
            }
        }
    }
}

/** A gap in a row, where the sheet leaves one. */
@Composable
private fun Spacer(width: Float) {
    Box(Modifier.size(width, 1f))
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
private fun Piece(face: SheetFace, width: Float, height: Float, corner: Float = height / 2f, grain: Boolean = false) {
    val piece = Modifier.size(width, height).moulded(face, height, Corners.all(corner))
    Box(if (grain) piece.grain(face) else piece)
}

/**
 * The streaks a wooden piece has: a few darker lines along it, kept inside its rounded edge.
 *
 * Drawn rather than painted from a texture, which is the only honest way to say it — a real sheet's
 * grain is a picture. Lines this faint read as wood at a glance and cost nothing.
 */
private fun Modifier.grain(face: SheetFace): Modifier = drawInFront { bounds ->
    val along = bounds.width > bounds.height
    val lines = if (along) 5 else 4
    val streak = face.dark.scaleAlpha(0.35f)
    val inset = if (along) bounds.height * 0.22f else bounds.width * 0.22f
    repeat(lines) { at ->
        val step = (at + 1f) / (lines + 1f)
        if (along) {
            val y = bounds.top + bounds.height * step
            line(Offset(bounds.left + inset, y), Offset(bounds.right - inset, y), width = 1.5f, colour = streak)
        } else {
            val x = bounds.left + bounds.width * step
            line(Offset(x, bounds.top + inset), Offset(x, bounds.bottom - inset), width = 1.5f, colour = streak)
        }
    }
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

/** The five knobs the sheet puts on its sliders. */
private sealed interface Knobs {
    class Ball(val face: SheetFace) : Knobs
    object Gem : Knobs
    object Berry : Knobs
    object Pill : Knobs
}

/** A knob above its post, as the sheet draws the loose ones. */
@Composable
private fun Knob(kind: Knobs) {
    Column(horizontalAlignment = dev.wildware.composegl.ui.layout.HorizontalAlignment.Centre) {
        KnobHead(kind)
        Post(46f)
    }
}

/** The same knob sat on a track, which is how a slider wears it. */
@Composable
private fun SliderKnob(kind: Knobs) {
    Box(contentAlignment = Alignment.Centre) {
        Post(96f)
        KnobHead(kind)
    }
}

/** The post a knob slides along: a thin sunken track. */
@Composable
private fun Post(height: Float) {
    Box(
        Modifier.size(14f, height)
            .background(Colour.rgb(0x5C3B25), corner = 7f)
            .innerShade(Colour.Black.scaleAlpha(0.45f), depth = 5f, corner = 7f)
            .borderOutside(Ink, width = 2f, corner = 7f),
    )
}

/**
 * The head itself. A ball and a pill are the same moulded piece at different radii; a gem is a cut
 * stone and a berry is a fruit, so both are polygons drawn behind the node with their own outline —
 * the two pieces on that sheet that a rounded box cannot be.
 */
@Composable
private fun KnobHead(kind: Knobs) = when (kind) {
    is Knobs.Ball -> Box(Modifier.size(38f, 38f).moulded(kind.face, 38f, Corners.all(19f), shine = 0.5f))
    Knobs.Pill -> Box(Modifier.size(38f, 30f).moulded(Cream, 30f, Corners.all(15f), shine = 0.5f))
    Knobs.Gem -> Box(
        Modifier.size(34f, 44f).drawBehind { bounds ->
            fun cut(box: Rect) = floatArrayOf(
                box.centre.x, box.top,
                box.right, box.top + box.height * 0.32f,
                box.centre.x, box.bottom,
                box.left, box.top + box.height * 0.32f,
            )
            polygon(cut(Rect(bounds.left - 3f, bounds.top - 3f, bounds.right + 3f, bounds.bottom + 3f)), Ink)
            polygon(cut(bounds), Colour.rgb(0x2E9BE0))
            // Two facets: the lit top-left, and the bright edge under the crown.
            polygon(
                floatArrayOf(
                    bounds.centre.x, bounds.top + 2f,
                    bounds.centre.x, bounds.top + bounds.height * 0.34f,
                    bounds.left + 5f, bounds.top + bounds.height * 0.3f,
                ),
                Colour.rgb(0x9BDDFB),
            )
            polygon(
                floatArrayOf(
                    bounds.centre.x, bounds.top + 2f,
                    bounds.right - 5f, bounds.top + bounds.height * 0.3f,
                    bounds.centre.x, bounds.top + bounds.height * 0.34f,
                ),
                Colour.rgb(0x63C4F2),
            )
        },
    )
    Knobs.Berry -> Box(
        Modifier.size(40f, 46f).drawBehind { bounds ->
            val body = Rect(bounds.left, bounds.top + bounds.height * 0.22f, bounds.right, bounds.bottom)
            // A berry: wide at the shoulders, tapering to a point. Ten points round it, so the
            // curve reads as fruit rather than as a gem.
            val around = listOf(
                0.10f to 0.24f, 0.26f to 0.06f, 0.50f to 0.00f, 0.74f to 0.06f, 0.90f to 0.24f,
                0.92f to 0.48f, 0.74f to 0.80f, 0.50f to 1.00f, 0.26f to 0.80f, 0.08f to 0.48f,
            )
            fun berry(box: Rect) = FloatArray(around.size * 2) { at ->
                val (x, y) = around[at / 2]
                if (at % 2 == 0) box.left + box.width * x else box.top + box.height * y
            }
            polygon(berry(Rect(body.left - 3f, body.top - 3f, body.right + 3f, body.bottom + 3f)), Ink)
            polygon(berry(body), Colour.rgb(0xE5293C))
            // A lit shoulder, then the pips.
            polygon(
                floatArrayOf(
                    body.left + body.width * 0.2f, body.top + body.height * 0.3f,
                    body.left + body.width * 0.42f, body.top + body.height * 0.12f,
                    body.left + body.width * 0.5f, body.top + body.height * 0.34f,
                    body.left + body.width * 0.3f, body.top + body.height * 0.46f,
                ),
                Colour.rgb(0xFF7B72).scaleAlpha(0.8f),
            )
            listOf(0.34f to 0.42f, 0.62f to 0.38f, 0.5f to 0.64f, 0.44f to 0.8f).forEach { (x, y) ->
                circle(Offset(body.left + body.width * x, body.top + body.height * y), 1.7f, Colour.rgb(0xFFE08A))
            }
            // Three leaves and a stalk.
            val hull = Rect(bounds.left + bounds.width * 0.14f, bounds.top, bounds.right - bounds.width * 0.14f, bounds.top + bounds.height * 0.3f)
            val leaf = Colour.rgb(0x3FA84B)
            listOf(0f to 0.32f, 0.5f to 0.0f, 1f to 0.32f).forEach { (x, lift) ->
                polygon(
                    floatArrayOf(
                        hull.centre.x, hull.bottom,
                        hull.left + hull.width * x, hull.top + hull.height * lift,
                        hull.centre.x + (x - 0.5f) * hull.width * 0.35f, hull.bottom - hull.height * 0.1f,
                    ),
                    leaf,
                )
            }
            line(
                Offset(hull.centre.x, hull.top + hull.height * 0.1f),
                Offset(hull.centre.x, hull.bottom),
                width = 2.5f,
                colour = Colour.rgb(0x2F7A34),
            )
        },
    )
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
