package dev.wildware.composegl.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.game.MinimapFrame
import dev.wildware.composegl.ui.game.MinimapMarker
import dev.wildware.composegl.ui.game.RadialCooldown
import dev.wildware.composegl.ui.game.Reticle
import dev.wildware.composegl.ui.game.rememberCooldown
import dev.wildware.composegl.ui.game.rememberReticleState
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Widgets whose skin file says "gradient", played with.
 *
 * The widgets have no idea gradients exist. Everything here comes out of the JSON: the file names a
 * gradient for a state, a real pointer, key or pad button puts the widget in that state, and the
 * frame that follows draws exactly that gradient behind it.
 */
class GradientSkinTest {

    private val skin = Skin.Default.overriddenWith(
        SkinFormat.read(
            """
            {
              "styles": {
                "button": {
                  "background": { "gradient": { "vertical": ["#3A6EA5", "#1B2A41"] }, "corner": 6, "border": "#5B8DEF", "padding": [14, 8] },
                  "hovered": { "background": { "gradient": { "vertical": ["#5B8DEF", "#3A6EA5"] }, "corner": 6 } },
                  "pressed": { "background": { "gradient": { "radial": ["#FFFFFF", "#3A6EA5"] }, "corner": 6 } },
                  "focused": { "background": { "gradient": { "linear": ["#FFD166", "#3A6EA5"], "angle": 30 }, "corner": 6 } },
                  "disabled": { "tint": "#80FFFFFF" }
                },
                "bar.fill": { "background": { "gradient": { "horizontal": ["#4CD964", "#FF3B30"] } } },
                "reticle": { "background": { "gradient": { "vertical": ["#E8ECF2", "#000000"] } } },
                "reticle.hostile": { "background": { "gradient": { "radial": ["#FF3B30", "#000000"] } } },
                "cooldown.sweep": { "background": { "gradient": { "radial": ["#B03A6EA5", "#00000000"] } } },
                "minimap.objective": { "background": { "gradient": { "radial": ["#FFD166", "#000000"] } } }
              }
            }
            """.trimIndent(),
        ),
    )

    private val resting = Brush.vertical(Colour.rgb(0x3A6EA5), Colour.rgb(0x1B2A41))

    private fun skinned(content: @Composable () -> Unit): UiTest = uiTest { ProvideSkin(skin, content) }

    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    /** The gradient drawn exactly across [tag]. */
    private fun UiTest.backgroundOf(tag: String): DrawCall.GradientRectangle {
        val bounds = node(tag).boundsInRoot
        return frame().only<DrawCall.GradientRectangle>().single { it.rect == bounds }
    }

    @Test
    fun `a button at rest wears the gradient its skin names with its border on top`() = skinned {
        // Focus sits on QUIT, so PLAY is neither focused, hovered nor pressed.
        Column {
            Button("QUIT", onClick = {}, initialFocus = true, modifier = Modifier.testTag("quit"))
            Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
        }
    }.use { ui ->
        ui.assertFocused("quit")
        val canvas = ui.frame()
        val play = ui.node("play").boundsInRoot

        val drawn = canvas.only<DrawCall.GradientRectangle>().single { it.rect == play }
        assertEquals(resting, drawn.brush, "across the whole button")
        assertEquals(6f, drawn.corner)
        val border = canvas.only<DrawCall.Border>().single { it.rect == play }
        assertEquals(Colour.rgb(0x5B8DEF), border.colour)
        assertTrue(canvas.calls.indexOf(drawn) < canvas.calls.indexOf(border), "the border is drawn over it")
        assertTrue(canvas.calls.indexOf(border) < canvas.calls.indexOfLast { it is DrawCall.Text }, "and the label over both")
        assertEquals(listOf("QUIT", "PLAY"), canvas.texts())
    }

