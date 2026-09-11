package composegl.snake

import composegl.ui.graphics.ArtAtlas
import composegl.ui.skin.FileSkinSource
import composegl.ui.skin.ReloadingSkin
import composegl.ui.skin.SkinSource
import composegl.ui.text.FontProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Snake's skin, and the loop that makes it worth having.
 *
 * Read off disk and watched when the source tree is there — the usual case for
 * `./gradlew :composegl-demo-snake:run` — so saving the file changes the running game on the next
 * frame. Otherwise it comes off the classpath, which is what a shipped game does.
 */
fun snakeSkin(fonts: FontProvider) = ReloadingSkin(
    source = skinSource(),
    art = ArtAtlas.of(emptyMap()),
    fonts = fonts,
    onProblem = { println("skin: ${it.message}") },
)

private const val SKIN = "ui/snake.skin.json"

private fun skinSource(): SkinSource {
    val onDisk = listOf(Path.of("composegl-demo-snake/src/main/resources/$SKIN"), Path.of("src/main/resources/$SKIN"))
        .firstOrNull { Files.exists(it) }

    return when {
        onDisk != null -> {
            println("skin: watching $onDisk — save it and the game changes")
            FileSkinSource(onDisk)
        }
        else -> Packaged
    }
}

/** The copy inside the jar. No revision, because nothing can edit it while the game runs. */
private object Packaged : SkinSource {

    override val revision: Any get() = Unit

    override fun read(): String =
        checkNotNull(object {}.javaClass.classLoader.getResourceAsStream(SKIN)) { "no $SKIN on the classpath" }
            .use { it.readBytes().decodeToString() }
}
