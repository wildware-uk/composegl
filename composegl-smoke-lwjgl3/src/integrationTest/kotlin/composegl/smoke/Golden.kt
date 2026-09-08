package composegl.smoke

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Golden-image comparison with a per-channel tolerance.
 *
 * Rendering is never bit-exact across drivers, so an equality check would be a test that fails
 * for reasons nobody can act on. A tolerance of 8 out of 255 absorbs antialiasing differences
 * while still catching a HUD that is upside down, the wrong colour, or missing.
 */
object Golden {

    private const val TOLERANCE = 8
    private val directory = File("src/integrationTest/resources/golden")

    /**
     * Compares [pixels] against the golden image called [name].
     *
     * When the golden does not exist it is written and the test fails, once, telling you to look
     * at it and commit it. That is deliberate: a golden nobody has looked at proves nothing.
     */
    fun assertMatches(name: String, pixels: IntArray, width: Int, height: Int) {
        val file = File(directory, "$name.png")
        if (!file.exists()) {
            write(file, pixels, width, height)
            error("No golden for '$name'. One has been written to ${file.path}. Look at it, and commit it if it is right.")
        }

        val expected = ImageIO.read(file)
            ?: error("${file.path} is not a readable PNG")
        check(expected.width == width && expected.height == height) {
            "golden '$name' is ${expected.width}x${expected.height}, render is ${width}x$height"
        }

        var worst = 0
        var worstAt = -1
        var differing = 0
        for (i in pixels.indices) {
            val actual = pixels[i]
            val golden = expected.getRGB(i % width, i / width)
            val delta = maxOf(
                abs(((actual shr 24) and 0xff) - ((golden shr 24) and 0xff)),
                abs(((actual shr 16) and 0xff) - ((golden shr 16) and 0xff)),
                abs(((actual shr 8) and 0xff) - ((golden shr 8) and 0xff)),
                abs((actual and 0xff) - (golden and 0xff)),
            )
            if (delta > TOLERANCE) {
                differing++
                if (delta > worst) {
                    worst = delta
                    worstAt = i
                }
            }
        }

        if (differing > 0) {
            val actualFile = File(directory, "$name-actual.png")
            write(actualFile, pixels, width, height)
            error(
                "'$name' differs from its golden: $differing of ${pixels.size} pixels are more " +
                    "than $TOLERANCE apart, worst $worst at (${worstAt % width}, ${worstAt / width}). " +
                    "What was rendered is in ${actualFile.path}.",
            )
        }
    }

    fun write(file: File, pixels: IntArray, width: Int, height: Int) {
        file.parentFile.mkdirs()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        ImageIO.write(image, "png", file)
    }
}
