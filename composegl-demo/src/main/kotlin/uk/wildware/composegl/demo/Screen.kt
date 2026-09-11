package uk.wildware.composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.debug.FrameBudget
import uk.wildware.composegl.ui.debug.FrameBudgetOverlay
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.graphics.UiCanvas
import uk.wildware.composegl.ui.geometry.Rect
import uk.wildware.composegl.ui.input.BackStack
import uk.wildware.composegl.ui.input.Action
import uk.wildware.composegl.ui.input.InputSourceTracker
import uk.wildware.composegl.ui.input.Prompts
import uk.wildware.composegl.ui.input.InteractionState
import uk.wildware.composegl.ui.input.Key
import uk.wildware.composegl.ui.input.KeyEventType
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Arrangement
import uk.wildware.composegl.ui.layout.Box
import uk.wildware.composegl.ui.layout.Column
import uk.wildware.composegl.ui.layout.LeafLayout
import uk.wildware.composegl.ui.layout.Row
import uk.wildware.composegl.ui.layout.Spacer
import uk.wildware.composegl.ui.layout.VerticalAlignment
import uk.wildware.composegl.ui.modifier.align
import uk.wildware.composegl.ui.modifier.alpha
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.clickable
import uk.wildware.composegl.ui.modifier.fillMaxHeight
import uk.wildware.composegl.ui.modifier.fillMaxSize
import uk.wildware.composegl.ui.modifier.fillMaxWidth
import uk.wildware.composegl.ui.modifier.focusable
import uk.wildware.composegl.ui.modifier.height
import uk.wildware.composegl.ui.modifier.interaction
import uk.wildware.composegl.ui.modifier.offset
import uk.wildware.composegl.ui.modifier.onKeyEvent
import uk.wildware.composegl.ui.modifier.padding
import uk.wildware.composegl.ui.modifier.size
import uk.wildware.composegl.ui.modifier.styled as styledWith
import uk.wildware.composegl.ui.modifier.weight
import uk.wildware.composegl.ui.modifier.width
import uk.wildware.composegl.ui.skin.ProvideSkin
import kotlin.math.cos
import kotlin.math.sin
import uk.wildware.composegl.ui.skin.Skin
import uk.wildware.composegl.ui.skin.rememberStates
import uk.wildware.composegl.ui.skin.rememberStyle
import uk.wildware.composegl.ui.skin.styled
import uk.wildware.composegl.ui.text.FontProvider
import uk.wildware.composegl.ui.animation.Clock
import uk.wildware.composegl.ui.animation.Easings
import uk.wildware.composegl.ui.animation.LocalClocks
import uk.wildware.composegl.ui.animation.Tween
import uk.wildware.composegl.ui.animation.animateFloatAsState
import uk.wildware.composegl.ui.backend.Clipboard
import androidx.compose.runtime.LaunchedEffect
import uk.wildware.composegl.ui.game.Bar
import uk.wildware.composegl.ui.game.DamageNumberLayer
import uk.wildware.composegl.ui.game.WorldAnchor
import uk.wildware.composegl.ui.game.Reticle
import uk.wildware.composegl.ui.game.ReticleState
import uk.wildware.composegl.ui.game.WorldProjection
import uk.wildware.composegl.ui.game.rememberDamageNumbers
import uk.wildware.composegl.ui.game.MinimapFrame
import uk.wildware.composegl.ui.game.MinimapMarker
import uk.wildware.composegl.ui.game.NotificationQueue
import uk.wildware.composegl.ui.game.Notifications
import uk.wildware.composegl.ui.game.rememberReticleState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import uk.wildware.composegl.ui.game.BarThreshold
import uk.wildware.composegl.ui.game.Hotbar
import uk.wildware.composegl.ui.game.HotbarSlot
import uk.wildware.composegl.ui.game.HotbarState
import uk.wildware.composegl.ui.game.RadialCooldown
import uk.wildware.composegl.ui.game.rememberCooldown
import uk.wildware.composegl.ui.backend.SoftKeyboard
import uk.wildware.composegl.ui.widget.Button
import uk.wildware.composegl.ui.widget.Checkbox
import uk.wildware.composegl.ui.widget.Dialog
import uk.wildware.composegl.ui.widget.Image
import uk.wildware.composegl.ui.widget.ImageFit
import uk.wildware.composegl.ui.widget.LocalClipboard
import uk.wildware.composegl.ui.widget.LocalSoftKeyboard
import uk.wildware.composegl.ui.widget.LocalFonts
import uk.wildware.composegl.ui.widget.ProvideBackStack
import uk.wildware.composegl.ui.widget.rememberLazyListState
import uk.wildware.composegl.ui.widget.LazyColumn
import uk.wildware.composegl.ui.widget.Slider
import uk.wildware.composegl.ui.widget.TextField
import uk.wildware.composegl.ui.widget.Tabs
import uk.wildware.composegl.ui.widget.Text
import uk.wildware.composegl.ui.widget.Toggle
import uk.wildware.composegl.ui.widget.Tooltip
import uk.wildware.composegl.ui.widget.LocalInputSource
import uk.wildware.composegl.ui.widget.LocalPrompts
import uk.wildware.composegl.ui.widget.PromptGlyph
import uk.wildware.composegl.ui.widget.Typewriter
import uk.wildware.composegl.ui.widget.TypewriterEffect
import uk.wildware.composegl.ui.widget.rememberTypewriter
import uk.wildware.composegl.ui.widget.TooltipHost

