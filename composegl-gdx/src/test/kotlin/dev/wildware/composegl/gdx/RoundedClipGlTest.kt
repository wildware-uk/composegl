package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.wildware.composegl.ui.debug.BatchBreak
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.effect.ShaderEffect
import dev.wildware.composegl.ui.effect.ShaderSource
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.effect
import dev.wildware.composegl.ui.modifier.mirror
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.wildware.composegl.testing.imageOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * A rounded clip drawn in place on the real renderer: no picture taken, and the corners still cut
 * from everything inside — the toolkit's own boxes, a shader effect, and a game's own drawing.
 *
 * [ClipShapeRenderTest] already checks where the edge lands and how soft it is, and now runs
 * through this road for its rounded and round cases. These are what is new about the road.
 */
class RoundedClipGlTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val yellow = Colour.rgb(0xFFFF00)
    private val black = Colour.rgb(0x000000)

    private val passThrough = ShaderEffect(
        ShaderSource("pass-through", "void main() { gl_FragColor = texture2D(u_texture, v_texCoord) * u_alpha; }"),
    )

    /** What a frame came out as, read the toolkit's way up, and what its batch was cut by. */
    private class Frame(private val pixels: IntArray, val breaks: Set<BatchBreak>) {
        fun at(x: Int, y: Int) = Color(pixels[y * Gl.size + x])
    }

    /**
     * Composes [content], draws two frames with [input] between them, and reads the second back —
     * or only the first, when [frames] is one. [wrap] puts a canvas in front of the real one, to
     * compare against the picture road.
     */
    private fun render(
        input: (PointerRouter) -> Unit = {},
        wrap: (UiCanvas) -> UiCanvas = { it },
        frames: Int = 2,
        content: @Composable () -> Unit,
    ): Frame = Gl.render {
        val host = UiHost()
        val sprites = SpriteBatch()
        val canvas = GdxCanvas(sprites)
        // Published every frame, so its reading is the last frame's batch breaks.
        val budget = FrameBudget(publishEveryMillis = 0L)
        try {
            host.setContent(content)
            val router = PointerRouter(host.root, FocusManager(host.root))
            val ui = UiRenderer(host, wrap(canvas), budget)
            fun frame(nanos: Long) {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                ui.render(viewport, nanos)
            }
            frame(0L)
            if (frames > 1) {
                input(router)
                frame(16_666_667L)
            }
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            try {
                // OpenGL hands back the bottom row first.
                val pixels = IntArray(Gl.size * Gl.size) { at -> pixmap.getPixel(at % Gl.size, Gl.size - 1 - at / Gl.size) }
                Frame(pixels, budget.reading.culprits.map { it.reason }.toSet())
            } finally {
                pixmap.dispose()
            }
        } finally {
            host.dispose()
            canvas.dispose()
            sprites.dispose()
            whiteTexture?.dispose()
            whiteTexture = null
        }
    }

    private fun assertColour(expected: Colour, actual: Color, what: String) {
        val close = abs(expected.red / 255f - actual.r) < 0.1f &&
            abs(expected.green / 255f - actual.g) < 0.1f &&
            abs(expected.blue / 255f - actual.b) < 0.1f
        assertTrue(close, "$what: expected $expected, got $actual")
    }

    private fun Offset.click(router: PointerRouter) {
        router.onPointer(PointerEvent.Press(PointerId.Mouse, this))
        router.onPointer(PointerEvent.Release(PointerId.Mouse, this))
    }

    /** A rounded card at 100, 100, 100 across, whose corners are 40 round, with [inside] filling it. */
    @Composable
    private fun card(inside: @Composable () -> Unit) {
        Box(Modifier.padding(100f)) {
            Box(Modifier.size(100f).clip(corner = 40f)) { inside() }
        }
    }

    private fun Frame.assertRoundCard(colour: Colour) {
        assertColour(colour, at(150, 150), "the middle")
        assertColour(colour, at(150, 102), "just inside the top edge")
        assertColour(black, at(103, 103), "the top-left corner, cut away")
        assertColour(black, at(196, 196), "the bottom-right one")
        assertColour(black, at(98, 150), "nothing past the side")
    }

    @Test
    fun `a still rounded card is drawn on its second frame without a picture`() {
        val frame = render { card { Box(Modifier.size(100f).background(red)) } }

        frame.assertRoundCard(red)
        assertTrue(BatchBreak.Layer !in frame.breaks, "no picture taken: ${frame.breaks}")
    }

    @Test
    fun `a press inside a rounded card shows and the corners stay cut`() {
        var presses by mutableStateOf(0)
        val frame = render(input = { Offset(150f, 150f).click(it) }) {
            card { Box(Modifier.size(100f).background(if (presses > 0) yellow else red).clickable { presses++ }) }
        }

        assertEquals(1, presses)
        frame.assertRoundCard(yellow)
    }

    @Test
    fun `a shader effect inside a rounded card loses its corners too`() {
        val frame = render { card { Box(Modifier.size(100f).effect(passThrough).background(red)) } }

        frame.assertRoundCard(red)
    }

    @Test
    fun `a small effect in a rounded card's corner is cut and one in its middle is whole`() {
        val frame = render {
            card {
                Box(Modifier.size(30f).effect(passThrough).background(red))
                Box(Modifier.offset(40f, 40f).size(20f).effect(passThrough).background(yellow))
            }
        }

        assertColour(black, frame.at(103, 103), "the corner of the red one, cut with the card's")
        assertColour(red, frame.at(125, 125), "the rest of it")
        assertColour(yellow, frame.at(150, 150), "the yellow one in the middle, whole")
        assertColour(yellow, frame.at(141, 141), "right to its own corner")
    }

    @Test
    fun `a game's own drawing inside a rounded card loses its corners too`() {
        val frame = render {
            card {
                Box(
                    Modifier.size(100f).drawBehind { rect ->
                        raw(rect) { batch -> (batch as Batch).draw(white(), 0f, 0f, rect.width, rect.height) }
                    },
                )
            }
        }

        frame.assertRoundCard(Colour.White)
    }

    @Test
    fun `a game's own drawing that spills past its place into a corner loses that corner too`() {
        val frame = render {
            card {
                // A small place in the middle of the card, and a drawing four times its size
                // around it: the origin moves, nothing clips it there.
                Box(
                    Modifier.offset(40f, 40f).size(20f).drawBehind { rect ->
                        raw(rect) { batch -> (batch as Batch).draw(white(), -40f, -40f, 100f, 100f) }
                    },
                )
            }
        }

        frame.assertRoundCard(Colour.White)
    }

    @Test
    fun `a game's see-through drawing in a rounded card lands as it always did`() {
        // Half see-through black over a white card, from a batch at its default blend. The card is
        // a cut picture from the second frame, so the drawing lands on the card's white inside it.
        val middle = render {
            card {
                Box(Modifier.size(100f).background(Colour.White)) {
                    Box(
                        Modifier.size(100f).drawBehind { rect ->
                            raw(rect) { lent ->
                                val batch = lent as Batch
                                batch.setColor(0f, 0f, 0f, 0.5f)
                                batch.draw(white(), 0f, 0f, rect.width, rect.height)
                            }
                        },
                    )
                }
            }
        }.at(150, 150).r

        assertEquals(0.5f, middle, 0.03f, "half black over white is half grey")
    }

    @Test
    fun `an additive shine after a game's drawing in a rounded card lights the card as it always did`() {
        val frame = render {
            card {
                Box(Modifier.size(100f).background(blue)) {
                    Box(
                        Modifier.size(100f).drawBehind { rect ->
                            raw(rect) { lent -> (lent as Batch).draw(white(), 0f, 0f, 20f, 20f) }
                        },
                    )
                    Box(Modifier.offset(30f, 30f).size(40f).blend(BlendMode.Additive).background(red))
                }
            }
        }

        assertColour(Colour.rgb(0xFF00FF), frame.at(150, 150), "red added to the card's blue")
        assertColour(Colour.White, frame.at(115, 185), "the game's drawing")
        assertColour(black, frame.at(103, 196), "its corner, cut with the card's")
        assertTrue(BatchBreak.Layer in frame.breaks, "the card was a cut picture: ${frame.breaks}")
    }

    @Test
    fun `on its first frame an additive shine after a game's drawing in a rounded card lights the card too`() {
        // The first frame opens a picture at the game's drawing and draws the rest of the card into
        // it. Light adds no opacity to that picture, so the shine adds onto the card's blue under
        // it, as it does from the second frame on, rather than covering it.
        val frame = render(frames = 1) {
            card {
                Box(Modifier.size(100f).background(blue)) {
                    Box(
                        Modifier.size(100f).drawBehind { rect ->
                            raw(rect) { lent -> (lent as Batch).draw(white(), 0f, 0f, 20f, 20f) }
                        },
                    )
                    Box(Modifier.offset(30f, 30f).size(40f).blend(BlendMode.Additive).background(red))
                }
            }
        }

        assertColour(Colour.rgb(0xFF00FF), frame.at(150, 150), "red added to the card's blue")
        assertColour(Colour.White, frame.at(115, 185), "the game's drawing")
        assertColour(black, frame.at(103, 196), "its corner, cut with the card's")
        assertTrue(BatchBreak.Layer in frame.breaks, "through a picture: ${frame.breaks}")
    }

    @Test
    fun `on its first frame a game's drawing in a rounded card is trimmed through one picture`() {
        val frame = render(frames = 1) {
            card {
                Box(
                    Modifier.offset(40f, 40f).size(20f).drawBehind { rect ->
                        raw(rect) { batch -> (batch as Batch).draw(white(), -40f, -40f, 100f, 100f) }
                    },
                )
            }
        }

        frame.assertRoundCard(Colour.White)
        assertTrue(BatchBreak.Layer in frame.breaks, "through a picture: ${frame.breaks}")
    }

    @Test
    fun `games' drawings in a rounded card lose their corners and what comes after lands on top`() {
        val frame = render {
            card {
                Box(Modifier.size(100f).background(blue)) {
                    Box(
                        Modifier.size(100f).drawBehind { rect ->
                            // Bottom-left and top-right, counted up from the bottom as the batch counts.
                            raw(rect) { lent -> (lent as Batch).draw(white(), 0f, 0f, 30f, 30f) }
                            raw(rect) { lent ->
                                val batch = lent as Batch
                                batch.setColor(1f, 1f, 0f, 1f)
                                batch.draw(white(), 70f, 70f, 30f, 30f)
                            }
                        },
                    )
                    Box(Modifier.offset(40f, 40f).size(20f).background(red))
                }
            }
        }

        assertColour(Colour.White, frame.at(115, 185), "the first one")
        assertColour(black, frame.at(103, 196), "its corner, cut with the card's")
        assertColour(yellow, frame.at(185, 115), "the second one")
        assertColour(black, frame.at(196, 103), "its corner")
        assertColour(red, frame.at(150, 150), "the box drawn after them, on top")
        assertColour(blue, frame.at(150, 120), "the card between them")
    }

    @Test
    fun `a game's drawing in a rounded card is laid down plainly under an additive blend`() {
        val frame = render {
            card {
                Box(Modifier.size(100f).background(blue)) {
                    Box(
                        Modifier.size(100f).blend(BlendMode.Additive).drawBehind { rect ->
                            raw(rect) { lent ->
                                val batch = lent as Batch
                                batch.setColor(1f, 0f, 0f, 1f)
                                batch.draw(white(), 0f, 0f, rect.width, rect.height)
                            }
                        },
                    )
                }
            }
        }

        // The game's batch paints red over the blue, as it does anywhere else; added, it would be magenta.
        frame.assertRoundCard(red)
    }

    @Test
    fun `a rounded clip inside another is cut by both`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clip(corner = 50f)) {
                    Box(Modifier.size(60f).clip(corner = 10f)) { Box(Modifier.size(60f).background(red)) }
                }
            }
        }

        assertColour(red, frame.at(130, 130), "inside both")
        assertColour(black, frame.at(110, 110), "inside the small one's corner but past the big one's")
        assertColour(red, frame.at(150, 105), "the small one's top edge, where the big one is straight")
        assertColour(black, frame.at(159, 101), "the small one's own corner")
        assertColour(black, frame.at(170, 130), "past the small one's side")
    }

    @Test
    fun `a round portrait in a column of them is drawn without a picture`() {
        val frame = render {
            Column(Modifier.padding(100f)) {
                repeat(3) {
                    Box(Modifier.size(60f).clipShape(Shapes.Circle)) { Box(Modifier.size(60f).background(blue)) }
                }
            }
        }

        assertColour(blue, frame.at(130, 130), "the first one's middle")
        assertColour(blue, frame.at(130, 250), "the third one's middle")
        assertColour(black, frame.at(103, 163), "the second one's corner")
        assertTrue(BatchBreak.Layer !in frame.breaks, "no picture taken: ${frame.breaks}")
    }

    /**
     * Draws a 140 by 100 card at 100, 100 with corners 30 round, filled by [inside], both in place
     * and as a cut picture, writes both out as [name], and checks they differ only on the curve and
     * by less than [limit]. Returns the worst difference.
     */
    private fun assertOnlyTheCurveDiffers(name: String, limit: Float, inside: @Composable () -> Unit): Float {
        val screen: @Composable () -> Unit = {
            Box(Modifier.padding(100f)) { Box(Modifier.size(140f, 100f).clip(corner = 30f)) { inside() } }
        }
        val inPlace = render(content = screen)
        val picture = render(
            wrap = { canvas -> object : UiCanvas by canvas { override val roundsClips: Boolean get() = false } },
            content = screen,
        )

        // Both written out, so the two edges can be looked at side by side.
        val shots = File("build/screenshots/rounded-clip").apply { mkdirs() }
        ImageIO.write(imageOf(160, 120) { x, y -> Color.rgb888(inPlace.at(x + 90, y + 90)) }, "png", File(shots, "$name-in-place.png"))
        ImageIO.write(imageOf(160, 120) { x, y -> Color.rgb888(picture.at(x + 90, y + 90)) }, "png", File(shots, "$name-picture.png"))

        assertTrue(BatchBreak.Layer in picture.breaks, "the comparison really took the picture road")
        assertTrue(BatchBreak.Layer !in inPlace.breaks, "and the other one did not")

        var worst = 0f
        for (y in 90 until 210) for (x in 90 until 250) {
            val a = inPlace.at(x, y)
            val b = picture.at(x, y)
            val difference = maxOf(abs(a.r - b.r), abs(a.g - b.g), abs(a.b - b.b))
            worst = maxOf(worst, difference)
            val fromEdge = abs(roundedBoxDistance(x + 0.5f, y + 0.5f, left = 100f, top = 100f, right = 240f, bottom = 200f, radius = 30f))
            assertTrue(difference < 0.05f || fromEdge < 1.5f, "($x, $y) is $fromEdge from the edge and differs by $difference")
        }
        println("rounded clip, $name: in place against the cut picture differs by at most $worst, on the curve")
        assertTrue(worst < limit, "the soft edge differs by $worst, past $limit")
        return worst
    }

    // The only difference allowed is on the curve itself. A pixel the edge passes through is part
    // covered, c of it inside, and drawn in place each thing stacked there trims its own share, so
    // the things underneath show through where the picture shows what is outside: (1 - c) - (1 - c)^n
    // of them, for n things stacked. A picture trims the finished art once instead. The worst pixel
    // is the one the edge cuts at the right place: a quarter for two things, about two fifths for
    // three, more for more.

    @Test
    fun `drawn in place two things stacked at the curve show at most a quarter of the lower one there`() {
        assertOnlyTheCurveDiffers("two", limit = 0.27f) {
            Box(Modifier.size(140f, 100f).background(blue)) { Box(Modifier.size(140f, 50f).background(red)) }
        }
    }

    @Test
    fun `drawn in place three things stacked at the curve show at most two fifths of the lower ones there`() {
        val white = Colour.rgb(0xFFFFFF)
        // A white card with a white panel on it and black writing across its top: the worst a
        // three-deep stack can do, every layer as far from the one above as colour goes.
        val worst = assertOnlyTheCurveDiffers("three", limit = 0.4f) {
            Box(Modifier.size(140f, 100f).background(white)) {
                Box(Modifier.size(140f, 100f).background(white)) { Box(Modifier.size(140f, 50f).background(black)) }
            }
        }
        assertTrue(worst > 0.3f, "a stack of three really shows more than a stack of two, was $worst")
    }

    @Test
    fun `a rounded clip inside a picture keeps its rounded corners where they belong`() {
        // A mirrored scale takes a picture (a still plain one is drawn through a transform), and the
        // rounded clip inside it is drawn into that picture: top corners round, bottom ones square,
        // so a mask turned over would show. The mirror is side to side, which leaves them as they are.
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).scale(0.9f).mirror()) {
                    Box(Modifier.size(100f).clip(Corners.top(40f))) { Box(Modifier.size(100f).background(red)) }
                }
            }
        }

        // Ninety across, from 105 to 195 each way.
        assertColour(red, frame.at(150, 150), "the middle")
        assertColour(black, frame.at(108, 108), "the top-left corner, rounded away")
        assertColour(black, frame.at(192, 108), "the top-right one")
        assertColour(red, frame.at(106, 193), "the bottom-left corner stays square")
        assertColour(red, frame.at(193, 193), "and the bottom-right")
        assertTrue(BatchBreak.Layer in frame.breaks, "the scale really took a picture")
    }

    @Test
    fun `a rounded clip inside a still scale keeps its rounded corners where they belong`() {
        // The same, with no mirror: a still scale is drawn through a transform, and the rounded clip
        // inside it is trimmed in place through that transform, with no picture at all.
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).scale(0.9f)) {
                    Box(Modifier.size(100f).clip(Corners.top(40f))) { Box(Modifier.size(100f).background(red)) }
                }
            }
        }

        assertColour(red, frame.at(150, 150), "the middle")
        assertColour(black, frame.at(108, 108), "the top-left corner, rounded away")
        assertColour(black, frame.at(192, 108), "the top-right one")
        assertColour(red, frame.at(106, 193), "the bottom-left corner stays square")
        assertColour(red, frame.at(193, 193), "and the bottom-right")
        assertColour(black, frame.at(102, 150), "nothing left of the shrunk box")
        assertTrue(BatchBreak.Layer !in frame.breaks, "no picture: ${frame.breaks}")
    }

    @Test
    fun `an additive glow cut to a card's corners is trimmed in place and adds as the cut picture did`() {
        // A blue card, and a red glow over it cut to 30-unit corners: magenta inside, blue at the corners.
        val glowing: @Composable () -> Unit = {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).background(blue)) {
                    Box(Modifier.size(100f).clip(corner = 30f).blend(BlendMode.Additive).background(red))
                }
            }
        }
        val inPlace = render(content = glowing)
        val cut = render(wrap = { canvas -> object : UiCanvas by canvas { override val roundsClips: Boolean get() = false } }, content = glowing)

        assertColour(Colour.rgb(0xFF00FF), inPlace.at(150, 150), "red added to the card's blue")
        assertColour(blue, inPlace.at(102, 102), "the top-left corner, cut: the card alone")
        assertColour(blue, inPlace.at(197, 197), "and the bottom-right")
        assertTrue(BatchBreak.Layer !in inPlace.breaks, "no picture: ${inPlace.breaks}")
        assertTrue(BatchBreak.Layer in cut.breaks, "where the cut road took one: ${cut.breaks}")
        // Only along the four curves, where both soften the edge over a pixel, may the two differ.
        var differ = 0
        for (y in 95..205) {
            for (x in 95..205) {
                val a = inPlace.at(x, y)
                val b = cut.at(x, y)
                if (abs(a.r - b.r) > 0.1f || abs(a.g - b.g) > 0.1f || abs(a.b - b.b) > 0.1f) differ++
            }
        }
        assertTrue(differ < 60, "$differ pixels differ from the cut picture")
    }

    /** What a still screen cost: draw calls a frame, and microseconds a frame on the CPU and GPU together. */
    private class Cost(val drawCalls: Int, val micros: Double)

    /** Draws [content] for a while, then times [frames] more, each finished on the GPU before the next. */
    private fun cost(frames: Int, wrap: (UiCanvas) -> UiCanvas = { it }, content: @Composable () -> Unit): Cost = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        val budget = FrameBudget(publishEveryMillis = 0L)
        try {
            host.setContent(content)
            val ui = UiRenderer(host, wrap(canvas), budget)
            var nanos = 0L
            fun frame() {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                ui.render(viewport, nanos)
                Gdx.gl.glFinish()
                nanos += 16_666_667L
            }
            repeat(frames) { frame() }
            val started = System.nanoTime()
            repeat(frames) { frame() }
            Cost(budget.reading.drawCalls, (System.nanoTime() - started) / 1000.0 / frames)
        } finally {
            host.dispose()
            canvas.dispose()
        }
    }

    @Test
    fun `a screen of still rounded cards costs fewer draw calls in place than as pictures`() {
        // A board of 24 rounded cards, each with a band of colour across its top: a card draft.
        val board: @Composable () -> Unit = {
            Column(Modifier.padding(8f)) {
                repeat(4) {
                    Row {
                        repeat(6) {
                            Box(Modifier.padding(4f).size(56f, 80f).clip(corner = 12f).background(blue)) {
                                Box(Modifier.size(56f, 24f).background(red))
                            }
                        }
                    }
                }
            }
        }
        val pictures = cost(300, wrap = { canvas -> object : UiCanvas by canvas { override val roundsClips: Boolean get() = false } }, content = board)
        val inPlace = cost(300, content = board)

        println(
            "24 still rounded cards: in place ${inPlace.drawCalls} draw calls, ${"%.0f".format(inPlace.micros)} µs a frame; " +
                "as pictures ${pictures.drawCalls} draw calls, ${"%.0f".format(pictures.micros)} µs a frame",
        )
        assertTrue(inPlace.drawCalls < pictures.drawCalls, "${inPlace.drawCalls} in place against ${pictures.drawCalls}")
    }

    /** How far a point is from the edge of a rounded box: negative inside. The shader's own sum. */
    @Suppress("LongParameterList")
    private fun roundedBoxDistance(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, radius: Float): Float {
        val qx = abs(x - (left + right) / 2f) - (right - left) / 2f + radius
        val qy = abs(y - (top + bottom) / 2f) - (bottom - top) / 2f + radius
        val outside = kotlin.math.hypot(maxOf(qx, 0f), maxOf(qy, 0f))
        return minOf(maxOf(qx, qy), 0f) + outside - radius
    }

    /** A one-pixel white texture for a game's own drawing, made on the GL thread when first asked for. */
    private var whiteTexture: Texture? = null

    private fun white(): Texture = whiteTexture ?: Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pixels ->
        pixels.setColor(Color.WHITE)
        pixels.fill()
        Texture(pixels).also {
            pixels.dispose()
            whiteTexture = it
        }
    }
}
