package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import composegl.ui.graphics.TextureHandle
import composegl.ui.input.InteractionState
import composegl.ui.layout.Alignment
import composegl.ui.layout.Box
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clickable
import composegl.ui.modifier.focusable
import composegl.ui.modifier.interaction
import composegl.ui.modifier.size
import composegl.ui.modifier.styled
import composegl.ui.skin.rememberStates
import composegl.ui.skin.rememberStyle

/**
 * A button.
 *
 * Everything about how it looks is the skin's: the frame in each of its four states, the padding
 * that keeps the label off the frame, and the pixel the contents drop by while it is held down.
 * Everything about how it behaves is the toolkit's: a press that wanders off the button and lets
 * go elsewhere is a change of mind and does not fire, a disabled button cannot be clicked or
 * focused but still swallows the click so it cannot fall through to whatever is behind it, and the
 * pad's South button and the keyboard's Enter press whatever has focus.
 *
 * The label takes the button's colour and font without being told, because the button publishes
 * the style it resolved as [LocalContentStyle].
 *
 * @param style the skin style to draw from. `"button.primary"` and `"button.danger"` ship with the
 *   default skin; a game adds its own by naming them in its skin file.
 * @param enabled false greys it out — through the skin's `disabled` state — and takes it out of
 *   the focus order, so a pad cannot land on something it cannot press.
 * @param initialFocus true on the one control a screen should open with focus on. A console
 *   interface with no focus anywhere is an interface the player cannot use at all.
 * @param interaction pass one in to read what the pointer is doing to this button from outside —
 *   a tooltip that appears on hover, a sound on press.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: String = "button",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    contentAlignment: Alignment = Alignment.Centre,
    content: @Composable () -> Unit,
) {
    val resolved = rememberStyle(style, rememberStates(interaction, enabled))

    Box(
        modifier = modifier
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .clickable(enabled = enabled, onClick = onClick)
            .styled(resolved),
        contentAlignment = contentAlignment,
    ) {
        ProvideContentStyle(resolved, content)
    }
}

/** The usual button: a word on it. */
@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: String = "button",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Button(onClick, modifier, style, enabled, initialFocus, interaction) {
        Text(text)
    }
}

/**
 * A button with a picture on it instead of a word.
 *
 * The picture is tinted with the style's text colour, so one grey icon is a whole set: it dims
 * when the button is disabled and brightens when it is hovered, without a second file.
 *
 * @param icon a region name in the skin's atlas.
 * @param size how big the picture is drawn. Not the button's size — the skin's padding decides
 *   that, the same way it decides the space around a label.
 */
@Composable
fun IconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: String = "button.icon",
    size: Float = 20f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Button(onClick, modifier, style, enabled, initialFocus, interaction) {
        val tint = LocalContentStyle.current?.textColour ?: rememberStyle(style).textColour
        Image(icon, Modifier.size(size), tint = tint)
    }
}

/** The same, for a game holding a picture rather than a name. */
@Composable
fun IconButton(
    icon: TextureHandle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: String = "button.icon",
    size: Float = 20f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Button(onClick, modifier, style, enabled, initialFocus, interaction) {
        val tint = LocalContentStyle.current?.textColour ?: rememberStyle(style).textColour
        Image(icon, Modifier.size(size), tint = tint)
    }
}
