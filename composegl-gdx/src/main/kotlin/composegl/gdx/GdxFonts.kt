package composegl.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import composegl.ui.geometry.Size
import composegl.ui.text.FontMetrics
import composegl.ui.text.FontProvider
import composegl.ui.text.TextLayout
import composegl.ui.text.TextStyle
import kotlin.math.roundToInt

/**
 * A measured string, with everything the renderer needs to draw exactly what was measured.
 *
 * The toolkit only ever looks at the four properties the [TextLayout] interface declares. The
 * canvas casts to this and uses the rest — which is the seam that keeps LibGDX out of the toolkit
 * while letting the backend be efficient.
 */
class GdxTextLayout internal constructor(
    override val text: String,
    override val size: Size,
    override val lineCount: Int,
    override val firstBaseline: Float,
    /** What is actually drawn, which may be shorter than [text] when a line limit cut it. */
    val glyphs: GlyphLayout,
    val font: BitmapFont,
    val style: TextStyle,
) : TextLayout

/**
 * Fonts, by name.
 *
 * A game registers what it wants at startup and the toolkit never touches a file: it asks for
 * "body at 16" and gets whatever this was told that means. Two weights are two names.
 *
 * Sizes are registered one by one rather than generated on demand. Generating a glyph atlas takes
 * milliseconds and allocates a texture, and doing that during a frame because a label appeared at
 * a size nobody anticipated is exactly the kind of stutter a game cannot afford. Asking for a size
 * that was never registered is an error that names the sizes that exist.
 *
 * Every size of every family generated here goes onto one page — see [GdxAtlas] — along with the
 * white texel the renderer draws solid colour from. Hand that atlas to [GdxCanvas] and a screen
 * of panels and labels is one texture and, barring a clip, one draw call.
 *
 * Note for later: a design pixel is not a screen pixel. On a screen where the viewport scales
 * everything by three, glyphs generated at sixteen are stretched to forty-eight and look soft.
 * Registering the sizes a game actually needs at the scales it expects is the current answer.
 *
 * @param atlas where generated glyphs are packed. One is made and disposed for you unless you
 *   supply one to share between two registries.
 */
