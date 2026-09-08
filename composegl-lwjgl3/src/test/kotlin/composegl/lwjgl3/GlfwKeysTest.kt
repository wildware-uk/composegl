package composegl.lwjgl3

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import org.lwjgl.glfw.GLFW
import java.lang.reflect.Modifier

/**
 * Every GLFW key is mapped or explicitly ignored, so an LWJGL upgrade that adds keys cannot
 * quietly stop delivering them.
 */
class GlfwKeysTest {

    private val ignored = mapOf(
        "GLFW_KEY_UNKNOWN" to "GLFW's own 'no idea' value",
        "GLFW_KEY_LAST" to "the end of the range, not a key",
        "GLFW_KEY_WORLD_1" to "the non-US layout escape hatch; no Compose equivalent",
        "GLFW_KEY_WORLD_2" to "the non-US layout escape hatch; no Compose equivalent",
    ) + (13..25).associate { "GLFW_KEY_F$it" to "Compose's Key stops at F12" }

    private fun allKeyConstants(): Map<String, Int> =
        GLFW::class.java.declaredFields
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.type == Int::class.javaPrimitiveType &&
                    it.name.startsWith("GLFW_KEY_")
            }
            .associate { it.name to it.getInt(null) }

    @Test
    fun `every GLFW key is mapped or explicitly ignored`() {
        val unmapped = allKeyConstants()
            .filterKeys { it !in ignored }
            .filterValues { composeKeyFor(it) == null }

        assertTrue(unmapped.isEmpty()) {
            "These GLFW keys map to nothing. Add them to composeKeyFor, or give them a reason in " +
                "the ignore list: ${unmapped.keys.sorted()}"
        }
    }

    @Test
    fun `the ignore list has no stale entries`() {
        val names = allKeyConstants().keys
        val stale = ignored.keys.filter { it !in names }
        assertTrue(stale.isEmpty()) { "These names are no longer in GLFW: $stale" }
    }

    @Test
    fun `no two GLFW keys map to the same Compose key`() {
        val mapped = allKeyConstants()
            .filterKeys { it !in ignored }
            .mapNotNull { (name, code) -> composeKeyFor(code)?.let { name to it } }
        val duplicates = mapped.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty()) { "Two GLFW keys map to the same Compose key: $duplicates" }
    }

    @Test
    fun `the ranges really do line up, not just at their ends`() {
        assertEquals(Key.A, composeKeyFor(GLFW.GLFW_KEY_A))
        assertEquals(Key.M, composeKeyFor(GLFW.GLFW_KEY_M))
        assertEquals(Key.Z, composeKeyFor(GLFW.GLFW_KEY_Z))
        assertEquals(Key.Zero, composeKeyFor(GLFW.GLFW_KEY_0))
        assertEquals(Key.Five, composeKeyFor(GLFW.GLFW_KEY_5))
        assertEquals(Key.Nine, composeKeyFor(GLFW.GLFW_KEY_9))
        assertEquals(Key.F1, composeKeyFor(GLFW.GLFW_KEY_F1))
        assertEquals(Key.F7, composeKeyFor(GLFW.GLFW_KEY_F7))
        assertEquals(Key.F12, composeKeyFor(GLFW.GLFW_KEY_F12))
        assertEquals(Key.NumPad0, composeKeyFor(GLFW.GLFW_KEY_KP_0))
        assertEquals(Key.NumPad9, composeKeyFor(GLFW.GLFW_KEY_KP_9))
    }

    @Test
    fun `the keys a HUD actually uses`() {
        assertEquals(Key.Backspace, composeKeyFor(GLFW.GLFW_KEY_BACKSPACE))
        assertEquals(Key.Delete, composeKeyFor(GLFW.GLFW_KEY_DELETE))
        assertEquals(Key.Tab, composeKeyFor(GLFW.GLFW_KEY_TAB))
        assertEquals(Key.Escape, composeKeyFor(GLFW.GLFW_KEY_ESCAPE))
        assertEquals(Key.DirectionLeft, composeKeyFor(GLFW.GLFW_KEY_LEFT))
        assertEquals(Key.MoveHome, composeKeyFor(GLFW.GLFW_KEY_HOME))
        assertEquals(Key.CtrlLeft, composeKeyFor(GLFW.GLFW_KEY_LEFT_CONTROL))
    }

    @Test
    fun `an unknown key maps to nothing rather than to something wrong`() {
        assertNull(composeKeyFor(GLFW.GLFW_KEY_UNKNOWN))
        assertNull(composeKeyFor(9999))
    }

    @Test
    fun `modifier bits come across`() {
        val none = modifiersOf(0)
        assertEquals(false, none.isShiftPressed)

        val shiftCtrl = modifiersOf(GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL)
        assertEquals(true, shiftCtrl.isShiftPressed)
        assertEquals(true, shiftCtrl.isCtrlPressed)
        assertEquals(false, shiftCtrl.isAltPressed)
        assertEquals(false, shiftCtrl.isMetaPressed)
    }
}
