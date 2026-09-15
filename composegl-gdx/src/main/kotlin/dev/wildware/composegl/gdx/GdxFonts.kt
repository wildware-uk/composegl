package dev.wildware.composegl.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.freetype.FreeType
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.render.AtlasFonts
import dev.wildware.composegl.render.AtlasTextLayout
import dev.wildware.composegl.render.GlyphAtlas
import dev.wildware.composegl.render.GlyphBitmap
import dev.wildware.composegl.render.GlyphKind
import dev.wildware.composegl.render.GlyphRasteriser
import dev.wildware.composegl.render.ImageDecoder
import dev.wildware.composegl.render.RasterFace
import dev.wildware.composegl.render.RgbaImage
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Text measured by [GdxFonts]: the shared renderer's layout, under the name this backend has always used. */
typealias GdxTextLayout = AtlasTextLayout

/**
 * Fonts, by name, rasterised by FreeType into the shared renderer's glyph atlas.
 *
 * A game registers what it wants at startup and the toolkit never touches a file: it asks for
 * "body at 16" and gets whatever this was told that means. Two weights are two names. Asking for a
 * size that was never registered is an error that names the sizes that exist.
 *
 * Every glyph of every family and size lands on the same pages as the white block the renderer
 * draws solid colour from, so a screen of panels and labels is one texture and, barring a clip, one
 * draw call. The characters a font is registered with are made when it is registered; a font
 * registered `onDemand` makes the rest the first time text asks for them. Neither needs OpenGL:
 * pages are uploaded when they are first drawn.
 *
 * **Characters a font does not have** — a player called 玩家, a chat line with 😀 in it — come
 * from the families named by [fallBackTo], tried in order, one character at a time. Colour emoji
 * are registered as pictures with [registerPictures], because FreeType here only makes coverage.
 *
 * No kerning: a width is the sum of the advances, which is what every backend's text does.
 *
 * A player's text size: `ProvideTextScale` asks for whole sizes, so every one of them has to be
 * registered. `scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f))` is the list to hand
 * [registerTrueType]. Fallbacks need every one of those sizes too.
 *
 * @param pageSize each atlas page, in pixels each way. A full page starts another, which costs a
 *   draw call wherever text crosses from one to the other.
 * @param maxPages the most pages there may be. More than fits is an error that says so.
 */
