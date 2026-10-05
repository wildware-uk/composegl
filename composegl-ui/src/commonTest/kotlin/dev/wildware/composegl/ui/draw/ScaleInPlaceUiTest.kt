package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A heads-up display shrunk to fit with `Modifier.scale`, on a composed screen driven by clicks:
 * drawn every frame through a transform, with no offscreen picture, and clicked where it is drawn.
 */
class ScaleInPlaceUiTest {

    /** A display at half size with a score and a button on it. */
    private fun screen(): UiTest = uiTest(Size(400f, 300f)) {
        var score by remember { mutableStateOf(0) }
        Column {
            Box(Modifier.testTag("hud").scale(0.5f, Alignment.TopStart)) {
                Column {
                    Text("Score $score")
                    Button("Add", onClick = { score++ }, modifier = Modifier.testTag("add"))
                }
            }
        }
    }

    /** One frame from scratch into the headless canvas. */
    private fun UiTest.drawn(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear()
        render()
        canvas.assertBalanced()
        return canvas
    }

    @Test
    fun `a still scaled display draws on its second frame without a picture`() = screen().use { ui ->
        repeat(2) {
            val drawn = ui.drawn()
            assertEquals(emptyList(), drawn.only<DrawCall.Layer>())
            val score = drawn.only<DrawCall.Text>().single { it.text == "Score 0" }
            assertEquals(0.5f, drawn.scaleOf(score), "the words drawn at half size")
        }
    }

    @Test
    fun `a click on the shrunk display lands and its change is drawn next frame`() = screen().use { ui ->
        ui.drawn()
        val button = ui.node("add").boundsInRoot
        assertEquals(0.5f, ui.node("add").scaleInRoot, "the button is where it is drawn, at half size")

        ui.click("add")

        val drawn = ui.drawn()
        assertTrue("Score 1" in drawn.texts(), "the new score, drawn straight away")
        assertEquals(emptyList(), drawn.only<DrawCall.Layer>())
        assertEquals(button, ui.node("add").boundsInRoot)
    }

    private val gold = Colour.rgb(0xFFCC00)
    private val soft = ShaderEffect(ShaderSource("soft", "void main() { }"), bleed = 4f)

    /** The same display with a glow and a softened mark on it, as a card in a draft row has. */
    private fun lit(): UiTest = uiTest(Size(400f, 300f)) {
        var score by remember { mutableStateOf(0) }
        Column {
            Box(Modifier.testTag("hud").scale(0.5f, Alignment.TopStart)) {
                Column {
                    Text("Score $score")
                    Box(Modifier.testTag("glow").size(40f, 10f).blend(BlendMode.Additive).background(gold))
                    Box(Modifier.testTag("mark").size(20f).effect(soft).background(gold))
                    Button("Add", onClick = { score++ }, modifier = Modifier.testTag("add"))
                }
            }
        }
    }

    @Test
    fun `a shrunk display with a glow and an effect on it draws with no picture of its own`() = lit().use { ui ->
        repeat(2) { at ->
            val drawn = ui.drawn()
            val picture = drawn.only<DrawCall.Layer>().single()
            assertEquals(soft, picture.effect, "frame ${at + 1}: the effect's own picture and nothing else")
            assertEquals(0.5f, drawn.scaleOf(picture), "put down at half size")
            val glow = drawn.calls(BlendMode.Additive).filterIsInstance<DrawCall.Rectangle>().single()
            assertEquals(0.5f, drawn.scaleOf(glow), "the glow, added at half size")
        }

        ui.click("add")

        val drawn = ui.drawn()
        assertTrue("Score 1" in drawn.texts(), "the click lands on the shrunk button and its change is drawn next frame")
        assertEquals(1, drawn.only<DrawCall.Layer>().size, "still only the effect's picture")
    }
}
