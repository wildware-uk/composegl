package uk.wildware.composegl.demo

import androidx.compose.runtime.Composable
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.shadow
import uk.wildware.composegl.ui.modifier.styled as styledWith
import uk.wildware.composegl.ui.skin.rememberStyle
import uk.wildware.composegl.ui.skin.styled
import uk.wildware.composegl.ui.widget.LocalInputSource
import uk.wildware.composegl.ui.widget.Text

/**
 * Widgets, written by a game rather than shipped by the toolkit.
 *
 * That is the point of this file. Text and pictures are the toolkit's now; everything left here is
 * built out of `Box` and the modifier chain, using nothing a game could not use. If a game cannot
 * write its own panel with what the toolkit exposes, the toolkit is wrong.
 *
 * None of it contains a colour. Every widget here names a style — `"panel"`, `"chip.danger"` — and
 * draws what the skin hands back, which is why `ui/demo.skin.json` can be edited while the example
 * is running and the example changes.
 */

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
        Text(label, textStyle = resolved.textStyle, colour = resolved.textColour)
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
