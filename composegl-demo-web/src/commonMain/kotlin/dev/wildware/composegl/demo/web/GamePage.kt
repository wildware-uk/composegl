package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.game.BarThreshold
import dev.wildware.composegl.game.DamageNumberLayer
import dev.wildware.composegl.game.DamageNumbers
import dev.wildware.composegl.game.Hotbar
import dev.wildware.composegl.game.HotbarSlot
import dev.wildware.composegl.game.MinimapFrame
import dev.wildware.composegl.game.MinimapMarker
import dev.wildware.composegl.game.RadialCooldown
import dev.wildware.composegl.game.Reticle
import dev.wildware.composegl.game.WorldAnchor
import dev.wildware.composegl.game.rememberCooldown
import dev.wildware.composegl.game.rememberReticleState
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.input.Action
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
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.PromptGlyph
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.rememberTypewriter

@Composable
fun GamePage() {
    Page(Section.Game, "The tier a game UI toolkit needs and an app toolkit does not have.") {
        HealthBars()
        Cooldowns()
        HotbarCard()
        Range()
        Dialogue()
        Prompts()
        Radar()
    }
}

@Composable
private fun HealthBars() = Card("Bars with a damage trail", "What was just lost lingers, then drains away. Below a quarter the bar turns critical and pulses.") {
    var health by remember { mutableFloatStateOf(0.8f) }
    var shield by remember { mutableFloatStateOf(1f) }
    var stamina by remember { mutableFloatStateOf(0.65f) }
    Labelled("HEALTH") {
        Bar(health, Modifier.fillMaxWidth().testTag("health"), thresholds = listOf(BarThreshold(0.25f, "bar.fill.critical")), pulseBelow = 0.25f, clock = Clock.Ui)
    }
    Labelled("SHIELD, six segments") { Bar(shield, Modifier.fillMaxWidth(), style = "bar.shield", segments = 6, clock = Clock.Ui) }
    Labelled("STAMINA") { Bar(stamina, Modifier.fillMaxWidth(), style = "bar.stamina", clock = Clock.Ui) }
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button("Hit", {
            if (shield > 0f) shield = (shield - 0.34f).coerceAtLeast(0f) else health = (health - 0.18f).coerceAtLeast(0f)
            stamina = (stamina - 0.1f).coerceAtLeast(0f)
        }, Modifier.testTag("hit"), style = "button.danger")
        Button("Heal", { health = 1f; shield = 1f; stamina = 1f }, style = "button.quiet")
    }
}

@Composable
private fun Cooldowns() = Card("Radial cooldowns", "Click an ability. The sweep and the seconds run on the game's clock.") {
    Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
        listOf(1_500, 4_000, 9_000).forEachIndexed { index, millis ->
            val cooldown = rememberCooldown(millis, Clock.Ui)
            RadialCooldown(
                cooldown,
                Modifier.size(64f).background(Steel, corner = 8f).border(Accent, width = 2f, corner = 8f)
                    .focusable().clickable { cooldown.trigger() }.testTag("ability-$index"),
            ) { Image("icon/crest", Modifier.size(36f)) }
        }
    }
}

@Composable
private fun HotbarCard() = Card("Hotbar", "Pick a slot. Charges count down as you use them.") {
    var selected by remember { mutableIntStateOf(0) }
    var potions by remember { mutableIntStateOf(3) }
    Hotbar(
        slots = listOf(
            HotbarSlot(icon = "icon/crest", charges = potions, enabled = potions > 0),
            HotbarSlot(icon = "icon/crest"),
            HotbarSlot(label = "MAP"),
            HotbarSlot(),
            HotbarSlot(icon = "icon/crest", charges = 1),
        ),
        selected = selected,
        onSelect = { selected = it },
        onUse = { if (it == 0 && potions > 0) potions-- },
        slotSize = 52f,
        hotkeys = false,
    )
    Button("Refill", { potions = 3 }, style = "button.quiet")
}

