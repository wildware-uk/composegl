package dev.wildware.composegl.lwjgl3.preview

import dev.wildware.composegl.lwjgl3.GlRenderTarget
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.preview.uiTest
import java.awt.image.BufferedImage

/**
 * Turns a `@Preview` into a picture, on a real GPU.
 *
 * Each preview is composed at its own size, settled the way a test settles a screen — every
 * animation it starts has played out — and drawn into a [GlRenderTarget] exactly its size. Drawing
 * off the window rather than onto it is what lets a preview be any size at all: a 1920 by 1080
 * screen previews from inside a window of one pixel.
 *
 * Needs a current GL context, which [backend]'s window provides. `renderPreviews` makes one.
 */
class PreviewRenderer(val backend: Lwjgl3Backend) {

    private val draw = DrawPass(backend.canvas)

    /**
     * [preview], drawn. Transparent where the preview's background is and nothing was drawn;
     * everywhere else exactly what a game would show.
     */
    fun render(preview: PreviewFunction): BufferedImage = uiTest(preview, backend).use { ui ->
        GlRenderTarget(preview.width, preview.height).use { target ->
            target.draw(backend.canvas) { draw.draw(ui.root) }
            read(target)
        }
    }

    /**
     * The target's pixels, the right way up and with the opacity taken back out.
     *
     * A render target holds premultiplied colour, and a PNG holds straight colour; a half-faded
     * panel written without dividing would come out darker in the picture than in the game.
     */
    private fun read(target: GlRenderTarget): BufferedImage {
        val width = target.width
        val height = target.height
        val bytes = target.readPixels()

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // OpenGL hands back the bottom row first.
                val at = ((height - 1 - y) * width + x) * 4
                val alpha = bytes[at + 3].toInt() and 0xFF
                fun straight(channel: Int): Int {
                    val premultiplied = bytes[at + channel].toInt() and 0xFF
                    return if (alpha == 0) 0 else minOf(255, (premultiplied * 255 + alpha / 2) / alpha)
                }
                image.setRGB(x, y, (alpha shl 24) or (straight(0) shl 16) or (straight(1) shl 8) or straight(2))
            }
        }
        return image
    }
}
