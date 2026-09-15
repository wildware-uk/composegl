package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.weight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `aspectRatio` all the way to the pixels: a screen composed, laid out and drawn through the real
 * renderer, and the shapes read back out of the framebuffer.
 */
class GdxAspectRatioTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** Composes [content] in a window the size of the GL context, draws one frame, reads it back. */
    private fun draw(content: @Composable () -> Unit): Pixmap = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent(content)
            host.frame(0L)
            MeasurePass().run(host.root, Constraints.atMost(Gl.size.toFloat(), Gl.size.toFloat()))

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(host.root)
            canvas.end()
            Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        } finally {
            canvas.dispose()
            host.dispose()
        }
    }

    /**
     * Which of the three things is at a point, top-left origin. OpenGL hands back the bottom row
     * first, and blends a flat red to 0xFE rather than 0xFF, so this names the colour rather than
     * comparing its last bit: the question is where the shapes are, not how the driver rounds.
     */
    private fun Pixmap.colour(x: Int, y: Int): String {
        val rgba = getPixel(x, Gl.size - 1 - y)
        val r = rgba ushr 24 and 0xFF
        val b = rgba ushr 8 and 0xFF
        return when {
            r > 200 && b < 50 -> "red"
            b > 200 && r < 50 -> "blue"
            r < 20 && b < 20 -> "black"
            else -> "0x%08X".format(rgba)
        }
    }

    @Test
    fun `a full width frame at 2 to 1 is painted half as tall as it is wide`() {
        val frame = draw {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f).background(red))
                Box(Modifier.fillMaxWidth().height(40f).background(blue))
            }
        }
        try {
            // 400 wide at 2:1 is 200 tall; the strip under it starts there.
            assertEquals("red", frame.colour(200, 5), "the top of the frame")
            assertEquals("red", frame.colour(200, 195), "the bottom of the frame")
            assertEquals("blue", frame.colour(200, 205), "the strip below it, pushed down by its height")
            assertEquals("black", frame.colour(200, 245), "nothing past the strip")
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `four weighted thumbnails are painted as squares`() {
        val frame = draw {
            Row(Modifier.fillMaxWidth()) {
                repeat(4) { index -> Box(Modifier.weight(1f).aspectRatio(1f).background(if (index % 2 == 0) red else blue)) }
            }
        }
        try {
            assertEquals("red", frame.colour(50, 95))
            assertEquals("blue", frame.colour(150, 95))
            assertEquals("red", frame.colour(250, 95))
            assertEquals("blue", frame.colour(350, 95))
            assertEquals("black", frame.colour(50, 105), "a hundred wide is a hundred tall and no more")
        } finally {
            frame.dispose()
        }
    }
}
