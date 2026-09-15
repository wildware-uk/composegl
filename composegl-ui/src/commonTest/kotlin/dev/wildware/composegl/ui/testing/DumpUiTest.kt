package dev.wildware.composegl.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tree as text, off a real screen: composed, clicked and driven with a pad through [uiTest],
 * and read back with `dump`.
 */
class DumpUiTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(content: @Composable () -> Unit): UiTest =
        uiTest(Size(400f, 300f), content = content).also { opened += it }

    /** The line for the node tagged [tag], found by its tag rather than its place in the tree. */
    private fun UiTest.line(tag: String, modifiers: Boolean = false): String =
        dump(modifiers).lines().single { " #$tag " in it }.trim()

    @Composable
    private fun Menu() {
        var banner by remember { mutableStateOf(false) }
        Column(Modifier.padding(8f).testTag("buttons")) {
            Button("SHOW", onClick = { banner = !banner }, initialFocus = true, modifier = Modifier.testTag("show"))
            if (banner) Box(Modifier.size(200f, 120f).testTag("banner"))
            Button("GO", onClick = {}, modifier = Modifier.testTag("go"))
        }
    }

    @Test
    fun `a composed screen dumps where each widget ended up`() {
        val ui = open { Menu() }

        // Monospace fonts in a headless test, so every number here can be worked out on paper:
        // SHOW is four characters at 0.6 of 16, plus the button's 14 either side.
        assertEquals(
            """
            root 0,0 400x300  given 400 x 300
              column #buttons 0,0 82.4x88  pad 8  given 0..400 x 0..300
                box #show 8,8 66.4x36  pad 14,8,14,8  given 0..384 x 0..284  focused
                  text 22,16 38.4x20  given 0..356 x 0..268
                box #go 8,44 47.2x36  pad 14,8,14,8  given 0..384 x 0..248
                  text 22,52 19.2x20  given 0..356 x 0..232
            """.trimIndent(),
            ui.dump(),
        )
        assertEquals(ui.node("go").layoutBoundsInRoot.top, 44f, "the dump's box is the node's own")
    }

    @Test
    fun `the dump follows a click that pushes a widget down`() {
        val ui = open { Menu() }
        assertFalse("#banner" in ui.dump(), ui.dump())
        assertTrue(ui.line("go").startsWith("box #go 8,44 47.2x36"), ui.dump())

        ui.click("show")

        assertEquals("box #banner 8,44 200x120  given 0..384 x 0..248", ui.line("banner"))
        assertTrue(ui.line("go").startsWith("box #go 8,164 47.2x36"), ui.dump())
        assertTrue(ui.line("buttons").startsWith("column #buttons 0,0 216x208"), ui.dump())

        ui.click("show")

        assertFalse("#banner" in ui.dump(), "a widget taken out of the screen leaves the dump:\n" + ui.dump())
        assertTrue(ui.line("go").startsWith("box #go 8,44 47.2x36"), ui.dump())
    }

    @Test
    fun `the dump marks focus where the pad and the keyboard moved it`() {
        val ui = open { Menu() }
        assertTrue(ui.line("show").endsWith("focused"), ui.dump())
        assertFalse(ui.line("go").endsWith("focused"), ui.dump())

        ui.pad(GamepadButton.DpadDown)

        assertTrue(ui.line("go").endsWith("focused"), ui.dump())
        assertFalse(ui.line("show").endsWith("focused"), ui.dump())

        ui.key(Key.Tab)
        assertEquals(1, ui.dump().lines().count { it.endsWith("focused") }, ui.dump())
    }

    @Test
    fun `the modifier chain of a composed widget is there when asked for`() {
        val ui = open { Menu() }

        val lines = ui.dump(modifiers = true).lines()
        val chain = lines[lines.indexOfFirst { " #show " in it } + 1]

        assertEquals(
            "        modifier testTag(\"show\") -> interaction -> focusable(initial) -> clickable -> styled -> " +
                "padding(14,8,14,8)",
            chain,
        )
        assertFalse(ui.dump().lines().any { "modifier" in it }, "only when asked for")
    }

    @Test
    fun `a failing assertion on a screen prints the dump with focus marked`() {
        val ui = open { Menu() }

        val failure = assertFailsWith<AssertionError> { ui.assertFocused("go") }

        val message = failure.message.orEmpty()
        assertTrue(ui.line("show") in message, message)
        assertTrue("given 0..400 x 0..300" in message, message)
        assertTrue(message.lines().any { " #show " in it && it.endsWith("focused") }, message)
    }

    @Test
    fun `a misspelt tag prints the dump of the screen`() {
        val ui = open { Menu() }

        val failure = assertFailsWith<IllegalStateException> { ui.click("sohw") }

        val message = failure.message.orEmpty()
        assertTrue("no node under root is tagged sohw" in message, message)
        assertTrue("    box #show 8,8 66.4x36  pad 14,8,14,8  given 0..384 x 0..284  focused\n" in message, message)
    }
}
