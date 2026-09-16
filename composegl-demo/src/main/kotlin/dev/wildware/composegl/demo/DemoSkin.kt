package dev.wildware.composegl.demo

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.skin.ReloadingSkin
import dev.wildware.composegl.ui.skin.SkinSource
import dev.wildware.composegl.ui.text.FontProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The example's skin, and the loop that makes it worth having.
 *
 * The file is `ui/demo.skin.json`. If the source tree is where the example is being run from — the
 * usual case for `./gradlew :composegl-demo:runGl` — the skin is read off disk and watched, so
 * saving the file changes the running example on the next frame. Otherwise it comes off the
 * classpath, which is what a shipped game does.
 *
 * @param art where the skin file's region names are looked up. A name the atlas has never heard of
 *   stops the load and says which line it was on.
 */
fun demoSkin(art: ArtAtlas, fonts: FontProvider) = ReloadingSkin(
    source = skinSource,
    art = art,
    fonts = fonts,
    // A broken save leaves the last skin that worked on screen; this is all the example does about
    // it. A game with a console would print it there.
    onProblem = { println("skin: ${it.message}") },
)

/**
 * The example's skin file as text, from wherever [demoSkin] would read it.
 *
 * For anything that has to know what the skin says *before* there is a skin to ask — the doc-shot
 * harness bakes the font sizes the file declares, and a font has to be registered before anything
 * is measured. Reading it through the same source is what keeps the two answers the same file.
 */
fun demoSkinText(): String = skinSource.read()

private const val SKIN = "ui/demo.skin.json"

/** Worked out once, because finding it says so out loud and nobody needs telling twice. */
private val skinSource: SkinSource by lazy {
    val onDisk = listOf(Path.of("composegl-demo/src/main/resources/$SKIN"), Path.of("src/main/resources/$SKIN"))
        .firstOrNull { Files.exists(it) }

    when {
        onDisk != null -> {
            println("skin: watching $onDisk — save it and the example changes")
            dev.wildware.composegl.ui.skin.FileSkinSource(onDisk)
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
