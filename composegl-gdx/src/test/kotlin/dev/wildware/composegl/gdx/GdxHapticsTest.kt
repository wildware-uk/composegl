package dev.wildware.composegl.gdx

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.headless.mock.input.MockInput
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.ControllerListener
import com.badlogic.gdx.controllers.ControllerMapping
import com.badlogic.gdx.controllers.ControllerPowerLevel
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InputSourceTracker
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Which motor a player feels, with no phone, no pad and no LibGDX application.
 *
 * The phone is LibGDX's mock input with `vibrate` written down, and the pads are made up. Whether
 * a real actuator then moves, and how it feels, needs the hardware.
 */
class GdxHapticsTest {

    private class Phone : MockInput() {
        val buzzes = mutableListOf<Input.VibrationType>()
        override fun vibrate(vibrationType: Input.VibrationType) {
            buzzes += vibrationType
        }
    }

    /** A pad that rumbles into a list. [rumbles] false is a pad with no motor in it. */
    private class Pad(private val rumbles: Boolean = true) : Controller {
        val played = mutableListOf<Pair<Int, Float>>()
        override fun startVibration(duration: Int, strength: Float) {
            played += duration to strength
        }
        override fun canVibrate() = rumbles
        override fun isVibrating() = false
        override fun cancelVibration() = Unit
        override fun getButton(buttonCode: Int) = false
        override fun getAxis(axisCode: Int) = 0f
        override fun getName() = "pad"
        override fun getUniqueId() = "pad"
        override fun getMinButtonIndex() = 0
        override fun getMaxButtonIndex() = 20
        override fun getAxisCount() = 4
        override fun isConnected() = true
        override fun supportsPlayerIndex() = false
        override fun getPlayerIndex() = Controller.PLAYER_IDX_UNSET
        override fun setPlayerIndex(index: Int) = Unit
        override fun getMapping(): ControllerMapping? = null
        override fun getPowerLevel() = ControllerPowerLevel.POWER_UNKNOWN
        override fun addListener(listener: ControllerListener) = Unit
        override fun removeListener(listener: ControllerListener) = Unit
    }

    private val phone = Phone()
    private val pad = Pad()
    private val silent = Pad(rumbles = false)
    private val tracker = InputSourceTracker()
    private val haptics = GdxHaptics(phone, { listOf(pad, silent) }, tracker)

    @Test
    fun `a touch buzzes the phone and leaves the pad alone`() {
        tracker.saw(InputSource.Touch)

        haptics.perform(Haptic.LightTap)
        haptics.perform(Haptic.MediumTap)
        haptics.perform(Haptic.Failure)

        assertEquals(
            listOf(Input.VibrationType.LIGHT, Input.VibrationType.MEDIUM, Input.VibrationType.HEAVY),
            phone.buzzes,
        )
        assertTrue(pad.played.isEmpty())
    }

    @Test
    fun `a pad rumbles for as long and as hard as the feedback asks`() {
        tracker.saw(InputSource.Gamepad)

        haptics.perform(Haptic.Tick)
        haptics.perform(Haptic.HeavyTap)

        assertEquals(
            listOf(
                Haptic.Tick.durationMillis to Haptic.Tick.strength,
                Haptic.HeavyTap.durationMillis to Haptic.HeavyTap.strength,
            ),
            pad.played,
        )
        assertTrue(silent.played.isEmpty(), "a pad that says it has no motor was asked to rumble")
        assertTrue(phone.buzzes.isEmpty())
    }

    @Test
    fun `a mouse or a keyboard moves nothing`() {
        tracker.saw(InputSource.Mouse)
        haptics.perform(Haptic.LightTap)
        tracker.saw(InputSource.Keyboard)
        haptics.perform(Haptic.LightTap)

        assertTrue(phone.buzzes.isEmpty())
        assertTrue(pad.played.isEmpty(), "a pad on the desk hummed at a mouse click")
    }

    @Test
    fun `with no idea what the player holds both are asked`() {
        val unsure = GdxHaptics(phone, { listOf(pad) })

        unsure.perform(Haptic.Success)

        assertEquals(listOf(Input.VibrationType.MEDIUM), phone.buzzes)
        assertEquals(listOf(Haptic.Success.durationMillis to Haptic.Success.strength), pad.played)
    }

    @Test
    fun `a phone refusing the permission does not crash the button`() {
        val locked = object : MockInput() {
            override fun vibrate(vibrationType: Input.VibrationType) = throw SecurityException("no VIBRATE")
        }

        GdxHaptics(locked, { listOf(pad) }).perform(Haptic.LightTap)

        assertEquals(1, pad.played.size, "the pad still rumbles when the phone would not")
    }

    @Test
    fun `a game that has not started yet can still ask`() {
        // No `Gdx.input`, no `Gdx.app`, no controllers backend: a button focused and pressed on the
        // first frame must be a no-op rather than a crash on startup.
        withApp(null) { GdxHaptics(phone).perform(Haptic.HeavyTap) }
        assertEquals(listOf(Input.VibrationType.HEAVY), phone.buzzes)
    }

    @Test
    fun `a running game whose pad library cannot start still gets its buzz`() {
        // An application of no known kind, so gdx-controllers has no backend to start and throws
        // when asked for the list. A game shipped without the controllers backend for a platform
        // hits the same thing on its first tap.
        val app = Proxy.newProxyInstance(Application::class.java.classLoader, arrayOf(Application::class.java)) { _, _, _ ->
            null
        } as Application

        withApp(app) {
            val haptics = GdxHaptics(phone)
            haptics.perform(Haptic.LightTap)
            haptics.perform(Haptic.LightTap)
        }

        assertEquals(listOf(Input.VibrationType.LIGHT, Input.VibrationType.LIGHT), phone.buzzes)
    }

    /** Runs [block] with `Gdx.app` set to [app], and puts back whatever another test left there. */
    private fun withApp(app: Application?, block: () -> Unit) {
        val before = Gdx.app
        Gdx.app = app
        try {
            block()
        } finally {
            Gdx.app = before
        }
    }

    @Test
    fun `a button pressed with the pad rumbles it and a click with the mouse does not`() {
        val backend = GdxBackend(HeadlessFonts.registry(family = "default"), haptics = haptics)
        uiTest(Size(400f, 300f), backend) {
            Button("Play", onClick = {}, modifier = Modifier.testTag("play"), initialFocus = true)
        }.use { ui ->
            // The harness's own tracker, which is the one its events go past — the same wiring a
            // game does with the tracker its input goes through.
            haptics.source = ui.source

            ui.click("play")
            assertTrue(pad.played.isEmpty(), "a mouse click rumbled the pad")
            assertTrue(phone.buzzes.isEmpty())

            ui.pad(GamepadButton.South)
            assertEquals(listOf(Haptic.LightTap.durationMillis to Haptic.LightTap.strength), pad.played)
        }
        backend.dispose()
    }
}
