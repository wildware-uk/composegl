package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.colourGrade
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.showcase.Exhibit
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.showcase.TargetReadout
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.debug.FrameBudgetOverlay
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.game.BarThreshold
import dev.wildware.composegl.game.Cooldown
import dev.wildware.composegl.game.DamageNumberLayer
import dev.wildware.composegl.game.Hotbar
import dev.wildware.composegl.game.HotbarSlot
import dev.wildware.composegl.game.HotbarState
import dev.wildware.composegl.game.MinimapFrame
import dev.wildware.composegl.game.ParticleLayer
import dev.wildware.composegl.game.MinimapMarker
import dev.wildware.composegl.game.Reticle
import dev.wildware.composegl.game.WorldProjection
import dev.wildware.composegl.game.rememberCooldown
import dev.wildware.composegl.game.rememberReticleState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyShortcut
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.plus
import dev.wildware.composegl.ui.widget.CollapsingHeader
import dev.wildware.composegl.ui.widget.LocalFonts
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.Splitter
import dev.wildware.composegl.ui.widget.contextMenu
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Table
import dev.wildware.composegl.ui.widget.rememberTableState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.TreeView
import dev.wildware.composegl.ui.widget.rememberTreeState

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

            // The menu bar's menus and the target panel's context menu drop through this.
            PopupHost {
                Box(Modifier.fillMaxSize().onKeyEvent(hotbar::onKey)) {
                    if (state.isOn(Exhibit.Hud)) {
                        CombatHud(state)
                        Radar(state)
                        Abilities(state, hotbar)
                    }

                    if (state.isOn(Exhibit.Tracking)) TargetTags(state)

                    if (state.isOn(Exhibit.Shaders)) ShaderShelf(state)

                    if (state.isOn(Exhibit.Contacts)) Contacts(state)
                    if (state.isOn(Exhibit.Tree)) SceneTree(state)

                    // Over the scene and under the panels, which is where a hit happens. The game
                    // fills the pool from its own loop; this only draws it.
                    if (state.isOn(Exhibit.Sparks)) ParticleLayer(state.sparks, Modifier.fillMaxSize())

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

                    // Last, so it is over the scene's panels. Alt or F10 reaches it from the keyboard,
                    // the pad's View button from a pad.
                    ShowcaseMenus(state, budget)
                }
            }
        }
    }
}

/** How far down the panels along the top start, clear of the menu bar. */
private const val BelowMenus = 64f

/**
 * The menu bar across the top: what is on show, which drone is locked, and the frame budget.
 *
 * Every item is state the rest of the screen already reads, so the bar is only another way of
 * changing it — the switches in the corner and the ticks in the Show menu always agree.
 */
@Composable
private fun ShowcaseMenus(state: ShowcaseState, budget: FrameBudget) {
    MenuBar(Modifier.align(Alignment.TopStart), padButton = GamepadButton.Back) {
        Menu("&Show") {
            Exhibit.entries.forEach { exhibit ->
                CheckItem(exhibit.title, checked = state.isOn(exhibit)) { state.toggle(exhibit) }
            }
            Separator()
            Item("Show &everything", shortcut = Modifiers.Primary + Key.E) {
                Exhibit.entries.forEach { if (!state.isOn(it)) state.toggle(it) }
            }
            Item("&Hide everything", shortcut = Modifiers.Primary + Key.H) {
                Exhibit.entries.forEach { if (state.isOn(it)) state.toggle(it) }
            }
        }
        Menu("&Target", enabled = state.targets.isNotEmpty()) {
            state.targets.forEachIndexed { index, target ->
                RadioItem(target.callsign, selected = state.locked == index) { state.locked = index }
            }
            Separator()
            Item("&Release lock", enabled = state.locked >= 0) { state.locked = -1 }
        }
        Menu("&Debug") {
            // F3 itself is the game's, answered before the interface sees it; the item says so.
            CheckItem("Frame &budget", checked = budget.isOn, shortcut = KeyShortcut(Key.F3)) { budget.toggle() }
            Submenu("Set &heat") {
                listOf("Cold" to 0f, "Warm" to 0.5f, "Overheating" to 0.95f).forEach { (name, heat) ->
                    Item(name) { state.heat = heat }
                }
            }
        }
    }
}

