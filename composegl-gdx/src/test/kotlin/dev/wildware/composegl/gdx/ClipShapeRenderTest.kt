package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Shape
import dev.wildware.composegl.ui.geometry.Shapes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
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
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.clipShape
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * `Modifier.clipShape` on the real renderer: a composed screen, drawn by LibGDX, clicked with a
 * pointer, and read back as pixels.
 *
 * The recording canvas already says the picture is taken and cut. Only pixels say the cut lands
 * where the outline is, the edge is soft rather than a staircase, and nothing outside it leaks.
 */
class ClipShapeRenderTest {

    private val viewport = Viewport(
        design = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private val red = Colour.rgb(0xFF0000)
    private val yellow = Colour.rgb(0xFFFF00)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)

    /** What a frame came out as, read the toolkit's way up. */
    private class Frame(private val pixels: IntArray) {
        fun at(x: Int, y: Int) = Color(pixels[y * Gl.size + x])
    }

    /**
     * Composes [content], draws a frame, hands the laid-out tree's pointer router to [input], draws
     * another frame so whatever the input changed is on screen, and reads that frame back.
     */
    private fun render(
        input: (PointerRouter) -> Unit = {},
        on: Viewport = viewport,
        content: @Composable () -> Unit,
    ): Frame = Gl.render {
        val host = UiHost()
        val canvas = GdxCanvas()
        try {
            host.setContent(content)
            val router = PointerRouter(host.root, FocusManager(host.root))
            val ui = UiRenderer(host, canvas)
            fun frame(nanos: Long) {
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                ui.render(on, nanos)
            }
            frame(0L)
            input(router)
            frame(16_666_667L)
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
            try {
                // OpenGL hands back the bottom row first.
                Frame(IntArray(Gl.size * Gl.size) { at -> pixmap.getPixel(at % Gl.size, Gl.size - 1 - at / Gl.size) })
            } finally {
                pixmap.dispose()
            }
        } finally {
            host.dispose()
            canvas.dispose()
        }
    }

    private fun assertColour(expected: Colour, actual: Color, what: String) {
        val close = kotlin.math.abs(expected.red / 255f - actual.r) < 0.1f &&
            kotlin.math.abs(expected.green / 255f - actual.g) < 0.1f &&
            kotlin.math.abs(expected.blue / 255f - actual.b) < 0.1f
        assertTrue(close, "$what: expected $expected, got $actual")
    }

    private val black = Colour.rgb(0x000000)

    private fun Offset.click(router: PointerRouter) {
        router.onPointer(PointerEvent.Press(PointerId.Mouse, this))
        router.onPointer(PointerEvent.Release(PointerId.Mouse, this))
    }

