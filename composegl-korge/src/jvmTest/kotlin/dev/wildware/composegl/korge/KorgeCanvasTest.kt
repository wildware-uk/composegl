package dev.wildware.composegl.korge

import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.ui.backend.FakeTexture
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.text.TextStyle
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/**
 * The parts of the KorGE canvas only a GPU can answer for: what came out in the pixels, and how many
 * times the batch talked to the driver. The same questions the LibGDX canvas is asked, with the same
 * numbers, so the two backends are held to one picture.
 */
class KorgeCanvasTest {

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val white = Colour.rgb(0xFFFFFF)

    /**
     * Skips before an `assertThrows`, which would otherwise catch the skip [KorgeGl.render] throws
     * with no GL and report it as the wrong exception.
     */
    private fun assumeGl() =
        org.junit.jupiter.api.Assumptions.assumeTrue(KorgeGl.available, "no display and KORGE_HEADLESS is not set")

    /** What one drawn frame came out as. */
    private class Frame(val pixels: Bitmap32, val drawCalls: Int)

    /** Draws [content] through a fresh canvas into an offscreen picture over black, and reads it back. */
    private fun draw(
        on: Viewport = viewport,
        atlas: GlyphAtlas? = null,
        content: KorgeCanvas.(RenderContext) -> Unit,
    ): Frame {
        var calls = -1
        val canvas = KorgeCanvas(atlas)
        try {
            val pixels = KorgeGl.picture { ctx ->
                canvas.begin(on, ctx)
                canvas.content(ctx)
                canvas.end()
                calls = canvas.drawCalls
            }
            return Frame(pixels, calls)
        } finally {
            canvas.close()
        }
    }

    // --- where things land ---

    @Test
    fun `a rectangle lands where the toolkit said, with y down from the top`() {
        val frame = draw { rect(Rect.of(10f, 20f, 100f, 50f), red) }

        assertColour(Red, frame.pixels.at(50, 30), "inside the box")
        assertColour(Black, frame.pixels.at(50, 10), "20 down should still be background")
        assertColour(Black, frame.pixels.at(50, 80), "70 down is past the bottom")
        assertColour(Black, frame.pixels.at(5, 30), "left of the box")
    }

    @Test
    fun `a letterboxed design is centred and scaled in the pixels`() {
        // A 200 by 100 design fitted into 400 square is scaled by two, with 100 pixels of bar above.
        val letterbox = Viewport(design = Size(200f, 100f), physical = Size(size, size), policy = ScalePolicy.Fit)
        val frame = draw(letterbox) { rect(Rect.of(0f, 0f, 50f, 50f), red) }

        assertColour(Red, frame.pixels.at(50, 150), "design (25, 25) is pixel (50, 150)")
        assertColour(Black, frame.pixels.at(50, 90), "the bar above the design")
        assertColour(Black, frame.pixels.at(150, 150), "design x 75 is past the box")
    }

    @Test
    fun `nothing is drawn outside the viewport's area even when the design overflows`() {
        val half = Viewport(design = Size(size, size), physical = Size(size, size), area = Rect.of(0f, 0f, 200f, 400f), policy = ScalePolicy.Stretch)
        val frame = draw(half) { rect(Rect.of(0f, 0f, size, size), red) }

        assertColour(Red, frame.pixels.at(100, 100), "inside the area")
        assertColour(Black, frame.pixels.at(300, 100), "past the area's right edge")
    }

    // --- rounded boxes ---

