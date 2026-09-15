package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.CameraDistanceUnit
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.rotate3d
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * `Modifier.rotate3d` on a real GPU, driven by `uiTest` and judged by the pixels that came out.
 *
 * The recording tests say which transform was asked for; these say the screen agrees, including
 * the part a recording cannot show: that the picture is divided by depth per pixel.
 */
class Rotate3dGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /**
     * A card 160 square at (40, 40), red on its left half and blue on its right, seen by a camera
     * 200 away. A click flips it over, the pad's South does the same, Right holds it at sixty
     * degrees, and Escape lays it flat again.
     */
    private fun card(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var flipped by remember { mutableStateOf(false) }
        var held by remember { mutableStateOf<Float?>(null) }
        val flip by animateFloatAsState(if (flipped) 180f else 0f, Tween(durationMillis = 300))
        Box(
            Modifier.offset(40f, 40f).size(160f)
                .rotate3d(y = held ?: flip, cameraDistance = 200f / CameraDistanceUnit)
                .background(red)
                .focusable(initial = true)
                .clickable { flipped = true }
                .onKeyEvent { event ->
                    when {
                        event.type != KeyEventType.Down -> false
                        event.key == Key.Escape -> { flipped = false; held = null; true }
                        event.key == Key.Right -> { held = 60f; true }
                        else -> false
                    }
                }
                .testTag("card"),
        ) {
            Box(Modifier.offset(80f, 0f).size(80f, 160f).background(blue))
        }
    }

    /** Clears, draws one frame of [ui] through the GL canvas, and reads the frame back. */
    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        do {
            val leftOver = Gdx.gl.glGetError()
        } while (leftOver != GL20.GL_NO_ERROR)
        ui.render()
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "the frame drew without a GL error")
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    /** y down from the top, as the toolkit counts it; OpenGL hands the bottom row back first. */
    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    private inline fun <T> Pixmap.use(block: (Pixmap) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }

    private inline fun withCard(block: (UiTest) -> Unit) {
        val backend = GdxBackend(HeadlessFonts.registry())
        try {
            val ui = card(backend)
            try {
                block(ui)
            } finally {
                ui.close()
            }
        } finally {
            backend.dispose()
        }
    }

    private fun assertFlat(frame: Pixmap, because: String) {
        assertColour(Color.RED, frame.at(60, 120), "$because: red on the left")
        assertColour(Color.BLUE, frame.at(180, 120), "$because: blue on the right")
        assertColour(Color.BLACK, frame.at(20, 120), "$because: nothing outside the box")
    }

    @Test
    fun `clicking the card turns it over on the screen and escape lays it flat again`() = Gl.render {
        withCard { ui ->
            frame(ui).use { assertFlat(it, "before anything") }

            ui.click("card")

            frame(ui).use {
                assertColour(Color.BLUE, it.at(60, 120), "turned over, blue has come round to the left")
                assertColour(Color.RED, it.at(180, 120), "and red to the right")
            }

            ui.key(Key.Escape)

            frame(ui).use { assertFlat(it, "laid flat again") }
        }
    }

    @Test
    fun `south on the pad turns the focused card over too`() = Gl.render {
        withCard { ui ->
            ui.pad(GamepadButton.South)

            frame(ui).use {
                assertColour(Color.BLUE, it.at(60, 120), "blue on the left")
                assertColour(Color.RED, it.at(180, 120), "red on the right")
            }
        }
    }

    @Test
    fun `a card held part way over is drawn in perspective without bending`() = Gl.render {
        withCard { ui ->
            ui.key(Key.Right)

            frame(ui).use {
                // Near edge at 58.8, far edge at 149.7, and the red and blue meeting at 120, where
                // the camera looks. Two flat triangles would put the meeting at 104.
                assertColour(Color.RED, it.at(112, 120), "the near red half takes more of the screen")
                assertColour(Color.BLUE, it.at(126, 120), "blue from the middle on")
                assertColour(Color.BLACK, it.at(160, 120), "the far edge has swung in")
                assertColour(Color.BLACK, it.at(50, 120), "and so has the near one")
                assertColour(Color.RED, it.at(64, 30), "the near edge is taller than the box")
            }
        }
    }
}
