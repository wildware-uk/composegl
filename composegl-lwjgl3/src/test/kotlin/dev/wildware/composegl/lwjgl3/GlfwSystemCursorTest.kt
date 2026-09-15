package dev.wildware.composegl.lwjgl3

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerIcon
import dev.wildware.composegl.ui.input.PointerId
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.pointerHoverIcon
import dev.wildware.composegl.ui.testing.TestTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * The mouse cursor, through GLFW.
 *
 * The bookkeeping — made once, kept, let go of, the arrow when GLFW has no picture — is checked with
 * the three GLFW calls swapped for a recording, so it runs anywhere. The window test hands the same
 * code GLFW's real calls, because whether a standard cursor comes back is the desktop's answer.
 */
class GlfwSystemCursorTest {

    private val made = mutableListOf<Int>()
    private val attached = mutableListOf<Long>()
    private val destroyed = mutableListOf<Long>()

    /** Hands out handles 100, 101, …, and nothing for the shapes in [missing]. */
    private fun recording(missing: Set<Int> = emptySet()) = GlfwSystemCursor(
        create = { shape ->
            made += shape
            if (shape in missing) 0L else 100L + made.size - 1
        },
        attach = { attached += it },
        destroy = { destroyed += it },
    )

    @Test
    fun `a shape is made once and attached every time it is asked for`() {
        val cursor = recording()

        cursor.set(PointerIcon.Text)
        cursor.set(PointerIcon.Default)
        cursor.set(PointerIcon.Text)

        assertEquals(listOf(GLFW.GLFW_IBEAM_CURSOR), made, "the I-beam was made the first time only")
        assertEquals(listOf(100L, 0L, 100L), attached, "and the arrow is GLFW's own default, made by nobody")
    }

    @Test
    fun `a shape the desktop has no picture for shows the arrow and is not asked for again`() {
        val cursor = recording(missing = setOf(GLFW.GLFW_NOT_ALLOWED_CURSOR))

        cursor.set(PointerIcon.NotAllowed)
        cursor.set(PointerIcon.NotAllowed)

        assertEquals(listOf(GLFW.GLFW_NOT_ALLOWED_CURSOR), made)
        assertEquals(listOf(0L, 0L), attached)
    }

    @Test
    fun `closing lets go of every cursor made and leaves the window alone`() {
        val cursor = recording(missing = setOf(GLFW.GLFW_RESIZE_ALL_CURSOR))
        cursor.set(PointerIcon.Text)
        cursor.set(PointerIcon.Hand)
        cursor.set(PointerIcon.Move)
        attached.clear()

        cursor.close()

        assertEquals(emptyList<Long>(), attached, "GLFW puts the arrow back itself when a shown cursor goes")
        assertEquals(listOf(100L, 101L), destroyed, "and the one GLFW never made is not destroyed")
    }

    @Test
    fun `every shape has a GLFW cursor of its own`() {
        val shapes = PointerIcon.entries.map { GlfwSystemCursor.shapeOf(it) }

        assertEquals(PointerIcon.entries.size, shapes.toSet().size, "two icons came out as one shape: $shapes")
    }

    @Test
    fun `the router drives the window's cursor as the mouse moves`() {
        val screen = TestTree()
        screen.root.width = 200f
        screen.root.height = 200f
        screen.box("edge", 0f, 0f, 10f, 200f, Modifier.pointerHoverIcon(PointerIcon.ResizeHorizontal))
        val router = PointerRouter(screen.root, cursor = recording())

        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(5f, 50f)))
        router.onPointer(PointerEvent.Move(PointerId.Mouse, Offset(50f, 50f)))

        assertEquals(listOf(GLFW.GLFW_RESIZE_EW_CURSOR), made)
        assertEquals(listOf(100L, 0L), attached)
    }

    @Test
    fun `a real window takes every shape and the common ones exist`() {
        Gl.window { window ->
            val handles = mutableMapOf<Int, Long>()
            val cursor = GlfwSystemCursor(
                create = { shape -> GLFW.glfwCreateStandardCursor(shape).also { handles[shape] = it } },
                attach = { GLFW.glfwSetCursor(window.handle, it) },
                destroy = { GLFW.glfwDestroyCursor(it) },
            )
            try {
                PointerIcon.entries.forEach { cursor.set(it) }
                // The shapes every X11 cursor font has had since long before GLFW 3.4. The newer
                // diagonal and "not allowed" ones depend on the desktop's theme, so they are only
                // required not to break anything.
                listOf(
                    GLFW.GLFW_IBEAM_CURSOR,
                    GLFW.GLFW_CROSSHAIR_CURSOR,
                    GLFW.GLFW_POINTING_HAND_CURSOR,
                    GLFW.GLFW_RESIZE_EW_CURSOR,
                    GLFW.GLFW_RESIZE_NS_CURSOR,
                ).forEach { shape -> assertNotEquals(0L, handles[shape], "GLFW made no cursor for $shape") }
                assertTrue(handles.size == PointerIcon.entries.size - 1, "every shape but the arrow was made")
            } finally {
                cursor.close()
            }
        }
    }
}
