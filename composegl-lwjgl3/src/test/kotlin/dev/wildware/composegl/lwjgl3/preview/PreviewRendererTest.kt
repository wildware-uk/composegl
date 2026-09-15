package dev.wildware.composegl.lwjgl3.preview

import dev.wildware.composegl.lwjgl3.Gl
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.lwjgl3.StbFonts
import dev.wildware.composegl.testing.Goldens
import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.preview.Previews
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.glfw.GLFW
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * `@Preview` functions turned into PNGs on a real GPU.
 *
 * The previews are in `preview/samples`, compiled by the Compose compiler like any game's. Skipped
 * with no display; CI gives these one with Xvfb.
 */
class PreviewRendererTest {

    private val classes = File(PreviewRendererTest::class.java.protectionDomain.codeSource.location.toURI())

    private val font = File(requireNotNull(javaClass.getResource("/fonts/DejaVuSans.ttf")).toURI())

    private fun sample(name: String): PreviewFunction =
        Previews.find(listOf(classes), packageName = "dev.wildware.composegl.lwjgl3.preview.samples")
            .single { it.name == name }

    /** Draws [preview] with the test font registered as the default skin's family. */
    private fun render(preview: PreviewFunction): BufferedImage = Gl.render {
        val fonts = StbFonts(pageSize = 1024)
        fonts.register("default", font.readBytes(), listOf(14, 16, 18))
        val backend = Lwjgl3Backend(Gl.window, fonts)
        try {
            PreviewRenderer(backend).render(preview)
        } finally {
            backend.close()
        }
    }

    private fun assertPixel(expected: Int, image: BufferedImage, x: Int, y: Int, because: String) {
        val actual = image.getRGB(x, y)
        val off = (0..3).maxOf { abs((expected shr (it * 8) and 0xFF) - (actual shr (it * 8) and 0xFF)) }
        assertTrue(
            off <= 3,
            "$because at ($x, $y): expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
        )
    }

    @Test
    fun `a preview is drawn at its own size even when that is wider than the window`() {
        val image = render(sample("squares"))

        assertEquals(480 to 60, image.width to image.height)
        assertTrue(image.width > Gl.size, "the point is a preview that would not fit the window")

        assertPixel(0xFFFF0000.toInt(), image, 15, 15, "the red square, the right way up")
        assertPixel(0xFF0000FF.toInt(), image, 15, 45, "the blue ground under it")
        assertPixel(0xFF00FF00.toInt(), image, 460, 45, "the green square, past the window's edge")
        assertPixel(0xFF0000FF.toInt(), image, 460, 15, "and ground above that")
    }

    @Test
    fun `a transparent preview stays transparent and a faded square keeps its colour`() {
        val image = render(sample("see-through"))

        assertEquals(0, image.getRGB(2, 2) ushr 24, "nothing drawn is nothing in the picture")
        val faded = image.getRGB(20, 20)
        assertTrue(abs((faded ushr 24) - 128) <= 3, "half faded is half opaque: ${Integer.toHexString(faded)}")
        assertTrue(
            (faded shr 16 and 0xFF) >= 250,
            "and still white, not the grey a premultiplied pixel is: ${Integer.toHexString(faded)}",
        )
    }

    @Test
    fun `real widgets and text come out as they did when somebody looked`() {
        Goldens.assertMatches("preview-menu", opaque(render(sample("menu"))))
    }

    @Test
    fun `renderPreviews writes every preview as a png named after it`(@TempDir out: File) = Gl.render {
        val report = renderPreviews(
            PreviewOptions(
                classes = listOf(classes),
                out = out,
                fonts = listOf(PreviewFont("default", font, listOf(16))),
                packageName = "dev.wildware.composegl.lwjgl3.preview.samples",
            ),
            log = {},
        )

        assertEquals(emptyMap<String, Throwable>(), report.failed)
        assertEquals(listOf("fade-in.png", "menu.png", "see-through.png", "squares.png"), out.list()!!.sorted())
        val squares = ImageIO.read(File(out, "squares.png"))
        assertEquals(480 to 60, squares.width to squares.height)
        assertPixel(0xFFFF0000.toInt(), squares, 15, 15, "the file holds the drawing")
    }

