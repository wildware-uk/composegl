package dev.wildware.composegl.ui.game

import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.graphics.TextureHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The simulation on its own: no composition, no screen, no clock.
 *
 * All of it is common code, so this runs on a JVM and on something that is not a JVM — which is
 * what proves the randomness is the standard library's own and not a platform's. A burst that came
 * out differently on a phone would make every golden screenshot in the project a lie.
 */
class ParticleEmitterTest {

    private val canvas = RecordingCanvas(Rect.of(0f, 0f, 400f, 400f))
    private val bounds = Rect.of(0f, 0f, 400f, 400f)

    private fun emitter(capacity: Int = 64, seed: Long = 7L) =
        ParticleEmitter(capacity = capacity, clock = Clock.World, seed = seed)

    // Copied, because the canvas hands back the list it is still writing into — two drawings
    // compared without this would be the same list twice, and every comparison below would pass.
    private fun draw(emitter: ParticleEmitter): List<DrawCall> {
        canvas.clear(bounds)
        emitter.drawInto(canvas, bounds)
        return canvas.calls.toList()
    }

    /** Straight up, no spread, no gravity: a particle whose arithmetic can be done by hand. */
    private val Plain = ParticleStyle(
        life = 1f,
        lifeSpread = 0f,
        speed = 100f,
        speedSpread = 0f,
        direction = -90f,
        spread = 0f,
        gravity = 0f,
        size = 10f,
        sizeSpread = 0f,
        endSize = 1f,
        colour = Colour.White,
        endColour = Colour.White,
    )

    @Test
    fun `a burst puts that many particles on the screen`() {
        val emitter = emitter()
        emitter.burst(12, 200f, 200f, Plain)

        assertEquals(12, emitter.active)
        assertEquals(12, draw(emitter).size)
    }

    @Test
    fun `an emitter with nothing alive draws nothing`() {
        assertTrue(draw(emitter()).isEmpty())
    }

    @Test
    fun `a particle goes the way it was sent at the speed it was given`() {
        val emitter = emitter()
        emitter.burst(1, 200f, 200f, Plain)
        emitter.update(0.5f)

        val first = draw(emitter).filterIsInstance<DrawCall.Rectangle>().single()
        // Half a second at a hundred a second, upwards, and the quad is centred on it.
        assertEquals(150f, first.rect.top + first.rect.height / 2f, 0.01f)
        assertEquals(200f, first.rect.left + first.rect.width / 2f, 0.01f)
    }

    @Test
    fun `gravity pulls it back down`() {
        val emitter = emitter()
        emitter.burst(1, 200f, 200f, Plain.copy(gravity = 400f))
        emitter.update(0.5f)

        val first = draw(emitter).filterIsInstance<DrawCall.Rectangle>().single()
        val y = first.rect.top + first.rect.height / 2f
        assertTrue(y > 150f, "gravity did nothing: it is at $y, and with none it would be at 150")
    }

    @Test
    fun `drag slows it down`() {
        val fast = emitter()
        val slow = emitter()
        fast.burst(1, 200f, 200f, Plain)
        slow.burst(1, 200f, 200f, Plain.copy(drag = 2f))
        fast.update(0.25f)
        slow.update(0.25f)

        val travelled = 200f - draw(fast).filterIsInstance<DrawCall.Rectangle>().single().rect.centreY()
        val dragged = 200f - draw(slow).filterIsInstance<DrawCall.Rectangle>().single().rect.centreY()
        assertTrue(dragged < travelled, "drag $dragged should be less than free $travelled")
    }

    @Test
    fun `a particle that has lived its life gives its place back`() {
        val emitter = emitter()
        emitter.burst(4, 200f, 200f, Plain)
        emitter.update(1.5f)

        assertEquals(0, emitter.active)
        assertTrue(draw(emitter).isEmpty())
    }

    @Test
    fun `it shrinks and changes colour across its life`() {
        val emitter = emitter()
        emitter.burst(1, 200f, 200f, Plain.copy(endSize = 0f, endColour = Colour.Transparent))
        emitter.update(0.5f)

        val half = draw(emitter).filterIsInstance<DrawCall.Rectangle>().single()
        assertEquals(5f, half.rect.width, 0.01f, "half way through, half the size")
        assertEquals(127, half.colour.alpha, "and half faded")
    }

