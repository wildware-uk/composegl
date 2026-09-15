package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.MeasurePass
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.aspectRatio
import dev.wildware.composegl.ui.modifier.defaultMinSize
import dev.wildware.composegl.ui.modifier.layoutId
import dev.wildware.composegl.ui.modifier.widthIn
import dev.wildware.composegl.ui.modifier.zIndex
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.border
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.drawBehind
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.rotate
import dev.wildware.composegl.ui.modifier.scale
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tree as text: what [dump] says about a hand-built tree after a real layout pass.
 *
 * The composed version — a real screen driven by clicks and a pad — is `DumpUiTest`.
 */
class DumpTest {

    private fun node(name: String, modifier: Modifier = Modifier, vararg children: UiNode) =
        UiNode(name).also { parent ->
            parent.modifier = modifier
            children.forEachIndexed { index, child -> parent.insertAt(index, child) }
        }

    /** A padded, offset panel with a fixed-size button in it, under a root. */
    private fun menu(): UiNode = node(
        "root",
        Modifier,
        node(
            "panel",
            Modifier.padding(8f).offset(10f, 20f).testTag("buttons"),
            node("button", Modifier.size(184f, 40f).testTag("play")),
        ),
    )

    @Test
    fun `every node says its name and tag and box and padding and the room it was given`() {
        val root = menu()
        MeasurePass().run(root, Constraints.atMost(1280f, 720f))

        assertEquals(
            """
            root 0,0 200x56  given 0..1280 x 0..720
              panel #buttons 10,20 200x56  pad 8  given 0..1280 x 0..720
                button #play 18,28 184x40  given 0..1264 x 0..704
            """.trimIndent(),
            root.dump(),
        )
    }

    @Test
    fun `a node no layout pass has reached says its box means nothing yet`() {
        val root = menu()

        val lines = root.dump().lines()

        assertEquals("root 0,0 0x0  not laid out", lines[0])
        assertTrue(lines.all { it.endsWith("not laid out") }, root.dump())
        assertNull(root.givenConstraints)
    }

    @Test
    fun `a tight axis is one number and an unbounded one runs to infinity`() {
        val root = node("root", Modifier, node("leaf", Modifier.width(30f)))
        MeasurePass().run(root, Constraints(minWidth = 200f, maxWidth = 200f, minHeight = 10f))

        assertEquals("root 0,0 200x10  given 200 x 10..∞", root.dump().lines()[0])
    }

    @Test
    fun `the room given is the last pass's and not the first's`() {
        val root = menu()
        MeasurePass().run(root, Constraints.atMost(1280f, 720f))
        MeasurePass().run(root, Constraints.atMost(640f, 360f))

        assertEquals("button #play 18,28 184x40  given 0..624 x 0..344", root.find("play").dump())
    }

    @Test
    fun `the modifier chain is only listed when asked for and then in chain order`() {
        val root = menu()
        MeasurePass().run(root, Constraints.atMost(1280f, 720f))

        assertFalse("modifier" in root.dump(), root.dump())
        assertEquals(
            """
            root 0,0 200x56  given 0..1280 x 0..720
              panel #buttons 10,20 200x56  pad 8  given 0..1280 x 0..720
                  modifier padding(8) -> offset(10,20) -> testTag("buttons")
                button #play 18,28 184x40  given 0..1264 x 0..704
                    modifier size(184x40) -> testTag("play")
            """.trimIndent(),
            root.dump(modifiers = true),
        )
    }

    @Test
    fun `modifiers read the way they were written`() {
        val chain = Modifier
            .fillMaxWidth(0.5f)
            .fillMaxSize()
            .padding(left = 1f, top = 2f, right = 3f, bottom = 4f)
            .align(Alignment.BottomEnd)
            .background(Colour.rgb(0xE5484D), corner = 4f)
            .border(Colour.White, width = 2f)
            .clip()
            .alpha(0.25f)
            .blend(BlendMode.Additive)
            .scale(1.5f, Alignment.TopStart)
            .rotate(-12.5f)
            .focusable(initial = true)
            .clickable(enabled = false) {}
            .onPointer { false }
            .drawBehind {}
        val root = node("root", chain)

        assertEquals(
            "    modifier fillMaxWidth(0.5) -> fillMaxSize -> padding(1,2,3,4) -> align(BottomEnd) -> " +
                "background(#FFE5484D corner 4) -> border(#FFFFFFFF 2) -> clip -> alpha(0.25) -> " +
                "blend(Additive) -> scale(1.5 about TopStart) -> rotate(-12.5) -> focusable(initial) -> " +
                "clickable(disabled) -> onPointer -> drawBehind",
            root.dump(modifiers = true).lines()[1],
        )
    }

