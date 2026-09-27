package dev.wildware.composegl.demo.web

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
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Text

/**
 * Surfaces: a box given an edge and a light, rather than a stack of bands hoping to agree.
 *
 * The page the chunky-button look lives on now. Everything here is `Modifier.relief` — the shape of
 * the edge, how tight the shine is, lighting a run of colours instead of one, a grain laid across
 * the face, buttons that turn their light over when they are held, and a whole panel built out of
 * nothing else.
 */
@Composable
fun SurfacesPage() {
    Page(Section.Surfaces, "One light, and the renderer works out which way the surface faces at every pixel.") {
        Edges()
        Polish()
        Faces()
        Pressable()
        Grains()
        Panel()
    }
}

@Composable
private fun Edges() = Card("The edge", "A flat cut, rolled over, or curving all the way across.") {
    FlowRow(horizontalSpacing = 10f, verticalSpacing = 10f) {
        Relief.entries.forEach { shape ->
            Labelled(shape.name.lowercase()) {
                Lit {
                    relief(
                        corner = 14f,
                        shape = shape,
                        // A dome on a box far wider than it is tall climbs to a ridge, and the ends
                        // of that ridge fold. Correct, and a fault at button size — so, shallow.
                        depth = if (shape == Relief.Dome) 0.3f else 0.22f,
                        face = Leaf,
                        gloss = 0.35f,
                        polish = 0.45f,
                    )
                }
            }
        }
    }
}

@Composable
private fun Polish() = Card("Polish", "How tight the shine is: wet plastic at one end, glass at the other.") {
    FlowRow(horizontalSpacing = 10f, verticalSpacing = 10f) {
        listOf(0.1f, 0.4f, 0.8f).forEach { polish ->
            Labelled(polish.toString()) {
                Lit { relief(corner = 14f, shape = Relief.Fillet, depth = 0.3f, face = Sky, gloss = 0.8f, polish = polish) }
            }
        }
    }
}

@Composable
private fun Faces() = Card("The face", "A painted button's body changes hue top to bottom; a lit flat colour cannot.") {
    FlowRow(horizontalSpacing = 10f, verticalSpacing = 10f) {
        Labelled("one colour") {
            Lit { relief(corner = 14f, depth = 0.13f, elevation = 42f, strength = 0.4f, face = Leaf, gloss = 0.3f, polish = 0.6f) }
        }
        Labelled("a run of them") {
            Lit {
                relief(corner = 14f, depth = 0.13f, elevation = 42f, strength = 0.4f, faceRun = LeafRun, gloss = 0.3f, polish = 0.6f)
            }
        }
    }
}

/**
 * Buttons you can actually press, because a press is the thing a still picture cannot show.
 *
 * Held, the light moves from 90° to 270° — from above to below — and that one number takes every
 * edge with it: the top edge drops into shadow, the bottom edge lights up, the face grade runs the
 * other way and the shine goes out. The corners follow, which is what no stack of bands manages.
 */
@Composable
private fun Pressable() = Card("Press one", "Held, it is lit from below instead of above. Everything else follows.") {
    var pressed by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
            PressButton("RESUME", LeafPaint, "press-resume") { pressed++ }
            PressButton("MAP", SkyPaint, "press-map") { pressed++ }
            PressButton("QUIT", EmberPaint, "press-quit") { pressed++ }
        }
        Text(if (pressed == 1) "Pressed 1 time" else "Pressed $pressed times", Modifier.testTag("press-count"), style = "label.dim")
    }
}

@Composable
private fun Grains() = Card("Materials", "One grey grain, multiplied into the face colour: oak, paper, steel.") {
    val materials = LocalMaterials.current
    if (materials == null) {
        Text("The grains are built when the page starts; this backend has none.", style = "label.dim")
        return@Card
    }
    FlowRow(horizontalSpacing = 10f, verticalSpacing = 10f) {
        listOf(
            Triple("oak", materials.wood, Colour.rgb(0xC08A46)),
            Triple("paper", materials.paper, Colour.rgb(0xF2EADA)),
            Triple("steel", materials.metal, Colour.rgb(0xC9CDD2)),
        ).forEach { (name, grain, tint) ->
            Labelled(name) {
                Lit {
                    relief(
                        corner = 14f, depth = 0.16f, elevation = 42f, strength = 0.4f,
                        face = tint, material = grain, gloss = 0.3f, polish = 0.5f,
                    )
                }
            }
        }
    }
}

/**
 * A whole panel of them: plank, name plate, buttons, groove, bar and switch.
 *
 * A piece at a time proves the modifier; a panel is where one light has to agree across eight
 * things at once, which is the part hand-placed bands cost most to keep.
 */