/**
 * The example interface.
 *
 * Written the way a game would write it: `Row`, `Column`, `Box`, a modifier chain, and widgets the
 * game defined itself. Nothing here reaches into the toolkit's internals, and nothing here mentions
 * LibGDX.
 *
 * Nothing here names a colour either. Every widget asks the skin for a style by name, and the skin
 * is `ui/demo.skin.json` — saved while the example runs, seen on the next frame.
 */
@Composable
fun Screen(
    fonts: FontProvider,
    skin: Skin,
    state: DemoState,
    clipboard: Clipboard = Clipboard.None,
    softKeyboard: SoftKeyboard = SoftKeyboard.None,
) {
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalInputSource provides state.source,
        // What the player has each action bound to. A rebinding screen would write into this and
        // every prompt on screen would change on the next frame.
        LocalPrompts provides state.prompts,
        LocalClipboard provides clipboard,
        LocalSoftKeyboard provides softKeyboard,
    ) {
        ProvideSkin(skin) {
            ProvideBackStack(state.backs) {
                // One host for the whole screen: a tooltip has to be drawn over every panel,
                // including the one next to the panel it belongs to.
                TooltipHost {
                    // The hotbar's press, hoisted to the screen. A key event only reaches a widget
                    // while focus is inside it, and the number keys have to work wherever the player
                    // is — so they are answered here, above every button, and the bar is handed the
                    // same state to press.
                    val hotbar = remember { HotbarState() }

                    Box(Modifier.fillMaxSize().styled("screen").onKeyEvent(hotbar::onKey)) {
                        Column(
                            Modifier.fillMaxSize().padding(28f),
                            verticalArrangement = Arrangement.spacedBy(20f),
                        ) {
                            Text("COMPOSEGL", style = "label.title")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("a game interface toolkit on the Compose runtime", style = "label.dim")
                                // Switches the moment the player picks up something else.
                                Text("input: ${state.source.current}".uppercase(), style = "label.dim")
                            }

                            Row(
                                Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(20f),
                            ) {
                                StatusPanel(Modifier.width(300f).fillMaxHeight(), state)
                                RangePanel(Modifier.weight(1f).fillMaxHeight(), state)
                                LorePanel(Modifier.weight(1.2f).fillMaxHeight(), state)
                            }

                            DemoHotbar(state, hotbar)
                        }

                        // What the game is telling the player, in the corner games put it in.
                        // Three at a time and the rest counted: pressing ACCEPT sets off a burst
                        // of eight, which is the moment a queue earns its keep.
                        Notifications(
                            state.notices,
                            Modifier.align(Alignment.TopEnd).padding(right = 40f, top = 150f).width(250f),
                            width = 250f,
                        )

                        // A question the player has to answer: focus cannot leave it, nothing behind it
                        // can be clicked, and Escape or the pad's Back button closes it.
                        if (state.declining) AbortDialog(state)

                        // Proof that a window coordinate made it all the way to a design coordinate,
                        // through the HDPI scale and the letterbox — and that the same coordinate found the
                        // right node underneath it.
                        state.pointer?.let { PointerCross(it) }

                        // F3, in the corner every game puts it in. Off by default, because it is
                        // a debug tool and this screen is also the picture in the README.
                        if (state.budget.isOn) {
                            FrameBudgetOverlay(
                                state.budget,
                                Modifier.align(Alignment.BottomEnd).padding(right = 40f, bottom = 40f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The example's stand-in for a game's own map: a few blocks and a road.
 *
 * Deliberately crude and deliberately too big for the hole it is given, so that the frame's
 * clipping is something the picture shows rather than something the README claims.
 */
private fun drawGroundInto(canvas: UiCanvas, area: Rect) {
    val width = area.right - area.left
    val height = area.bottom - area.top
    canvas.rect(area, Colour(0xFF1A2230.toInt()), 0f)
    canvas.rect(
        Rect(area.left - 20f, area.top + height * 0.52f, area.right + 20f, area.top + height * 0.62f),
        Colour(0xFF243040.toInt()),
        0f,
    )
    canvas.rect(
        Rect(area.left + width * 0.16f, area.top + height * 0.16f, area.left + width * 0.36f, area.top + height * 0.42f),
        Colour(0xFF232C3A.toInt()),
        2f,
    )
    canvas.rect(
        Rect(area.left + width * 0.62f, area.top + height * 0.66f, area.left + width * 1.2f, area.top + height * 0.92f),
        Colour(0xFF232C3A.toInt()),
        2f,
    )
}

/** The confirmation. Two chips, one of which is the answer nobody should give by accident. */
@Composable
private fun AbortDialog(state: DemoState) {
    val clocks = LocalClocks.current

    // Asking a question stops the game. The dialogue is on the interface's clock, so it still
    // fades in over a world that is no longer moving.
    DisposableEffect(clocks) {
        clocks.stop(Clock.World)
        onDispose { clocks.start(Clock.World) }
    }

    val shown by animateFloatAsState(1f, Tween(160, easing = Easings.EaseOut))

    Dialog(
        onDismiss = { state.declining = false },
        modifier = Modifier.width(380f).alpha(shown),
        dismissOnScrim = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
            Heading("CONFIRM")
            Text(
                "The relay is still transmitting in our own voice. Turn the job down?",
                style = "label.body",
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10f, Arrangement.End),
            ) {
                Chip("STAY", "chip", chosen = false, first = true) { state.declining = false }
                Chip("DECLINE", "chip.danger", chosen = false, first = false) {
                    state.declining = false
                    state.answer("DECLINE")
                }
            }
        }
    }
}

/**
 * The nearest thing this example has to a game: something being shot at.
 *
 * Everything in here is a game widget rather than an interface one. The crosshair sits exactly in
 * the middle of the panel whatever shape the window is, opens up when a shot goes off and settles
 * back, and flashes four ticks when one lands. The numbers come off the target on the same shot,
 * through a camera that puts zero in the middle of the view — so nothing here knows how big the
 * panel is, including the code that decides where a number goes.
 */
@Composable
private fun RangePanel(modifier: Modifier, state: DemoState) {
    val numbers = rememberDamageNumbers()
    val reticle = rememberReticleState()
    reticle.hostile = true

    LaunchedEffect(state.shots) {
        if (state.shots == 0) return@LaunchedEffect
        val critical = state.shots % 5 == 0
        val amount = 40 + (state.shots * 37) % 90
        // Nudged sideways by something about the shot, so two in a row are both readable.
        val sideways = (state.shots % 5) * 16f - 32f
        numbers.show(
            if (critical) "$amount!" else "$amount",
            WorldAnchor.at(sideways, -34f),
            critical = critical,
        )
        reticle.hit(kill = critical)
        // Kicks on the shot and settles afterwards, which is the whole reason the spread is
        // animated rather than set.
        reticle.spread = if (critical) 1f else 0.7f
        delay(140)
        reticle.spread = 0.12f
    }

    Panel(modifier, style = "panel.flat") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
                Heading("RANGE")
                Text("target at 40m, hostile", style = "label.dim")

                Spacer(Modifier.weight(1f))

                // The chrome is the toolkit's and the map inside it is the game's: these four
                // rectangles are drawn straight onto the canvas through the hole the frame hands
                // over, and the two that run off the side are clipped by it. A real game reaches
                // its own renderer here with `raw { }`.
                MinimapFrame(
                    Modifier.size(200f, 116f),
                    heading = state.heading,
                    rotate = true,
                    markers = state.mapMarkers,
                    range = 120f,
                    compass = "NESW",
                ) { area -> drawGroundInto(this, area) }
            }

            // The thing being shot at. A square, because what a game draws here is its own.
            Box(Modifier.size(72f).styledWith(rememberStyle("target")))

            // Both layers fill the panel, so both are measured from its middle.
            DamageNumberLayer(numbers, projection = WorldProjection.Centred)
            Reticle(reticle, dot = 2f)
        }
    }
}

/**
 * Drawn by the shader: a rounded fill, a hairline border, a soft shadow, no art at all.
 *
 * Two pages, one at a time. The page that is not showing is still composed, which is why the gear
 * page's checkboxes and its ammunition slider are exactly as the player left them when they come
 * back to it.
 */
@Composable
private fun StatusPanel(modifier: Modifier, state: DemoState) {
    Panel(modifier, style = "panel.flat") {
        Tabs(
            selected = state.tab,
            onSelect = { state.tab = it },
            titles = listOf("STATUS", "GEAR"),
            modifier = Modifier.fillMaxSize(),
            spacing = 10f,
        ) { page ->
            if (page == 0) StatusPage(state) else GearPage(state)
        }
    }
}

@Composable
private fun StatusPage(state: DemoState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12f)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10f),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            // A picture rather than a frame: `icon/crest` has no slices, so the Image widget
            // scales the whole thing and keeps it square whatever size it is asked for.
            Image("icon/crest", Modifier.size(26f), fit = ImageFit.Contain)
            Heading("VITALS")
        }
        // Health has the trail, the thresholds and the pulse: hit it and the ghost bar drains down
        // behind the real one, and under a quarter it turns red and breathes.
        LabelledBar(
            "Health",
            state.health,
            thresholds = listOf(
                BarThreshold(0.25f, "bar.fill.critical"),
                BarThreshold(0.5f, "bar.fill.low"),
            ),
            pulseBelow = 0.25f,
        )
        // Its own set of styles rather than its own widget, and notches so it is counted at a
        // glance rather than measured.
        LabelledBar("Shield", 0.42f, style = "bar.shield", segments = 4)
        LabelledBar("Stamina", 0.78f, style = "bar.stamina")
        Spacer(Modifier.height(4f))
        Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
            Ability("Q", 3_000, "overcharge, 3s")
            Ability("E", 6_000, "pulse shield, 6s")
            Ability("F", 9_000, "breach charge, 9s")
        }
        Spacer(Modifier.weight(1f))
        // A value the player drags, nudges with the arrow keys, or pushes the stick at — all
        // three the widget's, and all three landing on the same five-point steps.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Music", style = "label.dim")
            Text("${state.music.toInt()}", style = "label.dim")
        }
        Slider(
            value = state.music,
            onValueChange = { state.music = it },
            modifier = Modifier.fillMaxWidth(),
            range = 0f..100f,
            step = 5f,
        )
        Checkbox(state.invertY, onCheckedChange = { state.invertY = it }, label = "Invert Y")
        Toggle(state.subtitles, onCheckedChange = { state.subtitles = it }, label = "Subtitles")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Level 12", style = "label.dim")
            Text("2,480 XP", style = "label.dim")
        }
    }
}

