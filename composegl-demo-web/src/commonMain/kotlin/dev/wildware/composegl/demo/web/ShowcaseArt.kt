package dev.wildware.composegl.demo.web

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.TextureHandle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

/** How big each grain is. A power of two, because a material is tiled and WebGL 1 insists. */
const val GrainSize = 128

/** The three grains a lit face can wear. */
enum class Grain { Wood, Paper, Metal }

/**
 * The materials the surfaces page lays across a lit face, as textures the backend has uploaded.
 *
 * Handed in rather than loaded, because making a texture is the one thing every backend does
 * differently. A page with none falls back to a plain colour and says so.
 */
class ShowcaseMaterials(val wood: TextureHandle, val paper: TextureHandle, val metal: TextureHandle)

/**
 * One grey grain as RGBA bytes, [GrainSize] square.
 *
 * Grey, because a material is multiplied into the face's colour: the same picture is oak, walnut
 * or brushed steel depending only on what tints it. Drawn here rather than shipped so the tour
 * still carries no image files.
 */
fun grainSheet(grain: Grain): ByteArray {
    val pixels = ByteArray(GrainSize * GrainSize * 4)
    var seed = grain.ordinal * 7919 + 13
    fun random(): Float {
        // One small deterministic generator, so a grain looks the same on every machine.
        seed = seed * 1_103_515_245 + 12_345
        return ((seed ushr 16) and 0x7FFF) / 32_767f
    }
    for (y in 0 until GrainSize) {
        for (x in 0 until GrainSize) {
            val level = when (grain) {
                // Growth rings banding along the plank, wandering the way a sawn one's do.
                Grain.Wood -> {
                    // Whole cycles across the tile in both directions, so a face wearing four of
                    // these has no seam where one ends and the next begins.
                    val across = 2.0 * PI * x / GrainSize
                    val wander = sin(across) * 5.0 + sin(across * 2 + 1.3) * 8.0
                    val ring = sin((y + wander) * 2.0 * PI * 2 / GrainSize) * 0.5 + 0.5
                    0.58 + ring * ring * 0.36 + random() * 0.08
                }
                // Felted and almost flat: the shine is what gives paper away, not the grain.
                Grain.Paper -> 0.88 + random() * 0.12
                // Scratches along one axis: the row decides the brightness, so they run the length.
                Grain.Metal -> 0.72 + ((y * 2_654_435_761u.toInt()) ushr 20 and 0xFF) / 255.0 * 0.24 + random() * 0.04
            }
            val byte = (level.coerceIn(0.0, 1.0) * 255).toInt().toByte()
            val at = (y * GrainSize + x) * 4
            pixels[at] = byte
            pixels[at + 1] = byte
            pixels[at + 2] = byte
            pixels[at + 3] = 0xFF.toByte()
        }
    }
    return pixels
}
