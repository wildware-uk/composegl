package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Array as GdxArray
import kotlin.math.roundToInt

/**
 * A glyph that is a picture rather than a letter: an emoji, a button face, anything with colours
 * of its own.
 *
 * Its own type so that the canvas can tell. A letter is white coverage and takes the text's colour;
 * a picture already has its colours, and tinting a yellow face red because the chat line is red
 * turns it into a red blob.
 */
internal class PictureGlyph : Glyph()

/** Where a family in a fallback chain gets its glyphs from. */
internal sealed interface GlyphSource {

    /** A font registered under that name and size. */
    class Font(val font: BitmapFont) : GlyphSource

    /**
     * Pictures registered under that name and size, by codepoint.
     *
     * Each glyph's [Glyph.yoffset] is measured from the baseline rather than from the top of a
     * line, because a picture has no line of its own: it sits on whichever font it is standing in
     * for, and that font's baseline is only known when the chain is built.
     */
    class Pictures(val glyphs: Map<Int, PictureGlyph>, val page: (Int) -> TextureRegion) : GlyphSource
}

/**
 * One family with its fallbacks behind it, as the one font LibGDX's text layout knows how to use.
 *
 * `GlyphLayout` takes one font, and a line of chat mixing English, Chinese and an emoji needs
 * three. Rather than laying text out a second way, this answers LibGDX's own question — which glyph
 * is this character — by asking the primary font first and each fallback after it, and hands back
 * a glyph already moved onto the primary's baseline. Wrapping, the line limit and the ellipsis all
 * keep working because they never learn that anything happened.
 *
 * Two things LibGDX's own version of this cannot do are done here. Characters past U+FFFF — which
 * is where nearly every emoji lives — arrive as two halves, and are put back together into one
 * glyph. And a variation selector, the invisible character that asks for the emoji style of ❤, is
 * skipped rather than drawn as a box.
 *
 * Every metric is the primary's, so a line is as tall as it was before a fallback existed, and a
 * fallback glyph that is taller than the primary's letters overhangs rather than pushing the line.
 */
internal class FallbackFont(data: FallbackFontData, regions: GdxArray<TextureRegion>) :
    BitmapFont(data, regions, false) {

    /**
     * Nothing to do: every glyph arrives already pointing into the atlas.
     *
     * The base class would work each glyph's texture coordinates out again here, which needs a
     * texture, and a registry measured with no GPU has none.
     */
    override fun load(data: BitmapFontData) = Unit

    override fun getData(): FallbackFontData = super.getData() as FallbackFontData
}

