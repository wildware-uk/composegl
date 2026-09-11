package uk.wildware.composegl.showcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What is left of the showcase once the widgets are the toolkit's.
 *
 * The cooldowns, the bars and the damage numbers used to be this demo's own code and had tests
 * here; they are the toolkit's now and are tested there. What remains is the switchboard.
 */
class ShowcaseStateTest {

    @Test
    fun `everything is on to begin with`() {
        val state = ShowcaseState()

        assertTrue(Exhibit.entries.all { state.isOn(it) })
    }

    @Test
    fun `an exhibit can be switched off and back on`() {
        val state = ShowcaseState()

        state.toggle(Exhibit.Particles)
        assertFalse(state.isOn(Exhibit.Particles))
        assertTrue(state.isOn(Exhibit.Hud), "and nothing else moved")

        state.toggle(Exhibit.Particles)
        assertTrue(state.isOn(Exhibit.Particles))
    }

    @Test
    fun `the terminal's pages wrap both ways`() {
        val state = ShowcaseState()

        state.holoPage = (state.holoPage + 2) % 3
        assertEquals(2, state.holoPage, "back from the first page is the last one")

        repeat(4) { state.holoPage = (state.holoPage + 1) % 3 }
        assertEquals(0, state.holoPage)
    }
}
