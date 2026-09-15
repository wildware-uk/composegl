package dev.wildware.composegl.gdx.screenshot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.composegl.gdx.Gl
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.Scene
import dev.wildware.composegl.testing.SceneArt
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.testing.scenes
import dev.wildware.composegl.gdx.GdxCanvas
import dev.wildware.composegl.gdx.GdxFonts
import dev.wildware.composegl.gdx.ninePatch
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.awt.image.BufferedImage
import java.io.File

/**
 * The renderer, compared against pictures.
 *
 * One test per scene, so a failure names the scene rather than "screenshots". They skip themselves
 * when there is no display; CI gives them one with Xvfb and keeps whatever `build/screenshots`
 * ends up holding.
 *
 * Two checks, as the KorGE and WebGL backends have. Every scene against this backend's own goldens.
 * And the scenes with no text in them against the raw OpenGL backend's goldens: both backends draw
 * with composegl-render, so shapes, clips and pictures have no reason to differ, and a scene that
 * does has found renderer code living in one backend. Text is left out only because FreeType and
 * stb_truetype never agree on a glyph.
 */
class ScreenshotTest {

    @TestFactory
    fun goldens(): List<DynamicTest> = scenes().map { scene ->
        DynamicTest.dynamicTest(scene.name) {
            Goldens.assertMatches(scene.name, render(scene))
        }
    }

    @TestFactory
    fun `the scenes without text match the raw OpenGL backend's goldens`(): List<DynamicTest> =
        scenes().filter { it.name in WithoutText }.map { scene ->
            DynamicTest.dynamicTest(scene.name) {
                Goldens.assertMatches(scene.name, render(scene), directory = Desktop, updatable = false)
            }
        }

    private fun render(scene: Scene): BufferedImage = Gl.render {
        val fonts = GdxFonts()
        fonts.registerTrueType("body", Gdx.files.internal("fonts/DejaVuSans.ttf"), listOf(12, 16))
        val canvas = GdxCanvas(atlas = fonts.atlas)
        val pixmap = bevel()
        val texture = Texture(pixmap)
        try {
            val art = SceneArt(
                fonts = fonts,
                panel = TextureRegion(texture).ninePatch(slice = Padding.all(8f)),
            )

            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            scene.draw(canvas, art)
            canvas.end()

            Pixmap.createFromFrameBuffer(0, 0, SceneSize, SceneSize).let { frame ->
                // OpenGL hands back the bottom row first.
                imageOf(SceneSize, SceneSize) { x, y -> frame.getPixel(x, SceneSize - 1 - y) ushr 8 }
                    .also { frame.dispose() }
            }
        } finally {
            canvas.dispose()
            texture.dispose()
            pixmap.dispose()
            fonts.dispose()
        }
    }

    /**
     * The shared nine-patch art, as a LibGDX picture.
     *
     * The bytes come from the testing module so that both backends draw exactly the same art and
     * the two sets of goldens differ only where the two renderers do.
     */
    private fun bevel(): Pixmap {
        val art = dev.wildware.composegl.testing.bevel()
        val pixmap = Pixmap(art.width, art.height, Pixmap.Format.RGBA8888)
        pixmap.pixels.put(art.pixels).flip()
        return pixmap
    }

    /**
     * A window bigger than the scene, with the scene in its bottom-left corner.
     *
     * The shared GL context is one size for every test, so the scene is drawn at its own size
     * inside it and only that square is read back.
     */
    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private companion object {
        /** The raw OpenGL backend's goldens, which the text-free scenes are held to as well. */
        val Desktop = File("../composegl-lwjgl3/src/test/resources/goldens")

        /** The same list the KorGE and WebGL backends compare: the scenes that never call `text`. */
        val WithoutText = setOf(
            "borders", "corners", "gradients", "nine-patch", "particles", "per-corner", "rotation-and-glow", "shadow",
        )
    }
}
