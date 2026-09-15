package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextFieldValue
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.KeyBindButton
import dev.wildware.composegl.ui.widget.KeyboardTarget
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LazyVerticalGrid
import dev.wildware.composegl.ui.widget.LocalGamepadKeyboard
import dev.wildware.composegl.ui.widget.NumberStepper
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Tabs
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import dev.wildware.composegl.ui.widget.Tooltip

@Composable
fun WidgetsPage() {
    Page(Section.Widgets, "Every widget takes its look from the skin, and every one is driven by mouse, touch, keyboard and pad without extra wiring.") {
        Buttons()
        Switches()
        Sliders()
        Fields()
        Choosers()
        Lists()
        LazyGridCard()
        TabsCard()
        DialogCard()
        Rebinding()
    }
}

@Composable
private fun Buttons() = Card("Buttons", "Hover, press, focus with Tab; the disabled one refuses all of it.") {
    var presses by remember { mutableIntStateOf(0) }
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button("Play", { presses++ }, Modifier.testTag("button-play"), style = "button.primary")
        Button("Options", { presses++ })
        Button("Delete save", { presses++ }, style = "button.danger")
        Button("Locked", {}, enabled = false)
    }
    Text(if (presses == 0) "Not pressed yet" else "Pressed $presses time${if (presses == 1) "" else "s"}", Modifier.testTag("presses"), style = "label.dim")
}

@Composable
private fun Switches() = Card("Toggles and checkboxes") {
    var subtitles by remember { mutableStateOf(true) }
    var hints by remember { mutableStateOf(false) }
    var hardcore by remember { mutableStateOf(false) }
    Toggle(subtitles, { subtitles = it }, label = "Subtitles")
    Checkbox(hints, { hints = it }, label = "Show hints")
    Checkbox(hardcore, { hardcore = it }, label = "Hardcore mode", modifier = Modifier.testTag("hardcore"))
    Text(if (hardcore) "One life. Good luck." else "Saves are on.", style = if (hardcore) "label.danger" else "label.dim")
}

@Composable
private fun Sliders() = Card("Slider and steppers", "Drag, or focus and use the arrows.") {
    var volume by remember { mutableFloatStateOf(0.6f) }
    var quality by remember { mutableStateOf("Medium") }
    var lives by remember { mutableIntStateOf(3) }
    Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
        Slider(volume, { volume = it }, Modifier.testTag("volume"), step = 0.05f, length = (LocalCardWidth.current - 110f).coerceAtLeast(120f))
        Text("${(volume * 100).toInt()}%")
    }
    Bar(volume, Modifier.fillMaxWidth(), clock = dev.wildware.composegl.ui.animation.Clock.Ui)
    Stepper(listOf("Low", "Medium", "High", "Ultra"), quality, { quality = it })
    Row(horizontalArrangement = Arrangement.spacedBy(12f), verticalAlignment = VerticalAlignment.Centre) {
        Text("Lives")
        NumberStepper(lives, { lives = it }, range = 1..9)
    }
}

@Composable
private fun Fields() = Card("Text field and on-screen keyboard", "Type, select, copy and paste. On a pad the keyboard opens by itself; here a button opens it too.") {
    val name = remember { mutableStateOf("") }
    val keyboard = LocalGamepadKeyboard.current
    TextField(name.value, { name.value = it }, Modifier.fillMaxWidth().testTag("callsign"), placeholder = "Call sign", maxLength = 16)
    Text(if (name.value.isEmpty()) "Nobody yet" else "Welcome aboard, ${name.value}", style = "label.dim")
    if (keyboard != null) {
        val target = remember {
            object : KeyboardTarget {
                override val value get() = TextFieldValue(name.value, TextRange(name.value.length))
                override val multiline = false
                override fun onText(event: TextEvent): Boolean {
                    name.value = (name.value + event.text).take(16)
                    return true
                }
                override fun onKey(event: KeyEvent): Boolean {
                    if (event.type != KeyEventType.Down || event.key != Key.Backspace) return false
                    name.value = name.value.dropLast(1)
                    return true
                }
            }
        }
        Button(if (keyboard.isOpen) "Close the keyboard" else "Open the on-screen keyboard", {
            if (keyboard.isOpen) keyboard.close() else keyboard.open(target, from = null)
        }, Modifier.testTag("open-keyboard"), style = "button.quiet")
    }
}

