package dev.wildware.composegl.lwjgl3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.ProvideLayoutDirection
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A `SceneView` on a real GPU, through the raw OpenGL frontend, judged by the pixels.
 *
 * The frontend hands a scene the same [GlFrame] it hands `raw`, with the picture bound, and the
 * device hands the context back afterwards. These are the proof: the scene's colour lands inside the
 * panel and nowhere else, the right way up; the nearer of two triangles wins; and a widget drawn after
 * a scene that left its state lying about draws exactly as it would with no scene at all.
 *
 * Run on desktop GL 2, GL 3.2 core, OpenGL ES 3 and ES 2 by the suite's four test tasks.
 */
class SceneViewGlTest {

    @BeforeEach
    fun requireDisplay() = assumeTrue(Gl.available, "no display; these tests need a real GL context")

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)

    /** Where the scene view is laid out, left to right. */
    private val panel = Rect.of(40f, 60f, 120f, 80f)

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.readBytes()

    private fun <T> withBackend(block: (Lwjgl3Backend) -> T): T = Gl.render {
        val fonts = StbFonts().apply { register("body", bytes("/fonts/DejaVuSans.ttf"), listOf(24)) }
        val backend = Lwjgl3Backend(Gl.window, fonts)
        try {
            block(backend)
        } finally {
            backend.close()
            fonts.close()
        }
    }

    private fun open(backend: Lwjgl3Backend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)

    /** The scene view where [panel] says, then a green box and a label drawn after it. */
    @Composable
    private fun Screen(state: SceneViewState?, modifier: Modifier = Modifier, draw: SceneDrawScope.() -> Unit) {
        Box(Modifier.size(Gl.size.toFloat(), Gl.size.toFloat())) {
            if (state != null) {
                SceneView(state, Modifier.offset(panel.left, panel.top).size(panel.width, panel.height).then(modifier).testTag("scene"), draw = draw)
            }
            Box(Modifier.offset(200f, 60f).size(80f, 80f).background(green))
            Text("After", Modifier.offset(200f, 160f), textStyle = TextStyle(family = "body", size = 24f), colour = white)
        }
    }

    /** One whole frame of [ui] over black, top row first, as `0xRRGGBB`. */
    private fun frame(ui: UiTest): IntArray {
        Gl.gl.clearColor(0f, 0f, 0f, 1f)
        Gl.gl.clear(GlConst.COLOR_BUFFER_BIT)
        ui.render()
        return Gl.readPixels(Gl.size, Gl.size)
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * Gl.size + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    @Test
    fun `a scene's colour fills its panel and nothing outside it`() = withBackend { backend ->
        val state = SceneViewState()
        open(backend) { Screen(state) { clear(red) } }.use { ui ->
            val pixels = frame(ui)

            assertEquals(1L, state.draws)
            assertEquals(120 to 80, state.width to state.height, "the picture is the panel's real pixels")
            for (y in 61 until 139) for (x in 41 until 159) {
                assertEquals(red.code(), pixels.at(x, y), "inside the panel at $x, $y")
            }
            for (y in 0 until Gl.size) for (x in 0 until Gl.size) {
                val inside = x in 39..160 && y in 59..140
                if (!inside) assertTrue(pixels.at(x, y) != red.code(), "the scene leaked out of its panel at $x, $y")
            }
        }
        state.release()
    }

    @Test
    fun `the picture is the right way up`() = withBackend { backend ->
        val state = SceneViewState()
        open(backend) {
            Screen(state) {
                clear(red)
                raw { frame ->
                    assertTrue(frame is GlFrame, "the raw OpenGL frontend hands a scene a GlFrame")
                    // OpenGL counts rows up from the bottom: this is the bottom half, in blue.
                    Gl.gl.enable(GlConst.SCISSOR_TEST)
                    Gl.gl.scissor(0, 0, width, height / 2)
                    Gl.gl.clearColor(0f, 0f, 1f, 1f)
                    Gl.gl.clear(GlConst.COLOR_BUFFER_BIT)
                    // The scissor is deliberately left on: the device takes it back.
                }
            }
        }.use { ui ->
            val pixels = frame(ui)
            assertEquals(red.code(), pixels.at(100, 70), "the top of the panel")
            assertEquals(blue.code(), pixels.at(100, 130), "the bottom of the panel")
            assertEquals(green.code(), pixels.at(240, 100), "the box after it, whole: the scene's scissor went no further")
        }
        state.release()
    }

    @Test
    fun `the nearer triangle wins inside a scene view whichever order they are drawn in`() = withBackend { backend ->
        val state = SceneViewState()
        var farFirst = true
        open(backend) {
            Screen(state) {
                clear(green)
                raw {
                    DepthScene(Gl.gl).use { scene ->
                        if (farFirst) {
                            scene.triangle(depth = 0.6f, colour = blue)
                            scene.triangle(depth = -0.6f, colour = red)
                        } else {
                            scene.triangle(depth = -0.6f, colour = red)
                            scene.triangle(depth = 0.6f, colour = blue)
                        }
                    }
                }
            }
        }.use { ui ->
            assertEquals(red.code(), frame(ui).at(100, 100), "the far triangle was drawn first")

            farFirst = false
            state.invalidate()
            assertEquals(red.code(), frame(ui).at(100, 100), "the far triangle was drawn last")
            assertEquals(2L, state.draws)
        }
        state.release()
    }

    /**
     * Everything a careless renderer leaves behind: its own program, depth test, culling, a scissor,
     * a tiny viewport, another texture unit, a stricter row alignment.
     */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw {
            val scene = DepthScene(Gl.gl)
            scene.triangle(depth = 0f, colour = blue)
            Gl.gl.useProgram(0)
            Gl.gl.enable(GlConst.DEPTH_TEST)
            Gl.gl.enable(GlConst.CULL_FACE)
            Gl.gl.enable(GlConst.SCISSOR_TEST)
            Gl.gl.scissor(0, 0, 1, 1)
            Gl.gl.viewport(0, 0, 1, 1)
            Gl.gl.activeTexture(GlConst.TEXTURE0 + 3)
            Gl.gl.pixelStorei(GlConst.UNPACK_ALIGNMENT, 8)
            Gl.gl.clearColor(1f, 0f, 1f, 1f)
        }
    }

    @Test
    fun `a widget drawn after a careless scene is exactly what it is with no scene`() {
        val after = withBackend { backend ->
            val state = SceneViewState()
            open(backend) { Screen(state, draw = careless) }.use { ui -> frame(ui) }.also {
                state.release()
                Gl.gl.pixelStorei(GlConst.UNPACK_ALIGNMENT, 4)
            }
        }
        val alone = withBackend { backend -> open(backend) { Screen(null) { } }.use { ui -> frame(ui) } }

        assertEquals(blue.code(), after.at(100, 100), "the careless scene did draw")
        var lit = 0
        for (y in 40 until Gl.size) for (x in 190 until Gl.size) {
            assertEquals(alone.at(x, y), after.at(x, y), "the box and the label after the scene, at $x, $y")
            if (alone.at(x, y) == white.code()) lit++
        }
        assertTrue(lit > 50, "the label was drawn at all: $lit white pixels")
    }

    @Test
    fun `the context comes back in the documented state after a scene`() = withBackend { backend ->
        val canvas = backend.canvas
        val picture = canvas.scene(null, 16, 16) { target ->
            target.raw {
                Gl.gl.enable(GlConst.DEPTH_TEST)
                Gl.gl.enable(GlConst.CULL_FACE)
                Gl.gl.enable(GlConst.STENCIL_TEST)
                Gl.gl.enable(GlConst.SCISSOR_TEST)
                Gl.gl.activeTexture(GlConst.TEXTURE0 + 3)
            }
        }
        try {
            // What HostState.Leave promises the engine, the moment the scene pass hands the context back.
            assertFalse(Gl.gl.isEnabled(GlConst.DEPTH_TEST), "depth test")
            assertFalse(Gl.gl.isEnabled(GlConst.CULL_FACE), "culling")
            assertFalse(Gl.gl.isEnabled(GlConst.STENCIL_TEST), "stencil test")
            assertFalse(Gl.gl.isEnabled(GlConst.SCISSOR_TEST), "scissor")
            assertTrue(Gl.gl.isEnabled(GlConst.BLEND), "blending")
            assertEquals(GlConst.TEXTURE0, Gl.gl.getInteger(GlConst.ACTIVE_TEXTURE), "texture unit")
            assertEquals(0, Gl.gl.getInteger(GlConst.FRAMEBUFFER_BINDING), "the window's framebuffer")
            val viewport = IntArray(4).also { Gl.gl.getIntegers(GlConst.VIEWPORT, it) }
            assertEquals(listOf(0, 0, Gl.size, Gl.size), viewport.toList(), "the window's viewport")
        } finally {
            picture?.close()
        }
    }

    @Test
    fun `a click that asks for the scene again shows the new colour on the next frame`() = withBackend { backend ->
        val state = SceneViewState()
        open(backend) {
            var colour by remember { mutableStateOf(red) }
            Screen(state) { clear(colour) }
            Box(
                Modifier.offset(40f, 300f).size(80f, 40f).background(white)
                    .focusable(initial = true)
                    .clickable {
                        colour = if (colour == red) blue else red
                        state.invalidate()
                    }
                    .testTag("next"),
            )
        }.use { ui ->
            assertEquals(red.code(), frame(ui).at(100, 100))

            ui.click("next")
            assertEquals(blue.code(), frame(ui).at(100, 100), "after the click")
            assertEquals(2L, state.draws)

            assertEquals(blue.code(), frame(ui).at(100, 100), "and it stays")
            assertEquals(2L, state.draws, "a frame nobody asked to redraw renders nothing")

            ui.pad(GamepadButton.South)
            assertEquals(red.code(), frame(ui).at(100, 100), "after South on the pad")
            assertEquals(3L, state.draws)
        }
        state.release()
    }

    @Test
    fun `right to left moves the panel and does not mirror the picture`() = withBackend { backend ->
        val state = SceneViewState()
        open(backend) {
            ProvideLayoutDirection(LayoutDirection.Rtl) {
                Box(Modifier.size(Gl.size.toFloat(), Gl.size.toFloat())) {
                    SceneView(state, Modifier.size(panel.width, panel.height).testTag("scene")) {
                        clear(red)
                        raw {
                            Gl.gl.enable(GlConst.SCISSOR_TEST)
                            Gl.gl.scissor(0, 0, width / 2, height)
                            Gl.gl.clearColor(0f, 0f, 1f, 1f)
                            Gl.gl.clear(GlConst.COLOR_BUFFER_BIT)
                        }
                    }
                }
            }
        }.use { ui ->
            val pixels = frame(ui)
            val bounds = ui.node("scene").boundsInRoot
            assertEquals(Gl.size - panel.width, bounds.left, "the panel starts on the right")
            val y = bounds.centre.y.toInt()
            assertEquals(blue.code(), pixels.at((bounds.left + bounds.width / 4).toInt(), y), "the picture's left half is still on the left")
            assertEquals(red.code(), pixels.at((bounds.right - bounds.width / 4).toInt(), y))
        }
        state.release()
    }

    @Test
    fun `rounded corners clip the picture`() = withBackend { backend ->
        val state = SceneViewState()
        open(backend) { Screen(state, Modifier.clip(24f)) { clear(red) } }.use { ui ->
            val pixels = frame(ui)
            assertEquals(red.code(), pixels.at(100, 100), "the middle")
            assertEquals(0, pixels.at(41, 61), "the top left corner is cut off")
            assertEquals(0, pixels.at(158, 138), "and the bottom right")
        }
        state.release()
    }
}
