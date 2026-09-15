package dev.wildware.composegl.korge.demo

import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.testing.uiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The demo's screens with no window and no GPU: the same `DemoUi` the KorGE game shows, played with a
 * pad, keys and clicks. `DemoBootTest` does it again inside a real KorGE game.
 */
class DemoScreensTest {

    /**
     * The demo, made still enough to settle: a budget that is not measuring shows fixed numbers, and no
     * cooldown is left sweeping when the HUD opens. `uiTest` waits for a screen to stop changing.
     */
    private fun demo(state: DemoState) = uiTest(Size(1280f, 720f), onBack = state::back) {
        DemoUi(state, FrameBudget().also { it.isOn = false })
    }

    private fun still(state: DemoState) = state.also { it.primeCooldowns = false }

    @Test
    fun `a pad walks the menu into settings and back, and PLAY opens the HUD`() {
        val state = still(DemoState())
        demo(state).use { ui ->
            ui.assertExists("menu")
            ui.assertFocused("play")

            ui.pad(GamepadButton.DpadDown)
            ui.assertFocused("settings")
            ui.pad(GamepadButton.South)
            assertEquals(DemoScreen.Settings, state.screen)
            ui.assertExists("settings-screen")
            ui.assertDoesNotExist("menu")

            ui.pad(GamepadButton.East)
            assertEquals(DemoScreen.Menu, state.screen, "B goes back to the menu")
            ui.assertExists("menu")

            ui.click("play")
            assertEquals(DemoScreen.Game, state.screen)
            ui.assertExists("hud")
            ui.assertExists("health")

            ui.key(Key.Escape)
            assertEquals(DemoScreen.Menu, state.screen, "Escape leaves the game for the menu")
        }
    }

    @Test
    fun `the settings change what they say, the skin switches, and jump is rebound`() {
        val state = still(DemoState()).also { it.screen = DemoScreen.Settings }
        demo(state).use { ui ->
            ui.assertFocused("volume")
            ui.key(Key.Right)
            assertEquals(75f, state.volume, "an arrow nudges the slider one step")

            // Down leaves the slider for the stepper under it, which takes left and right.
            ui.key(Key.Down)
            ui.assertFocused("difficulty")
            ui.key(Key.Right)
            assertEquals("Veteran", state.difficulty)

            assertFalse(state.highContrast)
            ui.click("skin")
            ui.key(Key.Right)
            assertTrue(state.highContrast, "the skin stepper switched to high contrast")

            ui.click("jump")
            ui.key(Key.J)
            assertEquals(InputBinding.Keyboard(Key.J), state.jump)

            ui.click("callsign")
            ui.type("X")
            assertEquals("AdaX", state.callsign)

            ui.click("budget-toggle")
            assertFalse(state.showBudget)
            ui.assertDoesNotExist("budget")

            ui.click("back")
            assertEquals(DemoScreen.Menu, state.screen)
        }
    }

    @Test
    fun `the HUD's hotbar answers the number keys`() {
        val state = still(DemoState()).also { it.screen = DemoScreen.Game }
        demo(state).use { ui ->
            ui.assertExists("hud")
            ui.key(Key.F3)
            assertFalse(state.showBudget, "F3 hides the frame budget")
            ui.key(Key.F3)
            assertTrue(state.showBudget)
            ui.assertExists("budget")
        }
    }
}
