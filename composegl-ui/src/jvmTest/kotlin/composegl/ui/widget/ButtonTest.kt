package composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import composegl.ui.backend.FakeTexture
import composegl.ui.backend.MonospaceFontProvider
import composegl.ui.draw.DrawPass
import composegl.ui.focus.FocusManager
import composegl.ui.geometry.Offset
import composegl.ui.graphics.ArtAtlas
import composegl.ui.graphics.Colour
import composegl.ui.graphics.DrawCall
import composegl.ui.graphics.RecordingCanvas
import composegl.ui.host.UiHost
import composegl.ui.input.Key
import composegl.ui.input.KeyEvent
import composegl.ui.input.KeyEventType
import composegl.ui.input.KeyNavigator
import composegl.ui.input.PointerEvent
import composegl.ui.input.PointerId
import composegl.ui.input.PointerRouter
import composegl.ui.layout.Box
import composegl.ui.layout.Column
import composegl.ui.layout.Constraints
import composegl.ui.layout.MeasurePass
import composegl.ui.modifier.Modifier
import composegl.ui.modifier.clickable
import composegl.ui.modifier.offset
import composegl.ui.node.UiNode
import composegl.ui.skin.ProvideSkin
import composegl.ui.skin.Skin
import composegl.ui.skin.SkinDrawable
import composegl.ui.skin.WidgetState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The controls that carry every menu, driven the way a player drives them.
 *
 * Nothing here asserts a colour of its own: every expected value is read back out of the skin, so
 * the tests say "the button is drawn in the style the skin calls hovered" rather than "the button
 * is 0x39445A". Change the default skin and these keep passing; make a button stop reacting and
 * they stop.
 */
class ButtonTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()
    private val focus = FocusManager(host.root)
    private val pointer = PointerRouter(host.root, focus)
    private val keys = KeyNavigator(focus)

    @AfterEach
    fun tearDown() = host.dispose()

    private var clock = 0L

    private fun show(content: @Composable () -> Unit) {
        host.setContent { ProvideFonts(MonospaceFontProvider()) { content() } }
        frame()
    }

    /** One turn of a game loop: recompose, lay out, settle focus, draw. */
    private fun frame() {
        canvas.clear()
        host.frame(clock)
        clock += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(400f, 400f))
        focus.refresh()
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
    }

    private fun move(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(x, y)))

    private fun press(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Press(PointerId.Mouse, Offset(x, y)))

    private fun release(x: Float, y: Float) =
        pointer.onPointer(PointerEvent.Release(PointerId.Mouse, Offset(x, y)))

    private fun find(name: String, node: UiNode = host.root): UiNode? =
        if (node.name == name) node else node.children.firstNotNullOfOrNull { find(name, it) }

    private fun rectangles() = canvas.calls.filterIsInstance<DrawCall.Rectangle>()

    private fun texts() = canvas.calls.filterIsInstance<DrawCall.Text>()

    /** The colour the skin says a style is filled with in these states. */
    private fun fill(style: String, vararg states: WidgetState): Colour {
        val background = Skin.Default.resolve(style, states.toSet()).background
        return (background as SkinDrawable.Fill).colour
    }

    // --- states out of the skin -------------------------------------------------------------

    @Test
    fun `a button is drawn in the skin's idle style`() {
        show { Button("PLAY", onClick = {}) }

        assertEquals(fill("button"), rectangles().first().colour)
    }

    @Test
    fun `hovering it draws the skin's hovered style`() {
        show { Button("PLAY", onClick = {}) }

        move(10f, 10f)
        frame()

        assertEquals(fill("button", WidgetState.Hovered), rectangles().first().colour)
    }

    @Test
    fun `holding it down draws the pressed style and drops the label`() {
        show { Button("PLAY", onClick = {}) }
        val idle = texts().single().at

        press(10f, 10f)
        frame()

        assertEquals(fill("button", WidgetState.Pressed), rectangles().first().colour)
        assertEquals(
            idle + Skin.Default.resolve("button", setOf(WidgetState.Pressed)).contentOffset,
            texts().single().at,
            "the skin moves the contents while it is held, and the frame stays where it is",
        )
    }

    @Test
    fun `a disabled button is drawn in the skin's disabled style`() {
        show { Button("PLAY", onClick = {}, enabled = false) }

        val style = Skin.Default.resolve("button", setOf(WidgetState.Disabled))
        assertEquals(style.textColour, texts().single().colour, "the label greys with it")
        assertNotEquals(
            Colour.White,
            style.tint,
            "and the skin dims the frame with a tint rather than with a second set of pictures",
        )
    }

    @Test
    fun `focus draws the skin's focused style, which is the cursor on a console`() {
        show { Button("PLAY", onClick = {}, initialFocus = true) }
        frame()

        assertEquals(fill("button", WidgetState.Focused), rectangles().first().colour)
    }

    // --- what a click is --------------------------------------------------------------------

    @Test
    fun `a press and a release on the button is a click`() {
        var clicks = 0
        show { Button("PLAY", onClick = { clicks++ }) }

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(1, clicks)
    }

    @Test
    fun `a press that lets go somewhere else is a change of mind and fires nothing`() {
        var clicks = 0
        show { Button("PLAY", onClick = { clicks++ }) }

        press(10f, 10f)
        move(10f, 300f)
        release(10f, 300f)

        assertEquals(0, clicks, "players slide off a button on purpose")
    }

    @Test
    fun `a disabled button cannot be clicked`() {
        var clicks = 0
        show { Button("PLAY", onClick = { clicks++ }, enabled = false) }

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(0, clicks)
    }

    @Test
    fun `a disabled button still swallows the click`() {
        var behind = 0
        show {
            Box(Modifier.clickable { behind++ }) {
                Button("PLAY", onClick = {}, enabled = false)
            }
        }

        press(10f, 10f)
        release(10f, 10f)

        assertEquals(0, behind, "a click cannot fall through a greyed-out button to what is behind it")
    }

    @Test
    fun `a disabled button cannot be focused`() {
        show { Button("PLAY", onClick = {}, enabled = false) }
        frame()

        assertNull(focus.focused, "a pad cannot land on something it cannot press")
    }

    @Test
    fun `the keyboard presses whatever has focus`() {
        var clicks = 0
        show { Button("PLAY", onClick = { clicks++ }, initialFocus = true) }
        frame()

        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Down))
        keys.onKey(KeyEvent(Key.Enter, KeyEventType.Up))

        assertEquals(1, clicks, "and the pad's South button goes through the same door")
    }

    @Test
    fun `focus skips over the disabled one`() {
        show {
            Column {
                Button("ONE", onClick = {}, enabled = false)
                Button("TWO", onClick = {}, initialFocus = true)
            }
        }
        frame()

        assertEquals("TWO", label(focus.focused), "focus went to the one that can be pressed")
    }

    /** The text drawn inside a node, for saying which button focus landed on. */
    private fun label(node: UiNode?): String? {
        if (node == null) return null
        canvas.clear()
        DrawPass(canvas).draw(node)
        return texts().firstOrNull()?.text
    }

    // --- the icon button --------------------------------------------------------------------

    /** The default skin with one picture in it, because the shipped skin has no atlas at all. */
    private fun withGear() = Skin(
        styles = Skin.Default.styles,
        fonts = Skin.Default.fonts,
        art = ArtAtlas.of(mapOf("icons/gear" to FakeTexture(16, 16))),
        defaults = Skin.Default.defaults,
    )

    @Test
    fun `an icon button tints its picture with the style's colour`() {
        val skin = withGear()
        show { ProvideSkin(skin) { IconButton("icons/gear", onClick = {}) } }

        val image = canvas.calls.filterIsInstance<DrawCall.Image>().single()
        assertEquals(
            Skin.Default.resolve("button.icon").textColour,
            image.tint,
            "one grey icon is a whole set: the style dims it and brightens it",
        )
    }

    @Test
    fun `a disabled icon button dims the picture too`() {
        val skin = withGear()
        show {
            ProvideSkin(skin) {
                IconButton("icons/gear", onClick = {}, enabled = false)
            }
        }

        val image = canvas.calls.filterIsInstance<DrawCall.Image>().single()
        assertEquals(Skin.Default.resolve("button.icon", setOf(WidgetState.Disabled)).textColour, image.tint)
    }

    // --- the ones that hold a value -----------------------------------------------------------

    @Test
    fun `a checkbox draws its tick only when it is ticked`() {
        var checked by mutableStateOf(false)
        show { Checkbox(checked, onCheckedChange = { checked = it }) }

        assertEquals(1, rectangles().size, "the box and nothing in it")

        checked = true
        frame()

        assertEquals(fill("checkbox.tick"), rectangles()[1].colour)
    }

    @Test
    fun `clicking a checkbox asks for the other value`() {
        var asked: Boolean? = null
        show { Checkbox(checked = false, onCheckedChange = { asked = it }) }

        press(5f, 5f)
        release(5f, 5f)

        assertEquals(true, asked)
    }

    @Test
    fun `a checkbox's label is part of what you click`() {
        var asked: Boolean? = null
        show { Checkbox(checked = true, onCheckedChange = { asked = it }, label = "Fullscreen") }

        val text = texts().single()
        press(text.at.x + 10f, text.at.y + 5f)
        release(text.at.x + 10f, text.at.y + 5f)

        assertEquals(false, asked, "nobody should have to hit an eighteen-pixel box")
    }

    @Test
    fun `a disabled checkbox greys its label and does nothing when clicked`() {
        var asked: Boolean? = null
        show {
            Checkbox(checked = false, onCheckedChange = { asked = it }, label = "Fullscreen", enabled = false)
        }

        press(5f, 5f)
        release(5f, 5f)

        assertNull(asked)
        assertEquals(
            Skin.Default.resolve("label", setOf(WidgetState.Disabled)).textColour,
            texts().single().colour,
        )
    }

    @Test
    fun `a radio button reports being chosen and never being unchosen`() {
        var chosen = 0
        show { RadioButton(selected = true, onSelect = { chosen++ }) }

        press(5f, 5f)
        release(5f, 5f)

        assertEquals(1, chosen, "picking the one that is already picked is still a pick, not a clear")
        assertEquals(fill("radio.dot"), rectangles()[1].colour)
    }

    @Test
    fun `a toggle puts its knob at the end it is on`() {
        var on by mutableStateOf(false)
        show { Toggle(on, onCheckedChange = { on = it }) }
        val knobOff = rectangles()[1]

        on = true
        frame()
        val knobOn = rectangles()[1]

        assertTrue(
            knobOn.rect.left > knobOff.rect.left,
            "the knob moved across: ${knobOff.rect} then ${knobOn.rect}",
        )
        assertEquals(knobOff.rect.width, knobOn.rect.width, "and stayed the same size")
        assertEquals(fill("toggle.on"), rectangles().first().colour, "the track says which way it is")
    }

    @Test
    fun `a toggle's knob is as tall as the track's padding leaves it`() {
        show { Toggle(checked = false, onCheckedChange = {}, height = 30f) }

        val track = Skin.Default.resolve("toggle")
        assertEquals(30f - track.padding.vertical, rectangles()[1].rect.height)
    }

    @Test
    fun `a control moved by a modifier is still hit where it is drawn`() {
        var clicks = 0
        show { Button("PLAY", onClick = { clicks++ }, modifier = Modifier.offset(60f, 40f)) }

        press(10f, 10f)
        release(10f, 10f)
        assertEquals(0, clicks, "nothing is there any more")

        press(70f, 50f)
        release(70f, 50f)
        assertEquals(1, clicks)
    }

    @Test
    fun `nothing recomposes while the pointer sits still`() {
        show { Button("PLAY", onClick = {}) }
        move(10f, 10f)
        frame()

        assertFalse(host.frame(clock), "hover is state, so it costs a frame when it changes and none after")
    }
}
