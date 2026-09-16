package dev.wildware.composegl.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The desktop's store: a file beside the game that a window's position survives a restart in, and
 * which never stops the game when it cannot be read or written.
 */
class FileDebugWindowStoreTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `what one run saves the next run loads`() {
        val file = File(folder, "windows.txt")
        val values = mapOf("window:Physics" to "20.0;30.0;auto;auto;open")

        FileDebugWindowStore(file).save(values)

        assertTrue(file.isFile, "nothing was written")
        assertEquals(values, FileDebugWindowStore(file).load())
    }

    @Test
    fun `a file that is not there yet is an empty start`() {
        assertEquals(emptyMap<String, String>(), FileDebugWindowStore(File(folder, "none.txt")).load())
    }

    @Test
    fun `a file full of something else is an empty start rather than a crash`() {
        val file = File(folder, "windows.txt")
        file.writeText("<html>not this at all</html>")

        assertEquals(emptyMap<String, String>(), FileDebugWindowStore(file).load())
    }

    @Test
    fun `saving twice leaves one file with the newer values in it`() {
        val file = File(folder, "windows.txt")
        val store = FileDebugWindowStore(file)

        store.save(mapOf("window:Physics" to "1.0;1.0;auto;auto;open"))
        store.save(mapOf("window:Physics" to "2.0;2.0;auto;auto;open"))

        assertEquals(mapOf("window:Physics" to "2.0;2.0;auto;auto;open"), store.load())
        assertEquals(listOf("windows.txt"), folder.list()?.sorted())
    }
}