    @Test
    fun `a rounded corner is cut away`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 30f) }

        assertColour(Black, frame.pixels.at(52, 52), "the corner should be cut away")
        assertColour(Red, frame.pixels.at(100, 100), "the middle should be filled")
        assertColour(Red, frame.pixels.at(100, 52), "the flat top should be filled")
        assertColour(Black, frame.pixels.at(147, 147), "the bottom-right is cut the same")
    }

    @Test
    fun `a rounded corner is not a staircase`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 30f) }

        val partial = (0..30).count { step ->
            val shade = frame.pixels.at(50 + step, 50 + step).r
            shade > 0.05f && shade < 0.95f
        }
        assertTrue(partial > 0, "no softened pixels anywhere along the corner")
    }

    @Test
    fun `a corner is still soft over about one pixel when the interface is scaled up`() {
        val scaled = Viewport(design = Size(size / 2f, size / 2f), physical = Size(size, size), policy = ScalePolicy.Fit)
        val frame = draw(scaled) { rect(Rect.of(25f, 25f, 50f, 50f), red, corner = 15f) }

        val partial = (0..60).count { step ->
            val shade = frame.pixels.at(50 + step, 50 + step).r
            shade > 0.05f && shade < 0.95f
        }
        assertTrue(partial in 1..4, "softened over $partial pixels; expected about one")
    }

    @Test
    fun `a square corner stays square`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 0f) }

        assertColour(Red, frame.pixels.at(51, 51), "a zero radius should fill its corner")
        assertColour(Black, frame.pixels.at(48, 48))
    }

    @Test
    fun `a corner radius is held to half the box`() {
        // Asking for 500 on a 100 box is a circle, not a box with nothing left in it.
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, corner = 500f) }

        assertColour(Red, frame.pixels.at(100, 100), "the middle of the circle")
        assertColour(Red, frame.pixels.at(100, 55), "the top of the circle")
        assertColour(Black, frame.pixels.at(58, 58), "the corner of the square is outside the circle")
    }

    @Test
    fun `a border is a ring, not a filled box`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f) }

        assertColour(Blue, frame.pixels.at(52, 100), "the left edge should be drawn")
        assertColour(Blue, frame.pixels.at(100, 147), "the bottom edge should be drawn")
        assertColour(Black, frame.pixels.at(100, 100), "the middle should be empty")
        assertColour(Black, frame.pixels.at(60, 100), "past the border's width is empty")
    }

    @Test
    fun `a rounded border follows its corner`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f, corner = 30f) }

        assertColour(Black, frame.pixels.at(52, 52), "the corner is cut away from the ring too")
        assertColour(Blue, frame.pixels.at(52, 100), "the straight left edge is drawn")
    }

    @Test
    fun `a border is drawn over the fill of the same box`() {
        val frame = draw {
            rect(Rect.of(50f, 50f, 100f, 100f), red)
            border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f)
        }

        assertColour(Blue, frame.pixels.at(52, 100), "the border")
        assertColour(Red, frame.pixels.at(100, 100), "the fill inside it")
    }

    @Test
    fun `a shadow reaches beyond the box and fades`() {
        val lit = draw {
            rect(Rect.of(0f, 0f, size, size), white)
            shadow(Rect.of(100f, 100f, 100f, 100f), Colour.argb(0xFF000000), spread = 20f)
        }

        val near = lit.pixels.at(96, 150).r
        val far = lit.pixels.at(85, 150).r
        val beyond = lit.pixels.at(70, 150).r
        assertTrue(near < 0.9f, "the shadow should darken just outside the box, got $near")
        assertTrue(far > near, "the shadow should fade with distance: $near then $far")
        assertTrue(beyond > 0.97f, "past its spread the shadow is gone, got $beyond")
    }

    @Test
    fun `a panel with a corner, a border and a shadow is one draw call`() {
        val frame = draw {
            val panel = Rect.of(100f, 100f, 200f, 120f)
            shadow(panel, Colour.argb(0x80000000), spread = 12f, corner = 10f)
            rect(panel, blue, corner = 10f)
            border(panel, red, width = 2f, corner = 10f)
        }

        assertEquals(1, frame.drawCalls)
    }

    @Test
    fun `a hundred boxes with different corners cost one draw call`() {
        val frame = draw {
            repeat(100) { index ->
                rect(Rect.of((index % 10) * 40f, (index / 10) * 40f, 36f, 36f), if (index % 2 == 0) red else blue, corner = index % 7f)
            }
        }

        assertEquals(1, frame.drawCalls)
        assertColour(Red, frame.pixels.at(18, 18), "the first box")
        assertColour(Blue, frame.pixels.at(58, 18), "the second box")
    }

    // --- fans ---

    @Test
    fun `a fan fills the shape it describes`() {
        val frame = draw { fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), red) }

        assertColour(Red, frame.pixels.at(60, 40), "above the diagonal, inside the fan")
        assertColour(Red, frame.pixels.at(160, 180), "and below it, in the fan's second triangle")
        assertColour(Black, frame.pixels.at(300, 40), "outside it, where nothing was drawn")
    }

    @Test
    fun `a triangle fills only its own half`() {
        val frame = draw { fan(floatArrayOf(0f, 0f, 200f, 0f, 0f, 200f), red) }

        assertColour(Red, frame.pixels.at(40, 40), "inside the triangle")
        assertColour(Black, frame.pixels.at(160, 160), "the other side of its long edge")
    }

    @Test
    fun `a fan is drawn at its colour's own opacity`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 200f, 200f), red)
            fan(floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f), blue.scaleAlpha(0.5f))
        }

        assertColour(Rgb(0.5f, 0f, 0.5f), frame.pixels.at(60, 40), "half of each is what half opacity means")
    }

    @Test
    fun `a fan obeys the clip`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            fan(floatArrayOf(0f, 0f, 400f, 0f, 400f, 400f, 0f, 400f), red)
            popClip()
        }

        assertColour(Red, frame.pixels.at(25, 10))
        assertColour(Black, frame.pixels.at(200, 10), "the clip did not hold")
    }

    @Test
    fun `a circle is round`() {
        val frame = draw { circle(Offset(200f, 200f), 100f, red) }

        assertColour(Red, frame.pixels.at(200, 200), "the centre")
        assertColour(Red, frame.pixels.at(200, 105), "just inside the top")
        assertColour(Black, frame.pixels.at(120, 120), "the corner of the bounding square is outside")
    }

    // --- clips ---

    @Test
    fun `a clip stops drawing outside it`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            rect(Rect.of(0f, 0f, size, size), red)
            popClip()
        }

        assertColour(Red, frame.pixels.at(25, 25))
        assertColour(Black, frame.pixels.at(100, 100), "the clip did not hold")
        assertColour(Black, frame.pixels.at(25, 60), "the clip's bottom edge is where the toolkit said, not flipped")
    }

    @Test
    fun `nested clips intersect`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 200f, 200f))
            pushClip(Rect.of(100f, 100f, 200f, 200f))
            rect(Rect.of(0f, 0f, size, size), red)
            popClip()
            popClip()
        }

        assertColour(Red, frame.pixels.at(150, 150), "the overlap should be drawn")
        assertColour(Black, frame.pixels.at(50, 50), "outside the inner clip")
        assertColour(Black, frame.pixels.at(250, 250), "outside the outer clip")
    }

    @Test
    fun `popping an inner clip goes back to the outer one, not to none`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 200f, 200f))
            pushClip(Rect.of(100f, 100f, 50f, 50f))
            popClip()
            rect(Rect.of(0f, 0f, size, size), red)
            popClip()
        }

        assertColour(Red, frame.pixels.at(50, 50), "inside the outer clip")
        assertColour(Black, frame.pixels.at(300, 300), "outside the outer clip")
    }

    @Test
    fun `a clip is lifted when it is popped`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            popClip()
            rect(Rect.of(100f, 100f, 50f, 50f), blue)
        }

        assertColour(Blue, frame.pixels.at(120, 120))
    }

    @Test
    fun `a clip in a scaled design cuts at the scaled edge`() {
        val scaled = Viewport(design = Size(size / 2f, size / 2f), physical = Size(size, size), policy = ScalePolicy.Fit)
        val frame = draw(scaled) {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            rect(Rect.of(0f, 0f, 200f, 200f), red)
            popClip()
        }

        assertColour(Red, frame.pixels.at(90, 90), "design 45 is pixel 90, inside")
        assertColour(Black, frame.pixels.at(110, 110), "design 55 is pixel 110, outside")
    }

    @Test
    fun `a clip with nothing in it draws nothing`() {
        val frame = draw {
            pushClip(Rect.of(0f, 0f, 50f, 50f))
            pushClip(Rect.of(100f, 100f, 50f, 50f))
            rect(Rect.of(0f, 0f, size, size), red)
            popClip()
            popClip()
        }

        assertColour(Black, frame.pixels.at(25, 25))
        assertColour(Black, frame.pixels.at(120, 120))
    }

    // --- opacity ---

    @Test
    fun `opacity multiplies down the tree`() {
        val frame = draw {
            pushAlpha(0.5f)
            pushAlpha(0.5f)
            rect(Rect.of(0f, 0f, 100f, 100f), red)
            popAlpha()
            popAlpha()
        }

        val drawn = frame.pixels.at(50, 50)
        assertTrue(drawn.r in 0.2f..0.3f, "expected about a quarter red, got ${drawn.r}")
    }

    @Test
    fun `popping an opacity brings back the one underneath`() {
        val frame = draw {
            pushAlpha(0.5f)
            pushAlpha(0.2f)
            popAlpha()
            rect(Rect.of(0f, 0f, 100f, 100f), red)
            popAlpha()
            rect(Rect.of(200f, 0f, 100f, 100f), red)
        }

        assertTrue(frame.pixels.at(50, 50).r in 0.45f..0.55f, "half, got ${frame.pixels.at(50, 50).r}")
        assertColour(Red, frame.pixels.at(250, 50), "full once both are popped")
    }

    @Test
    fun `opacity fades text, borders and shadows as well as fills`() {
        val fonts = testFonts()
        val layout = fonts.measure("HHHH", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = fonts.atlas) {
            pushAlpha(0f)
            rect(Rect.of(0f, 0f, 100f, 100f), red)
            border(Rect.of(0f, 0f, 100f, 100f), red, 10f)
            shadow(Rect.of(150f, 150f, 50f, 50f), white, 20f)
            text(layout, 20f, 250f, red)
            popAlpha()
        }

        val lit = (0 until KorgeGl.size).sumOf { y -> (0 until KorgeGl.size).count { x -> frame.pixels.at(x, y).r > 0.02f } }
        assertEquals(0, lit, "nothing should show through an opacity of zero")
    }

    // --- pictures ---

    private fun twoRows(): Bitmap32 = Bitmap32(1, 2, premultiplied = false).also {
        it[0, 0] = RGBA(255, 0, 0, 255)
        it[0, 1] = RGBA(0, 0, 255, 255)
    }

    @Test
    fun `a picture is drawn where the toolkit said, the right way up`() {
        val frame = draw { image(KorgeTexture(twoRows()), Rect.of(10f, 10f, 40f, 40f)) }

        assertColour(Red, frame.pixels.at(30, 15), "the picture's first row belongs at the top")
        assertColour(Blue, frame.pixels.at(30, 45), "and its last row at the bottom")
        assertColour(Black, frame.pixels.at(60, 60), "nothing past it")
    }

    @Test
    fun `a source rectangle picks part of the picture`() {
        val frame = draw { image(KorgeTexture(twoRows()), Rect.of(10f, 10f, 40f, 40f), source = Rect.of(0f, 1f, 1f, 1f)) }

        assertColour(Blue, frame.pixels.at(30, 15), "only the blue row, stretched over the whole box")
        assertColour(Blue, frame.pixels.at(30, 45))
    }

    @Test
    fun `a tint multiplies the picture`() {
        val whitePicture = Bitmap32(2, 2, premultiplied = false).also { bitmap ->
            for (y in 0..1) for (x in 0..1) bitmap[x, y] = RGBA(255, 255, 255, 255)
        }
        val frame = draw { image(KorgeTexture(whitePicture), Rect.of(10f, 10f, 40f, 40f), tint = Colour.rgb(0x00FF00)) }

        assertColour(Green, frame.pixels.at(30, 30))
    }

    @Test
    fun `a premultiplied picture at half opacity blends like a straight one`() {
        val straight = Bitmap32(1, 1, premultiplied = false).also { it[0, 0] = RGBA(255, 255, 255, 128) }
        val premultiplied = Bitmap32(1, 1, premultiplied = true).also { it.setRgbaRaw(0, 0, RGBA(128, 128, 128, 128)) }
        val frame = draw {
            image(KorgeTexture(straight), Rect.of(0f, 0f, 100f, 100f))
            image(KorgeTexture(premultiplied), Rect.of(200f, 0f, 100f, 100f))
        }

        val a = frame.pixels.at(50, 50).r
        val b = frame.pixels.at(250, 50).r
        assertTrue(a in 0.45f..0.55f, "straight half-white over black is about half, got $a")
        assertTrue(abs(a - b) < 0.03f, "and so is premultiplied half-white: $a against $b")
    }

    @Test
    fun `a texture from another backend is refused by name`() {
        assumeGl()
        val failure = assertThrows<IllegalStateException> {
            draw { image(FakeTexture(4, 4), Rect.of(0f, 0f, 4f, 4f)) }
        }
        assertTrue("FakeTexture" in failure.message.orEmpty(), failure.message)
    }

    // --- text ---

    private fun testFonts() = KorgeFonts().also {
        it.registerTrueType("body", TestFonts.dejaVu(), listOf(48))
    }

    @Test
    fun `text is drawn where the toolkit said, growing downwards`() {
        val fonts = testFonts()
        val layout = fonts.measure("HHHH", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = fonts.atlas) { text(layout, Offset(20f, 20f), red) }

        val lit = (20 until 90).sumOf { y -> (20 until 200).count { x -> frame.pixels.at(x, y).r > 0.5f } }
        assertTrue(lit > 100, "expected letters below the top-left corner, lit $lit pixels")
        assertColour(Black, frame.pixels.at(200, 300), "nothing should be drawn down there")
        val above = (0 until 18).sumOf { y -> (0 until KorgeGl.size).count { x -> frame.pixels.at(x, y).r > 0.5f } }
        assertEquals(0, above, "nothing above the top of the layout")
    }

    @Test
    fun `the baseline is where the layout said it is`() {
        val fonts = testFonts()
        val layout = fonts.measure("HHHH", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = fonts.atlas) { text(layout, Offset(20f, 20f), red) }

        val bottom = (20..200).last { y -> (20 until 250).any { x -> frame.pixels.at(x, y).r > 0.5f } }
        val promised = 20f + layout.firstBaseline
        assertTrue(abs(bottom - promised) <= 2f, "the letters end at $bottom but the layout promised a baseline at $promised")
        val top = (20..200).first { y -> (20 until 250).any { x -> frame.pixels.at(x, y).r > 0.5f } }
        val capHeight = fonts.metrics(TextStyle(family = "body", size = 48f)).capHeight
        assertTrue(abs((bottom - top) - capHeight) <= 2f, "a capital H is ${bottom - top} tall; the metrics said $capHeight")
    }

    @Test
    fun `text is as wide as it was measured`() {
        val fonts = testFonts()
        val layout = fonts.measure("HHHH", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = fonts.atlas) { text(layout, Offset(20f, 20f), red) }

        val right = (20 until KorgeGl.size).last { x -> (20 until 100).any { y -> frame.pixels.at(x, y).r > 0.5f } }
        val promised = 20f + layout.size.width
        // The last H's right stem stops short of its advance by its side bearing, a few pixels at 48.
        assertTrue(right <= promised && right > promised - 8f, "ink ends at $right; the layout is $promised wide")
    }

    @Test
    fun `a label on a panel is one draw call when they share the atlas`() {
        val fonts = testFonts()
        val layout = fonts.measure("Play", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = fonts.atlas) {
            rect(Rect.of(10f, 10f, 300f, 100f), blue, corner = 8f)
            text(layout, 20f, 20f, white)
            border(Rect.of(10f, 10f, 300f, 100f), red, 2f, corner = 8f)
        }

        assertEquals(1, frame.drawCalls)
    }

    @Test
    fun `without the shared atlas a label on a panel costs a texture change`() {
        val fonts = testFonts()
        val layout = fonts.measure("Play", TextStyle(family = "body", size = 48f))
        val frame = draw(atlas = null) {
            rect(Rect.of(10f, 10f, 300f, 100f), blue)
            text(layout, 20f, 20f, white)
        }

        assertEquals(2, frame.drawCalls, "the proof that sharing the atlas is what makes it one")
    }

    @Test
    fun `a glyph first seen after the atlas was drawn still reaches the screen`() {
        val fonts = testFonts()
        val style = TextStyle(family = "body", size = 48f)
        draw(atlas = fonts.atlas) { text(fonts.measure("H", style), 20f, 20f, red) }

        // "W" was never measured before that frame, so its glyph is packed into a page already on the GPU.
        val later = fonts.measure("WWW", style)
        val frame = draw(atlas = fonts.atlas) { text(later, 20f, 20f, red) }

        val lit = (20 until 90).sumOf { y -> (20 until 200).count { x -> frame.pixels.at(x, y).r > 0.5f } }
        assertTrue(lit > 100, "the new glyphs were not uploaded; lit $lit pixels")
    }

    @Test
    fun `text measured by another backend is refused by name`() {
        assumeGl()
        val headless = dev.wildware.composegl.ui.backend.MonospaceFontProvider().measure("hi", TextStyle.Default)
        val failure = assertThrows<IllegalStateException> { draw { text(headless, 0f, 0f, red) } }
        assertTrue("KorgeFonts" in failure.message.orEmpty(), failure.message)
    }

    // --- the frame ---

    @Test
    fun `raw hands over the render context, after the interface's own quads`() {
        var handed: Any? = null
        var context: RenderContext? = null
        var callsBeforeRaw = -1
        val frame = draw { ctx ->
            context = ctx
            rect(Rect.of(0f, 0f, 100f, 100f), red)
            raw { handed = it; callsBeforeRaw = drawCalls }
            rect(Rect.of(200f, 0f, 100f, 100f), blue)
        }

        assertSame(context, handed)
        assertEquals(1, callsBeforeRaw, "the box before raw was on the screen before the block ran")
        assertColour(Red, frame.pixels.at(50, 50))
        assertColour(Blue, frame.pixels.at(250, 50), "the frame carries on normally after raw")
        assertTrue(KorgeCanvas().handsOverRaw)
    }

    @Test
    fun `a frame with nothing in it costs no draw calls`() {
        assertEquals(0, draw { }.drawCalls)
    }

    @Test
    fun `an unbalanced clip is caught at the end of the frame`() {
        assumeGl()
        val failure = assertThrows<IllegalStateException> { draw { pushClip(Rect.of(0f, 0f, 10f, 10f)) } }
        assertTrue("never popped" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `beginning twice is refused`() {
        assumeGl()
        assertThrows<IllegalStateException> { draw { ctx -> begin(viewport, ctx) } }
    }

    @Test
    fun `the plain begin says what it needs when there is no render context`() {
        val failure = assertThrows<IllegalStateException> { KorgeCanvas().begin(viewport) }
        assertTrue("renderContext" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `warming up builds the batch without drawing anything`() {
        val canvas = KorgeCanvas()
        assertFalse(canvas.warmedUp)
        canvas.warmUp()
        assertTrue(canvas.warmedUp)
        assertEquals(0, canvas.drawCalls)
        canvas.close()
    }
}
