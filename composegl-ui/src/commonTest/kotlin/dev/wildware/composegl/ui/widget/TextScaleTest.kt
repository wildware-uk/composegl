package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextDecoration
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.scaledTextSizes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The player's text size, kept apart from the interface's scale.
 *
 * Every test composes a real screen with [uiTest], changes nothing but the text scale around it,
 * and reads the result off the boxes that were laid out and the places text was drawn. The
 * monospace font makes the numbers checkable on paper: a character is 0.6 of the size across and a
 * line is 1.25 of it down, so "HULL" at 16 is 38.4 by 20 and at 24 is 57.6 by 30.
 */
class TextScaleTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(size: Size = Size(400f, 300f), content: @Composable () -> Unit): UiTest =
        uiTest(size, content = content).also { opened += it }

    private val sixteen = TextStyle(size = 16f)

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "$message: expected $expected, was $actual")

    // --- the style ---------------------------------------------------------------------------------

    @Test
    fun `a scaled style is the size times the scale and nothing else changes`() {
        val style = TextStyle(family = "body", size = 16f, lineHeightRatio = 1.5f, maxLines = 2, ellipsis = "...")
        val big = style.scaled(1.5f)

        assertEquals(TextStyle(family = "body", size = 24f, lineHeightRatio = 1.5f, maxLines = 2, ellipsis = "..."), big)
        assertEquals(36f, big.lineHeight, "the line height grows with the size")
    }

    @Test
    fun `a scaled size is rounded to a whole size so a font baked at it exists`() {
        assertEquals(18f, TextStyle(size = 16f).scaled(1.1f).size, "17.6 is drawn from the glyphs baked at 18")
        assertEquals(1f, TextStyle(size = 2f).scaled(0.1f).size, "and never shrinks to nothing")
    }

    @Test
    fun `a scale of one is the same style rather than a copy`() {
        val style = TextStyle(size = 13.5f)
        assertSame(style, style.scaled(1f))
    }

    @Test
    fun `a scale that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { TextStyle.Default.scaled(0f) }
        assertFailsWith<IllegalArgumentException> { TextStyle.Default.scaled(-1f) }
        assertFailsWith<IllegalArgumentException> { TextStyle.Default.scaled(Float.NaN) }
    }

    @Test
    fun `the sizes to register cover every size at every scale once each`() {
        // 13 at 125% is 16.25 and at 150% is 19.5, which round onto sizes sixteen already needs.
        assertEquals(listOf(13, 16, 20, 24), scaledTextSizes(listOf(16, 13), listOf(1f, 1.25f, 1.5f)))
        assertEquals(listOf(16, 20, 24), scaledTextSizes(listOf(16), listOf(1.5f, 1f, 1.25f)), "sorted whatever order")
        assertEquals(listOf(16, 20), scaledTextSizes(listOf(16, 16), listOf(1f, 1.25f, 1.25f)))
        assertEquals(listOf(14, 16, 18), scaledTextSizes(listOf(16), listOf(0.875f, 1f, 1.1f)), "rounded as the widgets round")
    }

    // --- labels ------------------------------------------------------------------------------------

    @Test
    fun `a label is laid out at the text scale`() {
        val ui = open {
            ProvideTextScale(1.5f) { Text("HULL", Modifier.testTag("hull"), textStyle = sixteen) }
        }

        assertNear(57.6f, ui.node("hull").width, "four characters at 24")
        assertNear(30f, ui.node("hull").height, "one line at 24")
        ui.assertText("hull", "HULL")
    }

    @Test
    fun `without a text scale a label is the size its style says`() {
        val ui = open { Text("HULL", Modifier.testTag("hull"), textStyle = sixteen) }

        assertNear(38.4f, ui.node("hull").width, "four characters at 16")
        assertNear(20f, ui.node("hull").height, "one line at 16")
    }

    @Test
    fun `the text scale leaves everything that is not text the size it was`() {
        val ui = open {
            ProvideTextScale(2f) {
                Row {
                    Box(Modifier.size(40f).testTag("icon"))
                    Text("HP", Modifier.testTag("hp"), textStyle = sixteen)
                }
            }
        }

        assertEquals(40f, ui.node("icon").width)
        assertEquals(40f, ui.node("icon").height)
        assertEquals(40f, ui.node("hp").boundsInRoot.left, "the label still starts where the icon ends")
        assertNear(38.4f, ui.node("hp").width, "two characters at 32")
    }

    @Test
    fun `nested text scales multiply`() {
        val ui = open {
            ProvideTextScale(2f) {
                ProvideTextScale(0.75f) { Text("HULL", Modifier.testTag("hull"), textStyle = sixteen) }
            }
        }

        assertNear(57.6f, ui.node("hull").width, "two times three quarters is text at 24")
    }

    @Test
    fun `nested text scales that cancel out leave a fractional size alone`() {
        // 1.45 times 1/1.45 is 0.99999994 in a float. Taken at face value that rounds 13.5 to 14.
        val ui = open {
            ProvideTextScale(1.45f) {
                ProvideTextScale(1f / 1.45f) { Text("HULL", Modifier.testTag("hull"), textStyle = TextStyle(size = 13.5f)) }
            }
        }

        assertNear(4 * 13.5f * 0.6f, ui.node("hull").width, "four characters at 13.5, not at 14")
    }

    @Test
    fun `an empty label under a text scale is one scaled line tall`() {
        val ui = open {
            Column {
                ProvideTextScale(1.5f) { Text("", Modifier.testTag("empty"), textStyle = sixteen) }
                Text("", Modifier.testTag("direct"), textStyle = TextStyle(size = 24f))
            }
        }

        assertEquals(ui.node("direct").width, ui.node("empty").width)
        assertEquals(ui.node("direct").height, ui.node("empty").height)
    }

    @Test
    fun `a still screen under a text scale does not redraw even when it composes again`() {
        var tick by mutableStateOf(0)
        val ui = open {
            ProvideTextScale(1.5f) {
                TooltipHost {
                    Column {
                        // Read in here, so every tick composes each widget below again rather than
                        // letting Compose skip the unchanged lambdas round them.
                        @Suppress("UNUSED_EXPRESSION") tick
                        Text("HULL", textStyle = sixteen)
                        Text("HULL", textStyle = sixteen, runs = listOf(TextRun(TextRange(0, 2), decoration = TextDecoration.Underline)))
                        Button("PLAY", onClick = {})
                        TextField("Ada", onValueChange = {}, modifier = Modifier.width(200f))
                        PromptGlyph(Action.Confirm)
                        Typewriter(rememberTypewriter("HELLO"), textStyle = sixteen)
                    }
                }
            }
        }
        ui.advanceBy(2_000)
        ui.render()
        assertFalse(ui.render(), "nothing changed, so nothing is drawn again")

        // Straight to a frame: the frame is what composes and lays out the change, and settling
        // first would use up the answer to "did anything change".
        tick++

        assertFalse(ui.render(), "composing the same scale again is not a change")
    }

    @Test
    fun `a label at an uneven scale is laid out at the whole size it rounds to`() {
        val ui = open {
            ProvideTextScale(1.1f) { Text("HULL", Modifier.testTag("hull"), textStyle = sixteen) }
        }

        assertNear(43.2f, ui.node("hull").width, "four characters at 18")
    }

    @Test
    fun `a skin style is scaled as well as an explicit one`() {
        val skinned = Skin.Default.resolve("label").textStyle
        val ui = open {
            Column {
                Text("HULL", Modifier.testTag("plain"))
                ProvideTextScale(2f) { Text("HULL", Modifier.testTag("big")) }
            }
        }

        assertNear(4 * skinned.size * 0.6f, ui.node("plain").width, "the skin's own size")
        assertNear(4 * skinned.size * 2f * 0.6f, ui.node("big").width, "twice the skin's size")
        assertNear(skinned.lineHeight * 2f, ui.node("big").height, "and twice its line height")
    }

    @Test
    fun `a paragraph in a fixed width wraps onto more lines when the text is bigger`() {
        val words = "one two three four five six"
        val ui = open {
            Column {
                Text(words, Modifier.width(120f).testTag("small"), textStyle = sixteen)
                ProvideTextScale(1.5f) { Text(words, Modifier.width(120f).testTag("big"), textStyle = sixteen) }
            }
        }

        val smallLines = ui.node("small").height / 20f
        val bigLines = ui.node("big").height / 30f
        assertEquals(120f, ui.node("big").width, "the width it was given is kept")
        assertTrue(bigLines > smallLines, "bigger text in the same width needs more lines: $smallLines then $bigLines")
    }

    @Test
    fun `styled runs are laid out at the text scale`() {
        val ui = open {
            ProvideTextScale(1.5f) {
                Text(
                    "HULL",
                    Modifier.testTag("hull"),
                    textStyle = sixteen,
                    runs = listOf(TextRun(TextRange(0, 2), decoration = TextDecoration.Underline)),
                )
            }
        }

        assertNear(57.6f, ui.node("hull").width, "four characters at 24")
        assertNear(30f, ui.node("hull").height, "one line at 24")
    }

    // --- the setting, changed on a running screen -------------------------------------------------

    /** A label and the two buttons a settings menu would put beside it. */
    @Composable
    private fun Settings() {
        var scale by remember { mutableStateOf(1f) }
        ProvideTextScale(scale) {
            Column {
                Text("HULL", Modifier.testTag("hull"), textStyle = sixteen)
                Button("BIGGER", onClick = { scale = 1.5f }, initialFocus = true, modifier = Modifier.testTag("bigger"))
                Button("SMALLER", onClick = { scale = 1f }, modifier = Modifier.testTag("smaller"))
            }
        }
    }

    @Test
    fun `clicking a text size button makes the text on the screen bigger`() {
        val ui = open { Settings() }
        assertNear(20f, ui.node("hull").height, "before")

        ui.click("bigger")

        assertNear(30f, ui.node("hull").height, "after")
        assertNear(57.6f, ui.node("hull").width, "after")
        ui.assertText("hull", "HULL")
    }

    @Test
    fun `the text size can be changed back from a pad`() {
        val ui = open { Settings() }
        ui.click("bigger")
        val big = ui.node("bigger").height

        ui.assertFocused("bigger")
        ui.pad(dev.wildware.composegl.ui.input.GamepadButton.DpadDown)
        ui.assertFocused("smaller")
        ui.pad(dev.wildware.composegl.ui.input.GamepadButton.South)

        assertNear(20f, ui.node("hull").height, "the label is back to 16")
        assertTrue(ui.node("bigger").height < big, "and the buttons shrink back round their labels")
    }

    @Test
    fun `a button grows to fit a scaled label and still takes a click`() {
        var clicks = 0
        val ui = open {
            Column {
                Button("PLAY", onClick = {}, modifier = Modifier.testTag("plain"))
                ProvideTextScale(2f) { Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("big")) }
            }
        }

        val label = Skin.Default.resolve("button").textStyle
        val plain = ui.node("plain")
        val big = ui.node("big")
        assertNear(plain.width + 4 * label.size * 0.6f, big.width, "wider by the label's growth and no more")
        assertNear(plain.height + label.lineHeight, big.height, "taller by one more line height and no more")

        ui.click("big")
        assertEquals(1, clicks)
    }

    // --- the other widgets that draw text -----------------------------------------------------------

    @Test
    fun `a field under a text scale is a scaled line tall and a click lands on the scaled letters`() {
        val style = Skin.Default.resolve("field")
        val ui = open {
            var name by remember { mutableStateOf("") }
            Column {
                TextField("", onValueChange = {}, modifier = Modifier.width(200f).testTag("plain"))
                ProvideTextScale(2f) {
                    TextField(name, onValueChange = { name = it }, modifier = Modifier.width(200f).testTag("name"))
                }
            }
        }

        assertNear(ui.node("plain").height + style.textStyle.lineHeight, ui.node("name").height, "one line, twice as tall")

        ui.click("name")
        ui.type("Ada")
        ui.assertText("name", "Ada")

        // A fifth of the way into the second letter at double size. Measured at the unscaled size
        // the same point is most of the way through the second letter, and the caret would go
        // after the "d" instead.
        val advance = style.textStyle.size * 2f * 0.6f
        val bounds = ui.node("name").boundsInRoot
        ui.click(Offset(bounds.left + style.padding.left + advance * 1.2f, bounds.centre.y))
        ui.type("X")

        ui.assertText("name", "AXda")
    }

    @Test
    fun `a stepper's arrows stay put under a text scale when a key changes the option`() {
        val ui = open {
            var quality by remember { mutableStateOf("Medium") }
            ProvideTextScale(1.5f) {
                Stepper(
                    options = listOf("Low", "Medium", "High"),
                    selected = quality,
                    onSelect = { quality = it },
                    initialFocus = true,
                    modifier = Modifier.testTag("quality"),
                )
            }
        }
        val rightArrow = ui.node("quality").children[2].boundsInRoot.left
        val width = ui.node("quality").width

        ui.key(dev.wildware.composegl.ui.input.Key.Left)

        ui.assertText("quality", "<\nLow\n>")
        // The room kept for the widest option has to be measured at the size it is drawn at. Kept
        // at the unscaled size, "Medium" at 150% overflows it and the arrow jumps in with "Low".
        assertEquals(rightArrow, ui.node("quality").children[2].boundsInRoot.left, "the right arrow has not moved")
        assertEquals(width, ui.node("quality").width, "nor has the control's width")
    }

    @Test
    fun `a typewriter under a text scale reveals its line at the scaled size`() {
        val ui = open {
            val state = rememberTypewriter("HELLO")
            ProvideTextScale(1.5f) { Typewriter(state, Modifier.testTag("line"), textStyle = sixteen) }
        }

        ui.advanceBy(2_000)

        ui.assertText("line", "HELLO")
        assertNear(72f, ui.node("line").width, "five characters at 24")
        assertNear(30f, ui.node("line").height, "one line at 24")
    }

    @Test
    fun `a tooltip shown by hovering is drawn in a box that fits the scaled text`() {
        fun boxWidth(scale: Float): Float {
            val ui = open {
                ProvideTextScale(scale) {
                    TooltipHost(delayMillis = 100, fadeMillis = 50) {
                        Tooltip("tip one") { Box(Modifier.size(40f).testTag("item")) }
                    }
                }
            }
            ui.moveTo("item")
            ui.advanceBy(1_000)

            val canvas = (ui.backend as HeadlessBackend).canvas
            canvas.clear()
            ui.render()
            val text = assertNotNull(canvas.calls.filterIsInstance<DrawCall.Text>().firstOrNull { it.text == "tip one" })
            val box = assertNotNull(
                canvas.calls.filterIsInstance<DrawCall.Rectangle>()
                    .lastOrNull { it.rect.left <= text.at.x && it.rect.right >= text.at.x },
            )
            return box.rect.right - box.rect.left
        }

        val size = Skin.Default.resolve("tooltip").textStyle.size
        assertNear(boxWidth(1f) + 7 * size * 0.6f, boxWidth(2f), "wider by exactly the text's growth")
    }

    @Test
    fun `a prompt glyph sits on a scaled line`() {
        val line = Skin.Default.resolve("label").textStyle
        val ui = open {
            Row {
                PromptGlyph(Action.Confirm, Modifier.testTag("plain"))
                ProvideTextScale(2f) { PromptGlyph(Action.Confirm, Modifier.testTag("big")) }
            }
        }

        assertNear(line.lineHeight, ui.node("plain").height, "a line of label text")
        assertNear(line.lineHeight * 2f, ui.node("big").height, "a line of label text at twice the size")
    }
}
