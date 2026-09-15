package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.TextureHandle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** How many frames the coin turns in, and how big each is. */
const val CoinFrames = 8
const val CoinSize = 32

/**
 * The tour's art by name: the example's crest, and a coin turning on its edge.
 *
 * @param crest the crest, cut from `ui/ui.png`.
 * @param coins the [coinSheet] as a texture, all frames side by side.
 * @param region cuts a part out of a texture; each backend has its own.
 */
fun <T : TextureHandle> showcaseAtlas(crest: TextureHandle, coins: T, region: (T, Int, Int, Int, Int) -> TextureHandle): ArtAtlas =
    ArtAtlas.of(
        mapOf("icon/crest" to crest) +
            (0 until CoinFrames).associate { "coin_$it" to region(coins, it * CoinSize, 0, CoinSize, CoinSize) },
    )

/**
 * A coin turning on its edge, as one RGBA sheet of [CoinFrames] frames side by side.
 *
 * Drawn here rather than shipped as a picture, so a sprite animation has a real sheet to cut up
 * without adding art to the repository.
 */
fun coinSheet(): ByteArray {
    val width = CoinFrames * CoinSize
    val pixels = ByteArray(width * CoinSize * 4)
    val radius = 13f
    val centre = (CoinSize - 1) / 2f
    for (frame in 0 until CoinFrames) {
        val turn = cos(frame * PI / CoinFrames * 2).toFloat()
        val across = (radius * abs(turn)).coerceAtLeast(2f)
        val face = if (turn >= 0f) 0xF2C14E else 0xC9952E
        for (y in 0 until CoinSize) {
            for (x in 0 until CoinSize) {
                val dx = x - centre
                val dy = y - centre
                if ((dx / across) * (dx / across) + (dy / radius) * (dy / radius) > 1f) continue
                val innerAcross = (across - 2f).coerceAtLeast(0.5f)
                val rim = (dx / innerAcross) * (dx / innerAcross) + (dy / (radius - 2f)) * (dy / (radius - 2f)) > 1f
                val shine = turn >= 0f && !rim && dx < -across * 0.2f && dx > -across * 0.5f
                val colour = when {
                    rim -> 0x8A5A12
                    shine -> 0xFFE9A8
                    else -> face
                }
                val at = (y * width + frame * CoinSize + x) * 4
                pixels[at] = (colour shr 16).toByte()
                pixels[at + 1] = (colour shr 8).toByte()
                pixels[at + 2] = colour.toByte()
                pixels[at + 3] = 0xFF.toByte()
            }
        }
    }
    return pixels
}
