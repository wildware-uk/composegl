package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.composegl.ui.animation.AnimatedVisibility
import dev.wildware.composegl.ui.animation.Crossfade
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.fadeIn
import dev.wildware.composegl.ui.animation.fadeOut
import dev.wildware.composegl.ui.animation.scaleIn
import dev.wildware.composegl.ui.animation.scaleOut
import dev.wildware.composegl.ui.debug.FocusOverlay
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.Inspector
import dev.wildware.composegl.ui.debug.LayoutOverlay
import dev.wildware.composegl.ui.debug.RedrawOverlay
import dev.wildware.composegl.ui.debug.TextMetricsOverlay
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Dialog
import dev.wildware.composegl.ui.widget.GamepadKeyboard
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.ProvideGamepadKeyboard
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.ScrollState
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TooltipHost
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val RepoUrl = "https://github.com/wildware-uk/composegl"
const val WikiUrl = "https://github.com/wildware-uk/composegl/wiki"

/** The frame budget the renderer times frames with, for the corner readout and the debug page. Null when nothing measures. */
val LocalBudget = staticCompositionLocalOf<FrameBudget?> { null }

/** Opens a link in a new tab, however the platform does that. */
val LocalOpenLink = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The size to lay the showcase out at for a window [width] by [height] CSS pixels.
 *
 * One design unit per CSS pixel, so text is the size a page's text is — except on a very narrow
 * phone, where the interface is laid out at 400 wide and shrunk, and on a very wide monitor, where
 * it is laid out at 1680 and grown, so lines do not run the width of a television.
 */
fun designFor(width: Double, height: Double): Size {
    val scale = when {
        width < MinDesignWidth -> width / MinDesignWidth
        width > MaxDesignWidth -> width / MaxDesignWidth
        else -> 1.0
    }
    return Size((width / scale).toFloat(), (max(height, 1.0) / scale).toFloat())
}

private const val MinDesignWidth = 400.0
private const val MaxDesignWidth = 1680.0
private const val NavWidth = 214f

/**
 * The whole tour: a header with the frame time in its corner, the sections down the side (along the
 * top on a phone), the page, and the dialog and overlays over everything.
 *
 * @param skins what the settings page switches between.
 * @param budget the renderer's frame budget, for the readout. Null shows none.
 * @param openLink opens the repository or the wiki.
 */
@Composable
fun Showcase(
    state: ShowcaseState,
    skins: ShowcaseSkins,
    budget: FrameBudget? = null,
    openLink: (String) -> Unit = {},
) {
    val keyboard = remember { GamepadKeyboard() }
    CompositionLocalProvider(
        LocalShowcase provides state,
        LocalBudget provides budget,
        LocalOpenLink provides openLink,
        LocalCardWidth provides cardWidthFor(state),
    ) {
        ProvideSkin(skins[state.skin]) {
            ProvideTextScale(state.textScale) {
                Inspector(state.inspector, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().styled("screen")) {
                        TooltipHost {
                            PopupHost {
                                ProvideGamepadKeyboard(keyboard, Modifier.width(min(600f, state.width - 24f))) {
                                    if (state.compact) CompactFrame(state) else WideFrame(state)
                                    AnimatedVisibility(
                                        state.dialogOpen,
                                        Modifier.fillMaxSize(),
                                        enter = fadeIn() + scaleIn(from = 0.92f),
                                        exit = fadeOut() + scaleOut(to = 0.92f),
                                    ) { AbandonDialog(state) }
                                }
                            }
                        }
                        LayoutOverlay(state.layoutOverlay)
                        FocusOverlay(state.focusOverlay)
                        RedrawOverlay(state.redrawOverlay)
                        TextMetricsOverlay(state.textMetricsOverlay)
                    }
                }
            }
        }
    }
}

/** Cards two or three across on a desktop, one across on a phone, never wider than reads well. */
internal fun cardWidthFor(state: ShowcaseState): Float {
    if (state.compact) return state.width - 2 * CompactPadding - ScrollBarRoom
    val available = state.width - NavWidth - 2 * WidePadding - ScrollBarRoom
    val across = max(1f, floor((available + CardGap) / (MinCardWidth + CardGap)))
    return min(MaxCardWidth, (available - CardGap * (across - 1)) / across)
}

private const val CompactPadding = 12f
private const val WidePadding = 24f
private const val ScrollBarRoom = 12f
private const val CardGap = 14f
private const val MinCardWidth = 340f
private const val MaxCardWidth = 560f

