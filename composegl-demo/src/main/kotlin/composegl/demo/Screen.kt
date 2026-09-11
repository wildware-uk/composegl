package composegl.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
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
import composegl.ui.widget.Button
import composegl.ui.widget.Checkbox
import composegl.ui.widget.Image
import composegl.ui.widget.Button
import composegl.ui.widget.Checkbox
import composegl.ui.widget.ImageFit
import composegl.ui.widget.LocalFonts
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
fun Screen(fonts: FontProvider, skin: Skin, state: DemoState) {
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalInputSource provides state.source,
    ) {
        ProvideSkin(skin) {
            // A screen-level shortcut, on the outermost node: the number keys pick a hotbar slot
            // the way they do in every game that has one. It sits above every button, so it works
            // wherever focus happens to be — which is exactly what bubbling outwards buys.
            val hotkeys = remember {
                Modifier.onKeyEvent { event ->
                    if (event.type != KeyEventType.Down) false else {
                        val slot = digits.indexOf(event.key)
                        if (slot < 0) false else {
                            state.select(if (slot == 0) 9 else slot - 1)
                            true
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize().styled("screen").then(hotkeys)) {
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

                    Hotbar(state)
                }

                // Proof that a window coordinate made it all the way to a design coordinate,
                // through the HDPI scale and the letterbox — and that the same coordinate found the
                // right node underneath it.
                state.pointer?.let { Reticle(it) }
            }
        }
    }
}

/** Drawn by the shader: a rounded fill, a hairline border, a soft shadow, no art at all. */
@Composable
private fun StatusPanel(modifier: Modifier, state: DemoState) {
    Panel(modifier, style = "panel.flat") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10f),
                verticalAlignment = VerticalAlignment.Centre,
            ) {
                // A picture rather than a frame: `icon/crest` has no slices, so the Image widget
                // scales the whole thing and keeps it square whatever size it is asked for.
                Image("icon/crest", Modifier.size(26f), fit = ImageFit.Contain)
                Heading("STATUS")
            }
            Bar("Health", state.health, "bar.fill")
            Bar("Shield", 0.42f, "bar.fill.shield")
            Bar("Stamina", 0.78f, "bar.fill.stamina")
            Spacer(Modifier.weight(1f))
            Checkbox(state.invertY, onCheckedChange = { state.invertY = it }, label = "Invert Y")
            Toggle(state.subtitles, onCheckedChange = { state.subtitles = it }, label = "Subtitles")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Level 12", style = "label.dim")
                Text("2,480 XP", style = "label.dim")
            }
        }
    }
}

/** A labelled bar. Two styled boxes and a fraction — the whole widget. */
@Composable
private fun Bar(label: String, fraction: Float, fill: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = "label.dim")
            Text("${(fraction * 100).toInt()}%", style = "label.dim")
        }
        Box(Modifier.fillMaxWidth().height(10f).styled("bar.track")) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().styled(fill))
        }
    }
}

/** The same job, done by a nine-patch. Its slices and padding come from the skin file. */
@Composable
private fun LorePanel(modifier: Modifier, state: DemoState) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Heading("BRIEFING")
            Text(
                "The relay went quiet six hours ago. Whatever is down there has already " +
                    "rewritten the door codes, so bring the cutter and do not count on the lift.",
                style = "label.body",
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Chip("ACCEPT", "chip", state.briefing == "ACCEPT", first = true) { state.answer("ACCEPT") }
                Chip("DECLINE", "chip.danger", state.briefing == "DECLINE", first = false) { state.answer("DECLINE") }
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

/** Ten slots, one of them lit, and now one of them pickable. */
@Composable
private fun Hotbar(state: DemoState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8f, Arrangement.Centre),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        repeat(10) { slot ->
            Slot(slot, lit = slot == state.selected) { state.select(slot) }
        }
    }
}

@Composable
private fun Slot(slot: Int, lit: Boolean, onClick: () -> Unit) {
    val touch = remember { InteractionState() }
    val resolved = rememberStyle(if (lit) "slot.lit" else "slot", rememberStates(touch))

    FocusRing(touch.isFocused) {
        Box(
            Modifier
                .interaction(touch)
                .focusable(touch)
                .clickable(onClick = onClick)
                .size(54f)
                .styledWith(resolved),
            contentAlignment = Alignment.Centre,
        ) {
            Text("${(slot + 1) % 10}", textStyle = resolved.textStyle, colour = resolved.textColour)
        }
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

    var health by mutableStateOf(0.86f)

    /** Two settings, so the example has something a checkbox and a switch can be about. */
    var invertY by mutableStateOf(false)
    var subtitles by mutableStateOf(true)

    var selected by mutableStateOf(3)

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
}
