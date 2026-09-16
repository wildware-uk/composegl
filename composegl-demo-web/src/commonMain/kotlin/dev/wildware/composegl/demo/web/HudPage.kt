package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.CompassBar
import dev.wildware.composegl.game.DamageDirectionLayer
import dev.wildware.composegl.game.DialogueBox
import dev.wildware.composegl.game.DialogueChoice
import dev.wildware.composegl.game.DialogueLine
import dev.wildware.composegl.game.HitKind
import dev.wildware.composegl.game.HitMarker
import dev.wildware.composegl.game.OffScreen
import dev.wildware.composegl.game.RadialConfirm
import dev.wildware.composegl.game.RadialMenu
import dev.wildware.composegl.game.SubtitleSettings
import dev.wildware.composegl.game.SubtitleSize
import dev.wildware.composegl.game.Subtitles
import dev.wildware.composegl.game.WorldMarkerLayer
import dev.wildware.composegl.game.WorldProjection
import dev.wildware.composegl.game.rememberDamageDirections
import dev.wildware.composegl.game.rememberDialogueLog
import dev.wildware.composegl.game.rememberHitMarkerState
import dev.wildware.composegl.game.rememberSubtitleQueue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The composegl-game widgets that sit over a game's picture: where things are, being shot at, the
 * weapon wheel, and what people are saying.
 */
@Composable
fun HudPage() {
    Page(Section.Hud, "What goes over the game itself. There is no 3D scene on this page, so the world here is a strip of bearings round the player that you turn by dragging.") {
        BearingsCard()
        HitsCard()
        WheelCard()
        SubtitlesCard()
        DialogueCard()
    }
}

/** Things out in the world, by bearing and distance. Shared by the compass and the markers. */
private class Landmark(val name: String, val bearing: Float, val distance: Float)

private val Landmarks = listOf(
    Landmark("Beacon", 20f, 120f),
    Landmark("Wreck", 95f, 340f),
    Landmark("Outpost", 200f, 60f),
    Landmark("Relay", 300f, 210f),
)

