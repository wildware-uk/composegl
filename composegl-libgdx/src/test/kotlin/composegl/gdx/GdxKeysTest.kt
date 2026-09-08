package composegl.gdx

import androidx.compose.ui.input.key.Key
import com.badlogic.gdx.Input
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier as JavaModifier

/**
 * Every `Input.Keys` constant is either mapped or explicitly ignored. Without this, a LibGDX
 * release that adds keys would quietly stop delivering them and nobody would notice until a user
 * reported that F13 does nothing.
 */
class GdxKeysTest {

    /** Names that are not keys, or that Compose has no name for. Each one needs a reason. */
    private val ignored = mapOf(
        "ANY_KEY" to "a wildcard for isKeyPressed, not a key",
        "UNKNOWN" to "LibGDX's own 'no idea' value",
        "MAX_KEYCODE" to "the size of the range, not a key",
        "BUTTON_CIRCLE" to "shares MAX_KEYCODE's value and has no Compose name",
        "META_ALT_ON" to "a modifier bitmask, not a keycode",
        "META_ALT_LEFT_ON" to "a modifier bitmask, not a keycode",
        "META_ALT_RIGHT_ON" to "a modifier bitmask, not a keycode",
        "META_SHIFT_ON" to "a modifier bitmask, not a keycode",
        "META_SHIFT_LEFT_ON" to "a modifier bitmask, not a keycode",
        "META_SHIFT_RIGHT_ON" to "a modifier bitmask, not a keycode",
        "META_SYM_ON" to "a modifier bitmask, not a keycode",
        "COLON" to "Compose has no Colon key; it arrives as a typed character instead",
        "WORLD_1" to "GLFW's non-US layout escape hatch; no Compose equivalent",
        "WORLD_2" to "GLFW's non-US layout escape hatch; no Compose equivalent",
        "DEL" to "the older spelling of BACKSPACE, same value",
        "CENTER" to "the older spelling of DPAD_CENTER, same value",
        "UP" to "the older spelling of DPAD_UP, same value",
        "DOWN" to "the older spelling of DPAD_DOWN, same value",
        "LEFT" to "the older spelling of DPAD_LEFT, same value",
        "RIGHT" to "the older spelling of DPAD_RIGHT, same value",
    ) + (13..24).associate { "F$it" to "Compose's Key stops at F12" }

    private fun allKeyConstants(): Map<String, Int> =
        Input.Keys::class.java.declaredFields
            .filter { JavaModifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    @Test
    fun `every LibGDX key constant is mapped or explicitly ignored`() {
        val unmapped = allKeyConstants()
            .filterKeys { it !in ignored }
            .filterValues { composeKeyFor(it) == null }

        assertTrue(unmapped.isEmpty()) {
            "These Input.Keys constants map to nothing. Either add them to composeKeyFor or " +
                "give them a reason in the ignore list: ${unmapped.keys.sorted()}"
        }
    }

    @Test
    fun `the ignore list has no stale entries`() {
        val names = allKeyConstants().keys
        val stale = ignored.keys.filter { it !in names }
        assertTrue(stale.isEmpty()) { "These names are no longer in Input.Keys: $stale" }
    }

    @Test
    fun `the mapping is not accidentally many-to-one`() {
        val mapped = allKeyConstants()
            .filterKeys { it !in ignored }
            .mapNotNull { (name, code) -> composeKeyFor(code)?.let { name to it } }
        val duplicates = mapped.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty()) { "Two LibGDX keys map to the same Compose key: $duplicates" }
    }

    @Test
    fun `the keys a HUD actually uses`() {
        assertEquals(Key.A, composeKeyFor(Input.Keys.A))
        assertEquals(Key.Backspace, composeKeyFor(Input.Keys.BACKSPACE))
        assertEquals(Key.Backspace, composeKeyFor(Input.Keys.DEL), "the two spellings are one key")
        assertEquals(Key.Delete, composeKeyFor(Input.Keys.FORWARD_DEL))
        assertEquals(Key.Tab, composeKeyFor(Input.Keys.TAB))
        assertEquals(Key.Enter, composeKeyFor(Input.Keys.ENTER))
        assertEquals(Key.Escape, composeKeyFor(Input.Keys.ESCAPE))
        assertEquals(Key.DirectionLeft, composeKeyFor(Input.Keys.LEFT))
        assertEquals(Key.Spacebar, composeKeyFor(Input.Keys.SPACE))
        assertEquals(Key.CtrlLeft, composeKeyFor(Input.Keys.CONTROL_LEFT))
    }

    @Test
    fun `Home and End are the desktop keys, not Android's home button`() {
        assertEquals(Key.MoveHome, composeKeyFor(Input.Keys.HOME))
        assertEquals(Key.MoveEnd, composeKeyFor(Input.Keys.END))
    }

    @Test
    fun `an unknown keycode maps to nothing rather than to something wrong`() {
        assertNull(composeKeyFor(Input.Keys.UNKNOWN))
        assertNull(composeKeyFor(9999))
        assertNull(composeKeyFor(-42))
    }
}