class GdxFonts private constructor(
    private val rasteriser: FreeTypeRasteriser,
    pageSize: Int,
    maxPages: Int,
) : AtlasFonts(rasteriser, PixmapDecoder, pageSize, maxPageSize = pageSize, maxPages = maxPages, atlasOwner = "GdxFonts"),
    Disposable {

    constructor(pageSize: Int = 1024, maxPages: Int = 8) : this(FreeTypeRasteriser(), pageSize, maxPages)

    init {
        owners[atlas] = WeakReference(this)
    }

    /**
     * Registers [family] at each of [sizes], from a `.ttf` or `.otf`.
     *
     * The parameter's `characters` are made now — Latin, Latin-1 and the punctuation real text has
     * in it, unless [configure] says otherwise — along with its hinting, gamma and `mono`.
     *
     * @param onDemand make any other character the font has the first time text asks for it. For a
     *   font with more characters than any atlas holds — Chinese, Japanese and Korean have tens of
     *   thousands — of which text only uses a few hundred. The file stays open until [dispose].
     */
    fun registerTrueType(
        family: String,
        file: FileHandle,
        sizes: List<Int>,
        onDemand: Boolean = false,
        configure: FreeTypeFontParameter.() -> Unit = {},
    ) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        val parameter = FreeTypeFontParameter().apply {
            characters = FreeTypeFontGenerator.DEFAULT_CHARS + Typography
            incremental = onDemand
            configure()
        }
        val sorted = sizes.distinct().sorted()
        rasteriser.add(family, file, sorted, parameter)
        registerFont(family, sorted)
        val characters = (" " + parameter.characters).codePoints().toArray().asIterable()
        sorted.forEach { makeGlyphs(family, it, characters) }
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes]: how colour
     * emoji get into text. Each is scaled to the size tall, keeping its shape, sits a little below
     * the baseline and keeps its own colours whatever colour the text is. A family of pictures can
     * only be a fallback: name it in [fallBackTo].
     *
     * The pictures are copied; the caller still owns [pictures] and disposes them.
     *
     * @param pictures by the character each one draws: `"😀"`, `"❤️"`, with or without the emoji
     *   variation selector. A sequence joined into one emoji is refused.
     */
    @JvmName("registerPixmaps")
    fun registerPictures(family: String, pictures: Map<String, Pixmap>, sizes: List<Int>) =
        registerDecodedPictures(family, pictures.mapValues { (_, pixmap) -> rgbaOf(pixmap) }, sizes)

    /** Lets go of the glyph atlas and every font file, and forgets every registration. */
    override fun dispose() {
        close()
        rasteriser.dispose()
    }

    internal companion object {

        /** The punctuation real text has in it that ASCII does not. */
        const val Typography = "—–…‘’“”·«»×÷°≤≥←→↑↓○△□✕✓●☰"

        private val owners: MutableMap<GlyphAtlas, WeakReference<GdxFonts>> = Collections.synchronizedMap(WeakHashMap())

        /** The fonts [atlas] belongs to. */
        fun owning(atlas: GlyphAtlas): GdxFonts =
            requireNotNull(owners[atlas]?.get()) { "that atlas does not belong to any GdxFonts" }

        fun rgbaOf(pixmap: Pixmap): RgbaImage {
            val rgba = if (pixmap.format == Pixmap.Format.RGBA8888) {
                pixmap
            } else {
                Pixmap(pixmap.width, pixmap.height, Pixmap.Format.RGBA8888).also {
                    it.blending = Pixmap.Blending.None
                    it.drawPixmap(pixmap, 0, 0)
                }
            }
            try {
                val bytes = ByteArray(rgba.width * rgba.height * 4)
                rgba.pixels.duplicate().position(0).let { (it as java.nio.ByteBuffer).get(bytes) }
                return RgbaImage(rgba.width, rgba.height, bytes)
            } finally {
                if (rgba !== pixmap) rgba.dispose()
            }
        }
    }
}

/** LibGDX's own picture decoding, for pictures registered as encoded bytes. */
private object PixmapDecoder : ImageDecoder {
    override fun decode(encoded: ByteArray): RgbaImage {
        val pixmap = Pixmap(encoded, 0, encoded.size)
        try {
            return GdxFonts.rgbaOf(pixmap)
        } finally {
            pixmap.dispose()
        }
    }
}

/**
 * FreeType, one glyph at a time: the whole of this backend's part in drawing text.
 *
 * Loads and renders exactly as LibGDX's `FreeTypeFontGenerator` does — its hinting flags, its gamma,
 * its whole-pixel metrics — so letters keep the shapes they had.
 */
internal class FreeTypeRasteriser : GlyphRasteriser, Disposable {

    private class Registration(
        val family: String,
        val face: FreeType.Face,
        val sizes: List<Int>,
        val parameter: FreeTypeFontParameter,
        val characters: Set<Int>,
    ) {
        var selected = 0
    }

    private var library: FreeType.Library? = null

    private val registrations = mutableListOf<Registration>()

    fun add(family: String, file: FileHandle, sizes: List<Int>, parameter: FreeTypeFontParameter) {
        val library = library ?: FreeType.initFreeType().also { library = it }
        val face = library.newFace(file, 0)
        // A space always, as LibGDX's generator adds one whatever the list says.
        val characters = parameter.characters.codePoints().toArray().toHashSet().also { it += ' '.code }
        registrations += Registration(family, face, sizes, parameter, characters)
    }

    override fun face(family: String, size: Int): RasterFace? = face(family, size, size)

    override fun face(family: String, size: Int, pixels: Int): RasterFace? =
        registrations.lastOrNull { it.family == family && size in it.sizes }?.let { Face(it, pixels) }

