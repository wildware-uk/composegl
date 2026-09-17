package dev.wildware.composegl.kool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.MipMapping
import de.fabmax.kool.pipeline.SamplerSettings
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Uint8Buffer
import dev.wildware.composegl.testing.SceneArt
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.bevel
import dev.wildware.composegl.testing.comparePictures
import dev.wildware.composegl.testing.scenes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared scenes, drawn inside Kool's frame on a device, held to the raw OpenGL frontend's goldens.
 *
 * The scenes with no text in them: shapes, clips, layers and effects have no reason to differ between
 * desktop OpenGL and Kool's OpenGL ES, so a scene that does has found an assumption this frontend
 * leaked, or state Kool left bound that the device did not take for itself. Text is left out only
 * because stb_truetype and Android's text drawing will never agree on a glyph; the tests beside this one
 * look at Android's glyphs on their own.
 */
@RunWith(AndroidJUnit4::class)
class KoolAndroidScreenshotTest {

    @Test
    fun the_scenes_without_text_match_the_raw_OpenGL_frontends_goldens() {
        val fonts = AndroidFonts().apply { register("body", KoolDevice.dejaVu(), listOf(12, 16)) }
        val canvas = KoolCanvas(fonts)
        val art = bevelTexture()
        val viewport = Viewport(Size(SceneSize.toFloat(), SceneSize.toFloat()), Size(SceneSize.toFloat(), SceneSize.toFloat()), ScalePolicy.Fit)
        val failures = mutableListOf<String>()
        val list = scenes().filter { it.name in WithoutText }
        try {
            assertTrue("every scene named here still exists", list.size == WithoutText.size)
            for (scene in list) {
                val pixels = KoolDevice.render {
                    canvas.begin(viewport)
                    scene.draw(canvas, SceneArt(fonts = fonts, panel = NinePatch(KoolTexture(art), slice = Padding.all(8f))))
                    canvas.end()
                    KoolDevice.readPixels(SceneSize, SceneSize)
                }
                KoolDevice.save("scene-${scene.name}", SceneSize, SceneSize, pixels)
                val comparison = comparePictures(SceneSize, SceneSize, golden(scene.name), pixels)
                if (!comparison.passes) failures += "${scene.name}: ${comparison.summary}"
            }
        } finally {
            KoolDevice.render {
                canvas.close()
                art.release()
            }
            fonts.close()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The raw OpenGL frontend's golden, as `0xRRGGBB` top row first. */
    private fun golden(name: String): IntArray {
        val bitmap = KoolAndroidScreenshotTest::class.java.getResourceAsStream("/goldens/$name.png").use { BitmapFactory.decodeStream(it) }
        checkNotNull(bitmap) { "no golden for $name" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(pixels.size) { pixels[it] and 0xFFFFFF }
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

    private companion object {
        /** The scenes that never call `text`: the same list the browser frontend holds to these goldens. */
        val WithoutText = setOf(
            "borders", "corners", "gradients", "nine-patch", "particles", "per-corner", "rotation-and-glow", "shadow",
        )
    }
}
