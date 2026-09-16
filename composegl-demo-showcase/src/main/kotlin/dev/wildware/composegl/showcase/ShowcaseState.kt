package dev.wildware.composegl.showcase

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.DamageDirections
import dev.wildware.composegl.game.DamageNumbers
import dev.wildware.composegl.game.DialogueLine
import dev.wildware.composegl.game.DialogueLog
import dev.wildware.composegl.game.HitMarkerState
import dev.wildware.composegl.game.ParticleEmitter
import dev.wildware.composegl.game.ParticleStyle
import dev.wildware.composegl.game.SubtitleSize
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.graphics.Colour

/** One thing on show, and whether it is currently on. */
enum class Exhibit(val title: String, val blurb: String) {
    Hud("Combat HUD", "Reticle, radar, bars, cooldowns"),
    Tracking("World tracking", "Tags that follow drones"),
    Damage("Floating numbers", "Damage text at world positions"),
    Holo("In-world panel", "The toolkit on a quad in the scene"),
    Particles("GL particles", "Embers drawn by the game"),
    Shaders("Shaders", "Blur, outline, grade, dissolve"),
    Sparks("Hit sparks", "A seeded burst off every hit"),
    Contacts("Contacts table", "Sort, resize and pick a drone"),
    Tree("Scene tree", "Rows that open, by mouse or pad"),
    Starmap("Star map", "A plane to drag and zoom"),
    Nodes("UI tree", "The live interface, and what keeps changing"),
    Telemetry("Live plots", "Heat and frame time as graphs"),
    Wheel("Weapon wheel", "Hold Q or LB and flick a stick"),
    Comms("Subtitles", "Timed lines, captions and the player's settings"),
    Dialogue("Comms channel", "A conversation with answers and a log"),
}

/** How often the fight fires, as a debug window offers it. */
enum class Pace(val seconds: Float) {
    Calm(1.2f),
    Brisk(0.45f),
    Frantic(0.12f),
}

/** What one of the scene's drones looks like to the interface. */
class TargetReadout(val callsign: String) {
    var shield by mutableFloatStateOf(1f)
    var integrity by mutableFloatStateOf(1f)
    var screenX by mutableFloatStateOf(0f)
    var screenY by mutableFloatStateOf(0f)
    var distance by mutableFloatStateOf(0f)
    var onScreen by mutableStateOf(true)

    /**
     * Where it is in the world, for the marker layer that pins a tag to it.
     *
     * Plain numbers rather than state on purpose: they are written by the game once a frame and
     * read by the layer once a frame, outside the composition. As state they would recompose three
     * tags sixty times a second to say what the layer works out for itself anyway.
     */
    var worldX = 0f
    var worldY = 0f
    var worldZ = 0f

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

    /** The colour the reticle is tinted, chosen with the colour picker in the hull panel. */
    var reticleTint by mutableStateOf(Colour.White)

    /** Whether the weapon wheel is up, which here means "the key or the bumper is held". */
    var wheelOpen by mutableStateOf(false)

    /** What the wheel last equipped. Shown under the hull readout. */
    var weapon by mutableStateOf("PULSE")

    /** How far the camera has turned, for the radar's compass. */
    var heading by mutableFloatStateOf(0f)

    // --- the conversation on the comms channel ----------------------------------------------------
    //
    // A conversation is the game's, never the widget's: this is where it is kept, and the dialogue
    // box is handed one line at a time.

    /** Which line of the exchange is being said. */
    var commsAt by mutableIntStateOf(0)

    /** What VEGA said back to the answer the player gave, made when they answered. */
    var commsReply by mutableStateOf<DialogueLine?>(null)

    /** Whether the conversation is running itself, and whether the player is holding skip. */
    var commsAuto by mutableStateOf(false)
    var commsSkipping by mutableStateOf(false)

    /** Whether the log is up over the channel. */
    var commsLogOpen by mutableStateOf(false)

    /** Everything said so far, which outlives the box: the log is readable with the box gone. */
    val commsLog = DialogueLog()

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
     * The player's own end of a fight: what is shooting back, and what the player just hit.
     *
     * Here for the same reason the numbers are: the game is what gets hit and what lands a shot,
     * so the game writes to these and the interface only draws them.
     */
    val incoming = DamageDirections(capacity = 6, clock = Clock.World)

    val hitMarker = HitMarkerState(Clock.World)

    /** How many hit markers have sounded. A game would play a tick here; this one counts them. */
    var hitsMarked by mutableIntStateOf(0)

    // --- what the tuning window changes ---------------------------------------------------------
    //
    // Ordinary properties the game's own loop reads. The debug window writes them through
    // `state::pace` and the rest; nothing here knows a window exists.

    /** Whether the tuning window is on show. The Debug menu's tick and its cross both set it. */
    var tuningOpen by mutableStateOf(true)

    /** How often something is shot at. */
    var pace by mutableStateOf(Pace.Brisk)

    /** Nothing is shot at while this is on, so a screenshot can be taken of a still fight. */
    var holdFire by mutableStateOf(false)

    /** Shoot at something on the next frame, whatever the pace says. */
    var fireNow by mutableStateOf(false)

    /** Every damage number multiplied by this, for seeing what four figures looks like. */
    var damageScale by mutableFloatStateOf(1f)

    /** How many sparks an ordinary hit throws off. */
    var sparkBurst by mutableIntStateOf(24)

    /** What colour those sparks start. */
    var sparkTint by mutableStateOf(Colour.rgb(0xFFD48A))

    // --- what a player sets in an accessibility menu ---------------------------------------------
    //
    // A real game saves these three with the rest of its settings. Here the tuning window is the
    // options screen, so that what changing them does is visible while the fight is running.

    /** How big the subtitles are, on its own rather than on top of the interface scale. */
    var subtitleSize by mutableStateOf(SubtitleSize.Medium)

    /** How solid the band behind the words is. */
    var subtitleBackground by mutableFloatStateOf(0.8f)

    /** Whether who is speaking is written above the line. */
    var subtitleSpeakers by mutableStateOf(true)

    /** Say something on the next frame, whatever the script was going to do. */
    var sayNow by mutableStateOf(false)

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
