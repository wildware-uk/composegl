package dev.wildware.composegl.robovm

import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import org.robovm.apple.dispatch.DispatchQueue
import org.robovm.apple.uikit.UIImpactFeedbackGenerator
import org.robovm.apple.uikit.UIImpactFeedbackStyle
import org.robovm.apple.uikit.UINotificationFeedbackGenerator
import org.robovm.apple.uikit.UINotificationFeedbackType
import org.robovm.apple.uikit.UISelectionFeedbackGenerator

/**
 * The iPhone's Taptic Engine, through UIKit's feedback generators.
 *
 * iOS has three generators and they mean three different things: an impact is something hitting
 * something, a selection is a picker clicking past a value, and a notification is an outcome. A
 * [Haptic] is named by what happened, so each lands on the one iOS would use — which is why this is
 * UIKit's rather than LibGDX's `vibrate`, which only knows light, medium and heavy impacts.
 *
 * ```kotlin
 * val haptics = UiKitHaptics()
 * ProvideHaptics(haptics) { Hud() }
 * ```
 *
 * The player's "System Haptics" setting is honoured by UIKit itself.
 *
 * @param onMainThread how to get onto the main thread, which is the only one UIKit may be touched
 *   on. The default posts to the main queue, which is right for a game drawing on its own thread and
 *   costs a frame's delay at most for one that is not.
 */
class UiKitHaptics(
    private val onMainThread: (Runnable) -> Unit = { DispatchQueue.getMainQueue().async(it) },
) : Haptics {

    // Kept rather than made per tap: a generator warms the engine up, and a fresh one each time is
    // the difference between feedback on the press and feedback a moment after it.
    //
    // The style constructor is deprecated in favour of one tied to a view, but that one only exists
    // from iOS 17.5, and a game ships to phones older than that.
    @Suppress("DEPRECATION")
    private val light by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.Light) }
    @Suppress("DEPRECATION")
    private val medium by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.Medium) }
    @Suppress("DEPRECATION")
    private val heavy by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.Heavy) }
    private val selection by lazy { UISelectionFeedbackGenerator() }
    private val notification by lazy { UINotificationFeedbackGenerator() }

    override fun perform(haptic: Haptic) {
        val feedback = UiKitFeedback.of(haptic)
        onMainThread(Runnable { play(feedback) })
    }

    private fun play(feedback: UiKitFeedback) = when (feedback) {
        UiKitFeedback.LightImpact -> light.impactOccurred()
        UiKitFeedback.MediumImpact -> medium.impactOccurred()
        UiKitFeedback.HeavyImpact -> heavy.impactOccurred()
        UiKitFeedback.Selection -> selection.selectionChanged()
        UiKitFeedback.Success -> notification.notificationOccurred(UINotificationFeedbackType.Success)
        UiKitFeedback.Warning -> notification.notificationOccurred(UINotificationFeedbackType.Warning)
        UiKitFeedback.Error -> notification.notificationOccurred(UINotificationFeedbackType.Error)
    }
}

/**
 * What UIKit plays for each [Haptic], as a plain value.
 *
 * Split out for the same reason [Mirror] is: the decision runs in a test, and the generators it
 * leads to only run on an iPhone.
 */
internal enum class UiKitFeedback {
    LightImpact, MediumImpact, HeavyImpact, Selection, Success, Warning, Error;

    companion object {
        fun of(haptic: Haptic): UiKitFeedback = when (haptic) {
            Haptic.LightTap -> LightImpact
            Haptic.MediumTap -> MediumImpact
            Haptic.HeavyTap -> HeavyImpact
            Haptic.Tick -> Selection
            Haptic.Success -> Success
            Haptic.Warning -> Warning
            Haptic.Failure -> Error
        }
    }
}
