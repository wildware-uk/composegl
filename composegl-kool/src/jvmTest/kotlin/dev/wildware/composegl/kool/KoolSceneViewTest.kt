package dev.wildware.composegl.kool

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.opengl.GL11

/**
 * A `SceneView` inside a Kool frame, judged by the pixels.
 *
 * The Kool frontend hands a scene's `raw` a [KoolFrame] with the picture bound, and hands Kool its GL
 * state back. Kool remembers the state it last set — the program it bound, whether it culls, whether it
 * writes depth — and skips setting what it believes is already set. So the proof that matters most is
 * Kool's own drawing: every frame of these tests Kool draws a magenta square after the interface, and
 * it has to come out exactly as it does with no scene at all, however carelessly the scene left the
 * context.
 */
class KoolSceneViewTest {

    private val size = KoolApp.size

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)
    private val magenta = 0xFF00FF

    /** Where the scene view is laid out. */
    private val panel = Rect.of(40f, 60f, 120f, 80f)

    private fun backend() = KoolBackend(StbFonts().apply { register("body", TestFonts.dejaVu(), listOf(24)) })

    private fun open(backend: KoolBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(size.toFloat(), size.toFloat()), backend, content = content)

    /** Closes the backend's GL objects on Kool's render thread, and the fonts. */
    private fun close(backend: KoolBackend) {
        KoolApp.render { backend.close() }
        backend.fonts.close()
    }

    /** The scene view where [panel] says, then a green box and a label drawn after it. */
    @Composable
    private fun Screen(state: SceneViewState?, draw: SceneDrawScope.() -> Unit) {
        Box(Modifier.size(size.toFloat(), size.toFloat())) {
            if (state != null) {
                SceneView(state, Modifier.offset(panel.left, panel.top).size(panel.width, panel.height).testTag("scene"), draw = draw)
            }
            Box(Modifier.offset(200f, 60f).size(80f, 80f).background(green))
            Text("After", Modifier.offset(200f, 160f), textStyle = TextStyle(family = "body", size = 24f), colour = white)
        }
    }

    /**
     * One whole frame: [ui] drawn while Kool renders a scene, then Kool's own square, then the
     * framebuffer read back, top row first, as `0xRRGGBB`.
     */
    private fun frame(ui: UiTest): IntArray = KoolApp.frame({ ui.render() }) { _, _ -> KoolApp.readPixels() }

    private fun IntArray.at(x: Int, y: Int) = this[y * KoolApp.size + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    @Test
    fun `it renders scenes and asks the driver for their size only where Kool's context is`() {
        val canvas = KoolCanvas()
        assertTrue(canvas.drawsScenes)
        assertEquals(Int.MAX_VALUE, canvas.maxSceneSize, "a thread with no context: nothing to ask, and no throw")
        val (asked, driver) = KoolApp.render { canvas.maxSceneSize to GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) }
        assertEquals(driver, asked, "on Kool's render thread it is the driver's own limit")
        assertTrue(asked in 1 until Int.MAX_VALUE)
    }

    @Test
    fun `a scene's colour fills its panel and nothing outside it`() {
        val backend = backend()
        val state = SceneViewState()
        try {
            open(backend) { Screen(state) { clear(red) } }.use { ui ->
                val pixels = frame(ui)
                assertEquals(1L, state.draws)
                assertEquals(120 to 80, state.width to state.height, "the picture is the panel's real pixels")
                for (y in 61 until 139) for (x in 41 until 159) {
                    assertEquals(red.code(), pixels.at(x, y), "inside the panel at $x, $y")
                }
                for (y in 0 until size) for (x in 0 until size) {
                    val inside = x in 39..160 && y in 59..140
                    if (!inside) assertNotEquals(red.code(), pixels.at(x, y), "the scene leaked out of its panel at $x, $y")
                }
            }
        } finally {
            state.release()
            close(backend)
        }
    }

    @Test
    fun `raw is handed a Kool frame with the picture bound the right way up`() {
        val backend = backend()
        val state = SceneViewState()
        var handed: Any? = null
        var boundInRaw = -1
        try {
            open(backend) {
                Screen(state) {
                    clear(red)
                    raw { frame ->
                        handed = frame
                        boundInRaw = GL11.glGetInteger(GlConst.FRAMEBUFFER_BINDING)
                        // OpenGL counts rows up from the bottom: this is the bottom half, in blue.
                        KoolGl.enable(GlConst.SCISSOR_TEST)
                        KoolGl.scissor(0, 0, width, height / 2)
                        KoolGl.clearColor(0f, 0f, 1f, 1f)
                        KoolGl.clear(GlConst.COLOR_BUFFER_BIT)
                    }
                }
            }.use { ui ->
                var koolFramebuffer = -1
                val pixels = KoolApp.frame({
                    koolFramebuffer = GL11.glGetInteger(GlConst.FRAMEBUFFER_BINDING)
                    ui.render()
                }) { _, _ -> KoolApp.readPixels() }

                val frame = handed as KoolFrame
                assertEquals(KoolApp.ctx, frame.ctx, "Kool's own context")
                assertEquals(Size(120f, 80f), frame.viewport.design, "the picture's size, one to one")
                assertTrue(boundInRaw != koolFramebuffer, "the picture was bound in raw, not Kool's framebuffer ($boundInRaw)")
                assertEquals(red.code(), pixels.at(100, 70), "the top of the panel")
                assertEquals(blue.code(), pixels.at(100, 130), "the bottom of the panel")
                assertEquals(green.code(), pixels.at(240, 100), "the box after it, whole: the scene's scissor went no further")
            }
        } finally {
            state.release()
            close(backend)
        }
    }

    @Test
    fun `the nearer triangle wins inside a scene view whichever order they are drawn in`() {
        val backend = backend()
        val state = SceneViewState()
        var farFirst = true
        try {
            open(backend) {
                Screen(state) {
                    clear(green)
                    raw {
                        KoolDepthScene().use { scene ->
                            if (farFirst) {
                                scene.triangle(depth = 0.8f, colour = blue)
                                scene.triangle(depth = 0.2f, colour = red)
                            } else {
                                scene.triangle(depth = 0.2f, colour = red)
                                scene.triangle(depth = 0.8f, colour = blue)
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
        } finally {
            state.release()
            close(backend)
        }
    }

    /**
     * Everything a careless renderer leaves behind, all of it behind Kool's back: its own program, a
     * closed colour mask, every face culled, depth writing off, depth testing on and failing everything,
     * a thick line, a scissor, a tiny viewport and another texture unit. Kool believes none of it and sets none of it back itself.
     */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw {
            val scene = KoolDepthScene()
            scene.triangle(depth = 0.5f, colour = blue)
            KoolGl.useProgram(0)
            KoolGl.colorMask(red = false, green = false, blue = false, alpha = false)
            GL11.glCullFace(GL11.GL_FRONT_AND_BACK)
            KoolGl.enable(GlConst.CULL_FACE)
            KoolGl.depthMask(false)
            GL11.glDepthFunc(GL11.GL_NEVER)
            GL11.glLineWidth(3f)
            KoolGl.enable(GlConst.DEPTH_TEST)
            KoolGl.enable(GlConst.SCISSOR_TEST)
            KoolGl.scissor(0, 0, 1, 1)
            KoolGl.viewport(0, 0, 1, 1)
            KoolGl.activeTexture(GlConst.TEXTURE0 + 3)
        }
    }

    @Test
    fun `Kool's own drawing and a widget after a careless scene come out as they do with no scene`() {
        fun draw(state: SceneViewState?): IntArray {
            val backend = backend()
            try {
                return open(backend) { Screen(state, careless) }.use { ui -> frame(ui) }
            } finally {
                state?.release()
                close(backend)
            }
        }

        val withScene = draw(SceneViewState())
        val alone = draw(null)

        assertEquals(blue.code(), withScene.at(100, 100), "the careless scene did draw")
        val square = KoolApp.KoolSquare
        assertEquals(magenta, alone.at(square.x + 40, square.y + 40), "Kool's square, with no scene")
        var lit = 0
        for (y in 0 until size) for (x in 190 until size) {
            assertEquals(alone.at(x, y), withScene.at(x, y), "the box, the label and Kool's square at $x, $y")
            if (alone.at(x, y) == white.code()) lit++
        }
        assertTrue(lit > 50, "the label was drawn at all: $lit white pixels")
        // And after the frame too: what Kool left is what Kool set.
        KoolApp.render {
            assertTrue(GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK), "Kool's colour mask")
            assertNotEquals(GL11.GL_FRONT_AND_BACK, GL11.glGetInteger(GL11.GL_CULL_FACE_MODE), "Kool's culled faces")
            assertNotEquals(GL11.GL_NEVER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC), "Kool's depth comparison")
        }
    }

    @Test
    fun `a click that asks for the scene again shows the new colour on the next frame`() {
        val backend = backend()
        val state = SceneViewState()
        try {
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
            }
        } finally {
            state.release()
            close(backend)
        }
    }
}
