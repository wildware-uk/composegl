package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.text.scaledTextSizes
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * The text size setting on a real GPU, judged by the pixels.
 *
 * The headless tests prove the boxes grow. These prove the letters are *baked* at the bigger size
 * rather than stretched: text at 16 under a scale of two is pixel for pixel the text a style asking
 * for 32 draws, which is only true if both came out of the glyphs baked at 32.
 */
class TextScaleGlTest {

    private val white = Colour.rgb(0xFFFFFF)

    private fun style(size: Float) = TextStyle(family = "test", size = size)

    /** Every size the tests here draw at: 16 and 20 at each scale they use. */
    private fun fonts(): GdxFonts = GdxFonts().also {
        it.registerTrueType(
            "test",
            Gdx.files.internal("fonts/DejaVuSans.ttf"),
            scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f, 2f)),
        )
    }

    private fun open(backend: GdxBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)

    /** One frame of [ui], read back the right way up as a picture [width] by [height] from the top left. */
    private fun frame(ui: UiTest, width: Int, height: Int): BufferedImage {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        try {
            // OpenGL hands back the bottom row first, and the toolkit counts y down from the top.
            return imageOf(width, height) { x, y -> pixmap.getPixel(x, Gl.size - 1 - y) ushr 8 }
        } finally {
            pixmap.dispose()
        }
    }

    private fun sameImage(a: BufferedImage, b: BufferedImage): Boolean {
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    /** Rows holding anything brighter than half grey: how tall the ink is. */
    private fun inkHeight(image: BufferedImage): Int =
        (0 until image.height).count { y -> (0 until image.width).any { x -> (image.getRGB(x, y) and 0xFF) > 128 } }

    private fun <T> withBackend(block: (GdxBackend) -> T): T = Gl.render {
        val fonts = fonts()
        val backend = GdxBackend(fonts)
        try {
            block(backend)
        } finally {
            backend.dispose()
            fonts.dispose()
        }
    }

    private fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    @Test
    fun `text under a scale of two is drawn from the glyphs baked at twice the size`() = withBackend { backend ->
        val scaled = open(backend) {
            ProvideTextScale(2f) { Text("Hg", Modifier.offset(10f, 10f), textStyle = style(16f), colour = white) }
        }.using { frame(it, 200, 100) }
        val direct = open(backend) {
            Text("Hg", Modifier.offset(10f, 10f), textStyle = style(32f), colour = white)
        }.using { frame(it, 200, 100) }
        val plain = open(backend) {
            Text("Hg", Modifier.offset(10f, 10f), textStyle = style(16f), colour = white)
        }.using { frame(it, 200, 100) }

        assertTrue(inkHeight(plain) > 0, "the plain label should have drawn something")
        assertTrue(sameImage(scaled, direct), "a scale of two should draw exactly what a 32 style draws")
        assertFalse(sameImage(scaled, plain), "and not what the 16 style draws")
        val ratio = inkHeight(scaled).toFloat() / inkHeight(plain)
        assertTrue(ratio in 1.8f..2.2f, "the letters should be about twice as tall, were $ratio times")
    }

    @Test
    fun `a scaled label is laid out exactly like one styled at the scaled size`() = withBackend { backend ->
        val scaled = open(backend) {
            ProvideTextScale(1.25f) { Text("Hull integrity", Modifier.testTag("t"), textStyle = style(16f)) }
        }.using { it.node("t").let { node -> node.width to node.height } }
        val direct = open(backend) {
            Text("Hull integrity", Modifier.testTag("t"), textStyle = style(20f))
        }.using { it.node("t").let { node -> node.width to node.height } }

        assertEquals(direct, scaled)
    }

    @Test
    fun `a scale whose size was never registered says which sizes were`() = Gl.render {
        val fonts = GdxFonts().also {
            it.registerTrueType("test", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(16))
        }
        val backend = GdxBackend(fonts)
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                open(backend) { ProvideTextScale(1.5f) { Text("Hull", textStyle = style(16f)) } }.close()
            }
            assertTrue("test at 24" in failure.message.orEmpty(), failure.message)
            assertTrue("[16]" in failure.message.orEmpty(), failure.message)
        } finally {
            backend.dispose()
            fonts.dispose()
        }
    }

    @Test
    fun `three text sizes match the golden`() {
        val image = withBackend { backend ->
            open(backend) {
                Column(Modifier.offset(8f, 8f), verticalArrangement = Arrangement.spacedBy(6f)) {
                    listOf(1f, 1.25f, 1.5f).forEach { scale ->
                        ProvideTextScale(scale) {
                            Text("Hull integrity 148", textStyle = style(16f), colour = white)
                        }
                    }
                }
            }.using { frame(it, 240, 110) }
        }
        Goldens.assertMatches("text-scale", image)
    }
}
