package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A settings screen with a dropdown on it, driven by a mouse, a keyboard and a pad.
 *
 * Every test composes the screen for real inside a [PopupHost], sends the events a player would,
 * and reads the answer off the screen: which options are drawn and where, what the field shows,
 * and where focus is.
 */
class DropdownTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size = Size(400f, 300f),
        onBack: () -> Unit = {},
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, onBack = onBack, content = content).also { opened += it }

    private val resolutions = listOf("1280x720", "1600x900", "1920x1080", "2560x1440")

    /** What was chosen, in order, across a test. */
    private val picked = mutableListOf<String>()

    /** A resolution dropdown with an APPLY button straight under it, where the list will open. */
    @Composable
    private fun Settings(
        options: List<String> = resolutions,
        maxListHeight: Float = 280f,
        enabled: Boolean = true,
        elsewhere: @Composable () -> Unit = {},
    ) {
        PopupHost {
            var resolution by remember { mutableStateOf(options[0]) }
            Box(Modifier.fillMaxSize()) {
                Column(verticalArrangement = Arrangement.spacedBy(4f)) {
                    Dropdown(
                        options = options,
                        selected = resolution,
                        onSelect = { resolution = it; picked += it },
                        modifier = Modifier.width(200f).testTag("resolution"),
                        maxListHeight = maxListHeight,
                        enabled = enabled,
                        initialFocus = true,
                    ) { Text(it, Modifier.testTag("option $it")) }
                    Button("APPLY", onClick = { picked += "apply" }, modifier = Modifier.testTag("apply"))
                }
                elsewhere()
            }
        }
    }

    // --- reading an open list ------------------------------------------------------------------

    /** Every open popup's node. The screen's own nodes come first in the tree, so these are last. */
    private fun UiTest.popups(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.name == "popup") found += it }
        return found
    }

    /**
     * The option in the open list that shows [text]: the button round its label.
     *
     * The field shows the chosen option's label too, so the list's copy is the last one in the tree.
     */
    private fun UiTest.option(text: String): UiNode {
        val labels = root.findAll("option $text")
        val inList = labels.lastOrNull { it.isInside(popups()) }
            ?: throw AssertionError("$text is not in an open list:\n" + root.debugTree())
        return checkNotNull(inList.parent)
    }

    private fun UiNode.isInside(nodes: List<UiNode>): Boolean {
        var walk: UiNode? = this
        while (walk != null) {
            if (nodes.any { it === walk }) return true
            walk = walk.parent
        }
        return false
    }

    private fun UiTest.clickOption(text: String) = click(option(text).boundsInRoot.centre)

    private fun UiTest.assertFocusedOption(text: String) {
        val expected = option(text)
        assertTrue(focus.focused === expected, "expected focus on the $text option, it is on ${focus.focused}")
    }

    private fun UiTest.assertClosed() {
        assertEquals(emptyList(), popups(), "the list is still open")
    }

    // --- opening -------------------------------------------------------------------------------

    @Test
    fun `closed it shows the chosen option and no list`() {
        val ui = open { Settings() }

        ui.assertText("resolution", "1280x720")
        ui.assertClosed()
    }

    @Test
    fun `a click opens the list under the field as wide as the field`() {
        val ui = open { Settings() }
        val field = ui.node("resolution").boundsInRoot

        ui.click("resolution")

        assertEquals(1, ui.popups().size)
        val first = ui.option("1280x720").boundsInRoot
        val last = ui.option("2560x1440").boundsInRoot
        assertTrue(first.top >= field.bottom, "the list starts under the field: $first against $field")
        assertTrue(last.top > first.top, "options are in order down the list")
        val panel = checkNotNull(ui.popups()[0].children[0].children[0]).boundsInRoot
        assertEquals(field.left, panel.left, "the list lines up with the field")
        assertEquals(field.width, panel.width, "the list is as wide as the field")
    }

    @Test
    fun `the open list is drawn over what is under it and takes its clicks`() {
        val ui = open { Settings() }
        val apply = ui.node("apply").boundsInRoot
        ui.click("resolution")

        ui.render()
        val drawn = (ui.backend.canvas as RecordingCanvas).texts()
        assertTrue(drawn.indexOf("APPLY") < drawn.lastIndexOf("1280x720"), "the list is drawn after APPLY: $drawn")

        ui.click(apply.centre)
        assertFalse("apply" in picked, "the click landed on the list, not on APPLY underneath it: $picked")
    }

    @Test
    fun `the list opens with focus on the chosen option`() {
        val ui = open { Settings() }
        ui.click("resolution")
        ui.clickOption("1920x1080")

        ui.click("resolution")

        ui.assertFocusedOption("1920x1080")
    }

    @Test
    fun `the arrow sits at the right edge of a field given a width`() {
        val ui = open { Settings() }
        val field = ui.node("resolution")
        val arrow = checkNotNull(field.children.firstOrNull { it.name == "dropdown.arrow" })

        val gap = field.boundsInRoot.right - arrow.boundsInRoot.right
        assertTrue(gap in 0f..16f, "the arrow is at the field's right edge, $gap in from it")
        assertEquals(200f, field.width)
    }

    // --- choosing ------------------------------------------------------------------------------

    @Test
    fun `choosing with the mouse selects the option and closes the list`() {
        val ui = open { Settings() }
        ui.click("resolution")

        ui.clickOption("2560x1440")

        assertEquals(listOf("2560x1440"), picked)
        ui.assertText("resolution", "2560x1440")
        ui.assertClosed()
        ui.assertFocused("resolution")
    }

    @Test
    fun `enter opens the list and the arrows and enter choose`() {
        val ui = open { Settings() }
        ui.assertFocused("resolution")

        ui.key(Key.Enter)
        ui.assertFocusedOption("1280x720")
        ui.key(Key.Down)
        ui.key(Key.Down)
        ui.assertFocusedOption("1920x1080")
        ui.key(Key.Enter)

        assertEquals(listOf("1920x1080"), picked)
        ui.assertText("resolution", "1920x1080")
        ui.assertClosed()
        ui.assertFocused("resolution")
    }

    @Test
    fun `the pad opens moves and chooses with south and the d-pad`() {
        val ui = open { Settings() }

        ui.pad(GamepadButton.South)
        ui.assertFocusedOption("1280x720")
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocusedOption("1600x900")
        ui.pad(GamepadButton.South)

        assertEquals(listOf("1600x900"), picked)
        ui.assertText("resolution", "1600x900")
        ui.assertClosed()
        ui.assertFocused("resolution")
    }

    // --- closing without choosing --------------------------------------------------------------

    @Test
    fun `escape closes the list without choosing and focus goes back to the field`() {
        val ui = open { Settings() }
        ui.key(Key.Enter)
        ui.key(Key.Down)

        ui.key(Key.Escape)

        ui.assertClosed()
        assertEquals(emptyList(), picked)
        ui.assertText("resolution", "1280x720")
        ui.assertFocused("resolution")
    }

    @Test
    fun `east on the pad closes the list before it reaches the game`() {
        var leftTheScreen = 0
        val ui = open(onBack = { leftTheScreen++ }) { Settings() }
        ui.pad(GamepadButton.South)

        ui.pad(GamepadButton.East)
        ui.assertClosed()
        ui.assertFocused("resolution")
        assertEquals(0, leftTheScreen, "the list answered back itself")

        ui.pad(GamepadButton.East)
        assertEquals(1, leftTheScreen, "with the list closed back reaches the game")
    }

    @Test
    fun `a press outside closes the list and presses nothing else`() {
        val ui = open(size = Size(400f, 300f)) {
            Settings {
                Button("BACK", onClick = { picked += "back" }, modifier = Modifier.align(Alignment.BottomEnd).testTag("back"))
            }
        }
        ui.click("resolution")
        val back = ui.node("back").boundsInRoot
        val list = ui.popups()[0].children[0].children[0].boundsInRoot
        assertFalse(list.overlaps(back), "BACK is outside the list, so only the popup's cover is over it")

        ui.click("back")
        ui.assertClosed()
        assertEquals(emptyList(), picked, "the press that closed the list did not also press BACK")

        ui.click("back")
        ui.click("apply")
        assertEquals(listOf("back", "apply"), picked, "with the list gone both can be pressed again")
    }

    @Test
    fun `a second click on the field closes the list it opened`() {
        val ui = open { Settings() }
        val field = ui.node("resolution").boundsInRoot.centre

        ui.click(field)
        assertEquals(1, ui.popups().size)
        ui.click(field)

        ui.assertClosed()
    }

    // --- focus ---------------------------------------------------------------------------------

    @Test
    fun `focus cannot walk out of the open list`() {
        val ui = open { Settings() }
        ui.pad(GamepadButton.South)

        repeat(6) { ui.pad(GamepadButton.DpadDown) }
        ui.assertFocusedOption("2560x1440")
        repeat(6) { ui.pad(GamepadButton.DpadUp) }
        ui.assertFocusedOption("1280x720")

        ui.key(Key.Tab, dev.wildware.composegl.ui.input.Modifiers.Shift)
        ui.assertFocusedOption("2560x1440")
    }

    @Test
    fun `a disabled dropdown does not open`() {
        val ui = open { Settings(enabled = false) }

        ui.click("resolution")
        ui.pad(GamepadButton.South)

        ui.assertClosed()
    }

    // --- placement -----------------------------------------------------------------------------

    @Test
    fun `the list opens upwards when there is no room below`() {
        val ui = open(size = Size(400f, 300f)) {
            PopupHost {
                var chosen by remember { mutableStateOf("Easy") }
                Box(Modifier.fillMaxSize()) {
                    Dropdown(
                        listOf("Easy", "Normal", "Hard"),
                        chosen,
                        onSelect = { chosen = it },
                        modifier = Modifier.align(Alignment.BottomStart).width(160f).testTag("difficulty"),
                    ) { Text(it, Modifier.testTag("option $it")) }
                }
            }
        }
        val field = ui.node("difficulty").boundsInRoot

        ui.click("difficulty")

        val bottom = ui.option("Hard").boundsInRoot.bottom
        assertTrue(bottom <= field.top, "the list is above the field: its last option ends at $bottom, the field starts at ${field.top}")
        assertTrue(ui.option("Easy").boundsInRoot.top >= 0f, "and all of it is on the screen")
    }

    @Test
    fun `the list follows a field that has been moved inside the screen`() {
        val ui = open(size = Size(500f, 400f)) {
            PopupHost {
                var chosen by remember { mutableStateOf("English") }
                Box(Modifier.offset(120f, 60f)) {
                    Column(Modifier.offset(30f, 20f)) {
                        Dropdown(
                            listOf("English", "Deutsch", "日本語"),
                            chosen,
                            onSelect = { chosen = it },
                            modifier = Modifier.width(140f).testTag("language"),
                        ) { Text(it, Modifier.testTag("option $it")) }
                    }
                }
            }
        }
        val field = ui.node("language").boundsInRoot
        assertEquals(150f, field.left)

        ui.click("language")

        val first = ui.option("English").boundsInRoot
        assertTrue(first.left >= field.left && first.right <= field.right, "the option $first is under the field $field")
        assertTrue(first.top >= field.bottom && first.top < field.bottom + 20f, "and just under it: $first against $field")
    }

    @Test
    fun `a list longer than it may be scrolls to the chosen option`() {
        val many = List(30) { "Option $it" }
        val ui = open(size = Size(400f, 600f)) {
            PopupHost {
                var chosen by remember { mutableStateOf("Option 25") }
                Dropdown(
                    many,
                    chosen,
                    onSelect = { chosen = it },
                    modifier = Modifier.width(200f).testTag("long"),
                    maxListHeight = 120f,
                ) { Text(it, Modifier.testTag("option $it")) }
            }
        }

        ui.click("long")

        val list = ui.popups()[0].children[0].children[0].boundsInRoot
        assertTrue(list.height <= 120f, "the list is held to its maximum: $list")
        ui.assertFocusedOption("Option 25")
        val chosen = ui.option("Option 25").boundsInRoot
        assertTrue(chosen.top >= list.top && chosen.bottom <= list.bottom, "the chosen option $chosen is scrolled into $list")

        ui.pad(GamepadButton.DpadDown)
        ui.assertFocusedOption("Option 26")
        val next = ui.option("Option 26").boundsInRoot
        assertTrue(next.top >= list.top && next.bottom <= list.bottom, "moving down keeps the option in view: $next in $list")
    }

    // --- where the list lives ------------------------------------------------------------------

    @Test
    fun `the list reads what was provided where the dropdown is not at the host`() {
        val word = staticCompositionLocalOf { "host" }
        val ui = open {
            PopupHost {
                CompositionLocalProvider(word provides "field") {
                    Dropdown(listOf(1, 2), 1, onSelect = {}, modifier = Modifier.width(120f).testTag("numbers")) {
                        Text("$it ${word.current}", Modifier.testTag("option $it"))
                    }
                }
            }
        }

        ui.click("numbers")

        // Only in the list: the field shows 1, so 2 is drawn by the popup alone.
        ui.assertText("option 2", "2 field")
    }

    @Test
    fun `taking the dropdown off the screen takes its open list with it`() {
        // Written from the test rather than by a click, since a click would only close the list.
        var shown by mutableStateOf(true)
        val ui = open {
            PopupHost {
                Column {
                    if (shown) {
                        Dropdown(listOf("A", "B"), "A", onSelect = {}, modifier = Modifier.width(120f).testTag("letters")) {
                            Text(it, Modifier.testTag("option $it"))
                        }
                    }
                    Button("OTHER", onClick = {}, modifier = Modifier.testTag("other"))
                }
            }
        }
        ui.click("letters")
        assertEquals(1, ui.popups().size)

        shown = false
        ui.settle()

        ui.assertClosed()
        ui.assertDoesNotExist("letters")
        ui.assertFocused("other")
    }

    @Test
    fun `turning the dropdown off while it is open closes it for good`() {
        var enabled by mutableStateOf(true)
        val ui = open { Settings(enabled = enabled) }
        ui.click("resolution")
        assertEquals(1, ui.popups().size)

        enabled = false
        ui.settle()
        ui.assertClosed()

        // Turned back on, it stays shut until the player opens it again.
        enabled = true
        ui.settle()
        ui.assertClosed()
        ui.click("resolution")
        assertEquals(1, ui.popups().size)
    }

    @Test
    fun `an empty list opens and escape still closes it`() {
        val ui = open {
            PopupHost {
                Dropdown(emptyList<String>(), "none", onSelect = { picked += it }, modifier = Modifier.width(120f).testTag("empty")) {
                    Text(it)
                }
            }
        }

        ui.click("empty")
        assertEquals(1, ui.popups().size)
        ui.key(Key.Escape)

        ui.assertClosed()
        assertEquals(emptyList(), picked)
    }

    @Test
    fun `a chosen value that is not among the options opens with focus in the list`() {
        val ui = open {
            PopupHost {
                Dropdown(listOf("A", "B"), "Z", onSelect = { picked += it }, modifier = Modifier.width(120f).testTag("letters"), initialFocus = true) {
                    Text(it, Modifier.testTag("option $it"))
                }
            }
        }

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)

        assertEquals(1, picked.size, "the pad reached an option and chose it: $picked")
        ui.assertClosed()
    }

    @Test
    fun `inside a dialog the list is on top and back closes the list before the dialog`() {
        var dialogs = 0
        val ui = open {
            PopupHost {
                var chosen by remember { mutableStateOf("Easy") }
                var showing by remember { mutableStateOf(true) }
                if (showing) {
                    Dialog(onDismiss = { showing = false; dialogs++ }) {
                        Column {
                            Dropdown(
                                listOf("Easy", "Normal", "Hard"),
                                chosen,
                                onSelect = { chosen = it },
                                modifier = Modifier.width(160f).testTag("difficulty"),
                                initialFocus = true,
                            ) { Text(it, Modifier.testTag("option $it")) }
                            Button("OK", onClick = {}, modifier = Modifier.testTag("ok"))
                        }
                    }
                }
            }
        }
        ui.assertFocused("difficulty")

        ui.pad(GamepadButton.South)
        ui.assertFocusedOption("Easy")
        repeat(4) { ui.pad(GamepadButton.DpadDown) }
        ui.assertFocusedOption("Hard")

        ui.pad(GamepadButton.East)
        ui.assertClosed()
        assertEquals(0, dialogs, "back closed the list, not the dialog")
        ui.assertFocused("difficulty")

        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)
        ui.assertText("difficulty", "Normal")
        ui.assertFocused("difficulty")

        ui.pad(GamepadButton.East)
        assertEquals(1, dialogs, "with the list shut back reaches the dialog")
    }

    @Test
    fun `an open list on a still screen draws nothing new`() {
        var frame by mutableStateOf(0)
        val ui = open {
            PopupHost {
                // Read here, and a fresh but equal list each time, so every write reruns the
                // dropdown and the list it has open, the way a screen that recomposes for a clock does.
                @Suppress("UNUSED_VARIABLE") val unused = frame
                Dropdown(resolutions.toList(), "1600x900", onSelect = {}, modifier = Modifier.width(200f).testTag("resolution")) {
                    Text(it)
                }
            }
        }
        ui.click("resolution")
        ui.render()

        frame++

        assertFalse(ui.render(), "recomposing an open dropdown with nothing different is no change")
    }

    @Test
    fun `options that change while the list is open are drawn at once`() {
        var options by mutableStateOf(listOf("Low", "High"))
        val ui = open {
            PopupHost {
                Dropdown(options, "Low", onSelect = {}, modifier = Modifier.width(160f).testTag("quality")) {
                    Text(it, Modifier.testTag("option $it"))
                }
            }
        }
        ui.click("quality")
        ui.render()

        options = listOf("Low", "High", "Ultra")

        assertTrue(ui.render(), "a new option in the open list is something to draw")
        ui.assertText("option Ultra", "Ultra")
        assertTrue(ui.option("Ultra").boundsInRoot.top > ui.option("High").boundsInRoot.top)
    }

    @Test
    fun `a wheel over the screen behind does not close the list`() {
        val ui = open { Settings() }
        ui.click("resolution")

        ui.scroll("apply", Offset(0f, 40f))

        assertEquals(1, ui.popups().size)
    }

    @Test
    fun `a press outside gives focus back to the field`() {
        val ui = open { Settings() }
        ui.key(Key.Enter)
        ui.assertFocusedOption("1280x720")

        ui.click(Offset(390f, 290f))

        ui.assertClosed()
        ui.assertFocused("resolution")
    }

    @Test
    fun `a dropdown with no popup host round it fails where the screen is built`() {
        val failure = kotlin.runCatching {
            open { Dropdown(listOf("A", "B"), "A", onSelect = {}, modifier = Modifier.width(120f)) { Text(it) } }
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("PopupHost") == true, "building the screen names the fix: $failure")
    }

    @Test
    fun `in a scrolled area the list opens under where the field is now and is not cut off`() {
        val scroll = ScrollState()
        val ui = open(size = Size(400f, 400f)) {
            PopupHost {
                var chosen by remember { mutableStateOf("Low") }
                ScrollArea(Modifier.width(300f).height(120f).testTag("area"), state = scroll) {
                    Column {
                        Box(Modifier.size(10f, 200f))
                        Dropdown(
                            listOf("Low", "Medium", "High", "Ultra"),
                            chosen,
                            onSelect = { chosen = it },
                            modifier = Modifier.width(200f).testTag("quality"),
                        ) { Text(it, Modifier.testTag("option $it")) }
                        Box(Modifier.size(10f, 200f))
                    }
                }
            }
        }
        scroll.scrollTo(y = 180f)
        ui.settle()
        val field = ui.node("quality").boundsInRoot
        assertTrue(field.top in 0f..40f, "the field was scrolled up to the top of the area: $field")

        ui.click("quality")

        val first = ui.option("Low").boundsInRoot
        val last = ui.option("Ultra").boundsInRoot
        assertTrue(first.top >= field.bottom && first.top < field.bottom + 20f, "the list is just under the field: $first against $field")
        assertTrue(last.bottom > ui.node("area").boundsInRoot.bottom, "and runs past the scroll area's edge: $last")
        ui.render()
        val drawn = (ui.backend.canvas as RecordingCanvas).texts()
        assertTrue("Ultra" in drawn, "the part past the area is still drawn: $drawn")
    }

    @Test
    fun `a wheel that scrolls the field along keeps the list next to it`() {
        val ui = open(size = Size(400f, 400f)) {
            PopupHost {
                var chosen by remember { mutableStateOf("Low") }
                ScrollArea(Modifier.width(300f).height(300f)) {
                    Column {
                        Box(Modifier.size(280f, 100f).testTag("above"))
                        Dropdown(
                            listOf("Low", "High"),
                            chosen,
                            onSelect = { chosen = it },
                            modifier = Modifier.width(200f).testTag("quality"),
                        ) { Text(it, Modifier.testTag("option $it")) }
                        Box(Modifier.size(10f, 600f))
                    }
                }
            }
        }
        ui.click("quality")
        val before = ui.node("quality").boundsInRoot

        ui.scroll("above", Offset(0f, 60f))
        ui.settle()

        assertEquals(1, ui.popups().size, "a wheel does not close the list")
        val field = ui.node("quality").boundsInRoot
        assertTrue(field.top < before.top, "the wheel scrolled the field up: $before to $field")
        val first = ui.option("Low").boundsInRoot
        assertTrue(first.top >= field.bottom && first.top < field.bottom + 20f, "the list moved with the field: $first against $field")
    }
}
