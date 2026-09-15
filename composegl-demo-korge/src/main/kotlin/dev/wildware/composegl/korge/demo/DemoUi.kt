package dev.wildware.composegl.korge.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.effects.outline
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.FrameBudgetOverlay
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.game.BarThreshold
import dev.wildware.composegl.ui.game.DamageNumberLayer
import dev.wildware.composegl.ui.game.Hotbar
import dev.wildware.composegl.ui.game.HotbarSlot
import dev.wildware.composegl.ui.game.HotbarState
import dev.wildware.composegl.ui.game.rememberCooldown
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.KeyBindButton
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.PromptGlyph
import dev.wildware.composegl.ui.widget.ProvidePrompts
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle

/**
 * The demo's whole interface: one of three screens, the skin the player chose, and the frame budget.
 *
 * Nothing here knows it is running on KorGE. The same function runs under `uiTest` with no window,
 * which is how `DemoScreensTest` plays it.
 *
 * @param budget what the frame costs, from the view's renderer. F3 hides it.
 */
@Composable
fun DemoUi(state: DemoState, budget: FrameBudget) {
    ProvideSkin(if (state.highContrast) Skin.HighContrast else Skin.Default) {
        // The dropdown's list opens in the popup layer, over everything else on the screen.
        ProvidePrompts(state.prompts) { PopupHost {
            Box(
                Modifier.fillMaxSize().onKeyEvent { event ->
                    if (event.type == KeyEventType.Down && event.key == Key.F3) {
                        state.showBudget = !state.showBudget
                        true
                    } else {
                        false
                    }
                },
            ) {
                when (state.screen) {
                    DemoScreen.Menu -> MainMenu(state)
                    DemoScreen.Game -> Hud(state)
                    DemoScreen.Settings -> Settings(state)
                }
                if (state.showBudget) {
                    FrameBudgetOverlay(budget, Modifier.align(Alignment.TopEnd).padding(top = 16f, right = 16f).testTag("budget"))
                }
            }
        } }
    }
}

/** Play, settings and quit, over the moving scene. A pad, the arrows, Tab and the mouse all work. */
@Composable
private fun MainMenu(state: DemoState) {
    Box(Modifier.fillMaxSize().background(Colour.argb(0x8C05070C)).testTag("menu"), contentAlignment = Alignment.Centre) {
        Panel(Modifier.width(460f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
                // Two shader effects from composegl-effects, compiled by KorGE's GL context: an outline
                // round the title, and a stripe that dissolves in and out on the game's own clock.
                Box(Modifier.outline(Colour.rgb(0x4CC2FF), width = 2f)) {
                    Text("KORGE  x  COMPOSEGL", style = "label.title")
                }
                Box(
                    Modifier.fillMaxWidth().height(8f)
                        .dissolve(state.dissolve, scale = 10f, edge = Colour.rgb(0xFFB02E))
                        .background(Colour.rgb(0x4CC2FF), corner = 4f),
                )
                Button("PLAY", onClick = { state.screen = DemoScreen.Game }, modifier = Modifier.fillMaxWidth().testTag("play"), style = "button.primary", initialFocus = true)
                Button("SETTINGS", onClick = { state.screen = DemoScreen.Settings }, modifier = Modifier.fillMaxWidth().testTag("settings"))
                Button("QUIT", onClick = { state.onQuit() }, modifier = Modifier.fillMaxWidth().testTag("quit"), style = "button.danger")
                // Fallback fonts and picture glyphs: Chinese and Korean from Noto subsets, emoji as PNGs.
                Text("玩家: 你好！欢迎来到游戏 🎮", style = "label")
                Text("플레이어 안녕하세요 👍", style = "label.dim")
                Row(horizontalArrangement = Arrangement.spacedBy(6f), verticalAlignment = VerticalAlignment.Centre) {
                    PromptGlyph(Action.Confirm)
                    Text("select", style = "label.dim")
                    Spacer(Modifier.width(10f))
                    PromptGlyph(Action.Cancel)
                    Text("back", style = "label.dim")
                }
            }
        }
    }
}

