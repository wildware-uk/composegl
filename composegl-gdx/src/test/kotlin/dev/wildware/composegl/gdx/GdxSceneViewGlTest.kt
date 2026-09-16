package dev.wildware.composegl.gdx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Matrix4
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.GamepadButton
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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `SceneView` on a real GPU, through LibGDX, judged by the pixels.
 *
 * LibGDX hands a scene the game's own `SpriteBatch`, as it does for `raw`: opened on the picture,
 * and handed back with its projection and colour as they were. These are the proof that it lands in
 * the panel, the right way up; that depth works, through the game's own GL and through LibGDX's
 * `ModelBatch`; and that neither a widget drawn after the scene nor the game's own batch drawn after
 * the whole interface can tell a scene was there. Run on GL 2 and on GL 3.2 core.
 */
class GdxSceneViewGlTest {

    private val red = Colour.rgb(0xFF0000)
    private val blue = Colour.rgb(0x0000FF)
    private val green = Colour.rgb(0x00FF00)
    private val white = Colour.rgb(0xFFFFFF)

    private val panel = Rect.of(40f, 60f, 120f, 80f)

    /** A backend with a sprite batch of the game's, on the GL thread. */
    private fun <T> withBackend(block: (GdxBackend, SpriteBatch) -> T): T = Gl.render { backendHere(block) }

    /** The same, for a test already on the GL thread. */
    private fun <T> backendHere(block: (GdxBackend, SpriteBatch) -> T): T {
        val batch = SpriteBatch()
        val backend = GdxBackend(HeadlessFonts.registry(), batch)
        try {
            return block(backend, batch)
        } finally {
            backend.dispose()
            batch.dispose()
        }
    }

    private fun open(backend: GdxBackend, content: @Composable () -> Unit): UiTest =
        uiTest(Size(Gl.size.toFloat(), Gl.size.toFloat()), backend, content = content)

    /** The scene view where [panel] says, then a green box and a label drawn after it. */
    @Composable
    private fun Screen(state: SceneViewState?, draw: SceneDrawScope.() -> Unit) {
        Box(Modifier.size(Gl.size.toFloat(), Gl.size.toFloat())) {
            if (state != null) {
                SceneView(state, Modifier.offset(panel.left, panel.top).size(panel.width, panel.height).testTag("scene"), draw = draw)
            }
            Box(Modifier.offset(200f, 60f).size(80f, 80f).background(green))
            Text("After", Modifier.offset(200f, 160f), textStyle = TextStyle(family = "test", size = 16f), colour = white)
        }
    }

