package dev.wildware.composegl.korge

import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.world.WorldPanel
import korlibs.graphics.gl.AGOpengl
import korlibs.image.bitmap.Bitmap32
import korlibs.korge.render.RenderContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `SceneView` inside a [WorldPanel], inside a KorGE render, judged by the pixels of the panel's
 * own [KorgeRenderTarget] — not the window's.
 *
 * KorGE renders a scene through the frame's render context, so the canvas is handed it for the
 * panel's draw, as `ComposeGlView` does for a screen.
 */
class KorgeWorldPanelSceneTest {

    private val width = 240
    private val height = 160

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val black = Colour.rgb(0x000000)

    /**
     * A panel with a scene view at (20, 30) of 80 by 60 and a green box drawn after it, drawn into a
     * target of its own over black, read back top row first.
     */
    private fun drawPanel(state: SceneViewState?, whileShown: () -> Unit = {}, draw: SceneDrawScope.() -> Unit): Bitmap32 {
        val canvas = KorgeCanvas()
        val target = KorgeRenderTarget(width, height)
        val panel = WorldPanel(width.toFloat(), height.toFloat())
        try {
            panel.setContent {
                Box(Modifier.size(width.toFloat(), height.toFloat())) {
                    if (state != null) SceneView(state, Modifier.offset(20f, 30f).size(80f, 60f), draw = draw)
                    Box(Modifier.offset(140f, 30f).size(60f, 60f).background(green))
                }
            }
            var nanos = 0L
            return KorgeGl.render { ctx ->
                canvas.renderContext = ctx
                try {
                    repeat(3) {
                        nanos += 16_666_667L
                        if (panel.needsRedraw(nanos)) panel.draw(canvas) { tree -> target.draw(canvas, ctx, black) { tree() } }
                    }
                } finally {
                    canvas.renderContext = null
                }
                whileShown()
                target.read(ctx)
            }
        } finally {
            panel.close()
            target.close()
            canvas.close()
        }
    }

    private fun Bitmap32.code(x: Int, y: Int): Int = getRgbaRaw(x, y).let { (it.r shl 16) or (it.g shl 8) or it.b }

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    @Test
    fun `a scene view inside a world panel shows up in the panel's own target`() {
        val state = SceneViewState()
        var size = 0 to 0
        val pixels = drawPanel(state, whileShown = { size = state.width to state.height }) { clear(red) }

        assertEquals(1L, state.draws, "the panel's scene pass rendered it once")
        assertEquals(80 to 60, size, "at the panel's own pixels")
        for (y in 31 until 89) for (x in 21 until 99) {
            assertEquals(red.code(), pixels.code(x, y), "inside the scene view at $x, $y")
        }
        assertEquals(black.code(), pixels.code(10, 10), "outside it the target is as cleared")
        assertEquals(green.code(), pixels.code(170, 60), "the box after it")
    }

    /** What a careless renderer leaves behind, through KorGE's GL behind KorGE's back. */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw { handed ->
            val gl = ((handed as RenderContext).ag as AGOpengl).gl
            KorgeDepthScene(KorgeKmlGl().also { it.gl = gl }).triangle(depth = 0f, colour = blue)
            gl.enable(GlConst.DEPTH_TEST)
            gl.enable(GlConst.CULL_FACE)
            gl.enable(GlConst.SCISSOR_TEST)
            gl.scissor(0, 0, 1, 1)
            gl.viewport(0, 0, 1, 1)
            gl.activeTexture(GlConst.TEXTURE0 + 3)
        }
    }

    @Test
    fun `a widget drawn after a careless scene in a world panel is exactly what it is with no scene`() {
        val after = drawPanel(SceneViewState(), draw = careless)
        val alone = drawPanel(null) { }

        assertEquals(blue.code(), after.code(60, 60), "the careless scene did draw")
        for (y in 0 until height) for (x in 110 until width) {
            assertEquals(alone.code(x, y), after.code(x, y), "the box after the scene, at $x, $y")
        }
        assertEquals(green.code(), after.code(170, 60))
    }
}
