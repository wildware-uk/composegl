package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.settle
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideFonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.ref.WeakReference

/**
 * A reload lets go of the build before it.
 *
 * A window left open all afternoon reloads hundreds of times. Every build it keeps is a class loader
 * with every class of the module in it, so a leak here is the window slowly running out of memory.
 * A weak reference to each old loader is the proof: once the collector has cleared it, nothing
 * anywhere still holds that build.
 */
class PreviewLeakTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `old class loaders are freed however many times the module reloads`() {
        val folder = ModuleFolder(File(temp, "module"))
        val backend = HeadlessBackend()
        val session = PreviewSession(folder.module(File(temp, "scratch")), backend)
        session.gallery = true
        val old = mutableListOf<WeakReference<ClassLoader>>()

        session.use {
            repeat(12) { build ->
                folder.install(if (build % 2 == 0) "fixtureV1" else "fixtureV2", "basic")
                assertTrue(session.reload(), "build $build did not load")
                // Composed, run for a few frames and drawn, as the window does, so the recomposer
                // has started the preview's coroutine and the tree holds its nodes.
                drawEverything(session, backend)
                old += WeakReference(checkNotNull(session.classLoader))
            }

            val current = old.removeAt(old.lastIndex)
            val kept = old.withIndex().filterNot { (_, loader) -> collected(loader) }.map { it.index }
            assertEquals(emptyList<Int>(), kept, "the class loaders of these builds were never freed")
            assertFalse(collected(current, attempts = 3), "the build on screen must still be loaded")
        }
    }

    @Test
    fun `a build that failed to load is freed too`() {
        val folder = ModuleFolder(File(temp, "module"))
        val session = PreviewSession(folder.module(File(temp, "scratch")), HeadlessBackend())
        session.use {
            folder.install("fixtureV1", "basic")
            session.reload()
            folder.install("fixtureV2", "basic")
            folder.add("fixtureV1", "invalid")
            val failed = failedLoad(session)
            assertTrue(collected(failed), "a build discovery refused was never freed")
        }
    }

    /**
     * The control: the same build, composed and run, then dropped **without** being disposed.
     *
     * This is what the session would do if it skipped disposal, and it must leak — the Compose
     * runtime keeps a running recomposer, and through it the composition and the preview's code,
     * reachable from its own global state. If this ever stops leaking, the test above no longer
     * proves that disposal is what frees a build, and needs a harder fixture.
     */
    @Test
    fun `a composition that is dropped without being disposed keeps its build alive`() {
        val folder = ModuleFolder(File(temp, "module"))
        folder.install("fixtureV1", "basic")
        val module = folder.module(File(temp, "scratch"))

        val loader = composeAndDrop(module)

        assertFalse(collected(loader), "an undisposed composition was freed, so the leak test proves nothing")
    }

    private fun drawEverything(session: PreviewSession, backend: HeadlessBackend) {
        session.names.forEach { name -> session.textsOf(name, backend) }
    }

    private fun failedLoad(session: PreviewSession): WeakReference<ClassLoader> {
        val seen = mutableListOf<WeakReference<ClassLoader>>()
        val module = session.module
        val listener = { loader: ClassLoader -> seen += WeakReference(loader) }
        module.onLoader = listener
        try {
            assertFalse(session.reload())
        } finally {
            module.onLoader = null
        }
        return seen.single()
    }

    private fun composeAndDrop(module: ReloadableModule): WeakReference<ClassLoader> {
        val loaded = module.load()
        val preview = loaded.previews.single { it.name == "greeting" }
        val host = UiHost()
        val fonts = HeadlessBackend().fonts
        host.setContent { ProvideFonts(fonts) { preview.screen() } }
        repeat(4) { frame -> host.settle(Viewport.oneToOne(preview.size), nanos = frame * 16_666_667L) }
        return WeakReference(loaded.loader)
    }
}
