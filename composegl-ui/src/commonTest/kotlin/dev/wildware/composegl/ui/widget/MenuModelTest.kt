package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.plus
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.focusTrap
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The parts of a menu that are not on a screen: labels, the scope, the safe triangle, the focus stack. */
class MenuModelTest {

    @Test
    fun `an ampersand marks the letter and a doubled one is an ampersand`() {
        assertEquals(Mnemonic("File", 0), Mnemonic.of("&File"))
        assertEquals(Mnemonic("Save As", 5), Mnemonic.of("Save &As"))
        assertEquals(Mnemonic("Fish & Chips", 7), Mnemonic.of("Fish && &Chips"))
        assertEquals(Mnemonic("Plain", -1), Mnemonic.of("Plain"))
        assertEquals(Mnemonic("Trailing&", -1), Mnemonic.of("Trailing&"))
        assertEquals(Mnemonic("Fichier", 0), Mnemonic.of("&Fichier"), "a translation marks its own letter")
    }

    @Test
    fun `the marked letter matches its key whatever the case`() {
        val save = Mnemonic.of("&save")
        assertTrue(save.matches(Key.S))
        assertFalse(save.matches(Key.A))
        assertTrue(Mnemonic.of("Level &2").matches(Key.Digit2))
        assertFalse(Mnemonic.of("Plain").matches(Key.P), "no mark, no letter")
    }

    @Test
    fun `the scope builds entries in order and a submenu's shortcut still fires`() {
        val fired = mutableListOf<String>()
        val entries = buildMenu {
            Item("New", shortcut = Modifiers.Control + Key.N) { fired += "new" }
            Separator()
            Submenu("More") {
                Item("Deep", shortcut = Modifiers.Control + Key.D) { fired += "deep" }
                Item("Off", shortcut = Modifiers.Control + Key.O, enabled = false) { fired += "off" }
            }
            Submenu("Closed", enabled = false) {
                Item("Hidden", shortcut = Modifiers.Control + Key.H) { fired += "hidden" }
            }
        }

        assertEquals(4, entries.size)
        assertTrue(entries[1] is MenuEntry.Separator)
        assertTrue(entries.fireShortcut(KeyEvent(Key.D, KeyEventType.Down, Modifiers.Control)))
        assertFalse(entries.fireShortcut(KeyEvent(Key.O, KeyEventType.Down, Modifiers.Control)), "disabled item")
        assertFalse(entries.fireShortcut(KeyEvent(Key.H, KeyEventType.Down, Modifiers.Control)), "inside a disabled submenu")
        assertEquals(listOf("deep"), fired)
    }

    @Test
    fun `a check item hands back the other value and a radio item selects`() {
        var checked = false
        var chosen = ""
        val entries = buildMenu {
            CheckItem("Grid", checked = checked) { checked = it }
            RadioItem("Wire", selected = false) { chosen = "wire" }
        }
        val check = entries[0] as MenuEntry.Item
        val radio = entries[1] as MenuEntry.Item

        check.activate()
        radio.activate()

        assertTrue(checked)
        assertEquals("wire", chosen)
        assertEquals(MenuMark.Unchecked, check.mark)
        assertEquals(MenuMark.Unselected, radio.mark)
    }

    @Test
    fun `the safe triangle holds the points between the pointer and the submenu's near edge`() {
        val from = Offset(100f, 50f)
        val top = Offset(200f, 40f)
        val bottom = Offset(200f, 200f)

        assertTrue(inTriangle(Offset(150f, 80f), from, top, bottom), "down and towards the submenu")
        assertTrue(inTriangle(Offset(199f, 190f), from, top, bottom), "nearly at its bottom corner")
        assertFalse(inTriangle(Offset(150f, 150f), from, top, bottom), "too steep: that is heading down the menu")
        assertFalse(inTriangle(Offset(90f, 60f), from, top, bottom), "backwards")
    }

    @Test
    fun `focus goes back to where it was when two traps close in the same frame`() {
        var outer by mutableStateOf(false)
        var inner by mutableStateOf(false)
        val ui = uiTest(Size(400f, 300f)) {
            Column {
                Button("SCREEN", onClick = {}, modifier = Modifier.testTag("screen"), initialFocus = true)
                Column(Modifier.focusTrap(outer)) {
                    if (outer) Button("OUTER", onClick = { inner = true }, modifier = Modifier.testTag("outer"))
                }
                Box(Modifier.focusTrap(inner)) {
                    if (inner) Button("INNER", onClick = {}, modifier = Modifier.testTag("inner"))
                }
            }
        }
        ui.use {
            ui.assertFocused("screen")
            outer = true
            ui.advanceBy(16)
            ui.assertFocused("outer")
            inner = true
            ui.advanceBy(16)
            ui.assertFocused("inner")

            outer = false
            inner = false
            ui.advanceBy(16)

            ui.assertFocused("screen")
        }
    }
}