@Composable
private fun Choosers() = Card("Dropdown and tooltip", "The list opens over everything else on the screen.") {
    var resolution by remember { mutableStateOf("1920 x 1080") }
    Dropdown(
        options = listOf("1280 x 720", "1600 x 900", "1920 x 1080", "2560 x 1440", "3840 x 2160"),
        selected = resolution,
        onSelect = { resolution = it },
        modifier = Modifier.width(220f).testTag("resolution"),
    ) { Text(it) }
    Tooltip("Costs 40 energy. Cools down in 12 seconds.") {
        Button("Hover me", {}, style = "button.quiet")
    }
}

@Composable
private fun Lists() = Card("Lazy list with sticky headers", "Only the rows on screen are built. Scroll with the wheel, a drag or the pad.") {
    LazyColumn(Modifier.fillMaxWidth().height(210f).testTag("inventory"), spacing = 4f) {
        Inventory.forEach { (section, names) ->
            stickyHeader(key = section) {
                Box(Modifier.fillMaxWidth().height(26f).background(Accent, corner = 4f).padding(left = 10f), contentAlignment = Alignment.CentreStart) {
                    Text(section, colour = Ink)
                }
            }
            items(names.size, key = { "$section$it" }) { index ->
                Button(onClick = {}, modifier = Modifier.fillMaxWidth(), style = "item", contentAlignment = Alignment.CentreStart) {
                    Text(names[index])
                }
            }
        }
    }
}

private val Inventory = listOf(
    "WEAPONS" to listOf("Iron sword", "Longbow", "War hammer", "Dagger", "Crossbow"),
    "ARMOUR" to listOf("Leather cap", "Chain mail", "Tower shield", "Greaves", "Gauntlets"),
    "POTIONS" to listOf("Healing", "Stamina", "Night eye", "Fire ward", "Swiftness"),
)

@Composable
private fun LazyGridCard() = Card("Lazy grid", "Ten thousand items; only the visible rows exist.") {
    var picked by remember { mutableIntStateOf(-1) }
    LazyVerticalGrid(
        count = 10_000,
        columns = GridCells.Adaptive(minSize = 56f),
        modifier = Modifier.fillMaxWidth().height(180f),
        spacing = 4f,
    ) { index ->
        Button(
            "$index",
            { picked = index },
            Modifier.fillMaxWidth(),
            style = if (index == picked) "item.selected" else "item",
        )
    }
    Text(if (picked < 0) "Pick one" else "Picked item $picked", style = "label.dim")
}

@Composable
private fun TabsCard() = Card("Tabs") {
    var tab by remember { mutableIntStateOf(0) }
    Tabs(tab, { tab = it }, listOf("Map", "Gear", "Quests"), Modifier.fillMaxWidth()) { page ->
        Text(
            when (page) {
                0 -> "The fog clears as you walk."
                1 -> "Iron sword, chain mail, three potions."
                else -> "Find the lost map of Oakmere."
            },
            Modifier.fillMaxWidth().padding(vertical = 8f),
        )
    }
}

@Composable
private fun DialogCard() = Card("Dialog", "A modal question: it takes focus and gives it back when it closes.") {
    val state = LocalShowcase.current
    Button("Abandon mission…", { state.dialogOpen = true }, Modifier.testTag("open-dialog"), style = "button.danger")
}

@Composable
private fun Rebinding() = Card("Key rebinding", "Press a binding, then any key, mouse button or pad button. Escape cancels.") {
    BindRow("Jump", InputBinding.Keyboard(Key.Space))
    BindRow("Fire", InputBinding.Mouse(PointerButton.Primary))
    BindRow("Interact", InputBinding.Gamepad(GamepadButton.West))
}

@Composable
private fun BindRow(action: String, initial: InputBinding) {
    var binding by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = VerticalAlignment.Centre) {
        Text(action, Modifier.weight(1f))
        KeyBindButton(binding, onBind = { binding = it }, modifier = Modifier.width(160f))
    }
}
