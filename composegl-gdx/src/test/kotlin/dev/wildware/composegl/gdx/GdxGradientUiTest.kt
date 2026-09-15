package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A composed screen with gradients on it, clicked and hovered, and read back as pixels.
 *
 * The canvas tests prove the shader paints a gradient; the recording tests prove a widget asks for
 * one. This is the join: a real tree, real pointer events through the router, a real frame through
 * [UiRenderer] onto [GdxCanvas] — and the colours that come out where the tree put the node.
 */
class GdxGradientUiTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val green = Colour.rgb(0x00FF00)
    private val red = Colour.rgb(0xFF0000)

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun near(expected: Colour, actual: Color): Boolean =
        abs(expected.red / 255f - actual.r) < 0.06f &&
            abs(expected.green / 255f - actual.g) < 0.06f &&
            abs(expected.blue / 255f - actual.b) < 0.06f

    /** Draws one frame of [host] and hands back the pixels, which the caller disposes. */
    private fun frame(renderer: UiRenderer, nanos: Long): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        renderer.render(viewport, nanos)
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    @Test
    fun `clicking damage shrinks a gradient health bar that stays red at its tip`() {
        assumeTrue(Gl.available, "needs a display")

        val found = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            try {
                val focus = FocusManager(host.root)
                val renderer = UiRenderer(host, canvas).also { it.focus = focus }
                val pointer = PointerRouter(host.root, focus)
                host.setContent {
                    var health by remember { mutableStateOf(1f) }
                    Column {
                        Box(Modifier.testTag("bar").size(300f * health, 40f).background(Brush.horizontal(green, red)))
                        Box(Modifier.testTag("hit").size(100f, 100f).clickable { health -= 0.5f }.background(Colour.White))
                    }
                }

                val before = frame(renderer, 0L)
                val fullTip = before.at(296, 20)
                val fullRoot = before.at(3, 20)
                val fullBeyond = before.at(200, 20)
                before.dispose()

                val hit = host.root.find("hit").boundsInRoot.centre
                pointer.onPointer(PointerEvent.Press(PointerId.Mouse, hit))
                pointer.onPointer(PointerEvent.Release(PointerId.Mouse, hit))

                val after = frame(renderer, 16_666_667L)
                val bar = host.root.find("bar").boundsInRoot
                val result = listOf(fullTip, fullRoot, fullBeyond, after.at(146, 20), after.at(3, 20), after.at(200, 20)) to bar
                after.dispose()
                result
            } finally {
                canvas.dispose()
                host.dispose()
            }
        }
        val (pixels, bar) = found
        val fullTip = pixels[0]
        val fullRoot = pixels[1]
        val fullMiddle = pixels[2]
        val hurtTip = pixels[3]
        val hurtRoot = pixels[4]
        val hurtBeyond = pixels[5]

        assertTrue(near(red, fullTip), "a full bar is red at its right end: $fullTip")
        assertTrue(near(green, fullRoot), "and green at its left: $fullRoot")
        assertTrue(fullMiddle.r > 0.2f && fullMiddle.g > 0.2f, "and in between in the middle: $fullMiddle")
        assertTrue(abs(bar.width - 150f) < 0.01f, "the click took half: ${bar.width}")
        assertTrue(near(red, hurtTip), "the shorter bar is still red at its tip: $hurtTip")
        assertTrue(near(green, hurtRoot), "and still green at its root: $hurtRoot")
        assertTrue(near(Colour.Black, hurtBeyond), "and nothing is drawn past it: $hurtBeyond")
    }

    @Test
    fun `hovering a card swaps its straight gradient for a radial glow on screen`() {
        assumeTrue(Gl.available, "needs a display")

        val (rest, hover) = Gl.render {
            val host = UiHost()
            val canvas = GdxCanvas()
            try {
                val focus = FocusManager(host.root)
                val renderer = UiRenderer(host, canvas).also { it.focus = focus }
                val pointer = PointerRouter(host.root, focus)
                host.setContent {
                    val state = remember { InteractionState() }
                    val brush = if (state.isHovered) Brush.radial(Colour.White, red) else Brush.vertical(green, green)
                    Box(Modifier.testTag("card").size(200f, 200f).interaction(state).background(brush, corner = 12f))
                }

                val before = frame(renderer, 0L)
                val atRest = before.at(100, 100) to before.at(100, 4)
                before.dispose()

                pointer.onPointer(PointerEvent.Move(PointerId.Mouse, host.root.find("card").boundsInRoot.centre))
                val after = frame(renderer, 16_666_667L)
                val hovered = after.at(100, 100) to after.at(100, 4)
                after.dispose()
                atRest to hovered
            } finally {
                canvas.dispose()
                host.dispose()
            }
        }

        assertTrue(near(green, rest.first) && near(green, rest.second), "flat green at rest: $rest")
        assertTrue(near(Colour.White, hover.first), "white in the middle under the pointer: ${hover.first}")
        assertTrue(hover.second.r > 0.9f && hover.second.g < 0.2f, "red at the rim: ${hover.second}")
    }
}
