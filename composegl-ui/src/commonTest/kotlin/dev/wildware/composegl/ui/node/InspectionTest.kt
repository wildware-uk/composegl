package dev.wildware.composegl.ui.node

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.debug.DebugOverlay
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.OverdrawMap
import dev.wildware.composegl.ui.debug.isDebugOverlay
import dev.wildware.composegl.ui.debug.measureOverdraw
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.linearOrientation
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The read-only API a debug tool in another module reads the tree through: `composegl-debug`'s
 * overlays use nothing else, so each piece is held to what those overlays need from it.
 */
class InspectionTest {

    @Test
    fun `the content box is the laid-out box less its padding and follows a click`() {
        uiTest(Size(400f, 300f)) {
            var wide by remember { mutableStateOf(false) }
            Box(
                Modifier.offset(20f, 30f).padding(left = 4f, top = 6f, right = 8f, bottom = 10f)
                    .size(if (wide) 120f else 100f, 60f).clickable { wide = true }.testTag("box"),
            )
        }.use { ui ->
            assertEquals(Rect(24f, 36f, 112f, 80f), ui.node("box").contentBoundsInRoot)
            ui.click("box")
            assertEquals(Rect(24f, 36f, 132f, 80f), ui.node("box").contentBoundsInRoot)
        }
    }

    @Test
    fun `rows and columns say which way they run and nothing else does`() {
        uiTest(Size(400f, 300f)) {
            Column(Modifier.testTag("column")) {
                Row(Modifier.testTag("row")) { Box(Modifier.size(10f, 10f)) }
                Box(Modifier.size(10f, 10f).testTag("box"))
            }
        }.use { ui ->
            assertEquals(Orientation.Vertical, ui.node("column").measurePolicy.linearOrientation)
            assertEquals(Orientation.Horizontal, ui.node("row").measurePolicy.linearOrientation)
            assertNull(ui.node("box").measurePolicy.linearOrientation)
            assertNull(MeasurePolicy.Stack.linearOrientation)
            assertEquals("Column", describePolicy(ui.node("column").measurePolicy))
            assertEquals("Row", describePolicy(ui.node("row").measurePolicy))
        }
    }

    @Test
    fun `focus says where a key would go and tells a listener when it moves`() {
        uiTest(Size(400f, 300f)) {
            Row {
                Box(Modifier.size(40f, 40f).focusable(initial = true).testTag("a"))
                Box(Modifier.size(40f, 40f).focusable().testTag("b"))
            }
        }.use { ui ->
            assertSame(ui.focus, ui.root.focusManager)
            assertSame(ui.node("b"), ui.focus.peek(FocusDirection.Right)?.node)
            assertFalse(ui.focus.peek(FocusDirection.Right)!!.ordered)
            assertEquals(setOf(ui.node("a"), ui.node("b")), ui.focus.reachable().toSet())

            var moves = 0
            val listener: () -> Unit = { moves++ }
            ui.focus.addMovedListener(listener)
            assertEquals(1, ui.focus.movedListenerCount)
            ui.key(Key.Right)
            ui.assertFocused("b")
            assertTrue(moves > 0, "told of the move")
            ui.focus.removeMovedListener(listener)
            assertEquals(0, ui.focus.movedListenerCount)
            val before = moves
            ui.key(Key.Left)
            ui.assertFocused("a")
            assertEquals(before, moves, "and not after it stopped listening")
        }
    }

    @Test
    fun `a watcher turns counting on until it stops and changes are stamped with the frame time`() {
        uiTest(Size(400f, 300f)) {
            var on by remember { mutableStateOf(false) }
            Box(Modifier.size(40f, 40f).background(if (on) Colour.Red else Colour.Blue).clickable { on = true }.testTag("box"))
        }.use { ui ->
            val tree = ui.host.tree
            assertFalse(tree.counting)
            tree.watchChanges()
            assertTrue(tree.counting)
            assertEquals(NeverChanged, ui.node("box").changedAtNanos)

            ui.click("box")
            val stamped = ui.node("box").changedAtNanos
            assertTrue(stamped != NeverChanged && stamped <= tree.clocks.frameNanos, "stamped with a frame time: $stamped")
            assertSame(tree, ui.node("box").tree)

            tree.stopWatchingChanges()
            assertFalse(tree.counting)
        }
    }

    @Test
    fun `a map counted into again is cleared first and a debug overlay is left out`() {
        val marks = object : DebugOverlay {
            override fun invoke(canvas: UiCanvas, content: Rect) = canvas.rect(content, Colour.Red)
        }
        uiTest(Size(100f, 100f)) {
            Box(Modifier.size(50f, 50f).background(Colour.Blue)) {
                Layout(Modifier.size(50f, 50f), name = "marks", draw = marks, measurePolicy = MeasurePolicy.Stack)
            }
        }.use { ui ->
            val map = OverdrawMap(100f, 100f)
            measureOverdraw(ui.root, into = map)
            measureOverdraw(ui.root, into = map)
            assertEquals(1, map.at(25f, 25f), "counted once, not added to the last count, and the marks left out")
            assertTrue(isDebugOverlay(ui.root.firstOrNull { it.name == "marks" }!!))
            assertFalse(isDebugOverlay(ui.root))
            assertEquals("frame budget", FrameBudget.OverlayName)
        }
    }
}
