package dev.wildware.composegl.robovm

import dev.wildware.composegl.ui.backend.Haptic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of UIKit's generators each kind of feedback reaches, and that none is touched in place.
 *
 * The generators themselves only run on an iPhone, so what is checked here is the decision and the
 * hop onto the main thread; how it feels needs a phone in a hand.
 */
class UiKitHapticsTest {

    @Test
    fun `each kind of feedback reaches the generator iOS would use for it`() {
        assertEquals(
            mapOf(
                Haptic.LightTap to UiKitFeedback.LightImpact,
                Haptic.MediumTap to UiKitFeedback.MediumImpact,
                Haptic.HeavyTap to UiKitFeedback.HeavyImpact,
                Haptic.Tick to UiKitFeedback.Selection,
                Haptic.Success to UiKitFeedback.Success,
                Haptic.Warning to UiKitFeedback.Warning,
                Haptic.Failure to UiKitFeedback.Error,
            ),
            Haptic.entries.associateWith { UiKitFeedback.of(it) },
        )
    }

    @Test
    fun `asking from the render thread only posts to the main one`() {
        val posted = mutableListOf<Runnable>()
        val haptics = UiKitHaptics(onMainThread = { posted += it })

        // Nothing here runs the posted work: doing it would make a UIKit call off an iPhone, which
        // is exactly what a call in place would do on the render thread.
        haptics.perform(Haptic.LightTap)
        haptics.perform(Haptic.Tick)

        assertEquals(2, posted.size)
    }
}
