package dev.wildware.composegl.showcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every text size the skin asks for is a size the showcase registers its font at.
 *
 * Unlike a missing style, this one is not silent — it is a crash on the first frame that draws the
 * word, which is how it was found: adding the toolkit's remaining styles to the showcase's skin
 * brought a `"size": 12` with them and the demo would not start. A skin is edited while the game
 * runs, so the failure lands on whoever is editing it, and a test is cheaper than that.
 */
class ShowcaseFontSizesTest {

    @Test
    fun `the skin asks for no size the font is not registered at`() {
        val asked = Regex("\"size\"\\s*:\\s*(\\d+)")
            .findAll(skinFile().readText())
            .map { it.groupValues[1].toInt() }
            .toSortedSet()

        assertEquals(
            emptyList<Int>(),
            asked.filterNot { it in ShowcaseTextSizes },
            "sizes in $SKIN that nothing registers the font at, so the showcase would not start",
        )
    }

    private fun skinFile(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null && !File(here, "settings.gradle.kts").isFile) here = here.parentFile
        val root = checkNotNull(here) { "ran from ${System.getProperty("user.dir")}, which is not inside the project" }
        return File(root, "composegl-demo-showcase/src/main/resources/$SKIN")
    }
}
