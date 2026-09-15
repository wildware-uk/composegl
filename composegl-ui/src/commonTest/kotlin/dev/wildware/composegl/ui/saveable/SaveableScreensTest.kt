package dev.wildware.composegl.ui.saveable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.OnBack
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Screens left and come back to, driven by a mouse, a keyboard and a pad, and judged by what they
 * show when they come back.
 *
 * Every test here opens a screen, changes something on it the way a player would, goes somewhere
 * else so that screen leaves the tree entirely, comes back, and reads the screen.
 */
class SaveableScreensTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(600f, 400f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    // --- the screens ---------------------------------------------------------------------------

    /**
     * A bar of screen buttons, and whichever screen is chosen under it, kept by a holder.
     *
     * Every screen in a test goes through [screen], so the only thing that differs from one test to
     * the next is what is on the screens.
     */
    @Composable
    private fun Game(screens: List<String>, screen: @Composable (String) -> Unit) {
        var current by remember { mutableStateOf(screens.first()) }
        Column {
            Row {
                screens.forEach { name ->
                    Button(name.uppercase(), onClick = { current = name }, modifier = Modifier.testTag("to-$name"))
                }
            }
            SaveableStateHolder(current) { key ->
                if (key != screens.first()) OnBack { current = screens.first() }
                screen(key)
            }
        }
    }

    /** Three tabs, and which one is chosen. */
    @Composable
    private fun Tabs(prefix: String = "") {
        var tab by rememberSaveable { mutableStateOf(0) }
        Column {
            Row {
                repeat(3) { index ->
                    Button("TAB $index", onClick = { tab = index }, modifier = Modifier.testTag("${prefix}tab$index"))
                }
            }
            Text("SHOWING $tab", Modifier.testTag("${prefix}showing"))
        }
    }

    // --- tabs ----------------------------------------------------------------------------------

    @Test
    fun `the tab chosen in the codex is still chosen after the map was opened and closed`() {
        val ui = open {
            Game(listOf("codex", "map")) { key ->
                if (key == "codex") Tabs() else Text("THE MAP", Modifier.testTag("map"))
            }
        }
        ui.click("tab2")
        ui.assertText("showing", "SHOWING 2")

        ui.click("to-map")
        ui.assertDoesNotExist("showing")
        ui.assertText("map", "THE MAP")
        ui.click("to-codex")

        ui.assertText("showing", "SHOWING 2")
    }

    @Test
    fun `two screens built from the same code keep separate state`() {
        val ui = open {
            Game(listOf("codex", "bestiary")) { key ->
                Column {
                    Text(key, Modifier.testTag("title"))
                    Tabs()
                }
            }
        }
        ui.click("tab1")
        ui.click("to-bestiary")
        ui.assertText("title", "bestiary")
        ui.assertText("showing", "SHOWING 0")
        ui.click("tab2")

        ui.click("to-codex")
        ui.assertText("showing", "SHOWING 1")
        ui.click("to-bestiary")
        ui.assertText("showing", "SHOWING 2")
    }

    @Test
    fun `east on the pad leaves the map and the codex comes back as it was`() {
        val ui = open {
            Game(listOf("codex", "map")) { key ->
                if (key == "codex") Tabs() else Text("THE MAP", Modifier.testTag("map"))
            }
        }
        ui.click("tab1")
        ui.click("to-map")

        ui.pad(GamepadButton.East)

        ui.assertDoesNotExist("map")
        ui.assertText("showing", "SHOWING 1")
    }

    @Test
    fun `escape leaves the map the same way`() {
        val ui = open {
            Game(listOf("codex", "map")) { key ->
                if (key == "codex") Tabs() else Text("THE MAP", Modifier.testTag("map"))
            }
        }
        ui.click("tab2")
        ui.click("to-map")

        ui.key(Key.Escape)

        ui.assertText("showing", "SHOWING 2")
    }

    // --- a half-typed name ---------------------------------------------------------------------

    @Test
    fun `a half typed name is still in the field after leaving the screen`() {
        val ui = open {
            Game(listOf("character", "map")) { key ->
                if (key == "character") {
                    var name by rememberSaveable { mutableStateOf("") }
                    TextField(name, onValueChange = { name = it }, modifier = Modifier.testTag("name"))
                } else {
                    Text("THE MAP", Modifier.testTag("map"))
                }
            }
        }
        ui.click("name")
        ui.type("Ad")

        ui.click("to-map")
        ui.pad(GamepadButton.East)
        ui.assertText("name", "Ad")

        ui.click("name")
        ui.type("a")
        ui.assertText("name", "Ada")
    }

    // --- scrolling -----------------------------------------------------------------------------

    @Test
    fun `an inventory is still scrolled where it was left`() {
        val ui = open {
            Game(listOf("inventory", "map")) { key ->
                if (key == "inventory") {
                    ScrollArea(Modifier.size(200f, 100f).testTag("inventory")) {
                        Column { repeat(40) { Text("ITEM $it", Modifier.testTag("item$it")) } }
                    }
                } else {
                    Text("THE MAP", Modifier.testTag("map"))
                }
            }
        }
        val top = ui.node("item0").boundsInRoot.top
        ui.scroll("inventory", Offset(0f, 3f))
        val scrolled = ui.node("item0").boundsInRoot.top
        assertEquals(top - 144f, scrolled, "three wheel steps scrolled the rows up")

        ui.click("to-map")
        ui.assertDoesNotExist("item0")
        ui.click("to-inventory")

        assertEquals(scrolled, ui.node("item0").boundsInRoot.top, "came back scrolled")
    }

    @Test
    fun `a lazy list is still scrolled where it was left`() {
        val ui = open {
            Game(listOf("inventory", "map")) { key ->
                if (key == "inventory") {
                    LazyColumn(count = 500, modifier = Modifier.size(200f, 100f).testTag("inventory")) {
                        Box(Modifier.size(200f, 25f).testTag("item$it")) { Text("ITEM $it") }
                    }
                } else {
                    Text("THE MAP", Modifier.testTag("map"))
                }
            }
        }
        val first = ui.texts("inventory").first()
        ui.scroll("inventory", Offset(0f, 50f))
        val scrolled = ui.texts("inventory")
        assertNotEquals(first, scrolled.first(), "the wheel moved the list")

        ui.click("to-map")
        ui.click("to-inventory")

        assertEquals(scrolled, ui.texts("inventory"), "the same rows are showing")
    }

    // --- holders inside holders ----------------------------------------------------------------

    @Test
    fun `a screen inside a screen keeps its pages when the outer screen comes back`() {
        val ui = open {
            Game(listOf("journal", "map")) { key ->
                if (key == "journal") {
                    var page by rememberSaveable { mutableStateOf("quests") }
                    Column {
                        Row {
                            Button("QUESTS", onClick = { page = "quests" }, modifier = Modifier.testTag("quests"))
                            Button("NOTES", onClick = { page = "notes" }, modifier = Modifier.testTag("notes"))
                        }
                        SaveableStateHolder(page) { inner -> Tabs(prefix = "$inner-") }
                    }
                } else {
                    Text("THE MAP", Modifier.testTag("map"))
                }
            }
        }
        ui.click("quests-tab1")
        ui.click("notes")
        ui.click("notes-tab2")

        ui.click("to-map")
        ui.click("to-journal")

        ui.assertText("notes-showing", "SHOWING 2")
        ui.click("quests")
        ui.assertText("quests-showing", "SHOWING 1")
    }

    // --- forgetting ----------------------------------------------------------------------------

    @Test
    fun `removeState forgets a screen that is away`() {
        val ui = open {
            val holder = rememberSaveableStateHolder()
            var current by remember { mutableStateOf("chest") }
            Column {
                Button("CHEST", onClick = { current = "chest" }, modifier = Modifier.testTag("to-chest"))
                Button("MAP", onClick = { current = "map" }, modifier = Modifier.testTag("to-map"))
                Button("EMPTY", onClick = { holder.removeState("chest") }, modifier = Modifier.testTag("empty"))
                holder.SaveableStateProvider(current) { if (current == "chest") Tabs() else Text("THE MAP") }
            }
        }
        ui.click("tab2")
        ui.click("to-map")

        ui.click("empty")
        ui.click("to-chest")

        ui.assertText("showing", "SHOWING 0")
    }

    @Test
    fun `removeState on the screen that is showing forgets it once it leaves`() {
        val ui = open {
            val holder = rememberSaveableStateHolder()
            var current by remember { mutableStateOf("chest") }
            Column {
                Button("CHEST", onClick = { current = "chest" }, modifier = Modifier.testTag("to-chest"))
                Button("MAP", onClick = { current = "map" }, modifier = Modifier.testTag("to-map"))
                Button("EMPTY", onClick = { holder.removeState("chest") }, modifier = Modifier.testTag("empty"))
                holder.SaveableStateProvider(current) { if (current == "chest") Tabs() else Text("THE MAP") }
            }
        }
        ui.click("tab2")
        ui.click("empty")
        ui.assertText("showing", "SHOWING 2")

        ui.click("to-map")
        ui.click("to-chest")

        ui.assertText("showing", "SHOWING 0")
    }

    // --- keys and inputs -----------------------------------------------------------------------

    @Test
    fun `rows built in a loop each get their own value back`() {
        val ui = open {
            Game(listOf("party", "map")) { key ->
                if (key == "party") {
                    Column {
                        repeat(3) { row ->
                            var level by rememberSaveable { mutableStateOf(1) }
                            Button("LEVEL $level", onClick = { level++ }, modifier = Modifier.testTag("member$row"))
                        }
                    }
                } else {
                    Text("THE MAP")
                }
            }
        }
        ui.click("member0")
        ui.click("member2")
        ui.click("member2")

        ui.click("to-map")
        ui.click("to-party")

        ui.assertText("member0", "LEVEL 2")
        ui.assertText("member1", "LEVEL 1")
        ui.assertText("member2", "LEVEL 3")
    }

    @Test
    fun `an explicit key finds the value from a different place in the code`() {
        val ui = open {
            var wide by remember { mutableStateOf(true) }
            Column {
                Button("LAYOUT", onClick = { wide = !wide }, modifier = Modifier.testTag("layout"))
                Game(listOf("character", "map")) { key ->
                    if (key == "character") {
                        // The same field in one of two layouts, so where the call sits changes.
                        if (wide) {
                            Row { NameField() }
                        } else {
                            Column { Text("NARROW"); NameField() }
                        }
                    } else {
                        Text("THE MAP")
                    }
                }
            }
        }
        ui.click("name")
        ui.type("Ada")

        ui.click("to-map")
        ui.click("layout")
        ui.click("to-character")

        ui.assertText("name", "Ada")
    }

    @Composable
    private fun NameField() {
        var name by rememberSaveable(key = "name") { mutableStateOf("") }
        TextField(name, onValueChange = { name = it }, modifier = Modifier.testTag("name"))
    }

    @Test
    fun `a value whose inputs changed starts again`() {
        val ui = open {
            var slot by remember { mutableStateOf(0) }
            Column {
                Button("NEXT SLOT", onClick = { slot++ }, modifier = Modifier.testTag("slot"))
                var picks by rememberSaveable(slot) { mutableStateOf(0) }
                Button("PICK $picks", onClick = { picks++ }, modifier = Modifier.testTag("pick"))
            }
        }
        ui.click("pick")
        ui.click("pick")
        ui.assertText("pick", "PICK 2")

        ui.click("slot")

        ui.assertText("pick", "PICK 0")
    }

    /** A chest screen whose picks belong to a slot chosen outside it, and a map to go to. */
    @Composable
    private fun SlotGame() {
        var slot by remember { mutableStateOf(0) }
        Column {
            Button("NEXT SLOT", onClick = { slot++ }, modifier = Modifier.testTag("slot"))
            Game(listOf("chest", "map")) { key ->
                if (key == "chest") {
                    var picks by rememberSaveable(slot) { mutableStateOf(0) }
                    Button("PICK $picks", onClick = { picks++ }, modifier = Modifier.testTag("pick"))
                } else {
                    Text("THE MAP")
                }
            }
        }
    }

    @Test
    fun `a value whose inputs changed while its screen was away starts again`() {
        val ui = open { SlotGame() }
        ui.click("pick")
        ui.click("pick")

        ui.click("to-map")
        ui.click("slot")
        ui.click("to-chest")

        ui.assertText("pick", "PICK 0")
    }

    @Test
    fun `a value started again for new inputs is the one kept when the screen leaves`() {
        val ui = open { SlotGame() }
        ui.click("pick")
        ui.click("slot")
        ui.click("pick")
        ui.assertText("pick", "PICK 1")

        ui.click("to-map")
        ui.click("to-chest")

        ui.assertText("pick", "PICK 1")
    }

    @Test
    fun `outside a holder it is remember and a screen that leaves starts again`() {
        val ui = open {
            var open by remember { mutableStateOf(true) }
            Column {
                Button("TOGGLE", onClick = { open = !open }, modifier = Modifier.testTag("toggle"))
                if (open) Tabs()
            }
        }
        ui.click("tab2")

        ui.click("toggle")
        ui.click("toggle")

        ui.assertText("showing", "SHOWING 0")
    }
}