    @Test
    fun `square art comes out round`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(red, frame.at(150, 150), "the middle")
        assertColour(red, frame.at(150, 103), "just inside the top of the circle")
        assertColour(black, frame.at(103, 103), "the top-left corner of the art, cut away")
        assertColour(black, frame.at(196, 196), "the bottom-right one")
        assertColour(black, frame.at(99, 150), "nothing leaks past the side of the box")
    }

    @Test
    fun `a clip rounded only along its top cuts the art's top corners and keeps its bottom ones`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clip(Corners.top(40f))) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(red, frame.at(150, 150), "the middle")
        assertColour(black, frame.at(103, 103), "the top-left corner, rounded away")
        assertColour(black, frame.at(196, 103), "the top-right one")
        assertColour(red, frame.at(101, 198), "the bottom-left corner stays square")
        assertColour(red, frame.at(198, 198), "and the bottom-right")
    }

    @Test
    fun `the edge of the circle is soft rather than a staircase`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        var between = 0
        for (y in 100 until 200) for (x in 100 until 200) {
            val r = frame.at(x, y).r
            if (r > 0.15f && r < 0.85f) between++
        }
        // A hard-edged fan puts every pixel fully in or fully out. A one-pixel feather round a
        // circle of radius 50 leaves a ring of a few hundred part-covered pixels.
        assertTrue(between > 100, "only $between pixels were partly covered")
        assertTrue(between < 800, "$between pixels were partly covered, which is a blur rather than an edge")
    }

    @Test
    fun `a click on a corner the circle cut away presses the button underneath, on screen`() {
        var underClicks by mutableStateOf(0)
        var portraitClicks by mutableStateOf(0)
        val screen: @Composable () -> Unit = {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).background(if (underClicks > 0) green else blue).clickable { underClicks++ })
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) {
                    Box(Modifier.size(100f).background(if (portraitClicks > 0) yellow else red).clickable { portraitClicks++ })
                }
            }
        }

        val corner = render(input = { Offset(104f, 104f).click(it) }, content = screen)
        assertEquals(1, underClicks)
        assertEquals(0, portraitClicks)
        assertColour(green, corner.at(103, 103), "the corner shows the button, and the button shows the press")
        assertColour(red, corner.at(150, 150), "the portrait is untouched")

        underClicks = 0
        val middle = render(input = { Offset(150f, 150f).click(it) }, content = screen)
        assertEquals(0, underClicks)
        assertEquals(1, portraitClicks)
        assertColour(yellow, middle.at(150, 150), "the portrait shows the press")
        assertColour(blue, middle.at(103, 103), "and the button does not")
    }

    @Test
    fun `a circle inside a diamond is cut by both on screen`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Diamond)) {
                    Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
                }
            }
        }

        assertColour(red, frame.at(150, 150), "inside both")
        assertColour(black, frame.at(120, 128), "inside the circle but past the diamond's edge")
        assertColour(black, frame.at(104, 104), "the corner both of them cut")
        assertColour(red, frame.at(150, 104), "the diamond's tip, where the circle reaches too")
    }

    @Test
    fun `on a screen twice the design size the edge stays one screen pixel soft`() {
        // Half the design size, drawn at double scale: the picture is taken at screen resolution
        // and the feather is one screen pixel, not one design unit.
        val doubled = Viewport(
            design = Size(Gl.size / 2f, Gl.size / 2f),
            physical = Size(Gl.size.toFloat(), Gl.size.toFloat()),
            policy = ScalePolicy.Fit,
        )
        val frame = render(on = doubled) {
            Box(Modifier.padding(50f)) {
                Box(Modifier.size(50f).clipShape(Shapes.Circle)) { Box(Modifier.size(50f).background(red)) }
            }
        }
        val plain = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(100f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
            }
        }

        assertColour(red, frame.at(150, 150), "the middle, in screen pixels")
        assertColour(black, frame.at(104, 104), "the corner, cut")
        fun Frame.partlyCovered(): Int {
            var between = 0
            for (y in 100 until 200) for (x in 100 until 200) {
                val r = at(x, y).r
                if (r > 0.15f && r < 0.85f) between++
            }
            return between
        }
        // The same circle on screen either way, so the same ring. A feather of one design unit
        // would be two screen pixels here, and about double the count.
        val doubledRing = frame.partlyCovered()
        val plainRing = plain.partlyCovered()
        assertTrue(
            doubledRing < plainRing * 1.4f,
            "$doubledRing pixels partly covered at double scale against $plainRing at one to one",
        )
    }

    @Test
    fun `a shaped clip with no size draws nothing and breaks nothing after it`() {
        val frame = render {
            Box(Modifier.padding(100f)) {
                Box(Modifier.size(0f).clipShape(Shapes.Circle)) { Box(Modifier.size(100f).background(red)) }
                Box(Modifier.offset(x = 50f).size(20f).background(blue))
            }
        }

        assertColour(black, frame.at(120, 120), "the art in a node of no size is not drawn")
        assertColour(blue, frame.at(160, 110), "and what comes after it is drawn as usual")
    }

    @Test
    fun `a row of shaped portraits matches its golden`() {
        val shapes: List<Shape> = listOf(Shapes.Circle, Shapes.Diamond, Shapes.Hexagon, Shapes.roundedRect(16f))
        val frame = render {
            Row(Modifier.padding(8f)) {
                shapes.forEach { shape ->
                    Box(Modifier.padding(4f)) {
                        // The background after the clip, so it is cut too: the whole tile is the shape.
                        Box(Modifier.size(80f).clipShape(shape).background(Colour.rgb(0x1E2836))) {
                            Column {
                                Box(Modifier.size(80f, 26f).background(Colour.rgb(0x4CC2FF)))
                                Box(Modifier.size(80f, 27f).background(Colour.rgb(0xE6EDF5)))
                                Box(Modifier.size(80f, 20f).background(Colour.rgb(0xE5484D)))
                            }
                        }
                    }
                }
            }
        }

        val image: BufferedImage = imageOf(360, 104) { x, y -> frame.at(x, y).let { Color.rgb888(it) } }
        Goldens.assertMatches("clip-shape-ui", image)
    }
}
