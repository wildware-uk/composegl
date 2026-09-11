package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.graphics.Colour
import composegl.ui.graphics.NinePatch
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePolicy
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.border
import composegl.ui.modifier.ninePatch
import composegl.ui.modifier.padding
import composegl.ui.modifier.shadow
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle

/**
 * Widgets, written by a game rather than shipped by the toolkit.
 *
 * That is the point of this file. The widget set lands in a later milestone; everything here is
 * built out of `Layout`, `Box` and the modifier chain, using nothing a game could not use. If a
 * game cannot write a label with what the toolkit exposes, the toolkit is wrong.
 */

/** Where text measurement comes from. The toolkit will grow its own; this one is the game's. */
val LocalFonts = staticCompositionLocalOf<FontProvider> { error("no fonts were provided") }

/**
 * The game's art, in the shape the interface wants it.
 *
 * Two nine-patches out of one atlas. A real game's would have forty, and would be loaded from a
 * skin file rather than assembled by hand — that is a later milestone. The point here is that the
 * interface below never mentions a texture, a region or a corner size.
 */
class DemoSkin(val panel: NinePatch, val ribbon: NinePatch)

val LocalSkin = staticCompositionLocalOf<DemoSkin> { error("no skin was provided") }

/**
 * A run of text.
 *
 * Measuring happens inside the measure policy, because how much room there is decides where the
 * lines break, and drawing uses the layout that measuring produced — never a second one.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    colour: Colour = Colour.White,
) {
    val fonts = LocalFonts.current
    var measured: TextLayout? = null

    LeafLayout(
        modifier = modifier,
        name = "text",
        measurePolicy = MeasurePolicy { _, constraints ->
            val measurement = fonts.measure(text, style, constraints.maxWidth)
            measured = measurement
            layout(
                constraints.constrainWidth(measurement.size.width),
                constraints.constrainHeight(measurement.size.height),
            ) {}
        },
        // Drawing uses the layout measuring produced, never a second one: measuring twice is how
        // text ends up drawn a pixel away from where the space was reserved for it.
        draw = { bounds -> measured?.let { text(it, bounds.topLeft, colour) } },
    )
}

/**
 * A panel cut out of the art, rather than drawn by the shader.
 *
 * Note what is *not* here: a padding value. The gap between the bevel and the contents is written
 * in the atlas beside the picture, so changing the art changes the layout, and a skin swap does
 * not leave the text sitting on the frame.
 */
@Composable
fun ArtPanel(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(Colour.argb(0x80000000), spread = 14f, corner = 12f)
            .ninePatch(LocalSkin.current.panel),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * A section heading on a ribbon whose hatch repeats sideways and stretches down.
 *
 * The reason the middle of a nine-patch has a mode per axis: stretching this pattern would smear
 * it into a grey wash at any width worth having.
 */
@Composable
fun Heading(label: String, style: TextStyle) {
    Box(Modifier.ninePatch(LocalSkin.current.ribbon)) {
        Text(label, style = style)
    }
}

/** A raised panel: a shadow, a rounded fill and a hairline border. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    fill: Colour = Colour.argb(0xF01B1F2A),
    edge: Colour = Colour.argb(0x40FFFFFF),
    corner: Float = 10f,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(Colour.argb(0x80000000), spread = 14f, corner = corner)
            .background(fill, corner)
            .border(edge, width = 1f, corner = corner)
            .padding(16f),
        contentAlignment = contentAlignment,
        content = content,
    )
}
