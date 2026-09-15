package dev.wildware.composegl.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reading a typed line: the words it is made of, the types the arguments are read as, and the
 * sentence a person gets back when one of them is wrong.
 *
 * The console's user interface is tested in [DevConsoleTest] by typing at it. This is the part
 * underneath, where the answers are strings rather than pixels.
 */
class ConsoleCommandsTest {

    private val ran = mutableListOf<String>()

    private val commands = buildList<ConsoleCommand> {
        ConsoleScope(this).apply {
            command("noclip") { ran += "noclip" }
            command("timescale", arg<Float>("scale")) { ran += "timescale $it" }
            command(
                "give",
                arg<String>("item", suggest = { listOf("sword", "shield", "sapphire") }),
                arg<Int>("count", default = 1),
            ) { item, count -> ran += "give $item $count" }
            command("giveall") { ran += "giveall" }
            command("god", arg<Boolean>("on")) { ran += "god $it" }
        }
    }

    private fun run(line: String): String {
        val parsed = parseLine(line, commands)
        return when (parsed) {
            is ConsoleParse.Blank -> "blank"
            is ConsoleParse.Failed -> parsed.message
            is ConsoleParse.Ready -> {
                parsed.command.run(parsed.values)
                ran.last()
            }
        }
    }

    // --- the words ------------------------------------------------------------------------------

    @Test
    fun `a line is split on spaces and a quoted run is one word`() {
        val tokens = tokenise("""give "iron sword"  10""")

        assertEquals(listOf("give", "iron sword", "10"), tokens.map { it.text })
        assertEquals(listOf(0, 5, 19), tokens.map { it.start })
    }

    @Test
    fun `a quote that was never closed still makes a word`() {
        assertEquals(listOf("give", "iron sw"), tokenise("""give "iron sw""").map { it.text })
    }

    @Test
    fun `a word with a space in it goes back into the line quoted`() {
        assertEquals("\"iron sword\"", quoteIfNeeded("iron sword"))
        assertEquals("sword", quoteIfNeeded("sword"))
    }

    // --- the types ------------------------------------------------------------------------------

    @Test
    fun `arguments arrive as the types the command asked for`() {
        assertEquals("timescale 0.2", run("timescale 0.2"))
        assertEquals("give sword 10", run("give sword 10"))
        assertEquals("god true", run("god on"))
        assertEquals("god false", run("god 0"))
    }

    @Test
    fun `an argument with a default may be left out`() {
        assertEquals("give sword 1", run("give sword"))
    }

    @Test
    fun `a type the console cannot read is refused where the command is written`() {
        val failure = assertFailsWith<IllegalArgumentException> { arg<ConsoleCommandsTest>("thing") }

        assertTrue(failure.message!!.contains("ConsoleCommandsTest"), failure.message!!)
    }

    @Test
    fun `a required argument cannot come after one with a default`() {
        assertFailsWith<IllegalArgumentException> {
            ConsoleScope(mutableListOf()).command(
                "teleport",
                arg<Int>("x", default = 0),
                arg<Int>("y"),
            ) { _, _ -> }
        }
    }

    // --- what it says when it is wrong ------------------------------------------------------------

    @Test
    fun `a misspelt command is told what it probably meant`() {
        val message = run("gove sword")

        assertTrue(message.contains("unknown command \"gove\""), message)
        assertTrue(message.contains("Did you mean \"give\"?"), message)
    }

    @Test
    fun `a word that is not a number says so and says which argument`() {
        val message = run("timescale fast")

        assertEquals("timescale: \"fast\" is not a number for <scale>", message)
    }

    @Test
    fun `a missing argument says what it was and how the command reads`() {
        val message = run("give")

        assertEquals("give needs <item>. Usage: give <item> [count]", message)
    }

    @Test
    fun `too many arguments says how many it takes`() {
        assertTrue(run("noclip now").contains("takes no arguments"), run("noclip now"))
        assertTrue(run("give sword 1 2").contains("takes 2"), run("give sword 1 2"))
    }

    @Test
    fun `an empty line is nothing at all`() {
        assertEquals("blank", run("   "))
    }

    // --- completing -----------------------------------------------------------------------------

    @Test
    fun `the first word completes to the commands that start with it`() {
        val completion = completionAt("gi", 2, commands)

        assertEquals(listOf("give", "giveall"), completion.options)
        assertEquals(0, completion.start)
        assertEquals(2, completion.end)
    }

    @Test
    fun `an argument completes to what that argument suggests`() {
        val completion = completionAt("give s", 6, commands)

        assertEquals(listOf("sapphire", "shield", "sword"), completion.options)
        assertEquals(5, completion.start)
    }

    @Test
    fun `the space after a command offers every one of its suggestions`() {
        val completion = completionAt("give ", 5, commands)

        assertEquals(listOf("sapphire", "shield", "sword"), completion.options)
        assertEquals(5, completion.start)
        assertEquals(5, completion.end)
    }

    @Test
    fun `a boolean argument suggests the two words it answers to`() {
        assertEquals(listOf("false", "true"), completionAt("god ", 4, commands).options)
    }

    @Test
    fun `an argument nothing suggests offers nothing`() {
        assertTrue(completionAt("timescale ", 10, commands).isEmpty)
    }

    @Test
    fun `the shared start of the choices is what one press can fill in`() {
        assertEquals("give", commonPrefix(listOf("give", "giveall")))
        assertEquals("", commonPrefix(listOf("give", "noclip")))
    }

    @Test
    fun `one letter out is near enough to suggest and three is not`() {
        assertEquals(1, editDistance("give", "gove"))
        assertEquals(4, editDistance("give", "noclip".take(4)))
    }
}
