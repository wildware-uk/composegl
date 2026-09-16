package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.backend.HeadlessBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A reload: the module's classes swapped for a new build, and the new build is what draws.
 *
 * Every test here runs with no window and no GPU. The previews draw into a recording canvas, so
 * "what draws" is the text that was drawn.
 */
class PreviewReloadTest {

    @TempDir
    lateinit var temp: File

    private val backend = HeadlessBackend()
    private val sessions = mutableListOf<PreviewSession>()

    @AfterEach
    fun close() = sessions.forEach { it.close() }

    private fun session(folder: ModuleFolder) =
        PreviewSession(folder.module(File(temp, "scratch")), backend).also { sessions += it }

    @Test
    fun `a reload draws the newly compiled preview, classes and resources both`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)

        assertTrue(session.reload())
        assertEquals(listOf("farewell", "greeting"), session.names)
        assertEquals(listOf("greeting: version one", "ready"), session.textsOf("greeting", backend))
        assertEquals(listOf("farewell: goodbye one"), session.textsOf("farewell", backend))
        val first = session.classLoader

        folder.install("fixtureV2", "basic")
        assertTrue(session.reload())

        assertEquals(listOf("greeting: version two", "ready"), session.textsOf("greeting", backend))
        assertEquals(listOf("farewell: goodbye two"), session.textsOf("farewell", backend))
        assertNotSame(first, session.classLoader, "a reload loads the module in a new class loader")
    }

    @Test
    fun `the module's classes come from the newest build even when the kept layer has them too`() {
        // A build file that forgot to take the module's own output off the window's classpath puts
        // the first build in the kept layer. A parent-first loader would then run it for ever.
        val stale = ModuleFolder(File(temp, "stale")).apply { install("fixtureV1", "basic") }
        val kept = java.net.URLClassLoader(arrayOf(stale.classes.toURI().toURL(), stale.resources.toURI().toURL()), javaClass.classLoader)
        val folder = ModuleFolder(File(temp, "module")).apply { install("fixtureV2", "basic") }
        val module = ReloadableModule(listOf(folder.classes), listOf(folder.resources), kept, scratch = File(temp, "scratch"))
        val session = PreviewSession(module, backend).also { sessions += it }

        assertTrue(session.reload())

        assertEquals(listOf("greeting: version two", "ready"), session.textsOf("greeting", backend))
        assertEquals(listOf("farewell: goodbye two"), session.textsOf("farewell", backend))
    }

    @Test
    fun `selection and gallery mode survive a reload`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()

        session.select("greeting")
        session.gallery = true
        folder.install("fixtureV2", "basic")
        session.reload()

        assertEquals("greeting", session.selected)
        assertTrue(session.gallery)
        assertNull(session.notice)
    }

    @Test
    fun `a deleted preview moves the selection to the first one and says so`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()
        session.select("farewell")

        folder.install("fixtureV3", "basic")
        assertTrue(session.reload())

        assertEquals(listOf("greeting"), session.names)
        assertEquals("greeting", session.selected)
        val notice = checkNotNull(session.notice)
        assertTrue("farewell" in notice && "greeting" in notice, notice)
        assertEquals(listOf("greeting: version three"), session.textsOf("greeting", backend))
    }

    @Test
    fun `a preview that throws only breaks its own tile`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "throwing")
        val session = session(folder)
        session.gallery = true

        assertTrue(session.reload())
        session.frame(0L)
        session.frame(16_666_667L)

        val boom = checkNotNull(session.stage("boom"))
        val failure = checkNotNull(boom.failure)
        assertTrue("boom from the preview" in failure, failure)
        assertTrue("BoomPreview" in failure, "the failure names where it was thrown:\n$failure")

        val steady = checkNotNull(session.stage("steady"))
        assertNull(steady.failure)
        assertEquals(listOf("still standing"), session.textsOf("steady", backend))
        assertNull(session.banner, "one broken preview is its tile's business, not the whole window's")
    }

    @Test
    fun `a build that cannot be loaded keeps the last good previews on screen`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()
        session.select("farewell")

        // The next build adds a preview discovery refuses.
        folder.install("fixtureV2", "basic")
        folder.add("fixtureV1", "invalid")
        assertFalse(session.reload())

        val banner = session.banner
        assertTrue(banner is Banner.LoadFailed, "banner was $banner")
        assertTrue("NeedsArgumentPreview" in banner!!.message, banner.message)
        assertEquals(listOf("farewell", "greeting"), session.names)
        assertEquals("farewell", session.selected)
        assertEquals(listOf("greeting: version one", "ready"), session.textsOf("greeting", backend))

        // Fixed: the banner goes and the new build draws.
        folder.install("fixtureV2", "basic")
        assertTrue(session.reload())
        assertNull(session.banner)
        assertEquals(listOf("greeting: version two", "ready"), session.textsOf("greeting", backend))
    }

    @Test
    fun `a failed compile keeps the last good previews and shows the first error until the next good compile`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()
        val loader = session.classLoader

        val error = CompileError("/game/src/main/kotlin/Menus.kt", 12, 5, "Unresolved reference 'Buton'.")
        session.compileFailed(CompileResult.Failed(error, "e: file:///game/src/main/kotlin/Menus.kt:12:5 Unresolved reference 'Buton'."))

        val banner = session.banner
        assertTrue(banner is Banner.CompileFailed, "banner was $banner")
        assertTrue("/game/src/main/kotlin/Menus.kt:12" in banner!!.message, banner.message)
        assertTrue(loader === session.classLoader, "a failed compile reloads nothing")
        assertEquals(listOf("greeting: version one", "ready"), session.textsOf("greeting", backend))

        folder.install("fixtureV2", "basic")
        session.reload()
        assertNull(session.banner)
    }

    @Test
    fun `once a restart is needed nothing reloads, so nothing stale runs`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()

        session.restartNeeded("build.gradle.kts changed")
        folder.install("fixtureV2", "basic")
        assertFalse(session.reload())

        val banner = session.banner
        assertTrue(banner is Banner.RestartNeeded, "banner was $banner")
        assertTrue("restart the preview" in banner!!.message, banner.message)
        assertEquals(listOf("greeting: version one", "ready"), session.textsOf("greeting", backend))

        // A compile error afterwards does not hide that the window is out of date.
        session.compileFailed(CompileResult.Failed(null, "boom"))
        assertTrue(session.banner is Banner.RestartNeeded)
    }

    @Test
    fun `a hang that ends is named on the window`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val session = session(folder)
        session.reload()

        session.hangEnded(Hang("preview \"greeting\"", 7_000_000_000L))

        val notice = checkNotNull(session.notice)
        assertTrue("greeting" in notice && "7" in notice, notice)
    }
}
