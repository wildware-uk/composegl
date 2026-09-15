package dev.wildware.composegl.korge

import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDeviceTexture
import dev.wildware.composegl.ui.graphics.TextureHandle
import korlibs.graphics.AGTextureTargetKind
import korlibs.graphics.gl.AGOpengl
import korlibs.image.bitmap.Bitmap
import korlibs.kgl.getIntegerv
import korlibs.korge.render.RenderContext

/**
 * A KorGE picture, wearing the toolkit's opaque handle.
 *
 * The toolkit sees a width, a height and nothing else. The canvas casts back to this, which is the
 * only place in either module that knows both halves.
 *
 * A [Bitmap] rather than an uploaded texture, because that is what a KorGE game already holds —
 * `resourcesVfs["hero.png"].readBitmap()` — and KorGE uploads a bitmap the first time it is drawn
 * and again whenever its contents change. Holding one of these needs no OpenGL.
 *
 * @param bitmap the picture. Straight alpha or premultiplied, either works: the canvas asks which.
 * @param smooth sample it smoothly when it is drawn at a size other than its own. Off by default,
 *   which keeps pixel art and one-to-one interface art crisp.
 */
class KorgeTexture(val bitmap: Bitmap, val smooth: Boolean = false) : TextureHandle {

    override val width: Int get() = bitmap.width

    override val height: Int get() = bitmap.height

    private var bound: BoundPicture? = null
    private var boundOn: AGOpengl? = null
    private var boundVersion = -1

    /**
     * This picture as the shared renderer binds it: KorGE's own texture for the bitmap, adopted by
     * its GL name. Binding goes through KorGE, which is what uploads the bitmap when it changed and
     * keeps KorGE's record of it true. Worked out once per context.
     */
    internal fun bind(context: RenderContext, ag: AGOpengl): BoundPicture {
        bound?.let { if (boundOn === ag && boundVersion == ag.contextVersion) return it }
        val filter = if (smooth) GlConst.LINEAR else GlConst.NEAREST
        val use = {
            ag.textureBind(context.getTex(bitmap).base, AGTextureTargetKind.TEXTURE_2D)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, filter)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, filter)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        }
        use()
        val name = ag.gl.getIntegerv(GlConst.TEXTURE_BINDING_2D)
        return BoundPicture(GlDeviceTexture.adopt(name, width, height, use), premultiplied = bitmap.premultiplied).also {
            bound = it
            boundOn = ag
            boundVersion = ag.contextVersion
        }
    }
}
