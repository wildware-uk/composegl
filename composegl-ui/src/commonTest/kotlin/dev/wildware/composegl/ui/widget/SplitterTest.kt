package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.Haptic
import dev.wildware.composegl.ui.backend.RecordingHaptics
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.ProvideUiSounds
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * An editor split into a hierarchy and a properties pane, driven the way a player drives it: the
 * mouse over and on the divider, arrow keys, the pad. Every test reads the answer off the screen —
 * where the panes landed, what the cursor is, where focus is and what was drawn.
 *
 * The screen is 406 wide and the divider 6, so the panes share exactly 400: a quarter is 100.
 */
class SplitterTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(406f, 300f), content = content).also { opened += it }

    /** The fraction the screen holds, which the splitter reports into. */
    private var split by mutableStateOf(0.25f)

    /** Every fraction the splitter reported, in order. */
    private val reported = mutableListOf<Float>()

    @Composable
    private fun Editor(
        orientation: Orientation = Orientation.Horizontal,
        minFirst: Float = 0f,
        minSecond: Float = 0f,
        defaultFraction: Float? = null,
        enabled: Boolean = true,
        initialFocus: Boolean = false,
    ) {
        val content: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().focusable().testTag("hierarchy"))
        }
        val properties: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().focusable().testTag("properties"))
        }
        val report = { it: Float -> reported += it; split = it }
        if (defaultFraction == null) {
            Splitter(
                split, report, Modifier.fillMaxSize().testTag("splitter"), orientation,
                minFirst = minFirst, minSecond = minSecond, enabled = enabled, initialFocus = initialFocus,
                first = content, second = properties,
            )
        } else {
            Splitter(
                split, report, Modifier.fillMaxSize().testTag("splitter"), orientation,
                minFirst = minFirst, minSecond = minSecond, defaultFraction = defaultFraction,
                enabled = enabled, initialFocus = initialFocus,
                first = content, second = properties,
            )
        }
    }

    private fun UiTest.divider(): UiNode = node("splitter").children[1]

    private fun UiTest.first(): Rect = node("splitter").children[0].boundsInRoot

    private fun UiTest.second(): Rect = node("splitter").children[2].boundsInRoot

    private fun UiTest.onDivider(): Offset = divider().boundsInRoot.centre

    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        return canvas
    }

    private fun near(expected: Float, actual: Float, message: String? = null) =
        assertEquals(expected, actual, 0.001f, message)

    // --- layout ----------------------------------------------------------------------------------

    @Test
    fun `side by side the first pane gets its fraction and the divider sits between`() {
        val ui = open { Editor() }

        assertEquals(Rect(0f, 0f, 100f, 300f), ui.first())
        assertEquals(Rect(100f, 0f, 106f, 300f), ui.divider().boundsInRoot)
        assertEquals(Rect(106f, 0f, 406f, 300f), ui.second())
        assertEquals(Rect(0f, 0f, 100f, 300f), ui.node("hierarchy").boundsInRoot, "the pane's contents fill it")
    }

    @Test
    fun `stacked the first pane is on top`() {
        split = 0.5f
        val ui = uiTest(Size(300f, 406f)) { Editor(Orientation.Vertical) }.also { opened += it }

        assertEquals(Rect(0f, 0f, 300f, 200f), ui.first())
        assertEquals(Rect(0f, 200f, 300f, 206f), ui.divider().boundsInRoot)
        assertEquals(Rect(0f, 206f, 300f, 406f), ui.second())
    }

    @Test
    fun `the minimums hold whatever the fraction says`() {
        split = 0.05f
        val ui = open { Editor(minFirst = 120f, minSecond = 200f) }
        assertEquals(120f, ui.first().width)

        split = 0.9f
        ui.settle()
        assertEquals(200f, ui.second().width)
    }

    @Test
    fun `without room for both minimums the space is shared in proportion`() {
        val ui = open { Editor(minFirst = 300f, minSecond = 500f) }

        assertEquals(150f, ui.first().width)
        assertEquals(250f, ui.second().width)
    }

    @Test
    fun `a pane is clipped to its share`() {
        val ui = open {
            Splitter(
                0.25f, {}, Modifier.fillMaxSize(),
                first = { Box(Modifier.fillMaxSize().background(Colour.rgb(0xFF0000))) },
                second = {},
            )
        }
        val wide = ui.drawn().calls.filterIsInstance<DrawCall.Rectangle>().single { it.colour == Colour.rgb(0xFF0000) }
        assertEquals(Rect(0f, 0f, 100f, 300f), wide.clip, "whatever it draws is cut off at the divider")
    }

    // --- the pointer -------------------------------------------------------------------------------

    @Test
    fun `over the divider the cursor is a resize arrow and off it the ordinary one`() {
        val ui = open { Editor() }

        ui.moveTo(ui.onDivider())
        assertEquals(PointerIcon.ResizeHorizontal, ui.pointerIcon)

        ui.moveTo(ui.first().centre)
        assertEquals(PointerIcon.Default, ui.pointerIcon)
    }

    @Test
    fun `a stacked splitter shows the up and down arrow`() {
        val ui = uiTest(Size(300f, 406f)) { Editor(Orientation.Vertical) }.also { opened += it }

        ui.moveTo(ui.onDivider())

        assertEquals(PointerIcon.ResizeVertical, ui.pointerIcon)
    }

    @Test
    fun `dragging the divider moves it with the pointer`() {
        val ui = open { Editor() }

        ui.press(ui.onDivider())
        ui.dragTo(ui.onDivider() + Offset(100f, 40f))
        ui.release()

        near(0.5f, split)
        assertEquals(200f, ui.first().width)
        assertEquals(PointerIcon.ResizeHorizontal, ui.pointerIcon)
    }

    @Test
    fun `the drag keeps the pointer and the arrow however far it wanders`() {
        val ui = open { Editor(minFirst = 50f, minSecond = 100f) }

        ui.press(ui.onDivider())
        ui.dragTo(Offset(390f, 150f))
        assertEquals(PointerIcon.ResizeHorizontal, ui.pointerIcon, "still the arrow over the second pane")
        ui.dragTo(Offset(-500f, 150f))

        assertEquals(50f, ui.first().width, "held at the first pane's minimum")
        ui.dragTo(Offset(900f, 150f))
        ui.release()

        assertEquals(100f, ui.second().width, "and at the second pane's")
    }

    @Test
    fun `past a minimum the divider waits for the pointer to come back`() {
        val ui = open { Editor(minFirst = 80f) }

        val grabbed = ui.onDivider()
        ui.press(grabbed)
        ui.dragTo(grabbed - Offset(60f, 0f))
        assertEquals(80f, ui.first().width)
        ui.dragTo(grabbed - Offset(30f, 0f))

        assertEquals(80f, ui.first().width, "the pointer is still 10 short of the edge")
        ui.dragTo(grabbed - Offset(10f, 0f))
        assertEquals(90f, ui.first().width, "and past it the divider follows again")
    }

    @Test
    fun `a double click puts the divider back where it started`() {
        val ui = open { Editor() }
        ui.press(ui.onDivider())
        ui.dragTo(ui.onDivider() + Offset(100f, 0f))
        ui.release()
        near(0.5f, split)

        ui.click(ui.onDivider())
        near(0.5f, split, "one click does nothing")
        ui.click(ui.onDivider())

        near(0.25f, split)
        assertEquals(100f, ui.first().width)
    }

    @Test
    fun `a double click goes to the default fraction when one is given`() {
        val ui = open { Editor(defaultFraction = 0.6f) }

        ui.click(ui.onDivider())
        ui.click(ui.onDivider())

        near(0.6f, split)
    }

    @Test
    fun `two clicks far apart in time are not a double click`() {
        split = 0.5f
        val ui = open { Editor(defaultFraction = 0.25f) }

        ui.click(ui.onDivider())
        ui.advanceBy(1000)
        ui.click(ui.onDivider())

        assertEquals(emptyList(), reported)
    }

    // --- keys and the pad --------------------------------------------------------------------------

    @Test
    fun `arrow keys nudge the focused divider the way they point`() {
        val ui = open { Editor(initialFocus = true) }
        assertEquals(ui.divider(), ui.focus.focused)

        ui.key(Key.Right)
        near(0.3f, split)
        assertEquals(120f, ui.first().width, 0.01f)

        ui.key(Key.Left)
        ui.key(Key.Left)
        near(0.2f, split)
    }

    @Test
    fun `the pad nudges it too`() {
        val ui = open { Editor(initialFocus = true) }

        ui.pad(GamepadButton.DpadRight)
        ui.pad(GamepadButton.DpadRight)

        near(0.35f, split)
    }

    @Test
    fun `up and down leave a side by side divider alone`() {
        val ui = open { Editor(initialFocus = true) }

        ui.key(Key.Up)
        ui.key(Key.Down)

        assertEquals(emptyList(), reported)
    }

    @Test
    fun `a stacked divider takes up and down`() {
        split = 0.5f
        val ui = uiTest(Size(300f, 406f)) { Editor(Orientation.Vertical, initialFocus = true) }.also { opened += it }

        ui.key(Key.Down)
        near(0.55f, split)
        ui.pad(GamepadButton.DpadUp)
        near(0.5f, split)
    }

    @Test
    fun `at a minimum one more press carries focus out of the divider`() {
        split = 0.5f
        val ui = open { Editor(minSecond = 190f, initialFocus = true) }

        ui.key(Key.Right)
        near(0.525f, split, "the last bit of room before the minimum")
        ui.key(Key.Right)

        ui.assertFocused("properties")
    }

    @Test
    fun `enter twice on the focused divider resets it`() {
        val ui = open { Editor(initialFocus = true) }
        ui.key(Key.Right)
        near(0.3f, split)

        ui.key(Key.Enter)
        ui.key(Key.Enter)

        near(0.25f, split)
    }

    @Test
    fun `a nudge sounds a change and ticks the pad`() {
        var changes = 0
        val sounds = object : UiSounds {
            override fun change() {
                changes++
            }
        }
        val felt = RecordingHaptics()
        val ui = open { ProvideHaptics(felt) { ProvideUiSounds(sounds) { Editor(initialFocus = true) } } }

        ui.key(Key.Right)

        assertEquals(1, changes)
        assertEquals(listOf(Haptic.Tick), felt.performed)
    }

    @Test
    fun `a drag sounds once when it lets go`() {
        var changes = 0
        val sounds = object : UiSounds {
            override fun change() {
                changes++
            }
        }
        val ui = open { ProvideUiSounds(sounds) { Editor() } }

        ui.press(ui.onDivider())
        repeat(5) { ui.dragTo(ui.onDivider() + Offset(10f, 0f)) }
        assertEquals(0, changes)
        ui.release()

        assertEquals(1, changes)
    }

    // --- disabled -------------------------------------------------------------------------------------

    @Test
    fun `a disabled splitter cannot be dragged nudged or focused`() {
        val ui = open { Editor(enabled = false) }

        ui.moveTo(ui.onDivider())
        assertEquals(PointerIcon.Default, ui.pointerIcon)
        ui.press(ui.onDivider())
        ui.dragTo(ui.onDivider() + Offset(100f, 0f))
        ui.release()
        ui.click(ui.onDivider())
        ui.click(ui.onDivider())

        assertEquals(emptyList(), reported)
        assertNotEquals(ui.divider(), ui.focus.focused)
    }

    // --- right to left ----------------------------------------------------------------------------------

    @Test
    fun `right to left the first pane is on the right`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Editor() } }

        assertEquals(Rect(306f, 0f, 406f, 300f), ui.first())
        assertEquals(Rect(300f, 0f, 306f, 300f), ui.divider().boundsInRoot)
        assertEquals(Rect(0f, 0f, 300f, 300f), ui.second())
    }

    @Test
    fun `right to left dragging left grows the first pane`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Editor() } }

        ui.press(ui.onDivider())
        ui.dragTo(ui.onDivider() - Offset(100f, 0f))
        ui.release()

        near(0.5f, split)
        assertEquals(Rect(206f, 0f, 406f, 300f), ui.first())
    }

    @Test
    fun `right to left the arrows still move the divider the way they point`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Editor(initialFocus = true) } }
        val before = ui.divider().boundsInRoot.left

        ui.key(Key.Left)

        near(0.3f, split, "left is towards the second pane, so the first grows")
        assertTrue(ui.divider().boundsInRoot.left < before)
    }

    // --- the skin -----------------------------------------------------------------------------------------

    @Test
    fun `the divider is drawn from the skin in its states`() {
        val ui = open { Editor() }
        val skin = Skin.Default
        assertEquals((skin.resolve("splitter").background as SkinDrawable.Fill).colour, ui.dividerFill())

        ui.moveTo(ui.onDivider())

        val hovered = skin.resolve("splitter", setOf(WidgetState.Hovered))
        assertEquals((hovered.background as SkinDrawable.Fill).colour, ui.dividerFill())
    }

    @Test
    fun `both shipped skins draw the divider`() {
        assertTrue(Skin.Default.has("splitter"))
        assertTrue(Skin.HighContrast.has("splitter"))
    }

    /** The colour of the box drawn over the whole divider. */
    private fun UiTest.dividerFill() = drawn().calls.filterIsInstance<DrawCall.Rectangle>()
        .last { it.rect == divider().boundsInRoot }.colour
}
