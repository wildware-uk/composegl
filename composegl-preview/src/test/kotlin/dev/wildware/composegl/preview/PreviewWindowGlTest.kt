package dev.wildware.composegl.preview

import dev.wildware.composegl.lwjgl3.GlfwWindow
import dev.wildware.composegl.lwjgl3.Lwjgl3Backend
import dev.wildware.composegl.lwjgl3.StbFonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import java.io.File
import java.util.Arrays
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * The window on a real GPU: a preview is loaded, drawn into its picture and shown in the window, then
 * reloaded and drawn again. Read back as pixels, so "it draws" means pixels changed.
 *
 * Needs a display, so it runs in the OpenGL job under Xvfb and is skipped on the headless build.
 * Writes what the window showed to `build/screenshots/preview-window.png`.
 */
class PreviewWindowGlTest {

    @TempDir
    lateinit var temp: File

    @BeforeEach
    fun requireDisplay() = assumeTrue(!System.getenv("DISPLAY").isNullOrBlank(), "no display; this test needs a real GL context")

    @Test
    fun `the window draws a loaded preview, and the new build after a reload`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val font = File(checkNotNull(System.getProperty("composegl.preview.font")) { "composegl.preview.font is not set" })

        GlfwWindow("preview window test", Width, Height, visible = false, vsync = false).use { window ->
            val fonts = StbFonts(pageSize = 1024)
            fonts.register("default", font.readBytes(), listOf(12, 13, 14, 16, 18))
            Lwjgl3Backend(window, fonts).use { backend ->
                PreviewSession(folder.module(File(temp, "scratch")), backend).use { session ->
                    PreviewWindow(window, backend, session).use { live ->
                        assertTrue(session.reload())
                        session.select("greeting")
                        frames(live, from = 0)

                        val first = checkNotNull(live.picture("greeting")) { "the greeting was never pictured" }.readPixels()
                        val blue = count(first) { r, g, b -> near(r, 0x20) && near(g, 0x40) && near(b, 0xA0) }
                        val ink = count(first) { r, g, b -> r > 0xB0 && g > 0xB0 && b > 0xB0 }
                        assertTrue(blue > first.size / 4 / 2, "the preview's blue background was not drawn ($blue pixels)")
                        assertTrue(ink > 20, "no text was drawn on the preview ($ink light pixels)")

                        // The window shows that picture: its blue is on screen.
                        val screen = readWindow(window)
                        save(screen, window)
                        val onScreen = count(screen) { r, g, b -> near(r, 0x20) && near(g, 0x40) && near(b, 0xA0) }
                        assertTrue(onScreen > 240 * 80 / 2, "the window does not show the preview ($onScreen blue pixels)")

                        folder.install("fixtureV2", "basic")
                        live.post(LiveEvent.Reload)
                        frames(live, from = 100)

                        val second = checkNotNull(live.picture("greeting")).readPixels()
                        assertFalse(Arrays.equals(first, second), "the reloaded build drew exactly what the first did")
                        assertEquals("greeting", session.selected)
                    }
                }
            }
        }
    }

    private fun frames(live: PreviewWindow, from: Int) {
        repeat(6) { frame -> live.frame((from + frame) * 16_666_667L) }
    }

    private fun near(value: Int, target: Int) = kotlin.math.abs(value - target) <= 6

    private fun count(rgba: ByteArray, test: (Int, Int, Int) -> Boolean): Int {
        var found = 0
        for (at in rgba.indices step 4) {
            val r = rgba[at].toInt() and 0xFF
            val g = rgba[at + 1].toInt() and 0xFF
            val b = rgba[at + 2].toInt() and 0xFF
            if (test(r, g, b)) found++
        }
        return found
    }

    private fun readWindow(window: GlfwWindow): ByteArray {
        val size = window.framebuffer
        val bytes = BufferUtils.createByteBuffer(size.width.toInt() * size.height.toInt() * 4)
        GL11.glReadPixels(0, 0, size.width.toInt(), size.height.toInt(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    private fun save(rgba: ByteArray, window: GlfwWindow) {
        val width = window.framebuffer.width.toInt()
        val height = window.framebuffer.height.toInt()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = ((height - 1 - y) * width + x) * 4
                image.setRGB(x, y, (rgba[at].toInt() and 0xFF shl 16) or (rgba[at + 1].toInt() and 0xFF shl 8) or (rgba[at + 2].toInt() and 0xFF))
            }
        }
        val out = File("build/screenshots/preview-window.png")
        out.parentFile.mkdirs()
        ImageIO.write(image, "png", out)
    }

    private companion object {
        const val Width = 1000
        const val Height = 700
    }
}