/**
 * The other page, and the point of it.
 *
 * Everything on it is remembered here rather than in [DemoState] on purpose: switch to STATUS and
 * back, and the boxes are still ticked and the slider is still where it was, because a hidden tab
 * keeps its composition instead of being thrown away and built again.
 */
@Composable
private fun GearPage(state: DemoState) {
    var cutter by remember { mutableStateOf(true) }
    var flares by remember { mutableStateOf(false) }
    var rebreather by remember { mutableStateOf(true) }
    var rounds by remember { mutableStateOf(40f) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12f)) {
        Heading("LOADOUT")
        // Somewhere to type. Cut and paste reach the system clipboard through whichever backend is
        // running, which is the only part of a text field a game cannot write for itself.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
            Text("Callsign", style = "label.dim")
            TextField(
                value = state.callsign,
                onValueChange = { state.callsign = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "unnamed",
                maxLength = 16,
            )
        }
        Checkbox(cutter, onCheckedChange = { cutter = it }, label = "Plasma cutter")
        Checkbox(flares, onCheckedChange = { flares = it }, label = "Flares")
        Checkbox(rebreather, onCheckedChange = { rebreather = it }, label = "Rebreather")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Rounds", style = "label.dim")
            Text("${rounds.toInt()}", style = "label.dim")
        }
        Slider(
            value = rounds,
            onValueChange = { rounds = it },
            modifier = Modifier.fillMaxWidth(),
            range = 0f..60f,
            step = 10f,
        )
        Spacer(Modifier.weight(1f))
        Text("Mass 18.4 kg of a 24.0 kg allowance", style = "label.dim")
    }
}

