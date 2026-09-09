package composegl.showcase.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** The palette the whole showcase draws from. */
object Hud {
    val Cyan = Color(0xFF6FD3FF)
    val CyanDim = Color(0xFF2E6C8A)
    val Amber = Color(0xFFFFC46B)
    val Danger = Color(0xFFFF6B6B)
    val Shield = Color(0xFF7FE3FF)
    val Integrity = Color(0xFF8BE08F)
    val Ink = Color(0xFF060A10)
    val Panel = Color(0xCC0B131C)
    val Line = Color(0x556FD3FF)
}

private val Scheme = darkColorScheme(
    primary = Hud.Cyan,
    onPrimary = Hud.Ink,
    surface = Color(0xFF0C1219),
    onSurface = Color(0xFFDCE8F2),
    surfaceVariant = Color(0xFF141D28),
    onSurfaceVariant = Color(0xFF9FB6C8),
    background = Hud.Ink,
    error = Hud.Danger,
)

@Composable
fun ShowcaseTheme(fontFamily: FontFamily, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = typographyWith(fontFamily),
        content = content,
    )
}

private fun typographyWith(family: FontFamily): Typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
