package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.text.TextStyle

/**
 * How much bigger than its style says text inside should be drawn. One means as styled.
 *
 * The player's text-size setting, kept apart from the [dev.wildware.composegl.ui.layout.Viewport]
 * scale. The viewport makes the whole interface bigger — panels, icons, gaps and all — which is
 * the wrong answer to "I cannot read this", because it spends the screen on everything that was
 * already big enough. This makes only the letters bigger, and everything sized by its contents
 * grows to fit them: a button round a label, a tooltip, a row of text, the height of a field.
 * Anything given a fixed size stays that size, and text in it wraps sooner or is cut off, the same
 * as any text that does not fit.
 *
 * Applied to the style's size when text is measured, so it is measured and baked at the scaled
 * size rather than measured small and stretched. See [TextStyle.scaled] for the rounding, and
 * [dev.wildware.composegl.ui.text.scaledTextSizes] for the font sizes a game has to register.
 *
 * **Which widgets look at this**: [Text] in every form, [Typewriter], [TextField], [Tooltip],
 * [PromptGlyph], `DamageNumberLayer`, `Minimap`'s compass letters, and everything built out of
 * those, which is how `Button`, `Stepper` and `Hotbar` labels follow along. A widget handed a `textStyle` of
 * its own is scaled too: the setting is the player's, and a label that ignored it would be the one
 * they cannot read.
 *
 * **The one exception is `Subtitles`**, which replaces this with its own size preset rather than
 * multiplying by it. Subtitle size is its own row in its own accessibility menu, and a player who
 * set the interface to 150% and their subtitles to Medium asked for Medium subtitles.
 *
 * Static: a text size changes when a player moves a slider in a settings menu, not every frame,
 * and every piece of text inside has to be measured again when it does.
 */
val LocalTextScale: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

/**
 * Draws text inside [scale] times the size its style asks for.
 *
 * ```kotlin
 * ProvideTextScale(settings.textScale) { Game() }
 * ```
 *
 * Nested, they multiply rather than replace: a dense stats panel that asks for 0.85 inside a
 * player's 1.5 draws at 1.275, so a part of the screen that wants smaller text still honours the
 * setting rather than quietly overriding it.
 */
@Composable
fun ProvideTextScale(scale: Float, content: @Composable () -> Unit) {
    require(scale > 0f && scale.isFinite()) { "a text scale must be positive, was $scale" }
    CompositionLocalProvider(LocalTextScale provides LocalTextScale.current * scale, content = content)
}

/** [style] at the text scale in force here, remembered on both. @see LocalTextScale */
@Composable
fun rememberScaled(style: TextStyle): TextStyle {
    val scale = LocalTextScale.current
    return remember(style, scale) { style.scaled(scale) }
}
