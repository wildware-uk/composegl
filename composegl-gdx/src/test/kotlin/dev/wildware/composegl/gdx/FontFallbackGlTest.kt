package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextOutline
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.ProvideTextOutline
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.TypewriterEffect
import dev.wildware.composegl.ui.widget.rememberTypewriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * Characters the display font does not have, on a real GPU, judged by the pixels.
 *
 * The headless tests prove the widths come from the right font. These prove the right *shapes* are
 * drawn: a name taken from the fallback is, pixel for pixel, the same ink that font draws on its
 * own, an emoji keeps its colours inside coloured text, and glyphs generated when first asked for
 * — onto a page that fills and spills onto another — come out exactly like glyphs baked up front.
 */
class FontFallbackGlTest {

    private val white = Colour.rgb(0xFFFFFF)
    private val red = Colour.rgb(0xFF0000)

    private fun style(size: Float = 24f, family: String = "test") = TextStyle(family = family, size = size)

    /**
     * DejaVu Sans as the display font, which has no Chinese, Japanese or Korean, with small cuts of
     * Noto Sans CJK and a Noto emoji behind it.
     */
    private fun fonts(atlas: GdxAtlas = GdxAtlas(), cjkOnDemand: Boolean = false): GdxFonts = GdxFonts(atlas).also {
        it.registerTrueType("test", Gdx.files.internal("fonts/DejaVuSans.ttf"), Sizes)
        it.registerTrueType("cjk", Gdx.files.internal("fonts/NotoSansSC-Subset.ttf"), Sizes, onDemand = cjkOnDemand) {
            if (!cjkOnDemand) characters += Chinese + Japanese
        }
        it.registerTrueType("korean", Gdx.files.internal("fonts/NotoSansKR-Subset.ttf"), Sizes) { characters += Korean }
        val smiley = Pixmap(Gdx.files.internal("emoji/emoji_u1f600.png"))
        try {
            it.registerPictures("emoji", mapOf("😀" to smiley), Sizes)
        } finally {
            smiley.dispose()
        }
        it.fallBackTo(listOf("cjk", "korean", "emoji"))
    }

    private fun open(backend: GdxBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)

    /** One frame of [ui], the right way up, [width] by [height] from the top left. */
    private fun frame(ui: UiTest, width: Int = 300, height: Int = 120): BufferedImage {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        try {
            return imageOf(width, height) { x, y -> pixmap.getPixel(x, Gl.size - 1 - y) ushr 8 }
        } finally {
            pixmap.dispose()
        }
    }

    /**
     * [block] with a backend over the fonts [make] builds — on the GL thread, because a font
     * registry makes pixmaps and LibGDX's natives are only loaded once the application is up.
     */
    private fun <T> withBackend(make: () -> GdxFonts = { fonts() }, block: (GdxBackend, GdxFonts) -> T): T = Gl.render {
        val fonts = make()
        val backend = GdxBackend(fonts)
        try {
            block(backend, fonts)
        } finally {
            // Disposes the fonts too.
            backend.dispose()
        }
    }

    private fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    // --- looking at pixels ---

    private class Box2(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        override fun toString() = "($left, $top)..($right, $bottom)"
    }

    private fun brightness(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)

    /** The smallest box round everything brighter than a quarter, inside rows [rows]. */
    private fun inkBox(image: BufferedImage, rows: IntRange = 0 until image.height): Box2 {
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
        return Box2(left, top, right, bottom)
    }

    /** The ink inside [rows], cut to its own box, so two runs drawn at different places compare. */
    private fun ink(image: BufferedImage, rows: IntRange = 0 until image.height): BufferedImage {
        val box = inkBox(image, rows)
        return image.getSubimage(box.left, box.top, box.width, box.bottom - box.top + 1)
    }

    private fun sameImage(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
        return true
    }

    // --- the tests ---

