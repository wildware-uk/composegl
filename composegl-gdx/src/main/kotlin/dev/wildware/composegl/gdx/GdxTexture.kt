package dev.wildware.composegl.gdx

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.gl.GlDeviceTexture
import dev.wildware.composegl.ui.graphics.TextureHandle

/**
 * A LibGDX picture, wearing the toolkit's opaque handle.
 *
 * The toolkit sees a width, a height and nothing else. The shared renderer reaches the GL texture
 * behind it through [Resolver], which is the only place in either module that knows both halves.
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

    /** What the renderer was last handed, kept until the region or its texture changes. */
    private var bound: BoundPicture? = null

    private fun bound(): BoundPicture {
        val texture = region.texture
        val name = texture.textureObjectHandle
        bound?.let { last ->
            val same = (last.texture as GlDeviceTexture).name == name &&
                last.u == region.u && last.v == region.v && last.u2 == region.u2 && last.v2 == region.v2
            if (same) return last
        }
        // A region is the game's, and a game may move it — an animation, a flip — or LibGDX may
        // give its texture a new name after a lost context. Either way the picture is made again.
        return BoundPicture(
            GlDeviceTexture.adopt(name, texture.width, texture.height),
            region.u, region.v, region.u2, region.v2,
            rotated = rotated,
        ).also { bound = it }
    }

    internal companion object {
        /** How the shared renderer binds one of these: the GL name, adopted, and never deleted by it. */
        val Resolver = TextureResolver { handle -> (handle as? GdxTexture)?.bound() }
    }
}
