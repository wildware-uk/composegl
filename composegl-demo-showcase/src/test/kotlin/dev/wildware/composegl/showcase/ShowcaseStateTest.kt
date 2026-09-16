package dev.wildware.composegl.showcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `no section is open to begin with, and stepping walks them in a ring`() {
        val state = ShowcaseState()

        assertNull(state.section, "the showcase opens on the fight, not on a page about it")

        state.stepSection(1)
        assertEquals(Module.Ui, state.section, "the first step opens the first module")

        repeat(3) { state.stepSection(1) }
        assertEquals(Module.Ui, state.section, "three more steps comes back round")

        state.stepSection(-1)
        assertEquals(Module.Game, state.section, "and it walks backwards too")
    }

    @Test
    fun `a section takes the panels' half of the screen but not the overlays'`() {
        val state = ShowcaseState()

        assertTrue(state.showsPanel(Exhibit.Contacts), "with no section open, everything on show draws")

        state.section = Module.Game

        assertFalse(state.showsPanel(Exhibit.Contacts), "a panel stands aside for the section")
        assertTrue(state.showsPanel(Exhibit.Damage), "what is drawn over the scene carries on")

        state.toggle(Exhibit.Damage)
        assertFalse(state.showsPanel(Exhibit.Damage), "and an overlay switched off is still off")
    }

    @Test
    fun `every exhibit belongs to a module, and every module has some`() {
        Module.entries.forEach { module ->
            assertTrue(
                Exhibit.entries.any { it.module == module },
                "${module.artifact} has a section with nothing in it",
            )
        }
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
