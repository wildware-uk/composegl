package composegl.ui.skin

import composegl.ui.graphics.Colour
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Saving the file changes the running game.
 *
 * The loop this is all for: an artist edits a colour, saves, and sees it, without a compiler or a
 * programmer in the way. The other half matters just as much — half a file, which is what a file
 * looks like for the instant an editor takes to write it, must not take the game down.
 */
class ReloadingSkinTest {

    @TempDir
    lateinit var directory: Path

    private val problems = mutableListOf<String>()

    private fun file(text: String): Path {
        val path = directory.resolve("skin.json")
        Files.writeString(path, text)
        // Explicit, because a filesystem that keeps modified times to the second cannot tell two
        // saves in one test apart, and a real artist saving twice quickly has the same problem.
        Files.setLastModifiedTime(path, FileTime.fromMillis(clock))
        clock += 2_000
        return path
    }

    private var clock = 1_000_000L

    private fun skinOf(path: Path) = ReloadingSkin(
        source = FileSkinSource(path),
        onProblem = { problems += it.message.orEmpty() },
    )

    private fun colour(text: String) = """{ "styles": { "button": { "textColour": "$text" } } }"""

    @Test
    fun `the file is read when the skin is made`() {
        val skin = skinOf(file(colour("#CCCCCC")))

        assertEquals(Colour.rgb(0xCCCCCC), skin.skin.resolve("button").textColour)
    }

    @Test
    fun `saving the file changes the skin`() {
        val path = file(colour("#CCCCCC"))
        val skin = skinOf(path)

        file(colour("#FF5C5C"))

        assertTrue(skin.reloadIfChanged(), "the file moved, so the skin did")
        assertEquals(Colour.rgb(0xFF5C5C), skin.skin.resolve("button").textColour)
    }

    @Test
    fun `a file nobody has touched is not read again`() {
        val skin = skinOf(file(colour("#CCCCCC")))

        assertFalse(skin.reloadIfChanged(), "once a frame, and it costs one look at a timestamp")
    }

    @Test
    fun `a broken save keeps the last skin that worked`() {
        val skin = skinOf(file(colour("#CCCCCC")))

        file("""{ "styles": { "button": { "textColour": """)   // an editor, mid-write

        assertFalse(skin.reloadIfChanged())
        assertEquals(
            Colour.rgb(0xCCCCCC),
            skin.skin.resolve("button").textColour,
            "the game carries on looking the way it did a second ago",
        )
        assertTrue(problems.single().startsWith("line "), problems.toString())
    }

    @Test
    fun `and takes the next save that works`() {
        val skin = skinOf(file(colour("#CCCCCC")))
        file("nonsense")
        skin.reloadIfChanged()

        file(colour("#00FF00"))

        assertTrue(skin.reloadIfChanged())
        assertEquals(Colour.rgb(0x00FF00), skin.skin.resolve("button").textColour)
    }

    @Test
    fun `a skin that was wrong before the game started says so and stops`() {
        val path = file("""{ "styles": { "button": { "textColor": "#FFFFFF" } } }""")

        val problem = assertThrows<SkinFormatException> { skinOf(path) }

        assertTrue("textColour" in problem.message.orEmpty(), problem.message.orEmpty())
    }
}
