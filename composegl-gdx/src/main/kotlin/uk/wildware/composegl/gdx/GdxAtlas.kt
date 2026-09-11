package uk.wildware.composegl.gdx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

/**
 * One texture for the whole interface.
 *
 * A draw call ends when the texture changes, so the cost of a screen is not how many rectangles
 * are on it — it is how many times the interface changes its mind about which picture it is
 * drawing from. A screen with four panels and six labels at three sizes was costing a draw call
 * for every switch between a rounded box and a word.
 *
 * So everything the toolkit itself draws lives on one page: every glyph, at every registered size,
 * of every registered family, and the single white texel that every plain rectangle, border and
 * shadow is made of. A panel with a label on it is then one texture and one draw call, and a
 * game's own art costs exactly one change, which is the irreducible minimum.
 *
 * Nothing here touches OpenGL until [refresh] is called, so a registry can be built and glyphs
 * measured on a machine with no context — which is how most of this module is tested.
 *
 * @param pageSize how big the page is. Bigger than everything will fit on, or it spills onto a
 *   second page and the saving goes away. A thousand square holds several sizes of a Latin face.
 * @param filter how glyphs are sampled. Nearest keeps text crisp at one-to-one, which is what a
 *   game that registered the sizes it needs should be getting.
 */
class GdxAtlas(
    pageSize: Int = 1024,
    private val filter: Texture.TextureFilter = Texture.TextureFilter.Nearest,
) : Disposable {

    /** Two pixels of padding, so a neighbouring glyph cannot bleed into one being magnified. */
    internal val packer = PixmapPacker(pageSize, pageSize, Pixmap.Format.RGBA8888, Padding, false)

    /**
     * The solid white texel that every plain rectangle is drawn from.
     *
     * The same object for the life of the atlas, filled in by [refresh] and updated in place
     * afterwards, so whatever is holding it never goes stale. Its texture is null until the first
     * [refresh], and the batch falls back to a texture of its own until then.
     */
    val white = TextureRegion()

    init {
        // Packed first, so it lands on page zero and stays there however many glyphs follow.
        val block = Pixmap(WhiteBlock, WhiteBlock, Pixmap.Format.RGBA8888).apply {
            setColor(1f, 1f, 1f, 1f)
            fill()
        }
        packer.pack(WhiteName, block)
        block.dispose()
    }

    /**
     * Uploads whatever has been packed since the last call, and re-finds the white texel.
     *
     * Needs an OpenGL context. Called for you when a font is registered; call it yourself only if
     * you pack into [packer] directly.
     */
    fun refresh() {
        packer.updatePageTextures(filter, filter, false)
        val rectangle = packer.getRect(WhiteName) ?: return
        val page = packer.pages[packer.getPageIndex(WhiteName)] ?: return
        val texture = page.texture ?: return
        // The middle of the block, a pixel in from every side. Sampling the very edge of a packed
        // region is how a rectangle picks up a neighbour's colour at the wrong filter setting.
        white.texture = texture
        white.setRegion(
            rectangle.x + 1,
            rectangle.y + 1,
            WhiteBlock - 2,
            WhiteBlock - 2,
        )
    }

    /** How many pages the glyphs and the white texel needed. More than one costs a draw call. */
    val pageCount: Int get() = packer.pages.size

    override fun dispose() = packer.dispose()

    private companion object {
        const val Padding = 2
        const val WhiteBlock = 4
        const val WhiteName = "composegl:white"
    }
}
