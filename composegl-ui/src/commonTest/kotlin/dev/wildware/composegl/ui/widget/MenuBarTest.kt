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
import dev.wildware.composegl.ui.input.plus
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A level editor's menu bar, driven by a mouse, a keyboard and a pad.
 *
 * Every test composes the editor for real inside a [PopupHost] and does what a player does: clicks
 * titles, rests the pointer on rows, presses Alt and the arrows, holds a shoulder button. The answers
 * are read off the screen: which menus are open, where they are, and where focus is.
 */
class MenuBarTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        Modifiers.isMac = false
    }

    private fun open(
        size: Size = Size(800f, 600f),
        onBack: () -> Unit = {},
        content: @Composable () -> Unit,
    ): UiTest = uiTest(size, onBack = onBack, content = content).also { opened += it }

    /** What the editor did, in order. */
    private val log = mutableListOf<String>()

    private var dirty by mutableStateOf(true)
    private var grid by mutableStateOf(false)
    private var mode by mutableStateOf(0)

    @Composable
    private fun Editor(
        padButton: GamepadButton? = GamepadButton.Back,
        barModifier: Modifier = Modifier,
        below: @Composable () -> Unit = {},
    ) {
        PopupHost {
            Column(Modifier.fillMaxSize()) {
                MenuBar(barModifier, padButton = padButton) {
                    Menu("&File") {
                        Item("&New", shortcut = Modifiers.Primary + Key.N) { log += "new" }
                        Item("&Save", shortcut = Modifiers.Primary + Key.S, enabled = dirty) { log += "save" }
                        Submenu("&Recent") {
                            Item("Level one") { log += "one" }
                            Item("Level two") { log += "two" }
                        }
                        Separator()
                        Item("&Quit") { log += "quit" }
                    }
                    Menu("&View") {
                        CheckItem("&Grid", checked = grid) { grid = it }
                        Separator()
                        RadioItem("&Wireframe", selected = mode == 0) { mode = 0 }
                        RadioItem("S&haded", selected = mode == 1) { mode = 1 }
                    }
                    Menu("&Help", enabled = false) {
                        Item("About") { log += "about" }
                    }
                }
                Button("PLAY", onClick = { log += "play" }, modifier = Modifier.testTag("play"), initialFocus = true)
                below()
            }
        }
    }

    @Composable
    private fun EditorWithField() = Editor {
        var name by androidx.compose.runtime.remember { mutableStateOf("") }
        TextField(name, { name = it }, Modifier.width(200f).testTag("name"))
    }

    // --- the mouse -----------------------------------------------------------------------------

    @Test
    fun `a click on a title opens its menu under it`() {
        val ui = open { Editor() }
        val file = ui.title("File").boundsInRoot

        ui.click(file.centre)

        assertEquals(1, ui.openMenus().size)
        val menu = ui.openMenus()[0].boundsInRoot
        assertEquals(file.left, menu.left, 1f, "the menu hangs from the title's start edge")
        assertTrue(menu.top >= file.bottom, "and under it: $menu against $file")
        assertTrue(ui.hasRow("New") && ui.hasRow("Quit"))
        assertTrue("Ctrl+N" in ui.textsOf(ui.row("New")), "the shortcut is written beside the item: ${ui.textsOf(ui.row("New"))}")
    }

    @Test
    fun `a second click on the open title closes it`() {
        val ui = open { Editor() }

        ui.click(ui.title("File").boundsInRoot.centre)
        ui.click(ui.title("File").boundsInRoot.centre)

        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `resting on another title while one is open switches to it`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.moveTo(ui.title("View").boundsInRoot.centre)

        assertEquals(1, ui.openMenus().size)
        assertTrue(ui.hasRow("Grid"), "the View menu is the one open now")
        assertFalse(ui.hasRow("New"))
    }

    @Test
    fun `hovering the titles with nothing open opens nothing`() {
        val ui = open { Editor() }

        ui.moveTo(ui.title("View").boundsInRoot.centre)

        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `choosing an item runs it closes the menu and leaves focus where it was`() {
        val ui = open { Editor() }
        ui.assertFocused("play")
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.clickRow("New")

        assertEquals(listOf("new"), log)
        assertEquals(emptyList(), ui.openMenus())
        ui.assertFocused("play")
    }

    @Test
    fun `a press outside closes the menu and presses nothing else`() {
        val ui = open {
            Editor {
                Box(Modifier.fillMaxWidth()) {
                    Button("BACK", onClick = { log += "back" }, modifier = Modifier.align(Alignment.TopEnd).testTag("back"))
                }
            }
        }
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.click("back")

        assertEquals(emptyList(), ui.openMenus())
        assertEquals(emptyList(), log, "the press that closed the menu did not press BACK")
        ui.click("back")
        assertEquals(listOf("back"), log, "with the menu gone it can be pressed")
    }

    @Test
    fun `a disabled item cannot be chosen and the menu stays open`() {
        dirty = false
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.clickRow("Save")

        assertEquals(emptyList(), log)
        assertEquals(1, ui.openMenus().size)
    }

    @Test
    fun `a disabled menu does not open`() {
        val ui = open { Editor() }

        ui.click(ui.title("Help").boundsInRoot.centre)

        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `a check item ticks and a radio item moves the dot`() {
        val ui = open { Editor() }
        ui.click(ui.title("View").boundsInRoot.centre)
        assertFalse(ui.row("Grid").marked(), "not ticked yet")
        assertTrue(ui.row("Wireframe").marked())
        assertFalse(ui.row("Shaded").marked())

        ui.clickRow("Grid")
        ui.click(ui.title("View").boundsInRoot.centre)
        ui.clickRow("Shaded")
        ui.click(ui.title("View").boundsInRoot.centre)

        assertTrue(grid)
        assertEquals(1, mode)
        assertTrue(ui.row("Grid").marked(), "ticked")
        assertFalse(ui.row("Wireframe").marked())
        assertTrue(ui.row("Shaded").marked(), "the dot moved")
    }

    /** Whether a row's first column holds a tick or a dot. */
    private fun dev.wildware.composegl.ui.node.UiNode.marked(): Boolean = children[0].children.isNotEmpty()

    @Test
    fun `shortcuts line up in a column at the right of the menu`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)

        val new = ui.row("New").boundsInRoot
        val quit = ui.row("Quit").boundsInRoot
        assertEquals(new.width, quit.width, "every row is as wide as the menu")
        val newShortcut = ui.row("New").children[1].boundsInRoot
        val saveShortcut = ui.row("Save").children[1].boundsInRoot
        assertEquals(newShortcut.right, saveShortcut.right, 0.5f, "shortcuts end at the same place")
        assertTrue(newShortcut.left > ui.row("New").children[0].boundsInRoot.right, "after the label")
    }

    // --- submenus ------------------------------------------------------------------------------

    @Test
    fun `resting on a submenu row opens it beside the row`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.moveTo(ui.row("Recent").boundsInRoot.centre)
        ui.advanceBy(400)

        assertEquals(2, ui.openMenus().size, "the submenu opened")
        val parent = ui.openMenus()[0].boundsInRoot
        val sub = ui.openMenus()[1].boundsInRoot
        // Over the menu's own padding by a few pixels, so its first row sits level with the row it came from.
        assertTrue(sub.left >= parent.right - 8f && sub.left > parent.centre.x, "beside the menu, on the right: $sub against $parent")
        assertEquals(ui.row("Recent").boundsInRoot.top, sub.top, 1f, "level with its row")

        ui.clickRow("Level two")
        assertEquals(listOf("two"), log)
        assertEquals(emptyList(), ui.openMenus(), "choosing inside a submenu closes every level")
    }

    @Test
    fun `a diagonal move towards the open submenu does not close it on the way`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)
        ui.moveTo(ui.row("Recent").boundsInRoot.centre)
        ui.advanceBy(400)
        assertEquals(2, ui.openMenus().size)

        // Down and to the right, over the Quit row, heading for Level two.
        val quit = ui.row("Quit").boundsInRoot
        ui.moveTo(Offset(quit.right - 3f, quit.top + 2f))
        ui.advanceBy(60)
        assertEquals(2, ui.openMenus().size, "crossing Quit on the way did not close the submenu")

        ui.clickRow("Level two")
        assertEquals(listOf("two"), log)
    }

    @Test
    fun `moving away from the submenu closes it`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)
        ui.moveTo(ui.row("Recent").boundsInRoot.centre)
        ui.advanceBy(400)

        ui.moveTo(ui.row("New").boundsInRoot.centre)

        assertEquals(1, ui.openMenus().size, "up and away is not towards the submenu")
    }

    @Test
    fun `resting on the diagonal gives up and switches to the row under the pointer`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)
        ui.moveTo(ui.row("Recent").boundsInRoot.centre)
        ui.advanceBy(400)
        val quit = ui.row("Quit").boundsInRoot

        ui.moveTo(Offset(quit.right - 3f, quit.top + 2f))
        ui.advanceBy(600)

        assertEquals(1, ui.openMenus().size, "the pointer stopped on Quit, so Quit is what it wants")
    }

    @Test
    fun `a submenu with no room on the right opens on the left`() {
        // The titles start 300 in, so File's menu ends near the right edge of a 500-wide screen.
        val ui = open(size = Size(500f, 400f)) { Editor(barModifier = Modifier.padding(left = 300f)) }
        ui.click(ui.title("File").boundsInRoot.centre)
        ui.moveTo(ui.row("Recent").boundsInRoot.centre)
        ui.advanceBy(400)

        val parent = ui.openMenus()[0].boundsInRoot
        val sub = ui.openMenus()[1].boundsInRoot
        assertTrue(sub.right <= 500f, "all of it is on screen: $sub")
        assertTrue(sub.right <= parent.left + 8f && sub.left >= 0f, "flipped to the other side: $sub against $parent")
    }

    // --- the keyboard --------------------------------------------------------------------------

    @Test
    fun `alt on its own puts focus on the bar and alt again gives it back`() {
        val ui = open { Editor() }

        ui.key(Key.Alt)
        ui.assertTitleFocused("File")

        ui.key(Key.Alt)
        ui.assertFocused("play")
    }

    @Test
    fun `a click below the bar while it has focus lets go and the field gets focus`() {
        val ui = open { EditorWithField() }

        ui.key(Key.F10)
        ui.assertTitleFocused("File")
        ui.click("name")
        ui.type("abc")

        ui.assertFocused("name")
        assertEquals("abc", ui.text("name"))
        ui.key(Key.Right)
        ui.assertFocused("name")
    }

    @Test
    fun `a click on a button below the bar after alt presses it and leaves focus there`() {
        val ui = open { Editor() }

        ui.key(Key.Alt)
        ui.assertTitleFocused("File")
        ui.click("play")

        assertEquals(listOf("play"), log)
        ui.assertFocused("play")
        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `alt with a key a text field takes does not reach the bar when alt comes up`() {
        val ui = open { EditorWithField() }
        ui.click("name")
        ui.type("abc def")

        ui.keyDown(Key.Alt, Modifiers.Alt)
        ui.key(Key.Left, Modifiers.Alt)
        ui.keyUp(Key.Alt)

        ui.assertFocused("name")
        ui.type("x")
        assertTrue("x" in ui.text("name"), "typing still goes to the field: ${ui.text("name")}")
    }

    @Test
    fun `alt held for a click does not reach the bar when alt comes up`() {
        val ui = open { Editor() }

        ui.keyDown(Key.Alt, Modifiers.Alt)
        ui.click("play")
        ui.keyUp(Key.Alt)

        ui.assertFocused("play")
        assertEquals(listOf("play"), log)
        ui.key(Key.Alt)
        ui.assertTitleFocused("File")
    }

    @Test
    fun `f10 reaches the bar the arrows move along it and down opens`() {
        val ui = open { Editor() }

        ui.key(Key.F10)
        ui.assertTitleFocused("File")
        ui.key(Key.Right)
        ui.assertTitleFocused("View")
        ui.key(Key.Right)
        ui.assertTitleFocused("File")

        ui.key(Key.Left)
        ui.assertTitleFocused("View")
        ui.key(Key.Down)
        ui.assertRowFocused("Grid")

        ui.key(Key.Enter)
        assertTrue(grid)
        assertEquals(emptyList(), ui.openMenus())
        ui.assertFocused("play")
    }

    @Test
    fun `alt and a letter opens that menu from anywhere and a letter chooses`() {
        val ui = open { Editor() }

        ui.key(Key.F, Modifiers.Alt)
        assertTrue(ui.hasRow("New"))
        ui.assertRowFocused("New")

        ui.key(Key.Q)
        assertEquals(listOf("quit"), log)
        ui.assertFocused("play")
    }

    @Test
    fun `escape closes one level at a time`() {
        val ui = open { Editor() }
        ui.key(Key.F, Modifiers.Alt)
        ui.key(Key.Down)
        ui.key(Key.Down)
        ui.assertRowFocused("Recent")
        ui.key(Key.Right)
        ui.assertRowFocused("Level one")

        ui.key(Key.Escape)
        assertEquals(1, ui.openMenus().size)
        ui.assertRowFocused("Recent")

        ui.key(Key.Left)
        assertEquals(1, ui.openMenus().size, "Left on a top menu moves to the menu before it rather than closing")
        assertTrue(ui.hasRow("Grid"), "which, coming round past the disabled Help, is View")
    }

    @Test
    fun `escape from a menu goes back to its title and then off the bar`() {
        val ui = open { Editor() }
        ui.key(Key.F, Modifiers.Alt)

        ui.key(Key.Escape)
        assertEquals(emptyList(), ui.openMenus())
        ui.assertTitleFocused("File")

        ui.key(Key.Escape)
        ui.assertFocused("play")
    }

    @Test
    fun `left and right in an open menu switch to the menu beside it`() {
        val ui = open { Editor() }
        ui.key(Key.F, Modifiers.Alt)

        ui.key(Key.Right)
        assertTrue(ui.hasRow("Grid"), "Right on New, which has no submenu, moved to View")
        ui.key(Key.Right)
        assertTrue(ui.hasRow("New"), "Help is disabled, so Right came round to File")

        ui.key(Key.Escape)
        ui.assertTitleFocused("File")
    }

    @Test
    fun `left closes a submenu and right opens it`() {
        val ui = open { Editor() }
        ui.key(Key.F, Modifiers.Alt)
        ui.key(Key.R)
        assertEquals(2, ui.openMenus().size, "R opened Recent")
        ui.assertRowFocused("Level one")

        ui.key(Key.Left)
        assertEquals(1, ui.openMenus().size)
        ui.assertRowFocused("Recent")
    }

    @Test
    fun `a disabled item is skipped by the arrows`() {
        dirty = false
        val ui = open { Editor() }
        ui.key(Key.F, Modifiers.Alt)
        ui.assertRowFocused("New")

        ui.key(Key.Down)

        ui.assertRowFocused("Recent")
    }

    @Test
    fun `letters are underlined only while the keyboard is driving the bar`() {
        val ui = open { Editor() }
        ui.click(ui.title("File").boundsInRoot.centre)
        assertEquals(listOf("File"), ui.textsOf(ui.title("File")), "opened with the mouse, nothing is marked")
        assertEquals(0, ui.underlines(ui.title("File")))
        ui.click(ui.title("File").boundsInRoot.centre)

        ui.key(Key.F, Modifiers.Alt)

        assertEquals(listOf("F", "ile"), ui.textsOf(ui.title("File")), "the F is drawn as a piece of its own")
        assertEquals(1, ui.underlines(ui.title("File")), "with a line under it")
        assertEquals(1, ui.underlines(ui.row("New")), "and so is the N in the open menu")
    }

    /** The thin lines drawn under letters in [node]: rectangles no more than two pixels tall. */
    private fun UiTest.underlines(node: dev.wildware.composegl.ui.node.UiNode): Int =
        drawingOf(node).calls.filterIsInstance<dev.wildware.composegl.ui.graphics.DrawCall.Rectangle>()
            .count { it.rect.height in 0.5f..2f && it.rect.width < 20f }

    // --- shortcuts -----------------------------------------------------------------------------

    @Test
    fun `a shortcut fires with every menu closed and focus somewhere else`() {
        val ui = open { Editor() }
        ui.assertFocused("play")

        ui.key(Key.S, Modifiers.Control)
        ui.key(Key.N, Modifiers.Control)

        assertEquals(listOf("save", "new"), log)
        assertEquals(emptyList(), ui.openMenus())
    }

    @Test
    fun `a shortcut fires from inside a text field that does not want it`() {
        val ui = open {
            Editor {
                var name by androidx.compose.runtime.remember { mutableStateOf("") }
                TextField(name, { name = it }, Modifier.width(200f).testTag("name"))
            }
        }
        ui.click("name")
        ui.type("abc")

        ui.key(Key.S, Modifiers.Control)

        assertEquals(listOf("save"), log)
    }

    @Test
    fun `a disabled item's shortcut does nothing`() {
        dirty = false
        val ui = open { Editor() }

        ui.key(Key.S, Modifiers.Control)

        assertEquals(emptyList(), log)
    }

    @Test
    fun `the modifiers must match exactly`() {
        val ui = open { Editor() }

        ui.key(Key.S, Modifiers.Control + Modifiers.Shift)
        ui.key(Key.S)

        assertEquals(emptyList(), log)
    }

    @Test
    fun `a shortcut does not fire through a dialogue`() {
        var asking by mutableStateOf(true)
        val ui = open {
            Editor {
                if (asking) {
                    Dialog(onDismiss = { asking = false }) { Button("OK", onClick = { asking = false }) }
                }
            }
        }

        ui.key(Key.S, Modifiers.Control)

        assertEquals(emptyList(), log)
    }

    @Test
    fun `on a mac the shortcut is command and is written so`() {
        Modifiers.isMac = true
        val ui = open { Editor() }

        ui.key(Key.S, Modifiers.Meta)
        ui.key(Key.S, Modifiers.Control)
        assertEquals(listOf("save"), log, "Command+S saves and Control+S does not")

        ui.click(ui.title("File").boundsInRoot.centre)
        assertTrue("Cmd+S" in ui.textsOf(ui.row("Save")), "${ui.textsOf(ui.row("Save"))}")
    }

    // --- the pad -------------------------------------------------------------------------------

    @Test
    fun `the pad button reaches the bar the shoulders move and south opens`() {
        val ui = open { Editor() }

        ui.pad(GamepadButton.Back)
        ui.assertTitleFocused("File")
        ui.pad(GamepadButton.RightBumper)
        ui.assertTitleFocused("View")

        ui.pad(GamepadButton.South)
        ui.assertRowFocused("Grid")
        ui.pad(GamepadButton.DpadDown)
        ui.assertRowFocused("Wireframe")

        ui.pad(GamepadButton.LeftBumper)
        assertTrue(ui.hasRow("New"), "the left shoulder switched the open menu to File")

        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.South)
        assertEquals(listOf("save"), log)
        ui.assertFocused("play")
    }

    @Test
    fun `east closes one level at a time and then leaves the bar`() {
        var leftTheScreen = 0
        val ui = open(onBack = { leftTheScreen++ }) { Editor() }
        ui.pad(GamepadButton.Back)
        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.DpadDown)
        ui.pad(GamepadButton.DpadRight)
        assertEquals(2, ui.openMenus().size)

        ui.pad(GamepadButton.East)
        assertEquals(1, ui.openMenus().size)
        ui.pad(GamepadButton.East)
        assertEquals(0, ui.openMenus().size)
        ui.assertTitleFocused("File")
        ui.pad(GamepadButton.East)
        ui.assertFocused("play")
        assertEquals(0, leftTheScreen, "every one of those was the menu's")

        ui.pad(GamepadButton.East)
        assertEquals(1, leftTheScreen)
    }

    @Test
    fun `a bar with no pad button is out of a pad's reach`() {
        val ui = open { Editor(padButton = null) }

        ui.pad(GamepadButton.Back)
        ui.pad(GamepadButton.DpadUp)

        ui.assertFocused("play")
    }

    // --- right to left -------------------------------------------------------------------------

    @Test
    fun `right to left the bar reads from the right and submenus open to the left`() {
        val ui = open {
            ProvideLayoutDirection(LayoutDirection.Rtl) { Editor() }
        }
        val file = ui.title("File").boundsInRoot
        val view = ui.title("View").boundsInRoot
        assertTrue(file.left > view.left, "File is first, so rightmost: $file and $view")

        ui.key(Key.F, Modifiers.Alt)
        val menu = ui.openMenus()[0].boundsInRoot
        assertEquals(file.right, menu.right, 1f, "the menu hangs from the title's right edge")

        ui.key(Key.Down)
        ui.key(Key.Down)
        ui.key(Key.Right)
        assertEquals(1, ui.openMenus().size, "Right points out of a submenu on this screen, so nothing opened")
        ui.key(Key.Escape)
        ui.key(Key.F, Modifiers.Alt)
        ui.key(Key.Down)
        ui.key(Key.Down)
        ui.key(Key.Left)
        assertEquals(2, ui.openMenus().size, "Left opened Recent")
        val sub = ui.openMenus()[1].boundsInRoot
        val parent = ui.openMenus()[0].boundsInRoot
        assertTrue(sub.right <= parent.left + 8f && sub.right < parent.centre.x, "to the left of its menu: $sub against $parent")
    }

    // --- the skin ------------------------------------------------------------------------------

    @Test
    fun `both shipped skins draw every part of a menu`() {
        val parts = listOf(
            "menubar", "menubar.title", "menubar.title.open", "menu", "menu.item", "menu.item.open",
            "menu.shortcut", "menu.separator", "menu.check", "menu.radio",
        )
        parts.forEach { part ->
            assertTrue(Skin.Default.has(part), "the default skin has no $part")
            assertTrue(Skin.HighContrast.has(part), "the high contrast skin has no $part")
        }
    }
}