    override fun dispose() {
        registrations.forEach { it.face.dispose() }
        registrations.clear()
        library?.dispose()
        library = null
    }

    private class Face(private val font: Registration, private val size: Int) : RasterFace {

        private val face = font.face
        private val parameter = font.parameter
        private val flags = loadingFlags(parameter.hinting)

        override val ascent: Float
        override val descent: Float
        override val capHeight: Float

        init {
            select()
            val metrics = face.size.metrics
            ascent = FreeType.toInt(metrics.ascender).toFloat()
            descent = -FreeType.toInt(metrics.descender).toFloat()
            // The first capital the font has, as LibGDX measures it.
            val capital = CapitalLetters.firstOrNull { face.getCharIndex(it.code) != 0 && face.loadChar(it.code, flags) }
            capHeight = if (capital != null) FreeType.toInt(face.glyph.metrics.height).toFloat() else size * 0.7f
        }

        /** A face holds one size at a time, so whichever size asks sets it. */
        private fun select() {
            if (font.selected == size) return
            face.setPixelSizes(0, size)
            font.selected = size
        }

        override fun has(codepoint: Int): Boolean =
            (parameter.incremental || codepoint in font.characters) && face.getCharIndex(codepoint) != 0

        override fun advance(codepoint: Int): Float {
            select()
            if (!face.loadChar(codepoint, flags)) return 0f
            return (FreeType.toInt(face.glyph.metrics.horiAdvance) + parameter.spaceX).toFloat()
        }

        override fun draw(codepoint: Int, into: GlyphBitmap): Boolean {
            select()
            if (!face.loadChar(codepoint, flags)) return false
            val glyph = face.glyph.glyph
            try {
                glyph.toBitmap(if (parameter.mono) FreeType.FT_RENDER_MODE_MONO else FreeType.FT_RENDER_MODE_NORMAL)
                val bitmap = glyph.bitmap
                into.xOffset = glyph.left.toFloat()
                into.yOffset = -glyph.top.toFloat()
                if (bitmap.width <= 0 || bitmap.rows <= 0) {
                    into.resize(0, 0, GlyphKind.Coverage)
                    return true
                }
                val pixmap = bitmap.getPixmap(Pixmap.Format.RGBA8888, Color.WHITE, parameter.gamma)
                try {
                    into.resize(pixmap.width, pixmap.height, GlyphKind.Coverage)
                    val pixels = pixmap.pixels
                    for (at in 0 until pixmap.width * pixmap.height) into.pixels[at] = pixels.get(at * 4 + 3)
                } finally {
                    pixmap.dispose()
                }
                return true
            } finally {
                glyph.dispose()
            }
        }

        private companion object {
            /** The letters LibGDX measures a font's cap height by. */
            const val CapitalLetters = "MNBDCEFKAGHIJLOPQRSTUVWXYZ"

            fun loadingFlags(hinting: FreeTypeFontGenerator.Hinting): Int = FreeType.FT_LOAD_DEFAULT or when (hinting) {
                FreeTypeFontGenerator.Hinting.None -> FreeType.FT_LOAD_NO_HINTING
                FreeTypeFontGenerator.Hinting.Slight -> FreeType.FT_LOAD_TARGET_LIGHT
                FreeTypeFontGenerator.Hinting.Medium -> FreeType.FT_LOAD_TARGET_NORMAL
                FreeTypeFontGenerator.Hinting.Full -> FreeType.FT_LOAD_TARGET_MONO
                FreeTypeFontGenerator.Hinting.AutoSlight -> FreeType.FT_LOAD_FORCE_AUTOHINT or FreeType.FT_LOAD_TARGET_LIGHT
                FreeTypeFontGenerator.Hinting.AutoMedium -> FreeType.FT_LOAD_FORCE_AUTOHINT or FreeType.FT_LOAD_TARGET_NORMAL
                FreeTypeFontGenerator.Hinting.AutoFull -> FreeType.FT_LOAD_FORCE_AUTOHINT or FreeType.FT_LOAD_TARGET_MONO
            }
        }
    }
}
