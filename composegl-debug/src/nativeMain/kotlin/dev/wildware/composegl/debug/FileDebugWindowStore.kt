package dev.wildware.composegl.debug

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.posix.rename

/**
 * Debug windows remembered in a file, so a window dragged out of the way today is out of the way
 * tomorrow.
 *
 * ```kotlin
 * DebugWindowHost(state = rememberDebugWindowsState(FileDebugWindowStore("build/windows.txt"))) { Game() }
 * ```
 *
 * The same file the JVM one writes, one `key=value` a line and sorted. Written to a file beside it
 * first and then moved into place, so a game killed half way through a save leaves the last good file
 * rather than half of one. A file that cannot be read or written is a fresh start, not a crash: these
 * are debugging tools.
 *
 * @param path where the file goes. On iOS that has to be somewhere the app may write, its Documents
 *   directory say; the app's own directory is read-only, which is why the windows there remember in
 *   memory unless a game hands one of these in.
 */
@OptIn(ExperimentalForeignApi::class)
class FileDebugWindowStore(val path: String) : DebugWindowStore {

    override fun load(): Map<String, String> {
        val file = fopen(path, "r") ?: return emptyMap()
        val text = StringBuilder()
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(LINE)
                while (true) {
                    val line = fgets(buffer, LINE, file) ?: break
                    text.append(line.toKString())
                }
            }
        } finally {
            fclose(file)
        }
        return DebugWindowStoreText.read(text.toString())
    }

    override fun save(values: Map<String, String>) {
        val next = "$path.tmp"
        // Nowhere to write, a read-only install say: the windows still work, they only forget.
        val file = fopen(next, "w") ?: return
        val written = fputs(DebugWindowStoreText.write(values), file) >= 0
        fclose(file)
        if (!written || rename(next, path) != 0) remove(next)
    }

    private companion object {

        /** A line at a time, long enough for any window's key and place. A longer one arrives in pieces. */
        const val LINE = 4096
    }
}
