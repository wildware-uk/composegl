package dev.wildware.composegl.debug

import java.io.File
import java.io.IOException

/**
 * Debug windows remembered in a file, so a window dragged out of the way today is out of the way
 * tomorrow.
 *
 * ```kotlin
 * DebugWindowHost(state = rememberDebugWindowsState(FileDebugWindowStore(File("build/windows.txt")))) { Game() }
 * ```
 *
 * One `key=value` a line, sorted, so the file is short, readable and diffs quietly if somebody
 * commits it. Written to a file beside it first and then moved into place, so a game killed half way
 * through a save leaves the last good file rather than half of one. A file that cannot be read or
 * written is a fresh start, not a crash: these are debugging tools.
 */
class FileDebugWindowStore(val file: File) : DebugWindowStore {

    override fun load(): Map<String, String> = try {
        if (file.isFile) DebugWindowStoreText.read(file.readText()) else emptyMap()
    } catch (_: IOException) {
        emptyMap()
    }

    override fun save(values: Map<String, String>) {
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val next = File(file.absoluteFile.parentFile, file.name + ".tmp")
            next.writeText(DebugWindowStoreText.write(values))
            if (!next.renameTo(file)) {
                file.writeText(next.readText())
                next.delete()
            }
        } catch (_: IOException) {
            // Nowhere to write, a read-only install say: the windows still work, they only forget.
        }
    }
}

actual fun defaultDebugWindowStore(): DebugWindowStore =
    FileDebugWindowStore(File(System.getProperty("user.dir") ?: ".", "composegl-debug-windows.txt"))
