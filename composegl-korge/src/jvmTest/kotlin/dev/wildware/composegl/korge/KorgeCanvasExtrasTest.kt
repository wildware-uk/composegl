package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Brush
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.image.format.PNG
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Gradients, a radius per corner, turned pictures, blend modes and tints, through KorGE — the LibGDX
 * canvas's own pixel tests with the same numbers, so the two backends are held to one picture.
 */
class KorgeCanvasExtrasTest {

    private val size = KorgeGl.size.toFloat()
    private val viewport = Viewport(design = Size(size, size), physical = Size(size, size), policy = ScalePolicy.Fit)

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    private class Frame(val pixels: Bitmap32, val drawCalls: Int)

    private fun draw(content: KorgeCanvas.() -> Unit): Frame {
        var calls = -1
        val canvas = KorgeCanvas()
        try {
            val pixels = KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.content()
                canvas.end()
                calls = canvas.drawCalls
            }
            return Frame(pixels, calls)
        } finally {
            canvas.close()
        }
    }

    /** A one by two picture: a red row on top of a blue one. */
    private fun twoRows() = KorgeTexture(
        Bitmap32(1, 2, premultiplied = false).also {
            it[0, 0] = RGBA(255, 0, 0, 255)
            it[0, 1] = RGBA(0, 0, 255, 255)
        },
    )

    // --- what it says it can do, which needs no GL ---

    @Test
    fun `it says it draws gradients, rounds corners separately, turns pictures, blends and tints`() {
        val canvas = KorgeCanvas()
        assertTrue(canvas.drawsGradients)
        assertTrue(canvas.roundsCornersSeparately)
        assertTrue(canvas.rotatesImages)
        assertTrue(canvas.supports(BlendMode.Additive))
        assertTrue(canvas.supports(BlendMode.SourceOver))
        assertTrue(canvas.tints)
        // Pushing and popping outside a frame builds nothing.
        canvas.pushBlend(BlendMode.Additive)
        canvas.popBlend()
        canvas.pushTint(Colour.Grey)
        canvas.popTint()
        assertTrue(!canvas.warmedUp, "a blend pushed outside a frame should not build the batch")
        canvas.close()
    }

    // --- corners ---

    @Test
    fun `each corner is cut by its own radius`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners(topLeft = 30f)) }

        assertColour(Black, frame.pixels.at(52, 52), "the top-left should be cut away")
        assertColour(Red, frame.pixels.at(147, 52), "the top-right should be square")
        assertColour(Red, frame.pixels.at(147, 147), "the bottom-right should be square")
        assertColour(Red, frame.pixels.at(52, 147), "the bottom-left should be square")
    }

    @Test
    fun `top and bottom are the screen's`() {
        val frame = draw { rect(Rect.of(50f, 50f, 100f, 100f), red, Corners.top(30f)) }

        assertColour(Black, frame.pixels.at(52, 52), "top-left cut")
        assertColour(Black, frame.pixels.at(147, 52), "top-right cut")
        assertColour(Red, frame.pixels.at(52, 147), "bottom-left square")
        assertColour(Red, frame.pixels.at(147, 147), "bottom-right square")
    }

    @Test
    fun `a border follows the corners it is given`() {
        val frame = draw { border(Rect.of(50f, 50f, 100f, 100f), blue, width = 6f, corners = Corners.right(30f)) }

        assertColour(Blue, frame.pixels.at(52, 52), "the square top-left is part of the ring")
        assertColour(Black, frame.pixels.at(147, 52), "the rounded top-right is cut away")
        assertColour(Black, frame.pixels.at(100, 100), "and the middle is still empty")
    }

    @Test
    fun `a shadow follows the corners it is given`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, size, size), Colour.White)
            shadow(Rect.of(100f, 100f, 100f, 100f), Colour.argb(0xFF000000), spread = 20f, corners = Corners(topLeft = 50f))
        }

        // Just outside the top-left corner, diagonally: a square corner's shadow is darkest there, a
        // round one has already fallen away.
        val roundCorner = frame.pixels.at(97, 97).r
        val squareCorner = frame.pixels.at(202, 97).r
        assertTrue(roundCorner > squareCorner + 0.2f, "the rounded corner's shadow is lighter: $roundCorner against $squareCorner")
    }

    @Test
    fun `a tab and a plain panel beside it are one draw call`() {
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 24f), red, Corners.top(8f))
            rect(Rect.of(10f, 34f, 200f, 80f), blue, corner = 4f)
            rect(Rect.of(80f, 10f, 60f, 24f), red, Corners(3f, 9f, 0f, 1f))
        }

        assertEquals(1, frame.drawCalls)
    }

    // --- gradients ---

    private val box = Rect.of(100f, 100f, 200f, 160f)

    /** What [brush] should paint at the middle of the pixel at [x], [y], over [over]. */
    private fun expected(brush: Brush, x: Int, y: Int, over: Colour = Colour.Black): Rgb {
        val painted = brush.colourAt(x + 0.5f, y + 0.5f, box)
        val t = painted.alphaFraction
        fun channel(under: Int, top: Int) = (under + (top - under) * t) / 255f
        return Rgb(channel(over.red, painted.red), channel(over.green, painted.green), channel(over.blue, painted.blue))
    }

    @Test
    fun `a vertical gradient runs from its first colour at the top to its last at the bottom`() {
        val brush = Brush.vertical(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(Red, frame.pixels.at(200, 101), "the top edge")
        assertColour(Blue, frame.pixels.at(200, 258), "the bottom edge")
        assertColour(expected(brush, 200, 180), frame.pixels.at(200, 180), "halfway down")
        assertColour(frame.pixels.at(110, 150), frame.pixels.at(290, 150), "nothing changes across")
        assertColour(Black, frame.pixels.at(200, 96), "nothing above the box")
    }

    @Test
    fun `a horizontal gradient runs left to right`() {
        val brush = Brush.horizontal(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(Red, frame.pixels.at(101, 180), "the left edge")
        assertColour(Blue, frame.pixels.at(298, 180), "the right edge")
        assertColour(expected(brush, 150, 180), frame.pixels.at(150, 180), "a quarter across")
    }

    @Test
    fun `an angled gradient turns clockwise and meets the corners`() {
        val brush = Brush.linear(red, blue, degrees = 45f)
        val frame = draw { rect(box, brush) }

        assertColour(Red, frame.pixels.at(101, 101), "top-left is the start")
        assertColour(Blue, frame.pixels.at(298, 258), "bottom-right is the end")
        assertColour(expected(brush, 298, 101), frame.pixels.at(298, 101), "top-right is halfway")
    }

    @Test
    fun `a radial gradient is its centre colour in the middle and its edge colour at the sides`() {
        val brush = Brush.radial(red, blue)
        val frame = draw { rect(box, brush) }

        assertColour(Red, frame.pixels.at(200, 180), "the middle")
        assertColour(Blue, frame.pixels.at(101, 180), "the left side")
        assertColour(Blue, frame.pixels.at(102, 102), "a corner stays at the edge colour")
        assertColour(expected(brush, 250, 180), frame.pixels.at(250, 180), "halfway out")
    }

    @Test
    fun `a gradient to transparent fades without going dark`() {
        val brush = Brush.vertical(red, Colour.Transparent)
        val frame = draw {
            rect(Rect.of(0f, 0f, size, size), Colour.White)
            rect(box, brush)
        }

        val pixel = frame.pixels.at(200, 180)
        assertColour(expected(brush, 200, 180, over = Colour.White), pixel, "halfway down")
        assertTrue(pixel.r > 0.95f, "red stays full over white while it fades, got $pixel")
    }

    @Test
    fun `a gradient is cut by its corner like any other box`() {
        val frame = draw { rect(box, Brush.vertical(red, blue), corner = 40f) }

        assertColour(Black, frame.pixels.at(102, 102), "the corner is cut away")
        assertColour(Red, frame.pixels.at(200, 101), "the flat top is filled")
    }

    @Test
    fun `a gradient is cut by each corner's own radius with top still the screen's top`() {
        val brush = Brush.vertical(red, blue)
        val frame = draw { rect(box, brush, Corners.top(40f)) }

        assertColour(Black, frame.pixels.at(102, 102), "top-left cut")
        assertColour(Black, frame.pixels.at(297, 102), "top-right cut")
        assertColour(expected(brush, 102, 257), frame.pixels.at(102, 257), "bottom-left square, and still the gradient")
        assertColour(expected(brush, 297, 257), frame.pixels.at(297, 257), "bottom-right square")
        assertColour(Red, frame.pixels.at(200, 101), "the flat top is filled")
    }

    @Test
    fun `a gradient fades with the opacity in force`() {
        val frame = draw {
            pushAlpha(0.5f)
            rect(box, Brush.vertical(red, red))
            popAlpha()
        }

        assertColour(Rgb(0.5f, 0f, 0f), frame.pixels.at(200, 180), "half of red over black")
    }

    @Test
    fun `a gradient takes the tint in force like a flat box does`() {
        val frame = draw {
            pushTint(Colour.rgb(0x808080))
            rect(box, Brush.vertical(Colour.White, Colour.White))
            popTint()
        }

        assertColour(Rgb(0x80 / 255f, 0x80 / 255f, 0x80 / 255f), frame.pixels.at(200, 180), "white through grey")
    }

    @Test
    fun `a gradient panel batches with the flat boxes round it`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, size, size), Colour.White)
            rect(box, Brush.radial(red, blue), corner = 10f)
            border(box, Colour.Black, width = 6f, corner = 10f)
            rect(Rect.of(10f, 10f, 20f, 20f), red)
        }

        assertEquals(1, frame.drawCalls)
        assertColour(Black, frame.pixels.at(200, 102), "the border after it still draws as a border")
        assertColour(Red, frame.pixels.at(20, 20), "and a flat box after that is still flat")
    }

    // --- turned pictures ---

    @Test
    fun `half a turn puts the top of the picture at the bottom`() {
        val frame = draw { image(twoRows(), Rect.of(10f, 10f, 40f, 40f), degrees = 180f) }

        assertColour(Blue, frame.pixels.at(30, 15), "half a turn puts the last row on top")
        assertColour(Red, frame.pixels.at(30, 45), "and the first row at the bottom")
    }

    @Test
    fun `a quarter turn clockwise sends the top of the picture to the right`() {
        val frame = draw { image(twoRows(), Rect.of(10f, 10f, 40f, 40f), degrees = 90f) }

        assertColour(Red, frame.pixels.at(45, 30), "the picture's first row ends up on the right")
        assertColour(Blue, frame.pixels.at(15, 30), "and its last row on the left")
    }

    @Test
    fun `a turn goes round the pivot it is given`() {
        // Half a turn about the top-left corner lands the picture up and to the left of where it was.
        val frame = draw { image(twoRows(), Rect.of(100f, 100f, 40f, 40f), degrees = 180f, pivotX = 0f, pivotY = 0f) }

        assertColour(Blue, frame.pixels.at(80, 65), "the last row, now above the corner")
        assertColour(Red, frame.pixels.at(80, 95), "the first row, just above the corner")
        assertColour(Black, frame.pixels.at(120, 120), "nothing where the picture was")
    }

    @Test
    fun `a turned picture fades with the opacity in force`() {
        val frame = draw {
            pushAlpha(0.5f)
            image(twoRows(), Rect.of(10f, 10f, 40f, 40f), degrees = 180f)
            popAlpha()
        }

        assertColour(Rgb(0f, 0f, 0.5f), frame.pixels.at(30, 15), "half a blue row over black")
        assertColour(Rgb(0.5f, 0f, 0f), frame.pixels.at(30, 45), "and half a red one")
    }

    @Test
    fun `part of a picture can be turned`() {
        val frame = draw { image(twoRows(), Rect.of(10f, 10f, 40f, 40f), degrees = 90f, source = Rect.of(0f, 0f, 1f, 1f)) }

        assertColour(Red, frame.pixels.at(20, 30), "the red row fills it")
        assertColour(Red, frame.pixels.at(40, 30), "all the way across")
    }

    @Test
    fun `fourteen turned pictures cost one draw call`() {
        val picture = twoRows()
        val frame = draw {
            repeat(14) { ray -> image(picture, Rect.of(100f, 98f, 80f, 4f), degrees = ray * 360f / 14f, pivotX = 0f) }
        }

        assertEquals(1, frame.drawCalls, "a sunburst should batch with itself")
    }

    // --- blending ---

    @Test
    fun `an additive group really adds`() {
        val half = Colour.rgb(0x404040)
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            pushBlend(BlendMode.Additive)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
            rect(Rect.of(100f, 10f, 60f, 60f), half)
            rect(Rect.of(100f, 10f, 60f, 60f), half)
        }

        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.pixels.at(40, 40), "two halves added")
        assertColour(Rgb(0.25f, 0.25f, 0.25f), frame.pixels.at(130, 40), "source-over covers")
    }

    @Test
    fun `nested blends replace rather than combine, and popping goes back`() {
        val half = Colour.rgb(0x404040)
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            pushBlend(BlendMode.Additive)
            pushBlend(BlendMode.SourceOver)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
            rect(Rect.of(100f, 10f, 60f, 60f), half)
            rect(Rect.of(100f, 10f, 60f, 60f), half)
            popBlend()
        }

        assertColour(Rgb(0.25f, 0.25f, 0.25f), frame.pixels.at(40, 40), "the inner source-over covers")
        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.pixels.at(130, 40), "popped back to adding")
    }

    @Test
    fun `a group costs two batch boundaries however many quads are in it`() {
        val counts = listOf(2, 20).map { many ->
            draw {
                rect(Rect.of(0f, 0f, 10f, 10f), red)
                pushBlend(BlendMode.Additive)
                repeat(many) { rect(Rect.of(it * 12f, 20f, 10f, 10f), red) }
                popBlend()
                rect(Rect.of(0f, 200f, 10f, 10f), red)
            }.drawCalls
        }

        assertEquals(listOf(3, 3), counts, "the cost is per group, not per quad")
    }

    @Test
    fun `raw leaves the blend mode the caller asked for still in force`() {
        val half = Colour.rgb(0x404040)
        val frame = draw {
            pushBlend(BlendMode.Additive)
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            raw { }
            rect(Rect.of(10f, 10f, 60f, 60f), half)
            popBlend()
        }

        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.pixels.at(40, 40), "still adding after raw")
    }

    @Test
    fun `an additive picture adds too`() {
        val grey = KorgeTexture(Bitmap32(2, 2, premultiplied = false).also { b -> for (y in 0..1) for (x in 0..1) b[x, y] = RGBA(64, 64, 64, 255) })
        val frame = draw {
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.rgb(0x404040))
            pushBlend(BlendMode.Additive)
            image(grey, Rect.of(10f, 10f, 60f, 60f))
            popBlend()
        }

        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.pixels.at(40, 40), "a picture's light adds like a box's")
    }

    @Test
    fun `a frame that ends inside a blend starts the next plainly`() {
        val half = Colour.rgb(0x404040)
        val canvas = KorgeCanvas()
        try {
            // The first frame fails its balance check on the way out, leaving the mode pushed.
            runCatching {
                KorgeGl.picture { ctx ->
                    canvas.begin(viewport, ctx)
                    canvas.pushBlend(BlendMode.Additive)
                    canvas.end()
                }
            }
            val pixels = KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.rect(Rect.of(10f, 10f, 60f, 60f), half)
                canvas.rect(Rect.of(10f, 10f, 60f, 60f), half)
                canvas.end()
            }
            assertColour(Rgb(0.25f, 0.25f, 0.25f), pixels.at(40, 40), "a new frame covers")
        } finally {
            canvas.close()
        }
    }

    // --- tinting ---

    @Test
    fun `a tint multiplies what is drawn under it and nothing after it`() {
        val frame = draw {
            pushTint(Colour.rgb(0xFF8000))
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.rgb(0x808080))
            popTint()
            rect(Rect.of(100f, 10f, 60f, 60f), Colour.rgb(0x808080))
        }

        assertColour(Rgb(0.5f, 0.25f, 0f), frame.pixels.at(40, 40), "grey through orange")
        assertColour(Rgb(0.5f, 0.5f, 0.5f), frame.pixels.at(130, 40), "after the pop it is grey")
    }

    @Test
    fun `half a tint is halfway to the colour rather than half as opaque`() {
        val frame = draw {
            pushTint(Colour.Red.scaleAlpha(0.5f))
            rect(Rect.of(10f, 10f, 60f, 60f), Colour.White)
            popTint()
        }

        assertColour(Rgb(1f, 0.5f, 0.5f), frame.pixels.at(40, 40), "white halfway to red")
    }

    @Test
    fun `a picture's own tint and the one in force both apply`() {
        val frame = draw {
            pushTint(Colour.rgb(0x808080))
            image(twoRows(), Rect.of(10f, 10f, 40f, 40f), tint = Colour.rgb(0xFF00FF))
            popTint()
        }

        assertColour(Rgb(0.5f, 0f, 0f), frame.pixels.at(30, 15), "the red row, halved")
        assertColour(Rgb(0f, 0f, 0.5f), frame.pixels.at(30, 45), "the blue row, halved")
    }

    @Test
    fun `a tint reaches borders, shadows, fans, turned pictures and text`() {
        val fonts = KorgeFonts().also { it.registerTrueType("body", TestFonts.dejaVu(), listOf(48)) }
        val layout = fonts.measure("HHHH", dev.wildware.composegl.ui.text.TextStyle(family = "body", size = 48f))
        val canvas = KorgeCanvas(fonts.atlas)
        val green = Colour.rgb(0x00FF00)
        try {
            val pixels = KorgeGl.picture { ctx ->
                canvas.begin(viewport, ctx)
                canvas.pushTint(Colour.rgb(0xFF0000))
                // Every one of these is green, and red times green is nothing.
                canvas.border(Rect.of(0f, 0f, 100f, 100f), green, 10f)
                canvas.shadow(Rect.of(150f, 20f, 50f, 50f), green, 20f)
                canvas.fan(floatArrayOf(250f, 0f, 350f, 0f, 350f, 100f), green)
                canvas.image(twoRows(), Rect.of(0f, 300f, 40f, 40f), degrees = 90f, tint = green)
                canvas.text(layout, 20f, 150f, green)
                canvas.popTint()
                canvas.end()
            }
            val lit = (0 until KorgeGl.size).sumOf { y -> (0 until KorgeGl.size).count { x -> pixels.at(x, y).let { it.r + it.g + it.b } > 0.02f } }
            assertEquals(0, lit, "a red tint over green should leave nothing lit")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a tint costs no draw call`() {
        val frame = draw {
            rect(Rect.of(0f, 0f, 10f, 10f), red)
            pushTint(Colour.Grey)
            rect(Rect.of(20f, 0f, 10f, 10f), red)
            popTint()
            rect(Rect.of(40f, 0f, 10f, 10f), red)
        }

        assertEquals(1, frame.drawCalls, "the tint rides on each vertex, so the batch never breaks")
    }

    // --- all of it at once, for a person to look at ---

    @Test
    fun `gradients, corners, a turned picture, a blend and a tint side by side`() {
        val checker = KorgeTexture(
            Bitmap32(4, 4, premultiplied = false).also { b ->
                for (y in 0..3) for (x in 0..3) {
                    b[x, y] = if ((x + y) % 2 == 0) RGBA(255, 220, 60, 255) else RGBA(40, 120, 255, 255)
                }
            },
        )
        val frame = draw {
            rect(Rect.of(0f, 0f, size, size), Colour.rgb(0x1B1F2A))
            // Gradients: straight, angled and radial.
            rect(Rect.of(20f, 20f, 110f, 80f), Brush.vertical(Colour.rgb(0xFF5A5A), Colour.rgb(0x5A2AFF)), corner = 12f)
            rect(Rect.of(145f, 20f, 110f, 80f), Brush.linear(Colour.rgb(0x33DD88), Colour.rgb(0x0077FF), degrees = 45f), corner = 12f)
            rect(Rect.of(270f, 20f, 110f, 80f), Brush.radial(Colour.White, Colour.rgb(0xFF3366)), corner = 40f)
            // A radius per corner: tabs and a speech bubble.
            shadow(Rect.of(20f, 130f, 170f, 90f), Colour.argb(0xC0000000), spread = 14f, corners = Corners(0f, 30f, 30f, 30f))
            rect(Rect.of(20f, 130f, 170f, 90f), Colour.rgb(0xF0F0F0), Corners(0f, 30f, 30f, 30f))
            border(Rect.of(20f, 130f, 170f, 90f), Colour.rgb(0x3050FF), 4f, Corners(0f, 30f, 30f, 30f))
            repeat(3) { tab -> rect(Rect.of(215f + tab * 55f, 180f, 50f, 40f), if (tab == 1) Colour.rgb(0x33DD88) else Colour.rgb(0x556070), Corners.top(14f)) }
            // A turned picture, a sunburst of them.
            repeat(12) { ray -> image(checker, Rect.of(100f, 296f, 70f, 8f), degrees = ray * 30f, pivotX = 0f, pivotY = 0.5f) }
            image(checker, Rect.of(60f, 260f, 80f, 80f), degrees = 20f)
            // Additive light: three overlapping discs.
            pushBlend(BlendMode.Additive)
            circle(dev.wildware.composegl.ui.geometry.Offset(250f, 290f), 36f, Colour.rgb(0xC00000))
            circle(dev.wildware.composegl.ui.geometry.Offset(285f, 290f), 36f, Colour.rgb(0x00C000))
            circle(dev.wildware.composegl.ui.geometry.Offset(267f, 320f), 36f, Colour.rgb(0x0000C0))
            popBlend()
            // A tint: the same white card, plain and tinted orange.
            rect(Rect.of(320f, 250f, 30f, 110f), Colour.White, corner = 6f)
            pushTint(Colour.Orange)
            rect(Rect.of(355f, 250f, 30f, 110f), Colour.White, corner = 6f)
            popTint()
        }

        val bytes = PNG.encode(frame.pixels)
        File("build/screenshots/korge-canvas-extras.png").also { it.parentFile.mkdirs() }.writeBytes(bytes)
        System.getenv("COMPOSEGL_KORGE_SHOTS")?.let { dir -> File(dir, "korge-canvas-extras.png").also { it.parentFile.mkdirs() }.writeBytes(bytes) }

        val overlap = frame.pixels.at(267, 300)
        assertTrue(overlap.r > 0.7f && overlap.g > 0.7f && overlap.b > 0.7f, "red, green and blue light add up to white: $overlap")
        assertColour(Rgb(1f, 0.5f, 0f), frame.pixels.at(370, 300), "the tinted card is orange")
    }
}
