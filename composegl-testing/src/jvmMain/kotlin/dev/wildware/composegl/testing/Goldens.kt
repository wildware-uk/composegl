package dev.wildware.composegl.testing

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Golden images: what the renderer drew last time somebody looked at it and agreed.
 *
 * The recording canvas answers nearly every question this project has without a GPU, and it cannot
 * answer this one: whether the pixels are right. A distance field that is a shade too soft, a
 * scissor left one pixel short, a glyph half off its baseline — none of those change a single draw
 * call, and all of them are obvious in a picture.
 *
 * Comparison is tolerant on purpose. These run on llvmpipe here and on whatever Mesa a CI runner
 * happens to have, and two software rasterisers disagree about the last bit of an antialiased edge.
 * A golden that fails on a rounding difference is a golden that gets regenerated without being
 * read, which is worse than not having one. So a pixel counts as different only when a channel is
 * off by more than [ChannelTolerance], the picture fails only when more than [MaxDifferingFraction]
 * of it differs, and a wholesale shift too small to trip either is caught by [MaxMeanDifference].
 *
 * On a failure the actual, the expected and a difference map are written next to each other under
 * `build/screenshots`, and CI keeps them, so the first question — "what does it look like now?" —
 * is answered without anybody rerunning anything.
 *
 * Set `COMPOSEGL_UPDATE_GOLDENS=1` to write what was drawn as the new golden. Look at the diff
 * before you commit it.
 */
object Goldens {

    /** A channel off by this much or less is the two rasterisers disagreeing, not a change. */
    const val ChannelTolerance = GoldenRule.ChannelTolerance

    /** How much of a picture may differ before it counts as a different picture. */
    const val MaxDifferingFraction = GoldenRule.MaxDifferingFraction

    /** Catches a change that moved everything a little rather than something a lot. */
    const val MaxMeanDifference = GoldenRule.MaxMeanDifference

    private val updating: Boolean = System.getenv("COMPOSEGL_UPDATE_GOLDENS") == "1"

    private val goldenDirectory = File("src/test/resources/goldens")
    private val reportDirectory = File("build/screenshots")

    /**
     * Fails unless [actual] matches the golden called [name].
     *
     * A missing golden is a failure, not a pass: a test that quietly writes its own expectation the
     * first time it runs is a test that can never fail.
     *
     * @param directory where the golden lives. A multiplatform module keeps its test resources
     *   somewhere other than `src/test`, and a backend can be held to another backend's goldens.
     * @param updatable whether `COMPOSEGL_UPDATE_GOLDENS=1` may overwrite it. Pass false when
     *   [directory] belongs to another backend: those pictures are that backend's to change.
     */
    fun assertMatches(
        name: String,
        actual: BufferedImage,
        directory: File = goldenDirectory,
        updatable: Boolean = true,
    ) {
        val golden = File(directory, "$name.png")

        if (updating && updatable) {
            directory.mkdirs()
            ImageIO.write(actual, "png", golden)
            return
        }

        if (!golden.exists()) {
            val written = report(name, actual, null, null)
            throw AssertionError(
                "there is no golden for \"$name\". What was drawn is at $written — look at it, " +
                    "and if it is right, rerun with COMPOSEGL_UPDATE_GOLDENS=1.",
            )
        }

        val expected = ImageIO.read(golden)
            ?: throw AssertionError("the golden for \"$name\" is not a picture: $golden")

        if (expected.width != actual.width || expected.height != actual.height) {
            report(name, actual, expected, null)
            throw AssertionError(
                "\"$name\" is ${actual.width}x${actual.height}, but its golden is " +
                    "${expected.width}x${expected.height}",
            )
        }

        val comparison = compare(expected, actual)
        if (comparison.passes) return

        val written = report(name, actual, expected, comparison.difference)
        throw AssertionError("\"$name\" does not match its golden: ${comparison.summary}. See $written")
    }

    private class Comparison(val result: PictureComparison, val difference: BufferedImage) {
        val passes: Boolean get() = result.passes
        val summary: String get() = result.summary
    }

    /** The common rule, [comparePictures], with the pictures read out of and back into images. */
    private fun compare(expected: BufferedImage, actual: BufferedImage): Comparison {
        val width = expected.width
        val height = expected.height
        val result = comparePictures(width, height, pixelsOf(expected), pixelsOf(actual))
        return Comparison(result, imageOf(width, height) { x, y -> result.difference[y * width + x] })
    }

    private fun pixelsOf(image: BufferedImage): IntArray =
        IntArray(image.width * image.height) { at -> image.getRGB(at % image.width, at / image.width) and 0xFFFFFF }

    /** Writes what there is to look at, and says where it went. */
    private fun report(
        name: String,
        actual: BufferedImage,
        expected: BufferedImage?,
        difference: BufferedImage?,
    ): String {
        reportDirectory.mkdirs()
        ImageIO.write(actual, "png", File(reportDirectory, "$name-actual.png"))
        expected?.let { ImageIO.write(it, "png", File(reportDirectory, "$name-expected.png")) }
        difference?.let { ImageIO.write(it, "png", File(reportDirectory, "$name-difference.png")) }
        return reportDirectory.absolutePath
    }
}

/**
 * A grid of pixels read back from a framebuffer, as an ordinary picture.
 *
 * [rgb] is asked for the colour at a point in the toolkit's coordinates — y downwards from the top
 * — so each backend turns its own frame the right way up once, here, rather than in every test
 * that looks at one.
 */
fun imageOf(width: Int, height: Int, rgb: (Int, Int) -> Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            image.setRGB(x, y, rgb(x, y) and 0xFFFFFF)
        }
    }
    return image
}