/**
 * An ability with a cooldown on it.
 *
 * The demo has no player, so each one uses itself again as soon as it is ready and the sweep never
 * stops. Clicking it while it is still going does nothing at all, which is the point: a cooldown
 * that is already running ignores being triggered rather than starting over.
 */
@Composable
private fun Ability(letter: String, millis: Int, description: String) {
    val cooldown = rememberCooldown(millis)

    LaunchedEffect(cooldown.isReady) {
        if (cooldown.isReady) cooldown.trigger()
    }

    // Rest the pointer on one — or reach it with Tab or a pad — and the tooltip says what it does.
    Tooltip("$letter — $description") {
        RadialCooldown(
            cooldown = cooldown,
            modifier = Modifier.size(44f).styled("slot").clickable { cooldown.trigger() },
        ) {
            // The key while it is usable, the seconds while it is not: two things to say in one
            // square, and only ever one of them at a time.
            if (cooldown.isReady) Text(letter, style = "label")
        }
    }
}

/** The toolkit's bar, with a label and a percentage above it. */
@Composable
private fun LabelledBar(
    label: String,
    fraction: Float,
    style: String = "bar",
    segments: Int = 0,
    thresholds: List<BarThreshold> = emptyList(),
    pulseBelow: Float = 0f,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = "label.dim")
            Text("${(fraction * 100).toInt()}%", style = "label.dim")
        }
        // On the world's clock, not the interface's: this is the player's health, so it stops when
        // the game stops. Open the confirmation dialogue and a draining trail freezes mid-slide
        // while the dialogue itself carries on fading in — the whole reason for having two clocks.
        Bar(
            value = fraction,
            modifier = Modifier.fillMaxWidth(),
            style = style,
            segments = segments,
            thresholds = thresholds,
            pulseBelow = pulseBelow,
        )
    }
}

