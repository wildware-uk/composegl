package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.PointerButton
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.input.plus
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An inventory with a menu on each slot, opened by a right-click, a long press, Shift+F10 and a pad
 * button, and read back off the screen: where the menu is, what it did, where focus went.
 */
class ContextMenuTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(
        size: Size = Size(600f, 400f),
        onBack: () -> Unit = {},
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, onBack = onBack, content = content).also { opened += it }

    private val log = mutableListOf<String>()
    private var count by mutableStateOf(5)
    private var sorted by mutableStateOf(false)

    /** The same items in a context menu and, in another test, on a menu bar. */
    private fun MenuScope.slotItems(name: String) {
        Item("&Use") { log += "use $name" }
        Item("S&plit stack", enabled = count > 1) { log += "split $name" }
        CheckItem("&Sorted", checked = sorted) { sorted = it }
        Submenu("&Give to") {
            Item("Ada") { log += "give $name to Ada" }
            Item("Bo") { log += "give $name to Bo" }
        }
        Separator()
        Item("&Drop", shortcut = Modifiers.Primary + Key.D) { log += "drop $name" }
    }

    @Composable
    private fun Inventory(slotAlignment: Alignment = Alignment.TopStart, enabled: Boolean = true) {
        PopupHost {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(slotAlignment)) {
                    Button(
                        "SWORD",
                        onClick = { log += "click sword" },
                        modifier = Modifier.size(120f, 60f).testTag("sword").contextMenu(enabled = enabled) { slotItems("sword") },
                        initialFocus = true,
                    )
                    Box(Modifier.size(120f, 60f).focusable().testTag("shield").contextMenu { slotItems("shield") })
                }
                Button("BACK", onClick = { log += "back" }, modifier = Modifier.align(Alignment.BottomEnd).testTag("back"))
            }
        }
    }

    // --- opening -------------------------------------------------------------------------------

    @Test
    fun `a right-click opens the menu at the pointer`() {
        val ui = open { Inventory() }
        val at = ui.node("shield").boundsInRoot.centre

        ui.click(at, PointerButton.Secondary)

        assertEquals(1, ui.openMenus().size)
        val menu = ui.openMenus()[0].boundsInRoot
        assertEquals(at.x, menu.left, 0.5f, "its corner is at the pointer")
        assertEquals(at.y, menu.top, 0.5f)
        assertTrue("Ctrl+D" in ui.textsOf(ui.row("Drop")), "the shortcut is shown")
    }

    @Test
    fun `choosing an item closes the menu and runs it`() {
        val ui = open { Inventory() }
        ui.click(ui.node("shield").boundsInRoot.centre, PointerButton.Secondary)

        ui.clickRow("Use")

        assertEquals(listOf("use shield"), log)
        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `a right-click on a button opens its menu and does not click it`() {
        val ui = open { Inventory() }

        ui.click(ui.node("sword").boundsInRoot.centre, PointerButton.Secondary)

        assertEquals(1, ui.openMenus().size)
        assertEquals(emptyList(), log, "the right press was the menu's, not a click")
        ui.key(Key.Escape)
        ui.click("sword")
        assertEquals(listOf("click sword"), log, "a left click is still a click")
    }

    @Test
    fun `a right-click on a plain button inside a box with a menu opens the box's menu`() {
        val ui = open {
            PopupHost {
                Box(Modifier.size(300f, 200f).testTag("panel").contextMenu { Item("Rename") { log += "rename" } }) {
                    Button("OK", onClick = { log += "ok" }, modifier = Modifier.testTag("ok"))
                }
            }
        }

        ui.click(ui.node("ok").boundsInRoot.centre, PointerButton.Secondary)

        assertTrue(ui.hasRow("Rename"), "the panel's menu opened")
        assertEquals(emptyList(), log)
    }

    @Test
    fun `a long press on a plain button inside a box with a menu opens the box's menu`() {
        val ui = open {
            PopupHost {
                Box(Modifier.size(300f, 200f).testTag("panel").contextMenu { Item("Rename") { log += "rename" } }) {
                    Button("OK", onClick = { log += "ok" }, modifier = Modifier.testTag("ok"))
                }
            }
        }
        val at = ui.node("ok").boundsInRoot.centre

        ui.press(at)
        ui.advanceBy(1500)
        ui.release()

        assertTrue(ui.hasRow("Rename"), "the panel's menu opened")
        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(at.x, menu.left, 0.5f, "under the finger")
        assertEquals(at.y, menu.top, 0.5f)
        assertEquals(emptyList(), log, "and the hold swallowed the button's click")
    }

    @Test
    fun `a button's own long press still wins over the menu round it`() {
        val ui = open {
            PopupHost {
                Box(Modifier.size(300f, 200f).contextMenu { Item("Rename") { log += "rename" } }) {
                    Box(
                        Modifier.size(80f).testTag("slot")
                            .clickable(onLongPress = { log += "inspect" }) { log += "tap" },
                    )
                }
            }
        }

        ui.press(ui.node("slot").boundsInRoot.centre)
        ui.advanceBy(1500)
        ui.release()

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(listOf("inspect"), log)
    }

    @Test
    fun `dragging a slider inside a box with a menu and holding still does not open the menu`() {
        var volume by mutableStateOf(0f)
        val ui = open {
            PopupHost {
                Box(Modifier.size(400f, 200f).contextMenu { Item("Rename") { log += "rename" } }) {
                    Slider(volume, { volume = it }, Modifier.testTag("volume"), length = 300f)
                }
            }
        }
        val track = ui.node("volume").boundsInRoot

        ui.press(Offset(track.left + 10f, track.centre.y))
        ui.dragTo(track.centre)
        ui.advanceBy(1500)

        assertEquals(emptyList(), ui.openMenus(), "a drag held still is still a drag")
        ui.dragTo(Offset(track.right - 10f, track.centre.y))
        ui.release()
        assertTrue(volume > 0.8f, "the thumb followed the whole way: $volume")
        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `holding enter on a button inside a menu region is still a click`() {
        val ui = open { Inventory() }
        ui.assertFocused("sword")

        ui.keyDown(Key.Enter)
        ui.advanceBy(1500)
        ui.keyUp(Key.Enter)

        assertEquals(emptyList(), ui.openMenus(), "shift f10 is the keyboard's way in")
        assertEquals(listOf("click sword"), log)
    }

    @Test
    fun `holding south on a button inside a menu region is still a click`() {
        val ui = open { Inventory() }
        ui.assertFocused("sword")

        ui.padDown(GamepadButton.South)
        ui.advanceBy(1500)
        ui.padUp(GamepadButton.South)

        assertEquals(emptyList(), ui.openMenus(), "the menu's pad button is the pad's way in")
        assertEquals(listOf("click sword"), log)
    }

    @Test
    fun `a box with a menu inside a clickable card leaves the card its click`() {
        val ui = open {
            PopupHost {
                Box(Modifier.size(300f, 200f).testTag("card").clickable { log += "card" }) {
                    Box(Modifier.size(100f).testTag("badge").contextMenu { Item("Rename") { log += "rename" } })
                }
            }
        }
        val badge = ui.node("badge").boundsInRoot.centre

        ui.click(badge)
        assertEquals(listOf("card"), log, "a short press on the badge is the card's click")
        assertEquals(emptyList(), ui.openMenus())

        ui.press(badge)
        ui.advanceBy(1500)
        ui.release()
        assertTrue(ui.hasRow("Rename"), "and a hold on it still opens the badge's menu")
        assertEquals(listOf("card"), log, "without clicking the card as well")
    }

    @Test
    fun `a press on a box with a menu and nothing round it goes nowhere without a popup host`() {
        var background = 0
        val ui = open {
            val handler = androidx.compose.runtime.remember {
                PointerHandler { event -> (event is PointerEvent.Press).also { if (it) background++ } }
            }
            Box(Modifier.fillMaxSize().onPointer(handler)) {
                Box(Modifier.size(100f).testTag("lonely").contextMenu { Item("Use") { log += "use" } })
            }
        }

        ui.click(ui.node("lonely").boundsInRoot.centre)

        assertEquals(1, background, "the press went on to the handler round it")
    }

    @Test
    fun `something that takes the right button for itself keeps it`() {
        val ui = open {
            PopupHost {
                Box(Modifier.size(300f, 200f).contextMenu { Item("Rename") { log += "rename" } }) {
                    val map = androidx.compose.runtime.remember {
                        PointerHandler { event ->
                            if (event is PointerEvent.Press && event.button == PointerButton.Secondary) {
                                log += "move unit"
                                true
                            } else {
                                false
                            }
                        }
                    }
                    Box(Modifier.size(100f).testTag("map").onPointer(map))
                }
            }
        }

        ui.click(ui.node("map").boundsInRoot.centre, PointerButton.Secondary)

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(listOf("move unit"), log)
    }

    @Test
    fun `a long press opens the menu and the release is not a click`() {
        val ui = open { Inventory() }
        val at = ui.node("sword").boundsInRoot.centre

        ui.press(at)
        ui.advanceBy(700)
        assertEquals(1, ui.openMenus().size, "held long enough, the menu opened")
        val menu = ui.openMenus()[0].boundsInRoot
        assertEquals(at.x, menu.left, 0.5f, "under the finger")
        ui.release()

        assertEquals(emptyList(), log, "letting go after the hold did not click the sword")
        assertEquals(1, ui.openMenus().size, "and did not close the menu")
    }

    @Test
    fun `a short press is still a click`() {
        val ui = open { Inventory() }

        ui.click("sword")

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(listOf("click sword"), log)
    }

    @Test
    fun `shift f10 opens the menu under the focused widget`() {
        val ui = open { Inventory() }
        ui.assertFocused("sword")
        val sword = ui.node("sword").boundsInRoot

        ui.key(Key.F10, Modifiers.Shift)

        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(sword.left, menu.left, 0.5f, "at the widget's start edge")
        assertTrue(menu.top >= sword.bottom, "and under it: $menu against $sword")
        ui.assertRowFocused("Use")
    }

    @Test
    fun `the pad button opens the menu on the focused widget`() {
        val ui = open { Inventory() }
        ui.pad(GamepadButton.DpadDown)
        ui.assertFocused("shield")

        ui.pad(GamepadButton.North)

        assertEquals(1, ui.openMenus().size)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)
        assertEquals(listOf("split shield"), log)
        ui.assertFocused("shield")
    }

    /** A big panel with the menu, and the focused button deep in its far corner. */
    @Composable
    private fun PanelWithButtonInCorner() {
        PopupHost {
            Box(Modifier.size(500f, 300f).testTag("panel").contextMenu { Item("Rename") { log += "rename" } }) {
                Button("OK", onClick = { log += "ok" }, modifier = Modifier.align(Alignment.BottomEnd).testTag("ok"), initialFocus = true)
            }
        }
    }

    @Test
    fun `shift f10 on a widget inside a panel with a menu opens it under that widget`() {
        val ui = open(Size(800f, 600f)) { PanelWithButtonInCorner() }
        ui.assertFocused("ok")
        val ok = ui.node("ok").boundsInRoot

        ui.key(Key.F10, Modifiers.Shift)

        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(ok.left, menu.left, 0.5f, "at the focused button's start edge, not the panel's")
        assertTrue(menu.top >= ok.bottom, "and under the button: $menu against $ok")
    }

    @Test
    fun `the pad button on a widget inside a panel with a menu opens it under that widget`() {
        val ui = open(Size(800f, 600f)) { PanelWithButtonInCorner() }
        val ok = ui.node("ok").boundsInRoot

        ui.pad(GamepadButton.North)

        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(ok.left, menu.left, 0.5f, "at the focused button's start edge, not the panel's")
        assertTrue(menu.top >= ok.bottom, "and under the button: $menu against $ok")
    }

    /** A dialog over a screen with a menu: inside the menu's node, or drawn over it as a sibling. */
    @Composable
    private fun DialogOverMenuRegion(inside: Boolean) {
        PopupHost {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().testTag("screen").contextMenu { Item("Rename") { log += "rename" } }) {
                    if (inside) ShowDialog()
                }
                if (!inside) ShowDialog()
            }
        }
    }

    @Composable
    private fun ShowDialog() {
        Dialog(onDismiss = { log += "dismiss" }, dismissOnScrim = true) {
            Button("OK", onClick = { log += "ok" }, modifier = Modifier.testTag("ok"), initialFocus = true)
        }
    }

    @Test
    fun `a dialog inside a menu region keeps shift f10 and the pad button to itself`() {
        val ui = open { DialogOverMenuRegion(inside = true) }
        ui.assertFocused("ok")

        ui.key(Key.F10, Modifiers.Shift)
        ui.pad(GamepadButton.North)

        assertEquals(emptyList(), ui.openMenus(), "the menu behind the dialog stayed shut")
        ui.assertFocused("ok")
    }

    @Test
    fun `a right-click on a button in a dialog inside a menu region is the button's`() {
        val ui = open { DialogOverMenuRegion(inside = true) }

        ui.click(ui.node("ok").boundsInRoot.centre, PointerButton.Secondary)

        assertEquals(emptyList(), ui.openMenus())
        assertFalse("dismiss" in log, "the scrim never heard it: $log")
    }

    @Test
    fun `a right-click on a button in a dialog drawn over a menu region is the button's`() {
        val ui = open { DialogOverMenuRegion(inside = false) }

        ui.click(ui.node("ok").boundsInRoot.centre, PointerButton.Secondary)

        assertEquals(emptyList(), ui.openMenus())
        assertFalse("dismiss" in log, "the scrim never heard it: $log")
    }

    @Test
    fun `a right-click on a hud button drawn over a map with a menu is the button's`() {
        val ui = open {
            PopupHost {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("map").contextMenu { Item("Waypoint") { log += "waypoint" } })
                    Button("PAUSE", onClick = { log += "pause" }, modifier = Modifier.align(Alignment.TopEnd).testTag("hud"))
                }
            }
        }

        ui.click(ui.node("hud").boundsInRoot.centre, PointerButton.Secondary)
        assertEquals(emptyList(), ui.openMenus(), "the map's menu did not open under the button")

        ui.click(Offset(20f, 300f), PointerButton.Secondary)
        assertTrue(ui.hasRow("Waypoint"), "the map itself still opens it")
    }

    @Test
    fun `a turned off context menu does not open`() {
        val ui = open { Inventory(enabled = false) }

        ui.click(ui.node("sword").boundsInRoot.centre, PointerButton.Secondary)
        ui.key(Key.F10, Modifiers.Shift)
        ui.pad(GamepadButton.North)

        assertEquals(emptyList(), ui.openMenus())
    }

    // --- closing and focus ---------------------------------------------------------------------

    @Test
    fun `focus is trapped in the open menu`() {
        val ui = open { Inventory() }
        ui.key(Key.F10, Modifiers.Shift)

        repeat(8) { ui.key(Key.Tab) }
        repeat(8) { ui.pad(GamepadButton.DpadDown) }

        assertTrue(ui.focus.focused?.name == "menu.item", "focus never left the menu: ${ui.focus.focused}")
    }

    @Test
    fun `escape closes it and focus goes back`() {
        val ui = open { Inventory() }
        ui.key(Key.F10, Modifiers.Shift)

        ui.key(Key.Escape)

        assertEquals(emptyList(), ui.openMenus())
        ui.assertFocused("sword")
    }

    @Test
    fun `east closes it before back reaches the game`() {
        var leftTheScreen = 0
        val ui = open(onBack = { leftTheScreen++ }) { Inventory() }
        ui.pad(GamepadButton.North)

        ui.pad(GamepadButton.East)

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(0, leftTheScreen)
        ui.assertFocused("sword")
    }

    @Test
    fun `a click outside closes it and presses nothing else`() {
        val ui = open { Inventory() }
        ui.click(ui.node("shield").boundsInRoot.centre, PointerButton.Secondary)

        ui.click("back")

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(emptyList(), log)
    }

    @Test
    fun `the menu closes when the thing it opened on leaves the screen`() {
        var shown by mutableStateOf(true)
        val ui = open {
            PopupHost {
                Box(Modifier.fillMaxSize()) {
                    if (shown) Box(Modifier.size(100f).testTag("chest").contextMenu { Item("Open") { log += "open" } })
                }
            }
        }
        ui.click(ui.node("chest").boundsInRoot.centre, PointerButton.Secondary)
        assertEquals(1, ui.openMenus().size)

        shown = false
        ui.advanceBy(100)

        assertEquals(emptyList(), ui.openMenus(), "a menu about nothing is gone")
    }

    @Test
    fun `an open menu follows what its owner last composed`() {
        val stock = intArrayOf(1)
        var recompose by mutableStateOf(0)
        val ui = open {
            PopupHost {
                Box(Modifier.fillMaxSize()) { StockSlot(stock, { recompose }) }
            }
        }
        ui.click(ui.node("stack").boundsInRoot.centre, PointerButton.Secondary)
        ui.clickRow("Split")
        assertEquals(emptyList(), log, "one in the stack, nothing to split")

        // A plain value, not state: only the slot's own recomposition carries it to the menu.
        stock[0] = 5
        recompose++
        ui.advanceBy(100)
        ui.clickRow("Split")

        assertEquals(listOf("split 5"), log)
    }

    @Composable
    private fun StockSlot(stock: IntArray, read: () -> Int) {
        read()
        val count = stock[0]
        Box(
            Modifier.size(100f).testTag("stack").contextMenu {
                Item("Split", enabled = count > 1) { log += "split $count" }
            },
        )
    }

    @Test
    fun `a submenu opens and its item runs`() {
        val ui = open { Inventory() }
        ui.key(Key.F10, Modifiers.Shift)

        ui.key(Key.G)
        assertEquals(2, ui.openMenus().size)
        ui.key(Key.Down)
        ui.key(Key.Enter)

        assertEquals(listOf("give sword to Bo"), log)
        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `a disabled item reads its state when the menu opens`() {
        count = 1
        val ui = open { Inventory() }
        ui.key(Key.F10, Modifiers.Shift)

        ui.key(Key.P)
        ui.clickRow("Split stack")

        assertEquals(emptyList(), log)
        assertEquals(1, ui.openMenus().size)
    }

    @Test
    fun `an item's icon is drawn in a column before the label`() {
        val ui = open {
            PopupHost {
                Box(
                    Modifier.size(200f).testTag("chest").contextMenu {
                        Item("Open", icon = { Box(Modifier.size(16f).testTag("open icon")) }) { log += "open" }
                        Item("Lock") { log += "lock" }
                    },
                )
            }
        }

        ui.click(ui.node("chest").boundsInRoot.centre, PointerButton.Secondary)

        val icon = ui.node("open icon").boundsInRoot
        val open = ui.row("Open")
        val lock = ui.row("Lock")
        assertTrue(icon.right <= open.children[1].boundsInRoot.left, "the icon is before the label: $icon")
        assertEquals(
            open.children[1].boundsInRoot.left,
            lock.children[1].boundsInRoot.left,
            0.5f,
            "a row with no icon keeps the column, so the labels line up",
        )
    }

    // --- placement -----------------------------------------------------------------------------

    @Test
    fun `near the bottom right corner it flips to stay on the screen`() {
        val ui = open { Inventory(slotAlignment = Alignment.BottomEnd) }
        // Towards the shield's start: the BACK button sits over its end in this corner, and a
        // right-click on that is the button's.
        val shield = ui.node("shield").boundsInRoot
        val at = Offset(shield.left + 10f, shield.centre.y)
        assertTrue(ui.node("back").boundsInRoot.left > at.x, "the point is clear of the BACK button")

        ui.click(at, PointerButton.Secondary)

        val menu = ui.openMenus().single().boundsInRoot
        assertTrue(menu.right <= 600f && menu.bottom <= 400f, "all of it is on screen: $menu")
        assertEquals(at.x, menu.right, 0.5f, "flipped to the left of the pointer")
        assertEquals(at.y, menu.bottom, 0.5f, "and above it")
    }

    @Test
    fun `right to left it hangs to the left of the pointer`() {
        val ui = open {
            // Start is the right-hand side on this screen, so there is room to the left of the slot.
            ProvideLayoutDirection(LayoutDirection.Rtl) { Inventory(slotAlignment = Alignment.TopStart) }
        }
        val at = ui.node("sword").boundsInRoot.centre

        ui.click(at, PointerButton.Secondary)

        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(at.x, menu.right, 0.5f, "its right edge is at the pointer: $menu")
    }

    @Test
    fun `a right-click inside a scaled node opens the menu at the pointer`() {
        val ui = open {
            PopupHost {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.align(Alignment.Centre).size(200f, 100f).scale(2f)
                            .testTag("card").contextMenu { Item("Use") { log += "use" } },
                    )
                }
            }
        }
        val card = ui.node("card").boundsInRoot
        val at = Offset(card.left + card.width * 0.25f, card.top + card.height * 0.25f)

        ui.click(at, PointerButton.Secondary)

        val menu = ui.openMenus().single().boundsInRoot
        assertEquals(at.x, menu.left, 0.5f, "its corner is at the pointer, not where the unscaled card would put it: $menu")
        assertEquals(at.y, menu.top, 0.5f)
    }

    @Test
    fun `without a popup host nothing opens and nothing breaks`() {
        val ui = open {
            Box(Modifier.size(100f).testTag("lonely").contextMenu { Item("Use") { log += "use" } })
        }

        ui.click(ui.node("lonely").boundsInRoot.centre, PointerButton.Secondary)

        assertEquals(emptyList(), ui.openMenus())
    }

    // --- one definition, two menus -------------------------------------------------------------

    @Test
    fun `the same items work in a menu bar and a context menu`() {
        val ui = open {
            PopupHost {
                Column(Modifier.fillMaxSize()) {
                    MenuBar { Menu("&Item") { slotItems("bar") } }
                    Row {
                        Box(Modifier.size(100f).testTag("slot").contextMenu { slotItems("slot") })
                    }
                }
            }
        }

        ui.click(ui.title("Item").boundsInRoot.centre)
        ui.clickRow("Use")
        ui.click(ui.node("slot").boundsInRoot.centre, PointerButton.Secondary)
        ui.clickRow("Use")
        ui.key(Key.D, Modifiers.Control)

        assertEquals(listOf("use bar", "use slot", "drop bar"), log, "the bar fires shortcuts; the context menu only shows them")
        assertFalse(ui.hasRow("Use"))
    }
}
