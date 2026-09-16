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
import dev.wildware.composegl.game.InventoryCell
import dev.wildware.composegl.game.InventoryItem
import dev.wildware.composegl.game.InventoryState
import dev.wildware.composegl.game.ParticleEmitter
import dev.wildware.composegl.game.ParticleStyle
import dev.wildware.composegl.game.SubtitleSize
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.graphics.Colour

/**
 * Which published module a thing on show comes out of.
 *
 * The showcase has always been one screen with everything happening at once, which answers "what
 * does this look like in a game" and answers "what is actually in composegl-game" not at all. So
 * each module also has a **section**: a page of its own widgets, driven rather than described. See
 * `ui/ModuleSection.kt`.
 */
enum class Module(val artifact: String, val tab: String, val blurb: String) {
    Ui("composegl-ui", "ui", "The toolkit itself: menus, tables, trees, the things you point at"),
    Debug("composegl-debug", "debug", "The tools that sit over a game while it runs"),
    Game("composegl-game", "game", "The widgets a game needs and never wants to write twice"),
}

/**
 * One thing on show, and whether it is currently on.
 *
 * [module] is which section it belongs to, and [overlay] says whether it is drawn over the scene
 * rather than in a panel of its own — an overlay keeps drawing while a section has the screen,
 * because the section is usually the thing driving it.
 */
enum class Exhibit(
    val title: String,
    val blurb: String,
    val module: Module,
    val overlay: Boolean = false,
) {
    Hud("Combat HUD", "Reticle, radar, bars, cooldowns", Module.Game),
    Tracking("World tracking", "Tags that follow drones", Module.Game, overlay = true),
    Damage("Floating numbers", "Damage text at world positions", Module.Game, overlay = true),
    Holo("In-world panel", "The toolkit on a quad in the scene", Module.Ui, overlay = true),
    Particles("GL particles", "Embers drawn by the game", Module.Game, overlay = true),
    Shaders("Shaders", "Blur, outline, grade, dissolve", Module.Ui),
    Sparks("Hit sparks", "A seeded burst off every hit", Module.Game, overlay = true),
    Contacts("Contacts table", "Sort, resize and pick a drone", Module.Ui),
    Tree("Scene tree", "Rows that open, by mouse or pad", Module.Ui),
    Starmap("Star map", "A plane to drag and zoom", Module.Ui),
    Skills("Skill tree", "Hold a node to buy it", Module.Game),
    Nodes("UI tree", "The live interface, and what keeps changing", Module.Debug),
    Telemetry("Live plots", "Heat and frame time as graphs", Module.Debug),
    Wheel("Weapon wheel", "Hold Q or LB and flick a stick", Module.Game, overlay = true),
    Comms("Subtitles", "Timed lines, captions and the player's settings", Module.Game, overlay = true),
    Dialogue("Comms channel", "A conversation with answers and a log", Module.Game),
    Chat("Squad chat", "Channels, clickable names and an input line", Module.Game),
    Cargo("Cargo grid", "Stacks, splits and long items", Module.Game),
    Salvage("Loot cards", "Hover a drop and hold Shift to compare", Module.Game),
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

    // --- which module's section is open ----------------------------------------------------------

    /**
     * The module whose section has the screen, or null for the ordinary combat HUD.
     *
     * The Modules menu sets it, Control and a number sets it, and the buttons along the top of the
     * section set it. Nothing else in the showcase reads it except [showsPanel].
     */
    var section by mutableStateOf<Module?>(null)

    /** Moves to the section [by] along, wrapping, starting at the first if none is open. */
    fun stepSection(by: Int) {
        val entries = Module.entries
        val at = section?.let { entries.indexOf(it) } ?: -1
        section = entries[((at + by) % entries.size + entries.size) % entries.size]
    }

    /**
     * Whether an exhibit should draw now.
     *
     * A section takes the left-hand side of the screen and most of its height, so the HUD's own
     * panels give it up while one is open. The overlays do not: a section's whole job is often to
     * drive one — press "take a hit" and the arc it draws is over the scene, not in the panel.
     */
    fun showsPanel(exhibit: Exhibit) = isOn(exhibit) && (section == null || exhibit.overlay)

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

    // --- what the player is meant to be doing -----------------------------------------------------
    //
    // Worked out from the fight rather than kept beside it, so the objective tracker is showing the
    // game's own numbers: land enough hits and the sector is clear, which is what puts the second
    // objective on the list.

    /** How many drones the drill wants seen off. */
    val droneQuota = 6

    /** How many are down, counted off the hit markers that have sounded. */
    val dronesDown: Int get() = (hitsMarked / HitsPerDrone).coerceAtMost(droneQuota)

    /** Whether the first objective is finished. */
    val sectorClear: Boolean get() = dronesDown >= droneQuota

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

    // --- what is in the hold --------------------------------------------------------------------
    //
    // Two grids and the rules between them. Held here rather than in the composition for the reason
    // everything else here is: switching the exhibit off and on again should not tidy the player's
    // bag for them.

    /** Names for the piles a split makes, so a saved game could still find them. */
    private var piles = 0

    /** The ship's hold: a long rifle, a stack of cells and a crate two squares square. */
    val hold = InventoryState(
        columns = 4,
        rows = 4,
        items = listOf(
            InventoryItem(id = "rifle", kind = "RFL", at = InventoryCell(0, 0), width = 2, height = 1),
            InventoryItem(id = "cells", kind = "CEL", at = InventoryCell(0, 1), count = 12, stackLimit = 20),
            InventoryItem(id = "crate", kind = "CRT", at = InventoryCell(2, 2), width = 2, height = 2),
        ),
        newId = { "pile${++piles}" },
    )

    /** The locker beside it, to drag things into. Neither grid knows the other one exists. */
    val locker = InventoryState(
        columns = 4,
        rows = 4,
        items = listOf(
            InventoryItem(id = "medkit", kind = "MED", at = InventoryCell(3, 0)),
            InventoryItem(id = "spare", kind = "CEL", at = InventoryCell(0, 3), count = 5, stackLimit = 20),
        ),
        newId = { "pile${++piles}" },
    )
}

/** How many hit markers one drone is worth, so the objective counter climbs at a readable pace. */
private const val HitsPerDrone = 5

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