/** The same job, done by a nine-patch. Its slices and padding come from the skin file. */
@Composable
private fun LorePanel(modifier: Modifier, state: DemoState) {
    val log = rememberLazyListState()

    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Heading("BRIEFING")

            // Typed rather than printed, resting at the full stops, each letter fading up as it
            // lands. Click it and the rest of the line arrives at once, which is what a player who
            // has already read it wants. The box is its final size from the first character, so
            // the log underneath does not jump while the briefing is still arriving.
            val briefing = rememberTypewriter(
                "The relay went quiet six hours ago. Whatever is down there has already " +
                    "rewritten the door codes, so bring the cutter and do not count on the lift.",
            )
            Typewriter(
                briefing,
                Modifier.fillMaxWidth().clickable { briefing.skip() },
                style = "label.body",
                charactersPerSecond = 38f,
                effect = TypewriterEffect.fadeIn(),
            )

            // Five hundred lines of log, of which about a dozen exist. The wheel, a drag, the
            // scrollbar and the pad all move it, and it is clipped by one scissor rather than one
            // per line.
            LazyColumn(
                count = transmissions.size,
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = log,
                key = { it },
                spacing = 6f,
            ) { index ->
                Text(transmissions[index], Modifier.padding(right = 14f), style = "label.dim")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Chip("ACCEPT", "chip", state.briefing == "ACCEPT", first = true) { state.answer("ACCEPT") }
                Chip("DECLINE", "chip.danger", state.briefing == "DECLINE", first = false) { state.declining = true }
            }

            // The buttons those two chips answer to. They say E and ESC now and A and B the moment
            // the player picks up a pad, with nothing reloaded and no event sent to either of them.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6f),
                verticalAlignment = VerticalAlignment.Centre,
            ) {
                PromptGlyph(Action.Confirm)
                Text("accept", style = "label.dim")
                Spacer(Modifier.width(8f))
                PromptGlyph(Action.Cancel)
                Text("stand down", style = "label.dim")
            }
        }
    }
}