    @Test
    fun `clicking to a player whose name the display font lacks draws it from the fallback`() = withBackend { backend, fonts ->
        val alone = open(backend) {
            Text("玩家你好", Modifier.offset(40f, 30f), textStyle = style(family = "cjk"), colour = white)
        }.using { ink(frame(it)) }

        open(backend) {
            var name by remember { mutableStateOf("Ace") }
            Column {
                Text(name, Modifier.offset(10f, 10f).testTag("name"), textStyle = style(), colour = white)
                Box(Modifier.offset(10f, 60f).size(80f, 30f).background(Colour.rgb(0x202020)).clickable { name = "玩家你好" }.testTag("next"))
            }
        }.using { ui ->
            val before = ink(frame(ui), 0 until 50)
            assertFalse(sameImage(alone, before), "Ace is not the Chinese name")

            ui.click("next")

            val after = frame(ui)
            assertTrue(sameImage(alone, ink(after, 0 until 50)), "the name should be exactly the fallback font's ink")
            assertEquals(fonts.measure("玩家你好", style()).size.width, ui.node("name").width)
        }
    }

    @Test
    fun `without a fallback the same name is not drawn in those shapes`() = withBackend { backend, fonts ->
        val alone = open(backend) {
            Text("玩家你好", Modifier.offset(10f, 10f), textStyle = style(family = "cjk"), colour = white)
        }.using { ink(frame(it)) }
        fonts.fallBackTo(emptyList())

        val boxes = open(backend) {
            Text("玩家你好", Modifier.offset(10f, 10f), textStyle = style(), colour = white)
        }.using { ink(frame(it)) }

        assertFalse(sameImage(alone, boxes))
    }

    @Test
    fun `an emoji keeps its own colours inside red text`() = withBackend { backend, fonts ->
        open(backend) {
            Text("GG 😀", Modifier.offset(10f, 10f).testTag("chat"), textStyle = style(), colour = red)
        }.using { ui ->
            val image = frame(ui)
            val gg = fonts.measure("GG ", style()).size.width.toInt() + 10
            val node = ui.node("chat")

            // The face is yellow: green well above nothing. Red text tinting it would leave no
            // green at all, which is exactly what the letters have.
            val yellow = (gg until 10 + node.width.toInt()).sumOf { x ->
                (10 until 10 + node.height.toInt()).count { y ->
                    val rgb = image.getRGB(x, y)
                    (rgb shr 16 and 0xFF) > 180 && (rgb shr 8 and 0xFF) > 140
                }
            }
            val greenInLetters = (10 until gg - 4).sumOf { x ->
                (10 until 10 + node.height.toInt()).count { y -> (image.getRGB(x, y) shr 8 and 0xFF) > 40 }
            }
            assertTrue(yellow > 40, "the emoji should be drawn in yellow, found $yellow yellow pixels")
            assertEquals(0, greenInLetters, "the letters are red")
        }
    }

    @Test
    fun `an outline rings the letters and leaves the emoji untouched`() = withBackend { backend, _ ->
        val ring = TextOutline(Colour.rgb(0x3060FF), width = 2f)
        val plain = open(backend) {
            Text("😀", Modifier.offset(10f, 10f), textStyle = style(), colour = white)
        }.using { frame(it) }
        val outlined = open(backend) {
            Text("😀", Modifier.offset(10f, 10f), textStyle = style(), colour = white, outline = ring)
        }.using { frame(it) }
        val letters = open(backend) {
            Text("G", Modifier.offset(10f, 10f), textStyle = style(), colour = white, outline = ring)
        }.using { frame(it) }

        assertTrue(sameImage(plain, outlined), "no copies of the picture smeared round it")
        val blue = (0 until letters.width).sumOf { x -> (0 until letters.height).count { y -> (letters.getRGB(x, y) and 0xFF) > 200 && (letters.getRGB(x, y) shr 16 and 0xFF) < 100 } }
        assertTrue(blue > 10, "a letter still gets its ring, found $blue ring pixels")
    }

    @Test
    fun `a typewriter with an effect and an outline leaves the emoji out of the ring too`() = withBackend { backend, _ ->
        // An effect draws a character at a time, so the ring copies come from the typewriter and
        // not from the canvas's own outlined run. They have to leave the picture out just the same.
        fun dialogue(outline: TextOutline?) = open(backend) {
            val line = rememberTypewriter("😀")
            ProvideTextOutline(outline) {
                Column {
                    Typewriter(line, Modifier.offset(10f, 10f), textStyle = style(), colour = white, effect = TypewriterEffect.fadeIn())
                    Box(Modifier.offset(10f, 60f).size(80f, 30f).background(Colour.rgb(0x202020)).clickable { line.skip() }.testTag("skip"))
                }
            }
        }.using { ui ->
            ui.click("skip")
            ui.advanceBy(1_000)
            frame(ui, height = 50)
        }

        val plain = dialogue(null)
        val outlined = dialogue(TextOutline(Colour.rgb(0x3060FF), width = 2f))

        assertTrue(inkBox(plain).width > 10, "the emoji should have been typed out")
        assertTrue(sameImage(plain, outlined), "no copies of the picture smeared round it")
    }

