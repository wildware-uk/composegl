package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.Text

/**
 * A whole panel built out of [dev.wildware.composegl.ui.modifier.relief] and nothing else.
 *
 * Every piece of it — the plank it is cut from, the name plate, the three buttons, the groove the
 * supply bar runs in, its fill, the switch and its knob — is a box given an edge and a light. There
 * is no art in it: the only picture on the whole panel is one grey wood grain, tinted two browns.
 *
 * It is here because the pieces on their own do not answer the question a panel does. A button by
 * itself looks lit; a panel is where the light has to agree across eight of them at once, which is
 * what one light and a generated normal map buys and what hand-placed bands cost most to keep.
 *
 * The buttons are real buttons: pressed, they light from underneath instead of from above, which
 * turns every edge over at once and is what a pressed piece of moulded plastic actually does.
 */
@Composable
internal fun ReliefPanel() {
    Box(Modifier.fillMaxSize().background(Slate), contentAlignment = Alignment.Centre) {
        Box(
            Modifier
                .size(500f, 268f)
                // The plank: one grey grain tinted oak, lit from above like everything else on it.
                .relief(
                    corners = Corners.all(26f),
                    shape = Relief.Chamfer,
                    depth = 0.07f,
                    elevation = 45f,
                    strength = 0.5f,
                    gloss = 0.12f,
                    polish = 0.3f,
                    face = Oak,
                    material = Materials.wood(),
                    tiles = 4f,
                )
                .borderOutside(Ink, width = 5f, corners = Corners.all(26f))
                .padding(18f),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
                Plate("EXPEDITION")
                Row(horizontalArrangement = Arrangement.spacedBy(11f)) {
                    ReliefButton("RESUME", Leaf)
                    ReliefButton("MAP", Sky)
                    ReliefButton("QUIT", Ember)
                }
                Supplies(filled = 0.62f)
                Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
                    Switch()
                    Text("Lanterns lit", style = "label", colour = Cream)
                }
            }
        }
    }
}

/** The name plate: the same wood, darker, cut into the plank rather than sitting on it. */
@Composable
private fun Plate(title: String) {
    Box(
        Modifier
            .size(464f, 46f)
            .relief(
                corners = Corners.all(12f),
                shape = Relief.Chamfer,
                depth = 0.22f,
                light = 270f,
                elevation = 50f,
                strength = 0.55f,
                gloss = 0.1f,
                polish = 0.3f,
                face = Walnut,
                material = Materials.wood(),
                tiles = 3f,
            )
            .borderOutside(Ink, width = 4f, corners = Corners.all(12f)),
        contentAlignment = Alignment.Centre,
    ) {
        Text(title, style = "label.heading", colour = Cream)
    }
}

/**
 * One button, lit from above until a finger is on it and from below while it is.
 *
 * Turning the light over is the whole press: the top edge that was catching the light goes into
 * shadow, the bottom edge lights up, the graded face runs the other way, and the shine goes out
 * because a pressed button is no longer facing the light. One number changes; the renderer works
 * out what that does to every pixel of the edge, corners included.
 */
@Composable
private fun ReliefButton(label: String, paint: Paint) {
    val interaction = remember { InteractionState() }
    var clicks by remember { mutableStateOf(0) }
    val down = interaction.isPressed
    // Hovered, the light is turned up a little rather than a colour changed: the same surface
    // catching more of the same light, which is what a mouse resting on a real one would do.
    val lit = interaction.isHovered && !down
    Box(
        Modifier
            .size(144f, 58f)
            .interaction(interaction)
            .clickable { clicks++ }
            .relief(
                corners = Corners.all(16f),
                shape = Relief.Chamfer,
                depth = 0.24f,
                light = if (down) 270f else 90f,
                elevation = if (down) 60f else 48f,
                strength = if (down) 0.5f else if (lit) 0.72f else 0.62f,
                gloss = if (down) 0.06f else if (lit) 0.6f else 0.4f,
                polish = 0.45f,
                faceRun = if (down) paint.pressed else paint.face,
            )
            .borderOutside(paint.line, width = 4f, corners = Corners.all(16f)),
        contentAlignment = Alignment.Centre,
    ) {
        // The label goes down with the face it is written on, a pixel, the way a printed one does.
        Text(label, Modifier.offset(y = if (down) 2f else 0f), style = "label", colour = Cream)
    }
}

