package composegl.ui.skin

import java.nio.file.Files
import java.nio.file.Path

/**
 * A skin file on a desktop's disk, for [ReloadingSkin] to follow.
 *
 * Polled rather than watched. A file watcher sounds like the right answer and is not: the platform
 * APIs differ, several of them report a save as two or three events, and the fix for that is to
 * wait a moment and read the file anyway. Looking at one modified time per frame costs nothing
 * measurable and behaves the same everywhere.
 *
 * Development only, in the sense that a shipped game normally loads its skin once from inside its
 * own package. Nothing here stops a game doing it in a release build; there is just no reason to.
 */
class FileSkinSource(private val path: Path) : SkinSource {

    /**
     * The modified time and the size together.
     *
     * The size is not paranoia: some filesystems keep modified times only to the second, and an
     * artist saving twice inside one second is exactly the case this exists for.
     */
    override val revision: Any
        get() = if (Files.exists(path)) {
            Files.getLastModifiedTime(path).toMillis() to Files.size(path)
        } else {
            Missing
        }

    override fun read(): String = Files.readString(path)

    private companion object {

        /** What a deleted file reports, so that putting it back reads as a change. */
        val Missing = Any()
    }
}
