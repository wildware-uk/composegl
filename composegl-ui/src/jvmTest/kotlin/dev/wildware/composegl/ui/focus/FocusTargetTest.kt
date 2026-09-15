package dev.wildware.composegl.ui.focus

import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** `targetOf`: where a direction would take focus, asked without moving it. */
class FocusTargetTest {

    private val screen = TestTree()

    private fun grid() = repeat(3) { row ->
        screen.row("r${row}c0", "r${row}c1", "r${row}c2", y = row * 30f, modifier = Modifier.focusable())
    }

    @Test
    fun `it answers what moveFocus then does and moves nothing itself`() {
        grid()
        val focus = FocusManager(screen.root)
        focus.focusOn(screen["r1c1"])

        assertEquals("r1c2", focus.targetOf(FocusDirection.Right)?.name)
        assertEquals("r0c1", focus.targetOf(FocusDirection.Up)?.name)
        assertEquals("r1c1", focus.focused?.name, "still where it was")

        for (direction in listOf(FocusDirection.Down, FocusDirection.Left, FocusDirection.Next)) {
            val predicted = focus.targetOf(direction)
            focus.focusOn(screen["r1c1"])
            focus.moveFocus(direction)
            assertSame(predicted, focus.focused, "$direction")
            focus.focusOn(screen["r1c1"])
        }
    }

    @Test
    fun `nothing focused or nowhere to go is null`() {
        grid()
        val focus = FocusManager(screen.root, autoFocus = false)
        assertNull(focus.targetOf(FocusDirection.Down))

        focus.focusOn(screen["r0c0"])
        assertNull(focus.targetOf(FocusDirection.Left))
        assertNull(focus.targetOf(FocusDirection.Up))
    }

    @Test
    fun `the newest manager over a root is the one its tree finds`() {
        val first = FocusManager(screen.root)
        val second = FocusManager(screen.root)

        assertSame(second, screen.root.focusManager)
        assertEquals(false, first === screen.root.focusManager)
    }
}
