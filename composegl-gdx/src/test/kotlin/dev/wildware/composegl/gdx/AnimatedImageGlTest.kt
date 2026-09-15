package dev.wildware.composegl.gdx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.widget.AnimatedImage
import dev.wildware.composegl.ui.widget.rememberSpriteAnimation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * A sprite sheet played on a real GPU, judged by the pixels that came out.
 *
 * The sheet is one texture cut into regions the way a packer cuts one, so what is on trial is the
 * whole road from `coin_0`, `coin_1`, `coin_2` in an atlas to the right third of a texture on the
 * screen at the right moment — and a stopped clock holding it there.
 */
class AnimatedImageGlTest {

    /** Three sixteen-pixel frames side by side: red, green, blue. */
    private fun sheet(): Pixmap = Pixmap(48, 16, Pixmap.Format.RGBA8888).apply {
        setColor(Color.RED); fillRectangle(0, 0, 16, 16)
        setColor(Color.GREEN); fillRectangle(16, 0, 16, 16)
        setColor(Color.BLUE); fillRectangle(32, 0, 16, 16)
    }

    private fun Pixmap.at(x: Int, y: Int) = Color(getPixel(x, Gl.size - 1 - y))

    private fun frame(ui: UiTest): Pixmap {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        return Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
    }

    private fun assertColour(expected: Color, actual: Color, because: String) {
        val close = abs(expected.r - actual.r) < 0.02f &&
            abs(expected.g - actual.g) < 0.02f &&
            abs(expected.b - actual.b) < 0.02f
        assertTrue(close, "$because: expected about $expected, got $actual")
    }

    /** Reads the middle of the animation and the corner outside it off one frame. */
    private fun UiTest.shows(expected: Color, because: String) {
        val pixels = frame(this)
        try {
            assertColour(expected, pixels.at(132, 132), because)
            assertColour(Color.BLACK, pixels.at(50, 50), "$because, and nothing outside the picture")
            // Every frame is drawn whole, scaled up, never a neighbour bleeding in at the edges.
            assertColour(expected, pixels.at(103, 103), "$because, at its top left")
            assertColour(expected, pixels.at(160, 160), "$because, at its bottom right")
        } finally {
            pixels.dispose()
        }
    }

    @Test
    fun `frames cut from one sheet play on the screen and hold when the world is paused`() = Gl.render {
        val backend = GdxBackend(HeadlessFonts.registry())
        val pixmap = sheet()
        val texture = Texture(pixmap)
        val atlas = ArtAtlas.of(
            // Named out of order, so the numbers have to be what orders them.
            mapOf(
                "coin_2" to GdxTexture(TextureRegion(texture, 32, 0, 16, 16)),
                "coin_0" to GdxTexture(TextureRegion(texture, 0, 0, 16, 16)),
                "coin_1" to GdxTexture(TextureRegion(texture, 16, 0, 16, 16)),
            ),
        )
        val ui = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend) {
            val clocks = LocalClocks.current
            var paused by remember { mutableStateOf(false) }
            Box(
                Modifier.offset(100f, 100f).size(64f, 64f)
                    .clickable {
                        paused = !paused
                        clocks.setRunning(Clock.World, !paused)
                    }
                    .testTag("coin"),
            ) {
                AnimatedImage(
                    rememberSpriteAnimation(atlas, prefix = "coin_", fps = 2f, clock = Clock.World),
                    Modifier.size(64f, 64f),
                )
            }
        }
        try {
            ui.shows(Color.RED, "the first frame")

            ui.advanceBy(500)
            ui.shows(Color.GREEN, "half a second on")

            // A click on the coin pauses the world.
            ui.click("coin")
            ui.advanceBy(3_000)
            ui.shows(Color.GREEN, "paused for three seconds")

            ui.click("coin")
            ui.advanceBy(500)
            ui.shows(Color.BLUE, "one frame on from where it stopped")

            // Less than a whole frame's length, because every action settles for a few frames more
            // than it was asked for, and this keeps the check well clear of the next boundary.
            ui.advanceBy(300)
            ui.shows(Color.RED, "round again")
        } finally {
            ui.close()
            texture.dispose()
            pixmap.dispose()
            backend.dispose()
        }
    }
}
