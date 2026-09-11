package dev.wildware.composegl.snake.android

import com.badlogic.gdx.Gdx
import dev.wildware.composegl.snake.game.Difficulty
import dev.wildware.composegl.snake.game.HighScore
import dev.wildware.composegl.snake.game.HighScoreStore

/**
 * High scores in the app's own private storage.
 *
 * The one thing the desktop launcher and this one genuinely have to do differently: a phone has no
 * home directory a game may write to, and LibGDX's `local` files are the app's sandbox.
 *
 * Deliberately forgiving, like the desktop one: a file written by an older version is read for
 * whatever rows still parse rather than throwing a game away at startup.
 */
class GdxHighScores(private val name: String = "snake-scores") : HighScoreStore {

    override fun load(): List<HighScore> = runCatching {
        val file = Gdx.files.local(name)
        if (!file.exists()) return emptyList()
        file.readString().lines().mapNotNull(::parse)
    }.getOrElse { emptyList() }

    override fun save(scores: List<HighScore>) {
        runCatching {
            Gdx.files.local(name).writeString(
                scores.joinToString("\n") {
                    "${it.name.replace(FIELD, " ")}$FIELD${it.score}$FIELD${it.difficulty.name}"
                },
                false,
            )
        }
    }

    private fun parse(row: String): HighScore? {
        val parts = row.split(FIELD)
        if (parts.size != 3) return null
        val score = parts[1].toIntOrNull() ?: return null
        val difficulty = Difficulty.entries.firstOrNull { it.name == parts[2] } ?: return null
        return HighScore(parts[0], score, difficulty)
    }

    private companion object {
        /** A separator no player can type into their own name. */
        const val FIELD = "\u001F"
    }
}
