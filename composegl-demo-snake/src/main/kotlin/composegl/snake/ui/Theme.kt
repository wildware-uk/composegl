package composegl.snake.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val Scheme = darkColorScheme(
    primary = Color(0xFF7EE081),
    onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF1B4D2E),
    onPrimaryContainer = Color(0xFFB9F5BC),
    secondary = Color(0xFF9FB4D6),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFB6BFCF),
    background = Color(0xFF0B0E13),
    error = Color(0xFFEF5350),
)

/**
 * The game's look, in one place.
 *
 * The font is bundled rather than left to `FontFamily.Default`, which resolves through whatever
 * font manager the player's machine has — the same HUD would otherwise look different on every
 * computer it runs on.
 */
@Composable
fun SnakeTheme(fontFamily: FontFamily, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = typographyWith(fontFamily),
        content = content,
    )
}

private fun typographyWith(family: FontFamily): Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
