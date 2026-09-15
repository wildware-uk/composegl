package dev.wildware.composegl.android

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics

/**
 * The phone's own feel for a tap, asked of a view rather than of the vibrator.
 *
 * `View.performHapticFeedback` is how Android's own buttons and keyboards do it, and that is why
 * this is here rather than LibGDX's `vibrate`: it needs no permission, it plays the effect the
 * phone's maker tuned for the actuator in that phone rather than a timed buzz, and it respects the
 * player's "touch feedback" setting without anybody asking.
 *
 * ```kotlin
 * val haptics = AndroidHaptics(view)
 * ProvideHaptics(haptics) { Hud() }
 * ```
 *
 * @param view any view in the game's window — the engine's surface will do. Its
 *   `isHapticFeedbackEnabled` is honoured, so a game can switch feedback off there too.
 */
class AndroidHaptics(private val view: View) : Haptics {

    override fun perform(haptic: Haptic) {
        val constant = feedbackConstant(haptic, Build.VERSION.SDK_INT)
        // Posted, because a game performs this from the render thread and a view may only be
        // touched on the thread that made it.
        view.post { view.performHapticFeedback(constant) }
    }

    internal companion object {

        /**
         * The effect Android has for [haptic], on a phone running [sdk].
         *
         * Confirm and reject only arrived in Android 11. Before that there is nothing that means
         * "it worked" or "it failed", so those borrow the nearest tap: a key for a success, a long
         * press — the heaviest thing on offer — for a failure.
         */
        fun feedbackConstant(haptic: Haptic, sdk: Int): Int = when (haptic) {
            Haptic.LightTap -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.MediumTap -> HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.HeavyTap -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Success ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Warning -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Failure ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }
}
