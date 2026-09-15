package dev.wildware.composegl.korge.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.game.DamageNumbers
import dev.wildware.composegl.ui.input.Action
import dev.wildware.composegl.ui.input.InputBinding
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.Prompts
import dev.wildware.composegl.ui.input.bind

/** The three screens the demo has. */
enum class DemoScreen { Menu, Game, Settings }

/**
 * Everything the interface reads, in Compose state.
 *
 * The KorGE side writes to it once a frame from its updaters — health, score, where the hits land —
 * and the interface reads it. Neither side knows how the other works.
 */
class DemoState {

    var screen by mutableStateOf(DemoScreen.Menu)

    /** The player, 0 to 1. The HUD's bar drains a trail behind every hit. */
    var health by mutableFloatStateOf(1f)
    var shield by mutableFloatStateOf(0.6f)
    var score by mutableIntStateOf(0)

    /** Floating numbers, filled by the world and drawn by the HUD. */
    val damage = DamageNumbers(lifeMillis = 1_000, criticalLifeMillis = 1_500)

    /** How far through the logo's dissolve the menu is, driven by the game loop. */
    var dissolve by mutableFloatStateOf(0f)

    // --- settings --------------------------------------------------------------------------------

    var volume by mutableFloatStateOf(70f)
    var difficulty by mutableStateOf("Normal")
    var quality by mutableStateOf("High")
    var callsign by mutableStateOf("Ada")
    var highContrast by mutableStateOf(false)
    var showBudget by mutableStateOf(true)
    var jump: InputBinding? by mutableStateOf(InputBinding.Keyboard(Key.Space))

    /** What every `PromptGlyph` on screen draws for an action. Rebinding jump changes it. */
    val prompts = Prompts().also { it.bind(Jump, InputBinding.Keyboard(Key.Space)) }

    /**
     * Whether the HUD starts two cooldowns the moment it opens, so a screenshot shows a sweep. A test
     * that waits for the screen to settle switches it off.
     */
    var primeCooldowns = true

    /** What QUIT does. The game closes its window; a test leaves it alone. */
    var onQuit: () -> Unit = {}

    fun rebindJump(binding: InputBinding) {
        jump = binding
        prompts.bind(Jump, binding)
    }

    /** Escape, B and Back when nothing open wanted them: every screen but the menu goes to the menu. */
    fun back() {
        screen = DemoScreen.Menu
    }

    companion object {
        val Jump = Action("jump")
    }
}
