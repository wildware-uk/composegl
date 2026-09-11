package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.composegl.ui.graphics.TextureHandle

/**
 * A LibGDX picture, wearing the toolkit's opaque handle.
 *
 * The toolkit sees a width, a height and nothing else. The canvas casts back to this, which is the
 * only place in either module that knows both halves.
 */
class GdxTexture(val region: TextureRegion) : TextureHandle {

    constructor(texture: Texture) : this(TextureRegion(texture))

    override val width: Int get() = region.regionWidth

    override val height: Int get() = region.regionHeight

    /**
     * True when the packer turned this region on its side to save space.
     *
     * Whole-region drawing does not care, because LibGDX's own texture coordinates already account
     * for it. Drawing *part* of a region does, and gets it wrong silently, so the canvas refuses.
     */
    val rotated: Boolean get() = (region as? TextureAtlas.AtlasRegion)?.rotate == true
}
