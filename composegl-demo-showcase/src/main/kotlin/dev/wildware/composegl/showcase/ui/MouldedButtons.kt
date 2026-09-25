package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.bevel
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.gloss
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.innerShade
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Text

/** So a test can find the row of buttons without knowing where the section put it. */
internal const val MouldedRowTag = "showcase.ui.moulded"

/**
 * The chunky casual-game look — a heavy outline, a moulded edge, a shine — drawn from modifiers
 * alone. No art, no nine-patch, no texture: every piece here is a box with four modifiers on it.
 *
 * It is here because it is the question people ask about this toolkit: a game interface usually
 * means an artist and an atlas, and this is what the shapes look like without either.
 */
@Composable
internal fun MouldedButtons() {
    Column(Modifier.testTag(MouldedRowTag).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12f)) {
            Faces.forEach { face -> PillButton(face) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
            Faces.forEach { face -> SquareButton(face) }
            Toggle()
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
            Faces.take(3).forEachIndexed { at, face -> Bar(face, fill = 0.35f + at * 0.25f) }
        }
    }
}

/** One button's colours: the run of its face, and the ink round it. */
private class Face(val name: String, val light: Colour, val mid: Colour, val dark: Colour)

private val Ink = Colour.rgb(0x2B1B10)

private val Faces = listOf(
    Face("Play", Colour.rgb(0x9BE86B), Colour.rgb(0x5CC23F), Colour.rgb(0x2F8A24)),
    Face("Shop", Colour.rgb(0x7FD4FF), Colour.rgb(0x2E9BE0), Colour.rgb(0x1668A8)),
    Face("Coins", Colour.rgb(0xFFDE7A), Colour.rgb(0xF2B233), Colour.rgb(0xC0790F)),
    Face("Quit", Colour.rgb(0xFF8E86), Colour.rgb(0xE8453C), Colour.rgb(0xA81E1B)),
)

/**
 * A capsule button: three colours down its face, a moulded edge, a shine across the top and the
 * heavy line round the outside. It sinks a pixel while it is held, which costs nothing and is most
 * of what makes a button feel like one.
 */
@Composable
private fun PillButton(face: Face) {
    val press = remember { InteractionState() }
    val held = press.isPressed
    val corner = 20f
    Box(
        Modifier
            .size(96f, 40f)
            .padding(top = if (held) 2f else 0f)
            .background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corner = corner)
            .bevel(depth = 6f, corner = corner, dark = Ink.scaleAlpha(0.45f))
            .gloss(fraction = if (held) 0.2f else 0.45f, corner = corner, inset = 5f)
            .borderOutside(Ink, width = 3f, corner = corner)
            .interaction(press)
            .clickable { },
        contentAlignment = Alignment.Centre,
    ) {
        Text(face.name, style = "label")
    }
}

/** The same face in a square: the shapes on that sheet are one look at several sizes. */
@Composable
private fun SquareButton(face: Face) {
    val corner = 10f
    Box(
        Modifier
            .size(40f, 40f)
            .background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corner = corner)
            .bevel(depth = 5f, corner = corner, dark = Ink.scaleAlpha(0.45f))
            .gloss(fraction = 0.4f, corner = corner, inset = 4f)
            .borderOutside(Ink, width = 3f, corner = corner),
    )
}

/** A switch: a sunken track with a lit face, and a knob that is the same button made round. */
@Composable
private fun Toggle() {
    var on by remember { mutableStateOf(true) }
    val face = Faces[0]
    Box(
        Modifier
            .size(72f, 36f)
            .background(if (on) face.mid else Colour.rgb(0xE8E0CF), corner = 18f)
            .innerShade(Ink.scaleAlpha(0.5f), depth = 8f, corner = 18f, offset = Offset(0f, 8f))
            .borderOutside(Ink, width = 3f, corner = 18f)
            .clickable { on = !on },
        contentAlignment = if (on) Alignment.CentreEnd else Alignment.CentreStart,
    ) {
        Box(
            Modifier
                .padding(left = 3f, right = 3f)
                .size(30f, 30f)
                .background(Brush.evenly(listOf(Colour.White, Colour.rgb(0xF3ECDC), Colour.rgb(0xD8CDB4))), corner = 15f)
                .bevel(depth = 5f, corner = 15f, dark = Ink.scaleAlpha(0.35f))
                .borderOutside(Ink, width = 3f, corner = 15f),
        )
    }
}

/** A progress bar: a sunken capsule with a glossy capsule inside it, the way that sheet draws one. */
@Composable
private fun Bar(face: Face, fill: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22f)
            .background(Colour.rgb(0x5A3E2B), corner = 11f)
            .innerShade(Ink.scaleAlpha(0.6f), depth = 7f, corner = 11f, offset = Offset(0f, 7f))
            .borderOutside(Ink, width = 3f, corner = 11f),
        contentAlignment = Alignment.CentreStart,
    ) {
        Box(
            Modifier
                .padding(left = 3f)
                .width(220f * fill)
                .height(16f)
                .background(Brush.evenly(listOf(face.light, face.mid, face.dark)), corners = Corners.all(8f))
                .gloss(fraction = 0.5f, corners = Corners.all(8f), inset = 3f),
        )
    }
}
