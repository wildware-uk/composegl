package dev.wildware.composegl.gdx.screenshot

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.gdx.GdxCanvas
import dev.wildware.composegl.gdx.Gl
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.FlowColumn
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Flow rows and flow columns composed for real and drawn through LibGDX, checked pixel by pixel.
 *
 * The scenes in the testing module draw on a canvas by hand and cannot say anything about layout.
 * This one goes through the whole path a game does — compose, lay out, draw — so a wrap that lands
 * a line in the wrong place shows up in the pixels, not only in a number.
 */
class FlowScreenshotTest {

    @Test
    fun `a flow row wraps its tiles onto three lines`() {
        val image = render {
            FlowRow(Modifier.padding(10f), horizontalSpacing = 10f, verticalSpacing = 10f) { Tiles() }
        }

        // 10 padding, then 50 wide tiles with a 10 gap: three fit across 220, so the fourth starts
        // the second line, 50 tall plus a 10 gap down.
        assertColour(Colours[0], image, 35, 35, "the first tile")
        assertColour(Colours[3], image, 35, 95, "the fourth tile, wrapped under the first")
        assertColour(Colours[6], image, 35, 155, "the seventh, on a third line")
        assertColour(Background, image, 210, 35, "nothing left of a fourth tile on line one")

        Goldens.assertMatches("flow-row", image)
    }

    @Test
    fun `a flow column wraps its tiles into three columns`() {
        val image = render {
            Box(Modifier.padding(10f)) {
                FlowColumn(Modifier.height(170f), horizontalSpacing = 20f, verticalSpacing = 10f) { Tiles() }
            }
        }

        // Three 50 tall tiles with 10 gaps are exactly 170, so the fourth starts a column 50 wide
        // plus a 20 gap to the right. The two gaps differ so that swapping them shows.
        assertColour(Colours[0], image, 35, 35, "the first tile")
        assertColour(Colours[2], image, 35, 155, "the third, at the bottom of column one")
        assertColour(Background, image, 35, 215, "nothing under the third tile")
        assertColour(Colours[3], image, 105, 35, "the fourth tile, at the top of column two")
        assertColour(Colours[6], image, 175, 35, "the seventh, at the top of column three")
        assertColour(Background, image, 175, 95, "column three holds one tile")
    }

    @Composable
    private fun Tiles() {
        Colours.forEach { Box(Modifier.size(50f).background(Colour.rgb(it), corner = 6f)) {} }
    }

    /** Within a shade or two: the batch's colour packing rounds a channel off by one. */
    private fun assertColour(expected: Long, image: BufferedImage, x: Int, y: Int, what: String) {
        val actual = image.getRGB(x, y)
        val worst = listOf(16, 8, 0).maxOf { shift ->
            abs(((expected shr shift) and 0xFF).toInt() - ((actual shr shift) and 0xFF))
        }
        assertTrue(worst <= 3, "$what: expected ${expected.toString(16)} at $x,$y but was ${(actual and 0xFFFFFF).toString(16)}")
    }

    private fun render(content: @Composable () -> Unit): BufferedImage = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent {
                Box(Modifier.fillMaxSize().background(Colour.rgb(Background))) { content() }
            }
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            UiRenderer(host, canvas).render(viewport, nanos = 16_666_667L)

            Pixmap.createFromFrameBuffer(0, 0, SceneSize, SceneSize).let { frame ->
                // OpenGL hands back the bottom row first.
                imageOf(SceneSize, SceneSize) { x, y -> frame.getPixel(x, SceneSize - 1 - y) ushr 8 }
                    .also { frame.dispose() }
            }
        } finally {
            canvas.dispose()
            host.dispose()
        }
    }

    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private companion object {
        const val Background = 0x12161DL
        val Colours = listOf(0x4CC2FFL, 0xE6EDF5L, 0x2C3545L, 0xFF8A3DL, 0x1D4F70L, 0x9BE564L, 0xD64550L)
    }
}
