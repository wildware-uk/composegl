package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.text.TextOutline

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

/**
 * The ring drawn round the letters of everything inside, or null for none.
 *
 * What a whole HUD is outlined with in one place, rather than a parameter on every label in it.
 * Set it round the layer that sits over the game and a score, a nameplate and a floating number
 * are all readable over whatever is moving underneath.
 *
 * **Which widgets look at this**, because "everything inside" is a promise worth being exact
 * about: [Text], [Typewriter], [Tooltip], and in `composegl-game` `DamageNumberLayer` and
 * `MinimapFrame`'s compass letters. Not
 * [TextField] — a field is a box on a background of its own, and its caret and selection are
 * measured off the raw glyph advances, so an outline there would be paint in the wrong place as
 * often as not. Not [PromptGlyph] — a key-cap already has its own background to stand out against.
 * Both take an explicit outline the day somebody wants one.
 *
 * Not static: a game may well turn this on and off as the background behind the HUD changes, and
 * only what is inside should be recomposed when it does.
 *
 * @see dev.wildware.composegl.ui.text.TextOutline for what the ring is and is not — it is stamped
 *   out of the bitmap glyphs, not stroked, and there are two colour traps worth reading about.
 */
val LocalTextOutline: ProvidableCompositionLocal<TextOutline?> = compositionLocalOf { null }

/** Outlines the text of everything inside. @see LocalTextOutline */
@Composable
fun ProvideTextOutline(outline: TextOutline?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextOutline provides outline, content = content)
}

/** The same, for the usual case of a colour and a width. @see LocalTextOutline */
@Composable
fun ProvideTextOutline(colour: Colour, width: Float = 2f, content: @Composable () -> Unit) {
    val outline = remember(colour, width) { TextOutline(colour, width) }
    CompositionLocalProvider(LocalTextOutline provides outline, content = content)
}
