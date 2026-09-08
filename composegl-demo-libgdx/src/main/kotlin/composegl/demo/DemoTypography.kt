package composegl.demo

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Material 3's defaults, with the bundled font swapped in for the styles this demo uses.
 *
 * Bundling a font is what a real game wants: `FontFamily.Default` resolves through the machine's
 * own font manager, so the same HUD looks different on every player's computer.
 */
internal fun demoTypography(family: FontFamily): Typography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
    )
}
