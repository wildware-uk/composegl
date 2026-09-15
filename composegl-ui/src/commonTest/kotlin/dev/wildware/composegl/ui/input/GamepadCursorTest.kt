package dev.wildware.composegl.ui.input

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GamepadCursor] on its own clock: a laid-out tree to point at, a sink that writes down what it
 * was sent, and frames called by hand so every distance is exact.
 */
class GamepadCursorTest {

    private val opened = mutableListOf<UiTest>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    private class Recorder : InputSink {
        val events = mutableListOf<PointerEvent>()
        override fun onPointer(event: PointerEvent): Boolean = events.add(event)
        override fun onKey(event: KeyEvent) = false
        override fun onText(event: TextEvent) = false
        override fun onGamepad(event: GamepadEvent) = false
    }

    private val sink = Recorder()

    /** 1000 by 500, with a 100 wide target 600 across when [target] is on. */
    private fun cursor(target: Boolean = false, hidden: Boolean = false): GamepadCursor {
        val ui = uiTest(Size(1000f, 500f)) {
            Box(Modifier.size(1000f, 500f)) {
                if (target) {
                    Box(Modifier.offset(600f, 200f).size(100f, 100f).alpha(if (hidden) 0f else 1f).clickable { })
                }
            }
        }.also { opened += it }
        return GamepadCursor(ui.root, sink).apply { enabled = true }
    }

