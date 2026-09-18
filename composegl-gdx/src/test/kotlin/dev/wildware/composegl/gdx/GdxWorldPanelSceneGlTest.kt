package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.world.WorldPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `SceneView` inside a [WorldPanel], through LibGDX on a real GPU, judged by the pixels of the
 * panel's own [GdxRenderTarget] — not the window's.
 *
 * The panel renders its scenes before it opens the target's frame, so the scene's colour lands in
 * the target where the scene view was laid out, and a widget drawn after a scene that left its GL
 * state lying about draws exactly as it would with no scene at all. Run on GL 2 and on GL 3.2 core.
 */
class GdxWorldPanelSceneGlTest {

    private val width = 240
    private val height = 160

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)
    private val black = Colour.rgb(0x000000)

    /**
     * A panel with a scene view at (20, 30) of 80 by 60, then a green box and a label drawn after it,
     * drawn into a target of its own over black. The target's pixels, top row first, as `0xRRGGBB`.
     * [whileShown] runs before the panel is closed, which gives the scene's picture back.
     */
    private fun drawPanel(
        state: SceneViewState?,
        whileShown: () -> Unit = {},
        draw: SceneDrawScope.() -> Unit,
    ): IntArray = Gl.render {
        val batch = SpriteBatch()
        val backend = GdxBackend(HeadlessFonts.registry(), batch)
        val canvas = backend.canvas
        val target = GdxRenderTarget(width, height)
        val panel = WorldPanel(width.toFloat(), height.toFloat())
        try {
            panel.setContent {
                ProvideFonts(backend.fonts) {
                    Box(Modifier.size(width.toFloat(), height.toFloat())) {
                        if (state != null) SceneView(state, Modifier.offset(20f, 30f).size(80f, 60f), draw = draw)
                        Box(Modifier.offset(140f, 30f).size(60f, 60f).background(green))
                        Text("After", Modifier.offset(130f, 110f), textStyle = TextStyle(family = "test", size = 16f), colour = white)
                    }
                }
            }
            var nanos = 0L
            repeat(3) {
                nanos += 16_666_667L
                if (panel.needsRedraw(nanos)) panel.draw(canvas) { tree -> target.draw(canvas, black) { tree() } }
            }
            whileShown()
            target.read {
                val pixmap = Pixmap.createFromFrameBuffer(0, 0, width, height)
                try {
                    // Read the way OpenGL stores it, the bottom row first.
                    IntArray(width * height) { at -> pixmap.getPixel(at % width, height - 1 - at / width) ushr 8 }
                } finally {
                    pixmap.dispose()
                }
            }
        } finally {
            panel.close()
            target.dispose()
            backend.dispose()
            batch.dispose()
        }
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * width + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    @Test
    fun `a scene view inside a world panel shows up in the panel's own target`() {
        val state = SceneViewState()
        var size = 0 to 0
        val pixels = drawPanel(state, whileShown = { size = state.width to state.height }) { clear(red) }

        assertEquals(1L, state.draws, "the panel's scene pass rendered it once")
        assertEquals(80 to 60, size, "at the panel's own pixels")
        for (y in 31 until 89) for (x in 21 until 99) {
            assertEquals(red.code(), pixels.at(x, y), "inside the scene view at $x, $y")
        }
        assertEquals(black.code(), pixels.at(10, 10), "outside it the target is as cleared")
        assertEquals(black.code(), pixels.at(60, 100), "and below it")
        assertEquals(green.code(), pixels.at(170, 60), "the box after it")
    }

    @Test
    fun `the scene in a world panel is the right way up`() {
        val state = SceneViewState()
        val pixels = drawPanel(state) {
            clear(red)
            raw {
                // OpenGL counts rows up from the bottom: this is the bottom half, in blue.
                Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
                Gdx.gl.glScissor(0, 0, width, height / 2)
                Gdx.gl.glClearColor(0f, 0f, 1f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            }
        }
        assertEquals(red.code(), pixels.at(60, 40), "the top of the scene view")
        assertEquals(blue.code(), pixels.at(60, 80), "the bottom of it")
    }

    /** What a careless renderer leaves behind, as in [GdxSceneViewGlTest]. */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw {
            DepthScene(GdxGl).triangle(depth = 0f, colour = blue)
            Gdx.gl.glUseProgram(0)
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
            Gdx.gl.glEnable(GL20.GL_CULL_FACE)
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(0, 0, 1, 1)
            Gdx.gl.glViewport(0, 0, 1, 1)
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE3)
        }
    }

    @Test
    fun `a widget drawn after a careless scene in a world panel is exactly what it is with no scene`() {
        val after = drawPanel(SceneViewState(), draw = careless)
        val alone = drawPanel(null) { }

        assertEquals(blue.code(), after.at(60, 60), "the careless scene did draw")
        var lit = 0
        for (y in 0 until height) for (x in 110 until width) {
            assertEquals(alone.at(x, y), after.at(x, y), "the box and the label after the scene, at $x, $y")
            if (alone.at(x, y) == white.code()) lit++
        }
        assertTrue(lit > 20, "the label was drawn at all: $lit white pixels")
    }
}
