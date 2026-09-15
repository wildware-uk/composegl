package dev.wildware.composegl.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.backend.MonospaceFontProvider
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A player changing the whole skin from the options screen, while the options screen is open.
 *
 * The accessibility setting a player expects — high contrast, bigger text, a palette they can tell
 * apart — is a skin, and choosing it is a [Stepper] in the same screen it restyles. So everything
 * here is driven the way a player does it, and judged by what is drawn in the frame that follows:
 * every surface in the new skin's colours, and nothing the player had done undone by it — not the
 * name they typed, not the box they ticked, not where focus was.
 */
class SkinSwitchTest {

    private val shipped = listOf(Skin.Default, Skin.HighContrast)

    /** The options screen, with the skin chosen from inside it. */
    @Composable
    private fun Options(skins: List<Skin> = shipped, initial: Skin = skins.first()) {
        var skin by remember { mutableStateOf(initial) }
        ProvideSkin(skin) { Form(skins, skin) { skin = it } }
    }

    @Composable
    private fun Form(skins: List<Skin>, skin: Skin, onSkin: (Skin) -> Unit) {
        var name by remember { mutableStateOf("") }
        var ticked by remember { mutableStateOf(false) }
        Column {
            Text("Options", modifier = Modifier.testTag("title"))
            TextField(name, { name = it }, modifier = Modifier.testTag("name"))
            Checkbox(ticked, { ticked = it }, label = if (ticked) "Subtitles on" else "Subtitles off", modifier = Modifier.testTag("tick"))
            Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
            Stepper(
                options = skins,
                selected = skin,
                onSelect = onSkin,
                label = { it.name },
                modifier = Modifier.testTag("skin"),
                initialFocus = true,
            )
        }
    }

    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    /** The fill drawn exactly behind [tag]. */
    private fun UiTest.backgroundOf(tag: String): Colour {
        val bounds = node(tag).boundsInRoot
        return frame().only<DrawCall.Rectangle>().single { it.rect == bounds }.colour
    }

    /** The edge drawn exactly round [tag]. */
    private fun UiTest.borderOf(tag: String): DrawCall.Border {
        val bounds = node(tag).boundsInRoot
        return frame().only<DrawCall.Border>().single { it.rect == bounds }
    }

    private fun fill(skin: Skin, style: String, vararg states: WidgetState) =
        (skin.resolve(style, states.toSet()).background as SkinDrawable.Fill).colour

    @Test
    fun `the right arrow on the skin stepper restyles the whole screen`() = uiTest { Options() }.use { ui ->
        assertEquals(fill(Skin.Default, "button"), ui.backgroundOf("play"))
        assertTrue("Standard" in ui.texts("skin"), ui.texts("skin").toString())

        ui.key(Key.Right)

        assertTrue("High contrast" in ui.texts("skin"), "the stepper says which skin: ${ui.texts("skin")}")
        assertEquals(fill(Skin.HighContrast, "button"), ui.backgroundOf("play"), "the button is the new skin's")
        assertEquals(Colour.White, ui.borderOf("play").colour, "with the new skin's white edge")
        assertEquals(2f, ui.borderOf("play").width, "twice as thick")
        assertEquals(
            Colour.rgb(0xFFD600),
            ui.borderOf("skin").colour,
            "and the stepper, which has focus, is outlined in the new skin's focus colour",
        )
        assertEquals(fill(Skin.HighContrast, "field"), ui.backgroundOf("name"), "the field too")
    }

