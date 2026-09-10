package composegl.gdx

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import composegl.ui.graphics.TextureHandle

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
}
