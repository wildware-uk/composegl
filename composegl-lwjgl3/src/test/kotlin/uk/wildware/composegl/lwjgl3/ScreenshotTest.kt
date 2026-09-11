package uk.wildware.composegl.lwjgl3

import uk.wildware.composegl.testing.Goldens
import uk.wildware.composegl.testing.Scene
import uk.wildware.composegl.testing.SceneArt
import uk.wildware.composegl.testing.SceneSize
import uk.wildware.composegl.testing.bevel
import uk.wildware.composegl.testing.imageOf
import uk.wildware.composegl.testing.scenes
import uk.wildware.composegl.ui.geometry.Size
import uk.wildware.composegl.ui.graphics.NinePatch
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.layout.ScalePolicy
import uk.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage

/**
 * The same scenes the LibGDX backend draws, drawn with no LibGDX anywhere.
 *
 * These have their own goldens rather than sharing that backend's, because FreeType and
 * stb_truetype will never agree on a glyph pixel for pixel and pretending otherwise would mean a
 * tolerance so loose it caught nothing. What the two sets are for is the comparison a person makes
 * by looking at them: shapes, positions and clipping must match, and where they do not, the
 * toolkit has leaked an assumption into one of the two backends.
 *
 * They skip themselves when there is no display; CI gives them one with Xvfb and keeps whatever
 * `build/screenshots` ends up holding.
 */
class ScreenshotTest {

    @TestFactory
    fun goldens(): List<DynamicTest> = scenes().map { scene ->
        DynamicTest.dynamicTest(scene.name) {
            Goldens.assertMatches(scene.name, render(scene))
        }
    }

    private fun render(scene: Scene): BufferedImage = Gl.render {
        val fonts = StbFonts()
        fonts.register("body", javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.readBytes(), listOf(12, 16))
        val canvas = GlCanvas(fonts)
        val bevel = bevel()
        val art = GlTexture.rgba(bevel.width, bevel.height, bevel.pixels, smooth = false)
        try {
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            canvas.begin(viewport)
            scene.draw(canvas, SceneArt(fonts = fonts, panel = NinePatch(art, slice = Padding.all(8f))))
            canvas.end()

            val pixels = Gl.readPixels(SceneSize, SceneSize)
            imageOf(SceneSize, SceneSize) { x, y -> pixels[y * SceneSize + x] }
        } finally {
            art.close()
            canvas.close()
            fonts.close()
        }
    }

    /**
     * A window bigger than the scene, with the scene in its bottom-left corner.
     *
     * The shared context is one size for every test, so the scene is drawn at its own size inside
     * it and only that square is read back.
     */
    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )
}
