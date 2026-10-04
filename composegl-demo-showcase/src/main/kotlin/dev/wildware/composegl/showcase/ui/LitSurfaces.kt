package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.composegl.gdx.GdxTexture
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.moulded
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Text
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** So a test can find the lit rows without knowing where the section put them. */
internal const val LitRowTag = "showcase.ui.lit"

/**
 * A box lit as a surface with a shape, rather than as a stack of bands.
 *
 * [MouldedButtons] above draws the same look the long way — a fill, two shades, a shine and an
 * outline, each placed by hand — and that is worth seeing, because it is what the primitives are
 * for. This is the short way: the edge is given a height, the renderer works out which way the
 * surface faces at every pixel, and one light is shone on it. Two lines a button.
 *
 * Four rows, because four separate things came out of matching real art and each is visible on its
 * own: the shape of the edge, how tight the shine is, lighting a run of colours instead of one, and
 * laying a material across the face.
 */
@Composable
internal fun LitSurfaces() {
    Column(Modifier.testTag(LitRowTag).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
        Rank("Edge: a flat cut, rolled over, or curving all the way across") {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                Relief.entries.forEach { shape ->
                    Lit(shape.name) {
                        relief(
                            corner = 14f, shape = shape,
                            // A dome on a wide box climbs to a ridge rather than a point, and at
                            // the ends of that ridge the surface really does fold — so it is kept
                            // shallow here, where the sample is a button rather than a bead.
                            depth = if (shape == Relief.Dome) 0.3f else 0.22f,
                            face = Leaf, gloss = 0.35f, polish = 0.45f,
                        )
                    }
                }
            }
        }

        Rank("Polish: how tight the shine is, from wet plastic to glass") {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                listOf(0.1f, 0.4f, 0.8f).forEach { polish ->
                    Lit(polish.toString()) {
                        relief(corner = 14f, shape = Relief.Fillet, depth = 0.3f, face = Sky, gloss = 0.8f, polish = polish)
                    }
                }
            }
        }

        // The one that took longest to see. Drawn art grades the body of a button, and the grade is
        // a change of colour rather than of brightness — the top is a warmer green than the bottom.
        // Lighting one flat colour cannot produce that, however the light is set, which is why the
        // left-hand button reads as plastic and the right-hand one reads as painted.
        Rank("Face: one colour, a run of colours, and the same thing as stacked bands") {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                Lit("One") {
                    relief(corner = 14f, depth = 0.13f, elevation = 42f, strength = 0.4f, face = Leaf, gloss = 0.3f, polish = 0.6f)
                }
                Lit("Run") {
                    relief(
                        corner = 14f, depth = 0.13f, elevation = 42f, strength = 0.4f,
                        faceRun = Brush.Ramp(
                            listOf(
                                Brush.Stop(0.18f, Colour.rgb(0x7FD84A)),
                                Brush.Stop(0.5f, Colour.rgb(0x45B024)),
                                Brush.Stop(0.8f, Colour.rgb(0x2E8C18)),
                            ),
                        ),
                        gloss = 0.3f, polish = 0.6f,
                    )
                }
                // The long way round, for comparison: one light placing four bands itself.
                Lit("Bands") {
                    background(Brush.evenly(listOf(Colour.rgb(0x7FD84A), Colour.rgb(0x45B024), Colour.rgb(0x2E8C18))), corner = 14f)
                        .moulded(corner = 14f, hardness = 0.8f)
                }
            }
        }

        // One grey grain per column, tinted three ways: a material is multiplied into the face's
        // colour, so oak and steel are the same code and a different brown.
        Rank("Material: one grey grain, three tints — oak, paper and steel") {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                Grains.forEach { (grain, tint) ->
                    Lit(null) {
                        relief(corner = 14f, depth = 0.16f, elevation = 42f, strength = 0.4f, face = tint, material = grain(), gloss = 0.3f, polish = 0.5f)
                    }
                }
            }
        }

        // The one that only exists while somebody is doing something. Held, the light moves from
        // above to below, and that one number turns every edge and corner over with it.
        Rank("Press: held, it is lit from below instead of above") {
            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                PressButton("RESUME", LeafRun, Colour.rgb(0x1F5C10))
                PressButton("MAP", SkyRun, Colour.rgb(0x0B406F))
                PressButton("QUIT", EmberRun, Colour.rgb(0x63120D))
            }
        }

        // Honest about the cost: the coloured ones all batch together, and each material is a
        // texture of its own, so it breaks the batch the same way any other picture does.
        Text("Two lines a button. The coloured ones batch into one call; each material costs its own.", style = "label.dim")
    }
}

/**
 * A button that turns its light over while it is held.
 *
 * A press is the one thing a row of samples cannot show, because the whole of it is the change
 * between two frames: the top edge drops into shadow, the bottom edge lights, the face grade runs
 * the other way, the shine goes out, and the label goes down with the face it is written on.
 */
