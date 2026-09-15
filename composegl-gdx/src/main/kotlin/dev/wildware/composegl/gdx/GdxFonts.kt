package dev.wildware.composegl.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.text.FontMetrics
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.text.TextLayout
import dev.wildware.composegl.ui.text.TextStyle
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
 * **Characters a font does not have** — a player called 玩家, a chat line with 😀 in it — come
 * from the families named by [fallBackTo], tried in order, one character at a time. A font
 * (Noto Sans CJK, say) is registered with [registerTrueType] like any other, usually with
 * `onDemand` so that twenty thousand characters are not baked at startup; colour emoji are
 * registered as pictures with [registerPictures], because FreeType here can only make white
 * letters.
 *
 * Note for later: a design pixel is not a screen pixel. On a screen where the viewport scales
 * everything by three, glyphs generated at sixteen are stretched to forty-eight and look soft.
 * Registering the sizes a game actually needs at the scales it expects is the current answer.
 *
 * The same goes for a player's text size. `ProvideTextScale` asks for whole sizes — 16 at 125% is
 * a style at 20 — so every one of them has to be here before it is asked for.
 * `scaledTextSizes(listOf(16), listOf(1f, 1.25f, 1.5f))` is the list to hand [registerTrueType].
 * Fallbacks need every one of those sizes too.
 *
 * @param atlas where generated glyphs are packed. One is made and disposed for you unless you
 *   supply one to share between two registries.
 */
