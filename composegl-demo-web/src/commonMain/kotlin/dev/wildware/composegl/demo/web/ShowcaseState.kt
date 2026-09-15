package dev.wildware.composegl.demo.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.input.GamepadButton
import dev.wildware.composegl.ui.input.GamepadEvent
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType

/** One stop on the tour. [tag] is what the tests and the page's links call it. */
enum class Section(val title: String, val blurb: String, val tag: String) {
    Home("Home", "What ComposeGL is", "home"),
    Widgets("Widgets", "Buttons, fields, lists, dialogs, rebinding", "widgets"),
    Layout("Layout", "Rows, grids, flow, ratios, baselines", "layout"),
    Animation("Animation", "Springs, crossfades, shake, sprites", "animation"),
    Game("Game widgets", "Health bars, cooldowns, hotbar, dialogue", "game"),
    Effects("Effects", "Gradients, clips, blend, 3D, shaders", "effects"),
    Text("Text", "Styled runs, selection, fallback, right to left", "text"),
    Settings("Accessibility", "Skins, high contrast and text size", "settings"),
    Debug("Debug tools", "Overlays, inspector and the frame budget", "debug"),
}

/** The three looks the settings page switches between. The first is the showcase's own. */
enum class SkinChoice(val title: String) { Showcase("Showcase"), Standard("Standard"), HighContrast("High contrast") }

/**
 * Everything the showcase's frame knows, in Compose state.
 *
 * Kept apart from the screens so that what the tour does — which page is showing, what the
 * shortcuts do, when the layout folds up for a phone — is tested without drawing anything.
 */
class ShowcaseState {

    var section by mutableStateOf(Section.Home)
        private set

    /** How wide the interface is laid out, in design units. The page sets it from the window. */
    var width by mutableFloatStateOf(1280f)

    /** Narrow enough that the section list goes along the top rather than down the side. */
    val compact: Boolean get() = width < CompactBelow

    var skin by mutableStateOf(SkinChoice.Showcase)

    /** One of [TextScales]. */
    var textScale by mutableFloatStateOf(1f)

    /**
     * Stops everything that would otherwise move forever: the landing page's display, looping
     * sprites, marquees, the ticking score. What moves because you asked it to still moves.
     */
    var reduceMotion by mutableStateOf(false)

    var layoutOverlay by mutableStateOf(false)
    var focusOverlay by mutableStateOf(false)
    var redrawOverlay by mutableStateOf(false)
    var textMetricsOverlay by mutableStateOf(false)
    var inspector by mutableStateOf(false)

    /** The frame-time line in the corner. */
    var budgetReadout by mutableStateOf(true)

    /** The dialog the widgets page opens; at the top of the screen, over everything. */
    var dialogOpen by mutableStateOf(false)

    /** Bumped on every page change, so a page's scroll starts at the top. */
    var visits by mutableIntStateOf(0)
        private set

    fun goTo(section: Section) {
        if (section == this.section) return
        this.section = section
        visits++
    }

    fun next() = goTo(Section.entries[(section.ordinal + 1) % Section.entries.size])

    fun previous() = goTo(Section.entries[(section.ordinal - 1 + Section.entries.size) % Section.entries.size])

    /**
     * The tour's own keys, asked before anything on the screen is: Page Down and Page Up turn the
     * page. Everything else is the screen's, so Tab, the arrows and typing all still work.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.Down || dialogOpen) return false
        return when (event.key) {
            Key.PageDown -> true.also { next() }
            Key.PageUp -> true.also { previous() }
            else -> false
        }
    }

    /** The shoulder buttons turn the page on a pad, the way a game's menus do. */
    fun onGamepad(event: GamepadEvent): Boolean {
        if (event !is GamepadEvent.ButtonDown || dialogOpen) return false
        return when (event.button) {
            GamepadButton.RightBumper -> true.also { next() }
            GamepadButton.LeftBumper -> true.also { previous() }
            else -> false
        }
    }

    companion object {
        const val CompactBelow = 820f
        val TextScales = listOf(1f, 1.25f, 1.5f)
    }
}
