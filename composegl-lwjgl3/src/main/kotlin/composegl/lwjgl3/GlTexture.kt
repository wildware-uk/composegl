package composegl.lwjgl3

import composegl.ui.graphics.TextureHandle
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

/**
 * An OpenGL texture, or a part of one, wearing the toolkit's opaque handle.
 *
 * The toolkit sees a width and a height. This side knows the texture name and where in it the
 * picture lives, which is what lets an atlas hand out many pictures that all cost one texture.
 *
 * Texture coordinates count y downwards, like everything else in the toolkit: [v] is the top edge
 * and [v2] the bottom. Rows are uploaded top first, so that matches what is in the texture.
 *
 * Owning is explicit. A region of an atlas does not own the atlas, and deleting the name out from
 * under everything else that shares it is the bug this is here to make impossible.
 */
class GlTexture(
    val name: Int,
    override val width: Int,
    override val height: Int,
    val u: Float = 0f,
    val v: Float = 0f,
    val u2: Float = 1f,
    val v2: Float = 1f,
    private val owned: Boolean = false,
) : TextureHandle, AutoCloseable {

    /** A part of this picture, in pixels from its top-left. Owns nothing. */
    fun region(left: Int, top: Int, width: Int, height: Int): GlTexture {
        val acrossWhole = (u2 - u) / this.width
        val downWhole = (v2 - v) / this.height
        return GlTexture(
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
        if (owned) GL11.glDeleteTextures(name)
    }

    companion object {

        /**
         * Uploads [pixels] — four bytes each, red first, the top row first — as a new texture.
         *
         * @param smooth true to sample it smoothly. False keeps pixel art crisp, and is what a
         *   nine-patch usually wants.
         */
        fun rgba(width: Int, height: Int, pixels: ByteBuffer, smooth: Boolean = true): GlTexture {
            require(width > 0 && height > 0) { "a texture cannot be ${width}x$height" }
            val name = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels,
            )
            val filter = if (smooth) GL11.GL_LINEAR else GL11.GL_NEAREST
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter)
            // Clamped, because every edge of every picture here is an edge, and repeating it is
            // how a nine-patch's corner ends up with a stripe of the opposite corner in it.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            return GlTexture(name, width, height, owned = true)
        }

        /** The same, from an ordinary byte array. */
        fun rgba(width: Int, height: Int, pixels: ByteArray, smooth: Boolean = true): GlTexture {
            require(pixels.size >= width * height * 4) {
                "a ${width}x$height picture needs ${width * height * 4} bytes, got ${pixels.size}"
            }
            val buffer = BufferUtils.createByteBuffer(pixels.size).put(pixels)
            buffer.flip()
            return rgba(width, height, buffer, smooth)
        }

        /**
         * Decodes a PNG, JPEG or the other formats stb_image reads, and uploads it.
         *
         * The whole of this backend's asset loading. A game on this backend loads its own files;
         * LibGDX is the one that ships an asset manager, and that is part of why it is the
         * reference backend.
         */
        fun decode(encoded: ByteArray, smooth: Boolean = true): GlTexture {
            val bytes = BufferUtils.createByteBuffer(encoded.size).put(encoded)
            bytes.flip()
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                // Four channels whatever the file has, so there is one upload path and one format.
                val pixels = STBImage.stbi_load_from_memory(bytes, width, height, channels, 4)
                    ?: error("stb_image could not read that picture: ${STBImage.stbi_failure_reason()}")
                try {
                    return rgba(width[0], height[0], pixels, smooth)
                } finally {
                    STBImage.stbi_image_free(pixels)
                }
            }
        }
    }
}
