package dev.wildware.composegl.ui.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.KeyNavigator
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.resolve
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A widget in real composed UI, found from a test by a name the test gave it.
 *
 * Every assertion here is about a tree nobody built by hand: the tag goes in through the same
 * modifier a screen already passes, and comes out on whichever node that modifier landed on.
 */
class TestTagTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        settle()
    }

    private fun settle() {
        repeat(8) {
            clock += 16_666_667L
            if (!host.settle(Constraints.atMost(400f, 400f), focus, nanos = clock)) return
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    @Test
    fun `a tagged button is found and its rectangle is where it takes clicks`() {
        var clicks = 0
        show {
            Column {
                Button("QUIT", onClick = {})
                Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("play"))
            }
        }

        val play = host.root.find("play")
        val bounds = play.boundsInRoot
        assertTrue(bounds.top > 0f, "the second button in a column is below the first: $bounds")
        assertTrue(bounds.width > 0f && bounds.height > 0f, "and was laid out: $bounds")

        val pointer = PointerRouter(host.root, focus)
        val centre = bounds.centre
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, centre))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, centre))
        assertEquals(1, clicks, "the rectangle find hands back is the one the player clicks")
    }

    @Test
    fun `the keyboard walks focus onto the node find hands back`() {
        var clicks = 0
        show {
            Column {
                Button("QUIT", onClick = {}, initialFocus = true, modifier = Modifier.testTag("quit"))
                Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("play"))
            }
        }
        assertSame(host.root.find("quit"), focus.focused, "the first button starts with focus")

        val keys = KeyNavigator(focus)
        keys.onKey(KeyEvent(Key.Down, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Down, KeyEventType.Up))
        settle()
        assertSame(host.root.find("play"), focus.focused, "down moved focus to the tagged button")

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))
        assertEquals(1, clicks)
    }

    @Test
    fun `the tag is on the node the modifier landed on`() {
        show { Box(Modifier.padding(12f)) { Text("hello", Modifier.testTag("greeting")) } }

        val node = host.root.find("greeting")
        assertEquals("greeting", node.testTag)
        assertEquals("text", node.name, "the text node itself rather than something round it")
        assertEquals(12f, node.boundsInRoot.left)
    }

    @Test
    fun `findAll hands back every tagged node in tree order`() {
        show {
            Column {
                repeat(3) { index -> Text("slot $index", Modifier.testTag("slot")) }
                Text("footer", Modifier.testTag("footer"))
            }
        }

        val slots = host.root.findAll("slot")
        assertEquals(3, slots.size)
        val tops = slots.map { it.boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "top to bottom, the order they were written in")
        assertEquals(emptyList(), host.root.findAll("missing"))
    }

    @Test
    fun `find fails with the tags it could see when nothing has the tag`() {
        show { Text("hello", Modifier.testTag("greeting")) }

        val failure = assertFailsWith<IllegalStateException> { host.root.find("gretting") }
        val message = failure.message.orEmpty()
        assertTrue("gretting" in message, message)
        assertTrue("#greeting" in message, "the tree is printed with the tags that are there: $message")
        assertNull(host.root.findOrNull("gretting"))
    }

    @Test
    fun `find refuses to pick between two nodes with the same tag`() {
        show {
            Column {
                Text("one", Modifier.testTag("twin"))
                Text("two", Modifier.testTag("twin"))
            }
        }

        val failure = assertFailsWith<IllegalStateException> { host.root.find("twin") }
        assertTrue("2 nodes" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `find searches only under the node it is asked on`() {
        show {
            Column {
                Box(Modifier.testTag("left")) { Text("a", Modifier.testTag("label")) }
                Box(Modifier.testTag("right")) { Text("b", Modifier.testTag("label")) }
            }
        }

        val right = host.root.find("right")
        val label = right.find("label")
        assertSame(right, label.parent, "a tag can repeat across a screen and still be found inside one part")
        assertSame(right, right.find("right"), "the node asked is part of its own search")
    }

    @Test
    fun `a node taken away by recomposition is no longer found`() {
        var showing by mutableStateOf(true)
        show { Column { if (showing) Text("banner", Modifier.testTag("banner")) } }
        host.root.find("banner")

        showing = false
        settle()

        assertNull(host.root.findOrNull("banner"))
    }

    @Test
    fun `a recomposition that writes the same tag again costs the frame nothing`() {
        var ticks by mutableStateOf(0)
        show {
            // Read here so this scope runs again and hands the box a freshly built tag.
            ticks.let { Box(Modifier.testTag("still")) }
        }
        val before = host.root.find("still")

        ticks++
        clock += 16_666_667L
        assertFalse(host.frame(clock), "the recomposition wrote an equal tag, so nothing needs drawing")
        assertSame(before, host.root.find("still"))
        assertEquals(Modifier.testTag("play"), Modifier.testTag("play"))
    }

    @Test
    fun `a tag that recomposition changes is found under its new name`() {
        var tag by mutableStateOf("before")
        show { Column { Text("hello", Modifier.testTag(tag)) } }
        val node = host.root.find("before")

        tag = "after"
        settle()

        assertNull(host.root.findOrNull("before"), "the old tag is not left behind on the node")
        assertSame(node, host.root.find("after"), "the same node, told a new name")
    }

    @Test
    fun `a node with nothing in it and no size is still found`() {
        show {
            Column {
                Box(Modifier.testTag("empty"))
                Text("after")
            }
        }

        val empty = host.root.find("empty")
        assertEquals(0f, empty.width)
        assertEquals(0f, empty.height)
    }

    @Test
    fun `the later tag in a chain is the one that counts`() {
        assertEquals("inner", Modifier.testTag("outer").testTag("inner").resolve().testTag)
        assertNull(Modifier.padding(4f).resolve().testTag)
    }

    @Test
    fun `a tag does not make a node something the pointer can hit`() {
        assertFalse(Modifier.testTag("scenery").resolve().isInteractive)
    }

    // --- driven through uiTest -------------------------------------------------------------------

    @Test
    fun `a uiTest clicks focuses and reads buttons by their tags`() {
        var played = 0
        uiTest(Size(400f, 300f)) {
            Column {
                Button("QUIT", onClick = {}, initialFocus = true, modifier = Modifier.testTag("quit"))
                Button("PLAY", onClick = { played++ }, modifier = Modifier.testTag("play"))
            }
        }.use { ui ->
            ui.assertText("play", "PLAY")
            ui.click("play")
            assertEquals(1, played, "the click went to the middle of the tagged button")

            ui.pad(GamepadButton.DpadUp)
            ui.assertFocused("quit")
            ui.key(Key.Down)
            ui.assertFocused("play")
            ui.pad(GamepadButton.South)
            assertEquals(2, played)
        }
    }

    @Test
    fun `a uiTest click on a misspelt tag fails and prints the tags that are there`() {
        uiTest(Size(400f, 300f)) {
            Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
        }.use { ui ->
            val failure = assertFailsWith<IllegalStateException> { ui.click("plya") }
            val message = failure.message.orEmpty()
            assertTrue("plya" in message, message)
            assertTrue("#play" in message, "the tree is printed with the tag that is there: $message")
        }
    }

    @Test
    fun `a button whose tag a click changes is found by its new tag`() {
        uiTest(Size(400f, 300f)) {
            var armed by remember { mutableStateOf(false) }
            Button(
                if (armed) "FIRE" else "ARM",
                onClick = { armed = !armed },
                modifier = Modifier.testTag(if (armed) "fire" else "arm"),
            )
        }.use { ui ->
            ui.click("arm")
            ui.assertDoesNotExist("arm")
            ui.assertText("fire", "FIRE")
            ui.click("fire")
            ui.assertText("arm", "ARM")
        }
    }
}
