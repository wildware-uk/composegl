package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.LazyRow
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * Right-to-left screens and Hebrew text on a real GPU, judged by the pixels.
 *
 * The headless tests prove the order pieces are handed to the backend in and where. These prove
 * what comes out: a mirrored row really is mirrored on the screen, and a Hebrew word is the same
 * ink as its letters drawn one by one from the right — and not the ink of them drawn as typed.
 */
class RightToLeftGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val white = Colour.rgb(0xFFFFFF)

    private val face = TextStyle(family = "test", size = 32f)

    /** DejaVu Sans, which has the Hebrew alphabet, with the Hebrew letters baked alongside Latin. */
    private fun fonts(): GdxFonts = GdxFonts().also {
        it.registerTrueType("test", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(32)) { characters += Hebrew }
    }

    private fun <T> withBackend(block: (GdxBackend, GdxFonts) -> T): T = Gl.render {
        val fonts = fonts()
        val backend = GdxBackend(fonts)
        try {
            block(backend, fonts)
        } finally {
            backend.dispose()
        }
    }

    private fun frame(backend: GdxBackend, width: Int = 300, height: Int = 80, content: @Composable () -> Unit): BufferedImage {
        val ui: UiTest = uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)
        try {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            ui.render()
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            try {
                return imageOf(width, height) { x, y -> pixmap.getPixel(x, Gl.size - 1 - y) ushr 8 }
            } finally {
                pixmap.dispose()
            }
        } finally {
            ui.close()
        }
    }

    private fun BufferedImage.rgb(x: Int, y: Int) = getRGB(x, y) and 0xFFFFFF

    private fun brightness(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

    /** How many pixels differ between two frames of the same size by more than a rounding error. */
    private fun differences(a: BufferedImage, b: BufferedImage): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (kotlin.math.abs(brightness(a.rgb(x, y)) - brightness(b.rgb(x, y))) > 8) count++
        }
        return count
    }

    private fun inked(image: BufferedImage) = (0 until image.height).sumOf { y -> (0 until image.width).count { x -> brightness(image.rgb(x, y)) > 64 } }

    @Test
    fun `a row in a right to left screen is drawn mirrored`() = withBackend { backend, _ ->
        val image = frame(backend) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Row(Modifier.width(200f)) {
                    Box(Modifier.size(50f).background(red))
                    Box(Modifier.size(50f).background(blue))
                }
            }
        }

        assertColour(0xFF0000, image.rgb(175, 25), "the first box is at the right")
        assertColour(0x0000FF, image.rgb(125, 25), "the second is to its left")
        assertColour(0x000000, image.rgb(25, 25), "and the left of the row is empty")
    }

    @Test
    fun `a lazy row in a right to left screen is drawn from the right`() = withBackend { backend, _ ->
        val image = frame(backend) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                LazyRow(10, Modifier.width(200f).height(50f), bars = false) { index ->
                    Box(Modifier.size(50f).background(if (index == 0) red else blue))
                }
            }
        }

        assertColour(0xFF0000, image.rgb(175, 25), "the first item is at the right")
        assertColour(0x0000FF, image.rgb(125, 25), "the second is to its left")
        assertColour(0x0000FF, image.rgb(25, 25), "and the row carries on to the left edge")
    }

    /** Within a couple of levels on each channel, which is as close as a driver's blending rounds. */
    private fun assertColour(expected: Int, actual: Int, because: String) {
        val close = (0..2).all { channel ->
            val shift = channel * 8
            kotlin.math.abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 4
        }
        assertTrue(close, "$because: expected about ${expected.toString(16)}, got ${actual.toString(16)}")
    }

    @Test
    fun `a hebrew word is drawn as its letters from the right`() = withBackend { backend, fonts ->
        val word = "שלום"
        val label = frame(backend) { Text(word, textStyle = face, colour = white) }
        // The backend handed the word already reversed, and handed it as typed, with nothing between.
        val reversed = frame(backend) { Raw(fonts.measure(word.reversed(), face)) }
        val asTyped = frame(backend) { Raw(fonts.measure(word, face)) }

        assertTrue(inked(label) > 100, "the label drew something: ${inked(label)} pixels")
        assertEquals(0, differences(label, reversed), "the label is the reversed letters, pixel for pixel")
        assertTrue(differences(label, asTyped) > 100, "and not the letters in the order they were typed")
    }

    @Test
    fun `a coloured hebrew word after english is drawn to the right of it`() = withBackend { backend, _ ->
        val image = frame(backend, width = 400) {
            Text("abc שלום", textStyle = face, colour = white, runs = listOf(TextRun(TextRange(4, 8), colour = red)))
        }

        var whiteRight = -1
        var redLeft = Int.MAX_VALUE
        var redPixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.rgb(x, y)
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            if (r > 128 && g > 128) whiteRight = maxOf(whiteRight, x)
            if (r > 128 && g < 64) {
                redLeft = minOf(redLeft, x)
                redPixels++
            }
        }

        assertTrue(redPixels > 100, "the Hebrew was drawn in red: $redPixels pixels")
        assertTrue(redLeft > whiteRight, "all of the red is right of all of the white: red from $redLeft, white to $whiteRight")
    }

    @Composable
    private fun Raw(layout: dev.wildware.composegl.ui.text.TextLayout) {
        LeafLayout(Modifier.size(300f, 80f), draw = { bounds -> text(layout, Offset(bounds.left, bounds.top), white) })
    }

    private companion object {
        const val Hebrew = "אבגדהוזחטיכךלמםנןסעפףצץקרשת"
    }
}