    @Test
    fun `size ranges and shapes and corners and stacking read the way they were written`() {
        val chain = Modifier
            .widthIn(min = 40f, max = 200f)
            .defaultMinSize(minHeight = 48f)
            .aspectRatio(1.5f, matchHeightConstraintsFirst = true)
            .layoutId("slot")
            .zIndex(2f)
            .background(Colour.Black, Corners(topLeft = 4f, topRight = 4f))
            .border(Colour.White, width = 1f, corner = 6f)
            .clip(Corners(bottomLeft = 2f))
        val root = node("root", chain)

        assertEquals(
            "    modifier sizeIn(minWidth 40 maxWidth 200) -> defaultMinSize(minHeight 48) -> " +
                "aspectRatio(1.5 height first) -> layoutId(slot) -> zIndex(2) -> " +
                "background(#FF000000 corners 4,4,0,0) -> border(#FFFFFFFF 1 corner 6) -> clip(corners 0,0,0,2)",
            root.dump(modifiers = true).lines()[1],
        )
    }

    @Test
    fun `a node lifted over its siblings says by how much`() {
        val lifted = node("lifted", Modifier.size(10f).zIndex(3f))
        val root = node("root", Modifier, lifted, node("flat", Modifier.size(10f)))
        MeasurePass().run(root, Constraints.atMost(100f, 100f))

        assertEquals("lifted 0,0 10x10  given 0..100 x 0..100  z 3", lifted.dump())
        assertFalse(" z " in root.children[1].dump(), root.dump())
    }

    @Test
    fun `an empty node with no size says 0x0 and nothing more`() {
        val root = node("root", Modifier, node("nothing"))
        MeasurePass().run(root, Constraints.atMost(100f, 100f))

        assertEquals("root 0,0 0x0  given 0..100 x 0..100\n  nothing 0,0 0x0  given 0..100 x 0..100", root.dump())
    }

    @Test
    fun `a scaled node says where it is drawn as well as where it was laid out`() {
        val root = node("root", Modifier, node("card", Modifier.size(100f, 50f).scale(0.5f, Alignment.TopStart)))
        MeasurePass().run(root, Constraints.atMost(400f, 400f))

        assertEquals("card 0,0 100x50  drawn 0,0 50x25  given 0..400 x 0..400", root.children[0].dump())
    }

    @Test
    fun `a see-through node says how see-through and the focused node says so`() {
        val faded = node("faded", Modifier.size(10f).alpha(0.5f))
        val root = node("root", Modifier, faded)
        MeasurePass().run(root, Constraints.atMost(100f, 100f))

        assertEquals("faded 0,0 10x10  given 0..100 x 0..100  alpha 0.5  focused", faded.dump(focused = faded))
        assertEquals("faded 0,0 10x10  given 0..100 x 0..100  alpha 0.5", faded.dump(focused = root))
    }

    @Test
    fun `numbers print the same on every platform`() {
        assertEquals("1280", number(1280f))
        assertEquals("12.5", number(12.5f))
        assertEquals("0.33", number(1f / 3f))
        assertEquals("-2.25", number(-2.25f))
        assertEquals("0.05", number(0.05f))
        assertEquals("0", number(-0.001f))
        assertEquals("10000000", number(1e7f))
        assertEquals("∞", number(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a find that fails prints the dump`() {
        val root = menu()
        MeasurePass().run(root, Constraints.atMost(1280f, 720f))

        val message = runCatching { root.find("quit") }.exceptionOrNull()?.message.orEmpty()

        assertTrue("panel #buttons 10,20 200x56  pad 8" in message, message)
    }
}
