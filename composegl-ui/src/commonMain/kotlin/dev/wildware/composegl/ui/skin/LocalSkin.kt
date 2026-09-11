package dev.wildware.composegl.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.styled

/**
 * The skin every widget under here draws from.
 *
 * A composition local rather than an argument, because the alternative is threading a skin through
 * every widget in a game by hand, and the first person to forget gets a button that does not match
 * the others. `Button("PLAY")` looks like the game without the game having said so.
 *
 * Static, so a new skin rebuilds the interface rather than being diffed into it. A skin changes
 * when a game starts and when an artist saves a file, and neither is a thing to optimise for.
 *
 * The default is [Skin.Default], the neutral dark skin that ships with the toolkit, so a game that
 * has registered nothing at all still has an interface somebody can use.
 */
val LocalSkin: ProvidableCompositionLocal<Skin> = staticCompositionLocalOf { Skin.Default }

/**
 * The game's skin, for everything inside.
 *
 * Usually written once, around the whole interface. Handing it [ReloadingSkin.skin] is all a game
 * needs to do to pick up an artist's saves while it runs.
 */
@Composable
fun ProvideSkin(skin: Skin, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSkin provides skin, content = content)
}

/**
 * The same skin with some of it changed, for this subtree only.
 *
 * How a screen says "the buttons in this dialogue are red" without copying the skin, reaching into
 * it, or leaving red buttons behind when the dialogue closes. The overrides are laid over the skin
 * style by style and field by field, so naming one hovered colour changes one hovered colour.
 */
@Composable
fun SkinOverride(overrides: Skin, content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    val merged = remember(skin, overrides) { skin.overriddenWith(overrides) }
    CompositionLocalProvider(LocalSkin provides merged, content = content)
}

/**
 * The style called [name], for a widget in [states].
 *
 * What a widget calls instead of choosing a colour. Recomputed when the skin changes, which is how
 * a saved file reaches the screen, and not otherwise.
 */
@Composable
fun rememberStyle(name: String, states: Set<WidgetState> = emptySet()): ResolvedStyle {
    val skin = LocalSkin.current
    return remember(skin, name, states) { skin.resolve(name, states) }
}

/**
 * What a widget is doing, as the skin thinks of it.
 *
 * The join between the input side of the toolkit and the appearance side, in one place, so that
 * forty widgets do not each decide for themselves whether a disabled button counts as hovered.
 */
@Composable
fun rememberStates(interaction: InteractionState, enabled: Boolean = true): Set<WidgetState> {
    val hovered = interaction.isHovered
    val pressed = interaction.isPressed
    val focused = interaction.isFocused
    return remember(hovered, pressed, focused, enabled) {
        buildSet {
            if (hovered) add(WidgetState.Hovered)
            if (focused) add(WidgetState.Focused)
            if (pressed) add(WidgetState.Pressed)
            if (!enabled) add(WidgetState.Disabled)
        }
    }
}

/**
 * Wears the skin's style called [name].
 *
 * The one line a widget writes to look like the rest of the game: background, tint, padding and
 * content offset, all of them the skin's numbers. Text colour and font come from the same style,
 * through [rememberStyle], because only the widget knows where its text goes.
 */
@Composable
fun Modifier.styled(name: String, states: Set<WidgetState> = emptySet()): Modifier =
    styled(rememberStyle(name, states))