@Composable
private fun PressButton(label: String, paint: Brush.Ramp, line: Colour) {
    val interaction = remember { InteractionState() }
    val down = interaction.isPressed
    // Hovered, the light is turned up rather than a colour changed: the same surface catching more
    // of the same light, which is what a mouse resting on a real one would do.
    val lit = interaction.isHovered && !down
    Box(
        Modifier
            .size(112f, 46f)
            .interaction(interaction)
            .clickable {}
            .relief(
                corners = Corners.all(14f),
                shape = Relief.Chamfer,
                depth = 0.24f,
                light = if (down) 270f else 90f,
                elevation = if (down) 60f else 48f,
                strength = if (down) 0.5f else if (lit) 0.72f else 0.62f,
                gloss = if (down) 0.06f else if (lit) 0.6f else 0.4f,
                polish = 0.45f,
                faceRun = if (down) paint.turnedOver() else paint,
            )
            .borderOutside(line, width = 3f, corners = Corners.all(14f)),
        contentAlignment = Alignment.Centre,
    ) {
        Text(label, Modifier.offset(y = if (down) 2f else 0f), style = "label")
    }
}

/** The same run the other way up and a shade down: a face further from the light than it was. */
private fun Brush.Ramp.turnedOver(): Brush.Ramp {
    val shade = Colour.rgb(0xD2D2D2)
    return Brush.Ramp(stops.reversed().mapIndexed { at, stop -> Brush.Stop(stops[at].at, stop.colour.modulate(shade)) })
}

/** A row of samples under a line saying what is being varied along it. */
@Composable
private fun Rank(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
        Text(label, style = "label.dim")
        content()
    }
}

/** One lit box at the size the panel has room for, with its setting written on it. */
@Composable
private fun Lit(caption: String?, look: Modifier.() -> Modifier) {
    Box(
        Modifier.size(112f, 46f).look().borderOutside(Ink, width = 3f, corners = Corners.all(14f)),
        contentAlignment = Alignment.Centre,
    ) {
        if (caption != null) Text(caption, style = "label")
    }
}

private val LeafRun = run(0x7FD84A, 0x45B024, 0x2E8C18)
private val SkyRun = run(0x6FD0F7, 0x1E97E0, 0x1268B4)
private val EmberRun = run(0xF4796F, 0xDB3B32, 0xA82018)

/** A button's body: a grade of hue rather than of brightness, which is what painted art has. */
private fun run(top: Long, middle: Long, bottom: Long) = Brush.Ramp(
    listOf(
        Brush.Stop(0.16f, Colour.rgb(top)),
        Brush.Stop(0.5f, Colour.rgb(middle)),
        Brush.Stop(0.84f, Colour.rgb(bottom)),
    ),
)

private val Ink = Colour.rgb(0x2B1D12)
private val Leaf = Colour.rgb(0x4FAF28)
private val Sky = Colour.rgb(0x1E97E0)

private val Grains: List<Pair<() -> TextureHandle, Colour>> = listOf(
    Grain::wood to Colour.rgb(0xC08A46),
    Grain::paper to Colour.rgb(0xF2EADA),
    Grain::metal to Colour.rgb(0xC9CDD2),
)

/**
 * Three grains, built at runtime so the showcase carries no image files.
 *
 * A material is tiled across a face, so each has to be a texture of its own rather than a region of
 * an atlas: tiling a region samples whatever was packed beside it at every repeat.
 */
private object Grain {

    private const val Side = 128

    private val made = mutableMapOf<String, GdxTexture>()

    fun wood(): TextureHandle = of("wood") { random, x, y ->
        // Growth rings banding along the plank, wandering the way a sawn one's do, with long
        // streaks smeared along the grain over the top.
        val wander = sin(x * 0.055) * 6.0 + sin(x * 0.017 + 1.3) * 11.0
        val ring = sin((y + wander) * 2.0 * PI / 30.0) * 0.5 + 0.5
        0.58 + ring * ring * 0.36 + random.nextDouble() * 0.08
    }

    fun paper(): TextureHandle = of("paper") { random, _, _ -> 0.88 + random.nextDouble() * 0.12 }

    fun metal(): TextureHandle = of("metal") { random, _, y ->
        // Scratches along one axis: the row decides the brightness, so the streaks run the length.
        0.72 + Random(y).nextDouble() * 0.24 + random.nextDouble() * 0.04
    }

    private fun of(name: String, grey: (Random, Int, Int) -> Double): TextureHandle {
        // No GL context to make a texture in: the section is composed headless, in a test. There
        // a picture is only laid out around and written into a recording, never uploaded, so a
        // stand-in the same size does. Not kept, so a real run in the same process builds the grain.
        if (Gdx.gl == null) return FakeTexture(Side, Side)
        return made.getOrPut(name) {
            val random = Random(name.hashCode())
            val pixmap = Pixmap(Side, Side, Pixmap.Format.RGBA8888)
            for (y in 0 until Side) {
                for (x in 0 until Side) {
                    val level = grey(random, x, y).coerceIn(0.0, 1.0).toFloat()
                    pixmap.setColor(level, level, level, 1f)
                    pixmap.drawPixel(x, y)
                }
            }
            val texture = Texture(pixmap).also { pixmap.dispose() }
            GdxTexture(TextureRegion(texture))
        }
    }
}
