package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.GdxNativesLoader
import java.io.File

/**
 * Real glyph shapes with no GPU.
 *
 * FreeType only needs a CPU, and the glyph atlas is made in memory and uploaded when something is
 * first drawn — so every question about *measurement*, the half the toolkit depends on, can be
 * answered on any machine.
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

    /** A registry holding [family] at each of [sizes], measurable with no GPU. */
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
        fonts.registerTrueType(family, Gdx.files.absolute(fontFile(file).absolutePath), sizes) {
            this.characters = FreeTypeFontGenerator.DEFAULT_CHARS + characters.orEmpty()
        }
    }

    /** The test emoji, 😀, as a picture: a yellow face, seventy-two pixels square. */
    fun smiley(): Pixmap {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/emoji/emoji_u1f600.png")) {
            "the test emoji is missing"
        }.readBytes()
        return Pixmap(bytes, 0, bytes.size)
    }
}
