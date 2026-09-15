package dev.wildware.composegl.korge

import dev.wildware.composegl.ui.graphics.TextureHandle
import korlibs.image.bitmap.Bitmap

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
}