/** A groove cut into the plank with a lit fill sitting in it. */
@Composable
private fun Supplies(filled: Float) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5f)) {
        Text("SUPPLIES", style = "label.dim")
        Box(
            Modifier
                .size(464f, 26f)
                // Lit from below: the same edge, turned over, reads as cut in rather than stuck on.
                .relief(
                    corners = Corners.all(13f),
                    shape = Relief.Fillet,
                    depth = 0.45f,
                    light = 270f,
                    elevation = 40f,
                    strength = 0.6f,
                    gloss = 0.05f,
                    face = Walnut,
                )
                .borderOutside(Ink, width = 4f, corners = Corners.all(13f)),
            contentAlignment = Alignment.CentreStart,
        ) {
            Box(
                Modifier
                    .size(464f * filled, 26f)
                    .relief(
                        corners = Corners.all(13f),
                        shape = Relief.Fillet,
                        depth = 0.5f,
                        elevation = 45f,
                        strength = 0.5f,
                        gloss = 0.45f,
                        polish = 0.35f,
                        faceRun = Amber.face,
                    ),
            )
        }
    }
}

/** A switch: a sunken track with a lit knob standing in it. */
@Composable
private fun Switch() {
    Box(
        Modifier
            .size(72f, 34f)
            .relief(
                corners = Corners.all(17f),
                shape = Relief.Fillet,
                depth = 0.5f,
                light = 270f,
                elevation = 40f,
                strength = 0.55f,
                gloss = 0.05f,
                face = Leaf.deep,
            )
            .borderOutside(Ink, width = 4f, corners = Corners.all(17f))
            .padding(right = 4f),
        contentAlignment = Alignment.CentreEnd,
    ) {
        Box(
            Modifier
                .size(28f, 28f)
                .relief(
                    corners = Corners.all(14f),
                    shape = Relief.Dome,
                    depth = 0.5f,
                    elevation = 50f,
                    strength = 0.55f,
                    gloss = 0.5f,
                    polish = 0.5f,
                    face = Cream,
                )
                .borderOutside(Ink, width = 3f, corners = Corners.all(14f)),
        )
    }
}

/**
 * A button's colour: the graded face, the same grade turned over for the press, and its outline.
 *
 * The face is a run rather than one colour because a painted button's body changes hue from top to
 * bottom, and no amount of lighting one flat colour produces that.
 */
private class Paint(top: Long, middle: Long, bottom: Long, line: Long) {
    val face = ramp(top, middle, bottom)

    // The same grade the other way up and a shade down, because a button somebody is holding down
    // is both turned over and further from the light than it was.
    val pressed = ramp(bottom, middle, top, Shade)
    val line = Colour.rgb(line)
    val deep = Colour.rgb(bottom)

    private fun ramp(first: Long, second: Long, third: Long, tint: Colour = Colour.White) = Brush.Ramp(
        listOf(
            Brush.Stop(0.16f, Colour.rgb(first).modulate(tint)),
            Brush.Stop(0.5f, Colour.rgb(second).modulate(tint)),
            Brush.Stop(0.84f, Colour.rgb(third).modulate(tint)),
        ),
    )
}

/** How much the light a pressed face gets is worth: a shade, not a different colour. */
private val Shade = Colour.rgb(0xD2D2D2)

private val Leaf = Paint(0x7FD84A, 0x45B024, 0x2E8C18, 0x1F5C10)
private val Sky = Paint(0x6FD0F7, 0x1E97E0, 0x1268B4, 0x0B406F)
private val Ember = Paint(0xF4796F, 0xDB3B32, 0xA82018, 0x63120D)
private val Amber = Paint(0xFFD95E, 0xF2A72B, 0xD07F12, 0x7A4708)

private val Slate = Colour.rgb(0x1B1712)
private val Ink = Colour.rgb(0x2B1D12)
private val Oak = Colour.rgb(0xB07A3E)
private val Walnut = Colour.rgb(0x5C3A1D)
private val Cream = Colour.rgb(0xFFF3DC)
