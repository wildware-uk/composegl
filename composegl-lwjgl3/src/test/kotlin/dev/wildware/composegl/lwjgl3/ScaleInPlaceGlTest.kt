package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11
import org.lwjgl.opengles.GLES20
import kotlin.math.abs

/**
 * A shrunk panel on a real context, drawn through a transform: where the picture used to put it,
 * the same colours, and fewer draw calls than the picture took.
 */
class ScaleInPlaceGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)

    /** A 160 by 80 panel at (40, 40), red on the left and blue on the right, drawn at half size from its corner. */
    private val screen = TestTree().also { screen ->
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("left", 0f, 0f, 80f, 80f, Modifier.background(red), parent = panel)
        screen.box("right", 80f, 0f, 80f, 80f, Modifier.background(blue), parent = panel)
    }

    /** One frame of [screen] through [canvas], read back, and how many draw calls it took. */
    private fun frame(canvas: UiCanvas): Pair<IntArray, Int> {
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        canvas.begin(viewport)
        DrawPass(canvas).draw(screen.root)
        canvas.end()
        return Gl.readPixels(Gl.size, Gl.size) to canvas.drawCalls
    }

    private fun IntArray.at(x: Int, y: Int): Int = this[y * Gl.size + x]

    private fun near(expected: Int, actual: Int): Boolean =
        abs((expected shr 16 and 0xFF) - (actual shr 16 and 0xFF)) < 24 &&
            abs((expected shr 8 and 0xFF) - (actual shr 8 and 0xFF)) < 24 &&
            abs((expected and 0xFF) - (actual and 0xFF)) < 24

    private fun assertColour(expected: Colour, actual: Int, message: String) {
        assertTrue(near(expected.argb and 0xFFFFFF, actual), "$message: expected %06X, got %06X".format(expected.argb and 0xFFFFFF, actual))
    }

    @Test
    fun `a shrunk panel is drawn where the picture put it with fewer draw calls`() = Gl.render {
        val canvas = GlCanvas()
        try {
            val (grown, grownCalls) = frame(canvas)
            // The same canvas, told it cannot transform: the way every scale was drawn before.
            val (pictured, picturedCalls) = frame(object : UiCanvas by canvas {
                override val transforms: Boolean get() = false
            })

            assertColour(red, grown.at(60, 60), "the left half, at half size")
            assertColour(blue, grown.at(100, 60), "the right half, at half size")
            assertColour(Colour.Black, grown.at(125, 60), "nothing past the shrunk panel")
            assertColour(Colour.Black, grown.at(60, 85), "or below it")

            // A picture is shrunk by sampling between its pixels, so where two colours meet — the
            // panel's edges and the seam down its middle — it blends a pixel the straight drawing
            // does not. Anywhere else the two must agree.
            val edges = setOf(39, 40, 79, 80, 119, 120)
            val elsewhere = grown.indices.filter { index ->
                !near(pictured[index], grown[index]) && index % Gl.size !in edges && index / Gl.size !in edges
            }
            assertTrue(
                elsewhere.isEmpty(),
                "${elsewhere.size} pixels away from an edge differ from the picture, first at " +
                    elsewhere.take(3).map { "(${it % Gl.size}, ${it / Gl.size})" },
            )
            assertTrue(grownCalls < picturedCalls, "no picture, fewer calls: $grownCalls against $picturedCalls")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a rounded clip inside a still scale is trimmed at the scaled corner as the picture trimmed it`() = Gl.render {
        // A 160 by 80 card with 40-unit corners, at half size from its corner: 80 by 40 at (40, 40),
        // corners of 20 pixels. The rounding is drawn in place, through the transform.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("card", 0f, 0f, 160f, 80f, Modifier.clip(corner = 40f).background(red), parent = panel)

        val canvas = GlCanvas()
        try {
            fun draw(on: UiCanvas): Pair<IntArray, Int> {
                Gl.gl.clearColor(0f, 0f, 0f, 1f)
                Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
                on.begin(viewport)
                DrawPass(on).draw(screen.root)
                on.end()
                return Gl.readPixels(Gl.size, Gl.size) to on.drawCalls
            }
            val (grown, grownCalls) = draw(canvas)
            val (pictured, picturedCalls) = draw(object : UiCanvas by canvas {
                override val transforms: Boolean get() = false
            })

            assertColour(red, grown.at(60, 60), "the middle of the half-size card")
            assertColour(red, grown.at(80, 42), "its top edge between the corners")
            // 20-pixel corners: (43, 43) is about 24 pixels from the top-left corner's centre at (60, 60).
            assertColour(Colour.Black, grown.at(43, 43), "the top-left corner, cut")
            assertColour(Colour.Black, grown.at(116, 76), "the bottom-right corner, cut")
            assertColour(Colour.Black, grown.at(125, 60), "nothing past the card")

            // Only along the curves, where both soften the edge over a pixel, may the two differ.
            val differ = grown.indices.count { !near(pictured[it], grown[it]) }
            assertTrue(differ < 40, "$differ pixels differ from the picture")
            assertTrue(grownCalls < picturedCalls, "no picture, fewer calls: $grownCalls against $picturedCalls")
        } finally {
            canvas.close()
        }
    }

    /** An effect that paints its first 20 design units green and leaves the rest alone: how far it reaches. */
    private val band = ShaderEffect(
        ShaderSource(
            "band",
            """
            void main() {
                float inside = step(v_texCoord.x * u_size.x, 20.0);
                gl_FragColor = vec4(0.0, inside, 0.0, inside) * u_alpha;
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `an effect inside a still scale reaches as far as it did in the picture`() = Gl.render {
        // The same panel with an effect on a child: at half size the band is 10 pixels, not 20.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("banded", 0f, 0f, 160f, 80f, Modifier.effect(band).background(red), parent = panel)

        val canvas = GlCanvas()
        try {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            DrawPass(canvas).draw(screen.root)
            canvas.end()
            val pixels = Gl.readPixels(Gl.size, Gl.size)

            assertColour(Colour.rgb(0x00FF00), pixels.at(45, 60), "inside the band")
            assertColour(Colour.Black, pixels.at(55, 60), "past 20 design units of the half-size panel")
        } finally {
            canvas.close()
        }
    }

    /**
     * [canvas], counting the pictures it is asked for, and how many of them were taken straight
     * from the screen: each of those stops the screen's drawing and starts it again, which on a
     * phone's tiled GPU is the expensive part.
     */
    private class Counting(private val canvas: UiCanvas) : UiCanvas by canvas {
        var pictures = 0
        var splits = 0
        private var depth = 0

        override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
            pictures++
            if (depth == 0) splits++
            depth++
            try {
                return canvas.layer(bounds, block)
            } finally {
                depth--
            }
        }
    }

    /** One frame of [tree] through [canvas], read back, and how many pictures it took. */
    private fun frame(tree: TestTree, canvas: UiCanvas): Pair<IntArray, Int> {
        val counting = Counting(canvas)
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
        counting.begin(viewport)
        DrawPass(counting).draw(tree.root)
        counting.end()
        return Gl.readPixels(Gl.size, Gl.size) to counting.pictures
    }

    /**
     * The same canvas, told it cannot transform: how a scale with a glow or an effect inside was
     * drawn before. Enough for a scene with no rounded clip in it; see [Master] for one with.
     */
    private fun pictureRoad(canvas: UiCanvas): UiCanvas = object : UiCanvas by canvas {
        override val transforms: Boolean get() = false
    }

    /**
     * The same canvas drawing as master did before this change: no transform for a scale with a
     * glow or an effect inside, and no rounded clip trimmed in place while a blend mode is in force,
     * which cut it through a picture of its own. A picture starts with plain blending, as on the
     * real canvas.
     */
    private class Master(private val canvas: UiCanvas) : UiCanvas by canvas {
        override val transforms: Boolean get() = false
        private var modes = ArrayList<BlendMode>()

        override val roundsClips: Boolean
            get() = canvas.roundsClips && (modes.lastOrNull() ?: BlendMode.SourceOver) == BlendMode.SourceOver

        override fun pushBlend(mode: BlendMode) {
            modes += mode
            canvas.pushBlend(mode)
        }

        override fun popBlend() {
            modes.removeAt(modes.lastIndex)
            canvas.popBlend()
        }

        override fun layer(bounds: Rect, block: () -> Unit): TextureHandle? {
            val outer = modes
            modes = ArrayList()
            try {
                return canvas.layer(bounds, block)
            } finally {
                modes = outer
            }
        }
    }

    private fun channels(pixel: Int) = listOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)

    @Test
    fun `a glow on a still scaled panel's own paint looks as it did in the picture`() = Gl.render {
        // A red card at half size with a blue glow added over its left half: magenta there.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        val card = screen.box("card", 0f, 0f, 160f, 80f, Modifier.background(red), parent = panel)
        screen.box("glow", 0f, 0f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(blue), parent = card)

        val canvas = GlCanvas()
        try {
            val (grown, grownPictures) = frame(screen, canvas)
            val (pictured, picturedPictures) = frame(screen, pictureRoad(canvas))

            assertColour(Colour.rgb(0xFF00FF), grown.at(60, 60), "red with blue added")
            assertColour(red, grown.at(100, 60), "red alone")
            val edges = setOf(39, 40, 79, 80, 119, 120)
            val differ = grown.indices.count { !near(pictured[it], grown[it]) && it % Gl.size !in edges && it / Gl.size !in edges }
            assertEquals(0, differ, "pixels away from an edge that differ from the picture")
            assertEquals(0, grownPictures, "no picture")
            assertEquals(1, picturedPictures, "where the picture road took one")
        } finally {
            canvas.close()
        }
    }

    @Test
    fun `a glow over a see-through part of a still scaled panel adds onto what is behind as it would unscaled`() = Gl.render {
        // A grey floor, and a half-opaque blue glow on a panel with no paint of its own.
        val grey = Colour.rgb(0x404040)
        val glow = Colour(0x800000FF.toInt())
        fun scene(scaled: Boolean) = TestTree().also { screen ->
            screen.box("floor", 0f, 0f, 400f, 400f, Modifier.background(grey))
            if (scaled) {
                val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
                screen.box("glow", 0f, 0f, 80f, 80f, Modifier.blend(BlendMode.Additive).background(glow), parent = panel)
            } else {
                // The same glow where the half-size one lands, with no scale at all.
                screen.box("glow", 40f, 40f, 40f, 40f, Modifier.blend(BlendMode.Additive).background(glow))
            }
        }

        val canvas = GlCanvas()
        try {
            val (grown, pictures) = frame(scene(scaled = true), canvas)
            val (unscaled, _) = frame(scene(scaled = false), canvas)
            val (pictured, _) = frame(scene(scaled = true), pictureRoad(canvas))

            val now = channels(grown.at(60, 60))
            val plain = channels(unscaled.at(60, 60))
            val before = channels(pictured.at(60, 60))
            println("glow over grey: in place $now, unscaled $plain, picture $before")

            assertTrue(now.zip(plain).all { (a, b) -> abs(a - b) <= 2 }, "the same as unscaled: $now against $plain")
            // And the same as the picture: light adds no opacity to it, so the picture laid over the
            // grey adds the glow onto it rather than covering it by the glow's own opacity.
            assertTrue(now.zip(before).all { (a, b) -> abs(a - b) <= 2 }, "the same as the picture: $now against $before")
            assertEquals(0, pictures, "no picture")
        } finally {
            canvas.close()
        }
    }

    /**
     * A sideways blur of `u_radius` over seventeen even taps: how far it spreads. Measured in design
     * units through `u_size`, or, [inPixels], in the picture's own pixels through `u_textureSize`,
     * the way a shader written from the pixel-step advice would be.
     */
    private fun spread(radius: Float, inPixels: Boolean = false) = ShaderEffect(
        ShaderSource(
            if (inPixels) "spread-in-pixels" else "spread",
            """
            uniform float u_radius;
            void main() {
                vec4 total = vec4(0.0);
                for (int i = -8; i <= 8; i++) {
                    vec2 at = v_texCoord + vec2(float(i) * u_radius / 8.0 / ${if (inPixels) "u_textureSize.x" else "u_size.x"}, 0.0);
                    total += texture2D(u_texture, at) * step(0.0, at.x) * step(at.x, 1.0);
                }
                gl_FragColor = total / 17.0 * u_alpha;
            }
            """.trimIndent(),
        ),
        mapOf("u_radius" to Uniform.Number(radius)),
        bleed = radius,
    )

    @Test
    fun `a blur inside a still scale spreads as far as it did in the picture`() = blurAgainstThePicture(inPixels = false)

    @Test
    fun `a blur stepping in the picture's own pixels inside a still scale spreads as far as it did in the picture`() =
        blurAgainstThePicture(inPixels = true)

    private fun blurAgainstThePicture(inPixels: Boolean) = Gl.render {
        // A 40-unit red square blurred 16 units sideways, at half size: an 8-pixel spread each side.
        val screen = TestTree()
        val panel = screen.box("panel", 40f, 40f, 160f, 80f, Modifier.scale(0.5f, Alignment.TopStart))
        screen.box("soft", 20f, 20f, 40f, 40f, Modifier.effect(spread(16f, inPixels)).background(red), parent = panel)

        val canvas = GlCanvas()
        try {
            val (grown, grownPictures) = frame(screen, canvas)
            val (pictured, picturedPictures) = frame(screen, pictureRoad(canvas))

            // The square lands on 50..70 across and 50..70 down; the spread reaches 42..78.
            assertColour(red, grown.at(60, 60), "the middle")
            assertTrue((grown.at(45, 60) shr 16 and 0xFF) in 0x10..0x70, "faint red 5 pixels out: %06X".format(grown.at(45, 60)))
            assertColour(Colour.Black, grown.at(40, 60), "nothing 10 pixels out")
            val worst = grown.indices.maxOf { index ->
                channels(grown[index]).zip(channels(pictured[index])).maxOf { (a, b) -> abs(a - b) }
            }
            println("blur under a still scale${if (inPixels) ", in pixels" else ""}: worst channel difference from the picture $worst")
            // Taken at the size the scale's picture took it and shrunk on the way down the same way,
            // so the same pixels but for rounding. Taken at the size it lands, a blur stepping in
            // its own pixels was off by over a hundred and cut off square.
            assertTrue(worst <= 4, "every pixel within 4 levels of the picture's, worst $worst")
            assertEquals(1, grownPictures, "the effect's own picture only")
            assertEquals(2, picturedPictures, "where the picture road took the scale's as well")
        } finally {
            canvas.close()
        }
    }

    /** Keeps what is already light, as a card's light pass does before it blurs it. */
    private val bright = ShaderEffect(
        ShaderSource(
            "bright",
            """
            void main() {
                vec4 picture = texture2D(u_texture, v_texCoord);
                float light = max(picture.r, max(picture.g, picture.b));
                gl_FragColor = picture * step(0.6, light) * u_alpha;
            }
            """.trimIndent(),
        ),
    )

    /**
     * A row of five cards shrunk to fit, the way a card draft deals them: each one a rounded face,
     * a glare cut to the same corners and added over it, sparks added round its rim, and its light
     * — the face again, brights kept, blurred — added over all of it.
     */
    private fun draft(): TestTree = TestTree().also { screen ->
        val gold = Colour.rgb(0xE8B040)
        val shine = Colour(0x60FFF8E0)
        val spark = Colour(0xB0FFE080.toInt())
        repeat(5) { at ->
            val seat = screen.box("seat$at", 10f + at * 76f, 120f, 96f, 136f, Modifier.scale(0.75f, Alignment.TopStart))
            screen.box("face$at", 8f, 8f, 80f, 120f, Modifier.clip(corner = 10f).background(gold), parent = seat)
            screen.box("glare$at", 8f, 8f, 80f, 120f, Modifier.clip(corner = 10f).blend(BlendMode.Additive).drawBehind { area ->
                for (slice in 0 until 14) {
                    val left = area.left + area.width * (0.2f + slice * 0.03f)
                    rect(Rect(left, area.top, left + area.width * 0.03f, area.bottom), shine)
                }
            }, parent = seat)
            screen.box("sparks$at", 0f, 0f, 96f, 136f, Modifier.blend(BlendMode.Additive).drawBehind { area ->
                for (dot in 0 until 12) {
                    val x = area.left + 4f + (dot * 37 % 88)
                    val y = if (dot % 2 == 0) area.top + 2f else area.bottom - 6f
                    rect(Rect(x, y, x + 4f, y + 4f), spark)
                }
            }, parent = seat)
            val light = screen.box(
                "light$at", 8f, 8f, 80f, 120f,
                Modifier.effect(bright).effect(spread(6f)).blend(BlendMode.Additive),
                parent = seat,
            )
            screen.box("again$at", 0f, 0f, 80f, 120f, Modifier.clip(corner = 10f).background(gold), parent = light)
        }
    }

    /** What a still frame cost: draw calls, pictures, pictures taken from the screen, and microseconds with the GPU finished. */
    private class Cost(val drawCalls: Int, val pictures: Int, val splits: Int, val micros: Double)

    private fun cost(tree: TestTree, canvas: UiCanvas, frames: Int = 300): Cost {
        val counting = Counting(canvas)
        val pass = DrawPass(counting)
        fun once() {
            Gl.gl.clearColor(0f, 0f, 0f, 1f)
            Gl.gl.clear(GL11.GL_COLOR_BUFFER_BIT)
            counting.pictures = 0
            counting.splits = 0
            counting.begin(viewport)
            pass.draw(tree.root)
            counting.end()
            // Through the context's own binding: desktop GL's class will not load on an ES context.
            if (Gl.context == GlfwContext.Desktop) GL11.glFinish() else GLES20.glFinish()
        }
        repeat(frames) { once() }
        val started = System.nanoTime()
        repeat(frames) { once() }
        return Cost(canvas.drawCalls, counting.pictures, counting.splits, (System.nanoTime() - started) / 1000.0 / frames)
    }

    @Test
    fun `a still shrunk draft row takes no picture for its seats and stops the screen no more often`() = Gl.render {
        val canvas = GlCanvas()
        try {
            val tree = draft()
            val now = cost(tree, canvas)
            val before = cost(tree, Master(canvas))
            println(
                "five still shrunk draft cards: now ${now.drawCalls} draw calls, ${now.pictures} pictures " +
                    "(${now.splits} from the screen), ${"%.0f".format(now.micros)} µs a frame; as master drew them ${before.drawCalls} " +
                    "draw calls, ${before.pictures} pictures (${before.splits} from the screen), ${"%.0f".format(before.micros)} µs a frame",
            )
            // Master's own count for this scene, measured on master: 20 pictures, 5 from the screen.
            assertEquals(20, before.pictures, "the baseline draws as master did")
            assertEquals(before.pictures - 10, now.pictures, "two pictures fewer a card: the seat's and its glare's")
            assertTrue(now.splits <= before.splits, "the light's picture where the seat's was: ${now.splits} against ${before.splits}")
            assertTrue(now.drawCalls < before.drawCalls, "${now.drawCalls} draw calls against ${before.drawCalls}")
        } finally {
            canvas.close()
        }
    }
}
