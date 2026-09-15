package dev.wildware.composegl.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.backend.RecordingSystemCursor
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The mouse cursor's shape, over a real composed screen, driven by real pointer events.
 *
 * Every test here builds widgets the way a game does, lays them out, moves a mouse over the nodes
 * [dev.wildware.composegl.ui.node.UiNode.find] hands back, and asserts what the backend's cursor
 * was asked to show. The cursor is the [HeadlessBackend]'s own, so the wiring under test is the one
 * a game writes: `PointerRouter(root, focus, backend.cursor)`.
 */
class PointerHoverIconTest {

    private val host = UiHost()
    private val focus = FocusManager(host.root)
    private val backend = HeadlessBackend()
    private val cursor = backend.cursor as RecordingSystemCursor
    private val pointer = PointerRouter(host.root, focus, backend.cursor)
    private var clock = 0L

    @AfterTest
    fun tearDown() = host.dispose()

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(backend.fonts) { content() } }
        settle()
    }

    private fun settle() {
        repeat(8) {
            clock += 16_666_667L
            if (!host.settle(Constraints.atMost(400f, 400f), focus, nanos = clock)) return
        }
        throw AssertionError("settle still reported a change after 8 turns")
    }

    private fun centreOf(tag: String) = host.root.find(tag).boundsInRoot.centre

    private fun moveTo(at: Offset, type: PointerType = PointerType.Mouse, pressed: Set<PointerButton> = emptySet()) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, at, pressed, type = type))

    private fun moveTo(tag: String) = moveTo(centreOf(tag))

    @Test
    fun `the mouse over a text field shows an I-beam and the arrow again off it`() {
        var name by mutableStateOf("")
        show {
            Column(Modifier.width(300f)) {
                TextField(name, onValueChange = { name = it }, modifier = Modifier.testTag("name"))
                Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
            }
        }

        moveTo("name")
        assertEquals(PointerIcon.Text, cursor.icon, "over somewhere to type")
        assertEquals(PointerIcon.Text, pointer.pointerIcon)

        moveTo("play")
        assertEquals(PointerIcon.Default, cursor.icon, "a button asks for nothing")
        assertEquals(listOf(PointerIcon.Text, PointerIcon.Default), cursor.requests)
    }

    @Test
    fun `moving about inside one field asks the cursor once`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }
        val bounds = host.root.find("name").boundsInRoot

        for (step in 1..20) {
            moveTo(Offset(bounds.left + bounds.width * step / 21f, bounds.centre.y))
        }

        assertEquals(listOf(PointerIcon.Text), cursor.requests, "a mouse move is not a reason to ask again")
    }

    @Test
    fun `clicking into the field keeps the I-beam and focuses it`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }
        val field = host.root.find("name")

        moveTo("name")
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, field.boundsInRoot.centre))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, field.boundsInRoot.centre))
        settle()

        assertSame(field, focus.focused, "the click reached the field")
        assertEquals(PointerIcon.Text, cursor.icon)
        assertEquals(listOf(PointerIcon.Text), cursor.requests, "the press and release asked for nothing new")
    }

    @Test
    fun `a disabled text field shows no I-beam`() {
        show {
            TextField("", onValueChange = {}, enabled = false, modifier = Modifier.width(300f).testTag("name"))
        }

        moveTo("name")

        assertEquals(PointerIcon.Default, cursor.icon)
        assertTrue(cursor.requests.isEmpty(), "nothing changed, so nothing was asked: ${cursor.requests}")
    }

    @Test
    fun `a field that is switched off under a still mouse drops the I-beam on the next move`() {
        var enabled by mutableStateOf(true)
        show { TextField("", onValueChange = {}, enabled = enabled, modifier = Modifier.width(300f).testTag("name")) }

        val centre = centreOf("name")
        moveTo(centre)
        assertEquals(PointerIcon.Text, cursor.icon)

        enabled = false
        settle()
        moveTo(centre + Offset(1f, 0f))

        assertEquals(PointerIcon.Default, cursor.icon)
    }

    @Test
    fun `a caller's own icon beats the text field's`() {
        show {
            TextField(
                "",
                onValueChange = {},
                modifier = Modifier.width(300f).pointerHoverIcon(PointerIcon.Hand).testTag("name"),
            )
        }

        moveTo("name")

        assertEquals(PointerIcon.Hand, cursor.icon)
    }

    @Test
    fun `a panel's icon covers the children that ask for none`() {
        show {
            Column(Modifier.size(300f).pointerHoverIcon(PointerIcon.Hand).testTag("panel")) {
                Button("GO", onClick = {}, modifier = Modifier.testTag("go"))
                TextField("", onValueChange = {}, modifier = Modifier.width(200f).testTag("name"))
            }
        }
        val panel = host.root.find("panel").boundsInRoot

        moveTo("go")
        assertEquals(PointerIcon.Hand, cursor.icon, "the button has no icon of its own, so the panel's shows")

        moveTo("name")
        assertEquals(PointerIcon.Text, cursor.icon, "the field's own icon is nearer than the panel's")

        moveTo(Offset(panel.right - 5f, panel.bottom - 5f))
        assertEquals(PointerIcon.Hand, cursor.icon, "the empty part of the panel is the panel")

        moveTo(Offset(390f, 390f))
        assertEquals(PointerIcon.Default, cursor.icon, "outside the panel nothing is asking")
    }

    @Test
    fun `something drawn on top hides the icon of what is underneath`() {
        show {
            Box(Modifier.size(200f)) {
                TextField("", onValueChange = {}, modifier = Modifier.fillMaxSize().testTag("name"))
                Box(Modifier.fillMaxSize().clickable {}.testTag("cover"))
            }
        }

        moveTo("name")

        assertEquals(PointerIcon.Default, cursor.icon, "the pointer is over the cover, not the field")
    }

    @Test
    fun `an icon alone does not take the click from what is underneath`() {
        var clicks = 0
        show {
            Box(Modifier.size(200f)) {
                Button("FIRE", onClick = { clicks++ }, modifier = Modifier.fillMaxSize().testTag("fire"))
                Box(Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Crosshair).testTag("sight"))
            }
        }
        val at = centreOf("sight")

        moveTo(at)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, at))

        assertEquals(1, clicks, "the press went straight through the sight to the button")
        assertEquals(PointerIcon.Crosshair, cursor.icon)
    }

    @Test
    fun `a drag keeps the shape it started with until it lets go`() {
        var value by mutableStateOf(0f)
        show {
            Slider(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.width(200f).pointerHoverIcon(PointerIcon.ResizeHorizontal).testTag("volume"),
            )
        }
        val bounds = host.root.find("volume").boundsInRoot
        val start = Offset(bounds.left + 2f, bounds.centre.y)
        val held = setOf(PointerButton.Primary)

        moveTo(start)
        assertEquals(PointerIcon.ResizeHorizontal, cursor.icon)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, start))

        // Far past the end and well below it: off the slider altogether, which on its own would be
        // the arrow.
        moveTo(Offset(390f, 390f), pressed = held)
        pointer.onPointer(PointerEvent.Exit(PointerId.Mouse, Offset(390f, 390f)))
        settle()
        assertEquals(1f, value, "the drag really is still dragging")
        assertEquals(PointerIcon.ResizeHorizontal, cursor.icon, "and it kept the shape it started with")

        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(390f, 390f)))
        assertEquals(PointerIcon.Default, cursor.icon, "let go over nothing, so the arrow")
        assertEquals(listOf(PointerIcon.ResizeHorizontal, PointerIcon.Default), cursor.requests)
    }

    @Test
    fun `the mouse leaving the window puts the arrow back`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }

        moveTo("name")
        pointer.onPointer(PointerEvent.Exit(PointerId.Mouse, centreOf("name")))

        assertEquals(PointerIcon.Default, cursor.icon)
    }

    @Test
    fun `the window losing focus puts the arrow back`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }

        moveTo("name")
        pointer.cancelAll()

        assertEquals(PointerIcon.Default, cursor.icon)
    }

    @Test
    fun `a finger never changes the cursor`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }

        moveTo(centreOf("name"), type = PointerType.Touch)
        moveTo(centreOf("name"), type = PointerType.Ray)

        assertTrue(cursor.requests.isEmpty(), "a touch screen has no cursor: ${cursor.requests}")
        assertEquals(PointerIcon.Default, pointer.pointerIcon)
    }

    @Test
    fun `a stylus hovering a field shows the I-beam`() {
        show { TextField("", onValueChange = {}, modifier = Modifier.width(300f).testTag("name")) }

        moveTo(centreOf("name"), type = PointerType.Stylus)

        assertEquals(PointerIcon.Text, cursor.icon)
    }

    @Test
    fun `keyboard focus landing on a field leaves the cursor alone`() {
        show {
            Column(Modifier.width(300f)) {
                Button("PLAY", onClick = {}, initialFocus = true)
                TextField("", onValueChange = {}, modifier = Modifier.testTag("name"))
            }
        }

        val keys = KeyNavigator(focus)
        keys.onKey(KeyEvent(Key.Down, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Down, KeyEventType.Up))
        settle()

        assertSame(host.root.find("name"), focus.focused, "focus moved onto the field")
        assertTrue(cursor.requests.isEmpty(), "the cursor is the mouse's, and the mouse did not move")
    }

    @Test
    fun `a sibling lifted by zIndex shows its icon over the one written after it`() {
        var lifted by mutableStateOf(false)
        show {
            Box(Modifier.size(200f)) {
                Box(
                    Modifier.fillMaxSize().zIndex(if (lifted) 1f else 0f)
                        .pointerHoverIcon(PointerIcon.Move).testTag("card"),
                )
                Box(Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Hand).testTag("hand"))
            }
        }

        moveTo("card")
        assertEquals(PointerIcon.Hand, cursor.icon, "written last, so on top")

        lifted = true
        settle()
        moveTo(centreOf("card") + Offset(1f, 0f))
        assertEquals(PointerIcon.Move, cursor.icon, "lifted, so the card is on top now")
    }

    @Test
    fun `a node composed away under the mouse gives up its icon on the next move`() {
        var open by mutableStateOf(true)
        show {
            Box(Modifier.size(200f)) {
                if (open) Box(Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Hand).testTag("link"))
            }
        }
        val at = centreOf("link")

        moveTo(at)
        assertEquals(PointerIcon.Hand, cursor.icon)

        open = false
        settle()
        moveTo(at + Offset(1f, 0f))
        assertEquals(PointerIcon.Default, cursor.icon)

        open = true
        settle()
        moveTo(at)
        assertEquals(PointerIcon.Hand, cursor.icon, "and composed back, it has it again")
    }

    @Test
    fun `an icon that changes with state shows the new shape on the next move`() {
        var locked by mutableStateOf(false)
        show {
            Box(
                Modifier.size(200f)
                    .pointerHoverIcon(if (locked) PointerIcon.NotAllowed else PointerIcon.Hand)
                    .testTag("door"),
            )
        }
        val at = centreOf("door")

        moveTo(at)
        locked = true
        settle()
        moveTo(at + Offset(1f, 0f))

        assertEquals(listOf(PointerIcon.Hand, PointerIcon.NotAllowed), cursor.requests)
    }

    @Test
    fun `a node with no size never shows its icon`() {
        show {
            Box(Modifier.size(200f)) {
                Box(Modifier.size(0f).pointerHoverIcon(PointerIcon.Hand).testTag("nothing"))
            }
        }

        moveTo(Offset(0f, 0f))
        moveTo(Offset(1f, 1f))

        assertTrue(cursor.requests.isEmpty(), "there is nowhere to be over: ${cursor.requests}")
    }

    @Test
    fun `a drag the platform takes away puts the arrow back`() {
        show {
            Slider(
                value = 0.5f,
                onValueChange = {},
                modifier = Modifier.width(200f).pointerHoverIcon(PointerIcon.ResizeHorizontal).testTag("volume"),
            )
        }
        val at = centreOf("volume")

        moveTo(at)
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, at))
        pointer.onPointer(PointerEvent.Cancel(PointerId.Mouse, at))

        assertEquals(PointerIcon.Default, cursor.icon)
    }

    @Test
    fun `a drag let go over a field takes the field's I-beam`() {
        show {
            Column(Modifier.width(300f)) {
                Slider(
                    value = 0.5f,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.ResizeHorizontal).testTag("volume"),
                )
                TextField("", onValueChange = {}, modifier = Modifier.testTag("name"))
            }
        }
        val held = setOf(PointerButton.Primary)

        moveTo("volume")
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, centreOf("volume")))
        moveTo(centreOf("name"), pressed = held)
        assertEquals(PointerIcon.ResizeHorizontal, cursor.icon, "still dragging")

        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, centreOf("name")))
        assertEquals(PointerIcon.Text, cursor.icon)
    }
}