@Composable
private fun Panel() = Card("A panel of nothing else", "Every piece is a box with an edge and one light on it.") {
    val materials = LocalMaterials.current
    Box(
        Modifier
            .fillMaxWidth()
            .testTag("relief-panel")
            .relief(
                corners = Corners.all(20f),
                shape = Relief.Chamfer,
                depth = 0.07f,
                elevation = 45f,
                strength = 0.5f,
                gloss = 0.12f,
                polish = 0.3f,
                face = Oak,
                material = materials?.wood,
                tiles = 2f,
            )
            .borderOutside(PanelInk, width = 4f, corners = Corners.all(20f))
            .padding(14f),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38f)
                    .relief(
                        corners = Corners.all(10f),
                        shape = Relief.Chamfer,
                        depth = 0.22f,
                        light = 270f,
                        strength = 0.55f,
                        gloss = 0.1f,
                        face = Walnut,
                        material = materials?.wood,
                        tiles = 3f,
                    )
                    .borderOutside(PanelInk, width = 3f, corners = Corners.all(10f)),
                contentAlignment = Alignment.Centre,
            ) {
                Text("EXPEDITION", style = "label.heading", colour = Cream)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                PressButton("GO", LeafPaint, "panel-go", width = 92f) {}
                PressButton("MAP", SkyPaint, "panel-map", width = 92f) {}
                PressButton("QUIT", EmberPaint, "panel-quit", width = 92f) {}
            }
            // A groove cut into the plank, with a lit fill standing in it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22f)
                    .relief(
                        corners = Corners.all(11f),
                        shape = Relief.Fillet,
                        depth = 0.45f,
                        light = 270f,
                        elevation = 40f,
                        strength = 0.6f,
                        gloss = 0.05f,
                        face = Walnut,
                    )
                    .borderOutside(PanelInk, width = 3f, corners = Corners.all(11f)),
                contentAlignment = Alignment.CentreStart,
            ) {
                Box(
                    Modifier
                        .size(180f, 22f)
                        .relief(
                            corners = Corners.all(11f),
                            shape = Relief.Fillet,
                            depth = 0.5f,
                            elevation = 45f,
                            strength = 0.5f,
                            gloss = 0.45f,
                            polish = 0.35f,
                            faceRun = AmberRun,
                        ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
                Switch()
                Text("Lanterns lit", style = "label", colour = Cream)
            }
        }
    }
}

/** A switch: a sunken track with a lit knob standing at one end of it. */
@Composable
private fun Switch() {
    Box(
        Modifier
            .size(60f, 28f)
            .relief(
                corners = Corners.all(14f),
                shape = Relief.Fillet,
                depth = 0.5f,
                light = 270f,
                elevation = 40f,
                strength = 0.55f,
                gloss = 0.05f,
                face = Colour.rgb(0x2E8C18),
            )
            .borderOutside(PanelInk, width = 3f, corners = Corners.all(14f))
            .padding(right = 3f),
        contentAlignment = Alignment.CentreEnd,
    ) {
        Box(
            Modifier
                .size(22f, 22f)
                .relief(
                    corners = Corners.all(11f),
                    shape = Relief.Dome,
                    depth = 0.5f,
                    elevation = 50f,
                    strength = 0.55f,
                    gloss = 0.5f,
                    polish = 0.5f,
                    face = Cream,
                )
                .borderOutside(PanelInk, width = 2f, corners = Corners.all(11f)),
        )
    }
}

/** One button whose light turns over while it is held. */
@Composable
private fun PressButton(label: String, paint: Paint, tag: String, width: Float = 104f, onClick: () -> Unit) {
    val interaction = remember { InteractionState() }
    val down = interaction.isPressed
    // Hovered, the light is turned up rather than a colour changed: the same surface catching more
    // of the same light, which is what a mouse resting on a real one would do.
    val lit = interaction.isHovered && !down
    Box(
        Modifier
            .size(width, 44f)
            .testTag(tag)
            .interaction(interaction)
            .clickable(onClick = onClick)
            .relief(
                corners = Corners.all(14f),
                shape = Relief.Chamfer,
                depth = 0.24f,
                light = if (down) 270f else 90f,
                elevation = if (down) 60f else 48f,
                strength = if (down) 0.5f else if (lit) 0.72f else 0.62f,
                gloss = if (down) 0.06f else if (lit) 0.6f else 0.4f,
                polish = 0.45f,
                faceRun = if (down) paint.pressed else paint.face,
            )
            .borderOutside(paint.line, width = 3f, corners = Corners.all(14f)),
        contentAlignment = Alignment.Centre,
    ) {
        // The label goes down with the face it is written on, the way a printed one does.
        Text(label, Modifier.offset(y = if (down) 2f else 0f), style = "label", colour = Cream)
    }
}

/** One sample at the size every row here uses. */
@Composable
private fun Lit(look: Modifier.() -> Modifier) {
    Box(Modifier.size(104f, 44f).look().borderOutside(PanelInk, width = 3f, corners = Corners.all(14f)))
}

/**
 * A button's colour: the graded face, the same grade turned over and shaded for the press, and its
 * outline. The grade is a change of hue, not of brightness, which is what lighting one flat colour
 * can never produce.
 */
private class Paint(top: Long, middle: Long, bottom: Long, line: Long) {
    val face = ramp(top, middle, bottom)
    val pressed = ramp(bottom, middle, top, Colour.rgb(0xD2D2D2))
    val line = Colour.rgb(line)

    private fun ramp(first: Long, second: Long, third: Long, tint: Colour = Colour.White) = Brush.Ramp(
        listOf(
            Brush.Stop(0.16f, Colour.rgb(first).modulate(tint)),
            Brush.Stop(0.5f, Colour.rgb(second).modulate(tint)),
            Brush.Stop(0.84f, Colour.rgb(third).modulate(tint)),
        ),
    )
}

private val LeafPaint = Paint(0x7FD84A, 0x45B024, 0x2E8C18, 0x1F5C10)
private val SkyPaint = Paint(0x6FD0F7, 0x1E97E0, 0x1268B4, 0x0B406F)
private val EmberPaint = Paint(0xF4796F, 0xDB3B32, 0xA82018, 0x63120D)

private val LeafRun = LeafPaint.face
private val AmberRun = Paint(0xFFD95E, 0xF2A72B, 0xD07F12, 0x7A4708).face

private val Leaf = Colour.rgb(0x4FAF28)
private val Sky = Colour.rgb(0x1E97E0)
private val Oak = Colour.rgb(0xB07A3E)
private val Walnut = Colour.rgb(0x5C3A1D)
private val Cream = Colour.rgb(0xFFF3DC)
private val PanelInk = Colour.rgb(0x2B1D12)
