package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.graphics.Colour
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePolicy
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.background
import composegl.ui.modifier.border
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