/** A bearing folded into -180 to 180, so "just behind on the left" is a small negative number. */
private fun around(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

@Composable
private fun BearingsCard() = Card("Compass and world markers", "Drag across the view, or use the slider, to turn. Markers hold at the edge with an arrow when what they mark is off to the side.") {
    val state = LocalShowcase.current
    var heading by remember { mutableFloatStateOf(0f) }
    CompassBar(
        heading = heading,
        fieldOfView = 150f,
        modifier = Modifier.fillMaxWidth().testTag("compass"),
        readout = { "${it.roundToInt()}°" },
        distanceText = { "${it.roundToInt()}m" },
        fadeRange = 400f,
        // Nothing moves on its own here: the strip redraws when the heading changes, not every frame.
        live = false,
    ) {
        Landmarks.forEach { pin(bearing = it.bearing, distance = it.distance, fadeWithDistance = true) }
    }

    // The game's camera, as the layer sees it: a bearing becomes a place across the view, and
    // anything more than a right angle away is behind, which is a negative depth.
    val fieldOfView = 90f
    val camera = remember(heading) {
        WorldProjection { point, view, onto ->
            val off = around(point.x - heading)
            val behind = abs(off) > 90f
            onto.set(view.width / 2f + off / fieldOfView * view.width, view.height * 0.6f, if (behind) -point.z else point.z)
            true
        }
    }
    val lastX = remember { FloatArray(1) }
    val drag = remember {
        PointerHandler { event ->
            when (event) {
                is PointerEvent.Press -> {
                    lastX[0] = event.position.x
                    true
                }
                is PointerEvent.Move -> {
                    if (event.pressed.isEmpty()) return@PointerHandler false
                    heading = (heading - (event.position.x - lastX[0]) * 0.3f + 360f) % 360f
                    lastX[0] = event.position.x
                    true
                }
                is PointerEvent.Release -> true
                else -> false
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(150f).background(Ink, corner = 8f).clip().onPointer(drag).testTag("world")) {
        Box(Modifier.align(Alignment.BottomCentre).fillMaxWidth().height(40f).background(Steel)) {}
        WorldMarkerLayer(Modifier.fillMaxSize(), projection = camera) {
            Landmarks.forEach { landmark ->
                marker(
                    key = landmark.name,
                    x = landmark.bearing,
                    y = 0f,
                    z = landmark.distance,
                    offScreen = OffScreen.ClampToEdge(arrow = true, inset = 6f),
                    fadeDistance = 150f..600f,
                    anchor = Alignment.BottomCentre,
                ) {
                    Box(Modifier.styled("tag")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6f), verticalAlignment = VerticalAlignment.Centre) {
                            Box(Modifier.size(8f).background(state.tint, corner = 4f)) {}
                            Text("${landmark.name} ${landmark.distance.roundToInt()}m", style = "label.dim")
                        }
                    }
                }
            }
        }
    }
    Labelled("Heading") {
        Slider(heading, { heading = it }, Modifier.testTag("heading"), range = 0f..359f, length = (LocalCardWidth.current - 40f).coerceAtLeast(120f))
    }
}

@Composable
private fun HitsCard() = Card("Hit markers and damage direction", "Tap or click round the crosshair to be shot from that side; the buttons land hits of each kind.") {
    val marker = rememberHitMarkerState(Clock.Ui)
    val directions = rememberDamageDirections(clock = Clock.Ui)
    var landed by remember { mutableIntStateOf(0) }
    var taken by remember { mutableIntStateOf(0) }
    val box = remember { FloatArray(4) }
    val placed = remember {
        PlacedHandler { node ->
            val at = node.boundsInRoot
            box[0] = at.left
            box[1] = at.top
            box[2] = at.width
            box[3] = at.height
        }
    }
    val shot = remember {
        PointerHandler { event ->
            if (event !is PointerEvent.Press) return@PointerHandler false
            val dx = event.position.x - box[0] - box[2] / 2f
            val dy = event.position.y - box[1] - box[3] / 2f
            // Nought is straight ahead, which on a screen is up; clockwise from there.
            directions.hit((atan2(dx, -dy) * 180f / PI.toFloat()), 1f)
            taken++
            true
        }
    }
    Box(Modifier.fillMaxWidth().height(180f).background(Ink, corner = 8f).onPlaced(placed).onPointer(shot).testTag("crosshair")) {
        Box(Modifier.align(Alignment.Centre).size(4f).background(Paper, corner = 2f)) {}
        HitMarker(marker, Modifier.fillMaxSize(), onHit = { landed++ })
        DamageDirectionLayer(directions, Modifier.fillMaxSize(), thickness = 12f)
    }
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button("Hit", { marker.hit(HitKind.Normal) }, Modifier.testTag("mark-hit"), style = "button.quiet")
        Button("Critical", { marker.hit(HitKind.Critical) }, style = "button.quiet")
        Button("Kill", { marker.hit(HitKind.Kill) }, style = "button.danger")
        Button("Shot from behind", { directions.hit(180f); taken++ }, style = "button.quiet")
    }
    Text("Hits landed: $landed · shots taken: $taken", Modifier.testTag("hits"), style = "label.dim")
}

private val Rounds = listOf("AP", "HE")

@Composable
private fun WheelCard() = Card("Weapon wheel", "Open it, then point at a slice and click or tap. The rifle has a second ring for its rounds. A pad's stick aims it too.") {
    val state = LocalShowcase.current
    var open by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(Rounds.first()) }
    Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
        Button(if (open) "Put it away" else "Open the wheel", { open = !open }, Modifier.testTag("open-wheel"), style = "button.primary")
        Text("${state.weapon}${if (state.weapon == "RIFLE") " · $round" else ""}", Modifier.testTag("equipped"))
    }
    Box(Modifier.fillMaxWidth().height(if (open) 300f else 0f).testTag("wheel-area")) {
        RadialMenu(
            open = open,
            items = ShowcaseState.Weapons,
            modifier = Modifier.fillMaxSize(),
            selected = state.weapon,
            onSelect = { item ->
                if (item in Rounds) {
                    round = item
                    state.weapon = "RIFLE"
                } else {
                    state.weapon = item
                }
            },
            onCancel = { open = false },
            onOpenChange = { open = it },
            children = { if (it == "RIFLE") Rounds else emptyList() },
            confirm = RadialConfirm.Press,
            radius = 96f,
            hubRadius = 34f,
            ringWidth = 40f,
            clock = Clock.Ui,
            centre = { Text(it ?: "PICK", style = "wheel.label") },
        ) { item, highlighted ->
            Text(item, style = if (highlighted) "wheel.label" else "label")
        }
    }
}

