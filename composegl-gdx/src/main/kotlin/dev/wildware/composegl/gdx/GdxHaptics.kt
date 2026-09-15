package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.controllers.Controller
import com.badlogic.gdx.controllers.Controllers
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.Haptics
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InputSourceTracker

/**
 * A phone's vibration and a pad's rumble, as LibGDX and gdx-controllers offer them.
 *
 * Two motors, and which one a player should feel depends on what is in their hand. With [source]
 * set, that is decided by whatever they last used: a touch buzzes the phone, a pad rumbles the pad,
 * and a mouse or a keyboard — which have no motor — moves nothing, so a desktop player clicking
 * with a pad on the desk does not feel it hum. With no [source], both are asked.
 *
 * The phone half goes through `Input.vibrate(VibrationType)`, which LibGDX maps to the platform's
 * light, medium and heavy effects and which does nothing on a desktop. On Android that call needs
 * the `VIBRATE` permission; `AndroidHaptics` in `composegl-android` needs none and speaks the
 * phone's own vocabulary, so it is the better choice there.
 *
 * Every connected pad that says it can vibrate is rumbled, for the [Haptic]'s own length and
 * strength. In a menu there is one player, and in a game of four the game knows which pad to
 * rumble better than a button does.
 *
 * Not verified against real hardware: there is no phone or rumbling pad on the machine this was
 * written on, so which effect each name gets was chosen from the APIs' descriptions.
 *
 * @param input the engine's, or null to take `Gdx.input` when it is asked, since a game builds its
 *   interface before `Gdx.input` exists.
 * @param pads the pads to rumble. Null asks gdx-controllers, and asks nothing before LibGDX has
 *   started or once gdx-controllers has failed to.
 * @param source what the player last used. Hand it the same tracker the input goes through.
 */
class GdxHaptics(
    private val input: Input? = null,
    private val pads: (() -> Iterable<Controller>)? = null,
    var source: InputSourceTracker? = null,
) : Haptics {

    private val engine: Input? get() = input ?: Gdx.input

    /** Set once gdx-controllers has failed to start, so a button does not retry it on every tap. */
    private var noPads = false

    override fun perform(haptic: Haptic) {
        when (source?.current) {
            InputSource.Touch -> vibrate(haptic)
            InputSource.Gamepad -> rumble(haptic)
            InputSource.Mouse, InputSource.Keyboard -> Unit
            null -> {
                vibrate(haptic)
                rumble(haptic)
            }
        }
    }

    private fun vibrate(haptic: Haptic) {
        val type = when (haptic) {
            Haptic.Tick, Haptic.LightTap -> Input.VibrationType.LIGHT
            Haptic.MediumTap, Haptic.Success, Haptic.Warning -> Input.VibrationType.MEDIUM
            Haptic.HeavyTap, Haptic.Failure -> Input.VibrationType.HEAVY
        }
        try {
            engine?.vibrate(type)
        } catch (_: SecurityException) {
            // An Android game without the VIBRATE permission. A button must not crash for want
            // of a buzz, and the fix — the permission, or AndroidHaptics — is the game's.
        }
    }

    private fun rumble(haptic: Haptic) {
        val connected = pads?.invoke() ?: connectedPads()
        connected.forEach { pad ->
            if (pad.canVibrate()) pad.startVibration(haptic.durationMillis, haptic.strength)
        }
    }

    /**
     * What gdx-controllers can see, or nothing before LibGDX is running to ask.
     *
     * Also nothing when gdx-controllers cannot start at all — a platform the game shipped without
     * its controllers backend for, or a desktop missing the native library. Asking for the list is
     * what starts it, and a start that fails throws; a tap on a button is no place for that.
     */
    private fun connectedPads(): Iterable<Controller> {
        if (noPads || Gdx.app == null) return emptyList()
        return try {
            Controllers.getControllers()
        } catch (_: RuntimeException) {
            noPads = true
            emptyList()
        }
    }
}
