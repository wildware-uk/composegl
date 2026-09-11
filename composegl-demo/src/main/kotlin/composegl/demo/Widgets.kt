package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.graphics.Colour
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.MeasurePolicy
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.shadow
import composegl.ui.modifier.styled as styledWith
import composegl.ui.skin.rememberStyle
import composegl.ui.skin.styled
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle

/**
 * Widgets, written by a game rather than shipped by the toolkit.
 *
 * That is the point of this file. The widget set lands in a later milestone; everything here is
 * built out of `Layout`, `Box` and the modifier chain, using nothing a game could not use. If a
 * game cannot write a label with what the toolkit exposes, the toolkit is wrong.
 *
 * None of it contains a colour. Every widget here names a style — `"panel"`, `"chip.danger"` — and
 * draws what the skin hands back, which is why `ui/demo.skin.json` can be edited while the example
 * is running and the example changes.
 */

/** Where text measurement comes from. The toolkit will grow its own; this one is the game's. */
val LocalFonts = staticCompositionLocalOf<FontProvider> { error("no fonts were provided") }

/**
 * A run of text, in a style the skin names.
 *
 * Measuring happens inside the measure policy, because how much room there is decides where the
 * lines break, and drawing uses the layout that measuring produced — never a second one.
 */
@Composable
fun Label(
    text: String,
    style: String = "label",
    modifier: Modifier = Modifier,
) {
    val resolved = rememberStyle(style)
    Text(text, modifier, resolved.textStyle, resolved.textColour)
}

/** The same, for the few places that have a piece of text and a style already in hand. */
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
 * A panel, whatever the skin says a panel is.
 *
 * Note what is *not* here: a colour, a texture, a set of slices or a padding value. `"panel"` is
 * cut out of the art and `"panel.flat"` is drawn by the shader, and this composable cannot tell
 * which it was handed. Changing the art changes the layout with it, so a skin swap does not leave
 * the text sitting on the frame.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    style: String = "panel",
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(Colour.argb(0x80000000), spread = 14f, corner = 12f)
            .styled(style),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * A section heading on a ribbon whose hatch repeats sideways and stretches down.
 *
 * The reason the middle of a nine-patch has a mode per axis: stretching this pattern would smear
 * it into a grey wash at any width worth having. Which axis does which is in the skin file.
 */
@Composable
fun Heading(label: String, style: String = "heading") {
    val resolved = rememberStyle(style)
    Box(Modifier.styledWith(resolved)) {
        Text(label, style = resolved.textStyle, colour = resolved.textColour)
    }
}

/**
 * The ring that says where the player is.
 *
 * A separate box around the widget rather than a thicker border on it, because a focus ring is
 * outside the thing it marks — and because the skin gives both the drawn ring and the hidden one
 * the same padding, gaining focus never shifts the layout.
 */
@Composable
fun FocusRing(focused: Boolean, content: @Composable () -> Unit) {
    // Not drawn while somebody is using a mouse: a ring is a cursor for people who have no cursor,
    // and drawn next to a hover highlight it is just a second highlight arguing with the first.
    val shown = focused && LocalInputSource.current.showsFocusRing
    Box(Modifier.styled(if (shown) "focusRing" else "focusRing.hidden"), content = content)
}
