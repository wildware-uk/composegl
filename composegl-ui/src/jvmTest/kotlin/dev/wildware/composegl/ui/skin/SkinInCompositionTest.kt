package dev.wildware.composegl.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.drawInFront
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A widget that looks like the game without being told what the game looks like.
 *
 * [Chip] below is the whole argument. It names a style, draws what it is handed, and contains no
 * colour, no corner radius and no padding of its own — so the same widget under two skins is two
 * different pictures, and a dialogue can change its buttons without changing anybody else's.
 */
class SkinInCompositionTest {

    private val host = UiHost()
    private val canvas = RecordingCanvas()

    @AfterEach
    fun tearDown() = host.dispose()

    /** A widget with no colour in it. Every number it draws with came from the skin. */
    @Composable
    private fun Chip() {
        val style = rememberStyle("chip")
        Box(Modifier.size(40f).styled(style).drawInFront { rect(it, style.textColour) })
    }

    private fun skin(background: Colour, text: Colour) = Skin(
        styles = mapOf(
            "chip" to Style(
                base = StateStyle(
                    background = SkinDrawable.Fill(background, corner = 3f),
                    textColour = text,
                    padding = Padding.all(6f),
                ),
            ),
        ),
    )

    private val plain = skin(Colour.rgb(0x202020), Colour.rgb(0xCCCCCC))

    private fun draw(): List<DrawCall.Rectangle> {
        canvas.clear()
        host.frame(frame)
        frame += 16_666_667L
        MeasurePass().run(host.root, Constraints.atMost(200f, 200f))
        DrawPass(canvas).draw(host.root)
        canvas.assertBalanced()
        return canvas.calls.filterIsInstance<DrawCall.Rectangle>()
    }

    private var frame = 0L

    @Test
    fun `a widget draws the skin it is under`() {
        host.setContent { ProvideSkin(plain) { Chip() } }

        val (background, text) = draw()

        assertEquals(Colour.rgb(0x202020), background.colour)
        assertEquals(3f, background.corner, "the corner is the skin's too")
        assertEquals(Colour.rgb(0xCCCCCC), text.colour)
    }

    @Test
    fun `the same widget under another skin is another picture`() {
        var current by mutableStateOf(plain)
        host.setContent { ProvideSkin(current) { Chip() } }
        draw()

        current = skin(Colour.rgb(0x7A1F1F), Colour.White)

        val (background, text) = draw()
        assertEquals(Colour.rgb(0x7A1F1F), background.colour, "a saved skin file reaches the screen")
        assertEquals(Colour.White, text.colour)
    }

    @Test
    fun `an override applies to its subtree and nowhere else`() {
        val danger = Skin(
            styles = mapOf("chip" to Style(base = StateStyle(textColour = Colour.rgb(0xFF5C5C)))),
        )
        host.setContent {
            ProvideSkin(plain) {
                Box {
                    Chip()
                    SkinOverride(danger) { Chip() }
                }
            }
        }

        val rectangles = draw()

        assertEquals(Colour.rgb(0xCCCCCC), rectangles[1].colour, "the chip outside the override")
        assertEquals(Colour.rgb(0xFF5C5C), rectangles[3].colour, "and the one inside it")
        assertEquals(
            Colour.rgb(0x202020),
            rectangles[2].colour,
            "an override changes what it names and leaves the rest of the style alone",
        )
    }

    @Test
    fun `with no skin at all a widget still draws`() {
        host.setContent { Chip() }

        val calls = draw()

        assertEquals(1, calls.size, "nothing behind it, because no style called \"chip\" says there is")
        assertEquals(
            Skin.Default.resolve("chip").textColour,
            calls.single().colour,
            "and the shipped skin's text colour, so a game that registered nothing is still readable",
        )
    }
}