private val Scene = listOf(
    "Edda" to "Hold the door. Something is coming up the stairs.",
    null to "[heavy footsteps]",
    "Brann" to "It's only me. And I brought the map.",
    null to "[a door creaks shut]",
)

private val Cast = mapOf(
    "Edda" to Colour.rgb(0x4CC2FF),
    "Brann" to Colour.rgb(0xF2C94C),
)

@Composable
private fun SubtitlesCard() = Card("Subtitles", "Play the scene, then change how it reads. How long each line stays up is worked out from its length.") {
    val queue = rememberSubtitleQueue(capacity = 2, clock = Clock.Ui)
    var size by remember { mutableStateOf(SubtitleSize.Medium) }
    var backdrop by remember { mutableFloatStateOf(0.8f) }
    var names by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxWidth().height(130f).background(Deep, corner = 8f).clip().testTag("subtitle-stage")) {
        Subtitles(
            queue,
            Modifier.align(Alignment.BottomCentre).fillMaxWidth(),
            SubtitleSettings(size = size, backgroundOpacity = backdrop, speakerNames = names, maxLines = 2, widthFraction = 0.9f),
            speakerColours = Cast,
        )
    }
    Button("Play the scene", {
        Scene.forEach { (who, line) -> if (who == null) queue.caption(line) else queue.show(line, speaker = who) }
    }, Modifier.testTag("play-scene"), style = "button.primary")
    Labelled("Size") { Stepper(SubtitleSize.entries, size, { size = it }) }
    Labelled("Background") { Slider(backdrop, { backdrop = it }, step = 0.05f, length = 180f) }
    Toggle(names, { names = it }, label = "Speaker names")
}

/** A beat of the scene: the line, and the answers the player can give to it. */
private class Beat(val line: DialogueLine, val answers: List<DialogueChoice> = emptyList())

private val Script = listOf(
    Beat(DialogueLine("You made it back. I was starting to think the well had swallowed you.", speaker = "Edda", portrait = "E")),
    Beat(DialogueLine("Did you find my mother's ring?", speaker = "Edda", portrait = "E"),
        listOf(
            DialogueChoice("Here it is.", tag = "yes"),
            DialogueChoice("Not yet.", tag = "no"),
            DialogueChoice("Pay for the map (200 gold)", enabled = false, reason = "You have 40 gold"),
        ),
    ),
)

@Composable
private fun DialogueCard() = Card("Dialogue box", "Click or tap the box to move on, or press Enter. Answers are buttons: Tab, the arrows and a pad reach them.") {
    val log = rememberDialogueLog()
    var at by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<DialogueLine?>(null) }
    var auto by remember { mutableStateOf(false) }
    val beat = Script.getOrNull(at)
    DialogueBox(
        line = beat?.line ?: reply,
        modifier = Modifier.fillMaxWidth().testTag("dialogue"),
        choices = beat?.answers.orEmpty(),
        onChoose = { choice ->
            reply = if (choice.tag == "yes") {
                DialogueLine("Oh... thank you. Take this, and be careful on the north road.", speaker = "Edda", portrait = "E")
            } else {
                DialogueLine("Then keep looking. Please.", speaker = "Edda", portrait = "E")
            }
            at++
        },
        onAdvance = { if (at < Script.size) at++ },
        log = log,
        auto = auto,
        onAutoChange = { auto = it },
        // It is one card on a page, not the whole screen, so the page keeps its focus.
        trapFocus = false,
        clock = Clock.Ui,
        // The box's own arrow breathes for ever. With motion reduced it holds still instead.
        indicator = if (LocalShowcase.current.reduceMotion) {
            { Box(Modifier.size(10f).background(Accent, corner = 2f)) {} }
        } else {
            null
        },
        portrait = { line ->
            Box(Modifier.size(52f).background(Steel, corner = 8f), contentAlignment = Alignment.Centre) {
                Text(line.portrait?.toString() ?: "", style = "label.heading")
            }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Button("Start again", { at = 0; reply = null }, Modifier.testTag("dialogue-restart"), style = "button.quiet")
        Text("Lines said: ${log.entries.size}", style = "label.dim")
    }
}
