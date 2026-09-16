package dev.wildware.composegl.preview

import dev.wildware.composegl.ui.preview.PreviewFunction
import dev.wildware.composegl.ui.preview.Previews
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration

/**
 * A module's own compiled classes and resources: the part of a preview session that is loaded again
 * after every compile.
 *
 * Everything else — the JDK, the Compose runtime, composegl and the module's library dependencies —
 * is the **kept** layer, [kept], loaded once for the whole session. Each [load] puts the module's
 * output in a fresh class loader on top of it, so the new build's classes replace the old ones
 * without anything in the kept layer noticing.
 *
 * Each load copies the output first, into a folder of its own under [scratch]. A compile rewrites
 * `build/classes` in place, and a class the old build had not needed yet would otherwise be read
 * from the new build's files, halfway through being written.
 *
 * @param classes the module's class folders or jars, such as `build/classes/kotlin/main`.
 * @param resources the module's resource folders, such as `build/resources/main`.
 * @param kept the kept layer: what the module's classes are loaded on top of.
 * @param packageName only previews in this package or below it; empty for all of them.
 * @param scratch where each build is copied to. Emptied as builds are let go of.
 */
class ReloadableModule(
    val classes: List<File>,
    val resources: List<File> = emptyList(),
    val kept: ClassLoader = ReloadableModule::class.java.classLoader,
    val packageName: String = "",
    private val scratch: File = Files.createTempDirectory("composegl-preview").toFile(),
) {

    private var builds = 0

    /** Told about each class loader as it is made, before discovery runs. For the leak tests. */
    internal var onLoader: ((ClassLoader) -> Unit)? = null

    /**
     * The current build: copied, loaded in a new class loader, and searched for previews with the
     * same discovery `renderPreviews` uses.
     *
     * Throws what discovery throws — a preview with arguments, two previews with one name — having
     * let go of the half-made loader first.
     */
    fun load(): LoadedModule {
        builds++
        val folder = File(scratch, "build-$builds")
        folder.deleteRecursively()
        val copiedClasses = classes.mapIndexed { index, root -> copy(root, File(folder, "classes-$index")) }.filterNotNull()
        val copiedResources = resources.mapIndexed { index, root -> copy(root, File(folder, "resources-$index")) }.filterNotNull()

        val loader = ModuleClassLoader(
            "composegl-preview build $builds",
            (copiedClasses + copiedResources).map { it.toURI().toURL() }.toTypedArray(),
            kept,
        )
        try {
            onLoader?.invoke(loader)
            val previews = Previews.find(copiedClasses, loader, packageName)
            return LoadedModule(loader, previews, folder)
        } catch (failure: Throwable) {
            runCatching { loader.close() }
            folder.deleteRecursively()
            throw failure
        }
    }

    /** [root] copied to [to], or null when there is nothing there yet. */
    private fun copy(root: File, to: File): File? = when {
        root.isDirectory -> to.also { root.copyRecursively(it, overwrite = true) }
        root.isFile -> File(to, root.name).also { root.copyTo(it, overwrite = true) }
        else -> null
    }
}

/**
 * One build of a module, loaded: its class loader and the previews found in it.
 *
 * Holding one of these holds every class of that build. [close] it once nothing composed from it is
 * still alive — after disposing the compositions, never before.
 */
class LoadedModule internal constructor(
    val loader: ClassLoader,
    val previews: List<PreviewFunction>,
    private val folder: File,
) : AutoCloseable {

    override fun close() {
        (loader as? URLClassLoader)?.close()
        folder.deleteRecursively()
    }
}

/**
 * Loads the module's own classes itself, and everything else from the kept layer.
 *
 * Child first, for the names this build has. A build file that left the module's output on the
 * window's classpath as well would otherwise put the first build in the kept layer, and an ordinary
 * parent-first loader would keep running that build however many times the module recompiled.
 */
internal class ModuleClassLoader(name: String, urls: Array<URL>, parent: ClassLoader) : URLClassLoader(name, urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val type = findLoadedClass(name)
            ?: if (findResource(name.replace('.', '/') + ".class") != null) findClass(name) else super.loadClass(name, false)
        if (resolve) resolveClass(type)
        type
    }

    override fun getResource(name: String): URL? = findResource(name) ?: parent.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        Collections.enumeration(findResources(name).toList() + parent.getResources(name).toList())
}
