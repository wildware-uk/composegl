package dev.wildware.composegl.render

import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Text drawn onto a screen bigger than the design is made again at the screen's own size. */
class SharpTextTest {

    /** Every face asked for, as `size@pixels`, and every glyph drawn, as `pixels:WxH`. */
    private class Log {
        val faces = ArrayList<String>()
        val drawn = ArrayList<String>()
    }

    /** A font that scales exactly: every measurement is proportional to the pixel size it is drawn at. */
    private class LinearRasteriser(val log: Log) : GlyphRasteriser {
        override fun face(family: String, size: Int): RasterFace = face(family, size, size)

        override fun face(family: String, size: Int, pixels: Int): RasterFace {
            log.faces += "$size@$pixels"
            return LinearFace(pixels, log)
        }
    }

    private class LinearFace(private val pixels: Int, private val log: Log) : RasterFace {
        override val ascent = pixels * 0.75f
        override val descent = pixels * 0.25f
        override val capHeight = pixels * 0.7f

        override fun has(codepoint: Int) = true

        override fun advance(codepoint: Int) = pixels * 0.625f

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            if (codepoint == ' '.code) {
                into.resize(0, 0, GlyphKind.Coverage)
                return true
            }
            into.resize(pixels / 2, pixels, GlyphKind.Coverage)
            into.pixels.fill(-1, 0, (pixels / 2) * pixels)
            into.xOffset = pixels / 8f
            into.yOffset = -ascent
            log.drawn += "$pixels:${pixels / 2}x$pixels"
            return true
        }
    }

    private class Fonts(val log: Log = Log()) : AtlasFonts(LinearRasteriser(log), atlasOwner = "Fonts") {
        init {
            registerFont("body", listOf(16))
        }
    }

    private val style = TextStyle(family = "body", size = 16f)

    private fun scaled(scale: Float) = Viewport(Size(200f, 100f), Size(200f * scale, 100f * scale), ScalePolicy.Fit)

    private val position = ShapeVertex.Attributes.single { it.name == "a_position" }.offset
    private val texCoord = ShapeVertex.Attributes.single { it.name == "a_texCoord0" }.offset

    /** Each glyph quad as left, top, right, bottom in design units, y still counted up from the bottom. */
    private fun boxes(draw: RecordingDevice.Draw, from: Int = 0) = (from until draw.quads).map { quad ->
        val topLeft = quad * 4 + 1
        val bottomRight = quad * 4 + 3
        listOf(draw.at(topLeft, position), draw.at(topLeft, position + 1), draw.at(bottomRight, position), draw.at(bottomRight, position + 1))
    }

    private fun drawAt(fonts: AtlasFonts, scale: Float, text: String = "Hi", device: RecordingDevice = RecordingDevice()): RecordingDevice {
        val canvas = RenderCanvas(device, fonts)
        canvas.begin(scaled(scale))
        canvas.text(fonts.measure(text, style), 10f, 20f, Colour.White)
        canvas.end()
        return device
    }

    @Test
    fun `at twice the size each glyph is made at twice the pixels`() {
        val fonts = Fonts()
        fonts.measure("Hi", style)
        assertEquals(listOf("16:8x16", "16:8x16"), fonts.log.drawn)

        val device = drawAt(fonts, 2f)

        assertTrue("16@32" in fonts.log.faces, "the font was asked for 16 drawn 32 tall: ${fonts.log.faces}")
        assertEquals(listOf("16:8x16", "16:8x16", "32:16x32", "32:16x32"), fonts.log.drawn)
        val draw = device.draws.single()
        val page = device.calls.first { it.startsWith("texture(") }.substringAfter(", ").substringBefore("x").toFloat()
        (0 until draw.quads).forEach { quad ->
            val wide = (draw.at(quad * 4 + 3, texCoord) - draw.at(quad * 4 + 1, texCoord)) * page
            val tall = (draw.at(quad * 4 + 3, texCoord + 1) - draw.at(quad * 4 + 1, texCoord + 1)) * page
            assertEquals(16f, wide, 0.001f, "glyph $quad samples 16 pixels across")
            assertEquals(32f, tall, 0.001f, "glyph $quad samples 32 pixels down")
        }
    }

    @Test
    fun `the glyphs keep the design-unit boxes they had at a scale of one`() {
        val atOne = boxes(drawAt(Fonts(), 1f).draws.single())
        val atTwo = boxes(drawAt(Fonts(), 2f).draws.single())

        assertEquals(atOne, atTwo)
    }

    @Test
    fun `measuring is the same before and after drawing at a scale of two`() {
        val fonts = Fonts()
        val before = fonts.measure("Hi there, wrapped", style.copy(maxLines = 2), 60f) as AtlasTextLayout
        drawAt(fonts, 2f, "Hi there, wrapped")
        val after = fonts.measure("Hi there, wrapped", style.copy(maxLines = 2), 60f) as AtlasTextLayout
        val fresh = Fonts().measure("Hi there, wrapped", style.copy(maxLines = 2), 60f) as AtlasTextLayout

        listOf(after, fresh).forEach { layout ->
            assertEquals(before.size, layout.size)
            assertEquals(before.lineCount, layout.lineCount)
            assertEquals(before.firstBaseline, layout.firstBaseline)
            assertEquals(before.placed.map { listOf(it.left, it.top, it.width, it.height) }, layout.placed.map { listOf(it.left, it.top, it.width, it.height) })
        }
        assertEquals(fonts.metrics(style), Fonts().metrics(style))
    }

    @Test
    fun `at a scale of one nothing is made again and text draws from the fonts' own page`() {
        val fonts = Fonts()
        val device = drawAt(fonts, 1f)

        assertEquals(listOf("16@16"), fonts.log.faces)
        assertEquals(1, device.calls.count { it.startsWith("texture(") })
        assertEquals(null, fonts.atlas.sharp?.atlas, "no atlas for copies was made")
    }

    @Test
    fun `a scale that animates makes a handful of sizes rather than one a frame`() {
        val fonts = Fonts()
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        val layout = fonts.measure("Hi", style)
        for (frame in 0..400) {
            canvas.begin(scaled(1f + frame / 200f))
            canvas.text(layout, 10f, 20f, Colour.White)
            canvas.end()
        }

        val sizes = fonts.log.faces.toSet()
        // 1.25, 1.5 ... 3: eight scaled sizes and the font's own.
        assertEquals(9, sizes.size, "$sizes")
        assertTrue(fonts.log.drawn.size <= 2 * 9, "each size's glyphs are made once: ${fonts.log.drawn.size}")
    }

    @Test
    fun `a full atlas of copies is emptied for a new scale rather than growing without end`() {
        val log = Log()
        val fonts = object : AtlasFonts(LinearRasteriser(log), atlasOwner = "Big") {
            init {
                registerFont("body", listOf(120))
            }
        }
        val big = TextStyle(family = "body", size = 120f)
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        val layout = fonts.measure("ABCDEFGHIJ", big)
        val sharp = checkNotNull(fonts.atlas.sharp)
        for (frame in 0 until 40) {
            canvas.begin(scaled(if (frame % 2 == 0) 3f else 3.5f))
            canvas.text(layout, 0f, 0f, Colour.White)
            canvas.end()
        }

        assertTrue(checkNotNull(sharp.atlas).pageCount <= 2, "the copies stay on two pages at most")
        assertTrue(sharp.generation > 0, "the atlas was emptied when the scale moved on")
        assertTrue(device.draws.isNotEmpty())
    }

    @Test
    fun `a picture is made from its source at the screen's size and fills the same box`() {
        val source = RgbaImage(64, 64, ByteArray(64 * 64 * 4) { -1 })
        fun draw(scale: Float): Pair<RecordingDevice, AtlasFonts> {
            val fonts = Fonts().apply {
                registerDecodedPictures("emoji", mapOf("😀" to source), listOf(16))
                fallBackTo(listOf("emoji"))
            }
            return drawAt(fonts, scale, "😀") to fonts
        }
        val (one, _) = draw(1f)
        val (two, fonts) = draw(2f)

        assertEquals(boxes(one.draws.single()), boxes(two.draws.single()))
        val glyph = (fonts.measure("😀", style) as AtlasTextLayout).placed.single().glyph
        val copy = checkNotNull(glyph.sharp(8))
        assertEquals(32f, copy.height)
        assertNotEquals(glyph.page, copy.page)
    }

    @Test
    fun `a panel and its label are still one draw call at a scale of two`() {
        val fonts = Fonts()
        val device = RecordingDevice()
        val canvas = RenderCanvas(device, fonts)
        val layout = fonts.measure("Hi", style)
        repeat(2) {
            device.draws.clear()
            canvas.begin(scaled(2f))
            canvas.rect(Rect.of(0f, 0f, 50f, 30f), Colour.Blue)
            canvas.text(layout, 10f, 20f, Colour.White)
            canvas.rect(Rect.of(0f, 40f, 50f, 30f), Colour.Blue)
            canvas.end()
        }

        assertEquals(1, device.draws.size, "after the first frame, one texture holds the white block and the copies")
    }
}
