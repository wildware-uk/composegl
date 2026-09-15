package dev.wildware.composegl.korge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
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
import dev.wildware.composegl.ui.widget.ProvideTextOutline
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Typewriter
import dev.wildware.composegl.ui.widget.TypewriterEffect
import dev.wildware.composegl.ui.widget.rememberTypewriter
import korlibs.image.bitmap.Bitmap32
import korlibs.image.format.PNG
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Characters the display font does not have, drawn through KorGE and judged by the pixels. The same
 * questions the LibGDX backend's `FontFallbackGlTest` asks.
 *
 * The measuring tests prove the widths come from the right font. These prove the right shapes are
 * drawn: a name taken from the fallback is, pixel for pixel, the same ink that font draws on its own,
 * an emoji keeps its colours inside coloured text and stays out of an outline's ring, and glyphs
 * packed onto a page that fills and spills onto another come out exactly the same.
 */
class KorgeFontFallbackGlTest {

    private val size = KorgeGl.size.toFloat()
    private val white = Colour.rgb(0xFFFFFF)
    private val red = Colour.rgb(0xFF0000)

    private fun style(size: Float = 24f, family: String = "test") = TextStyle(family = family, size = size)

    /** DejaVu Sans as the display font, with Noto Sans CJK cuts and a Noto emoji behind it. */
    private fun fonts(atlas: KorgeAtlas = KorgeAtlas()) = KorgeFonts(atlas).also {
        it.registerTrueType("test", TestFonts.dejaVu(), Sizes)
        it.registerTrueType("cjk", TestPictures.chinese(), Sizes)
        it.registerTrueType("korean", TestPictures.korean(), Sizes)
        it.registerPictures("emoji", mapOf(Emoji to TestPictures.smiley()), Sizes)
        it.fallBackTo(listOf("cjk", "korean", "emoji"))
    }

    private fun <T> withBackend(fonts: KorgeFonts = fonts(), block: (KorgeBackend, KorgeFonts) -> T): T {
        val backend = KorgeBackend(fonts)
        try {
            return block(backend, fonts)
        } finally {
            backend.close()
        }
    }

    private fun open(backend: KorgeBackend, content: @Composable () -> Unit): UiTest = uiTest(Size(size, size), backend, content = content)

    private fun <T> UiTest.using(block: (UiTest) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /** One frame of [ui] through the backend's canvas, over black, y down from the top. */
    private fun frame(ui: UiTest, backend: KorgeBackend): Bitmap32 = KorgeGl.picture { ctx ->
        backend.canvas.renderContext = ctx
        try {
            ui.render()
        } finally {
            backend.canvas.renderContext = null
        }
    }

    // --- looking at pixels ---

    private class InkBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        override fun toString() = "($left, $top)..($right, $bottom)"
    }

    private fun brightness(image: Bitmap32, x: Int, y: Int) = image[x, y].let { maxOf(it.r, it.g, it.b) }

    /** The smallest box round everything brighter than a quarter, inside [rows]. */
    private fun inkBox(image: Bitmap32, rows: IntRange = 0 until image.height): InkBox {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in rows) for (x in 0 until image.width) {
            if (brightness(image, x, y) > 64) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        check(right >= 0) { "nothing was drawn in rows $rows" }
        return InkBox(left, top, right, bottom)
    }

    /** The ink inside [rows], cut to its own box, so two runs drawn at different places compare. */
    private fun ink(image: Bitmap32, rows: IntRange = 0 until image.height): List<List<Int>> {
        val box = inkBox(image, rows)
        return (box.top..box.bottom).map { y -> (box.left..box.right).map { x -> image[x, y].value } }
    }

