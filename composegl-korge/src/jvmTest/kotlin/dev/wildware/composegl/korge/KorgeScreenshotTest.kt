package dev.wildware.composegl.korge

import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.testing.Scene
import dev.wildware.composegl.testing.SceneArt
import dev.wildware.composegl.testing.SceneSize
import dev.wildware.composegl.testing.bevel
import dev.wildware.composegl.testing.imageOf
import dev.wildware.composegl.testing.scenes
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.NinePatch
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.composegl.ui.layout.Viewport
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.awt.image.BufferedImage
import java.io.File

/**
 * The shared scenes, drawn through KorGE.
 *
 * Two checks, as the WebGL backend has. Every scene against this backend's own goldens. And the
 * scenes with no text in them against the raw OpenGL backend's goldens, by the same rule: shapes,
 * clips and pictures have no reason to differ between two OpenGL renderers, and a scene that does
 * has found an assumption one of them leaked. Text is left out of that second check only because
 * KorGE's TrueType rasteriser and stb_truetype will never agree on a glyph.
 *
 * A scene that needs a canvas feature this backend does not claim yet is skipped by an assumption
 * that names the missing capability, never silently. Once the capability is switched on the scene
 * runs, finds no golden, and says so — which is when somebody looks at the picture and commits it.
 *
 * Like the other pixel tests here, they skip with no display and no `KORGE_HEADLESS=true`.
 */
class KorgeScreenshotTest {

    @TestFactory
    fun goldens(): List<DynamicTest> = scenes().map { scene ->
        DynamicTest.dynamicTest(scene.name) {
            Goldens.assertMatches(scene.name, render(scene), directory = Own)
        }
    }

    @TestFactory
    fun `the scenes without text match the raw OpenGL backend's goldens`(): List<DynamicTest> =
        scenes().filter { it.name in WithoutText }.map { scene ->
            DynamicTest.dynamicTest(scene.name) {
                Goldens.assertMatches(scene.name, render(scene), directory = Desktop, updatable = false)
            }
        }

    private fun render(scene: Scene): BufferedImage {
        val fonts = KorgeFonts().also { it.registerTrueType("body", TestFonts.dejaVu(), listOf(12, 16)) }
        val canvas = KorgeCanvas(fonts.atlas)
        try {
            Needs[scene.name]?.let { (capability, claimed) ->
                assumeTrue(claimed(canvas), "\"${scene.name}\" needs $capability, which KorgeCanvas does not claim yet")
            }
            val art = SceneArt(fonts = fonts, panel = NinePatch(KorgeTexture(bevelBitmap()), slice = Padding.all(8f)))
            val pixels = KorgeGl.picture(SceneSize, SceneSize) { ctx ->
                canvas.begin(viewport, ctx)
                scene.draw(canvas, art)
                canvas.end()
            }
            return imageOf(SceneSize, SceneSize) { x, y -> pixels[x, y].let { (it.r shl 16) or (it.g shl 8) or it.b } }
        } finally {
            canvas.close()
        }
    }

    /**
     * The shared nine-patch art, as a KorGE picture. Every pixel is opaque, so its bytes are already
     * premultiplied — which is what KorGE wants uploaded.
     */
    private fun bevelBitmap(): Bitmap32 {
        val art = bevel()
        return Bitmap32(art.width, art.height, premultiplied = true).also { bitmap ->
            for (y in 0 until art.height) for (x in 0 until art.width) {
                val at = (y * art.width + x) * 4
                bitmap.setRgbaRaw(x, y, RGBA(
                    art.pixels[at].toInt() and 0xFF,
                    art.pixels[at + 1].toInt() and 0xFF,
                    art.pixels[at + 2].toInt() and 0xFF,
                    art.pixels[at + 3].toInt() and 0xFF,
                ))
            }
        }
    }

    private val viewport = Viewport(
        design = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        physical = Size(SceneSize.toFloat(), SceneSize.toFloat()),
        policy = ScalePolicy.Fit,
    )

    private companion object {
        /** This backend's goldens. A multiplatform module's test resources are under `jvmTest`. */
        val Own = File("src/jvmTest/resources/goldens")

        /** The raw OpenGL backend's goldens, which the text-free scenes are held to as well. */
        val Desktop = File("../composegl-lwjgl3/src/test/resources/goldens")

        /** The same list the WebGL backend compares: the scenes that never call `text`. */
        val WithoutText = setOf(
            "borders", "corners", "gradients", "nine-patch", "particles", "per-corner", "rotation-and-glow", "shadow",
        )

        /**
         * The scenes that draw with a canvas feature beyond the required calls, and the capability
         * that says the feature is there. Without it a scene still draws — the interface's defaults
         * fall back to something honest — but not the picture the goldens hold.
         */
        val Needs: Map<String, Pair<String, (KorgeCanvas) -> Boolean>> = mapOf(
            "layer" to ("drawsLayers" to { c -> c.drawsLayers }),
            "scale" to ("drawsLayers" to { c -> c.drawsLayers }),
            // Effects compose with layers; there is no separate flag for a ShaderEffect program.
            "effect" to ("drawsLayers (and ShaderEffect programs)" to { c -> c.drawsLayers }),
            "effects" to ("drawsLayers (and ShaderEffect programs)" to { c -> c.drawsLayers }),
            "per-corner" to ("roundsCornersSeparately" to { c -> c.roundsCornersSeparately }),
            "rotation-and-glow" to ("rotatesImages and supports(BlendMode.Additive)" to { c ->
                c.rotatesImages && c.supports(BlendMode.Additive)
            }),
            "skew" to ("drawsLayersOnto" to { c -> c.drawsLayersOnto }),
            "tint" to ("tints and drawsLayers" to { c -> c.tints && c.drawsLayers }),
            "gradients" to ("drawsGradients" to { c -> c.drawsGradients }),
            "tilt" to ("tiltsLayers" to { c -> c.tiltsLayers }),
            "clip-shape" to ("cutsLayers" to { c -> c.cutsLayers }),
        )
    }
}
