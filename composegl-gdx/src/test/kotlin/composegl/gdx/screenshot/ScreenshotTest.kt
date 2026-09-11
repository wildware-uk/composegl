package composegl.gdx.screenshot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import composegl.gdx.Gl
import composegl.gdx.GdxCanvas
import composegl.gdx.GdxFonts
import composegl.gdx.ninePatch
import composegl.ui.geometry.Size
import composegl.ui.layout.Padding
import composegl.ui.layout.ScalePolicy
import composegl.ui.layout.Viewport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.awt.image.BufferedImage

/**
 * The renderer, compared against pictures.
 *
 * One test per scene, so a failure names the scene rather than "screenshots". They skip themselves
 * when there is no display; CI gives them one with Xvfb and keeps whatever `build/screenshots`
 * ends up holding.
 */
class ScreenshotTest {

    @TestFactory
    fun goldens(): List<DynamicTest> = scenes().map { scene ->
        DynamicTest.dynamicTest(scene.name) {
            Goldens.assertMatches(scene.name, render(scene))
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

            Pixmap.createFromFrameBuffer(0, 0, SceneSize, SceneSize).let {
                val image = it.toImage()
                it.dispose()
                image
            }
        } finally {
            canvas.dispose()
            texture.dispose()
            pixmap.dispose()
            fonts.dispose()
        }
    }

    /**
     * The nine-patch art, drawn in code rather than loaded.
     *
     * A picture in the repository is a picture that can be edited by accident; twenty-four pixels
     * of arithmetic cannot be.
     */
    private fun bevel(): Pixmap = Pixmap(24, 24, Pixmap.Format.RGBA8888).apply {
        setColor(0.12f, 0.16f, 0.22f, 1f)
        fill()
        setColor(0.30f, 0.76f, 1f, 1f)
        drawRectangle(0, 0, 24, 24)
        setColor(0.30f, 0.76f, 1f, 0.25f)
        // A cross through the middle eight pixels, so tiling and stretching look different.
        fillRectangle(8, 11, 8, 2)
        fillRectangle(11, 8, 2, 8)
        // The corners, marked, so a corner drawn from the wrong place is unmistakable.
        setColor(1f, 0.85f, 0.3f, 1f)
        fillRectangle(2, 2, 3, 3)
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
}
