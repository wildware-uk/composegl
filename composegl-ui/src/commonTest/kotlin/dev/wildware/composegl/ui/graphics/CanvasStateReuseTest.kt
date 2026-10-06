package dev.wildware.composegl.ui.graphics

import dev.wildware.composegl.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A [CanvasState] kept and used again, as a canvas keeps one for its frame and one per layer depth
 * (#252): it has to come back exactly as a new one would, however deep the last use went.
 */
class CanvasStateReuseTest {

    private val screen = Rect.of(0f, 0f, 100f, 100f)

    @Test
    fun `a stack deeper than its first room unwinds level by level`() {
        val state = CanvasState(screen)
        val modes = BlendMode.entries
        val depth = 40
        // What each level said as it was pushed, to be read back as the stacks unwind: a stack that
        // lost its middle levels as it grew would answer differently on the way down. The clip is
        // recorded by its edges, never asked for as a rectangle on the way up: a rectangle asked
        // for is kept for its level, and would answer for the edges on the way down.
        val clips = FloatArray(depth * 4)
        val alphas = FloatArray(depth)
        val blends = arrayOfNulls<BlendMode>(depth)
        val tints = IntArray(depth)

        // Each level a clip one unit smaller, a little less opaque, another mode and another tint.
        for (level in 0 until depth) {
            state.pushClip(Rect(level + 1f, 0f, 100f, 100f))
            state.pushAlpha(0.99f)
            state.pushBlend(modes[level % modes.size])
            state.pushTint(Colour.rgb(0xFFFFFFL - level * 0x030201L))
            clips[level * 4] = state.clipLeft
            clips[level * 4 + 1] = state.clipTop
            clips[level * 4 + 2] = state.clipRight
            clips[level * 4 + 3] = state.clipBottom
            alphas[level] = state.alpha
            blends[level] = state.blend
            tints[level] = state.tint.argb
        }
        for (level in depth - 1 downTo 0) {
            assertEquals(clips[level * 4], state.clipLeft, "the clip's left at level $level")
            assertEquals(clips[level * 4 + 1], state.clipTop, "the clip's top at level $level")
            assertEquals(clips[level * 4 + 2], state.clipRight, "the clip's right at level $level")
            assertEquals(clips[level * 4 + 3], state.clipBottom, "the clip's bottom at level $level")
            assertTrue(!state.isHidden, "nothing hidden at level $level")
            // First asked for here, so made from the edges the stack holds now.
            assertEquals(Rect(level + 1f, 0f, 100f, 100f), state.clip, "the clip at level $level")
            assertEquals(alphas[level], state.alpha, "the opacity at level $level")
            assertEquals(blends[level], state.blend, "the blend at level $level")
            assertEquals(tints[level], state.tint.argb, "the tint at level $level")
            state.popClip()
            state.popAlpha()
            state.popBlend()
            state.popTint()
        }

        assertEquals(screen, state.clip)
        assertEquals(1f, state.alpha)
        assertEquals(BlendMode.SourceOver, state.blend)
        assertEquals(Colour.White, state.tint)
        assertTrue(state.isBalanced)
        assertTrue(alphas[depth - 1] < alphas[0], "each level really did fade further")
        assertTrue(tints.distinct().size == depth, "and each tint really was different")
    }

    @Test
    fun `nested opacity and tint still multiply when kept as numbers`() {
        val state = CanvasState(screen)

        state.pushAlpha(0.5f)
        state.pushAlpha(0.5f)
        state.pushTint(Colour.rgb(0x808080))
        state.pushTint(Colour.rgb(0xFF0000))

        assertEquals(0.25f, state.alpha)
        assertEquals(Colour.rgb(0x800000), state.tint)
        state.popAlpha()
        state.popTint()
        assertEquals(0.5f, state.alpha)
        assertEquals(Colour.rgb(0x808080), state.tint)
    }

    @Test
    fun `the clip's edges are the clip`() {
        val state = CanvasState(screen)
        state.pushTransform(2f, 10f, 5f)
        state.pushClip(Rect(5f, 5f, 20f, 30f))

        assertEquals(Rect(20f, 15f, 50f, 65f), state.clip, "mapped through the transform, then cut to the screen")
        assertEquals(state.clip.left, state.clipLeft)
        assertEquals(state.clip.top, state.clipTop)
        assertEquals(state.clip.right, state.clipRight)
        assertEquals(state.clip.bottom, state.clipBottom)

        state.popClip()
        state.popTransform()
        assertEquals(screen, state.clip)
    }

    @Test
    fun `a clip outside the one in force hides everything`() {
        val state = CanvasState(screen)
        state.pushClip(Rect(200f, 200f, 300f, 300f))

        assertTrue(state.isHidden)
        assertTrue(state.clip.isEmpty)
        state.popClip()
        assertTrue(!state.isHidden)
    }

    @Test
    fun `the clip asked for twice is the same rectangle`() {
        val state = CanvasState(screen)
        state.pushClip(Rect(10f, 10f, 20f, 20f))

        assertSame(state.clip, state.clip, "made once for its level, not once a call")
    }

    @Test
    fun `reset by its edges is reset by the rectangle`() {
        val state = CanvasState(screen)
        state.pushClip(Rect(10f, 10f, 20f, 20f))
        state.pushAlpha(0.5f)
        state.pushTransform(2f, 1f, 1f)

        state.reset(0f, 0f, 640f, 360f)

        assertEquals(Rect(0f, 0f, 640f, 360f), state.clip)
        assertEquals(1f, state.alpha)
        assertEquals(1f, state.transformScale)
        assertTrue(state.isBalanced)
    }

    @Test
    fun `a layer filled into a kept state starts as a new one would`() {
        val outer = CanvasState(screen)
        outer.pushAlpha(0.5f)
        outer.pushBlend(BlendMode.Additive)
        outer.pushTint(Colour.Green)
        outer.pushTransform(2f, 3f, 4f, text = 1.5f)
        val area = Rect.of(10f, 10f, 20f, 20f)

        // Left in a mess by a layer before it, which a kept state may have been.
        val kept = CanvasState(screen)
        kept.pushClip(Rect(1f, 1f, 2f, 2f))
        kept.pushAlpha(0.1f)
        kept.pushTint(Colour.Red)
        kept.pushTransform(5f, 5f, 5f)

        val filled = outer.forLayer(area, into = kept)
        val made = outer.forLayer(area)

        assertSame(kept, filled)
        assertEquals(made.clip, filled.clip)
        assertEquals(made.alpha, filled.alpha)
        assertEquals(made.blend, filled.blend)
        assertEquals(made.tint, filled.tint)
        assertEquals(Colour.Green, filled.tint)
        assertEquals(made.transformScale, filled.transformScale)
        assertEquals(made.transformX, filled.transformX)
        assertEquals(made.transformY, filled.transformY)
        assertEquals(made.textScale, filled.textScale)
        assertTrue(filled.isBalanced, "what the carried tint and transform are is its floor")
        assertEquals(0.5f, outer.alpha, "the state outside is untouched")
    }

    @Test
    fun `a state cannot be its own layer`() {
        val state = CanvasState(screen)

        assertFailsWith<IllegalArgumentException> { state.forLayer(Rect.of(0f, 0f, 10f, 10f), into = state) }
    }
}