/**
 * The four effects the toolkit ships, on four ordinary widgets.
 *
 * Nothing here is a special widget. Each tile is the same box and the same text; what differs is one
 * modifier, and each of those modifiers is a couple of dozen lines of GLSL in `composegl-effects`
 * written against the same public API a game's own shader uses. That is the claim this exhibit is
 * making, and it is why the module is separate: if a blur needed something a game could not have,
 * this would not compile.
 *
 * The two halves sit either side of a [Splitter], so the shelf is also where its divider is on show.
 */
@Composable
private fun ShaderShelf(state: ShowcaseState) {
    Panel(
        Modifier.align(Alignment.TopCentre).padding(top = BelowMenus),
        style = "panel.quiet",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8f)) {
            Text("SHADERS", style = "label.title")
            // Two halves of the shelf on a splitter: drag the bar between them, nudge it with the
            // arrows once it has focus, or double-click it to put it back in the middle.
            var split by remember { mutableStateOf(0.5f) }
            Splitter(
                fraction = split,
                onFractionChange = { split = it },
                modifier = Modifier.size(410f, 56f),
                minFirst = 86f,
                minSecond = 86f,
                first = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12f)) {
                        ShaderTile("BLUR", Modifier.blur(3f))
                        ShaderTile("LINE", Modifier.outline(Colour.White, width = 2f))
                    }
                },
                second = {
                    Row(Modifier.padding(left = 12f), horizontalArrangement = Arrangement.spacedBy(12f)) {
                        ShaderTile("GRADE", Modifier.colourGrade(brightness = 0.7f, saturation = 0f))
                        ShaderTile(
                            "GONE",
                            Modifier.dissolve(state.dissolve, scale = 12f, edge = Colour.rgb(0x4CC2FF)),
                        )
                    }
                },
            )
        }
    }
}

