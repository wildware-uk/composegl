package uk.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.GdxNativesLoader
import java.io.File

/**
 * Real glyph shapes with no GPU.
 *
 * FreeType only needs a CPU to work out how wide a letter is; it needs a GPU to put that letter in
 * a texture. Handing it a packer of our own stops it reaching for the second, which lets every
 * question about *measurement* — the half the toolkit depends on — be answered on any machine.
 *
 * The font this produces cannot be drawn. Drawing is what the screenshot tests are for.
 */
object HeadlessFonts {

    private val fontFile: File by lazy {
        val resource = requireNotNull(javaClass.getResource("/fonts/DejaVuSans.ttf")) {
            "the test font is missing"
        }
        File(resource.toURI())
    }

    init {
        GdxNativesLoader.load()
        Gdx.files = HeadlessFiles()
    }

    /** A registry holding [family] at each of [sizes], measurable but not drawable. */
    fun registry(family: String = "test", sizes: List<Int> = listOf(16)): GdxFonts {
        val fonts = GdxFonts()
        FreeTypeFontGenerator(Gdx.files.absolute(fontFile.absolutePath)).use { generator ->
            sizes.forEach { size ->
                val packer = PixmapPacker(512, 512, Pixmap.Format.RGBA8888, 1, false)
                val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().also {
                    it.size = size
                    it.packer = packer
                }
                fonts.register(family, size.toFloat(), MeasuringFont(generator.generateData(parameter)))
            }
        }
        return fonts
    }

    /**
     * A font that knows every width and has no picture of anything.
     *
     * `load` is where LibGDX attaches each glyph to a place in a texture, and it is the only step
     * that needs a GPU. Skipping it leaves the metrics — which is all that measurement reads —
     * exactly as FreeType produced them.
     */
    private class MeasuringFont(data: BitmapFont.BitmapFontData) :
        BitmapFont(data, TextureRegion(), false) {
        override fun load(data: BitmapFontData) = Unit
    }

    private inline fun <T : FreeTypeFontGenerator, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        dispose()
    }
}
