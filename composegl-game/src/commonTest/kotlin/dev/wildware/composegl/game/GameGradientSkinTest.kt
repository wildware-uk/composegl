package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinFormat
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Game widgets whose skin file says "gradient", played with.
 *
 * A bar's fill is a box and takes the gradient whole. A crosshair, a cooldown wedge and a minimap
 * marker draw shapes rather than boxes, so they take the colour the gradient starts from — the one
 * a canvas with no gradients would paint — through the public `SkinDrawable.flatColour`, rather than
 * drawing nothing at all. Each is put in its state by a real click.
 */
class GameGradientSkinTest {

    private val skin = Skin.Default.overriddenWith(
        SkinFormat.read(
            """
            {
              "styles": {
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

    private fun skinned(content: @Composable () -> Unit): UiTest = uiTest { ProvideSkin(skin, content) }

    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
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
