package dev.wildware.composegl.kool

import android.opengl.GLES30
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.SceneViewState
import dev.wildware.composegl.ui.widget.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kool frontend on Android, judged by the pixels of Kool's own `GLSurfaceView` on a device.
 *
 * The same promises the desktop tests hold the frontend to: the screen is drawn inside Kool's frame,
 * over what Kool drew before it; a finger on Kool's view is a click; and Kool's own drawing after a
 * careless `raw` block comes out exactly as it does with none, because Kool's OpenGL ES state was
 * handed back.
 */
@RunWith(AndroidJUnit4::class)
class KoolAndroidTest {

    private val size = KoolDevice.size
    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)
    private val magenta = 0xFF00FF

    private fun fonts() = AndroidFonts().apply { register("body", KoolDevice.dejaVu(), listOf(24)) }

    private fun IntArray.at(x: Int, y: Int) = this[y * KoolDevice.size + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    /** Lets go of a backend's GL objects on Kool's render thread, then its fonts. */
    private fun close(backend: KoolBackend) {
        KoolDevice.render { backend.close() }
        backend.fonts.close()
    }

    /** A ComposeGL screen added to Kool the way a game adds one, taken out again after [block]. */
    private fun <T> screen(content: @Composable () -> Unit, block: (ComposeGlScene) -> T): T {
        KoolDevice.start()
        val backend = KoolBackend(fonts())
        val ui = ComposeGlScene(backend, Size(size.toFloat(), size.toFloat()))
        ui.setContent(content)
        KoolDevice.addScene(ui.scene)
        try {
            return block(ui)
        } finally {
            KoolDevice.removeScene(ui.scene)
            ui.close()
            close(backend)
        }
    }

    @Test
    fun a_ComposeGL_screen_is_drawn_inside_Kools_frame_over_what_Kool_drew_before_it() {
        screen({
            Box(Modifier.offset(40f, 60f).size(120f, 80f).background(red))
            Text("Kool", Modifier.offset(40f, 200f), textStyle = TextStyle(family = "body", size = 24f), colour = white)
        }) {
            val pixels = KoolDevice.capture()
            KoolDevice.save("screen", size, size, pixels)
            assertEquals("inside the box", red.code(), pixels.at(100, 100))
            assertEquals("Kool's clear, where the screen drew nothing", 0x000000, pixels.at(20, 300))
            assertEquals("Kool's square, drawn before the screen and not covered by it", magenta, pixels.at(340, 60))
            var lit = 0
            for (y in 195 until 240) for (x in 35 until 120) if (pixels.at(x, y) == white.code()) lit++
            assertTrue("the label was drawn with Android's glyphs: $lit white pixels", lit > 50)
        }
    }

    @Test
    fun a_finger_on_Kools_view_clicks_what_it_lands_on() {
        var clicks = 0
        var colour by mutableStateOf(red)
        screen({
            Box(
                Modifier.offset(40f, 60f).size(120f, 80f).background(colour).clickable {
                    clicks++
                    colour = blue
                },
            )
        }) {
            val before = KoolDevice.capture()
            KoolDevice.save("pointer-before", size, size, before)
            assertEquals(red.code(), before.at(100, 100))

            KoolDevice.touch(MotionEvent.ACTION_DOWN, 100f, 100f)
            // Held for two of Kool's frames. Kool 0.19.0 itself reports no press for a finger that lifts
            // within one frame of landing: its own pointer state never shows the button down.
            repeat(2) { KoolDevice.capture() }
            KoolDevice.touch(MotionEvent.ACTION_UP, 100f, 100f)
            // Kool reads the finger in one frame and the screen takes it in the next render.
            repeat(3) { KoolDevice.capture() }

            val after = KoolDevice.capture()
            KoolDevice.save("pointer-after", size, size, after)
            assertEquals("one tap, one click", 1, clicks)
            assertEquals("the box after the tap", blue.code(), after.at(100, 100))
        }
    }

    @Test
    fun a_finger_that_misses_clicks_nothing() {
        var clicks = 0
        screen({ Box(Modifier.offset(40f, 60f).size(120f, 80f).background(red).clickable { clicks++ }) }) {
            KoolDevice.capture()
            KoolDevice.touch(MotionEvent.ACTION_DOWN, 300f, 300f)
            repeat(2) { KoolDevice.capture() }
            KoolDevice.touch(MotionEvent.ACTION_UP, 300f, 300f)
            repeat(4) { KoolDevice.capture() }
            assertEquals(0, clicks)
        }
    }

    @Test
    fun it_renders_scenes_and_asks_the_driver_for_their_size_only_where_Kools_context_is() {
        val canvas = KoolCanvas()
        assertTrue(canvas.drawsScenes)
        assertEquals("a thread with no context: nothing to ask, and no throw", Int.MAX_VALUE, canvas.maxSceneSize)
        val (asked, driver) = KoolDevice.render {
            canvas.maxSceneSize to IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        }
        assertEquals("on Kool's render thread it is the driver's own limit", driver, asked)
        assertTrue(asked in 1 until Int.MAX_VALUE)
    }

    /**
     * Everything a careless renderer leaves behind, behind Kool's back: no program, a closed colour mask,
     * every face culled, depth writing off, depth testing on and failing everything, a thick line, a
     * scissor, a tiny viewport and another texture unit.
     */
    private val careless: SceneDrawScope.() -> Unit = {
        clear(red)
        raw {
            KoolGl.useProgram(0)
            KoolGl.colorMask(red = false, green = false, blue = false, alpha = false)
            GLES30.glCullFace(GLES30.GL_FRONT_AND_BACK)
            KoolGl.enable(GlConst.CULL_FACE)
            KoolGl.depthMask(false)
            GLES30.glDepthFunc(GLES30.GL_NEVER)
            GLES30.glLineWidth(3f)
            KoolGl.enable(GlConst.DEPTH_TEST)
            KoolGl.enable(GlConst.SCISSOR_TEST)
            KoolGl.scissor(0, 0, 1, 1)
            KoolGl.viewport(0, 0, 1, 1)
            KoolGl.activeTexture(GlConst.TEXTURE0 + 3)
        }
    }

    @Composable
    private fun Careless(state: SceneViewState?) {
        Box(Modifier.size(size.toFloat(), size.toFloat())) {
            if (state != null) SceneView(state, Modifier.offset(40f, 60f).size(120f, 80f), draw = careless)
            Box(Modifier.offset(200f, 160f).size(80f, 80f).background(green))
            Text("After", Modifier.offset(200f, 260f), textStyle = TextStyle(family = "body", size = 24f), colour = white)
        }
    }

    @Test
    fun Kools_own_drawing_and_a_widget_after_a_careless_scene_come_out_as_they_do_with_no_scene() {
        fun draw(state: SceneViewState?): IntArray {
            val backend = KoolBackend(fonts())
            try {
                return uiTest(Size(size.toFloat(), size.toFloat()), backend) { Careless(state) }.use { ui ->
                    // Two frames: Kool draws its square after the interface in the first, and again in the second.
                    KoolDevice.frame({ ui.render() }) { _, _ -> }
                    KoolDevice.frame({ ui.render() }) { _, _ -> KoolDevice.readPixels() }
                }
            } finally {
                state?.release()
                close(backend)
            }
        }

        val withScene = draw(SceneViewState())
        val alone = draw(null)
        KoolDevice.save("careless-scene", size, size, withScene)

        assertEquals("the careless scene did clear its panel", red.code(), withScene.at(100, 100))
        assertEquals("Kool's square, with no scene", magenta, alone.at(340, 60))
        var lit = 0
        for (y in 0 until size) for (x in 190 until size) {
            assertEquals("the box, the label and Kool's square at $x, $y", alone.at(x, y), withScene.at(x, y))
            if (alone.at(x, y) == white.code()) lit++
        }
        assertTrue("the label was drawn at all: $lit white pixels", lit > 50)
        KoolDevice.render {
            val mask = BooleanArray(4).also { GLES30.glGetBooleanv(GLES30.GL_COLOR_WRITEMASK, it, 0) }
            assertTrue("Kool's colour mask", mask.all { it })
            val cull = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_CULL_FACE_MODE, it, 0) }[0]
            assertNotEquals("Kool's culled faces", GLES30.GL_FRONT_AND_BACK, cull)
            val depth = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_DEPTH_FUNC, it, 0) }[0]
            assertNotEquals("Kool's depth comparison", GLES30.GL_NEVER, depth)
        }
    }
}
