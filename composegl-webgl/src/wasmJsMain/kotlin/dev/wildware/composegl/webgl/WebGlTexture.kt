package dev.wildware.composegl.webgl

import dev.wildware.composegl.render.BoundPicture
import dev.wildware.composegl.render.TextureResolver
import dev.wildware.composegl.render.gl.GlDeviceTexture
import dev.wildware.composegl.ui.graphics.TextureHandle
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLRenderingContext as GL
import org.khronos.webgl.WebGLTexture
import org.khronos.webgl.set
import org.w3c.dom.ImageBitmap
import org.khronos.webgl.TexImageSource
import org.w3c.fetch.Response
import org.w3c.files.Blob
import kotlin.js.Promise

/**
 * A WebGL texture, or a part of one, wearing the toolkit's opaque handle.
 *
 * The toolkit sees a width and a height. This side knows the texture object and where in it the
 * picture lives, which is what lets an atlas hand out many pictures that all cost one texture — and
 * batch as one, since the renderer compares textures by the object underneath.
 *
 * Texture coordinates count y downwards, like everything else in the toolkit: [v] is the top edge
 * and [v2] the bottom. Rows are uploaded top first, so that matches what is in the texture.
 *
 * Owning is explicit. A region of an atlas does not own the atlas, and deleting the texture out
 * from under everything else that shares it is the bug this is here to make impossible.
 */
class WebGlTexture(
    val gl: GL,
    val name: WebGLTexture,
    override val width: Int,
    override val height: Int,
    val u: Float = 0f,
    val v: Float = 0f,
    val u2: Float = 1f,
    val v2: Float = 1f,
    private val owned: Boolean = false,
) : TextureHandle, AutoCloseable {

    private var bound: BoundPicture? = null

    /** A part of this picture, in pixels from its top-left. Owns nothing. */
    fun region(left: Int, top: Int, width: Int, height: Int): WebGlTexture {
        val acrossWhole = (u2 - u) / this.width
        val downWhole = (v2 - v) / this.height
        return WebGlTexture(
            gl = gl,
            name = name,
            width = width,
            height = height,
            u = u + left * acrossWhole,
            v = v + top * downWhole,
            u2 = u + (left + width) * acrossWhole,
            v2 = v + (top + height) * downWhole,
        )
    }

    /** Deletes the texture, if this handle is the one that owns it. */
    override fun close() {
        if (owned) gl.deleteTexture(name)
    }

    companion object {

        /** How the shared renderer binds one of these: the texture object, adopted, and never deleted by it. */
        internal val Resolver = TextureResolver { handle ->
            (handle as? WebGlTexture)?.let { texture ->
                texture.bound ?: BoundPicture(
                    GlDeviceTexture.adopt(WebGl.handleOf(texture.name), texture.width, texture.height),
                    texture.u, texture.v, texture.u2, texture.v2,
                ).also { texture.bound = it }
            }
        }

        /**
         * Uploads [pixels] — four bytes each, red first, the top row first — as a new texture.
         *
         * @param smooth true to sample it smoothly. False keeps pixel art crisp, and is what a
         *   nine-patch usually wants.
         */
        fun rgba(gl: GL, width: Int, height: Int, pixels: ByteArray, smooth: Boolean = true): WebGlTexture {
            require(width > 0 && height > 0) { "a texture cannot be ${width}x$height" }
            require(pixels.size >= width * height * 4) {
                "a ${width}x$height picture needs ${width * height * 4} bytes, got ${pixels.size}"
            }
            val bytes = Uint8Array(width * height * 4)
            for (at in 0 until width * height * 4) bytes[at] = pixels[at]
            val name = create(gl, smooth)
            gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA, width, height, 0, GL.RGBA, GL.UNSIGNED_BYTE, bytes)
            gl.bindTexture(GL.TEXTURE_2D, null)
            return WebGlTexture(gl, name, width, height, owned = true)
        }

        /**
         * Uploads anything the browser can already draw: an `<img>` that has loaded, a `<canvas>`,
         * an `ImageBitmap`.
         *
         * The picture goes up exactly as it is — not premultiplied, and with no colour profile
         * applied — so a pixel in the file is the pixel the shader samples.
         */
        fun of(gl: GL, image: TexImageSource, width: Int, height: Int, smooth: Boolean = true): WebGlTexture {
            require(width > 0 && height > 0) { "a texture cannot be ${width}x$height" }
            val name = create(gl, smooth)
            upload(gl, image)
            gl.bindTexture(GL.TEXTURE_2D, null)
            return WebGlTexture(gl, name, width, height, owned = true)
        }

        /**
         * Fetches a PNG, a JPEG or anything else the browser decodes, and uploads it.
         *
         * The whole of this backend's asset loading, and suspending because a browser has no other
         * kind: nothing on a page can wait for a file.
         */
        suspend fun load(gl: GL, url: String, smooth: Boolean = true): WebGlTexture {
            val response = window.fetch(url).await<Response>()
            check(response.ok) { "could not fetch $url: ${response.status} ${response.statusText}" }
            val blob = response.blob().await<Blob>()
            val bitmap = decodeBitmap(blob).await<ImageBitmap>()
            return of(gl, bitmap, bitmap.width, bitmap.height, smooth)
        }

        /** A new texture, bound, with this backend's filtering and wrapping. */
        internal fun create(gl: GL, smooth: Boolean): WebGLTexture {
            val name = checkNotNull(gl.createTexture()) { "WebGL would not make a texture; the context is probably lost" }
            gl.bindTexture(GL.TEXTURE_2D, name)
            val filter = if (smooth) GL.LINEAR else GL.NEAREST
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, filter)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, filter)
            // Clamped, because every edge of every picture here is an edge, and repeating it is
            // how a nine-patch's corner ends up with a stripe of the opposite corner in it. WebGL
            // also refuses to sample a picture whose size is not a power of two any other way.
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
            return name
        }

        /** Puts [image] into whatever texture is bound, as the pixels it has and nothing else. */
        internal fun upload(gl: GL, image: TexImageSource) {
            gl.pixelStorei(GL.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)
            gl.pixelStorei(GL.UNPACK_COLORSPACE_CONVERSION_WEBGL, GL.NONE)
            gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA, GL.RGBA, GL.UNSIGNED_BYTE, image)
        }
    }
}

/** `createImageBitmap` with the colour left alone, so a file's pixels arrive as they were saved. */
private fun decodeBitmap(blob: Blob): Promise<ImageBitmap> =
    js("createImageBitmap(blob, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' })")