/** One tile: a coloured box with a word on it, drawn through whatever [effect] is. */
@Composable
private fun ShaderTile(label: String, effect: Modifier) {
    Box(
        effect.size(86f, 56f).background(Colour.rgb(0x1E88C7), corner = 8f),
        contentAlignment = Alignment.Centre,
    ) {
        Text(label, style = "label")
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

    if (target != null) TargetPanel(target, state)
}

/** What is locked: its shields, its hull, and how far away it is. */
@Composable
private fun TargetPanel(target: TargetReadout, state: ShowcaseState) {
    // A right-click, a long press, Shift+F10 or the pad's North on it opens what can be done to it.
    val menu = Modifier.contextMenu {
        Item("&Next target") { state.locked = (state.locked + 1) % state.targets.size.coerceAtLeast(1) }
        Item("&Release lock") { state.locked = -1 }
    }
    Panel(Modifier.align(Alignment.TopEnd).padding(right = 28f, top = BelowMenus).width(260f).then(menu)) {
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

/**
 * Every drone in a table: sorted by a click on a title or the pad's North, columns dragged wider at
 * the dividers, and a click or South on a row locks on to that drone.
 *
 * Distance and integrity are live numbers, so a table sorted by either re-sorts itself as the drones
 * move and take hits, and the rows slide past each other to their new places.
 */
@Composable
private fun Contacts(state: ShowcaseState) {
    val table = rememberTableState(sortColumn = 1)
    Table(
        rows = state.targets,
        modifier = Modifier.align(Alignment.TopStart).padding(left = 28f, top = BelowMenus + 196f).size(320f, 150f),
        key = { it.callsign },
        state = table,
        selected = state.targets.getOrNull(state.locked),
        onSelect = { state.locked = state.targets.indexOf(it) },
        empty = { Text("No contacts", style = "label.dim") },
    ) {
        column("Callsign", weight = 1f, sortBy = { it.callsign }) { Text(it.callsign) }
        column("Range", width = 76f, sortBy = { it.distance }, align = HorizontalAlignment.End) {
            Text("${it.distance.toInt()}m")
        }
        column("Hull", width = 76f, sortBy = { it.integrity }, align = HorizontalAlignment.End) {
            Text("${(it.integrity * 100f).toInt()}%")
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
        Modifier.align(Alignment.TopStart).padding(left = 28f, top = BelowMenus).size(180f, 180f),
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

/** One line in the scene tree. [drone] is which target it is, for the rows that are one. */
private class SceneEntry(
    val id: String,
    val label: String,
    val drone: Int = -1,
    val children: () -> List<SceneEntry> = { emptyList() },
)

/**
 * What is in the scene, as a tree: the ship, the drones and the effects on show.
 *
 * The drones are read from the game's state each time the row is opened, so a drone that arrives
 * appears under it. Choosing one locks it, and the locked drone is the chosen row, so the tree and
 * the target panel always agree. A right-click, Shift+F10 or the pad's North on a drone opens what
 * can be done to it.
 */
@Composable
private fun SceneTree(state: ShowcaseState) {
    val roots = remember(state) {
        listOf(
            SceneEntry("ship", "Ship") {
                listOf(
                    SceneEntry("reticle", "Reticle"),
                    SceneEntry("weapons", "Weapons") {
                        listOf(SceneEntry("cannon", "Cannon"), SceneEntry("missiles", "Missiles"))
                    },
                )
            },
            SceneEntry("drones", "Drones") {
                state.targets.mapIndexed { index, target -> SceneEntry("drone-$index", target.callsign, drone = index) }
            },
            SceneEntry("effects", "Effects") {
                Exhibit.entries.filter { state.isOn(it) }.map { SceneEntry("exhibit-${it.name}", it.title) }
            },
        )
    }
    val tree = rememberTreeState("drones")

    Panel(
        // Beside the contacts table rather than under it, so both fit on a 720-high screen.
        Modifier.align(Alignment.TopStart).padding(left = 364f, top = BelowMenus + 196f).width(240f),
        style = "panel.quiet",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
            Text("SCENE", style = "label.title")
            TreeView(
                roots = roots,
                children = { it.children() },
                key = { it.id },
                modifier = Modifier.fillMaxWidth().height(200f),
                selected = roots[1].children().getOrNull(state.locked),
                onSelect = { entry -> if (entry.drone >= 0) state.locked = entry.drone },
                state = tree,
                hasChildren = { it.id in Branches },
                rowModifier = { entry ->
                    if (entry.drone < 0) Modifier else Modifier.contextMenu {
                        Item("&Lock") { state.locked = entry.drone }
                        Item("&Release lock", enabled = state.locked == entry.drone) { state.locked = -1 }
                    }
                },
            ) { entry, _ ->
                Text(entry.label, style = if (entry.drone >= 0) "label" else "label.dim")
            }
        }
    }
}

/** The rows that open. Known without asking, the way a real scene knows which nodes are groups. */
private val Branches = setOf("ship", "weapons", "drones", "effects")

/**
 * The switches: every exhibit can be turned off, which is how you see what each one costs. They sit
 * under a collapsing header, so the panel folds down to one line once the player has chosen.
 */
@Composable
private fun ExhibitPanel(state: ShowcaseState) {
    val shown by animateFloatAsState(1f, Tween(240, easing = Easings.EaseOut))

    Panel(
        Modifier.align(Alignment.BottomEnd).padding(right = 28f, bottom = 28f).width(330f).alpha(shown),
        style = "panel.quiet",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            // Folds away, so the switches can get out of the way of the scene once they are set.
            CollapsingHeader("ON SHOW", Modifier.fillMaxWidth(), initiallyExpanded = true) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                    Exhibit.entries.forEach { exhibit ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = VerticalAlignment.Centre,
                        ) {
                            Column(Modifier.width(210f), verticalArrangement = Arrangement.spacedBy(2f)) {
                                Text(exhibit.title, style = "label")
                                Text(exhibit.blurb, style = "label.dim")
                            }
                            Toggle(state.isOn(exhibit), { state.toggle(exhibit) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("panel in the scene, redraws", style = "label.dim")
                Text("${state.holoDraws}", style = "label")
            }
        }
    }
}
