package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Grid
import dev.wildware.composegl.ui.layout.GridCells
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One whole options-and-inventory screen, built from the widgets a game's menus are made of, and
 * played through from start to finish with a mouse, a keyboard and a pad.
 *
 * Each widget has its own tests. This one is about them sharing a screen: a dropdown's list over an
 * inventory, a key binding that must not be stolen by navigation, a carried item and a popup both
 * answering Back, a panel fading in with selectable text inside it. What is asserted is what a
 * player sees afterwards — the words drawn, where focus is, what landed on the clipboard.
 */
class SettingsAndInventoryUiTest {

    private data class Item(val name: String)

    private val opened = mutableListOf<UiTest>()
    private val backend = HeadlessBackend()

    /** Every catalogue entry bought, in order. */
    private val bought = mutableListOf<Int>()
    private var screenBacks = 0

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(): UiTest =
        uiTest(Size(1000f, 600f), backend, onBack = { screenBacks++ }) { Screen() }.also { opened += it }

    private val seed = "Seed: 8F3A-22C1"

    /** Settings on the left, the bag and a shop catalogue on the right. */
    @Composable
    private fun Screen() {
        var resolution by remember { mutableStateOf("1280x720") }
        var quality by remember { mutableStateOf("Medium") }
        var jump by remember { mutableStateOf<InputBinding>(InputBinding.Keyboard(Key.Space)) }
        var details by remember { mutableStateOf(false) }
        val bag = remember { mutableStateListOf<Item?>(Item("sword"), Item("shield"), null, null, null, null) }

        PopupHost {
            DragAndDropHost {
                Row(Modifier.offset(20f, 20f), horizontalArrangement = Arrangement.spacedBy(40f)) {
                    Column(Modifier.width(400f), verticalArrangement = Arrangement.spacedBy(8f)) {
                        Text("SETTINGS")
                        Dropdown(
                            options = listOf("1280x720", "1600x900", "1920x1080"),
                            selected = resolution,
                            onSelect = { resolution = it },
                            modifier = Modifier.width(200f).testTag("resolution"),
                            initialFocus = true,
                        ) { Text(it, Modifier.testTag("res $it")) }
                        Stepper(
                            options = listOf("Low", "Medium", "High"),
                            selected = quality,
                            onSelect = { quality = it },
                            modifier = Modifier.testTag("quality"),
                        )
                        Divider(Modifier.fillMaxWidth().testTag("rule"))
                        KeyBindButton(
                            binding = jump,
                            onBind = { jump = it },
                            modifier = Modifier.width(160f).testTag("jump"),
                        )
                        Button("DETAILS", onClick = { details = !details }, modifier = Modifier.testTag("details"))
                        AnimatedVisibility(details) {
                            SelectionContainer(Modifier.testTag("panel")) { Text(seed, Modifier.testTag("seed")) }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16f)) {
                        Grid(GridCells.Fixed(3), Modifier.width(3 * 60f + 2 * 8f).testTag("bag"), spacing = 8f) {
                            repeat(bag.size) { index -> Slot(index, bag) }
                        }
                        LazyVerticalGrid(
                            count = 200,
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.size(240f, 160f).testTag("catalogue"),
                            key = { it },
                            bars = false,
                        ) { index ->
                            Button(
                                "$index",
                                onClick = { bought += index },
                                modifier = Modifier.fillMaxWidth().height(40f).testTag("shop$index"),
                            )
                        }
                    }
                }
            }
        }
    }

    /** A bag slot: carries its item, takes any item, and reads out what is in it. */
    @Composable
    private fun Slot(index: Int, bag: MutableList<Item?>) {
        val state = remember { DropTargetState() }
        val item = bag[index]
        var modifier = Modifier.testTag("slot$index").size(60f, 60f)
        if (item != null) modifier = modifier.dragSource(payload = item) { Text(item.name, Modifier.testTag("carried")) }
        modifier = modifier.dropTarget<Item>(
            state = state,
            onDrop = { moved ->
                val from = bag.indexOf(moved)
                bag[from] = bag[index]
                bag[index] = moved
            },
        )
        Box(modifier) { Text((item?.name ?: "-") + if (state.isHovered) "+" else "", Modifier.testTag("label$index")) }
    }