    /** One whole frame of [ui] over black, as `0xRRGGBB`, top row first. */
    private fun frame(ui: UiTest, then: () -> Unit = {}): IntArray {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        ui.render()
        then()
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gl.size, Gl.size)
        try {
            return IntArray(Gl.size * Gl.size) { at -> pixmap.getPixel(at % Gl.size, Gl.size - 1 - at / Gl.size) ushr 8 }
        } finally {
            pixmap.dispose()
        }
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * Gl.size + x]

    private fun Colour.code(): Int = (red shl 16) or (green shl 8) or blue

    private fun whiteTexture(): Texture {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply { setColor(Color.WHITE); fill() }
        return Texture(pixmap).also { pixmap.dispose() }
    }

    @Test
    fun `a scene's colour fills its panel and nothing outside it`() = withBackend { backend, _ ->
        val state = SceneViewState()
        open(backend) { Screen(state) { clear(red) } }.use { ui ->
            val pixels = frame(ui)

            assertEquals(120 to 80, state.width to state.height)
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
    fun `the game's sprite batch draws on the picture the right way up and comes back as it was`() = withBackend { backend, batch ->
        val texture = whiteTexture()
        val projection = Matrix4().setToOrtho2D(0f, 0f, 7f, 11f)
        batch.projectionMatrix = projection
        batch.color = Color.GREEN
        try {
            run {
                val state = SceneViewState()
                var lent: Any? = null
                open(backend) {
                    Screen(state) {
                        clear(red)
                        raw { handed ->
                            lent = handed
                            val sprites = handed as SpriteBatch
                            // LibGDX counts up from the bottom, and so does the projection it is handed.
                            sprites.color = Color.BLUE
                            sprites.draw(texture, 0f, 0f, width.toFloat(), height / 2f)
                        }
                    }
                }.use { ui ->
                    val pixels = frame(ui)
                    assertSame(batch, lent, "the batch the game gave the canvas")
                    assertEquals(red.code(), pixels.at(100, 70), "the top of the panel")
                    assertEquals(blue.code(), pixels.at(100, 130), "the bottom of the panel, where the batch drew")
                    assertArrayEquals(projection.values, batch.projectionMatrix.values, "the batch's projection is the game's again")
                    assertEquals(Color.GREEN, batch.color, "and so is its colour")
                    assertFalse(batch.isDrawing, "and it is closed")
                }
                state.release()
            }
        } finally {
            texture.dispose()
        }
    }

    @Test
    fun `the nearer triangle wins inside a scene view whichever order they are drawn in`() = withBackend { backend, _ ->
        val state = SceneViewState()
        var farFirst = true
        open(backend) {
            Screen(state) {
                clear(green)
                raw {
                    DepthScene(GdxGl).use { scene ->
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
        }
        state.release()
    }

    @Test
    fun `LibGDX's own model batch depth-tests inside a scene view`() = withBackend { backend, _ ->
        val models = ModelBatch()
        val builder = ModelBuilder()
        val attributes = (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        fun square(z: Float, colour: Color) = builder.createRect(
            -5f, -5f, z, 5f, -5f, z, 5f, 5f, z, -5f, 5f, z, 0f, 0f, 1f,
            Material(ColorAttribute.createDiffuse(colour)), attributes,
        )
        val near = square(-2f, Color.RED)
        val far = square(-4f, Color.BLUE)
        val camera = OrthographicCamera(4f, 4f).apply {
            this.near = 0.1f
            this.far = 10f
            update()
        }
        try {
            run {
                val state = SceneViewState()
                open(backend) {
                    Screen(state) {
                        clear(green)
                        raw {
                            // The game's usual 3D pass, inside the batch the canvas opened. ModelBatch
                            // sorts opaque things nearest first, so without depth the far one wins.
                            models.begin(camera)
                            models.render(ModelInstance(far))
                            models.render(ModelInstance(near))
                            models.end()
                        }
                    }
                }.use { ui ->
                    val pixels = frame(ui)
                    assertEquals(red.code(), pixels.at(100, 100), "the nearer square")
                    assertEquals(green.code(), pixels.at(240, 100), "the box after it")
                }
                state.release()
            }
        } finally {
            near.dispose()
            far.dispose()
            models.dispose()
        }
    }

    /** What a careless renderer leaves behind: depth test, culling, a scissor, a viewport, a texture unit. */
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
    fun `a widget and the game's own batch after a careless scene draw as they do with no scene`() = Gl.render {
        val texture = whiteTexture()
        /** The game's own drawing after the interface: a yellow square at the top right, in its own projection. */
        fun game(batch: SpriteBatch) {
            batch.projectionMatrix = Matrix4().setToOrtho2D(0f, 0f, Gl.size.toFloat(), Gl.size.toFloat())
            batch.begin()
            batch.color = Color.YELLOW
            batch.draw(texture, 320f, 320f, 40f, 40f)
            batch.end()
        }
        try {
            val after = backendHere { backend, batch ->
                val state = SceneViewState()
                open(backend) { Screen(state, careless) }.use { ui -> frame(ui) { game(batch) } }.also { state.release() }
            }
            val alone = backendHere { backend, batch -> open(backend) { Screen(null) { } }.use { ui -> frame(ui) { game(batch) } } }

            assertEquals(blue.code(), after.at(100, 100), "the careless scene did draw")
            var lit = 0
            for (y in 0 until Gl.size) for (x in 190 until Gl.size) {
                assertEquals(alone.at(x, y), after.at(x, y), "the box, the label and the game's square at $x, $y")
                if (alone.at(x, y) == white.code()) lit++
            }
            assertTrue(lit > 20, "the label was drawn at all: $lit white pixels")
            assertEquals(0xFFFF00, after.at(340, 60), "the game's own square")
        } finally {
            texture.dispose()
        }
    }

    @Test
    fun `a click or a pad press that asks for the scene again shows the new colour`() = withBackend { backend, _ ->
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
            assertEquals(blue.code(), frame(ui).at(100, 100), "and it stays")
            assertEquals(2L, state.draws, "a frame nobody asked to redraw renders nothing")

            ui.pad(GamepadButton.South)
            assertEquals(red.code(), frame(ui).at(100, 100), "after South on the pad")
            assertEquals(3L, state.draws)
        }
        state.release()
    }
}
