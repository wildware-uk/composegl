package dev.wildware.composegl.ui.skin

import dev.wildware.composegl.ui.graphics.ArtAtlas
import dev.wildware.composegl.ui.graphics.TextureHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The showcase's skin, held to the same list [ExampleSkinTest] holds the example's to.
 *
 * The showcase is the other skin a person actually looks at, and it answered 103 of the toolkit's
 * 245 style names: the menu bar, the table, the tree, the debug windows, the plots, the weapon
 * wheel, the upgrade board, the item cards and the objectives were all drawing plain. Nobody had
 * noticed, for the reason [StyleNames] gives — a missing style is silent, because [Skin.style]
 * falls back rather than failing.
 *
 * Read off disk for the same reason the example's is: composegl-demo-showcase is an application
 * this module knows nothing about and must not depend on. The file is named as an input of this
 * test task, so editing the showcase's skin runs this again.
 */
class ShowcaseSkinTest {

    /** Any region the file names. What the art is is not this test's business. */
    private val art = object : ArtAtlas {
        override fun region(name: String): TextureHandle = object : TextureHandle {
            override val width = 32
            override val height = 32
        }

        override val names: Set<String> get() = emptySet()
    }

    private val skin = SkinFormat.read(showcaseSkinFile().readText(), art = art)

    @Test
    fun `it is a file that reads`() {
        assertTrue(skin.styles.size > 20, "a skin for a whole showcase, not a sample")
    }

    @Test
    fun `everything a widget set will ask for is in it`() {
        val missing = StyleNames.filterNot { skin.has(it) }

        assertEquals(emptyList<String>(), missing, "styles the showcase's own skin does not name")
    }

    // The other direction is deliberately not checked. The showcase invents names of its own —
    // "bar.heat.fill", "chat.squad", "holo.button", "compass.pin.locked" — which is exactly what a
    // game does and what StyleNames says it is not there to list.

    private fun showcaseSkinFile(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null && !File(here, "settings.gradle.kts").isFile) here = here.parentFile
        val root = checkNotNull(here) { "ran from ${System.getProperty("user.dir")}, which is not inside the project" }
        val file = File(root, "composegl-demo-showcase/src/main/resources/ui/showcase.skin.json")
        check(file.isFile) { "the showcase's skin should be at $file" }
        return file
    }
}