    /** Why two crops of ink are not the same, or null when they are. */
    private fun difference(a: List<List<Int>>, b: List<List<Int>>): String? {
        if (a.size != b.size || a[0].size != b[0].size) return "${a[0].size}x${a.size} against ${b[0].size}x${b.size}"
        var first: String? = null
        var count = 0
        for (y in a.indices) for (x in a[y].indices) if (a[y][x] != b[y][x]) {
            count++
            if (first == null) first = "($x, $y): ${Integer.toHexString(a[y][x])} against ${Integer.toHexString(b[y][x])}"
        }
        return if (count == 0) null else "$count pixels differ, first at $first"
    }

    private fun same(a: Bitmap32, b: Bitmap32) = (0 until a.height).all { y -> (0 until a.width).all { x -> a[x, y] == b[x, y] } }

    private fun count(image: Bitmap32, area: IntRange = 0 until KorgeGl.size, rows: IntRange = 0 until KorgeGl.size, test: (r: Int, g: Int, b: Int) -> Boolean) =
        area.sumOf { x -> rows.count { y -> image[x, y].let { test(it.r, it.g, it.b) } } }

    // --- the tests ---

    @Test
    fun `clicking to a player whose name the display font lacks draws it from the fallback`() = withBackend { backend, fonts ->
        val alone = open(backend) {
            Text("玩家你好", Modifier.offset(40f, 30f), textStyle = style(family = "cjk"), colour = white)
        }.using { ink(frame(it, backend)) }

        open(backend) {
            var name by remember { mutableStateOf("Ace") }
            Column {
                Text(name, Modifier.offset(10f, 10f).testTag("name"), textStyle = style(), colour = white)
                Box(Modifier.offset(10f, 60f).size(80f, 30f).background(Colour.rgb(0x202020)).clickable { name = "玩家你好" }.testTag("next"))
            }
        }.using { ui ->
            val before = ink(frame(ui, backend), 0 until 50)
            assertFalse(alone == before, "Ace is not the Chinese name")

            ui.click("next")

            val after = ink(frame(ui, backend), 0 until 50)
            assertEquals(null, difference(alone, after), "the name should be exactly the fallback font's ink")
            assertEquals(fonts.measure("玩家你好", style()).size.width, ui.node("name").width)
        }
    }

    @Test
    fun `without a fallback the same name is not drawn in those shapes`() = withBackend { backend, fonts ->
        val alone = open(backend) {
            Text("玩家你好", Modifier.offset(10f, 10f), textStyle = style(family = "cjk"), colour = white)
        }.using { ink(frame(it, backend)) }
        fonts.fallBackTo(emptyList())

        val marks = open(backend) {
            Text("玩家你好", Modifier.offset(10f, 10f), textStyle = style(), colour = white)
        }.using { ink(frame(it, backend)) }

        assertFalse(alone == marks)
    }

    @Test
    fun `Korean from a second fallback is drawn in its own shapes`() = withBackend { backend, _ ->
        val alone = open(backend) {
            Text("안녕하세요", Modifier.offset(30f, 30f), textStyle = style(family = "korean"), colour = white)
        }.using { ink(frame(it, backend)) }
        val borrowed = open(backend) {
            Text("안녕하세요", Modifier.offset(10f, 10f), textStyle = style(), colour = white)
        }.using { ink(frame(it, backend)) }

        assertEquals(null, difference(alone, borrowed), "the Korean should be exactly the Korean font's ink")
    }

    @Test
    fun `an emoji keeps its own colours inside red text`() = withBackend { backend, fonts ->
        open(backend) {
            Text("GG $Emoji", Modifier.offset(10f, 10f).testTag("chat"), textStyle = style(), colour = red)
        }.using { ui ->
            val image = frame(ui, backend)
            val gg = fonts.measure("GG ", style()).size.width.toInt() + 10
            val node = ui.node("chat")
            val rows = 10 until 10 + node.height.toInt()

            // The face is yellow: green well above nothing. Red text tinting it would leave no green
            // at all, which is exactly what the letters have.
            val yellow = count(image, gg until 10 + node.width.toInt(), rows) { r, g, _ -> r > 180 && g > 140 }
            val greenInLetters = count(image, 10 until gg - 4, rows) { _, g, _ -> g > 40 }
            assertTrue(yellow > 40, "the emoji should be drawn in yellow, found $yellow yellow pixels")
            assertEquals(0, greenInLetters, "the letters are red")
        }
    }

