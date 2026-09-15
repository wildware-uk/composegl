package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InputSourceTracker
import korlibs.event.ISoftKeyboardConfig
import korlibs.render.GameWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A KorGE window that writes down the haptics, soft keyboard and cursor it was asked for. */
internal class RecordingWindow(private val haptics: Boolean = true) : GameWindow() {
    val feedback = mutableListOf<HapticFeedbackKind>()
    val keyboard = mutableListOf<Boolean>()
    val cursors = mutableListOf<ICursor>()

    override val hapticFeedbackGenerateSupport: Boolean get() = haptics
    override fun hapticFeedbackGenerate(kind: HapticFeedbackKind) {
        feedback += kind
    }

    override fun showSoftKeyboard(force: Boolean, config: ISoftKeyboardConfig?) {
        keyboard += true
    }

    override fun hideSoftKeyboard() {
        keyboard += false
    }

    override var cursor: ICursor
        get() = cursors.lastOrNull() ?: Cursor.DEFAULT
        set(value) {
            cursors += value
        }
}

/**
 * Which motor a player feels, with no phone and no KorGE game. Whether the phone then buzzes, and how
 * it feels, needs the phone.
 */
class KorgeHapticsTest {

    private val window = RecordingWindow()
    private val buzzes = mutableListOf<Pair<Int, Float>>()
    private val tracker = InputSourceTracker()
    private val haptics = KorgeHaptics({ window }, { ms, strength -> buzzes += ms to strength }, tracker)

    @Test
    fun `a touch asks the window's haptic engine, by kind`() {
        tracker.saw(InputSource.Touch)

        haptics.perform(Haptic.Tick)
        haptics.perform(Haptic.HeavyTap)
        haptics.perform(Haptic.Failure)

        assertEquals(
            listOf(
                GameWindow.HapticFeedbackKind.ALIGNMENT,
                GameWindow.HapticFeedbackKind.GENERIC,
                GameWindow.HapticFeedbackKind.LEVEL_CHANGE,
            ),
            window.feedback,
        )
        assertTrue(buzzes.isEmpty(), "the plain buzz is only for a window with no engine")
    }

    @Test
    fun `a window with no haptic engine gets a plain buzz as long and as hard as the feedback asks`() {
        val plain = KorgeHaptics({ RecordingWindow(haptics = false) }, { ms, strength -> buzzes += ms to strength }, tracker)
        tracker.saw(InputSource.Touch)

        plain.perform(Haptic.Success)

        assertEquals(listOf(Haptic.Success.durationMillis to Haptic.Success.strength), buzzes)
    }

    @Test
    fun `a mouse, a keyboard or a pad moves nothing`() {
        listOf(InputSource.Mouse, InputSource.Keyboard, InputSource.Gamepad).forEach {
            tracker.saw(it)
            haptics.perform(Haptic.LightTap)
        }

        assertTrue(window.feedback.isEmpty(), "the phone buzzed for ${window.feedback}")
        assertTrue(buzzes.isEmpty())
    }

    @Test
    fun `with no idea what the player holds the phone is asked`() {
        KorgeHaptics({ window }).perform(Haptic.MediumTap)

        assertEquals(listOf(GameWindow.HapticFeedbackKind.GENERIC), window.feedback)
    }

    @Test
    fun `a game with no window yet, or no permission to vibrate, does not crash the button`() {
        KorgeHaptics().perform(Haptic.LightTap)
        KorgeHaptics({ null }, { _, _ -> throw SecurityException("no VIBRATE") }).perform(Haptic.LightTap)
        assertFalse(false)
    }
}