    @Test
    fun `the pointer walks a button through its hovered and pressed gradients to a click`() {
        var clicks = 0
        skinned { Button("PLAY", onClick = { clicks++ }, modifier = Modifier.testTag("play")) }.use { ui ->
            ui.moveTo("play")
            assertEquals(Brush.vertical(Colour.rgb(0x5B8DEF), Colour.rgb(0x3A6EA5)), ui.backgroundOf("play").brush, "hovered")

            ui.press("play")
            assertEquals(Brush.radial(Colour.White, Colour.rgb(0x3A6EA5)), ui.backgroundOf("play").brush, "pressed")

            ui.release()
            assertEquals(1, clicks)
            assertTrue(ui.backgroundOf("play").brush != Brush.radial(Colour.White, Colour.rgb(0x3A6EA5)), "no longer pressed")
        }
    }

    @Test
    fun `focus moved by the keyboard or the pad shows the focused gradient`() = skinned {
        Column {
            Button("QUIT", onClick = {}, initialFocus = true, modifier = Modifier.testTag("quit"))
            Button("PLAY", onClick = {}, modifier = Modifier.testTag("play"))
        }
    }.use { ui ->
        val focused = Brush.linear(Colour.rgb(0xFFD166), Colour.rgb(0x3A6EA5), degrees = 30f)

        ui.key(Key.Down)
        ui.assertFocused("play")
        assertEquals(focused, ui.backgroundOf("play").brush, "the button focus moved onto")
        assertEquals(resting, ui.backgroundOf("quit").brush, "the one it left")

        ui.pad(GamepadButton.DpadUp)
        ui.assertFocused("quit")
        assertEquals(focused, ui.backgroundOf("quit").brush, "the pad moves it back")
        assertEquals(resting, ui.backgroundOf("play").brush)
    }

