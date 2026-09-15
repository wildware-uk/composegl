package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onKeyEvent
import dev.wildware.composegl.ui.modifier.perspective
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
 * `Modifier.perspective` on a real GPU: a row of cards tilted together, driven through `uiTest`
 * and judged by the pixels that came out.
 */
class PerspectiveGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /**
     * Three 100 by 80 cards, red on the left half and blue on the right, in a row at (20, 160) with
     * 20 between them, so the row's middle — where its camera is, 300 away — is (190, 200). Right
     * or South tilts them fifty degrees about y, Escape lays them flat, and the box in the corner
     * takes the shared camera away.
     */
    private fun row(backend: GdxBackend): UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
        var tilt by remember { mutableStateOf(0f) }
        var shared by remember { mutableStateOf(true) }
        Box(Modifier.testTag("toggle").size(20f).clickable { shared = !shared })
        Row(
            modifier = Modifier.testTag("row")
                .offset(20f, 160f)
                .let { if (shared) it.perspective(300f) else it }
                .focusable(initial = true)
                .clickable { tilt = 50f }
                .onKeyEvent { event ->
                    when {
                        event.type != KeyEventType.Down -> false
                        event.key == Key.Right -> { tilt = 50f; true }
                        event.key == Key.Escape -> { tilt = 0f; true }
                        else -> false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(20f),
        ) {
            repeat(3) {
                Box(Modifier.size(100f, 80f).rotate3d(y = tilt).background(red)) {
                    Box(Modifier.offset(50f, 0f).size(50f, 80f).background(blue))
                }
            }
        }
    }

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

    private inline fun withRow(block: (UiTest) -> Unit) {
        val backend = GdxBackend(HeadlessFonts.registry())
        try {
            val ui = row(backend)
            try {
                block(ui)
            } finally {
                ui.close()
            }
        } finally {
            backend.dispose()
        }
    }

    /**
     * The row tilted under its one camera. Worked out by hand: the left card is seen from x = 15.6
     * to 112.1, meeting red to blue at 70; the right card only from 290.7 to 324.9, meeting at 310.
     */
    private fun assertShared(frame: Pixmap, because: String) {
        assertColour(Color.RED, frame.at(25, 200), "$because: the left card's near edge swings out past 35")
        assertColour(Color.BLUE, frame.at(106, 200), "$because: and its far edge stays out past 100")
        assertColour(Color.BLACK, frame.at(282, 200), "$because: the right card's near edge is pulled in")
        assertColour(Color.RED, frame.at(300, 200), "$because: its red half")
        assertColour(Color.BLUE, frame.at(318, 200), "$because: its blue half")
        assertColour(Color.BLACK, frame.at(333, 200), "$because: and its far edge is well short of 340")
    }

    @Test
    fun `pressing right tilts the row towards one vanishing point on the screen`() = Gl.render {
        withRow { ui ->
            ui.key(Key.Right)

            frame(ui).use { assertShared(it, "tilted") }
        }
    }

    @Test
    fun `south on the pad does the same`() = Gl.render {
        withRow { ui ->
            ui.pad(GamepadButton.South)

            frame(ui).use { assertShared(it, "after South") }
        }
    }

    @Test
    fun `without the shared camera every card is the same shape`() = Gl.render {
        withRow { ui ->
            ui.key(Key.Right)
            ui.click("toggle")

            frame(ui).use {
                // Each card from 35.6 to 100.1 about its own middle, and the same again 240 along.
                assertColour(Color.BLACK, it.at(25, 200), "the left card no longer reaches out to 25")
                assertColour(Color.RED, it.at(282, 200), "the right card's near edge is back out at 276")
                assertColour(Color.BLUE, it.at(333, 200), "and its far edge at 340")
                for (x in listOf(40, 60, 80, 96)) {
                    assertEquals(it.at(x, 200), it.at(x + 240, 200), "the left and right cards match at x = $x")
                }
            }
        }
    }

    @Test
    fun `escape lays the row flat`() = Gl.render {
        withRow { ui ->
            ui.key(Key.Right)
            ui.key(Key.Escape)

            frame(ui).use {
                assertColour(Color.RED, it.at(30, 200), "the left card flat: red")
                assertColour(Color.BLUE, it.at(110, 200), "and blue")
                assertColour(Color.BLACK, it.at(125, 200), "and the gap after it")
                assertColour(Color.BLUE, it.at(350, 200), "the right card flat, reaching 360")
            }
        }
    }

    @Test
    fun `a tilted row under one camera matches its golden`() = Gl.render {
        withRow { ui ->
            ui.key(Key.Right)

            frame(ui).use { frame ->
                val image = imageOf(Gl.size, 120) { x, y -> Color.rgb888(frame.at(x, y + 140)) }
                Goldens.assertMatches("perspective-row", image)
            }
        }
    }
}
