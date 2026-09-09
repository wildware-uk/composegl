package composegl.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One thing on show, and whether it is currently on. */
enum class Exhibit(val title: String, val blurb: String) {
    Hud("Combat HUD", "Reticle, radar, cooldowns and a target readout, all drawn with Compose's Canvas"),
    Tracking("World tracking", "Panels that follow objects in the 3D scene, scaling and fading with distance"),
    Damage("Floating numbers", "Damage text spawned at world positions and projected to the screen"),
    Holo("In-world hologram", "A Compose surface as a texture on a panel inside the scene, glowing additively"),
    Particles("GL particles", "Embers drawn by the game, behind the interface"),
}

/** A number that floated off something that got hit. */
class DamageNumber(
    val worldX: Float,
    val worldY: Float,
    val worldZ: Float,
    val amount: Int,
    val critical: Boolean,
) {
    var age: Float = 0f
    var screenX: Float = 0f
    var screenY: Float = 0f
    var visible: Boolean = false

    val lifetime: Float get() = if (critical) 1.6f else 1.2f
    val done: Boolean get() = age >= lifetime
}

/** What one of the scene's drones looks like to the interface. */
class TargetReadout(val callsign: String) {
    var shield by mutableFloatStateOf(1f)
    var integrity by mutableFloatStateOf(1f)
    var screenX by mutableFloatStateOf(0f)
    var screenY by mutableFloatStateOf(0f)
    var distance by mutableFloatStateOf(0f)
    var onScreen by mutableStateOf(true)
    /** Bearing in radians for the radar, 0 = straight ahead. */
    var bearing by mutableFloatStateOf(0f)
}

/** One of the four ability buttons along the bottom. */
class Ability(val key: String, val label: String, val cooldownSeconds: Float) {
    var remaining by mutableFloatStateOf(0f)
    val ready: Boolean get() = remaining <= 0f
    val progress: Float get() = if (cooldownSeconds <= 0f) 1f else 1f - (remaining / cooldownSeconds)

    fun trigger() {
        if (ready) remaining = cooldownSeconds
    }

    fun tick(delta: Float) {
        if (remaining > 0f) remaining = (remaining - delta).coerceAtLeast(0f)
    }
}

/**
 * Everything the interface reads, in Compose state.
 *
 * The app writes to this from the render thread once per frame; the exhibits read it. Keeping it
 * in one place makes it obvious how little wiring a game needs: the scene knows nothing about
 * Compose, and Compose knows nothing about the scene.
 */
class ShowcaseState {

    val enabled = mutableStateListOf(*Exhibit.entries.toTypedArray())

    fun isOn(exhibit: Exhibit) = exhibit in enabled

    fun toggle(exhibit: Exhibit) {
        if (exhibit in enabled) enabled.remove(exhibit) else enabled.add(exhibit)
    }

    val targets = mutableStateListOf<TargetReadout>()

    val abilities = listOf(
        Ability("1", "PULSE", 2.5f),
        Ability("2", "CLOAK", 6f),
        Ability("3", "REPAIR", 4.5f),
        Ability("4", "OVERDRIVE", 9f),
    )

    val damageNumbers = mutableStateListOf<DamageNumber>()

    /** Drives the sweep, the reticle spin and anything else that just needs a clock. */
    var time by mutableFloatStateOf(0f)

    /** Player readouts, so the frame has something of its own to show. */
    var hull by mutableFloatStateOf(0.82f)
    var heat by mutableFloatStateOf(0.24f)
    var ammo by mutableStateOf(148)

    /** Compose render count, sampled for the chrome panel. */
    var composeRenders by mutableStateOf(0L)
    var gameFrames by mutableStateOf(0L)
    var lastRenderMicros by mutableStateOf(0L)
}