    private fun GamepadCursor.stick(x: Float, y: Float = 0f) {
        onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, x))
        onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftY, y))
    }

    /** Frames of 10 ms from [from] to [to], the first of which only switches the cursor on. */
    private fun GamepadCursor.run(from: Long, to: Long) {
        var t = from
        while (t <= to) { frame(t); t += 10 }
    }

    @Test
    fun `switched on it starts in the middle and hovers what is there`() {
        val cursor = cursor()
        cursor.frame(0)
        assertEquals(Offset(500f, 250f), cursor.position)
        assertEquals(listOf<PointerEvent>(PointerEvent.Move(GamepadCursor.Id, Offset(500f, 250f), type = PointerType.Ray)), sink.events)
    }

    @Test
    fun `a fully pushed stick covers speed per second`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.stick(1f)
        cursor.run(10, 500)
        assertEquals(500f + 900f * 0.5f, cursor.position.x, 1f)
    }

    @Test
    fun `a stick inside the dead zone does not drift`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.stick(0.1f, -0.1f)
        cursor.run(10, 1000)
        assertEquals(Offset(500f, 250f), cursor.position)
    }

    @Test
    fun `half a push is fine aim well under half the speed`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.stick(0.5f)
        cursor.run(10, 100)
        val travelled = cursor.position.x - 500f
        assertTrue(travelled > 0f && travelled < 900f * 0.1f * 0.25f, "went $travelled")
    }

    @Test
    fun `a long pause moves the cursor one short frame rather than flinging it`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.stick(1f)
        cursor.frame(5_000)
        assertEquals(590f, cursor.position.x, 0.5f)
    }

    @Test
    fun `over a target the cursor slows down`() {
        val cursor = cursor(target = true)
        cursor.frame(0)
        cursor.moveTo(Offset(610f, 250f))
        cursor.stick(1f)
        cursor.run(10, 50)
        val travelled = cursor.position.x - 610f
        assertEquals(900f * 0.05f * cursor.targetSlowdown, travelled, 0.5f)
    }

    @Test
    fun `an invisible target is not snapped to`() {
        val cursor = cursor(target = true, hidden = true)
        cursor.frame(0)
        cursor.moveTo(Offset(590f, 250f))
        cursor.run(10, 500)
        assertEquals(Offset(590f, 250f), cursor.position)
        assertFalse(cursor.overTarget)
    }

    @Test
    fun `the cursor cannot leave the area or sit on its far edge`() {
        val cursor = cursor()
        cursor.area = Rect.of(100f, 100f, 200f, 100f)
        cursor.frame(0)
        assertEquals(Offset(200f, 150f), cursor.position, "the middle of the area")
        cursor.stick(1f, 1f)
        cursor.run(10, 2000)
        assertTrue(cursor.position.x < 300f && cursor.position.x > 299f, "${cursor.position}")
        assertTrue(cursor.position.y < 200f && cursor.position.y > 199f, "${cursor.position}")
    }

    @Test
    fun `an area that shrinks under a still cursor brings the cursor inside it`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.moveTo(Offset(900f, 400f))
        cursor.area = Rect.of(0f, 0f, 500f, 300f)
        cursor.frame(10)
        assertTrue(cursor.position.x < 500f && cursor.position.y < 300f, "${cursor.position}")
        assertEquals(cursor.position, (sink.events.last() as PointerEvent.Move).position, "and the pointer hears it")
    }

    @Test
    fun `switched on before anything is laid out it waits for a size and then starts in the middle`() {
        val sink = Recorder()
        val ui = uiTest(Size(1000f, 500f)) { Box(Modifier.size(1000f, 500f)) }.also { opened += it }
        val cursor = GamepadCursor(dev.wildware.composegl.ui.node.UiNode(), sink).apply { enabled = true }
        cursor.frame(0)
        assertTrue(sink.events.isEmpty(), "a tree with no size has no middle")
        assertEquals(Offset.Zero, cursor.position)
        val laidOut = GamepadCursor(ui.root, sink).apply { enabled = true }
        laidOut.frame(10)
        assertEquals(Offset(500f, 250f), laidOut.position)
    }

    @Test
    fun `off it takes nothing and sends nothing`() {
        val cursor = cursor()
        cursor.enabled = false
        assertFalse(cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South)))
        assertFalse(cursor.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.LeftX, 1f)))
        cursor.run(0, 500)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a south that went down before the cursor came on is left for the navigator on the way up`() {
        val cursor = cursor()
        cursor.enabled = false
        cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        cursor.enabled = true
        cursor.frame(0)
        assertFalse(cursor.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South)))
        assertTrue(sink.events.none { it is PointerEvent.Release })
    }

    @Test
    fun `switched off while held it cancels rather than releases and stops hovering`() {
        val cursor = cursor()
        cursor.frame(0)
        assertTrue(cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South)))
        cursor.enabled = false
        cursor.frame(10)
        assertEquals(
            listOf("Move", "Press", "Cancel", "Exit"),
            sink.events.map { it::class.simpleName },
        )
        assertFalse(cursor.pressed)
    }

    @Test
    fun `a pad pulled out mid-drag cancels the drag`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        cursor.stick(1f)
        cursor.frame(10)
        assertTrue((sink.events.last() as PointerEvent.Move).pressed.contains(PointerButton.Primary), "a drag holds the button")
        cursor.onGamepad(GamepadEvent.Disconnected(GamepadId.First))
        assertTrue(sink.events.last() is PointerEvent.Cancel)
        val at = cursor.position
        cursor.frame(20)
        assertEquals(at, cursor.position, "and the stick is forgotten")
    }

    @Test
    fun `every event it sends is a ray so the desktop cursor is left alone`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        cursor.stick(1f)
        cursor.frame(10)
        cursor.onGamepad(GamepadEvent.ButtonUp(GamepadId.First, GamepadButton.South))
        cursor.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightY, 1f))
        cursor.frame(20)
        cursor.enabled = false
        cursor.frame(30)
        assertEquals(setOf("Move", "Press", "Release", "Scroll", "Exit"), sink.events.map { it::class.simpleName }.toSet())
        assertTrue(sink.events.all { it.type == PointerType.Ray }, "${sink.events}")
    }

    @Test
    fun `south before the cursor has been placed is taken but presses nothing`() {
        val cursor = cursor()
        assertTrue(cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South)))
        assertFalse(cursor.pressed)
        assertTrue(sink.events.isEmpty(), "no press in the corner: ${sink.events}")
    }

    @Test
    fun `another pad pulled out leaves the drag alone`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.onGamepad(GamepadEvent.ButtonDown(GamepadId.First, GamepadButton.South))
        cursor.stick(1f)
        cursor.onGamepad(GamepadEvent.Disconnected(GamepadId(1)))
        assertTrue(cursor.pressed)
        assertTrue(sink.events.none { it is PointerEvent.Cancel })
        cursor.frame(10)
        assertTrue(cursor.position.x > 500f, "and its stick still carries the cursor")
    }

    @Test
    fun `the right stick scrolls where the cursor is`() {
        val cursor = cursor()
        cursor.frame(0)
        cursor.onGamepad(GamepadEvent.Axis(GamepadId.First, GamepadAxis.RightY, 1f))
        cursor.frame(100)
        val scroll = sink.events.last() as PointerEvent.Scroll
        assertEquals(Offset(500f, 250f), scroll.position)
        assertTrue(abs(scroll.delta.y - cursor.scrollSpeed * 0.1f) < 0.01f, "${scroll.delta}")
    }
}
