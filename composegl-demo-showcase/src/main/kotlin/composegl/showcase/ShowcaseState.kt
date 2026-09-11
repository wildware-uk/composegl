package composegl.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.animation.Clock
import composegl.ui.game.DamageNumbers
import composegl.ui.game.ParticleEmitter
import composegl.ui.game.ParticleStyle
import composegl.ui.graphics.Colour

/** One thing on show, and whether it is currently on. */
enum class Exhibit(val title: String, val blurb: String) {
    Hud("Combat HUD", "Reticle, radar, bars, cooldowns"),
    Tracking("World tracking", "Tags that follow drones"),
    Damage("Floating numbers", "Damage text at world positions"),
    Holo("In-world panel", "The toolkit on a quad in the scene"),
    Particles("GL particles", "Embers drawn by the game"),
    Shaders("Shaders", "Blur, outline, grade, dissolve"),
    Sparks("Hit sparks", "A seeded burst off every hit"),
}

/** What one of the scene's drones looks like to the interface. */
class TargetReadout(val callsign: String) {
    var shield by mutableFloatStateOf(1f)
    var integrity by mutableFloatStateOf(1f)
    var screenX by mutableFloatStateOf(0f)
    var screenY by mutableFloatStateOf(0f)
    var distance by mutableFloatStateOf(0f)
    var onScreen by mutableStateOf(true)

    /** Where it is on the radar, in the radar's own units. */
    var mapX by mutableFloatStateOf(0f)
    var mapY by mutableFloatStateOf(0f)
}

/**
 * Everything the interface reads, in Compose state.
 *
 * The app writes to this from the render thread once a frame; the interface reads it. Keeping it in
 * one place makes it obvious how little wiring a game needs: the scene knows nothing about Compose,
 * and the interface knows nothing about the scene.
 */
class ShowcaseState {

    val enabled = mutableStateListOf(*Exhibit.entries.toTypedArray())

    fun isOn(exhibit: Exhibit) = exhibit in enabled

    fun toggle(exhibit: Exhibit) {
        if (exhibit in enabled) enabled.remove(exhibit) else enabled.add(exhibit)
    }

    val targets = mutableStateListOf<TargetReadout>()

    /** Which drone the reticle is on, or -1. Drives the target panel and the reticle's colour. */
    var locked by mutableIntStateOf(0)

    /** Player readouts, so the frame has something of its own to show. */
    var hull by mutableFloatStateOf(0.82f)
    var heat by mutableFloatStateOf(0.24f)
    var ammo by mutableIntStateOf(148)

    /** How far the camera has turned, for the radar's compass. */
    var heading by mutableFloatStateOf(0f)

    /**
     * How far through the dissolve the shader shelf is, 0 to 1 and back.
     *
     * Driven by the game's own loop rather than by an animation in the composition, because that is
     * what a real one would be: an effect's numbers usually come from the state of the world.
     */
    var dissolve by mutableFloatStateOf(0f)

    /** What the in-world panel is showing, changed by pointing at it and pulling the trigger. */
    var holoPage by mutableIntStateOf(0)

    /** How many times the panel in the scene has been redrawn, which is the number worth watching. */
    var holoDraws by mutableStateOf(0L)

    /**
     * The numbers that fly off a drone when it is hit.
     *
     * Made here rather than in the composition because the game is what hits things: the app calls
     * `damage.show(...)` from its own loop, and the interface only draws what is in the pool.
     */
    val damage = DamageNumbers(capacity = 96, clock = Clock.World)

    /**
     * The sparks that come off a hit.
     *
     * Seeded, so the same fight looks the same twice — which matters more than it sounds: it is
     * what makes a burst of particles something a screenshot test can check.
     */
    val sparks = ParticleEmitter(capacity = 240, clock = Clock.World, seed = 11L)
}

/** What a hit throws off: a short, fast, cooling burst. */
val HitSparks = ParticleStyle(
    life = 0.55f,
    lifeSpread = 0.4f,
    speed = 220f,
    speedSpread = 0.6f,
    // Every direction, because a hit does not care which way the shot came from.
    spread = 180f,
    gravity = 420f,
    drag = 1.5f,
    size = 6f,
    endSize = 0.2f,
    colour = Colour.rgb(0xFFD48A),
    endColour = Colour.argb(0x00FF5A2A),
    corner = 2.5f,
)
