package dev.wildware.composegl.ui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.scale
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
}
