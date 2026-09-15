package dev.wildware.composegl.ui.modifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * How `widthIn`, `heightIn`, `sizeIn` and `defaultMinSize` read out of a chain, and the numbers
 * they refuse. What the numbers then do to layout is `SizeInLayoutTest`'s business.
 */
class SizeInModifierTest {

    @Test
    fun `a chain with no range resolves to none`() {
        val resolved = Modifier.size(10f).resolve()

        assertNull(resolved.sizeIn)
        assertNull(resolved.defaultMinSize)
    }

    @Test
    fun `widthIn and heightIn are two statements that both stand`() {
        val resolved = Modifier.widthIn(min = 10f, max = 20f).heightIn(max = 30f).resolve()

        assertEquals(SizeInElement(minWidth = 10f, maxWidth = 20f, maxHeight = 30f), resolved.sizeIn)
    }

    @Test
    fun `sizeIn names its bounds in the order a reader expects`() {
        assertEquals(
            SizeInElement(minWidth = 1f, maxWidth = 3f, minHeight = 2f, maxHeight = 4f),
            Modifier.sizeIn(minWidth = 1f, minHeight = 2f, maxWidth = 3f, maxHeight = 4f).resolve().sizeIn,
        )
    }

    @Test
    fun `a later bound wins bound by bound`() {
        val resolved = Modifier.widthIn(min = 10f, max = 200f).widthIn(max = 300f).resolve()

        assertEquals(SizeInElement(minWidth = 10f, maxWidth = 300f), resolved.sizeIn)
    }

    @Test
    fun `a later minimum above an earlier maximum carries the maximum with it`() {
        val resolved = Modifier.widthIn(max = 100f).widthIn(min = 150f).resolve()

        assertEquals(SizeInElement(minWidth = 150f, maxWidth = 150f), resolved.sizeIn)
    }

    @Test
    fun `a later maximum below an earlier minimum carries the minimum with it`() {
        val resolved = Modifier.heightIn(min = 150f).heightIn(max = 100f).resolve()

        assertEquals(SizeInElement(minHeight = 100f, maxHeight = 100f), resolved.sizeIn)
    }

    @Test
    fun `default minimums settle per axis with the later one winning`() {
        val resolved = Modifier.defaultMinSize(minWidth = 48f).defaultMinSize(minHeight = 40f)
            .defaultMinSize(minWidth = 64f).resolve()

        assertEquals(DefaultMinSizeElement(minWidth = 64f, minHeight = 40f), resolved.defaultMinSize)
    }

    @Test
    fun `a range with its minimum above its maximum is refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.widthIn(min = 50f, max = 40f) }
        assertFailsWith<IllegalArgumentException> { Modifier.heightIn(min = 50f, max = 40f) }
    }

    @Test
    fun `negative and NaN bounds are refused`() {
        assertFailsWith<IllegalArgumentException> { Modifier.widthIn(min = -1f) }
        assertFailsWith<IllegalArgumentException> { Modifier.heightIn(max = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Modifier.sizeIn(maxHeight = -5f) }
        assertFailsWith<IllegalArgumentException> { Modifier.defaultMinSize(minWidth = -1f) }
        assertFailsWith<IllegalArgumentException> { Modifier.defaultMinSize(minHeight = Float.NaN) }
    }

    @Test
    fun `an infinite maximum is fine but an infinite minimum is not`() {
        assertEquals(
            Float.POSITIVE_INFINITY,
            Modifier.widthIn(max = Float.POSITIVE_INFINITY).resolve().sizeIn?.maxWidth,
        )
        assertFailsWith<IllegalArgumentException> { Modifier.defaultMinSize(minWidth = Float.POSITIVE_INFINITY) }
        // Under a scrolling parent that offers everything, this would lay a node out infinitely wide.
        assertFailsWith<IllegalArgumentException> { Modifier.widthIn(min = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Modifier.sizeIn(minHeight = Float.POSITIVE_INFINITY) }
    }
}
