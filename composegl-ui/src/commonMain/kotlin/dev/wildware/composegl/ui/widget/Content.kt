package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.wildware.composegl.ui.skin.ResolvedStyle

/**
 * The style the thing around this one is drawn in.
 *
 * What makes `Button("PLAY") { }` work. The button resolves `"button"` for the state it is
 * actually in — hovered, pressed, disabled — and puts the answer here, so the label inside takes
 * the button's colour and font without the caller saying so and without the button having to know
 * that its content happens to be text.
 *
 * Null means nothing is wrapping this, and a widget falls back to its own named style.
 *
 * Not static, unlike the skin: this changes whenever a pointer moves over a button, and only the
 * contents of that button should be recomposed when it does.
 */
val LocalContentStyle: ProvidableCompositionLocal<ResolvedStyle?> = compositionLocalOf { null }

/** The style everything inside should be drawn in, unless it names one of its own. */
@Composable
fun ProvideContentStyle(style: ResolvedStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentStyle provides style, content = content)
}