    @Test
    fun `an emoji fades with its text`() = withBackend { backend, _ ->
        open(backend) {
            Text(Emoji, Modifier.offset(10f, 10f), textStyle = style(), colour = red.scaleAlpha(0.5f))
        }.using { ui ->
            val image = frame(ui, backend)
            val brightest = (10 until 50).maxOf { y -> (10 until 50).maxOf { x -> image[x, y].r } }
            assertTrue(brightest in 100..160, "a half-faded emoji is about half as bright, brightest red was $brightest")
        }
    }

    @Test
    fun `an outline rings the letters and leaves the emoji untouched`() = withBackend { backend, _ ->
        val ring = TextOutline(Colour.rgb(0x3060FF), width = 2f)
        val plain = open(backend) {
            Text(Emoji, Modifier.offset(10f, 10f), textStyle = style(), colour = white)
        }.using { frame(it, backend) }
        val outlined = open(backend) {
            Text(Emoji, Modifier.offset(10f, 10f), textStyle = style(), colour = white, outline = ring)
        }.using { frame(it, backend) }
        val letters = open(backend) {
            Text("G", Modifier.offset(10f, 10f), textStyle = style(), colour = white, outline = ring)
        }.using { frame(it, backend) }

        assertTrue(same(plain, outlined), "no copies of the picture smeared round it")
        val blue = count(letters) { r, _, b -> b > 200 && r < 100 }
        assertTrue(blue > 10, "a letter still gets its ring, found $blue ring pixels")
    }

    @Test
    fun `a typewriter with an effect and an outline leaves the emoji out of the ring too`() = withBackend { backend, _ ->
        // An effect draws a character at a time, so the ring copies come from the typewriter and not
        // from the canvas's own outlined run. They have to leave the picture out just the same.
        fun dialogue(outline: TextOutline?) = open(backend) {
            val line = rememberTypewriter(Emoji)
            ProvideTextOutline(outline) {
                Column {
                    Typewriter(line, Modifier.offset(10f, 10f), textStyle = style(), colour = white, effect = TypewriterEffect.fadeIn())
                    Box(Modifier.offset(10f, 60f).size(80f, 30f).background(Colour.rgb(0x202020)).clickable { line.skip() }.testTag("skip"))
                }
            }
        }.using { ui ->
            ui.click("skip")
            ui.advanceBy(1_000)
            frame(ui, backend)
        }

        val plain = dialogue(null)
        val outlined = dialogue(TextOutline(Colour.rgb(0x3060FF), width = 2f))

        assertTrue(inkBox(plain, 0 until 50).width > 10, "the emoji should have been typed out")
        assertTrue(same(plain, outlined), "no copies of the picture smeared round it")
    }

    @Test
    fun `a small atlas that fills up grows onto another page and still draws every glyph`() {
        val text = "你好世界玩家聊天欢迎来到游戏こんにちは"
        val roomy = withBackend { backend, _ ->
            open(backend) { Text(text, Modifier.offset(4f, 10f), textStyle = style(), colour = white) }.using { frame(it, backend) }
        }
        var pages = 0
        val cramped = withBackend(fonts(KorgeAtlas(pageSize = 128))) { backend, fonts ->
            open(backend) { Text(text, Modifier.offset(4f, 10f), textStyle = style(), colour = white) }.using { frame(it, backend) }
                .also { pages = fonts.atlas.pageCount }
        }

        assertTrue(pages > 1, "the small atlas should have needed another page, had $pages")
        assertTrue(same(roomy, cramped), "every glyph, wherever it was packed, draws the same")
    }

