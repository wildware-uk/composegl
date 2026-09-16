package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.TextureHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The skin the example is dressed in, held to the same list the shipped ones are.
 *
 * It is the skin anybody copying this project starts from, and it is the only one a person actually
 * looks at, so a style missing from it is a widget that draws plain in the one place somebody would
 * notice — except that nobody does notice, because a missing style is silent. Every `itemtip.*`, the
 * whole `dialogue.*` family and all of `skilltree.*` were missing from it at once, and the way that
 * came to light was somebody photographing an item card.
 *
 * Read off disk rather than off the classpath: composegl-demo is an application that this module
 * knows nothing about and must not depend on. The file is named as an input of this test task, so
 * editing the example's skin runs this again.
 */
class ExampleSkinTest {

    /** Any region the file names. What the art is is not this test's business. */
    private val art = object : ArtAtlas {
        override fun region(name: String): TextureHandle = object : TextureHandle {
            override val width = 32
            override val height = 32
        }

        override val names: Set<String> get() = emptySet()
    }

    private val skin = SkinFormat.read(exampleSkinFile().readText(), art = art)

    @Test
    fun `it is a file that reads`() {
        assertTrue(skin.styles.size > 20, "a skin for a whole example, not a sample")
    }

    @Test
    fun `everything a widget set will ask for is in it`() {
        val missing = StyleNames.filterNot { skin.has(it) }

        assertEquals(emptyList<String>(), missing, "styles the example's own skin does not name")
    }

    // The other direction is deliberately not checked. The example invents names of its own —
    // "chip.chosen", "bar.stamina.fill" — which is exactly what a game does and what StyleNames
    // says it is not there to list.

    private fun exampleSkinFile(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null && !File(here, "settings.gradle.kts").isFile) here = here.parentFile
        val root = checkNotNull(here) { "ran from ${System.getProperty("user.dir")}, which is not inside the project" }
        val file = File(root, "composegl-demo/src/main/resources/ui/demo.skin.json")
        check(file.isFile) { "the example's skin should be at $file" }
        return file
    }
}
