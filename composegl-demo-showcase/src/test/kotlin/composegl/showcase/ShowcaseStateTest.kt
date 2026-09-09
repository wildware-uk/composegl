package composegl.showcase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parts of the showcase that are arithmetic rather than pixels.
 *
 * Everything visual needs a driver to judge, but cooldowns, lifetimes and toggles do not — and
 * they are the parts that would quietly go wrong.
 */
class ShowcaseStateTest {

    @Test
    fun `an ability starts ready and goes on cooldown when used`() {
        val ability = Ability("1", "PULSE", cooldownSeconds = 2f)

        assertTrue(ability.ready)
        assertEquals(1f, ability.progress, "a ready ability is fully charged")

        ability.trigger()

        assertFalse(ability.ready)
        assertEquals(0f, ability.progress, 0.001f)
    }

    @Test
    fun `a cooldown recharges over exactly its own length`() {
        val ability = Ability("1", "PULSE", cooldownSeconds = 2f)
        ability.trigger()

        repeat(60) { ability.tick(1f / 60f) }
        assertEquals(0.5f, ability.progress, 0.02f, "half a cooldown is half charged")

        // 120 ticks of 1/60 sum to a shade under 2, so the last sliver takes one more frame.
        // That is float arithmetic, not a bug: in a game it means "ready" lands 16ms late.
        repeat(61) { ability.tick(1f / 60f) }
        assertTrue(ability.ready, "it should be ready again after two seconds")
        assertEquals(0f, ability.remaining)
    }

    @Test
    fun `triggering while on cooldown does not restart it`() {
        val ability = Ability("1", "PULSE", cooldownSeconds = 2f)
        ability.trigger()
        ability.tick(1.5f)

        ability.trigger()

        assertEquals(0.5f, ability.remaining, 0.001f, "the second press is ignored")
    }

    @Test
    fun `a cooldown never goes past ready`() {
        val ability = Ability("1", "PULSE", cooldownSeconds = 1f)
        ability.trigger()
        ability.tick(10f)

        assertEquals(0f, ability.remaining)
        assertTrue(ability.ready)
    }

    @Test
    fun `damage numbers expire, and criticals last longer`() {
        val ordinary = DamageNumber(0f, 0f, 0f, amount = 40, critical = false)
        val critical = DamageNumber(0f, 0f, 0f, amount = 300, critical = true)

        assertTrue(critical.lifetime > ordinary.lifetime, "a critical should linger")

        ordinary.age = ordinary.lifetime - 0.01f
        assertFalse(ordinary.done)
        ordinary.age = ordinary.lifetime
        assertTrue(ordinary.done)
    }

    @Test
    fun `every exhibit starts on and can be switched off and back`() {
        val state = ShowcaseState()

        Exhibit.entries.forEach { assertTrue(state.isOn(it), "${it.title} should start on") }

        state.toggle(Exhibit.Particles)
        assertFalse(state.isOn(Exhibit.Particles))

        state.toggle(Exhibit.Particles)
        assertTrue(state.isOn(Exhibit.Particles))
    }

    @Test
    fun `toggling one exhibit leaves the others alone`() {
        val state = ShowcaseState()
        state.toggle(Exhibit.Hud)

        assertFalse(state.isOn(Exhibit.Hud))
        assertTrue(state.isOn(Exhibit.Tracking))
        assertTrue(state.isOn(Exhibit.Holo))
    }

    @Test
    fun `the ability bar is the four the interface draws`() {
        val state = ShowcaseState()
        assertEquals(listOf("1", "2", "3", "4"), state.abilities.map { it.key })
        assertTrue(state.abilities.all { it.cooldownSeconds > 0f })
    }
}