class GdxFonts(val atlas: GdxAtlas = GdxAtlas(), private val ownsAtlas: Boolean = true) :
    FontProvider, Disposable {

    private data class Key(val family: String, val size: Int)

    private class Registered(val font: BitmapFont, val owned: Boolean)

    private val fonts = LinkedHashMap<Key, Registered>()

    /**
     * Uses a font the game made and still owns.
     *
     * @param owned true to have this registry dispose the font. Only pass it for a font that
     *   exists solely for the interface.
     */
    fun register(family: String, size: Float, font: BitmapFont, owned: Boolean = false) {
        val key = Key(family, size.roundToInt())
        fonts.put(key, Registered(font, owned))?.let { if (it.owned) it.font.dispose() }
    }

    /**
     * Generates each of [sizes] from a `.ttf` and registers them under [family].
     *
     * Every size is packed into the shared [atlas] rather than being given a texture of its own,
     * which is what keeps a screen mixing three text sizes down to one draw call.
     *
     * Needs an OpenGL context, because the atlas is uploaded at the end of it. Call it at startup,
     * not in a frame.
     */
    fun registerTrueType(
        family: String,
        file: FileHandle,
        sizes: List<Int>,
        configure: FreeTypeFontGenerator.FreeTypeFontParameter.() -> Unit = {},
    ) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        FreeTypeFontGenerator(file).use { generator ->
            sizes.forEach { size ->
                val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    this.size = size
                    configure()
                    // After `configure`, so a game cannot accidentally take the shared page away
                    // and get its own texture back without noticing.
                    packer = atlas.packer
                }
                register(family, size.toFloat(), generator.generateFont(parameter), owned = true)
            }
        }
        atlas.refresh()
    }

    /** The font behind [style]. Throws, naming what is registered, when there is none. */
    fun fontFor(style: TextStyle): BitmapFont {
        val key = Key(style.family, style.size.roundToInt())
        fonts[key]?.let { return it.font }

        val sizes = fonts.keys.filter { it.family == key.family }.map { it.size }.sorted()
        val detail = if (sizes.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $sizes"
        }
        error("no font for ${key.family} at ${key.size}: $detail")
    }

    fun families(): List<String> = fonts.keys.map { it.family }.distinct()

    fun sizesOf(family: String): List<Int> =
        fonts.keys.filter { it.family == family }.map { it.size }.sorted()

    override fun metrics(style: TextStyle): FontMetrics {
        val font = fontFor(style)
        val data = font.data
        return FontMetrics(
            size = style.size,
            // LibGDX measures downwards from the baseline as negative. The toolkit measures
            // everything from the baseline as positive, in both directions.
            ascent = data.capHeight + data.ascent,
            descent = -data.descent,
            capHeight = data.capHeight,
            lineHeight = style.lineHeight,
            spaceAdvance = data.spaceXadvance,
        )
    }

    override fun measure(text: String, style: TextStyle, maxWidth: Float): TextLayout {
        val font = fontFor(style)
        val wrapped = layoutOf(font, text, maxWidth, style)

        if (style.maxLines <= 0 || linesIn(wrapped) <= style.maxLines) {
            return finish(text, wrapped, font, style)
        }

        val cut = longestPrefixFitting(font, text, maxWidth, style)
        return finish(text, layoutOf(font, cut + style.ellipsis, maxWidth, style), font, style)
    }

    /**
     * The most of [text] that still fits in the style's line limit once the ellipsis is added.
     *
     * A binary search rather than a walk: measuring is the expensive part, and a paragraph that
     * has to be cut is usually cut a long way from either end.
     */
    private fun longestPrefixFitting(
        font: BitmapFont,
        text: String,
        maxWidth: Float,
        style: TextStyle,
    ): String {
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = text.take(middle).trimEnd() + style.ellipsis
            if (linesIn(layoutOf(font, candidate, maxWidth, style)) <= style.maxLines) low = middle else high = middle - 1
        }
        return text.take(low).trimEnd()
    }

    private fun layoutOf(font: BitmapFont, text: String, maxWidth: Float, style: TextStyle): GlyphLayout {
        val wrap = maxWidth.isFinite() && maxWidth > 0f
        // The style's line spacing goes into the layout, not just into the reported height, so
        // that what is drawn is spaced the way what was measured said it would be.
        font.data.setLineHeight(style.lineHeight)
        return GlyphLayout().also {
            it.setText(font, text, 0, text.length, Color.WHITE, if (wrap) maxWidth else 0f, Align.left, wrap, null)
        }
    }

    private fun linesIn(layout: GlyphLayout): Int =
        layout.runs.map { it.y }.distinct().size.coerceAtLeast(1)

    private fun finish(text: String, glyphs: GlyphLayout, font: BitmapFont, style: TextStyle): GdxTextLayout {
        val lines = linesIn(glyphs)
        return GdxTextLayout(
            text = text,
            // The height is the style's line spacing, not the font's own: two labels in the same
            // style must line up whether or not their glyphs happen to be tall.
            size = Size(glyphs.width, lines * style.lineHeight),
            lineCount = lines,
            // The ascent, not the cap height: a layout's top edge has to be above everything the
            // font draws, or an accent on a capital pokes out of the box that was reserved for it.
            // It is also where LibGDX actually puts the first baseline, which is the part that has
            // to be true — the toolkit's rule is that drawing matches what was measured.
            firstBaseline = font.data.capHeight + font.data.ascent,
            glyphs = glyphs,
            font = font,
            style = style,
        )
    }

    override fun dispose() {
        // The fonts first: a font packed into the atlas does not own its texture, so letting go of
        // the atlas before them would leave them pointing at a texture that is already gone.
        fonts.values.forEach { if (it.owned) it.font.dispose() }
        fonts.clear()
        if (ownsAtlas) atlas.dispose()
    }

    private inline fun <T : Disposable, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        dispose()
    }
}