/**
 * The toolkit's button, wearing one of the game's own style names.
 *
 * All this adds is the ring around it, which is the example's rather than the toolkit's. The four
 * states, the press that fires only if it ends on the button, and the pad and keyboard reaching it
 * through focus are all `Button`.
 */
@Composable
private fun Chip(label: String, style: String, chosen: Boolean, first: Boolean, onClick: () -> Unit) {
    val touch = remember { InteractionState() }

    FocusRing(touch.isFocused) {
        Button(
            onClick = onClick,
            style = if (chosen) "$style.chosen" else style,
            initialFocus = first,
            interaction = touch,
        ) {
            Text(label)
        }
    }
}

/**
 * The toolkit's hotbar, with the demo's own ten slots in it.
 *
 * The number keys, a click and a pad all press the same slots, and the last three show what a slot
 * can say about itself: a cooldown sweeping over it, how many charges are left, an ability that is
 * switched off, and a hole in the bar where there is nothing at all.
 */
@Composable
private fun DemoHotbar(state: DemoState, hotbar: HotbarState) {
    val blink = rememberCooldown(2_500, Clock.World)
    val heal = rememberCooldown(7_000, Clock.World)

    val slots = listOf(
        HotbarSlot(label = "1", prompt = "1"),
        HotbarSlot(label = "2", prompt = "2"),
        HotbarSlot(label = "3", prompt = "3"),
        HotbarSlot(label = "4", prompt = "4"),
        HotbarSlot(label = "5", prompt = "5"),
        HotbarSlot(label = "6", prompt = "6"),
        HotbarSlot(label = "BLK", prompt = "7", cooldown = blink),
        HotbarSlot(label = "MED", prompt = "8", cooldown = heal, charges = 2),
        HotbarSlot(label = "AMP", prompt = "9", enabled = false),
        HotbarSlot(prompt = "0"),
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0f, Arrangement.Centre)) {
        Hotbar(
            slots = slots,
            state = hotbar,
            hotkeys = false,
            selected = state.selected,
            onSelect = { state.select(it) },
            onUse = { index ->
                // What a game does with a press: use the ability, which starts its cooldown. A
                // press that arrives while it is still running never reaches here.
                slots[index].cooldown?.trigger()
            },
            slotSize = 54f,
        )
    }
}

