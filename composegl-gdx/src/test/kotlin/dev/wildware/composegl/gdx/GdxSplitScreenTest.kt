package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.GamepadId
import dev.wildware.composegl.ui.input.InputRouter
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Two players' interfaces in one window, drawn by the real renderer and read back as pixels.
 *
 * The headless tests prove the routing and the layout as numbers. This proves the numbers reach the
 * screen: each player's interface is drawn inside its own half and nowhere else, and a click the
 * router hands to player two changes player two's half of the picture and leaves player one's alone.
 */
class GdxSplitScreenTest {

    private val halves = Viewport.splitScreen(Design, Size(Side.toFloat(), Side.toFloat()), players = 2)

    /** A player's half: their colour, and a switch in the middle that goes green when pressed. */
    private fun player(index: Int, backend: GdxBackend, ground: Int, viewports: List<Viewport> = halves): UiTest =
        uiTest(viewports[index].design, backend, viewport = viewports[index]) {
            var on by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(Colour.rgb(ground.toLong())), contentAlignment = Alignment.Centre) {
                Box(
                    Modifier.size(100f, 100f)
                        .background(Colour.rgb(if (on) Green.toLong() else Red.toLong()))
                        .focusable(initial = true)
                        .clickable { on = true }
                        .testTag("switch"),
                )
            }
        }

    @Test
    fun `each player is drawn in their own half and a routed click changes only player two's`() {
        val (before, after) = Gl.render {
            val backends = List(2) { GdxBackend(HeadlessFonts.registry()) }
            val one = player(0, backends[0], Blue)
            val two = player(1, backends[1], Orange)
            try {
                val router = InputRouter().apply {
                    assignPointer(halves[0], one.input)
                    assignPointer(halves[1], two.input)
                }
                val before = frame(one, two)

                // The middle of the right half of the window, as a mouse there would report it.
                val at = Offset(300f, 200f)
                router.onPointer(PointerEvent.Move(PointerId.Mouse, at))
                router.onPointer(PointerEvent.Press(PointerId.Mouse, at))
                router.onPointer(PointerEvent.Release(PointerId.Mouse, at))
                one.settle()
                two.settle()

                before to frame(one, two)
            } finally {
                one.close()
                two.close()
                backends.forEach { it.dispose() }
            }
        }

        assertColour(before, 20, 20, Blue, "player one's ground, top left of the left half")
        assertColour(before, 380, 380, Orange, "player two's ground, bottom right of the right half")
        assertColour(before, 100, 200, Red, "player one's switch, in the middle of the left half")
        assertColour(before, 300, 200, Red, "player two's switch, in the middle of the right half")
        // Two in from the divider on each side: the very edge column is the ground's own soft edge.
        assertColour(before, 197, 20, Blue, "just left of the divider is still player one's")
        assertColour(before, 202, 20, Orange, "and just right of it is player two's, with none of player one's in it")

        assertColour(after, 300, 200, Green, "player two's switch after the click in the right half")
        assertColour(after, 100, 200, Red, "player one's switch, which nobody clicked")

        Goldens.assertMatches("split-screen", after)
    }

    @Test
    fun `a pad routed to player one presses only the switch in the left half`() {
        val image = Gl.render {
            val backends = List(2) { GdxBackend(HeadlessFonts.registry()) }
            val one = player(0, backends[0], Blue)
            val two = player(1, backends[1], Orange)
            try {
                val router = InputRouter().apply {
                    assignGamepad(GamepadId(0), one.input)
                    assignGamepad(GamepadId(1), two.input)
                }
                router.onGamepad(GamepadEvent.ButtonDown(GamepadId(0), GamepadButton.South))
                router.onGamepad(GamepadEvent.ButtonUp(GamepadId(0), GamepadButton.South))
                one.settle()
                two.settle()
                frame(one, two)
            } finally {
                one.close()
                two.close()
                backends.forEach { it.dispose() }
            }
        }

        assertColour(image, 100, 200, Green, "player one pressed South on pad 0")
        assertColour(image, 300, 200, Red, "player two's switch, on a pad nobody touched")
    }

    @Test
    fun `a design filled past the edge of its half is cut off at the divider`() {
        // A square design in a tall half, filled: each player's picture is twice as wide as their
        // half, so it hangs a quarter of the window over the divider unless the area cuts it off.
        val filled = Viewport.splitScreen(
            Size(Side.toFloat(), Side.toFloat()), Size(Side.toFloat(), Side.toFloat()), players = 2,
            policy = ScalePolicy.Fill,
        )
        val (oneLast, twoLast) = Gl.render {
            val backends = List(2) { GdxBackend(HeadlessFonts.registry()) }
            val one = player(0, backends[0], Blue, filled)
            val two = player(1, backends[1], Orange, filled)
            try {
                // Player two first, so player one is drawn over the divider; then player two again.
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                two.render()
                one.render()
                val oneOnTop = read()
                two.render()
                oneOnTop to read()
            } finally {
                one.close()
                two.close()
                backends.forEach { it.dispose() }
            }
        }

        assertColour(oneLast, 250, 20, Orange, "player one drawn last leaves the right half to player two")
        assertColour(twoLast, 150, 20, Blue, "player two drawn last leaves the left half to player one")
        assertColour(twoLast, 300, 20, Orange, "player two's own half")
    }

    /** Clears the window once, draws both players into it, and reads the whole window back. */
    private fun frame(one: UiTest, two: UiTest): BufferedImage {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        one.render()
        two.render()
        return read()
    }

    /** The whole window as it is now, the right way up. */
    private fun read(): BufferedImage {
        return Pixmap.createFromFrameBuffer(0, 0, Side, Side).let { frame ->
            // OpenGL hands back the bottom row first.
            imageOf(Side, Side) { x, y -> frame.getPixel(x, Side - 1 - y) ushr 8 }
                .also { frame.dispose() }
        }
    }

    private fun assertColour(image: BufferedImage, x: Int, y: Int, expected: Int, what: String) {
        val actual = image.getRGB(x, y) and 0xFFFFFF
        val close = (0..2).all { shift ->
            abs((actual shr (shift * 8) and 0xFF) - (expected shr (shift * 8) and 0xFF)) <= 8
        }
        assertTrue(close, "$what at ($x, $y): expected #%06X but was #%06X".format(expected, actual))
    }

    private companion object {
        /** The whole test window, which [Gl] opens this size. */
        const val Side = 400

        /** Each player's interface: exactly one half of the window, so nothing is scaled. */
        val Design = Size(200f, 400f)

        const val Blue = 0x2B4C7E
        const val Orange = 0xC8702A
        const val Red = 0xE0303A
        const val Green = 0x3CC45A
    }
}
