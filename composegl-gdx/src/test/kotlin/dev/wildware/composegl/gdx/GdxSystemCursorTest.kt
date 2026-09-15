package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.mock.graphics.MockGraphics
import com.badlogic.gdx.graphics.Cursor
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mouse cursor, through LibGDX.
 *
 * The wiring is checked against LibGDX's own no-op graphics, so it runs anywhere. The one test that
 * needs a window hands every shape to a real desktop window, because the one thing a recording
 * cannot say is whether GLFW underneath will actually take them.
 */
class GdxSystemCursorTest {

    /** LibGDX's own no-op graphics, with the one call this cares about written down. */
    private class Recording : MockGraphics() {
        val asked = mutableListOf<Cursor.SystemCursor>()
        override fun setSystemCursor(systemCursor: Cursor.SystemCursor) {
            asked += systemCursor
        }
    }

    private val graphics = Recording()

    @Test
    fun `a shape reaches the engine as LibGDX's name for it`() {
        val cursor = GdxSystemCursor(graphics)

        cursor.set(PointerIcon.Text)
        cursor.set(PointerIcon.Hand)
        cursor.set(PointerIcon.Default)

        assertEquals(listOf(Cursor.SystemCursor.Ibeam, Cursor.SystemCursor.Hand, Cursor.SystemCursor.Arrow), graphics.asked)
    }

    @Test
    fun `every shape has a cursor of its own and none of them hides the pointer`() {
        val shapes = PointerIcon.entries.map { GdxSystemCursor.shapeOf(it) }

        assertEquals(PointerIcon.entries.size, shapes.toSet().size, "two icons came out as one shape: $shapes")
        assertFalse(Cursor.SystemCursor.None in shapes, "None hides the cursor altogether")
    }

    @Test
    fun `the router drives the engine's cursor as the mouse moves`() {
        val screen = TestTree()
        screen.root.width = 200f
        screen.root.height = 200f
        screen.box("field", 0f, 0f, 100f, 30f, Modifier.pointerHoverIcon(PointerIcon.Text))
        val router = PointerRouter(screen.root, FocusManager(screen.root), GdxSystemCursor(graphics))

        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(10f, 10f)))
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(20f, 10f)))
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(20f, 100f)))

        assertEquals(listOf(Cursor.SystemCursor.Ibeam, Cursor.SystemCursor.Arrow), graphics.asked)
    }

    @Test
    fun `a game that has not started yet can still ask`() {
        // The router may be built, and even told about a shape, before `Gdx.graphics` exists.
        NoGl.refusingGl {
            val engine = Gdx.graphics
            try {
                Gdx.graphics = null
                GdxSystemCursor().set(PointerIcon.Text)
            } finally {
                Gdx.graphics = engine
            }
        }
    }

    @Test
    fun `a real desktop window takes every shape`() {
        val taken = Gl.render {
            val cursor = GdxSystemCursor()
            PointerIcon.entries.forEach { cursor.set(it) }
            cursor.set(PointerIcon.Default)
            true
        }

        assertTrue(taken)
    }
}