    @Test
    fun `the same seed makes the same burst`() {
        val one = emitter(seed = 99L)
        val two = emitter(seed = 99L)
        val messy = ParticleStyle(spread = 180f, sizeSpread = 0.8f, lifeSpread = 0.8f)

        one.burst(20, 100f, 100f, messy)
        two.burst(20, 100f, 100f, messy)
        repeat(5) {
            one.update(0.05f)
            two.update(0.05f)
        }

        assertEquals(draw(one), draw(two), "two emitters with the same seed disagreed")
    }

    @Test
    fun `a different seed makes a different burst`() {
        val one = emitter(seed = 1L)
        val two = emitter(seed = 2L)
        val messy = ParticleStyle(spread = 180f)

        one.burst(20, 100f, 100f, messy)
        two.burst(20, 100f, 100f, messy)
        one.update(0.2f)
        two.update(0.2f)

        assertTrue(draw(one) != draw(two))
    }

    @Test
    fun `the pool gives the oldest place away rather than growing`() {
        val emitter = emitter(capacity = 8)
        emitter.burst(20, 200f, 200f, Plain)

        assertEquals(8, emitter.active, "a pool of eight holds eight")
        assertEquals(8, draw(emitter).size)
    }

    @Test
    fun `a source makes particles at the rate it was given`() {
        val emitter = emitter(capacity = 256)
        emitter.start(perSecond = 40f, x = 100f, y = 100f, style = Plain)
        repeat(60) { emitter.update(1f / 60f) }

        // A second at forty a second, and nothing has lived long enough to die.
        assertEquals(40, emitter.active)
    }

    @Test
    fun `a rate below one a frame still comes out evenly`() {
        val emitter = emitter()
        emitter.start(perSecond = 4f, x = 0f, y = 0f, style = Plain)
        repeat(30) { emitter.update(1f / 60f) }

        assertEquals(2, emitter.active, "half a second at four a second is two, not none")
    }

    @Test
    fun `stopping a source leaves what is already alive`() {
        val emitter = emitter()
        emitter.start(perSecond = 60f, x = 0f, y = 0f, style = Plain)
        repeat(30) { emitter.update(1f / 60f) }
        val alive = emitter.active
        emitter.stop()
        emitter.update(1f / 60f)

        assertTrue(!emitter.isStreaming)
        assertEquals(alive, emitter.active, "stopping should not kill what is in the air")
    }

    @Test
    fun `a source that moves leaves a trail behind it`() {
        val emitter = emitter()
        emitter.start(perSecond = 120f, x = 0f, y = 200f, style = Plain.copy(speed = 0f))
        emitter.update(1f / 60f)
        emitter.move(300f, 200f)
        emitter.update(1f / 60f)

        val left = draw(emitter).filterIsInstance<DrawCall.Rectangle>().map { it.rect.centreX() }
        assertTrue(left.any { it < 10f } && left.any { it > 290f }, "the trail is all in one place: $left")
    }

    @Test
    fun `clearing takes everything off the screen`() {
        val emitter = emitter()
        emitter.burst(10, 100f, 100f, Plain)
        emitter.clear()

        assertEquals(0, emitter.active)
        assertTrue(draw(emitter).isEmpty())
    }

    @Test
    fun `a style with a picture draws the picture tinted`() {
        val emitter = emitter()
        emitter.burst(1, 100f, 100f, Plain.copy(texture = Spark, colour = Colour.rgb(0xFF8800)))

        val image = draw(emitter).filterIsInstance<DrawCall.Image>().single()
        assertEquals(Spark, image.texture)
        assertEquals(Colour.rgb(0xFF8800), image.tint)
    }

    @Test
    fun `drawing is relative to where the widget is`() {
        val emitter = emitter()
        emitter.burst(1, 20f, 20f, Plain)

        canvas.clear(bounds)
        emitter.drawInto(canvas, Rect.of(100f, 50f, 200f, 200f))
        val first = canvas.calls.filterIsInstance<DrawCall.Rectangle>().single()

        assertEquals(120f, first.rect.centreX(), 0.01f)
        assertEquals(70f, first.rect.centreY(), 0.01f)
    }

    @Test
    fun `a source that makes a negative number of particles is a mistake`() {
        assertFailsWith<IllegalArgumentException> { emitter().start(-1f, 0f, 0f, Plain) }
    }

    private fun Rect.centreX() = left + width / 2f

    private fun Rect.centreY() = top + height / 2f

    private object Spark : TextureHandle {
        override val width = 8
        override val height = 8
    }
}
