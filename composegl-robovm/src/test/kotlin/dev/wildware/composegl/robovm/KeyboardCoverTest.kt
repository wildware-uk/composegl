package dev.wildware.composegl.robovm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a keyboard is covering, worked out from where iOS says it is. */
class KeyboardCoverTest {

    /** An iPhone 15: 852 points tall, three pixels to the point. */
    private val screen = 852.0
    private val scale = 3.0

    @Test
    fun `a keyboard on screen covers the distance from its top edge to the bottom`() {
        // Its top edge is 336 points up from the bottom.
        val covered = KeyboardCover.pixels(keyboardTop = 516.0, screenHeight = screen, scale = scale)

        assertEquals(336.0 * 3.0, covered.toDouble(), 0.001)
    }

    @Test
    fun `a keyboard below the screen covers nothing`() {
        // Where iOS parks it when it is away: the top edge is the bottom of the screen.
        assertEquals(0f, KeyboardCover.pixels(screen, screen, scale))
    }

    @Test
    fun `a keyboard further below the screen still covers nothing`() {
        assertEquals(0f, KeyboardCover.pixels(screen + 400.0, screen, scale))
    }

    @Test
    fun `the answer is in pixels, not points`() {
        val onePointScale = KeyboardCover.pixels(552.0, screen, scale = 1.0)
        val threePointScale = KeyboardCover.pixels(552.0, screen, scale = 3.0)

        assertEquals(onePointScale * 3f, threePointScale, 0.001f)
    }

    @Test
    fun `a keyboard taller than the screen covers the screen and no more`() {
        assertEquals((screen * scale).toFloat(), KeyboardCover.pixels(-200.0, screen, scale))
    }
}