/** A cross where the pointer is, drawn straight onto the canvas. */
@Composable
private fun PointerCross(at: Offset) {
    val colour = rememberStyle("reticle").textColour
    LeafLayout(
        Modifier.offset(at.x - ReticleSize / 2f, at.y - ReticleSize / 2f).size(ReticleSize),
        name = "reticle",
        draw = { bounds ->
            val centre = bounds.centre
            rect(Rect(bounds.left, centre.y - 0.5f, bounds.right, centre.y + 0.5f), colour)
            rect(Rect(centre.x - 0.5f, bounds.top, centre.x + 0.5f, bounds.bottom), colour)
        },
    )
}

private const val ReticleSize = 18f

private val relayLog = listOf(
    "06:12  relay handshake lost",
    "06:14  automated retry, no answer",
    "06:19  door codes rewritten from inside",
    "06:31  lift called to sublevel three",
    "06:33  lift did not arrive",
    "06:40  motion on the gantry, two returns",
    "06:41  motion on the gantry, eleven returns",
    "06:58  power drawn from the reactor ring",
    "07:04  cutter signed out of the locker",
    "07:22  suit telemetry from a suit nobody wore",
    "07:40  the relay answered, in our own voice",
    "07:41  it asked for the door codes",
    "07:44  reactor ring at one hundred and four per cent",
    "07:51  gantry cameras looped, four minutes of the same four minutes",
    "08:02  sublevel three sealed from inside",
    "08:09  suit telemetry stopped mid-sentence",
    "08:15  door codes rewritten again, ours this time",
    "08:20  something is counting down on the cargo channel",
    "08:26  the count is in our own transponder format",
    "08:31  lift called to the surface, empty",
    "08:38  hull microphones: footsteps in the airlock",
    "08:39  airlock reports nobody inside",
    "08:44  cutter signed back in, by nobody",
    "08:50  the relay went quiet again",
)

/**
 * Enough log that building all of it would be silly, which is the whole point of it being here.
 *
 * Five hundred lines, of which the list ever builds about a dozen.
 */
private val transmissions = List(500) { index ->
    val line = relayLog[index % relayLog.size]
    if (index < relayLog.size) line else "${index + 1}  ${line.substringAfter("  ")}"
}

/** The number row, in the order it is printed: 1 to 9 then 0, which is the tenth slot. */
private val digits = listOf(
    Key.Digit0, Key.Digit1, Key.Digit2, Key.Digit3, Key.Digit4,
    Key.Digit5, Key.Digit6, Key.Digit7, Key.Digit8, Key.Digit9,
)

/** What the demo animates, and what the player has changed. */
class DemoState {

    val source = InputSourceTracker()

    /**
     * What the interface costs a frame. Off, because it is a debug tool and the example is a
     * picture; F3 puts it up.
     */
    val budget = FrameBudget().also { it.isOn = false }

    /** What each action is bound to, and which pad's letters to draw. */
    val prompts = Prompts()

    /** Which way the player is facing, in degrees clockwise from north. */
    var heading by mutableStateOf(0f)
        private set

    /** Two squadmates and an objective, moved rather than made again. */
    val mapMarkers = listOf(
        MinimapMarker(60f, 40f),
        MinimapMarker(-40f, -70f),
        MinimapMarker(0f, 0f, style = "minimap.objective"),
    )

    /** What the game is telling the player about. Three on screen, the rest counted. */
    val notices = NotificationQueue(capacity = 3, holdMillis = 3_200)

    private var noticeStep = -1

    var health by mutableStateOf(HealthSteps.first())
        private set

    private var step = 0

    /** How many shots have landed on the range. Written by [tick], read by the range panel. */
    var shots by mutableStateOf(0)
        private set

    private var shotStep = -1

