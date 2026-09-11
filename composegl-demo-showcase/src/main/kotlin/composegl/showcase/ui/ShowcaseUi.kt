package composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import composegl.showcase.Exhibit
import composegl.showcase.ShowcaseState
import composegl.showcase.TargetReadout
import composegl.ui.animation.Easings
import composegl.ui.animation.Tween
import composegl.ui.animation.animateFloatAsState
import composegl.ui.debug.FrameBudget
import composegl.ui.debug.FrameBudgetOverlay
import composegl.ui.game.Bar
import composegl.ui.game.BarThreshold
import composegl.ui.game.Cooldown
import composegl.ui.game.DamageNumberLayer
import composegl.ui.game.Hotbar
import composegl.ui.game.HotbarSlot
import composegl.ui.game.HotbarState
import composegl.ui.game.MinimapFrame
import composegl.ui.game.MinimapMarker
import composegl.ui.game.Reticle
import composegl.ui.game.WorldProjection
import composegl.ui.game.rememberCooldown
import composegl.ui.game.rememberReticleState
import composegl.ui.layout.Alignment
import composegl.ui.layout.Arrangement
import composegl.ui.layout.Box
import composegl.ui.layout.Column
import composegl.ui.layout.Row
import composegl.ui.layout.VerticalAlignment
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.align
import composegl.ui.modifier.alpha
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.offset
import composegl.ui.modifier.onKeyEvent
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import composegl.ui.modifier.width
import composegl.ui.skin.ProvideSkin
import composegl.ui.skin.Skin
import composegl.ui.skin.styled
import composegl.ui.text.FontProvider
import composegl.ui.widget.LocalFonts
import composegl.ui.widget.Panel
import composegl.ui.widget.Text
import composegl.ui.widget.Toggle

/**
 * The showcase's interface: a combat HUD over a 3D scene.
 *
 * The point of this file is how little of it there is. In the previous version every one of these —
 * the reticle, the radar sweep, the cooldown rings, the target bars with their trails, the floating
 * damage numbers — was hand-written with a Canvas, 383 lines of it in `HudWidgets.kt`. Here they are
 * widgets the toolkit ships, and what is left is a game saying where things are.
 *
 * @param projection the game's camera, as one function: where on screen is this point in the world.
 * @param budget what the frame cost, shown in the corner. F3 puts it away.
 */
@Composable
fun ShowcaseUi(
    state: ShowcaseState,
    fonts: FontProvider,
    skin: Skin,
    projection: WorldProjection,
    budget: FrameBudget,
) {
    CompositionLocalProvider(LocalFonts provides fonts) {
        ProvideSkin(skin) {
            // The number keys have to work wherever the player is, so they are answered above
            // every widget rather than by whatever happens to have focus.
            val hotbar = remember { HotbarState() }

            Box(Modifier.fillMaxSize().onKeyEvent(hotbar::onKey)) {
                if (state.isOn(Exhibit.Hud)) {
                    CombatHud(state)
                    Radar(state)
                    Abilities(state, hotbar)
                }

                if (state.isOn(Exhibit.Tracking)) TargetTags(state)

                // The numbers live in the pool the game writes to; this only draws them, through
                // the game's own camera.
                if (state.isOn(Exhibit.Damage)) {
                    DamageNumberLayer(state.damage, Modifier.fillMaxSize(), projection)
                }

                ExhibitPanel(state)

                // Behind the game's own switch, which is the only place that decision belongs.
                if (budget.isOn) {
                    FrameBudgetOverlay(
                        budget,
                        Modifier.align(Alignment.TopStart).padding(left = 28f, top = 220f),
                    )
                }
            }
        }
    }
}

/** The reticle, the player's own bars, and the readout for whatever is locked. */
@Composable
private fun CombatHud(state: ShowcaseState) {
    val reticle = rememberReticleState()
    val target = state.targets.getOrNull(state.locked)
    reticle.hostile = target != null
    // Wider while the weapon is hot, which is the whole reason a reticle has a spread.
    reticle.spread = state.heat

    Reticle(reticle, Modifier.align(Alignment.Centre))

    Panel(Modifier.align(Alignment.BottomStart).padding(left = 28f, bottom = 28f).width(280f)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Text("HULL", style = "label.dim")
            Bar(
                state.hull,
                Modifier.fillMaxWidth(),
                thresholds = listOf(BarThreshold(0.35f, "low"), BarThreshold(0.15f, "critical")),
                segments = 8,
                pulseBelow = 0.15f,
            )
            Text("HEAT", style = "label.dim")
            Bar(state.heat, Modifier.fillMaxWidth(), style = "bar.heat", trail = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AMMO", style = "label.dim")
                Text("${state.ammo}", style = "label")
            }
        }
    }

    if (target != null) TargetPanel(target)
}