internal class FallbackFontData(
    private val primary: BitmapFont,
    private val fallbacks: List<GlyphSource>,
) : BitmapFontData() {

    /**
     * The pages every glyph handed out is drawn from, shared with the [FallbackFont] this belongs
     * to. Starts as the primary's own, so a glyph the primary had is handed out untouched.
     */
    val regions: GdxArray<TextureRegion> = GdxArray(primary.regions)

    /** Every codepoint asked for so far, and what it turned out to be. A miss is remembered too. */
    private val resolved = HashMap<Int, Glyph?>()

    /**
     * True when a glyph was looked up for the first time since this was last cleared.
     *
     * A font generating glyphs on demand packs a new one into the atlas the first time it is asked
     * for, and the atlas has to be uploaded again before that glyph can be drawn. Nothing else
     * can see that happen, so this says so.
     */
    var grew = false

    private val baseline: Float

    init {
        val from = primary.data
        flipped = from.flipped
        padTop = from.padTop
        padRight = from.padRight
        padBottom = from.padBottom
        padLeft = from.padLeft
        lineHeight = from.lineHeight
        capHeight = from.capHeight
        ascent = from.ascent
        descent = from.descent
        down = from.down
        blankLineScale = from.blankLineScale
        scaleX = from.scaleX
        scaleY = from.scaleY
        markupEnabled = from.markupEnabled
        cursorX = from.cursorX
        spaceXadvance = from.spaceXadvance
        xHeight = from.xHeight
        breakChars = from.breakChars
        xChars = from.xChars
        capChars = from.capChars
        baseline = baselineOf(from)
        missingGlyph = from.missingGlyph?.let { adopt(it, primary.regions[it.page], shift = 0, own = true) }
    }

    override fun getGlyph(ch: Char): Glyph? = glyphFor(ch.code)

    /** The glyph for [codepoint] from the first font in the chain that has one, or null. */
    fun glyphFor(codepoint: Int): Glyph? {
        if (resolved.containsKey(codepoint)) return resolved[codepoint]
        grew = true
        return resolve(codepoint).also { resolved[codepoint] = it }
    }

    private fun resolve(codepoint: Int): Glyph? {
        fromFont(primary, codepoint, own = true)?.let { return it }
        for (source in fallbacks) {
            when (source) {
                is GlyphSource.Font -> fromFont(source.font, codepoint, own = false)?.let { return it }
                is GlyphSource.Pictures -> source.glyphs[codepoint]?.let { picture ->
                    // Measured from the baseline, so the primary's baseline is the whole shift.
                    val shift = baseline.roundToInt().let { if (flipped) it else -it }
                    return adopt(picture, source.page(picture.page), shift, own = false)
                }
            }
        }
        return null
    }

    private fun fromFont(font: BitmapFont, codepoint: Int, own: Boolean): Glyph? {
        // A BitmapFont is indexed by a 16-bit char, so nothing past U+FFFF can be in one.
        if (codepoint > Char.MAX_VALUE.code) return null
        val data = font.data
        val glyph = data.getGlyph(codepoint.toChar()) ?: return null
        // A font generating on demand answers a character it does not have with its missing
        // glyph rather than with null, and the next font in the chain deserves a chance first.
        if (glyph === data.missingGlyph) return null
        val shift = if (own) 0 else (baselineOf(data) - baseline).roundToInt().let { if (flipped) -it else it }
        return adopt(glyph, font.regions[glyph.page], shift, own)
    }

    /**
     * [glyph], pointing at [region] among this font's pages and moved [shift] up.
     *
     * The same object when nothing needs to change, which is every glyph the primary had on a page
     * the primary started with — so text with no fallback in it is laid out from exactly the
     * glyphs it always was.
     */
    private fun adopt(glyph: Glyph, region: TextureRegion, shift: Int, own: Boolean): Glyph {
        val page = regions.indexOf(region, true).takeIf { it >= 0 } ?: regions.size.also { regions.add(region) }
        if (own && shift == 0 && page == glyph.page && glyph !is PictureGlyph) return glyph

        return (if (glyph is PictureGlyph) PictureGlyph() else Glyph()).also {
            it.id = glyph.id
            it.srcX = glyph.srcX
            it.srcY = glyph.srcY
            it.width = glyph.width
            it.height = glyph.height
            it.u = glyph.u
            it.v = glyph.v
            it.u2 = glyph.u2
            it.v2 = glyph.v2
            it.xoffset = glyph.xoffset
            it.yoffset = glyph.yoffset + shift
            it.xadvance = glyph.xadvance
            it.fixedWidth = glyph.fixedWidth
            it.page = page
            // Kerning is between two glyphs of one font. A pair from two fonts has no entry to
            // look up, and a borrowed table would answer for a different pair of shapes.
            it.kerning = if (own) glyph.kerning else null
        }
    }

    /**
     * LibGDX's own loop, a codepoint at a time instead of a char at a time.
     *
     * Kept line for line where it can be, because the rest of `GlyphLayout` depends on what this
     * leaves in the run: one advance per glyph, one more for the width of the last.
     */
    override fun getGlyphs(run: GlyphRun, str: CharSequence, start: Int, end: Int, lastGlyph: Glyph?) {
        if (end == start) return
        val glyphs = run.glyphs
        val xAdvances = run.xAdvances
        glyphs.ensureCapacity(end - start)
        xAdvances.ensureCapacity(end - start + 1)

        var last = lastGlyph
        var at = start
        while (at < end) {
            val ch = str[at++]
            if (ch == '\r') continue
            var codepoint = ch.code
            if (ch.isHighSurrogate() && at < end && str[at].isLowSurrogate()) {
                codepoint = Character.toCodePoint(ch, str[at++])
            }
            if (codepoint in Invisible) continue

            val glyph = glyphFor(codepoint) ?: missingGlyph ?: continue
            glyphs.add(glyph)
            xAdvances.add(
                if (last == null) {
                    if (glyph.fixedWidth) 0f else -glyph.xoffset * scaleX - padLeft
                } else {
                    (last.xadvance + last.getKerning(ch)) * scaleX
                },
            )
            last = glyph

            if (markupEnabled && ch == '[' && at < end && str[at] == '[') at++
        }
        if (last != null) {
            xAdvances.add(
                if (last.fixedWidth) last.xadvance * scaleX else (last.width + last.xoffset) * scaleX - padRight,
            )
        }
    }

    private companion object {

        /**
         * Characters that change how the one before them looks and have no look of their own: the
         * two variation selectors, which pick the text or the emoji style of a symbol.
         */
        val Invisible = setOf(0xFE0E, 0xFE0F)

        /** Top of a line down to its baseline, which is where LibGDX puts the first one. */
        fun baselineOf(data: BitmapFontData): Float = data.capHeight + data.ascent
    }
}
