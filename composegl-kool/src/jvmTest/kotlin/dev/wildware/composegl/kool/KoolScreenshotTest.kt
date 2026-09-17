package dev.wildware.composegl.kool

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.Scene
import dev.wildware.composegl.testing.SceneArt
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.bevel
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.testing.scenes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The shared scenes, drawn inside a Kool frame, held to the raw OpenGL frontend's goldens.
 *
 * Every scene, text included. Kool's desktop OpenGL is LWJGL on the context Kool made current, and the
 * glyphs are the same stb_truetype ones, so nothing about a picture has a reason to differ from the
 * raw OpenGL frontend's: a scene that does has found an assumption this frontend leaked, or state
 * Kool left bound that the device did not take for itself.
 *
 * The scenes are drawn where a [ComposeGlScene] draws — while Kool renders a scene, into the
 * framebuffer Kool bound — and they are never written here as goldens of their own: those pictures
 * are the raw OpenGL frontend's to change.
 */
class KoolScreenshotTest {

    @TestFactory
    fun `the shared scenes match the raw OpenGL frontend's goldens`(): List<DynamicTest> = scenes().map { scene ->
        DynamicTest.dynamicTest(scene.name) {
            Goldens.assertMatches(scene.name, render(scene), directory = Desktop, updatable = false)
        }
    }

    private fun render(scene: Scene): BufferedImage {
        val fonts = StbFonts()
        fonts.register("body", TestFonts.dejaVu(), listOf(12, 16))
        val canvas = KoolCanvas(fonts)
        val art = bevelTexture()
        try {
            val pixels = KoolApp.render {
                canvas.begin(viewport)
                scene.draw(canvas, SceneArt(fonts = fonts, panel = NinePatch(KoolTexture(art), slice = Padding.all(8f))))
                canvas.end()
                KoolApp.readPixels(SceneSize, SceneSize)
            }
            val image = imageOf(SceneSize, SceneSize) { x, y -> pixels[y * SceneSize + x] }
            shots?.let { dir -> ImageIO.write(image, "png", File(dir, "${scene.name}.png")) }
            return image
        } finally {
            KoolApp.render {
                canvas.close()
                art.release()
            }
            fonts.close()
        }
    }

    /** The shared nine-patch art as a Kool texture, sampled a texel at a time as the raw frontend samples it. */
    private fun bevelTexture(): Texture2d {
        val image = bevel()
        val bytes = Uint8Buffer(image.pixels.size)
        image.pixels.forEachIndexed { index, byte -> bytes[index] = byte.toUByte() }
        return Texture2d(
            BufferedImageData2d(bytes, image.width, image.height, TexFormat.RGBA),
            mipMapping = MipMapping.Off,
            samplerSettings = SamplerSettings().nearest().clamped(),
            name = "bevel",
        )
    }

    /** The scene at its own size in the bottom-left corner of Kool's framebuffer, which is the part read back. */
    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private companion object {
        /** The raw OpenGL frontend's goldens. */
        val Desktop = File("../composegl-lwjgl3/src/test/resources/goldens")

        /** Where to leave each picture for a person to look at, when asked. */
        val shots: File? = System.getenv("COMPOSEGL_KOOL_SHOTS")?.let { File(it, "scenes").apply { mkdirs() } }
    }
}