    @Test
    fun `what the player typed and ticked and where focus was all survive the switch`() =
        uiTest { Options() }.use { ui ->
            ui.click("name")
            ui.type("Ada")
            ui.click("tick")
            ui.click("skin")   // clicking the value steps to the next option

            assertTrue("High contrast" in ui.texts("skin"), ui.texts("skin").toString())
            ui.assertText("name", "Ada")
            assertTrue("Subtitles on" in ui.texts("tick"), ui.texts("tick").toString())
            ui.assertFocused("skin")

            ui.pad(GamepadButton.DpadLeft)

            assertTrue("Standard" in ui.texts("skin"), "the pad takes it back: ${ui.texts("skin")}")
            assertEquals(fill(Skin.Default, "button"), ui.backgroundOf("play"))
            ui.assertText("name", "Ada")
            assertTrue("Subtitles on" in ui.texts("tick"), ui.texts("tick").toString())
            ui.assertFocused("skin")

            ui.pad(GamepadButton.DpadUp)
            ui.assertFocused("play")   // and focus still moves from where it was, in the new skin
        }

    @Test
    fun `the first frame after the switch has nothing of the old skin in it`() {
        var skin by mutableStateOf(Skin.Default)
        uiTest { ProvideSkin(skin) { Form(shipped, skin) { skin = it } } }.use { ui ->
            val before = ui.frame().colours()
            // Colours only the standard skin uses, so finding one means something was left behind.
            val standardOnly = before - Skin.HighContrast.colours()
            assertTrue(standardOnly.isNotEmpty(), "the two skins have to differ for this to mean anything")

            skin = Skin.HighContrast
            val after = ui.frame().colours()

            assertEquals(emptySet(), after intersect standardOnly, "the standard skin, still on screen")
        }
    }

    @Test
    fun `a button under the pointer wears the new skin's hover straight away`() {
        var skin by mutableStateOf(Skin.Default)
        uiTest { ProvideSkin(skin) { Form(shipped, skin) { skin = it } } }.use { ui ->
            ui.moveTo("play")
            assertEquals(fill(Skin.Default, "button", WidgetState.Hovered), ui.backgroundOf("play"))

            skin = Skin.HighContrast

            assertEquals(
                fill(Skin.HighContrast, "button", WidgetState.Hovered),
                ui.backgroundOf("play"),
                "still hovered, in the new skin's hover",
            )
        }
    }

    @Test
    fun `nothing moves when the player switches between the shipped skins`() = uiTest { Options() }.use { ui ->
        val tags = listOf("title", "name", "tick", "play", "skin")
        val standard = tags.map { ui.node(it).boundsInRoot }

        ui.key(Key.Right)

        assertEquals(standard, tags.map { ui.node(it).boundsInRoot }, "so the cursor is still on the stepper")
    }

    @Test
    fun `a skin with bigger text lays the screen out again`() {
        val large = Skin.Default.overriddenWith(
            SkinFormat.read("""{ "name": "Large text", "defaults": { "textColour": "#FFFFFF", "text": { "font": "default", "size": 24 } } }"""),
        )
        uiTest { Options(listOf(Skin.Default, large)) }.use { ui ->
            val standard = ui.node("title").boundsInRoot

            ui.key(Key.Right)

            assertTrue("Large text" in ui.texts("skin"), ui.texts("skin").toString())
            val bigger = ui.node("title").boundsInRoot
            assertEquals(standard.width * 1.5f, bigger.width, 0.01f, "measured again, at the new size")
            assertEquals(standard.height * 1.5f, bigger.height, 0.01f)
            assertTrue(ui.node("play").boundsInRoot.top > standard.bottom * 1.5f, "and everything below moved down")
        }
    }

    @Test
    fun `a skin that brings its own fonts is measured with them`() {
        val wide = Skin.Default.overriddenWith(Skin(fonts = MonospaceFontProvider(advanceRatio = 1f), name = "Wide"))
        uiTest { Options(listOf(Skin.Default, wide)) }.use { ui ->
            val standard = ui.node("title").boundsInRoot.width

            ui.key(Key.Right)

            assertEquals(standard / 0.6f, ui.node("title").boundsInRoot.width, 0.01f, "the wide font's widths")
        }
    }

