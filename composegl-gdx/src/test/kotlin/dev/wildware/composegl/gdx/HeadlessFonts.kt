package dev.wildware.composegl.gdx

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

    private fun fontFile(name: String): File {
        val resource = requireNotNull(javaClass.getResource("/fonts/$name")) { "the test font $name is missing" }
        return File(resource.toURI())
    }

    init {
        GdxNativesLoader.load()
        Gdx.files = HeadlessFiles()
    }

    /** A registry holding [family] at each of [sizes], measurable but not drawable. */
    fun registry(family: String = "test", sizes: List<Int> = listOf(16)): GdxFonts =
        GdxFonts().also { add(it, family, sizes) }

    /**
     * Adds [family] from the test font [file] at each of [sizes] to [fonts], baking [characters] —
     * FreeType's default set when null, which is ASCII and nothing a fallback is for.
     */
    fun add(
        fonts: GdxFonts,
        family: String,
        sizes: List<Int>,
        file: String = "DejaVuSans.ttf",
        characters: String? = null,
    ) {
        FreeTypeFontGenerator(Gdx.files.absolute(fontFile(file).absolutePath)).use { generator ->
            sizes.forEach { size ->
                val packer = PixmapPacker(512, 512, Pixmap.Format.RGBA8888, 1, false)
                val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().also {
                    it.size = size
                    it.packer = packer
                    if (characters != null) it.characters = FreeTypeFontGenerator.DEFAULT_CHARS + characters
                }
                fonts.register(family, size.toFloat(), MeasuringFont(generator.generateData(parameter)))
            }
        }
    }

    /** The test emoji, 😀, as a picture: a yellow face, seventy-two pixels square. */
    fun smiley(): Pixmap {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/emoji/emoji_u1f600.png")) {
            "the test emoji is missing"
        }.readBytes()
        return Pixmap(bytes, 0, bytes.size)
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
