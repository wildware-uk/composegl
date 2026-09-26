package dev.wildware.composegl.demo.docs

import androidx.compose.runtime.Composable
import dev.wildware.composegl.lwjgl3.GlTexture
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.Relief
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.borderOutside
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.relief
import dev.wildware.composegl.ui.modifier.size

/**
 * The same lit surface, wearing a material instead of a colour.
 *
 * One grey grain per row, tinted three ways: a material is multiplied into the face's colour, so
 * oak and walnut are the same picture and a different brown, and the light still shapes the edge
 * over the top of whatever the grain does.
 *
 * Three rows, because the three behave differently under a light. Wood has a direction and wants
 * the light across it; paper is felted and almost flat, so it only reads at all with the shine
 * turned down; brushed metal is scratched along one axis and is the one that wants a tight shine,
 * because that is what a scratch does to a highlight.
 */
@Composable
internal fun MaterialSheet() {
    Box(Modifier.fillMaxSize().background(Colour.rgb(0xF2F1EC))) {
        Rows.forEachIndexed { row, (material, kinds) ->
            kinds.forEachIndexed { column, kind ->
                Sample(material, kind, x = 40f + column * 250f, y = 40f + row * 130f)
            }
        }
    }
}

/** One material, one tint, one light. */
@Composable
private fun Sample(material: () -> TextureHandle, kind: Kind, x: Float, y: Float) {
    val line = 5f
    Box(
        Modifier
            .offset(x, y)
            .size(210f, 90f)
            .relief(
                corners = Corners.all(20f),
                shape = Relief.Chamfer,
                depth = 0.13f,
                elevation = 42f,
                strength = 0.4f,
                gloss = kind.gloss,
                polish = kind.polish,
                face = kind.tint,
                material = material(),
                tiles = kind.tiles,
            )
            .borderOutside(kind.line, width = line, corners = Corners.all(20f)),
    )
}

/** What a material is tinted with, and how it takes a light. */
private class Kind(
    val tint: Colour,
    val line: Colour,
    val gloss: Float,
    val polish: Float,
    val tiles: Float = 1f,
)

private val Rows: List<Pair<() -> TextureHandle, List<Kind>>> = listOf(
    // Oak, walnut and mahogany: one picture and three browns. Nothing about the grain changes
    // between them, which is the point — a material is multiplied into whatever tints it.
    Materials::wood to listOf(
        Kind(Colour.rgb(0xC08A46), Colour.rgb(0x4A2E13), gloss = 0.25f, polish = 0.4f),
        Kind(Colour.rgb(0x7A4A23), Colour.rgb(0x321B09), gloss = 0.25f, polish = 0.4f),
        Kind(Colour.rgb(0x8E3B2A), Colour.rgb(0x3D1610), gloss = 0.3f, polish = 0.45f),
    ),
    // Paper, card and a parchment: almost no shine, because paper has almost none.
    Materials::paper to listOf(
        Kind(Colour.rgb(0xF6F2E6), Colour.rgb(0x9C8F6E), gloss = 0.05f, polish = 0.2f),
        Kind(Colour.rgb(0xD9C9A3), Colour.rgb(0x7E6A42), gloss = 0.05f, polish = 0.2f),
        Kind(Colour.rgb(0xBFD6E8), Colour.rgb(0x5E7C93), gloss = 0.08f, polish = 0.25f),
    ),
    // Steel, brass and anodised blue: a tight shine, which is what a scratched surface does to one.
    Materials::metal to listOf(
        Kind(Colour.rgb(0xC9CDD2), Colour.rgb(0x4A5158), gloss = 0.7f, polish = 0.75f),
        Kind(Colour.rgb(0xD8B25A), Colour.rgb(0x6A4E15), gloss = 0.7f, polish = 0.75f),
        Kind(Colour.rgb(0x6C96C4), Colour.rgb(0x2B4463), gloss = 0.6f, polish = 0.8f),
    ),
)

/**
 * The three grains, uploaded once each.
 *
 * A material is tiled across a face, so it has to be a texture of its own rather than a region of
 * an atlas: tiling a region samples whatever was packed next to it at every repeat.
 */
internal object Materials {

    private var woodTexture: GlTexture? = null
    private var paperTexture: GlTexture? = null
    private var metalTexture: GlTexture? = null

    fun wood(): TextureHandle = woodTexture ?: load("wood").also { woodTexture = it }

    fun paper(): TextureHandle = paperTexture ?: load("paper").also { paperTexture = it }

    fun metal(): TextureHandle = metalTexture ?: load("metal").also { metalTexture = it }

    private fun load(name: String): GlTexture {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("materials/$name.png")) {
            "no materials/$name.png on the classpath"
        }.use { it.readBytes() }
        return GlTexture.decode(bytes)
    }
}
