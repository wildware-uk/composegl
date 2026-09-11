package uk.wildware.composegl.ui.skin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.wildware.composegl.ui.graphics.ArtAtlas
import uk.wildware.composegl.ui.text.FontProvider

/**
 * Where a skin file's text comes from, and how to tell when it has changed.
 *
 * An interface rather than a file path, because `commonMain` has no files: a desktop game reads one
 * off disk, a phone reads one out of its assets, and a test hands over a string it built. All three
 * are the same two questions.
 */
interface SkinSource {

    /**
     * Something that changes when the text does — a modified time, a version, a hash.
     *
     * Only ever compared for equality, never ordered, so any value will do as long as an edit
     * changes it.
     */
    val revision: Any

    /** The text of the skin file, as it is right now. */
    fun read(): String
}

/**
 * A skin that follows its file.
 *
 * The difference between an artist iterating in seconds and in minutes. Save the file, see the
 * change: no recompile, no restart, nobody's turn to wait for anybody else.
 *
 * [skin] is Compose state, so anything that reads it during composition redraws by itself when the
 * file changes. A game calls [reloadIfChanged] once a frame — it costs one look at the file's
 * modified time, and does nothing at all until the time moves.
 *
 * **A bad save does not stop the game.** Half a file is what a file looks like for the instant an
 * editor takes to write it, and a skin with a typo in it is an ordinary Tuesday. Either one leaves
 * the last skin that worked in place and reports the problem through [onProblem], which a game
 * usually points at its own console. The *first* load is different: a game whose skin was wrong
 * before it started should say so and stop, so that failure is thrown.
 */
class ReloadingSkin(
    private val source: SkinSource,
    private val art: ArtAtlas? = null,
    private val fonts: FontProvider? = null,
    private val onProblem: (SkinFormatException) -> Unit = {},
) {

    private var loaded: Any = source.revision

    /** The skin as the file last said it should be. Read it in composition and it keeps up. */
    var skin: Skin by mutableStateOf(SkinFormat.read(source.read(), art, fonts))
        private set

    /**
     * Reads the file again if it has changed since last time.
     *
     * @return true if the skin was replaced, so a game that wants to log a reload can.
     */
    fun reloadIfChanged(): Boolean {
        val revision = source.revision
        if (revision == loaded) return false
        loaded = revision
        return try {
            skin = SkinFormat.read(source.read(), art, fonts)
            true
        } catch (problem: Exception) {
            // Deliberately everything: a file caught halfway through being written fails in
            // whatever way the platform's file reading fails, and none of those are worth taking a
            // running game down for.
            onProblem(
                problem as? SkinFormatException
                    ?: SkinFormatException("the skin file could not be read: ${problem.message}"),
            )
            false
        }
    }
}
