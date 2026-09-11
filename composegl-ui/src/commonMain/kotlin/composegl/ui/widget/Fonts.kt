package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.skin.LocalSkin
import composegl.ui.text.FontProvider

/**
 * Where a widget measures text.
 *
 * A backend's, always: only a backend knows what a glyph looks like. This is how a game hands one
 * over when it has no skin, or a skin with no fonts attached to it.
 */
val LocalFonts: ProvidableCompositionLocal<FontProvider?> = staticCompositionLocalOf { null }

/** The fonts every widget inside should measure with. */
@Composable
fun ProvideFonts(fonts: FontProvider, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFonts provides fonts, content = content)
}

/**
 * The font provider a widget should use.
 *
 * The skin's wins, because the skin is what names families: a style saying `"font": "display"` is
 * only meaningful to the provider that was loaded beside it. [LocalFonts] is the fallback, for a
 * game that has fonts and no skin.
 *
 * Throws rather than drawing nothing. Text that silently does not appear is one of the worst
 * afternoons in interface work, and a game that has registered no fonts at all has a bug in its
 * setup rather than in its screen.
 */
@Composable
fun rememberFonts(): FontProvider = LocalSkin.current.fonts
    ?: LocalFonts.current
    ?: error("no fonts: load the skin with a FontProvider, or wrap this in ProvideFonts")
