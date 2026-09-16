package dev.wildware.composegl.korge

import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.gl.GlConst
import dev.wildware.composegl.render.gl.GlDeviceTexture
import dev.wildware.composegl.ui.graphics.SceneSurface
import korlibs.graphics.AGFrameBuffer
import korlibs.graphics.AGTextureTargetKind
import korlibs.graphics.gl.AGOpengl
import korlibs.kgl.getIntegerv

/**
 * A picture [KorgeCanvas.scene] made: a KorGE framebuffer with depth and stencil, premultiplied, the
 * way up KorGE draws a render texture.
 *
 * KorGE's own rather than one the shared renderer makes, so that KorGE's batch — which draws into
 * the framebuffer on top of its render context's stack and nowhere else — can draw a scene into it.
 * Only the canvas that made it can fill it again or draw it. [close] gives it back to KorGE.
 */
class KorgeScenePicture internal constructor(internal val canvas: KorgeCanvas) : SceneSurface {

    /** The framebuffer, for a game that wants to show the scene somewhere KorGE draws as well. */
    val frameBuffer: AGFrameBuffer = AGFrameBuffer().also { it.setExtra(true, true) }

    override val width: Int get() = frameBuffer.width

    override val height: Int get() = frameBuffer.height

    override var closed: Boolean = false
        private set

    /** Whether KorGE drew it top row first, which it does into a render texture unless told not to. */
    internal var topRowFirst = true

    private var bound: BoundPicture? = null
    private var boundOn: AGOpengl? = null
    private var boundVersion = -1

    /**
     * This picture as the shared renderer binds it: KorGE's colour texture, adopted by its GL name,
     * bound through KorGE so its record stays true. Worked out again when the size, the context or
     * the way up changed.
     */
    internal fun bind(ag: AGOpengl): BoundPicture {
        check(!closed) { "this scene picture has been closed" }
        bound?.let {
            if (boundOn === ag && boundVersion == ag.contextVersion && it.texture.width == width &&
                it.texture.height == height && (it.v == 0f) == topRowFirst
            ) {
                return it
            }
        }
        val use = {
            ag.textureBind(frameBuffer.tex, AGTextureTargetKind.TEXTURE_2D)
            // A picture rendered at a fraction of the panel's pixels is stretched over it.
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MIN_FILTER, GlConst.LINEAR)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_MAG_FILTER, GlConst.LINEAR)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_S, GlConst.CLAMP_TO_EDGE)
            ag.gl.texParameteri(GlConst.TEXTURE_2D, GlConst.TEXTURE_WRAP_T, GlConst.CLAMP_TO_EDGE)
        }
        use()
        val name = ag.gl.getIntegerv(GlConst.TEXTURE_BINDING_2D)
        val top = if (topRowFirst) 0f else 1f
        return BoundPicture(
            GlDeviceTexture.adopt(name, width, height, use),
            u = 0f,
            v = top,
            u2 = 1f,
            v2 = 1f - top,
            premultiplied = true,
        ).also {
            bound = it
            boundOn = ag
            boundVersion = ag.contextVersion
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        bound = null
        frameBuffer.close()
    }
}
