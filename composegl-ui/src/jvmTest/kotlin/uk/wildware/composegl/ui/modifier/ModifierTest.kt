package uk.wildware.composegl.ui.modifier

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Alignment
import uk.wildware.composegl.ui.layout.Padding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModifierChainTest {

    private val blue = Colour.rgb(0x2255AA)

    @Test
    fun `the empty modifier is empty`() {
        assertTrue(Modifier.elements().isEmpty())
        assertFalse(Modifier.any { true })
        assertTrue(Modifier.all { false }, "vacuously, like every empty collection")
        assertEquals("Modifier", Modifier.toString())
    }

    @Test
    fun `joining the empty modifier changes nothing on either side`() {
        val chain = Modifier.padding(4f)

        assertEquals(chain, chain then Modifier)
        assertEquals(chain, Modifier then chain)
    }

    @Test
    fun `a chain keeps its order`() {
        val chain = Modifier.padding(4f).background(blue).size(10f)

        assertEquals(
            listOf(
                PaddingElement(Padding.all(4f)),
                BackgroundElement(blue),
                SizeElement(10f, 10f),
            ),
            chain.elements(),
        )
    }

    @Test
    fun `two chains written the same way compare equal, so nothing redraws`() {
        val once = Modifier.padding(8f).background(blue, corner = 4f).size(100f, 40f)
        val twice = Modifier.padding(8f).background(blue, corner = 4f).size(100f, 40f)

        assertEquals(once, twice)
        assertEquals(once.hashCode(), twice.hashCode())
    }

    @Test
    fun `the same elements in a different order are a different chain`() {
        val paddingFirst = Modifier.padding(8f).background(blue)
        val backgroundFirst = Modifier.background(blue).padding(8f)

        assertNotEquals(paddingFirst, backgroundFirst, "these draw differently, so they must compare differently")
    }

    @Test
    fun `changing one value makes the chain unequal`() {
        assertNotEquals(Modifier.padding(8f), Modifier.padding(9f))
        assertNotEquals(Modifier.background(blue), Modifier.background(Colour.White))
    }

    @Test
    fun `a joined chain reads as one chain`() {
        val theirs = Modifier.padding(4f)
        val ours = Modifier.background(blue)

        assertEquals(2, (theirs then ours).elements().size)
        assertEquals(listOf(PaddingElement(Padding.all(4f)), BackgroundElement(blue)), (theirs then ours).elements())
    }

    @Test
    fun `any and all look through the whole chain`() {
        val chain = Modifier.padding(4f).background(blue)

        assertTrue(chain.any { it is BackgroundElement })
        assertFalse(chain.any { it is BorderElement })
        assertTrue(chain.all { it is PaddingElement || it is BackgroundElement })
    }

    @Test
    fun `an inline drawing lambda defeats equality, which is why remember exists`() {
        val once = Modifier.drawBehind { }
        val twice = Modifier.drawBehind { }

        assertNotEquals(once, twice, "documented behaviour, not an accident: a new lambda is a new object")

        val remembered: uk.wildware.composegl.ui.graphics.UiCanvas.(uk.wildware.composegl.ui.geometry.Rect) -> Unit = { }
        assertEquals(Modifier.drawBehind(remembered), Modifier.drawBehind(remembered))
    }

    @Test
    fun `nonsense values are refused at the point they are written`() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.size(-1f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.weight(0f) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.border(Colour.White, width = -1f) }
    }

    @Test
    fun `toString names the chain in order, so a failing test is readable`() {
        val text = Modifier.padding(4f).background(blue).toString()

        assertTrue(text.contains("PaddingElement"), text)
        assertTrue(text.indexOf("PaddingElement") < text.indexOf("BackgroundElement"), text)
    }
}

class ResolvedModifierTest {

    private val blue = Colour.rgb(0x2255AA)

    @Test
    fun `quantities accumulate`() {
        val resolved = Modifier.padding(8f).padding(4f).offset(x = 5f).offset(y = 3f).alpha(0.5f).alpha(0.5f).resolve()

        assertEquals(Padding.all(12f), resolved.padding, "people write two paddings and expect twelve")
        assertEquals(Offset(5f, 3f), resolved.offset)
        assertEquals(0.25f, resolved.alpha, 0.0001f, "opacity multiplies, so nesting fades")
    }

    @Test
    fun `choices are won by the last one written`() {
        val resolved = Modifier
            .size(10f)
            .size(20f)
            .align(Alignment.TopStart)
            .align(Alignment.Centre)
            .resolve()

        assertEquals(SizeElement(20f, 20f), resolved.size)
        assertEquals(Alignment.Centre, resolved.alignment)
    }

    @Test
    fun `padding before a background paints it inside the padding`() {
        val resolved = Modifier.padding(8f).background(blue).resolve()

        assertEquals(Padding.all(8f), resolved.behind.single().inset)
    }

    @Test
    fun `padding after a background paints it across the whole node`() {
        val resolved = Modifier.background(blue).padding(8f).resolve()

        assertEquals(Padding.None, resolved.behind.single().inset)
        assertEquals(Padding.all(8f), resolved.padding, "the padding still applies to the content")
    }

    @Test
    fun `things to paint keep their order, because they overlap`() {
        val resolved = Modifier.background(blue).border(Colour.White, 2f).resolve()

        assertEquals(
            listOf(BackgroundElement(blue), BorderElement(Colour.White, 2f)),
            resolved.behind.map { it.element },
            "the border is drawn over the background, not under it",
        )
    }

    @Test
    fun `drawing in front is kept apart from drawing behind`() {
        val resolved = Modifier.background(blue).drawInFront { }.resolve()

        assertEquals(1, resolved.behind.size)
        assertEquals(1, resolved.inFront.size)
    }

    @Test
    fun `an empty modifier resolves to nothing at all`() {
        val resolved = Modifier.resolve()

        assertNull(resolved.size)
        assertNull(resolved.weight)
        assertEquals(Padding.None, resolved.padding)
        assertEquals(1f, resolved.alpha)
        assertFalse(resolved.hasPainting)
    }
}

class AlignmentTest {

    @Test
    fun `centring puts the child in the middle`() {
        val (x, y) = Alignment.Centre.offsetIn(100f, 100f, 20f, 20f)

        assertEquals(40f, x)
        assertEquals(40f, y)
    }

    @Test
    fun `the ends are the far edges`() {
        val (x, y) = Alignment.BottomEnd.offsetIn(100f, 50f, 20f, 10f)

        assertEquals(80f, x)
        assertEquals(40f, y)
    }

    @Test
    fun `a child bigger than its space overhangs rather than being clamped`() {
        // Clamping here would hide a layout mistake. Overhanging makes it obvious on screen.
        val (x, _) = Alignment.Centre.offsetIn(10f, 10f, 30f, 30f)

        assertEquals(-10f, x)
    }

    @Test
    fun `padding adds up and reports its totals`() {
        val padding = Padding.symmetric(horizontal = 8f, vertical = 4f) + Padding(left = 2f)

        assertEquals(18f, padding.horizontal)
        assertEquals(8f, padding.vertical)
        assertEquals(10f, padding.left)
    }
}
