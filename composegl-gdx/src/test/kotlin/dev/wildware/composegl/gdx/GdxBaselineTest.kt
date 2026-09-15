package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * A baseline row, drawn by the real renderer with a real font, and read back as pixels.
 *
 * The layout tests prove the numbers agree with the monospace provider. This proves the thing a
 * player sees: with a real face, whose ascent is not a round share of its size, the bottoms of the
 * letters in "120" and "HP" — neither of which has a descender — land on the same row of pixels.
 */
class GdxBaselineTest {

    private val white = Colour.rgb(0xFFFFFF)
    private val big = TextStyle(family = "body", size = 40f)
    private val small = TextStyle(family = "body", size = 16f)

    /** The two labels in a row lined up [how], drawn and read back, with where each one was laid. */
    private fun draw(how: VerticalAlignment): Triple<BufferedImage, UiNode, UiNode> = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16, 40))
        val canvas = GdxCanvas(atlas = fonts.atlas)
        val host = UiHost()
        try {
            host.setContent {
                ProvideFonts(fonts) { row(how) }
            }
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.atMost(Side.toFloat(), Side.toFloat()))

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(host.root)
            canvas.end()

            val image = Pixmap.createFromFrameBuffer(0, 0, Side, Side).let { frame ->
                // OpenGL hands back the bottom row first.
                imageOf(Side, Side) { x, y -> frame.getPixel(x, Side - 1 - y) ushr 8 }.also { frame.dispose() }
            }
            Triple(image, host.root.find("value"), host.root.find("unit"))
        } finally {
            host.dispose()
            canvas.dispose()
            fonts.dispose()
        }
    }

    @Composable
    private fun row(how: VerticalAlignment) {
        Row(verticalAlignment = how) {
            Text("120", Modifier.testTag("value"), textStyle = big, colour = white)
            Text("HP", Modifier.testTag("unit"), textStyle = small, colour = white)
        }
    }

    /** The lowest row with any lit pixel between [node]'s left and right edges. */
    private fun lowestInk(image: BufferedImage, node: UiNode): Int {
        val bounds = node.boundsInRoot
        for (y in image.height - 1 downTo 0) {
            for (x in bounds.left.toInt() until bounds.right.toInt().coerceAtMost(image.width)) {
                if ((image.getRGB(x, y) and 0xFF) > 128) return y
            }
        }
        error("${node.name} drew nothing at $bounds")
    }

    @Test
    fun `a baseline row puts the bottoms of both labels on one row of pixels`() {
        val (image, value, unit) = draw(VerticalAlignment.Baseline)

        val valueBottom = lowestInk(image, value)
        val unitBottom = lowestInk(image, unit)
        assertTrue(
            kotlin.math.abs(valueBottom - unitBottom) <= 1,
            "\"120\" stands on row $valueBottom and \"HP\" on row $unitBottom",
        )
        Goldens.assertMatches("baseline-row", image)
    }

    @Test
    fun `a top row does not`() {
        val (image, value, unit) = draw(VerticalAlignment.Top)

        val gap = lowestInk(image, value) - lowestInk(image, unit)
        assertTrue(gap > 10, "without a baseline the small label stands $gap rows higher than the big one")
        assertEquals(0f, unit.boundsInRoot.top)
    }

    private companion object {
        const val Side = 160

        val viewport = Viewport(
            design = Size(Side.toFloat(), Side.toFloat()),
            physical = Size(Side.toFloat(), Side.toFloat()),
            policy = ScalePolicy.Fit,
        )
    }
}
