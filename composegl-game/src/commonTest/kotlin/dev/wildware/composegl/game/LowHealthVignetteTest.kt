package dev.wildware.composegl.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.draw.DrawPass
import dev.wildware.composegl.ui.effect.Uniform
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.composegl.ui.text.TextLayout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring that closes in when the player is nearly dead.
 *
 * The issue's three: it comes in as health drops, it beats, and it goes through the toolkit's own
 * shader pipeline rather than being a stack of rectangles. Plus the two things that would be found
 * only in a game: a healthy player pays nothing at all for it, and the beat stops dead when the
 * world does, so it does not throb behind a pause menu.
 *
 * Each frame's uniforms are that frame's own — the widget hands the canvas a fresh map rather than
 * writing over the last one — so a reading taken from an earlier frame still says what that frame
 * drew. See `VignettePainter`.
 */
class LowHealthVignetteTest {

    private val opened = mutableListOf<UiTest>()

    private val screen = Rect.of(0f, 0f, 400f, 400f)
    private val headless = HeadlessBackend(screen)

    private var health by mutableFloatStateOf(1f)

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private fun open(pulse: Boolean = true, threshold: Float = 0.3f): UiTest =
        uiTest(Size(400f, 400f), backend = headless) {
            LowHealthVignette(health, threshold = threshold, pulse = pulse)
        }.also { opened += it }

    /** The picture the ring was drawn through this frame, if there was one. */
    private fun UiTest.ring(): DrawCall.Layer? {
        headless.canvas.clear(screen)
        render()
        return headless.canvas.calls.filterIsInstance<DrawCall.Layer>().firstOrNull()
    }

    /** How much of the colour is reaching the edge of the screen this frame. */
    private fun UiTest.strength(): Float {
        val effect = ring()?.effect ?: return 0f
        return (effect.uniforms["u_strength"] as Uniform.Number).value
    }

    private fun UiTest.strengths(frames: Int): List<Float> = List(frames) { strength() }

    /** How many times a beat turned round: twice a cycle, whatever it is worth at the time. */
    private fun turns(readings: List<Float>): Int {
        var turns = 0
        for (i in 1 until readings.size - 1) {
            val before = readings[i] - readings[i - 1]
            val after = readings[i + 1] - readings[i]
            if (before > 0f && after < 0f) turns++
            if (before < 0f && after > 0f) turns++
        }
        return turns
    }

    // --- the issue's three ------------------------------------------------------------------------

    @Test
    fun `the ring comes in as health drops and is nothing at all above the threshold`() {
        val ui = open()

        health = 1f
        ui.settle()
        assertEquals(null, ui.ring(), "a healthy player was given a vignette")

        health = 0.3f
        ui.settle()
        assertEquals(null, ui.ring(), "the threshold itself is still nothing")

        health = 0.2f
        ui.settle()
        val hurt = ui.strength()
        assertTrue(hurt > 0f, "below the threshold the ring should be there")

        health = 0f
        ui.settle()
        assertTrue(ui.strength() > hurt, "at no health at all it should be at its strongest")
    }

    @Test
    fun `it beats and the beat quickens as health drops`() {
        val ui = open()

        health = 0.15f
        ui.settle()
        val calm = ui.strengths(120)
        assertTrue(calm.max() - calm.min() > calm.max() * 0.1f, "the ring is not beating at all")

        health = 0f
        ui.settle()
        val frantic = ui.strengths(120)

        assertTrue(
            turns(frantic) > turns(calm),
            "the beat did not quicken: ${turns(calm)} turns at 15% health and ${turns(frantic)} at none",
        )
    }

    @Test
    fun `it is drawn through the toolkit's own shader pipeline`() {
        val ui = open(pulse = false)

        health = 0f
        ui.settle()

        val drawn = checkNotNull(ui.ring()) { "nothing was drawn for a player with no health left" }
        val effect = checkNotNull(drawn.effect) { "the ring was drawn with no shader on it" }

        assertEquals(screen, drawn.bounds, "the ring should cover the whole box it was given")
        assertEquals(Uniform.of(fill("vignette")), effect.uniforms["u_vignette"], "the colour is not the skin's")
        assertTrue(effect.source.fragment.contains("gl_FragColor"), "that is not a fragment shader")
        assertEquals(0f, effect.bleed, "a vignette has nothing to spread outside its own box")
    }

