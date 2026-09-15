package dev.wildware.composegl.android

import android.view.HapticFeedbackConstants
import android.view.View
import dev.wildware.composegl.ui.backend.Haptic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of the phone's effects each kind of feedback plays, and that asking reaches the view.
 *
 * Whether a real phone then moves, and whether it feels right, needs a phone in a hand.
 */
class AndroidHapticsTest {

    /** A view that runs what is posted to it at once and writes down what it was asked to play. */
    private class Recording : View(null) {
        val played = mutableListOf<Int>()
        val posted = mutableListOf<Runnable>()
        override fun post(action: Runnable): Boolean {
            posted += action
            return true
        }
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }

    /** What Android 8, this module's oldest, already had. */
    private val oldest = setOf(
        HapticFeedbackConstants.LONG_PRESS,
        HapticFeedbackConstants.VIRTUAL_KEY,
        HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.CONTEXT_CLICK,
    )

    @Test
    fun `a tap is played on the main thread and not on the caller's`() {
        val view = Recording()
        val haptics = AndroidHaptics(view)

        haptics.perform(Haptic.LightTap)
        assertTrue(view.played.isEmpty(), "the view was touched from the thread that asked")

        view.posted.forEach { it.run() }
        assertEquals(1, view.played.size)
    }

    @Test
    fun `an old phone is never asked for an effect it does not have`() {
        Haptic.entries.forEach {
            val constant = AndroidHaptics.feedbackConstant(it, sdk = 26)
            assertTrue(constant in oldest, "$it asks Android 8 for effect $constant")
        }
    }

    @Test
    fun `a newer phone says it worked and it failed in its own words`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, AndroidHaptics.feedbackConstant(Haptic.Success, sdk = 30))
        assertEquals(HapticFeedbackConstants.REJECT, AndroidHaptics.feedbackConstant(Haptic.Failure, sdk = 30))
    }

    @Test
    fun `a notch a tap and a thud feel different`() {
        val tick = AndroidHaptics.feedbackConstant(Haptic.Tick, sdk = 34)
        val tap = AndroidHaptics.feedbackConstant(Haptic.LightTap, sdk = 34)
        val thud = AndroidHaptics.feedbackConstant(Haptic.HeavyTap, sdk = 34)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, tick)
        assertNotEquals(tick, tap)
        assertNotEquals(tap, thud)
    }
}