    @Test
    fun `a skinned gradient keeps a radius per corner as the pointer changes its state`() {
        val tabs = Skin.Default.overriddenWith(
            SkinFormat.read(
                """
                {
                  "styles": {
                    "button": {
                      "background": { "gradient": { "vertical": ["#3A6EA5", "#1B2A41"] }, "corner": { "topLeft": 8, "topRight": 8 }, "border": "#5B8DEF" },
                      "hovered": { "background": { "gradient": { "vertical": ["#5B8DEF", "#3A6EA5"] }, "corner": [0, 0, 8, 8] } }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        uiTest {
            // Focus sits on MAP, so ITEMS is only ever at rest or hovered.
            ProvideSkin(tabs) {
                Column {
                    Button("MAP", onClick = {}, initialFocus = true)
                    Button("ITEMS", onClick = {}, modifier = Modifier.testTag("tab"))
                }
            }
        }.use { ui ->
            fun drawn(): Pair<DrawCall.CorneredGradientRectangle, RecordingCanvas> {
                val canvas = ui.frame()
                val bounds = ui.node("tab").boundsInRoot
                return canvas.only<DrawCall.CorneredGradientRectangle>().single { it.rect == bounds } to canvas
            }

            val (rest, canvas) = drawn()
            assertEquals(Corners.top(8f), rest.corners, "the file's top corners, not one radius")
            assertEquals(resting, rest.brush)
            assertEquals(
                Corners.top(8f),
                canvas.only<DrawCall.CorneredBorder>().single { it.rect == rest.rect }.corners,
                "and the border follows the same corners",
            )

            ui.moveTo("tab")
            val (hovered, _) = drawn()
            assertEquals(Corners(topLeft = 0f, topRight = 0f, bottomRight = 8f, bottomLeft = 8f), hovered.corners, "hovered")
            assertEquals(Brush.vertical(Colour.rgb(0x5B8DEF), Colour.rgb(0x3A6EA5)), hovered.brush)
        }
    }

    @Test
    fun `a disabled button tints both ends of its gradient`() = skinned {
        Button("PLAY", onClick = {}, enabled = false, modifier = Modifier.testTag("play"))
    }.use { ui ->
        val brush = ui.backgroundOf("play").brush
        val tint = Colour.argb(0x80FFFFFF)
        assertEquals(resting.modulate(tint), brush)
        assertEquals(0x80, brush.first.alpha, "the start is half faded")
        assertEquals(0x80, brush.last.alpha, "and so is the end")
    }

    @Test
    fun `a health bar whose skin fill is a gradient keeps it as a click drains the bar`() = skinned {
        var health by remember { mutableStateOf(1f) }
        Column {
            Button("HIT", onClick = { health -= 0.5f }, modifier = Modifier.testTag("hit"))
            Bar(health, trail = false, length = 160f)
        }
    }.use { ui ->
        val gradient = Brush.horizontal(Colour.rgb(0x4CD964), Colour.rgb(0xFF3B30))
        fun fill() = ui.frame().only<DrawCall.GradientRectangle>().single { it.brush == gradient }

        assertEquals(160f, fill().rect.width, "full")

        ui.click("hit")
        assertEquals(80f, fill().rect.width, "half gone, and still the gradient the skin named")
    }

    @Test
    fun `a crosshair skinned with a gradient draws its arms in the first colour`() = skinned {
        val state = rememberReticleState()
        Column {
            Button("AIM", onClick = { state.hostile = !state.hostile }, modifier = Modifier.testTag("aim"))
            Box(Modifier.size(200f, 200f)) { Reticle(state) }
        }
    }.use { ui ->
        // Lines are not boxes, so a crosshair takes the colour a canvas with no gradients would
        // paint rather than drawing nothing at all.
        assertEquals(4, ui.frame().only<DrawCall.Rectangle>().count { it.colour == Colour.rgb(0xE8ECF2) }, "friendly")

        ui.click("aim")
        assertEquals(4, ui.frame().only<DrawCall.Rectangle>().count { it.colour == Colour.rgb(0xFF3B30) }, "hostile")
    }

    @Test
    fun `a minimap marker styled with a gradient is drawn in its first colour`() = skinned {
        var tracking by remember { mutableStateOf(false) }
        val objective = remember { MinimapMarker(x = 20f, y = 0f, style = "minimap.objective") }
        Column {
            Button("TRACK", onClick = { tracking = true }, modifier = Modifier.testTag("track"))
            MinimapFrame(
                Modifier.size(200f, 200f),
                markers = if (tracking) listOf(objective) else emptyList(),
                live = false,
                compass = "",
            )
        }
    }.use { ui ->
        val gold = Colour.rgb(0xFFD166)
        assertTrue(ui.frame().only<DrawCall.Rectangle>().none { it.colour == gold }, "nothing tracked yet")

        ui.click("track")
        assertEquals(1, ui.frame().only<DrawCall.Rectangle>().count { it.colour == gold }, "the objective, in gold")
    }

    @Test
    fun `a cooldown wedge skinned with a gradient sweeps in the first colour after a click`() = skinned {
        val cooldown = rememberCooldown(1_000, Clock.World)
        Column {
            Button("CAST", onClick = { cooldown.trigger() }, modifier = Modifier.testTag("cast"))
            RadialCooldown(cooldown, Modifier.size(100f), seconds = false, flash = false)
        }
    }.use { ui ->
        val blue = Colour.argb(0xB03A6EA5)
        assertTrue(ui.frame().only<DrawCall.Fan>().isEmpty(), "a ready ability has no wedge")

        // The game is paused, so the wedge a click starts stays up for the frame that is read
        // rather than playing out while the click settles.
        ui.host.clocks.register(Clock.World)
        ui.host.clocks.stop(Clock.World)
        ui.click("cast")
        // A wedge is a fan, not a box, so like the crosshair it takes the colour the gradient
        // starts from rather than drawing nothing at all.
        assertEquals(listOf(blue), ui.frame().only<DrawCall.Fan>().map { it.colour }, "cooling down")
    }
}