/** Health with a damage trail, a shield, the score, a hotbar on cooldowns, and numbers over the hits. */
@Composable
private fun Hud(state: DemoState) {
    val hotbar = remember { HotbarState() }
    val dash = rememberCooldown(2_000)
    val bomb = rememberCooldown(5_000)
    val heal = rememberCooldown(8_000)
    val cooldowns = listOf(dash, bomb, heal)
    // Something already sweeping the moment the HUD opens, so a screenshot shows what a cooldown is.
    LaunchedEffect(Unit) {
        if (!state.primeCooldowns) return@LaunchedEffect
        bomb.trigger()
        heal.trigger()
    }

    Box(Modifier.fillMaxSize().onKeyEvent(hotbar::onKey).testTag("hud")) {
        DamageNumberLayer(state.damage, Modifier.fillMaxSize())

        Panel(Modifier.align(Alignment.TopStart).padding(left = 24f, top = 24f).width(300f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.callsign.ifBlank { "PLAYER" }, style = "label.title")
                    Text("${state.score}", style = "label", modifier = Modifier.testTag("score"))
                }
                Text("HEALTH", style = "label.dim")
                Bar(
                    state.health,
                    Modifier.fillMaxWidth().testTag("health"),
                    thresholds = listOf(BarThreshold(0.5f, "bar.fill.low"), BarThreshold(0.25f, "bar.fill.critical")),
                    pulseBelow = 0.25f,
                )
                Text("SHIELD", style = "label.dim")
                Bar(state.shield, Modifier.fillMaxWidth(), segments = 5, trail = false)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCentre).padding(bottom = 24f),
            verticalArrangement = Arrangement.spacedBy(8f),
        ) {
            Hotbar(
                listOf(
                    HotbarSlot(label = "DASH", prompt = "1", cooldown = dash),
                    HotbarSlot(label = "BOMB", prompt = "2", cooldown = bomb),
                    HotbarSlot(label = "HEAL", prompt = "3", cooldown = heal, charges = 2),
                    HotbarSlot(prompt = "4"),
                ),
                onUse = { index -> cooldowns.getOrNull(index)?.trigger() },
                state = hotbar,
                slotSize = 56f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6f), verticalAlignment = VerticalAlignment.Centre) {
                PromptGlyph(DemoState.Jump)
                Text("jump", style = "label.dim")
                Spacer(Modifier.width(10f))
                PromptGlyph(Action.Cancel)
                Text("menu", style = "label.dim")
            }
        }
    }
}

/** Every kind of setting a game has, each one a toolkit widget. */
@Composable
private fun Settings(state: DemoState) {
    Box(Modifier.fillMaxSize().background(Colour.argb(0xB005070C)).testTag("settings-screen"), contentAlignment = Alignment.Centre) {
        Panel(Modifier.width(600f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12f)) {
                Text("SETTINGS", style = "label.title")
                Setting("Volume  ${state.volume.toInt()}") {
                    Slider(
                        state.volume,
                        { state.volume = it },
                        Modifier.width(280f).testTag("volume"),
                        range = 0f..100f,
                        step = 5f,
                        initialFocus = true,
                    )
                }
                Setting("Difficulty") {
                    Stepper(
                        listOf("Story", "Normal", "Veteran", "Insane"),
                        state.difficulty,
                        { state.difficulty = it },
                        Modifier.width(280f).testTag("difficulty"),
                    )
                }
                Setting("Quality") {
                    Dropdown(
                        listOf("Low", "Medium", "High", "Ultra"),
                        state.quality,
                        { state.quality = it },
                        Modifier.width(280f).testTag("quality"),
                    ) { Text(it) }
                }
                Setting("Callsign") {
                    TextField(state.callsign, { state.callsign = it }, Modifier.width(280f).testTag("callsign"), placeholder = "name", maxLength = 16)
                }
                Setting("Jump") {
                    KeyBindButton(state.jump, state::rebindJump, Modifier.width(280f).testTag("jump"))
                }
                Setting("Skin") {
                    Stepper(
                        listOf(false, true),
                        state.highContrast,
                        { state.highContrast = it },
                        Modifier.width(280f).testTag("skin"),
                        label = { if (it) "High contrast" else "Standard" },
                    )
                }
                Setting("Frame budget") {
                    Toggle(state.showBudget, { state.showBudget = it }, Modifier.testTag("budget-toggle"))
                }
                Button("BACK", onClick = state::back, modifier = Modifier.fillMaxWidth().testTag("back"))
            }
        }
    }
}

@Composable
private fun Setting(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = VerticalAlignment.Centre) {
        Text(label, style = "label")
        control()
    }
}

/** What the panel standing in the world shows: an uplink readout that follows the score. */
@Composable
fun TerminalScreen(state: DemoState) {
    ProvideSkin(if (state.highContrast) Skin.HighContrast else Skin.Default) {
        Panel(Modifier.size(TerminalWidth.toFloat(), TerminalHeight.toFloat())) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6f)) {
                Text("UPLINK", style = "label.title")
                Bar((state.score % 1000) / 1000f, Modifier.fillMaxWidth(), trail = false)
                Text("hits ${state.score / 10}  ✦  ${state.difficulty}", style = "label.dim")
            }
        }
    }
}

const val TerminalWidth = 220
const val TerminalHeight = 110
