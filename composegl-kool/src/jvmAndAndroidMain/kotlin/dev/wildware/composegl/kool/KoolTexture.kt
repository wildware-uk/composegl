package dev.wildware.composegl.kool

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.backend.gl.LoadedTextureGl
import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.gl.GlDeviceTexture
import dev.wildware.composegl.ui.graphics.TextureHandle

/**
 * A Kool texture, wearing the toolkit's opaque handle.
 *
 * A [Texture2d] rather than pixels, because that is what a Kool game already holds —
 * `Assets.loadTexture2d("hero.png")` — and the picture is drawn with the filtering and wrapping the
 * game gave it. A texture Kool has not uploaded yet is uploaded the first time it is drawn, through
 * Kool, so Kool's record of it stays true. Holding one needs no OpenGL.
 *
 * @param premultiplied whether the texture's colours are already multiplied by alpha. Kool's are not,
 *   unless the game made them so.
 */
class KoolTexture(private val texture: Texture2d, private val premultiplied: Boolean = false) : TextureHandle {

    override val width: Int get() = texture.gpuTexture?.width ?: texture.uploadData?.width ?: 0

    override val height: Int get() = texture.gpuTexture?.height ?: texture.uploadData?.height ?: 0

    private var bound: BoundPicture? = null
    private var boundTo: LoadedTextureGl? = null

    /**
     * This picture as the shared renderer binds it: Kool's own GL texture, adopted by its name and never
     * deleted by the renderer. Null while Kool has neither the texture on the GPU nor data to put there.
     *
     * Bound through Kool, which also applies the texture's sampler settings. Kool sets those when it
     * draws with a texture rather than when it uploads one, so a texture only the interface has drawn
     * would otherwise still be waiting for its filtering — and an OpenGL texture with no mipmaps and
     * the default minifying filter samples as black. Going through Kool keeps its record of what it set
     * true as well.
     */
    internal fun bind(): BoundPicture? {
        if (texture.gpuTexture == null && texture.uploadData != null) {
            KoolSystem.requireContext().backend.uploadTextureData(texture)
        }
        val loaded = texture.gpuTexture as? LoadedTextureGl ?: return null
        bound?.let { if (boundTo === loaded && it.texture.width == loaded.width && it.texture.height == loaded.height) return it }
        val use = {
            loaded.bind()
            loaded.applySamplerSettings(texture.samplerSettings)
        }
        use()
        return BoundPicture(GlDeviceTexture.adopt(loaded.glTexture.handle, loaded.width, loaded.height, use), premultiplied = premultiplied).also {
            bound = it
            boundTo = loaded
        }
    }

    internal companion object {
        val Resolver = TextureResolver { handle -> (handle as? KoolTexture)?.bind() }
    }
}