class GdxFonts(val atlas: GdxAtlas = GdxAtlas(), private val ownsAtlas: Boolean = true) :
    FontProvider, Disposable {

    private data class Key(val family: String, val size: Int)

    private class Registered(val font: BitmapFont, val owned: Boolean, val onDemand: Boolean = false)

    private val fonts = LinkedHashMap<Key, Registered>()

    /** Families of pictures standing in for characters, which can only ever be fallbacks. */
    private val pictures = LinkedHashMap<Key, Map<Int, PictureGlyph>>()

    /** Kept open for as long as a family generates glyphs when they are first asked for. */
    private val generators = mutableListOf<FreeTypeFontGenerator>()

    private var everyFamilyFallsBackTo: List<String> = emptyList()
    private val fallbacksByFamily = HashMap<String, List<String>>()

    /** Each family with its fallbacks behind it, built the first time a style asks for it. */
    private val chains = HashMap<Key, FallbackFont>()

    /**
     * Uses a font the game made and still owns.
     *
     * @param owned true to have this registry dispose the font. Only pass it for a font that
     *   exists solely for the interface.
     */
    fun register(family: String, size: Float, font: BitmapFont, owned: Boolean = false) {
        register(Key(family, size.roundToInt()), Registered(font, owned))
    }

    private fun register(key: Key, registered: Registered) {
        fonts.put(key, registered)?.let { if (it.owned) it.font.dispose() }
        chains.clear()
    }

    /**
     * Generates each of [sizes] from a `.ttf` and registers them under [family].
     *
     * Every size is packed into the shared [atlas] rather than being given a texture of its own,
     * which is what keeps a screen mixing three text sizes down to one draw call.
     *
     * Needs an OpenGL context, because the atlas is uploaded at the end of it. Call it at startup,
     * not in a frame.
     *
     * @param onDemand generate a character the first time text asks for it, rather than only the
     *   ones baked up front. For a font with more characters than any atlas holds — Chinese,
     *   Japanese and Korean have tens of thousands — and which text only ever uses a few hundred
     *   of. The first frame that shows a new character pays for generating it, which is a
     *   fraction of a millisecond each, and the file stays open until this registry is disposed.
     *   The glyphs go into the same [atlas], and a page that fills starts another one, which needs
     *   the OpenGL context that measuring normally has anyway.
     */
    fun registerTrueType(
        family: String,
        file: FileHandle,
        sizes: List<Int>,
        onDemand: Boolean = false,
        configure: FreeTypeFontGenerator.FreeTypeFontParameter.() -> Unit = {},
    ) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        val generator = FreeTypeFontGenerator(file)
        try {
            sizes.forEach { size ->
                val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    this.size = size
                    // FreeType bakes a fixed set of glyphs, and LibGDX's default set stops at
                    // ASCII — so an em dash, a curly quote or an ellipsis is drawn as a box. Every
                    // game writes one of those eventually, so they are here rather than left to be
                    // discovered. Before `configure`, so a game can name its own set instead.
                    characters = FreeTypeFontGenerator.DEFAULT_CHARS + Typography
                    incremental = onDemand
                    configure()
                    // After `configure`, so a game cannot accidentally take the shared page away
                    // and get its own texture back without noticing.
                    packer = atlas.packer
                }
                register(Key(family, size), Registered(generator.generateFont(parameter), owned = true, onDemand))
            }
        } finally {
            if (onDemand) generators += generator else generator.dispose()
        }
        atlas.refresh()
    }

    /**
     * Registers pictures that stand in for characters under [family], at each of [sizes].
     *
     * This is how colour emoji get into text. Each picture is scaled to [sizes] tall — the text
     * size, so an emoji is as tall as the type is big — keeping its shape, and packed into the
     * shared [atlas], so a chat line with a smiley in it is still one texture. It sits a little
     * below the baseline, the way an emoji font's glyphs do, and is drawn in its own colours
     * whatever colour the text around it is.
     *
     * A family of pictures can only be a fallback: name it in [fallBackTo]. It has no letters of
     * its own to be the main font with.
     *
     * The pictures are copied; the caller still owns [pictures] and disposes them.
     *
     * @param pictures by the character each one draws: `"😀"`, `"❤️"`. One character each, with or
     *   without the emoji variation selector after it. A sequence joined into one emoji — a family,
     *   a flag, a skin tone — is refused, because it would need text shaping to find.
     */
    fun registerPictures(family: String, pictures: Map<String, Pixmap>, sizes: List<Int>) {
        require(sizes.isNotEmpty()) { "registering $family with no sizes would register nothing" }
        require(sizes.all { it > 0 }) { "a size must be positive, got $sizes" }
        require(pictures.isNotEmpty()) { "registering $family with no pictures would register nothing" }
        require(fonts.keys.none { it.family == family }) { "$family is already a font; pictures need a name of their own" }
        val codepoints = pictures.mapKeys { (text, _) -> codepointOf(text) }

        sizes.forEach { size ->
            this.pictures[Key(family, size)] = codepoints.mapValues { (codepoint, picture) ->
                pack(codepoint, picture, size)
            }
        }
        chains.clear()
        // Packed into pages that are not on the GPU yet. The canvas uploads them before it next
        // draws text, so this needs no OpenGL and a registry can be built on any thread.
        atlas.stale = true
    }

    /** [picture] at [size], packed into the atlas, as a glyph measured from the baseline. */
    private fun pack(codepoint: Int, picture: Pixmap, size: Int): PictureGlyph {
        val height = size
        val width = (picture.width * size / picture.height.toFloat()).roundToInt().coerceAtLeast(1)
        val scaled = scale(picture, width, height)
        val rectangle = try {
            atlas.packer.pack(scaled)
        } finally {
            scaled.dispose()
        }
        val page = atlas.packer.pages.indexOf(rectangle.page, true)
        val pageWidth = atlas.packer.pageWidth.toFloat()
        val pageHeight = atlas.packer.pageHeight.toFloat()
        val gap = (size / 16f).roundToInt().coerceAtLeast(1)

        return PictureGlyph().also {
            it.id = codepoint
            it.srcX = rectangle.x
            it.srcY = rectangle.y
            it.width = width
            it.height = height
            it.u = rectangle.x / pageWidth
            it.u2 = (rectangle.x + width) / pageWidth
            // The other way up from a texture region: see the note on the canvas's text.
            it.v2 = rectangle.y / pageHeight
            it.v = (rectangle.y + height) / pageHeight
            it.xoffset = gap
            // Its bottom this far below the baseline, which is about where an emoji font puts it:
            // low enough to sit with the letters' descenders, high enough to share their middle.
            it.yoffset = -(size * PictureDrop).roundToInt()
            it.xadvance = width + gap * 2
            it.page = page
        }
    }

    /**
     * [picture] at [width] by [height], halved until it is close and then filtered the rest of the
     * way — a straight bilinear shrink from seventy-two pixels to sixteen skips most of the pixels
     * and leaves an emoji's outline full of holes.
     */
    private fun scale(picture: Pixmap, width: Int, height: Int): Pixmap {
        var current = picture
        while (current.width >= width * 2 && current.height >= height * 2) {
            current = resized(current, current.width / 2, current.height / 2, disposeSource = current !== picture)
        }
        return resized(current, width, height, disposeSource = current !== picture)
    }

    private fun resized(source: Pixmap, width: Int, height: Int, disposeSource: Boolean): Pixmap =
        Pixmap(width, height, Pixmap.Format.RGBA8888).also {
            it.blending = Pixmap.Blending.None
            it.filter = Pixmap.Filter.BiLinear
            it.drawPixmap(source, 0, 0, source.width, source.height, 0, 0, width, height)
            if (disposeSource) source.dispose()
        }

    /**
     * Where every family looks for a character its own font does not have: each of [families], in
     * order.
     *
     * Checked one character at a time, so `"Ace 玩家 😀"` takes its letters from the main font, its
     * Chinese from the first fallback that has it and its emoji from the pictures. The main font
     * always wins for a character it does have, and only characters nothing has come out as the
     * main font's missing-glyph box.
     *
     * A fallback must be registered at every size the families that name it are asked for, or
     * measuring says which size it wanted — the same rule as a missing size of the font itself.
     *
     * Not followed any further: a fallback's own fallbacks are not tried. List everything here.
     */
    fun fallBackTo(families: List<String>) {
        everyFamilyFallsBackTo = families.toList()
        chains.clear()
    }

    /** Where [family] alone looks for a character it does not have, instead of the list for everyone. */
    fun fallBackTo(family: String, families: List<String>) {
        fallbacksByFamily[family] = families.toList()
        chains.clear()
    }

    /** The families [family] falls back to, in the order they are tried. */
    fun fallbacksOf(family: String): List<String> =
        (fallbacksByFamily[family] ?: everyFamilyFallsBackTo).filter { it != family }

    private companion object {
        /** The punctuation real text has in it that ASCII does not. */
        const val Typography = "—–…‘’“”·«»×÷°≤≥←→↑↓○△□✕✓●☰"

        /** How far below the baseline a picture's bottom edge sits, as a share of its height. */
        const val PictureDrop = 0.12f

        const val VariationSelector = 0xFE0F

        /** The one character a picture key stands for. */
        fun codepointOf(text: String): Int {
            val codepoints = text.codePoints().toArray().let {
                if (it.size == 2 && it[1] == VariationSelector) intArrayOf(it[0]) else it
            }
            require(codepoints.size == 1) {
                "a picture stands for one character, and \"$text\" is ${codepoints.size}; " +
                    "sequences joined into one emoji are not supported"
            }
            return codepoints[0]
        }
    }

    /** The font behind [style]. Throws, naming what is registered, when there is none. */
    fun fontFor(style: TextStyle): BitmapFont {
        val key = Key(style.family, style.size.roundToInt())
        val registered = fonts[key] ?: missing(key, "")
        val fallbacks = fallbacksOf(key.family)
        // A family that generates on demand goes through the chain even with nothing behind it,
        // so that the new glyphs are uploaded by the atlas rather than by LibGDX in the middle of
        // measuring.
        if (fallbacks.isEmpty() && !registered.onDemand) return registered.font

        chains[key]?.let { return it }
        val sources = fallbacks.map { family ->
            val fallback = Key(family, key.size)
            fonts[fallback]?.let { GlyphSource.Font(it.font) }
                ?: pictures[fallback]?.let { GlyphSource.Pictures(it, atlas::pageRegion) }
                ?: missing(fallback, ", which ${key.family} falls back to")
        }
        val data = FallbackFontData(registered.font, sources)
        return FallbackFont(data, data.regions).also { chains[key] = it }
    }

    private fun missing(key: Key, context: String): Nothing {
        val sizes = sizesOf(key.family)
        val detail = if (sizes.isEmpty()) {
            "no font is registered under that name. Registered names: ${families().ifEmpty { "none" }}"
        } else {
            "that name is registered at $sizes"
        }
        val pictureOnly = sizes.isNotEmpty() && fonts.keys.none { it.family == key.family }
        if (pictureOnly && context.isEmpty()) {
            error("no font for ${key.family} at ${key.size}: that name is pictures, which can only be a fallback")
        }
        error("no font for ${key.family} at ${key.size}$context: $detail")
    }

    fun families(): List<String> = (fonts.keys + pictures.keys).map { it.family }.distinct()

    fun sizesOf(family: String): List<Int> =
        (fonts.keys + pictures.keys).filter { it.family == family }.map { it.size }.distinct().sorted()

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

        val layout = if (style.maxLines <= 0 || linesIn(wrapped) <= style.maxLines) {
            finish(text, wrapped, font, style)
        } else {
            val cut = longestPrefixFitting(font, text, maxWidth, style)
            finish(text, layoutOf(font, cut + style.ellipsis, maxWidth, style), font, style)
        }

        (font as? FallbackFont)?.data?.let { data ->
            if (data.grew) {
                // A character seen for the first time may have been generated into the atlas
                // just now, and has to be uploaded before it can be drawn.
                data.grew = false
                atlas.stale = true
            }
        }
        return layout
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
            val candidate = prefix(text, middle).trimEnd() + style.ellipsis
            if (linesIn(layoutOf(font, candidate, maxWidth, style)) <= style.maxLines) low = middle else high = middle - 1
        }
        return prefix(text, low).trimEnd()
    }

    /**
     * The first [length] chars of [text], one shorter if that would cut an emoji in half — half of
     * one is not a character any font has, and would come out as a box beside the ellipsis.
     */
    private fun prefix(text: String, length: Int): String =
        if (length in 1 until text.length && text[length - 1].isHighSurrogate()) text.take(length - 1) else text.take(length)

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
        chains.clear()
        pictures.clear()
        generators.forEach { it.dispose() }
        generators.clear()
        if (ownsAtlas) atlas.dispose()
    }
}
