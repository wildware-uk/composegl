package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage

/**
 * Characters the main font does not have, drawn by the raw OpenGL backend and judged by the pixels.
 *
 * The same promises the LibGDX backend's tests hold it to: a borrowed name is exactly the ink the
 * fallback font draws, and an emoji is drawn in its own colours and kept out of an outline's ring.
 */
class StbFontFallbackGlTest {

    private val white = Colour.rgb(0xFFFFFF)
    private val red = Colour.rgb(0xFF0000)
    private val style = TextStyle(family = "body", size = 24f)

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.readBytes()

    private fun fonts() = StbFonts().apply {
        register("body", bytes("/fonts/DejaVuSans.ttf"), listOf(24))
        register("cjk", bytes("/fonts/NotoSansSC-Subset.ttf"), listOf(24), StbFonts.codepointsOf("玩家你好"))
        registerPictures("emoji", mapOf("😀" to bytes("/emoji/emoji_u1f600.png")), listOf(24))
        fallBackTo(listOf("cjk", "emoji"))
    }

    private fun <T> withBackend(block: (Lwjgl3Backend, StbFonts) -> T): T = Gl.render {
        val fonts = fonts()
        val backend = Lwjgl3Backend(Gl.window, fonts)
        try {
            block(backend, fonts)
        } finally {
            backend.close()
            fonts.close()
        }
    }

    private fun open(backend: Lwjgl3Backend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)

    private fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /** One frame of [ui], the top-left [width] by [height] of it. */
    private fun frame(ui: UiTest, width: Int = 300, height: Int = 120): BufferedImage {
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        ui.render()
        val pixels = Gl.readPixels(Gl.size, Gl.size)
        return imageOf(width, height) { x, y -> pixels[y * Gl.size + x] }
    }

    private fun brightness(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

    /** The ink inside [rows], cut to its own box, so two runs drawn at different places compare. */
    private fun ink(image: BufferedImage, rows: IntRange = 0 until image.height): BufferedImage {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in rows) for (x in 0 until image.width) {
            if (brightness(image.getRGB(x, y)) > 64) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        check(right >= 0) { "nothing was drawn in rows $rows" }
        return image.getSubimage(left, top, right - left + 1, bottom - top + 1)
    }

    private fun sameImage(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    @Test
    fun `clicking to a Chinese name draws the fallback font's own ink`() = withBackend { backend, fonts ->
        val alone = open(backend) {
            Text("玩家你好", Modifier.offset(40f, 30f), textStyle = style.copy(family = "cjk"), colour = white)
        }.using { ink(frame(it)) }

        open(backend) {
            var name by remember { mutableStateOf("Ace") }
            Column {
                Text(name, Modifier.offset(10f, 10f).testTag("name"), textStyle = style, colour = white)
                Box(Modifier.offset(10f, 60f).size(80f, 30f).background(Colour.rgb(0x202020)).clickable { name = "玩家你好" }.testTag("next"))
            }
        }.using { ui ->
            assertFalse(sameImage(alone, ink(frame(ui), 0 until 50)), "Ace is not the Chinese name")

            ui.click("next")

            assertTrue(sameImage(alone, ink(frame(ui), 0 until 50)), "the name should be exactly the fallback font's ink")
            assertEquals(fonts.measure("玩家你好", style).size.width, ui.node("name").width)
        }
    }

    @Test
    fun `an emoji keeps its colours in red text and stays out of the outline ring`() = withBackend { backend, _ ->
        val plain = open(backend) {
            Text("😀", Modifier.offset(10f, 10f), textStyle = style, colour = red)
        }.using { frame(it) }
        val outlined = open(backend) {
            Text("😀", Modifier.offset(10f, 10f), textStyle = style, colour = red, outline = TextOutline(Colour.rgb(0x3060FF), width = 2f))
        }.using { frame(it) }

        val yellow = (0 until plain.width).sumOf { x ->
            (0 until plain.height).count { y ->
                val rgb = plain.getRGB(x, y)
                (rgb shr 16 and 0xFF) > 180 && (rgb shr 8 and 0xFF) > 140
            }
        }
        assertTrue(yellow > 40, "the emoji should be yellow inside red text, found $yellow yellow pixels")
        assertTrue(sameImage(plain, outlined), "no copies of the picture smeared round it")
    }
}
