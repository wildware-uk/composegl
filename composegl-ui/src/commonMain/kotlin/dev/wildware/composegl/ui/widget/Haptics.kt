package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics

/**
 * The vibration motor, for the controls inside it. A game provides its backend's.
 *
 * Buttons, tick boxes, switches and radio buttons ask for a [LightTap][dev.wildware.composegl.ui.backend.Haptic.LightTap]
 * when they are clicked, and a stepped slider asks for a
 * [Tick][dev.wildware.composegl.ui.backend.Haptic.Tick] on every notch. Anything else — a failed
 * purchase, a hit — is the game's to ask for, from the same place.
 *
 * The default does nothing, which is the right answer on a desktop with no pad.
 */
val LocalHaptics: ProvidableCompositionLocal<Haptics> = staticCompositionLocalOf { Haptics.None }

@Composable
fun ProvideHaptics(haptics: Haptics, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalHaptics provides haptics, content = content)

/**
 * [onClick] with a light tap in front of it, for a control's `clickable`.
 *
 * The tap goes before the game's own handler, so a handler that asks for something heavier — a
 * failure, a purchase — is felt last, over the top of it.
 *
 * Remembered against the handler, because a lambda made fresh on every recomposition never compares
 * equal: the node's modifier would change each time its parent recomposed, and a still menu would
 * redraw for nothing.
 */
@Composable
internal fun rememberTapped(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    return remember(haptics, onClick) {
        {
            haptics.perform(Haptic.LightTap)
            onClick()
        }
    }
}