/** What is locked: its shields, its hull, and how far away it is. */
@Composable
private fun TargetPanel(target: TargetReadout) {
    Panel(Modifier.align(Alignment.TopEnd).padding(right = 28f, top = 28f).width(260f)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(target.callsign, style = "label.title")
                Text("${target.distance.toInt()}m", style = "label.dim")
            }
            Text("SHIELD", style = "label.dim")
            Bar(target.shield, Modifier.fillMaxWidth(), style = "bar.shield")
            Text("INTEGRITY", style = "label.dim")
            Bar(
                target.integrity,
                Modifier.fillMaxWidth(),
                thresholds = listOf(BarThreshold(0.35f, "low"), BarThreshold(0.15f, "critical")),
            )
        }
    }
}

/** The radar, which is the minimap frame with the game's own blips in it. */
@Composable
private fun Radar(state: ShowcaseState) {
    val markers = remember { mutableListOf<MinimapMarker>() }
    // One marker per drone, kept rather than rebuilt: the frame reads them every frame.
    while (markers.size < state.targets.size) markers.add(MinimapMarker())
    state.targets.forEachIndexed { index, target ->
        markers[index].x = target.mapX
        markers[index].y = target.mapY
    }

    MinimapFrame(
        Modifier.align(Alignment.TopStart).padding(left = 28f, top = 28f).size(180f, 180f),
        heading = state.heading,
        rotate = true,
        markers = markers,
        range = 12f,
    )
}

/** Four abilities on cooldown rings, pressed with the number keys or the pointer. */
@Composable
private fun Abilities(state: ShowcaseState, hotbar: HotbarState) {
    val pulse = rememberCooldown(2_500)
    val cloak = rememberCooldown(6_000)
    val repair = rememberCooldown(4_500)
    val overdrive = rememberCooldown(9_000)
    val cooldowns = listOf(pulse, cloak, repair, overdrive)

    val slots = listOf(
        HotbarSlot(label = "PULSE", prompt = "1", cooldown = pulse),
        HotbarSlot(label = "CLOAK", prompt = "2", cooldown = cloak),
        HotbarSlot(label = "REPAIR", prompt = "3", cooldown = repair),
        HotbarSlot(label = "OVER", prompt = "4", cooldown = overdrive),
    )

    Hotbar(
        slots,
        Modifier.align(Alignment.BottomCentre).padding(bottom = 28f),
        onUse = { index -> use(cooldowns[index], state) },
        state = hotbar,
        slotSize = 56f,
    )
}

/** What an ability does here: start its cooldown and cost something. */
private fun use(cooldown: Cooldown, state: ShowcaseState) {
    if (!cooldown.trigger()) return
    state.heat = (state.heat + 0.18f).coerceAtMost(1f)
}

/** A small readout pinned to each drone, which is what "world tracking" means. */
@Composable
private fun TargetTags(state: ShowcaseState) {
    state.targets.forEach { target ->
        if (!target.onScreen) return@forEach
        // Further away is fainter, which is most of what sells a tag as being in the world.
        val fade = (1f - (target.distance - 4f) / 16f).coerceIn(0.25f, 1f)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(target.screenX - 52f, target.screenY - 46f)
                .alpha(fade)
                .styled("tag"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4f)) {
                Text(target.callsign, style = "label.tag")
                Bar(target.integrity, Modifier.width(84f), thickness = 4f, trail = false)
            }
        }
    }
}

/** The switches: every exhibit can be turned off, which is how you see what each one costs. */
@Composable
private fun ExhibitPanel(state: ShowcaseState) {
    val shown by animateFloatAsState(1f, Tween(240, easing = Easings.EaseOut))

    Panel(
        Modifier.align(Alignment.BottomEnd).padding(right = 28f, bottom = 28f).width(330f).alpha(shown),
        style = "panel.quiet",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Text("ON SHOW", style = "label.title")
            Exhibit.entries.forEach { exhibit ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = VerticalAlignment.Centre,
                ) {
                    Column(Modifier.width(230f), verticalArrangement = Arrangement.spacedBy(2f)) {
                        Text(exhibit.title, style = "label")
                        Text(exhibit.blurb, style = "label.dim")
                    }
                    Toggle(state.isOn(exhibit), { state.toggle(exhibit) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("panel in the scene, redraws", style = "label.dim")
                Text("${state.holoDraws}", style = "label")
            }
        }
    }
}
