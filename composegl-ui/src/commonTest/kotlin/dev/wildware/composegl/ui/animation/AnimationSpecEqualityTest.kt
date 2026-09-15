package dev.wildware.composegl.ui.animation

import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animateContentSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Specs compare by what they say, so a modifier holding one written inline — a new object every
 * recomposition — is still the same modifier, and a still screen still costs nothing.
 */
class AnimationSpecEqualityTest {

    @Test
    fun `two springs that say the same thing are equal`() {
        assertEquals(Spring(), Spring())
        assertEquals(Spring().hashCode(), Spring().hashCode())
        assertNotEquals(Spring(), Spring(damping = Spring.Bouncy))
        assertNotEquals(Spring(), Spring(stiffness = Spring.High))
        assertNotEquals(Spring(), Spring(threshold = 0.5f))
    }

    @Test
    fun `two tweens that say the same thing are equal`() {
        assertEquals(Tween(300, 20, Easings.Linear), Tween(300, 20, Easings.Linear))
        assertEquals(Tween().hashCode(), Tween().hashCode())
        assertNotEquals(Tween(300), Tween(301))
        assertNotEquals(Tween(300, delayMillis = 5), Tween(300))
        assertNotEquals(Tween(300, easing = Easings.Linear), Tween(300))
    }

    @Test
    fun `two snaps that say the same thing are equal`() {
        assertEquals(Snap(), Snap())
        assertNotEquals(Snap(10), Snap())
        assertNotEquals<AnimationSpec>(Snap(), Tween(0))
    }

    @Test
    fun `an animateContentSize chain written twice is the same chain`() {
        assertEquals(Modifier.animateContentSize(), Modifier.animateContentSize())
        assertEquals(Modifier.animateContentSize(Tween(250)), Modifier.animateContentSize(Tween(250)))
        assertNotEquals(Modifier.animateContentSize(Tween(250)), Modifier.animateContentSize(Tween(200)))
        assertNotEquals(
            Modifier.animateContentSize(alignment = Alignment.BottomEnd),
            Modifier.animateContentSize(),
        )
        assertNotEquals(Modifier.animateContentSize(clock = Clock.World), Modifier.animateContentSize())
    }
}
