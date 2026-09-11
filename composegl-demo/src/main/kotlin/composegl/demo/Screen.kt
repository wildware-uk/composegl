package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.input.BackStack
import composegl.ui.input.InputSourceTracker
import composegl.ui.input.InteractionState
import composegl.ui.input.Key
import composegl.ui.input.KeyEventType
import composegl.ui.layout.Alignment
import composegl.ui.layout.Arrangement
import composegl.ui.layout.Box
import composegl.ui.layout.Column
import composegl.ui.layout.LeafLayout
import composegl.ui.layout.Row
import composegl.ui.layout.Spacer
import composegl.ui.layout.VerticalAlignment
import composegl.ui.modifier.alpha
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clickable
import composegl.ui.modifier.fillMaxHeight
import composegl.ui.modifier.fillMaxSize
import composegl.ui.modifier.fillMaxWidth
import composegl.ui.modifier.focusable
import composegl.ui.modifier.height
import composegl.ui.modifier.interaction
import composegl.ui.modifier.offset
import composegl.ui.modifier.onKeyEvent
import composegl.ui.modifier.padding
import composegl.ui.modifier.size
import composegl.ui.modifier.styled as styledWith
import composegl.ui.modifier.weight
import composegl.ui.modifier.width
import composegl.ui.skin.ProvideSkin
import composegl.ui.skin.Skin
import composegl.ui.skin.rememberStates
import composegl.ui.skin.rememberStyle
import composegl.ui.skin.styled
import composegl.ui.text.FontProvider
import composegl.ui.animation.Clock
import composegl.ui.animation.Easings
import composegl.ui.animation.LocalClocks
import composegl.ui.animation.Tween
import composegl.ui.animation.animateFloatAsState
import composegl.ui.backend.Clipboard
import androidx.compose.runtime.LaunchedEffect
import composegl.ui.game.Bar
import androidx.compose.runtime.LaunchedEffect
import composegl.ui.game.BarThreshold
import composegl.ui.game.Hotbar
import composegl.ui.game.HotbarSlot
import composegl.ui.game.HotbarState
import composegl.ui.game.RadialCooldown
import composegl.ui.game.rememberCooldown
import composegl.ui.backend.SoftKeyboard
import composegl.ui.widget.Button
import composegl.ui.widget.Checkbox
import composegl.ui.widget.Dialog
import composegl.ui.widget.Image
import composegl.ui.widget.ImageFit
import composegl.ui.widget.LocalClipboard
import composegl.ui.widget.LocalSoftKeyboard
import composegl.ui.widget.LocalFonts
import composegl.ui.widget.ProvideBackStack
import composegl.ui.widget.rememberLazyListState
import composegl.ui.widget.LazyColumn
import composegl.ui.widget.Slider
import composegl.ui.widget.TextField
import composegl.ui.widget.Tabs
import composegl.ui.widget.Text
import composegl.ui.widget.Toggle

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
        LocalClipboard provides clipboard,
        LocalSoftKeyboard provides softKeyboard,
    ) {
        ProvideSkin(skin) {
            ProvideBackStack(state.backs) {
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
                            LorePanel(Modifier.weight(1f).fillMaxHeight(), state)
                        }

                        DemoHotbar(state, hotbar)
                    }

                    // A question the player has to answer: focus cannot leave it, nothing behind it
                    // can be clicked, and Escape or the pad's Back button closes it.
                    if (state.declining) AbortDialog(state)

                    // Proof that a window coordinate made it all the way to a design coordinate,
                    // through the HDPI scale and the letterbox — and that the same coordinate found the
                    // right node underneath it.
                    state.pointer?.let { Reticle(it) }
                }
            }
        }
    }
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
            Ability("Q", 3_000)
            Ability("E", 6_000)
            Ability("F", 9_000)
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
private fun Ability(letter: String, millis: Int) {
    val cooldown = rememberCooldown(millis)

    LaunchedEffect(cooldown.isReady) {
        if (cooldown.isReady) cooldown.trigger()
    }

    RadialCooldown(
        cooldown = cooldown,
        modifier = Modifier.size(44f).styled("slot").clickable { cooldown.trigger() },
    ) {
        // The key while it is usable, the seconds while it is not: two things to say in one square,
        // and only ever one of them at a time.
        if (cooldown.isReady) Text(letter, style = "label")
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
            Text(
                "The relay went quiet six hours ago. Whatever is down there has already " +
                    "rewritten the door codes, so bring the cutter and do not count on the lift.",
                style = "label.body",
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
private fun Reticle(at: Offset) {
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

/** What the player is using, so a widget can ask without being handed it. */
val LocalInputSource = staticCompositionLocalOf<InputSourceTracker> { error("no input source tracker") }

/** What the demo animates, and what the player has changed. */
class DemoState {

    val source = InputSourceTracker()

    var health by mutableStateOf(HealthSteps.first())
        private set

    private var step = 0

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
    }

    /** What the pad's East and Back buttons do here: undo the answer, so it can be given again. */
    fun back() {
        briefing = null
    }

    private companion object {
        /** Somewhere for health to go. Arbitrary, and the point is that it arrives smoothly. */
        val HealthSteps = listOf(0.86f, 0.62f, 0.41f, 0.74f, 0.33f, 0.95f)
    }
}
