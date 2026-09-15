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
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.Hsv
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadAxis
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.ProvideUiSounds
import dev.wildware.composegl.ui.input.UiSounds
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A character creator's colour picker, driven the way a player drives it: the mouse on the square
 * and the strips, arrow keys, the stick and the shoulders, typing a hex code, and pressing a preset.
 * Every test reads the answer off the screen — the colour the screen holds, where focus is, and what
 * was drawn.
 *
 * The picker's panel has 10 of padding in the default skin, so the square starts at 10,10. It is 160
 * across, and the colour inside its 2-wide frame is 156: the point for a saturation and brightness is
 * worked out by [UiTest.onSquare].
 */
class ColourPickerTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(500f, 400f), content = content).also { opened += it }

    /** The colour the screen holds, which the picker reports into. */
    private var tint by mutableStateOf(Hsv(0f, 0.5f, 0.5f).toColour())

    /** Every colour the picker reported, in order. */
    private val reported = mutableListOf<Colour>()

    private val teamColours = listOf(Colour.Red, Colour.Blue, Colour.Green, Colour.Orange.withAlpha(0x80))

    @Composable
    private fun Creator(
        alpha: Boolean = false,
        presets: List<Colour> = emptyList(),
        enabled: Boolean = true,
        initialFocus: Boolean = true,
        follows: Boolean = true,
    ) {
        Column {
            ColourPicker(
                colour = tint,
                onColourChange = { reported += it; if (follows) tint = it },
                modifier = Modifier.testTag("picker"),
                alpha = alpha,
                presets = presets,
                enabled = enabled,
                initialFocus = initialFocus,
            )
            Button("DONE", onClick = {}, modifier = Modifier.testTag("done"))
        }
    }

    // --- reading the screen -----------------------------------------------------------------------

    private fun UiTest.named(name: String): UiNode = checkNotNull(namedOrNull(name)) { "no $name:\n" + dump() }

    private fun UiTest.namedOrNull(name: String): UiNode? {
        var found: UiNode? = null
        root.forEach { if (found == null && it.name == name) found = it }
        return found
    }

    private fun UiTest.square(): Rect = named("colourpicker.square").boundsInRoot

    private fun UiTest.hue(): Rect = named("colourpicker.hue").boundsInRoot

    private fun UiTest.alphaStrip(): Rect = named("colourpicker.alpha").boundsInRoot

    /** The point on the square for [saturation] and [value], left to right. */
    private fun UiTest.onSquare(saturation: Float, value: Float): Offset {
        val box = square()
        return Offset(box.left + 2f + 156f * saturation, box.top + 2f + 156f * (1f - value))
    }

    /** The point on a strip [fraction] of the way down. */
    private fun onStrip(strip: Rect, fraction: Float) = Offset(strip.centre.x, strip.top + 2f + 156f * fraction)

    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = RecordingCanvas(Rect.of(0f, 0f, size.width, size.height))
        DrawPass(canvas).draw(root)
        return canvas
    }

    private fun held(): Hsv = Hsv.of(tint)

    private fun near(expected: Float, actual: Float, message: String? = null, within: Float = 0.01f) =
        assertEquals(expected, actual, within, message)

    // --- how it looks ------------------------------------------------------------------------------

    @Test
    fun `the square and the hue strip sit side by side and the alpha strip only when asked for`() {
        val ui = open { Creator() }
        assertEquals(Rect(10f, 10f, 170f, 170f), ui.square())
        assertEquals(Rect(178f, 10f, 196f, 170f), ui.hue())
        assertNull(ui.namedOrNull("colourpicker.alpha"))

        val withAlpha = open { Creator(alpha = true) }
        assertEquals(Rect(204f, 10f, 222f, 170f), withAlpha.alphaStrip())
    }

    @Test
    fun `the square runs from white to the hue across and down to black`() {
        tint = Hsv(120f, 0.5f, 0.5f).toColour()
        val ui = open { Creator() }
        val inside = ui.square().inset(2f)

        val gradients = ui.drawn().calls.filterIsInstance<DrawCall.GradientRectangle>().filter { it.rect == inside }

        assertEquals(listOf(Brush.horizontal(Colour.White, Colour.Green), Brush.vertical(Colour.Transparent, Colour.Black)), gradients.map { it.brush })
    }

    @Test
    fun `the markers sit where the colour is`() {
        tint = Hsv(90f, 0.25f, 0.75f).toColour()
        val ui = open { Creator() }
        val borders = ui.drawn().calls.filterIsInstance<DrawCall.Border>()

        val ring = ui.onSquare(0.25f, 0.75f)
        assertTrue(borders.any { abs(it.rect.centre.x - ring.x) < 0.5f && abs(it.rect.centre.y - ring.y) < 0.5f }, "no ring at $ring")
        val bar = ui.hue().top + 2f + 156f * 0.25f
        assertTrue(borders.any { abs(it.rect.centre.y - bar) < 0.5f && it.rect.width >= 18f }, "no bar across the hue strip at $bar")
    }

    @Test
    fun `a see through swatch is drawn over a checkerboard and a solid one is not`() {
        val ui = open {
            Column {
                ColourSwatch(Colour.Red.withAlpha(0x40), Modifier.testTag("clear"))
                ColourSwatch(Colour.Red, Modifier.testTag("solid"))
            }
        }
        val checker = Skin.Default.resolve("colourpicker.checker").textColour
        val calls = ui.drawn().calls.filterIsInstance<DrawCall.Rectangle>()

        assertTrue(calls.any { it.colour == checker && it.rect.top < ui.node("clear").boundsInRoot.bottom })
        assertTrue(calls.none { it.colour == checker && it.rect.top >= ui.node("solid").boundsInRoot.top })
    }

    // --- the pointer --------------------------------------------------------------------------------

    @Test
    fun `a press on the square picks saturation across and brightness up`() {
        val ui = open { Creator() }

        ui.click(ui.onSquare(0.75f, 0.25f))

        near(0f, held().hue, "the hue is not the square's to change")
        near(0.75f, held().saturation)
        near(0.25f, held().value)
    }

    @Test
    fun `a drag off the edge of the square holds the marker at the edge`() {
        val ui = open { Creator() }

        ui.press(ui.onSquare(0.5f, 0.5f))
        ui.dragTo(Offset(480f, -50f))
        ui.release()

        assertEquals(Colour.Red, tint, "full saturation and full brightness: pure red")
    }

    @Test
    fun `dragging down the hue strip turns the hue and keeps the rest`() {
        val ui = open { Creator() }

        ui.press(onStrip(ui.hue(), 0.1f))
        ui.dragTo(onStrip(ui.hue(), 1f / 3f))
        ui.release()

        near(120f, held().hue, within = 1f)
        near(0.5f, held().saturation)
        near(0.5f, held().value)
    }

    @Test
    fun `the alpha strip sets how see through the colour is`() {
        val ui = open { Creator(alpha = true) }

        ui.click(onStrip(ui.alphaStrip(), 0.75f))

        assertEquals(64, tint.alpha, "a quarter of the way up from nothing")
        near(0.5f, held().saturation)
    }

    @Test
    fun `without alpha the colour keeps the alpha it came with`() {
        tint = Colour.Red.withAlpha(0x80)
        val ui = open { Creator() }

        ui.click(ui.onSquare(0f, 1f))

        assertEquals(Colour.White.withAlpha(0x80), tint)
    }

    @Test
    fun `a colour dragged to grey keeps its hue for when it comes back`() {
        tint = Hsv(200f, 0.8f, 0.8f).toColour()
        val ui = open { Creator() }

        ui.press(ui.onSquare(0.8f, 0.8f))
        ui.dragTo(ui.onSquare(0f, 0.8f))
        ui.dragTo(ui.onSquare(0.8f, 0.8f))
        ui.release()

        near(200f, held().hue, within = 1f)
    }

    @Test
    fun `nothing moves until the screen takes the colour`() {
        val ui = open { Creator(follows = false) }
        val outline = Skin.Default.resolve("colourpicker.marker").background as SkinDrawable.Fill
        fun UiTest.markers() = drawn().calls.filterIsInstance<DrawCall.Border>().filter { it.colour == outline.colour }.map { it.rect }
        val before = ui.markers()

        ui.click(ui.onSquare(1f, 1f))

        assertEquals(listOf(Colour.Red), reported)
        assertEquals(before, ui.markers(), "the ring stays where the screen's colour is")
    }

    @Test
    fun `a drag sounds once when it lets go`() {
        var changes = 0
        val sounds = object : UiSounds {
            override fun change() {
                changes++
            }
        }
        val ui = open { ProvideUiSounds(sounds) { Creator() } }

        ui.press(ui.onSquare(0.2f, 0.2f))
        repeat(5) { ui.dragTo(ui.onSquare(0.2f + it * 0.1f, 0.3f)) }
        assertEquals(0, changes)
        ui.release()

        assertEquals(1, changes)
    }

    // --- keys and the d-pad ----------------------------------------------------------------------------

    @Test
    fun `arrow keys move the marker on the focused square`() {
        val ui = open { Creator() }
        assertEquals(ui.named("colourpicker.square"), ui.focus.focused)

        ui.key(Key.Right)
        ui.key(Key.Right)
        ui.key(Key.Up)

        near(0.6f, held().saturation)
        near(0.55f, held().value)
    }

    @Test
    fun `the d-pad moves it too`() {
        val ui = open { Creator() }

        ui.pad(GamepadButton.DpadLeft)
        ui.pad(GamepadButton.DpadDown)

        near(0.45f, held().saturation)
        near(0.45f, held().value)
    }

    @Test
    fun `at the edge of the square one more press moves focus to the hue strip`() {
        tint = Colour.Red
        val ui = open { Creator() }

        ui.key(Key.Right)

        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun `arrow keys on the hue strip turn the hue and stop at its ends`() {
        val ui = open { Creator() }
        ui.key(Key.Tab)
        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)

        ui.key(Key.Down)
        ui.key(Key.Down)
        near(36f, held().hue, within = 1f)

        ui.key(Key.Up)
        ui.key(Key.Up)
        near(0f, held().hue, within = 1f)
        val before = reported.size
        ui.key(Key.Up)
        assertEquals(before, reported.size, "nothing is above red")
    }

    @Test
    fun `at the bottom of the hue strip one more press moves focus down to the hex field`() {
        tint = Hsv(350f, 0.5f, 0.5f).toColour()
        val ui = open { Creator() }
        ui.key(Key.Tab)

        ui.key(Key.Down)
        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)
        ui.key(Key.Down)

        val focused = checkNotNull(ui.focus.focused)
        assertTrue(generateSequence(ui.named("field")) { it.parent }.any { it === focused }, "focus is on ${'$'}focused, not the hex field")
    }

    @Test
    fun `arrow keys on the alpha strip change the alpha`() {
        val ui = open { Creator(alpha = true) }
        ui.key(Key.Tab)
        ui.key(Key.Tab)
        assertEquals(ui.named("colourpicker.alpha"), ui.focus.focused)

        ui.key(Key.Down)

        assertEquals(242, tint.alpha)
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
        val ui = open { ProvideHaptics(felt) { ProvideUiSounds(sounds) { Creator() } } }

        ui.key(Key.Right)

        assertEquals(1, changes)
        assertEquals(listOf(Haptic.Tick), felt.performed)
    }

    // --- the stick and the shoulders ---------------------------------------------------------------------

    @Test
    fun `the stick moves the marker smoothly with no virtual cursor`() {
        val ui = open { Creator() }

        ui.holdStick(1f, 0f, millis = 250)

        val now = held()
        assertTrue(now.saturation > 0.6f && now.saturation < 0.75f, "a quarter of a second at full push: ${now.saturation}")
        near(0.5f, now.value)
        assertEquals(ui.named("colourpicker.square"), ui.focus.focused, "the stick moved the marker, not focus")
    }

    @Test
    fun `the stick moves diagonally and faster the further it is pushed`() {
        val ui = open { Creator() }

        ui.holdStick(0.5f, -0.5f, millis = 250)
        val half = held()
        tint = Hsv(0f, 0.5f, 0.5f).toColour()
        ui.settle()
        ui.holdStick(1f, -1f, millis = 250)
        val full = held()

        assertTrue(half.saturation > 0.5f && half.value > 0.5f, "up and to the right at once: $half")
        assertTrue(full.saturation - 0.5f > 1.5f * (half.saturation - 0.5f), "$full against $half")
    }

    @Test
    fun `pushed to an edge and held the stick stays on the square`() {
        val ui = open { Creator() }

        ui.stick(1f, 0f)

        near(1f, held().saturation)
        assertEquals(ui.named("colourpicker.square"), ui.focus.focused)
        ui.stick(0f, 0f)
    }

    @Test
    fun `a fresh push against the edge the marker is on moves focus`() {
        tint = Colour.Red
        val ui = open { Creator() }

        ui.stick(1f, 0f)
        ui.stick(0f, 0f)

        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun `the shoulders turn the hue and wrap past red`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator() }

        ui.pad(GamepadButton.RightBumper)
        near(10f, held().hue, within = 1f)

        ui.pad(GamepadButton.LeftBumper)
        ui.pad(GamepadButton.LeftBumper)
        near(350f, held().hue, within = 1f)
    }

    @Test
    fun `holding a shoulder keeps the hue turning`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator() }

        ui.padDown(GamepadButton.RightBumper)
        ui.advanceBy(1000)
        ui.padUp(GamepadButton.RightBumper)
        val after = held().hue
        ui.advanceBy(500)

        assertTrue(after > 60f, "a second held turns it a long way: $after")
        near(after, held().hue, "and it stops when let go", within = 0.01f)
    }

    @Test
    fun `a shoulder held while focus leaves the picker stops turning the hue`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator() }

        ui.padDown(GamepadButton.RightBumper)
        ui.advanceBy(600)
        assertTrue(held().hue > 10f, "it was turning: ${held().hue}")
        ui.click("done")
        ui.assertFocused("done")
        // The release goes to the button focus moved to, and the picker never hears it.
        ui.padUp(GamepadButton.RightBumper)
        val after = held().hue
        val reports = reported.size
        ui.advanceBy(1000)

        near(after, held().hue, "the hue stopped when focus left", within = 0.01f)
        assertEquals(reports, reported.size, "and nothing more was reported")
    }

    @Test
    fun `a shoulder held while focus moves inside the picker keeps turning the hue`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator() }

        ui.padDown(GamepadButton.RightBumper)
        ui.key(Key.Tab)
        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)
        val moved = held().hue
        ui.advanceBy(1000)
        ui.padUp(GamepadButton.RightBumper)

        assertTrue(held().hue > moved + 60f, "still turning from the hue strip: $moved then ${held().hue}")
    }

    @Test
    fun `unplugging the pad with a shoulder held stops turning the hue`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Settings() }
        ui.pad(GamepadButton.South)

        ui.padDown(GamepadButton.RightBumper)
        ui.advanceBy(600)
        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.settle()
        val after = held().hue
        ui.advanceBy(1000)

        assertTrue(after > 10f, "it was turning: $after")
        near(after, held().hue, "the hue stopped with the pad gone", within = 0.01f)
        ui.named("colourpicker.square")
    }

    @Test
    fun `another pad being unplugged leaves a held shoulder turning`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator() }

        ui.padDown(GamepadButton.RightBumper)
        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId(1)))
        ui.settle()
        val before = held().hue
        ui.advanceBy(1000)
        ui.padUp(GamepadButton.RightBumper)

        assertTrue(held().hue > before + 60f, "the first pad still holds it: $before then ${held().hue}")
    }

    @Test
    fun `unplugging the pad with the stick pushed stops the marker`() {
        val ui = open { Creator() }

        // Pushed and pulled out before a single frame, so every bit of travel after it is the stale push.
        ui.input.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 0.6f))
        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.advanceBy(1000)

        near(0.5f, held().saturation, "the marker did not slide on with nobody pushing")
        assertEquals(ui.named("colourpicker.square"), ui.focus.focused)

        ui.holdStick(1f, 0f, millis = 100)
        assertTrue(held().saturation > 0.5f, "a pad plugged back in steers again: ${held().saturation}")
    }

    @Test
    fun `another pad's right stick does not take over the left stick of the pad steering`() {
        val ui = open { Creator() }

        ui.input.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 0.6f))
        ui.input.onGamepad(GamepadEvent.Axis(GamepadId(1), GamepadAxis.RightX, 0.8f))
        ui.input.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        ui.advanceBy(1000)

        near(0.5f, held().saturation, "the steering pad was unplugged, so the marker stopped")
    }

    @Test
    fun `the shoulders work wherever focus is inside the picker`() {
        tint = Hsv(0f, 1f, 1f).toColour()
        val ui = open { Creator(presets = teamColours) }
        ui.key(Key.Tab)
        ui.key(Key.Tab)

        ui.pad(GamepadButton.RightBumper)

        near(10f, held().hue, within = 1f)
    }

    // --- the hex field ------------------------------------------------------------------------------------

    /** What the hex field says: the first code drawn, as the pad keyboard's line is drawn after it. */
    private fun UiTest.hexText(): String = drawn().calls.filterIsInstance<DrawCall.Text>().first { it.text.startsWith("#") }.text

    private fun UiTest.clearField() {
        click(named("field").boundsInRoot.centre)
        key(Key.End)
        repeat(9) { key(Key.Backspace) }
    }

    @Test
    fun `the hex field says the colour`() {
        tint = Colour.Orange
        val ui = open { Creator() }

        assertEquals("#FF8000", ui.hexText())
    }

    @Test
    fun `typing a hex code changes the colour`() {
        val ui = open { Creator() }

        ui.clearField()
        ui.type("#00FF00")

        assertEquals(Colour.Green, tint)
    }

    @Test
    fun `half a hex code is left as typed until it is a colour`() {
        val ui = open { Creator() }
        ui.clearField()

        ui.type("#12")
        assertEquals("#12", ui.hexText(), "not put back to the colour while it is still being typed")
        ui.type("3")
        assertEquals(Colour.rgb(0x112233), tint, "#123 is a colour already")
        ui.type("456")

        assertEquals(Colour.rgb(0x123456), tint)
        assertEquals("#123456", ui.hexText())
    }

    @Test
    fun `leaving the field tidies what was typed`() {
        val ui = open { Creator() }
        ui.clearField()
        ui.type("abc")

        ui.key(Key.Tab)

        assertEquals("#AABBCC", ui.hexText())
    }

    /** Walks focus onto [tag] with the d-pad alone, looking where it is and pressing towards it. */
    private fun UiTest.walkTo(tag: String) {
        val goal = node(tag)
        repeat(40) {
            val at = focus.focused ?: error("nothing focused while walking to #$tag")
            if (at === goal) return
            val from = at.boundsInRoot
            val to = goal.boundsInRoot
            val dy = to.centre.y - from.centre.y
            val dx = to.centre.x - from.centre.x
            pad(
                when {
                    abs(dy) > from.height / 2f -> if (dy > 0) GamepadButton.DpadDown else GamepadButton.DpadUp
                    dx > 0 -> GamepadButton.DpadRight
                    else -> GamepadButton.DpadLeft
                },
            )
        }
        assertFocused(tag)
    }

    /**
     * A picker inside [ProvideGamepadKeyboard], with the pad walked from the square to the right edge,
     * onto the hue strip, and down past its end onto the hex field, which opens the keyboard.
     */
    private fun padKeyboardOnHexField(): UiTest {
        tint = Hsv(350f, 0.5f, 0.5f).toColour()
        val ui = uiTest(Size(800f, 600f)) { ProvideGamepadKeyboard { Creator() } }.also { opened += it }
        repeat(20) { if (ui.focus.focused !== ui.named("colourpicker.hue")) ui.pad(GamepadButton.DpadRight) }
        assertEquals(ui.named("colourpicker.hue"), ui.focus.focused)
        repeat(20) { if (ui.root.findOrNull("gamepad-keyboard") == null) ui.pad(GamepadButton.DpadDown) }
        ui.assertExists("gamepad-keyboard")
        return ui
    }

    @Test
    fun `a pad player types a hex code with the pad keyboard`() {
        val ui = padKeyboardOnHexField()

        ui.walkTo("gamepad-keyboard.delete")
        repeat(6) { ui.pad(GamepadButton.South) }
        assertEquals("#", ui.hexText(), "what the keyboard deleted stays deleted")
        for (key in listOf("0", "0", "f", "f", "0", "0")) {
            ui.walkTo("gamepad-keyboard.key.$key")
            ui.pad(GamepadButton.South)
        }

        assertEquals(Colour.Green, tint)
        ui.walkTo("gamepad-keyboard.done")
        ui.pad(GamepadButton.South)
        ui.assertDoesNotExist("gamepad-keyboard")
        assertEquals("#00FF00", ui.hexText(), "tidied into capitals when the keyboard closes")
    }

    @Test
    fun `half a code typed with the pad keyboard is tidied once the keyboard closes and focus leaves`() {
        val ui = padKeyboardOnHexField()

        ui.walkTo("gamepad-keyboard.delete")
        repeat(4) { ui.pad(GamepadButton.South) }
        assertEquals(3, ui.hexText().length, "half a code stays while the keyboard is open")
        ui.walkTo("gamepad-keyboard.done")
        ui.pad(GamepadButton.South)
        ui.pad(GamepadButton.DpadDown)

        assertEquals(tint.toHex(false), ui.hexText(), "put back to the colour once focus left the field")
    }

    @Test
    fun `the field follows a colour changed from elsewhere`() {
        val ui = open { Creator() }

        tint = Colour.Blue
        ui.settle()

        assertEquals("#0000FF", ui.hexText())
    }

    @Test
    fun `with alpha the field reads and writes the alpha too`() {
        tint = Colour.Orange
        val ui = open { Creator(alpha = true) }
        assertEquals("#FFFF8000", ui.hexText())

        ui.clearField()
        ui.type("#400000FF")

        assertEquals(Colour.Blue.withAlpha(0x40), tint)
    }

    @Test
    fun `without alpha eight digits are not taken`() {
        val ui = open { Creator() }
        ui.clearField()

        ui.type("#400000F")

        assertEquals(emptyList(), reported.filter { it.blue == 0x0F })
    }

    // --- presets ------------------------------------------------------------------------------------

    private fun UiTest.presets(): List<UiNode> {
        val found = mutableListOf<UiNode>()
        root.forEach { if (it.name == "colourswatch" && it.boundsInRoot.width == 20f) found += it }
        return found
    }

    @Test
    fun `a click on a preset picks it`() {
        val ui = open { Creator(presets = teamColours) }

        ui.click(ui.presets()[1].boundsInRoot.centre)

        assertEquals(Colour.Blue, tint)
    }

    @Test
    fun `a preset is picked with enter or the pad`() {
        val ui = open { Creator(presets = teamColours) }
        val third = ui.presets()[2]
        ui.click(third.boundsInRoot.centre)
        tint = Colour.Red
        ui.settle()
        assertEquals(third, ui.focus.focused)

        ui.key(Key.Enter)
        assertEquals(Colour.Green, tint)

        tint = Colour.Red
        ui.settle()
        ui.pad(GamepadButton.South)
        assertEquals(Colour.Green, tint)
    }

    @Test
    fun `a see through preset keeps the colour's alpha unless the picker has alpha`() {
        val ui = open { Creator(presets = teamColours) }
        ui.click(ui.presets()[3].boundsInRoot.centre)
        assertEquals(Colour.Orange, tint)

        val withAlpha = open { Creator(alpha = true, presets = teamColours) }
        withAlpha.click(withAlpha.presets()[3].boundsInRoot.centre)
        assertEquals(Colour.Orange.withAlpha(0x80), tint)
    }

    // --- right to left ------------------------------------------------------------------------------

    @Test
    fun `right to left grey is on the right of the square and the strips on its left`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Creator() } }
        assertTrue(ui.hue().right <= ui.square().left)

        val box = ui.square()
        ui.click(Offset(box.right - 2f - 156f * 0.25f, box.top + 2f))

        near(0.25f, held().saturation)
        near(1f, held().value)
    }

    @Test
    fun `right to left the arrows still move the marker the way they point`() {
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Creator() } }

        ui.key(Key.Left)

        near(0.55f, held().saturation, "left is towards the strong end on this screen")
    }

    @Test
    fun `right to left the square is drawn mirrored`() {
        tint = Hsv(240f, 0.5f, 0.5f).toColour()
        val ui = open { ProvideLayoutDirection(LayoutDirection.Rtl) { Creator() } }
        val inside = ui.square().inset(2f)

        val first = ui.drawn().calls.filterIsInstance<DrawCall.GradientRectangle>().first { it.rect == inside }

        assertEquals(Brush.horizontal(Colour.Blue, Colour.White), first.brush)
    }

    // --- disabled -------------------------------------------------------------------------------------

    @Test
    fun `a disabled picker ignores the pointer the keys and the pad`() {
        val ui = open { Creator(enabled = false, presets = teamColours) }

        ui.click(ui.onSquare(1f, 1f))
        ui.click(onStrip(ui.hue(), 0.5f))
        ui.click(ui.presets()[0].boundsInRoot.centre)
        ui.key(Key.Right)
        ui.pad(GamepadButton.RightBumper)
        ui.holdStick(1f, 0f, millis = 200)

        assertEquals(emptyList(), reported)
        assertNotEquals(ui.named("colourpicker.square"), ui.focus.focused)
    }

    // --- the swatch and the popup ----------------------------------------------------------------------

    @Test
    fun `a swatch with onClick is a button`() {
        var clicks = 0
        val ui = open { ColourSwatch(Colour.Red, Modifier.testTag("swatch"), onClick = { clicks++ }, initialFocus = true) }

        ui.click("swatch")
        ui.key(Key.Enter)
        ui.pad(GamepadButton.South)

        assertEquals(3, clicks)
    }

    @Test
    fun `a swatch with no onClick only shows`() {
        val ui = open { ColourSwatch(Colour.Red, Modifier.testTag("swatch"), size = 30f) }

        assertEquals(30f, ui.node("swatch").boundsInRoot.width)
        assertNull(ui.focus.focused)
    }

    @Composable
    private fun Settings(alpha: Boolean = false) {
        PopupHost {
            Box(Modifier.fillMaxSize()) {
                Column {
                    ColourPickerButton(
                        colour = tint,
                        onColourChange = { reported += it; tint = it },
                        modifier = Modifier.testTag("crosshair"),
                        alpha = alpha,
                        initialFocus = true,
                    )
                    Button("APPLY", onClick = {}, modifier = Modifier.testTag("apply"))
                }
                Button("ELSEWHERE", onClick = {}, modifier = Modifier.offset(400f, 350f).testTag("elsewhere"))
            }
        }
    }

    @Test
    fun `the picker button opens the picker under itself with focus on the square`() {
        val ui = open { Settings() }
        assertNull(ui.namedOrNull("colourpicker.square"))

        ui.click("crosshair")

        val square = ui.named("colourpicker.square")
        assertTrue(square.boundsInRoot.top > ui.node("crosshair").boundsInRoot.bottom)
        assertEquals(square, ui.focus.focused)
    }

    @Test
    fun `the colour changes live while the picker is open`() {
        val ui = open { Settings() }
        ui.pad(GamepadButton.South)

        ui.key(Key.Right)
        ui.pad(GamepadButton.RightBumper)

        near(0.55f, held().saturation)
        near(10f, held().hue, within = 1f)
        val drawn = ui.drawn().calls.filterIsInstance<DrawCall.Rectangle>()
        assertTrue(drawn.any { it.colour == tint && it.rect == ui.node("crosshair").boundsInRoot.inset(2f) }, "the swatch shows it")
    }

    @Test
    fun `escape closes the picker and focus goes back to the swatch`() {
        val ui = open { Settings() }
        ui.click("crosshair")

        ui.key(Key.Escape)

        assertNull(ui.namedOrNull("colourpicker.square"))
        ui.assertFocused("crosshair")
    }

    @Test
    fun `the pad's east closes it too`() {
        val ui = open { Settings() }
        ui.pad(GamepadButton.South)

        ui.pad(GamepadButton.East)

        assertNull(ui.namedOrNull("colourpicker.square"))
        ui.assertFocused("crosshair")
    }

    @Test
    fun `another press on the swatch closes it`() {
        val ui = open { Settings() }
        ui.click("crosshair")
        ui.named("colourpicker.square")

        ui.click("crosshair")

        assertNull(ui.namedOrNull("colourpicker.square"))
        ui.assertFocused("crosshair")
        assertEquals(emptyList(), reported)
    }

    @Test
    fun `the pad's back button closes it too`() {
        val ui = open { Settings() }
        ui.pad(GamepadButton.South)
        ui.named("colourpicker.square")

        ui.pad(GamepadButton.Back)

        assertNull(ui.namedOrNull("colourpicker.square"))
        ui.assertFocused("crosshair")
    }

    @Test
    fun `a press outside closes it and a press on the panel does not`() {
        val ui = open { Settings() }
        ui.click("crosshair")
        val panel = ui.named("colourpicker.square").parent!!.parent!!.boundsInRoot

        ui.click(Offset(panel.left + 3f, panel.bottom - 3f))
        ui.named("colourpicker.square")

        ui.click(Offset(480f, 20f))
        assertNull(ui.namedOrNull("colourpicker.square"))
        assertEquals(emptyList(), reported)
    }

    // --- the skin -------------------------------------------------------------------------------------

    @Test
    fun `the focused square is framed in the skin's focused colour`() {
        val ui = open { Creator() }

        val frame = ui.drawn().calls.filterIsInstance<DrawCall.Border>().first { it.rect == ui.square() }

        val focused = Skin.Default.resolve("colourpicker.area", setOf(WidgetState.Focused)).background as SkinDrawable.Fill
        assertEquals(focused.border, frame.colour)
    }

    @Test
    fun `both shipped skins draw the picker`() {
        listOf("colourpicker", "colourpicker.area", "colourpicker.marker", "colourpicker.checker", "colourswatch").forEach {
            assertTrue(Skin.Default.has(it), it)
            assertTrue(Skin.HighContrast.has(it), it)
        }
    }

    private fun abs(value: Float) = kotlin.math.abs(value)
}