    private fun UiTest.assertBag(vararg expected: String) =
        expected.forEachIndexed { index, text -> assertText("label$index", text) }

    /** The left edge of character [index] of the seed, whose font gives every character one width. */
    private fun UiTest.seedAt(index: Int): Offset {
        val box = node("seed").boundsInRoot
        return Offset(box.left + index * box.width / seed.length, box.centre.y)
    }

    @Test
    fun `a player sets up the options and sorts the bag with a mouse a keyboard and a pad`() {
        val ui = open()
        ui.assertFocused("resolution")
        ui.assertText("resolution", "1280x720")
        ui.assertBag("sword", "shield", "-", "-", "-", "-")

        // The mouse opens the dropdown over the bag's column and picks from the list.
        ui.click("resolution")
        ui.click(ui.root.findAll("res 1920x1080").last().boundsInRoot.centre)
        ui.assertText("resolution", "1920x1080")
        ui.assertFocused("resolution")

        // The keyboard walks down to the stepper and steps it.
        ui.key(Key.Down)
        ui.assertFocused("quality")
        ui.key(Key.Right)
        ui.assertText("quality", "<\nHigh\n>")

        // Rebinding takes the next key, even an arrow, instead of navigating on it.
        ui.click("jump")
        ui.assertText("jump", "PRESS A KEY")
        ui.key(Key.Down)
        ui.assertText("jump", "DOWN")
        ui.assertFocused("jump")

        // The pad opens the details panel, which fades in under the button.
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("details")
        ui.assertDoesNotExist("seed")
        ui.pad(GamepadButton.South)
        ui.assertText("panel", seed)

        // The seed in it can be dragged over and copied.
        ui.press(ui.seedAt(6))
        ui.moveTo(ui.seedAt(15))
        ui.release()
        assertTrue(ui.key(Key.C, Modifiers.Control), "ctrl c with the seed selected is taken")
        assertEquals("8F3A-22C1", backend.clipboard.read())

        // The mouse carries the sword across the grid to the last slot.
        ui.press("slot0")
        ui.moveTo(ui.node("slot0").boundsInRoot.centre + Offset(30f, 0f))
        ui.moveTo(ui.node("slot5").boundsInRoot.centre)
        ui.assertText("label5", "-+")
        ui.release()
        ui.assertBag("-", "shield", "-", "-", "-", "sword")

        // The pad picks the shield up, carries it down a row and puts it back with East.
        ui.click("slot1")
        ui.assertFocused("slot1")
        ui.pad(GamepadButton.South)
        ui.assertExists("carried")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("slot4")
        ui.pad(GamepadButton.East)
        ui.assertDoesNotExist("carried")
        ui.assertBag("-", "shield", "-", "-", "-", "sword")
        assertEquals(0, screenBacks, "the carried shield took the Back, not the screen")

        // And again, this time dropped with South.
        ui.pad(GamepadButton.DpadUp)
        ui.assertFocused("slot1")
        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)
        ui.assertBag("-", "-", "-", "-", "shield", "sword")

        // The wheel scrolls the catalogue a long way, and what came into view can be bought.
        ui.assertDoesNotExist("shop60")
        repeat(5) { ui.scroll("catalogue", Offset(0f, 200f)) }
        ui.advanceBy(500)
        ui.assertDoesNotExist("shop0")
        // The first row wholly inside the window, which the scroll brought from far below.
        val window = ui.node("catalogue").boundsInRoot
        val visible = (0 until 200).first { index ->
            ui.root.findOrNull("shop$index")?.boundsInRoot?.top?.let { it >= window.top } == true
        }
        assertTrue(visible >= 16, "five notches moved the catalogue past its first rows: $visible")
        ui.click("shop$visible")
        assertEquals(listOf(visible), bought)

        // Details closes, and the whole screen reads right on a drawn frame.
        ui.click("details")
        ui.assertDoesNotExist("seed")
        ui.render()
        val drawn = backend.canvas.texts()
        listOf("1920x1080", "High", "DOWN", "shield", "sword").forEach { assertTrue(it in drawn, "$it is drawn: $drawn") }
        assertFalse(seed in drawn, "the closed panel is not drawn")
        assertEquals(0, screenBacks)
    }
}