    @Test
    fun `high contrast laid over a game's own skin keeps the game's own styles`() {
        val game = Skin.Default.overriddenWith(
            SkinFormat.read("""{ "name": "Game", "styles": { "chip": { "background": { "fill": "#336699" } } } }"""),
        )
        val skins = listOf(game, game.overriddenWith(Skin.HighContrast))
        var skin by mutableStateOf(skins.first())
        uiTest {
            ProvideSkin(skin) {
                Column {
                    Button("FIRE", onClick = {}, style = "chip", modifier = Modifier.testTag("chip"))
                    Form(skins, skin) { skin = it }
                }
            }
        }.use { ui ->
            ui.key(Key.Right)

            assertTrue("High contrast" in ui.texts("skin"), ui.texts("skin").toString())
            assertEquals(fill(Skin.HighContrast, "button"), ui.backgroundOf("play"), "the toolkit's styles turn high contrast")
            assertEquals(Colour.rgb(0x336699), ui.backgroundOf("chip"), "and the game's own is still there")
        }
    }

    @Test
    fun `an override inside the screen is laid over whichever skin is chosen`() {
        val danger = Skin(styles = mapOf("button" to Style(base = StateStyle(textColour = Colour.rgb(0xFF5C5C)))))
        var skin by mutableStateOf(Skin.Default)
        uiTest {
            ProvideSkin(skin) {
                SkinOverride(danger) { Button("ABANDON", onClick = {}, modifier = Modifier.testTag("abandon")) }
            }
        }.use { ui ->
            skin = Skin.HighContrast
            val frame = ui.frame()

            assertEquals(fill(Skin.HighContrast, "button"), ui.backgroundOf("abandon"), "the chosen skin underneath")
            assertEquals(
                Colour.rgb(0xFF5C5C),
                frame.only<DrawCall.Text>().single { it.text == "ABANDON" }.colour,
                "and the override on top of it",
            )
        }
    }

    @Test
    fun `a reloading skin picked from the list keeps up with its file`() {
        val file = object : SkinSource {
            var text = """{ "name": "Mine", "styles": { "button": { "background": { "fill": "#123456" } } } }"""
            override val revision: Any get() = text
            override fun read() = text
        }
        val mine = ReloadingSkin(file)
        var choice by mutableStateOf("Standard")
        uiTest {
            ProvideSkin(if (choice == "Mine") mine.skin else Skin.Default) {
                Column {
                    Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
                    Stepper(listOf("Standard", "Mine"), choice, { choice = it }, Modifier.testTag("skin"), initialFocus = true)
                }
            }
        }.use { ui ->
            ui.key(Key.Right)
            assertEquals(Colour.rgb(0x123456), ui.backgroundOf("play"))

            file.text = file.text.replace("#123456", "#654321")
            mine.reloadIfChanged()

            assertEquals(Colour.rgb(0x654321), ui.backgroundOf("play"), "an artist's save reaches the chosen skin")
            ui.assertFocused("skin")
            assertTrue("Mine" in ui.texts("skin"))
        }
    }

    @Test
    fun `the shipped skins can be told apart by name`() {
        assertEquals("Standard", Skin.Default.name)
        assertEquals("High contrast", Skin.HighContrast.name)
        assertNotEquals(Skin.Default.name, Skin.HighContrast.name)
    }

    private fun RecordingCanvas.colours(): Set<Colour> = calls.mapNotNull {
        when (it) {
            is DrawCall.Rectangle -> it.colour
            is DrawCall.Border -> it.colour
            is DrawCall.Text -> it.colour
            else -> null
        }
    }.toSet()

    /** Every colour a skin could draw with, in any state. */
    private fun Skin.colours(): Set<Colour> = styles.keys.flatMap { name ->
        stateSets.flatMap { states ->
            val resolved = resolve(name, states)
            listOfNotNull(
                resolved.textColour,
                (resolved.background as? SkinDrawable.Fill)?.colour,
                (resolved.background as? SkinDrawable.Fill)?.border,
            )
        }
    }.toSet()

    private val stateSets = listOf(emptySet<WidgetState>()) + WidgetState.entries.map { setOf(it) }
}
