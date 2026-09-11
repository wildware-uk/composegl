package composegl.showcase

import composegl.ui.graphics.ArtAtlas
import composegl.ui.skin.FileSkinSource
import composegl.ui.skin.ReloadingSkin
import composegl.ui.skin.SkinSource
import composegl.ui.text.FontProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The showcase's skin, watched while it runs.
 *
 * No art: everything here is drawn by the shader, because the point of this demo is the widgets
 * rather than the picture they are cut from. The example next door is the one with the nine-patch.
 */
fun showcaseSkin(fonts: FontProvider) = ReloadingSkin(
    source = skinSource(),
    art = ArtAtlas.of(emptyMap()),
    fonts = fonts,
    onProblem = { println("skin: ${it.message}") },
)

private const val SKIN = "ui/showcase.skin.json"

private fun skinSource(): SkinSource {
    val onDisk = listOf(Path.of("composegl-demo-showcase/src/main/resources/$SKIN"), Path.of("src/main/resources/$SKIN"))
        .firstOrNull { Files.exists(it) }

    return when {
        onDisk != null -> {
            println("skin: watching $onDisk — save it and the showcase changes")
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