@Composable
private fun WideFrame(state: ShowcaseState) {
    Column(Modifier.fillMaxSize()) {
        Header(state)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.width(NavWidth).fillMaxHeight().padding(left = 12f, right = 8f, bottom = 12f),
                verticalArrangement = Arrangement.spacedBy(4f),
            ) {
                Section.entries.forEach { NavButton(state, it, Modifier.fillMaxWidth()) }
                Box(Modifier.weight(1f)) {}
                Text("Page Up / Page Down, or a pad's bumpers, turn the page.", Modifier.fillMaxWidth(), style = "label.dim")
                Links()
            }
            PageArea(state, Modifier.weight(1f).fillMaxHeight(), WidePadding)
        }
    }
}

@Composable
private fun CompactFrame(state: ShowcaseState) {
    Column(Modifier.fillMaxSize()) {
        Header(state)
        ScrollArea(Modifier.fillMaxWidth(), horizontal = true, vertical = false, bars = false) {
            Row(Modifier.padding(horizontal = CompactPadding, vertical = 4f), horizontalArrangement = Arrangement.spacedBy(4f)) {
                Section.entries.forEach { NavButton(state, it) }
            }
        }
        PageArea(state, Modifier.fillMaxWidth().weight(1f), CompactPadding)
    }
}

@Composable
private fun NavButton(state: ShowcaseState, section: Section, modifier: Modifier = Modifier) {
    Button(
        onClick = { state.goTo(section) },
        modifier = modifier.testTag("nav-${section.tag}"),
        style = if (state.section == section) "item.selected" else "item",
        initialFocus = section == Section.Home,
        contentAlignment = Alignment.CentreStart,
    ) { Text(section.title) }
}

@Composable
private fun Header(state: ShowcaseState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (state.compact) CompactPadding else 20f, vertical = 10f),
        horizontalArrangement = Arrangement.spacedBy(14f),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        Text("ComposeGL", style = "label.heading")
        if (!state.compact) {
            Text("Compose-style game UI, drawn inside your game's own OpenGL or WebGL frame", Modifier.weight(1f), style = "label.dim", maxLines = 1, ellipsis = "…")
        } else {
            Box(Modifier.weight(1f)) {}
        }
        val budget = LocalBudget.current
        if (budget != null && state.budgetReadout) BudgetLine(budget)
    }
}

/** The frame time and the draw calls, a few times a second. */
@Composable
fun BudgetLine(budget: FrameBudget, modifier: Modifier = Modifier) {
    val reading = budget.reading
    val calls = if (reading.drawCalls < 0) "–" else "${reading.drawCalls}"
    Text("${oneDecimal(reading.totalMillis)} ms · $calls draw calls", modifier.testTag("budget"), style = "code")
}

internal fun oneDecimal(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

@Composable
fun Links() {
    val open = LocalOpenLink.current
    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
        Button("GitHub", { open(RepoUrl) }, Modifier.testTag("link-github"), style = "button.quiet")
        Button("Wiki", { open(WikiUrl) }, Modifier.testTag("link-wiki"), style = "button.quiet")
    }
}

@Composable
private fun PageArea(state: ShowcaseState, modifier: Modifier, padding: Float) {
    // A fresh scroll position per page, so a page always opens at its top.
    val scroll = remember(state.visits) { ScrollState() }
    ScrollArea(modifier.testTag("page-area"), scroll) {
        Box(Modifier.fillMaxWidth().padding(left = padding, right = padding + ScrollBarRoom, top = 4f, bottom = 28f)) {
            Crossfade(state.section, Modifier.fillMaxWidth(), spec = Tween(160)) { section ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20f)) {
                    when (section) {
                        Section.Home -> HomePage()
                        Section.Widgets -> WidgetsPage()
                        Section.Layout -> LayoutPage()
                        Section.Animation -> AnimationPage()
                        Section.Game -> GamePage()
                        Section.Effects -> EffectsPage()
                        Section.Text -> TextPage()
                        Section.Settings -> SettingsPage()
                        Section.Debug -> DebugPage()
                    }
                    if (state.compact) Links()
                }
            }
        }
    }
}

@Composable
private fun AbandonDialog(state: ShowcaseState) {
    Dialog(
        onDismiss = { state.dialogOpen = false },
        modifier = Modifier.width(min(420f, state.width - 40f)).testTag("dialog"),
        dismissOnScrim = true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12f)) {
            Text("Abandon the mission?", style = "label.heading")
            Text("A real Dialog: focus is trapped inside, Escape or a pad's B closes it, and the screen behind cannot be clicked.", Modifier.fillMaxWidth(), style = "label.dim")
            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Button("Stay", { state.dialogOpen = false }, Modifier.testTag("dialog-stay"), style = "button.primary", initialFocus = true)
                Button("Abandon", { state.dialogOpen = false }, style = "button.danger")
            }
        }
    }
}