@Composable
private fun Range() = Card("Damage numbers and a reticle", "Click or tap the range. Numbers float up where you hit; one in five is a critical.") {
    val numbers = remember { DamageNumbers(capacity = 64, clock = Clock.Ui) }
    val reticle = rememberReticleState(Clock.Ui)
    var area by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var shots by remember { mutableIntStateOf(0) }
    val placed = remember { PlacedHandler { node -> area = node.boundsInRoot } }
    val fire = remember {
        PointerHandler { event ->
            if (event !is PointerEvent.Press) return@PointerHandler false
            shots++
            val critical = shots % 5 == 0
            val damage = if (critical) 120 + shots % 40 else 20 + (shots * 37) % 30
            numbers.show("$damage", WorldAnchor.at(event.position.x - area.left, event.position.y - area.top), critical)
            reticle.hit(kill = critical)
            true
        }
    }
    Box(Modifier.fillMaxWidth().height(170f).background(Ink, corner = 8f).onPlaced(placed).onPointer(fire).testTag("range")) {
        Box(Modifier.align(Alignment.Centre).size(90f, 110f).background(Steel, corner = 8f)) {}
        Reticle(reticle, Modifier.align(Alignment.Centre), gap = 8f, arm = 14f, thickness = 3f, dot = 2f)
        DamageNumberLayer(numbers, Modifier.fillMaxSize())
    }
    Text("Shots fired: $shots", style = "label.dim")
}

private val Lines = listOf(
    "Edda: You made it back. I was starting to think the well had swallowed you.",
    "Edda: The ring? Hold it up to the light... yes. That's my mother's.",
    "Edda: Take this, and be careful on the north road after dark.",
)

@Composable
private fun Dialogue() = Card("Typewriter dialogue", "Text types itself out, resting at punctuation.") {
    var line by remember { mutableIntStateOf(0) }
    val typing = rememberTypewriter(Lines[line])
    Box(Modifier.fillMaxWidth().height(70f)) { Typewriter(typing, Modifier.fillMaxWidth(), charactersPerSecond = 40f) }
    Button("Next line", { line = (line + 1) % Lines.size }, style = "button.quiet")
}

@Composable
private fun Prompts() = Card("Prompt glyphs", "The glyph follows your device: press a pad button and they change.") {
    Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
        Text("Press")
        PromptGlyph(Action.Interact)
        Text("to open the door")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8f), verticalAlignment = VerticalAlignment.Centre) {
        PromptGlyph(Action.Confirm)
        Text("Accept")
        PromptGlyph(Action.Cancel)
        Text("Back")
    }
}

@Composable
private fun Radar() = Card("Minimap and reticle", "Turn the heading; widen the spread; mark a hostile.") {
    var heading by remember { mutableFloatStateOf(0.2f) }
    var spread by remember { mutableFloatStateOf(0.2f) }
    var hostile by remember { mutableStateOf(false) }
    val reticle = rememberReticleState(Clock.Ui)
    reticle.spread = spread
    reticle.hostile = hostile
    val markers = remember { listOf(MinimapMarker(20f, -30f), MinimapMarker(-40f, 10f), MinimapMarker(35f, 45f, style = "minimap.objective")) }
    Row(horizontalArrangement = Arrangement.spacedBy(16f), verticalAlignment = VerticalAlignment.Centre) {
        MinimapFrame(Modifier.size(140f), heading = heading, rotate = true, markers = markers, range = 100f, live = !LocalShowcase.current.reduceMotion)
        Box(Modifier.size(110f).background(Ink, corner = 8f)) {
            Reticle(reticle, Modifier.align(Alignment.Centre), gap = 8f, arm = 16f, thickness = 3f, spreadDistance = 22f, dot = 2f)
        }
    }
    Labelled("Heading") { Slider(heading, { heading = it }, range = 0f..6.28f, length = 200f) }
    Labelled("Spread") { Slider(spread, { spread = it }, length = 200f) }
    Toggle(hostile, { hostile = it }, label = "Over something hostile")
}
