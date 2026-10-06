package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.effects.Axis
import dev.wildware.composegl.effects.blur
import dev.wildware.composegl.effects.colourGrade
import dev.wildware.composegl.effects.dissolve
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import kotlin.math.abs

/**
 * Light inside an offscreen picture, on a real context: an additive glow adds colour and no
 * opacity, so the picture laid over the screen adds the glow onto what is behind it exactly as
 * drawing the glow straight does. Before, the glow's opacity went into the picture too, and the
 * picture covered what was behind by that much.
 */
class AdditiveLightGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val grey = Colour.rgb(0x404040)

    /** Half-opaque blue light: 0x80 of blue added wherever it lands. */
    private val blueLight = Colour(0x800000FF.toInt())

    /** [canvas], counting the pictures it is asked for, so a test knows the picture road was taken. */
    private class Counting(private val canvas: UiCanvas) : UiCanvas by canvas {
        var pictures = 0

        override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
            pictures++
            return canvas.layer(bounds, block)
        }
    }

    /** One frame of [tree] on a grey floor, read back, and how many pictures it took. */
    private fun frame(tree: TestTree, canvas: GlCanvas): Pair<IntArray, Int> {
        val counting = Counting(canvas)
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        counting.begin(viewport)
        DrawPass(counting).draw(tree.root)
        counting.end()
        return Gl.readPixels(Gl.size, Gl.size) to counting.pictures
    }

    /** A tree with a grey floor under everything [build] adds. */
    private fun onFloor(build: TestTree.() -> Unit) = TestTree().also { tree ->
        tree.box("floor", 0f, 0f, 400f, 400f, Modifier.background(grey))
        tree.build()
    }

    private fun IntArray.at(x: Int, y: Int): Int = this[y * Gl.size + x]

    private fun channels(pixel: Int) = listOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)

    private fun assertNear(expected: List<Int>, actual: Int, within: Int, message: String) {
        val got = channels(actual)
        assertTrue(
            got.zip(expected).all { (a, b) -> abs(a - b) <= within },
            "$message: expected ${expected.hex()}, got ${got.hex()}",
        )
    }

    private fun List<Int>.hex() = joinToString(", ", "(", ")") { "%02X".format(it) }

    /** Grey with 0x80 of blue added: what the blue light drawn straight onto the floor comes out as. */
    private val lit = listOf(0x40, 0x40, 0xC0)

    @Test
    fun `a glow over a see-through part of a turned panel adds onto what is behind as drawn straight`() = Gl.render {
        // A square panel with no paint of its own, turned a quarter about its middle so it lands
        // where it was, and light filling it.
        val turned = onFloor {
            val panel = box("panel", 40f, 40f, 80f, 80f, Modifier.rotate(90f))
            box("glow", 0f, 0f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(blueLight), parent = panel)
        }
        val straight = onFloor {
            box("glow", 40f, 40f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(blueLight))
        }

        val canvas = GlCanvas()
        try {
            val (inPicture, pictures) = frame(turned, canvas)
            val (drawnStraight, none) = frame(straight, canvas)
            println("turned glow over grey: ${channels(inPicture.at(80, 80)).hex()}, straight ${channels(drawnStraight.at(80, 80)).hex()}")

            assertTrue(pictures >= 1, "a turned panel is drawn through a picture: $pictures")
            assertEquals(0, none, "the straight one takes none")
            assertNear(lit, drawnStraight.at(80, 80), 2, "straight, the light adds onto the grey")
            assertNear(channels(drawnStraight.at(80, 80)), inPicture.at(80, 80), 2, "through the picture, the same")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a glow over a see-through part through a blur adds onto what is behind and covers none of it`() = Gl.render {
        // Light on a panel with no paint of its own, blurred sideways: the middle of it is as bright
        // as the light drawn straight, and nowhere is the floor darker than it was.
        val blurred = onFloor {
            val panel = box("panel", 40f, 40f, 160f, 120f, Modifier.effect(blur(6f, Axis.Horizontal)))
            box("glow", 20f, 20f, 120f, 80f, Modifier.blend(BlendMode.Additive).background(blueLight), parent = panel)
        }

        val canvas = GlCanvas()
        try {
            val (pixels, pictures) = frame(blurred, canvas)
            println("blurred glow over grey: middle ${channels(pixels.at(120, 100)).hex()}")

            assertTrue(pictures >= 1, "a blur is drawn through a picture: $pictures")
            assertNear(lit, pixels.at(120, 100), 3, "the middle of the blurred light")
            assertNear(channels(grey.argb), pixels.at(20, 100), 1, "the floor beside it")
            // The panel's picture reaches 6 units past it each side, the blur's bleed: 34 to 206 across.
            val around = (30 until 170).flatMap { y -> (30 until 210).map { x -> pixels.at(x, y) } }
            assertEquals(0, around.count { (it shr 16 and 0xFF) < 0x40 - 1 }, "pixels where the light made the floor darker")
            val softened = pixels.at(58, 100) and 0xFF
            assertTrue(softened in 0x48..0xB8, "the blur spreads the light's edge: blue %02X two pixels out".format(softened))
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a glow over a see-through part of a greyed panel is greyed and added rather than lost`() = Gl.render {
        // Green light, greyed by the colour grade: 0x80 of green weighs 0x4B of grey, added onto the
        // floor. Dividing by the light's opacity, which it no longer has, would lose it altogether.
        val greyed = onFloor {
            val panel = box("panel", 40f, 40f, 80f, 80f, Modifier.effect(colourGrade(saturation = 0f)))
            box("glow", 0f, 0f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(Colour(0x8000FF00.toInt())), parent = panel)
        }

        val canvas = GlCanvas()
        try {
            val (pixels, pictures) = frame(greyed, canvas)
            println("greyed green glow over grey: ${channels(pixels.at(80, 80)).hex()}")

            assertTrue(pictures >= 1, "a colour grade is drawn through a picture: $pictures")
            assertNear(listOf(0x8B, 0x8B, 0x8B), pixels.at(80, 80), 3, "grey light on the grey floor")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `brightness above one leaves light over a see-through part as bright as it came`() = Gl.render {
        // Pinned rather than promised: green light over nothing, graded twice as bright, adds the
        // same 0x80 of green onto the floor as it does ungraded.
        fun glow(brightness: Float) = onFloor {
            val panel = box("panel", 40f, 40f, 80f, 80f, Modifier.effect(colourGrade(brightness = brightness)))
            box("glow", 0f, 0f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(Colour(0x8000FF00.toInt())), parent = panel)
        }

        val canvas = GlCanvas()
        try {
            val (plain, _) = frame(glow(1f), canvas)
            val (doubled, _) = frame(glow(2f), canvas)

            assertNear(listOf(0x40, 0xC0, 0x40), plain.at(80, 80), 2, "ungraded light on the grey floor")
            assertNear(listOf(0x40, 0xC0, 0x40), doubled.at(80, 80), 2, "graded twice as bright, the same")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a layer drawn as an image keeps its light and its tint`() = Gl.render {
        // The picture holds light only. Put down as an image it adds onto the floor as drawLayer
        // does; tinted half see-through, half as much.
        val area = Rect.of(40f, 40f, 80f, 80f)
        val canvas = GlCanvas()
        try {
            fun drawn(draw: GlCanvas.(TextureHandle) -> Unit): IntArray {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                canvas.begin(viewport)
                canvas.rect(Rect.of(0f, 0f, 400f, 400f), grey)
                val picture = checkNotNull(
                    canvas.layer(area) {
                        canvas.pushBlend(BlendMode.Additive)
                        canvas.rect(area, blueLight)
                        canvas.popBlend()
                    },
                ) { "this driver gave us no layer" }
                canvas.draw(picture)
                canvas.end()
                return Gl.readPixels(Gl.size, Gl.size)
            }

            val composited = drawn { drawLayer(it, area) }
            val image = drawn { image(it, area, Colour.White) }
            val tinted = drawn { image(it, area, Colour(0x80FFFFFF.toInt())) }
            val turned = drawn { image(it, area, 90f, 0.5f, 0.5f, Colour.White) }
            println("light in a layer: drawLayer ${channels(composited.at(60, 60)).hex()}, image ${channels(image.at(60, 60)).hex()}, tinted ${channels(tinted.at(60, 60)).hex()}")

            assertNear(lit, composited.at(60, 60), 2, "put down with drawLayer")
            assertNear(lit, image.at(60, 60), 2, "put down as an image")
            assertNear(lit, turned.at(60, 60), 2, "put down as a turned image")
            assertNear(listOf(0x40, 0x40, 0x80), tinted.at(60, 60), 2, "half the light through a half see-through tint")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a colour grade grades a half-transparent paint as it always did`() = Gl.render {
        // Grading the stored colours rather than the straightened ones must come to the same
        // answer for paint: half-transparent red, greyed, and blown out past white.
        val half = Colour(0x80FF0000.toInt())
        val graded = onFloor {
            box("grey", 40f, 40f, 40f, 40f, Modifier.effect(colourGrade(saturation = 0f)).background(half))
            box("blown", 120f, 40f, 40f, 40f, Modifier.effect(colourGrade(brightness = 2f)).background(half))
            box("dim", 200f, 40f, 40f, 40f, Modifier.effect(colourGrade(brightness = 0.5f, contrast = 1.5f)).background(half))
        }

        val canvas = GlCanvas()
        try {
            val (pixels, _) = frame(graded, canvas)

            // Red at half opacity over 0x40: greyed, 0.299 of it.
            assertNear(listOf(0x46, 0x46, 0x46), pixels.at(60, 60), 2, "greyed")
            // Twice red is still red, at half opacity: no brighter than the paint was.
            assertNear(listOf(0xA0, 0x20, 0x20), pixels.at(140, 60), 2, "blown out")
            // Half as bright, then pushed from mid grey: (0.5 - 0.5) * 1.5 + 0.5 red, 0 green and blue.
            assertNear(listOf(0x60, 0x20, 0x20), pixels.at(220, 60), 2, "dimmed and pushed")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a glow over a see-through part of a dissolving panel stays light`() = Gl.render {
        // At the start of a dissolve nearly every pixel is whole. The light must be among them, not
        // lost for having no opacity, and must cover nothing behind it.
        val dissolving = onFloor {
            val panel = box("panel", 40f, 40f, 160f, 160f, Modifier.effect(dissolve(progress = 0f)))
            box("glow", 0f, 0f, 160f, 160f, Modifier.blend(BlendMode.Additive).background(blueLight), parent = panel)
        }

        val canvas = GlCanvas()
        try {
            val (pixels, pictures) = frame(dissolving, canvas)

            assertTrue(pictures >= 1, "a dissolve is drawn through a picture: $pictures")
            val inside = (42 until 198).flatMap { y -> (42 until 198).map { x -> pixels.at(x, y) } }
            val whole = inside.count { abs((it and 0xFF) - 0xC0) <= 3 }
            println("dissolving glow: ${whole * 100 / inside.size}% of its pixels whole light")
            assertTrue(whole > inside.size * 7 / 10, "most of the light is whole: $whole of ${inside.size}")
            assertEquals(0, inside.count { (it shr 16 and 0xFF) < 0x40 - 1 }, "pixels where the light made the floor darker")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `light in a render target is colour with no opacity so a premultiplied composite adds it`() = Gl.render {
        // Red paint on the left half, clear on the right, and blue light across the middle of both.
        val canvas = GlCanvas()
        GlRenderTarget(64, 16).use { target ->
            target.draw(canvas) {
                canvas.rect(Rect.of(0f, 0f, 32f, 16f), Colour.rgb(0xFF0000))
                canvas.pushBlend(BlendMode.Additive)
                canvas.rect(Rect.of(16f, 0f, 32f, 16f), blueLight)
                canvas.popBlend()
            }
            val pixels = target.readPixels()
            fun rgba(x: Int): List<Int> = (0 until 4).map { pixels[(8 * 64 + x) * 4 + it].toInt() and 0xFF }
            println("render target: paint and light ${rgba(24)}, light alone ${rgba(40)}, clear ${rgba(56)}")

            // Laid over a scene with ONE, ONE_MINUS_SRC_ALPHA: the paint covers it, and the light,
            // having no opacity, only adds 0x80 of blue to it.
            assertTrue(rgba(24).zip(listOf(0xFF, 0x00, 0x80, 0xFF)).all { (a, b) -> abs(a - b) <= 2 }, "paint and light: ${rgba(24)}")
            assertTrue(rgba(40).zip(listOf(0x00, 0x00, 0x80, 0x00)).all { (a, b) -> abs(a - b) <= 2 }, "light alone: ${rgba(40)}")
            assertEquals(listOf(0, 0, 0, 0), rgba(56), "clear")
        }
        canvas.close()
    }
}
