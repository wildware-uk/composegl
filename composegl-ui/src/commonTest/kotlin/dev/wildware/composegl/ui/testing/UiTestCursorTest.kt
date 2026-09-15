package dev.wildware.composegl.ui.testing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.RecordingSystemCursor
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cursor's shape through [uiTest], the way a game's own tests will check it: the mouse moved
 * and clicked by tag, and the backend's cursor asked what it was given.
 */
class UiTestCursorTest {

    @Test
    fun `hovering and clicking a field gives the backend's cursor an I-beam`() {
        var name by mutableStateOf("")
        uiTest {
            Column(Modifier.width(300f)) {
                TextField(name, onValueChange = { name = it }, modifier = Modifier.testTag("name"))
                Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
            }
        }.use { ui ->
            val cursor = ui.backend.cursor as RecordingSystemCursor

            ui.moveTo("name")
            assertEquals(PointerIcon.Text, cursor.icon)
            assertEquals(PointerIcon.Text, ui.pointerIcon)

            ui.click("name")
            ui.type("Ada")
            ui.assertFocused("name")
            ui.assertText("name", "Ada")
            assertEquals(PointerIcon.Text, cursor.icon, "typing does not move the mouse")

            ui.moveTo("play")
            assertEquals(PointerIcon.Default, cursor.icon)
            assertEquals(listOf(PointerIcon.Text, PointerIcon.Default), cursor.requests)
        }
    }

    @Test
    fun `a drag off a resize handle keeps its arrow until it lets go`() {
        var value by mutableStateOf(0.5f)
        uiTest {
            Slider(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.width(200f).pointerHoverIcon(PointerIcon.ResizeHorizontal).testTag("volume"),
            )
        }.use { ui ->
            val cursor = ui.backend.cursor as RecordingSystemCursor

            ui.press("volume")
            // Far off the slider, which with no press held would be the arrow.
            ui.moveTo(Offset(1000f, 600f))
            assertEquals(PointerIcon.ResizeHorizontal, cursor.icon, "the press still holds its shape")

            ui.release()
            assertEquals(PointerIcon.Default, cursor.icon)
        }
    }

    @Test
    fun `recomposing an icon that has not changed does not redraw`() {
        var tick by mutableStateOf(0)
        var recomposed = 0
        uiTest {
            // Read here, so bumping it recomposes this scope and writes the chain afresh.
            tick.let { recomposed++ }
            Box(Modifier.size(100f).pointerHoverIcon(PointerIcon.Text).testTag("name"))
        }.use { ui ->
            ui.moveTo("name")
            ui.render()
            val before = recomposed
            val changed = ui.host.changedFrames

            tick++
            ui.advanceBy(50L)

            assertTrue(recomposed > before, "the scope really did recompose")
            // A fresh icon modifier equal to the last must not count as a change, or a screen
            // holding one would redraw every time anything near it recomposed.
            assertEquals(changed, ui.host.changedFrames, "an equal chain is not a change")
            assertFalse(ui.render(), "nothing visible changed, so nothing to draw")
            assertEquals(PointerIcon.Text, ui.pointerIcon)
        }
    }
}