    @Test
    fun `mixed scripts, an emoji and outlined text through KorGE`() {
        val image = withBackend { backend, _ ->
            open(backend) {
                Column(Modifier.offset(12f, 12f), verticalArrangement = Arrangement.spacedBy(8f)) {
                    Text("Ace 玩家 $Emoji", textStyle = style(), colour = white)
                    Text("こんにちは 안녕하세요", textStyle = style(16f), colour = Colour.rgb(0x9FE0FF))
                    Text("GG 你好 $Emoji", textStyle = style(), colour = Colour.rgb(0xFFD060), outline = TextOutline(Colour.rgb(0x000000), width = 2f))
                    Box(Modifier.size(300f, 44f).background(Colour.rgb(0x3A6EA5))) {
                        Text("HULL 148 $Emoji", Modifier.offset(10f, 8f), textStyle = style(), colour = white, outline = TextOutline(Colour.rgb(0x101820), width = 2f))
                    }
                }
            }.using { frame(it, backend) }
        }

        val yellow = count(image) { r, g, b -> r > 200 && g > 150 && b < 80 }
        assertTrue(yellow > 100, "the emoji and the gold line are drawn, $yellow yellow pixels")

        val bytes = PNG.encode(image)
        File("build/screenshots/korge-fonts.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { dir ->
            File(dir, "korge-fonts.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        }
    }

    // --- the ring on the canvas itself ---

    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private fun draw(fonts: KorgeFonts, content: KorgeCanvas.() -> Unit): Bitmap32 {
        val canvas = KorgeCanvas(fonts.atlas)
        try {
            return KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.content()
                canvas.end()
            }
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `the canvas's ring copy leaves pictures out and keeps letters`() {
        val fonts = fonts()
        val layout = fonts.measure("H$Emoji", style())
        val ring = draw(fonts) { textRing(layout, 10f, 10f, white) }
        val whole = draw(fonts) { text(layout, 10f, 10f, white) }

        val h = fonts.measure("H", style()).size.width.toInt() + 10
        assertTrue(count(ring, 10 until h) { r, _, _ -> r > 200 } > 20, "the H is in the ring copy")
        assertEquals(0, count(ring, h until 80) { r, g, b -> r + g + b > 30 }, "the emoji is not")
        assertTrue(count(whole, h until 80) { r, g, _ -> r > 180 && g > 140 } > 40, "but a plain draw has it")
    }

    @Test
    fun `an outline is drawn under its letters, and fades out with them`() {
        val fonts = fonts()
        val layout = fonts.measure("HH", style(24f))
        val box = Rect.of(10f, 10f, layout.size.width, layout.size.height)
        val solid = draw(fonts) { text(layout, Offset(10f, 10f), white, TextOutline(Colour.rgb(0x0000FF), width = 2f)) }
        val gone = draw(fonts) { text(layout, Offset(10f, 10f), white.scaleAlpha(0f), TextOutline(Colour.rgb(0x0000FF), width = 2f)) }

        val area = box.left.toInt() - 4 until box.right.toInt() + 4
        val rows = box.top.toInt() - 4 until box.bottom.toInt() + 4
        val face = count(solid, area, rows) { r, g, b -> r > 240 && g > 240 && b > 240 }
        val ringPixels = count(solid, area, rows) { r, _, b -> b > 200 && r < 60 }
        assertTrue(face > 50, "the letters are white on top of their ring, $face white pixels")
        assertTrue(ringPixels > 20, "and the ring shows round them, $ringPixels blue pixels")

        // The ring takes the text's opacity: a number that has faded away leaves no silhouette behind.
        assertEquals(0, count(gone, area, rows) { r, g, b -> r + g + b > 0 }, "letters faded to nothing take their ring with them")
    }

    private companion object {
        val Sizes = listOf(16, 24)

        /** A character DejaVu Sans has no glyph for, so it comes from the pictures. */
        const val Emoji = "🥳"
    }
}
