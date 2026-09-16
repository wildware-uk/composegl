package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The window's own interface, with no window: what it shows and what clicking it does. */
class PreviewChromeTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a broken preview's tile shows what it threw, its neighbour's does not, and a click selects`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "throwing")
        val backend = HeadlessBackend()
        PreviewSession(folder.module(File(temp, "scratch")), backend).use { session ->
            session.gallery = true
            session.reload()

            uiTest(Size(1400f, 900f), backend) { PreviewChrome(session) { null } }.use { ui ->
                assertTrue(ui.texts("face:boom").any { "boom from the preview" in it }, ui.texts("face:boom").toString())
                assertEquals(emptyList<String>(), ui.texts("face:steady"))
                ui.assertDoesNotExist("banner")

                ui.click("pick:steady")
                assertEquals("steady", session.selected)
                assertFalse(session.gallery, "picking a preview shows it on its own")
                ui.assertExists("face:steady")
                ui.assertDoesNotExist("face:boom")

                session.restartNeeded("build.gradle.kts changed")
                ui.settle()
                assertTrue(ui.texts("banner").single().contains("restart the preview"), ui.texts("banner").toString())
            }
        }
    }
}
