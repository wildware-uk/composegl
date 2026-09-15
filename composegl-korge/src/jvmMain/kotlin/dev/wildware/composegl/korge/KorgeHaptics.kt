package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InputSourceTracker
import korlibs.render.GameWindow

/**
 * A phone's vibration, as KorGE offers it.
 *
 * Which motor a player should feel depends on what is in their hand, so with [source] set that is
 * decided by what they last used, as `GdxHaptics` does it: a touch buzzes the phone, and a mouse or
 * a keyboard — which have no motor — moves nothing, so a desktop player clicking does not feel the
 * machine hum. With no [source] the phone is asked, since that is the only motor there is.
 *
 * A pad does not rumble. KorGE 6 reads pads (`GamepadInfo`) but has no call to vibrate one, on any
 * platform, so a player on a pad feels nothing rather than having the phone in their pocket buzz.
 * When KorGE grows one, it goes in the [InputSource.Gamepad] branch.
 *
 * The phone half prefers the window's own haptic engine (`GameWindow.hapticFeedbackGenerate`: iOS's
 * generators and Android's view feedback), which needs no permission and speaks the platform's own
 * vocabulary. A window without one falls back to [vibrator] — KorGE's `NativeVibration`, which
 * needs Android's `VIBRATE` permission and does nothing on a desktop. [ComposeGlView] wires both up.
 *
 * @param window the game's window, asked for each time rather than held.
 * @param vibrator a plain buzz for a length and a strength, for a window with no haptic engine.
 * @param source what the player last used. Hand it the same tracker the input goes through.
 */
class KorgeHaptics(
    private val window: () -> GameWindow? = { null },
    var vibrator: ((durationMillis: Int, strength: Float) -> Unit)? = null,
    var source: InputSourceTracker? = null,
) : Haptics {

    override fun perform(haptic: Haptic) {
        when (source?.current) {
            InputSource.Touch, null -> phone(haptic)
            // No rumble in KorGE; see the class comment.
            InputSource.Gamepad -> Unit
            InputSource.Mouse, InputSource.Keyboard -> Unit
        }
    }

    private fun phone(haptic: Haptic) {
        val engine = window()
        if (engine != null && engine.hapticFeedbackGenerateSupport) {
            engine.hapticFeedbackGenerate(kindOf(haptic))
            return
        }
        try {
            vibrator?.invoke(haptic.durationMillis, haptic.strength)
        } catch (_: SecurityException) {
            // An Android game without the VIBRATE permission. A button must not crash for want of a
            // buzz; the fix — the permission — is the game's.
        }
    }

    companion object {

        /**
         * KorGE's three kinds of feedback. A notch is an alignment (iOS's selection tick), an outcome
         * is a level change (a notification), and every tap is the generic impact.
         */
        fun kindOf(haptic: Haptic): GameWindow.HapticFeedbackKind = when (haptic) {
            Haptic.Tick -> GameWindow.HapticFeedbackKind.ALIGNMENT
            Haptic.LightTap, Haptic.MediumTap, Haptic.HeavyTap -> GameWindow.HapticFeedbackKind.GENERIC
            Haptic.Success, Haptic.Warning, Haptic.Failure -> GameWindow.HapticFeedbackKind.LEVEL_CHANGE
        }
    }
}
