package dev.wildware.composegl.debug

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native desktop's store: a file beside the game that a window's position survives a restart in,
 * and which never stops the game when it cannot be read or written.
 */
@OptIn(ExperimentalForeignApi::class)
class FileDebugWindowStoreTest {

    private val folder = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
    private val path = "$folder/composegl-windows-${getpid()}.txt"

    @AfterTest
    fun clean() {
        remove(path)
        remove("$path.tmp")
    }

    @Test
    fun `what one run saves the next run loads`() {
        val values = mapOf("window:Physics" to "20.0;30.0;auto;auto;open")

        FileDebugWindowStore(path).save(values)

        assertEquals(values, FileDebugWindowStore(path).load())
    }

    @Test
    fun `a file that is not there yet is an empty start`() {
        assertEquals(emptyMap<String, String>(), FileDebugWindowStore("$folder/composegl-none-${getpid()}.txt").load())
    }

    @Test
    fun `saving twice leaves the newer values`() {
        val store = FileDebugWindowStore(path)

        store.save(mapOf("window:Physics" to "1.0;1.0;auto;auto;open"))
        store.save(mapOf("window:Physics" to "2.0;2.0;auto;auto;open"))

        assertEquals(mapOf("window:Physics" to "2.0;2.0;auto;auto;open"), store.load())
        assertEquals(
            emptyMap<String, String>(),
            FileDebugWindowStore("$path.tmp").load(),
            "the half-written file was left behind",
        )
    }
}