    @Test
    fun `one broken preview is reported and the rest are still drawn`(@TempDir out: File) = Gl.render {
        val report = renderPreviews(
            PreviewOptions(
                listOf(classes),
                out,
                fonts = listOf(PreviewFont("default", font, listOf(16))),
                packageName = "dev.wildware.composegl.lwjgl3.preview.failing",
            ),
            log = {},
        )

        assertEquals(setOf("Broken", "Unfinished"), report.failed.keys)
        assertEquals("this screen is broken", report.failed.getValue("Broken").message)
        assertTrue("the shop screen" in report.failed.getValue("Unfinished").message!!)
        assertEquals(listOf("Working.png"), out.list()!!.toList())
    }

    @Test
    fun `a font path that is not there is refused without opening a window`(@TempDir out: File) = Gl.render {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            renderPreviews(
                PreviewOptions(
                    listOf(classes),
                    out,
                    fonts = listOf(PreviewFont("default", File(out, "missing.ttf"))),
                    packageName = "dev.wildware.composegl.lwjgl3.preview.samples",
                ),
                log = {},
            )
        }
        assertTrue("missing.ttf" in failure.message!!, failure.message)
        // A window opened for the run takes the context; one left open would still hold it.
        assertEquals(Gl.window.handle, GLFW.glfwGetCurrentContext(), "no hidden window left behind")
    }

    @Test
    fun `an animation the preview starts has finished in the picture`() {
        val image = render(sample("fade-in"))

        assertPixel(0xFFFFFFFF.toInt(), image, 20, 20, "the square faded all the way in")
    }

    @Test
    fun `a package with no previews is a mistake rather than an empty folder`(@TempDir out: File) {
        val failure = assertThrows(IllegalStateException::class.java) {
            renderPreviews(
                PreviewOptions(
                    listOf(classes),
                    out,
                    fonts = listOf(PreviewFont("default", font, listOf(16))),
                    packageName = "no.such.place",
                ),
                log = {},
            )
        }
        assertTrue("found no @Preview functions" in failure.message!!, failure.message)
    }

    @Test
    fun `no font is refused before anything is drawn`(@TempDir out: File) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            renderPreviews(PreviewOptions(listOf(classes), out), log = {})
        }
        assertTrue("--font" in failure.message!!, failure.message)
        assertEquals(0, out.list()!!.size, "and nothing was written")
    }

    @Test
    fun `the command line says where to look where to write and what fonts to use`() {
        val options = PreviewOptions.parse(
            listOf(
                "--classes", "a${File.pathSeparator}b",
                "--classes", "c",
                "--out", "build/previews",
                "--font", "default=fonts/DejaVuSans.ttf",
                "--font", "display=fonts/Big.ttf@34,48",
                "--package", "com.game.menus",
            ),
        )

        assertEquals(listOf(File("a"), File("b"), File("c")), options.classes)
        assertEquals(File("build/previews"), options.out)
        assertEquals(listOf("default", "display"), options.fonts.map { it.family })
        assertEquals(PreviewFont.DefaultSizes, options.fonts[0].sizes)
        assertEquals(listOf(34, 48), options.fonts[1].sizes)
        assertEquals(File("fonts/Big.ttf"), options.fonts[1].file)
        assertEquals("com.game.menus", options.packageName)
    }

    @Test
    fun `a command line missing what it needs says what`() {
        assertTrue(
            "--out" in assertThrows(IllegalArgumentException::class.java) {
                PreviewOptions.parse(listOf("--classes", "a"))
            }.message!!,
        )
        assertTrue(
            "--classes" in assertThrows(IllegalArgumentException::class.java) {
                PreviewOptions.parse(listOf("--out", "a"))
            }.message!!,
        )
        assertTrue(
            "family=path" in assertThrows(IllegalArgumentException::class.java) {
                PreviewOptions.parse(listOf("--classes", "a", "--out", "b", "--font", "no-equals.ttf"))
            }.message!!,
        )
    }

    /** Goldens are compared as RGB; every pixel of this preview is opaque anyway. */
    private fun opaque(image: BufferedImage): BufferedImage {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return rgb
    }
}