    @Test
    fun `typing Korean into a chat field draws every syllable`() = withBackend { backend, fonts ->
        // The stock skin's field asks for "default", which falls back like every other family.
        fonts.registerTrueType("default", Gdx.files.internal("fonts/DejaVuSans.ttf"), Sizes)
        val fieldStyle = TextStyle(family = "default", size = 16f)
        var typed = ""
        open(backend) {
            var value by remember { mutableStateOf("") }
            TextField(
                value,
                onValueChange = {
                    value = it
                    typed = it
                },
                modifier = Modifier.offset(10f, 10f).width(260f).testTag("chat"),
                initialFocus = true,
            )
        }.using { ui ->
            val empty = frame(ui)

            ui.type("안녕")

            val full = frame(ui)
            assertEquals("안녕", typed)
            var left = Int.MAX_VALUE
            var right = -1
            for (y in 0 until full.height) for (x in 0 until full.width) {
                if (full.getRGB(x, y) != empty.getRGB(x, y)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
            val measured = fonts.measure("안녕", fieldStyle).size.width
            assertTrue(right - left + 1 >= measured - 4, "the new ink is ${right - left + 1} wide, the text measures $measured")
        }
    }

    @Test
    fun `glyphs made on demand draw exactly like glyphs baked up front`() {
        val text = "聊天游戏"
        val baked = withBackend({ fonts() }) { backend, _ ->
            open(backend) { Text(text, Modifier.offset(10f, 10f), textStyle = style(), colour = white) }.using { frame(it) }
        }
        val onDemand = withBackend({ fonts(cjkOnDemand = true) }) { backend, _ ->
            open(backend) { Text(text, Modifier.offset(10f, 10f), textStyle = style(), colour = white) }.using { frame(it) }
        }

        assertTrue(sameImage(baked, onDemand))
    }

    @Test
    fun `a small atlas that fills up grows onto another page and still draws every glyph`() {
        val text = Chinese + Japanese
        val roomy = withBackend({ fonts(cjkOnDemand = true) }) { backend, _ ->
            open(backend) { Text(text, Modifier.offset(4f, 10f), textStyle = style(), colour = white) }.using { frame(it, 380, 60) }
        }
        var pages = 0
        val cramped = withBackend({ fonts(GdxAtlas(pageSize = 256), cjkOnDemand = true) }) { backend, fonts ->
            open(backend) { Text(text, Modifier.offset(4f, 10f), textStyle = style(), colour = white) }.using { frame(it, 380, 60) }
                .also { pages = fonts.atlas.pageCount }
        }

        assertTrue(pages > 1, "the small atlas should have needed another page, had $pages")
        assertTrue(sameImage(roomy, cramped), "every glyph, wherever it was packed, draws the same")
    }

    @Test
    fun `mixed scripts and an emoji match the golden`() {
        val image = withBackend { backend, _ ->
            open(backend) {
                Column(Modifier.offset(8f, 8f), verticalArrangement = Arrangement.spacedBy(4f)) {
                    Text("Ace 玩家 😀", textStyle = style(), colour = white)
                    Text("こんにちは 안녕", textStyle = style(16f), colour = Colour.rgb(0x9FE0FF))
                    Text("GG 你好 😀", textStyle = style(), colour = Colour.rgb(0xFFD060), outline = TextOutline(Colour.rgb(0x000000), width = 2f))
                }
            }.using { frame(it, 240, 100) }
        }
        Goldens.assertMatches("text-fallback", image)
    }

    private companion object {
        val Sizes = listOf(16, 24)
        const val Chinese = "你好世界玩家聊天欢迎来到游戏"
        const val Japanese = "こんにちは"
        const val Korean = "안녕하세요"
    }
}