    /**
     * What the game is doing while nobody touches it.
     *
     * Health moves to a new value every couple of seconds and the bar springs to it, rather than
     * the bar being driven frame by frame off a sine. That is the honest shape of a game interface:
     * the game changes a number now and then, and the interface is what makes it a movement.
     */
    fun tick(seconds: Float) {
        val now = (seconds / 2f).toInt()
        if (now != step) {
            step = now
            health = HealthSteps[now % HealthSteps.size]
        }
        // Something on the range is being shot at, twice a second. One counter, so everything
        // that answers a shot — the crosshair, the numbers — answers the same one.
        val shot = (seconds / 0.5f).toInt()
        if (shot != shotStep) {
            shotStep = shot
            shots++
        }
        // And something to say about it every few seconds, so the queue has work to do.
        val notice = (seconds / 4.5f).toInt()
        if (notice != noticeStep) {
            val (text, detail) = quietNotices[notice % quietNotices.size]
            if (noticeStep >= 0) notices.show(text, detail)
            noticeStep = notice
        }
        // The player turning on the spot, and a squad walking round. Both are written straight
        // onto objects the minimap already holds: nothing here allocates, and the map is redrawn
        // because it is live rather than because anything told it.
        heading = (seconds * 14f) % 360f
        val walk = seconds * 0.6f
        mapMarkers[0].x = 70f + cos(walk) * 40f
        mapMarkers[0].y = 30f + sin(walk) * 40f
        mapMarkers[1].x = -50f + sin(walk * 0.7f) * 30f
        mapMarkers[1].y = -60f + cos(walk * 0.7f) * 30f
        // The objective is a long way off, so it lives on the edge of the frame as an arrow.
        mapMarkers[2].x = 240f
        mapMarkers[2].y = 180f

        if (autoCycle) selected = (seconds / 0.8f).toInt() % 10
    }

    /** Three settings, so the example has something a slider, a checkbox and a switch are about. */
    var music by mutableStateOf(70f)
    var invertY by mutableStateOf(false)
    var subtitles by mutableStateOf(true)

    var selected by mutableStateOf(3)

    /** What the player typed into the callsign field. */
    var callsign by mutableStateOf("")

    /** Which page of the status panel is showing. */
    var tab by mutableStateOf(0)

    /** Whether the "are you sure" is up. */
    var declining by mutableStateOf(false)

    /** Who answers Back, innermost first. The dialogue puts itself on here while it is open. */
    val backs = BackStack()

    /** Where the pointer is, in design units. Null until it has moved at least once. */
    var pointer: Offset? by mutableStateOf(null)

    /** The demo cycles the hotbar until somebody picks a slot, and then stops interfering. */
    var autoCycle by mutableStateOf(true)
        private set

    /** Which chip the player pressed, if either. */
    var briefing: String? by mutableStateOf(null)
        private set

    fun select(slot: Int) {
        selected = slot
        autoCycle = false
    }

    fun answer(choice: String) {
        briefing = choice
        // A burst, the way a game hands out the contents of a chest: three are shown and the
        // rest are counted rather than covering the screen.
        if (choice == "ACCEPT") burst.forEach { notices.show(it.first, it.second) }
    }

    /** What the pad's East and Back buttons do here: undo the answer, so it can be given again. */
    fun back() {
        briefing = null
    }

    private companion object {
        /** Somewhere for health to go. Arbitrary, and the point is that it arrives smoothly. */
        val HealthSteps = listOf(0.86f, 0.62f, 0.41f, 0.74f, 0.33f, 0.95f)

        /** The slow drip: one every few seconds while nobody is doing anything. */
        val quietNotices = listOf(
            "Relay signal found" to "Bearing 042, two kilometres",
            "Suit telemetry restored" to null,
            "Objective updated" to "Reach the lift",
            "Cutter charged" to "Four cuts left",
        )

        /** And the burst, for the moment the briefing is accepted. */
        val burst = listOf(
            "Loadout issued" to "Cutter, breacher, two stims",
            "Stim x2" to null,
            "Breaching charge" to null,
            "Relay codes" to "Expire in six hours",
            "Squad channel open" to null,
            "Achievement" to "Took the job",
            "Waypoint set" to "Lift, level four",
            "Insurance waived" to null,
        )
    }
}