    // --- the rest of it ---------------------------------------------------------------------------

    @Test
    fun `the beat stops dead when the world does`() {
        val ui = open()

        health = 0.05f
        ui.settle()
        ui.host.clocks.stop(Clock.World)

        val still = ui.strengths(30)
        assertEquals(still.first(), still.max(), 0.0001f, "a paused ring kept beating")
        assertEquals(still.first(), still.min(), 0.0001f, "a paused ring kept beating")

        ui.host.clocks.start(Clock.World)
        val going = ui.strengths(60)
        assertTrue(going.max() - going.min() > 0f, "and it should carry on when the world does")
    }

    @Test
    fun `a game that would rather it did not beat gets a still ring`() {
        val ui = open(pulse = false)

        health = 0f
        ui.settle()

        val readings = ui.strengths(40)
        assertEquals(readings.first(), readings.max(), 0.0001f, "it beat anyway")
        assertEquals(readings.first(), readings.min(), 0.0001f, "it beat anyway")
    }

    @Test
    fun `a canvas that cannot take a picture of a layer still says the player is dying`() {
        val ui = open(pulse = false)

        health = 0f
        ui.settle()

        val flat = FlatCanvas()
        DrawPass(flat).draw(ui.root)

        val wash = flat.rects.singleOrNull { it.second.alpha > 0 }
        assertTrue(wash != null, "a canvas with no layers was left with nothing at all")
        assertEquals(screen, wash.first, "the wash should cover the whole box")
        assertEquals(fill("vignette").withAlpha(255), wash.second.withAlpha(255), "and be the skin's own colour")
    }

    @Test
    fun `one frame's uniforms are not written over by the next frame's`() {
        val ui = open()

        health = 0f
        ui.settle()

        val first = checkNotNull(ui.ring()?.effect) { "nothing was drawn for a player with no health left" }
        val was = (first.uniforms["u_strength"] as Uniform.Number).value

        // A canvas is allowed to write a draw call down and carry it out later, and what it wrote
        // down must not change under it while the beat moves on.
        val later = ui.strengths(20)
        assertTrue(later.any { it != was }, "the beat never moved, so this proves nothing")
        assertEquals(was, (first.uniforms["u_strength"] as Uniform.Number).value, "an old frame's uniforms changed")
    }

    @Test
    fun `a threshold of nothing only ever shows on a player who is already dead`() {
        val ui = open(threshold = 0f)

        health = 0.01f
        ui.settle()
        assertEquals(null, ui.ring(), "there is no threshold to be under")

        health = 0f
        ui.settle()
        assertTrue(ui.strength() > 0f, "a dead player should still be told")
    }

    private fun fill(style: String): Colour =
        (Skin.Default.resolve(style).background as SkinDrawable.Fill).colour

    /**
     * A canvas that draws rectangles and cannot take a picture of a layer: the oldest kind of
     * backend, and the one an effect has to degrade gracefully on.
     */
    private class FlatCanvas : UiCanvas {
        val rects = mutableListOf<Pair<Rect, Colour>>()

        override fun rect(rect: Rect, colour: Colour, corner: Float) {
            rects += rect to colour
        }

        override fun border(rect: Rect, colour: Colour, width: Float, corner: Float) = Unit
        override fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float) = Unit
        override fun fan(points: FloatArray, colour: Colour) = Unit
        override fun text(layout: TextLayout, x: Float, y: Float, colour: Colour) = Unit
        override fun image(texture: TextureHandle, destination: Rect, tint: Colour, source: Rect?) = Unit
        override fun pushClip(rect: Rect) = Unit
        override fun popClip() = Unit
        override fun pushAlpha(alpha: Float) = Unit
        override fun popAlpha() = Unit
        override fun raw(block: (Any) -> Unit) = Unit
    }
}
