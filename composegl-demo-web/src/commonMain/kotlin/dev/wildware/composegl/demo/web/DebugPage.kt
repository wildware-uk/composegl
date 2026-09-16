package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.debug.DebugWindow
import dev.wildware.composegl.debug.DevConsoleState
import dev.wildware.composegl.debug.DockSide
import dev.wildware.composegl.debug.FrameBudgetOverlay
import dev.wildware.composegl.debug.Histogram
import dev.wildware.composegl.debug.NodeTree
import dev.wildware.composegl.debug.Plot
import dev.wildware.composegl.debug.arg
import dev.wildware.composegl.debug.rememberDevConsole
import dev.wildware.composegl.debug.rememberPlotBuffer
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import kotlin.math.min

@Composable
fun DebugPage() {
    val state = LocalShowcase.current
    val budget = LocalBudget.current
    Page(Section.Debug, "The tools a game developer turns on while building a screen. Each draws over the whole showcase.") {
        Card("Overlays", "Turn one on, then look round the other pages.") {
            Toggle(state.layoutOverlay, { state.layoutOverlay = it }, label = "Layout: boxes, padding and gaps", modifier = Modifier.testTag("overlay-layout"))
            Toggle(state.focusOverlay, { state.focusOverlay = it }, label = "Focus: what Tab and the pad reach")
            Toggle(state.redrawOverlay, { state.redrawOverlay = it }, label = "Redraws: what changed this frame")
            Toggle(state.textMetricsOverlay, { state.textMetricsOverlay = it }, label = "Text metrics: baselines and ascent")
            Toggle(state.inspector, { state.inspector = it }, label = "Inspector: click anything to see its node")
            Toggle(state.budgetReadout, { state.budgetReadout = it }, label = "Frame time in the corner")
        }
        WindowsCard()
        DockingCard()
        ConsoleCard()
        PlotsCard()
        NodeTreeCard()
        Card("Frame budget", "Where each frame's time went, and what cost the batch a draw call.") {
            if (budget != null) {
                FrameBudgetOverlay(budget, Modifier.testTag("frame-budget"), culprits = 4)
            } else {
                Text("This host does not time its frames.", style = "label.dim")
            }
            Text("Each of these costs a draw call on purpose: a glow drawn additively, and a clipped panel.", style = "label.dim")
            Row(horizontalArrangement = Arrangement.spacedBy(10f)) {
                Box(Modifier.size(44f, 44f).background(Ink, corner = 6f).padding(8f).blend(BlendMode.Additive).background(Accent, corner = 4f).testTag("glow")) {}
                Box(Modifier.size(140f, 44f).background(Ink, corner = 6f).clip().padding(10f).testTag("clipped")) { Text("Clipped panel") }
            }
        }
        Card("Something busy", "A score that ticks five times a second. With redraws on, it flashes and nothing else does.") {
            var score by remember { mutableIntStateOf(0) }
            val clocks = LocalClocks.current
            val still = state.reduceMotion
            LaunchedEffect(still) {
                while (!still) {
                    clocks.wait(Clock.Ui, 200)
                    score += 10
                }
            }
            Text("SCORE $score", style = "label.heading")
        }
    }
}

@Composable
private fun WindowsCard() = Card("Debug windows and the tweak DSL", "A window of knobs is a few lines, each bound to a property the game already has. Drag it by its title, pull a corner, fold it. F9 hides every window.") {
    val state = LocalShowcase.current
    Toggle(state.tuningOpen, { state.tuningOpen = it }, label = "Tweaks window", modifier = Modifier.testTag("open-tweaks"))
    Toggle(state.nodesOpen, { state.nodesOpen = it }, label = "Node tree window", modifier = Modifier.testTag("open-nodes"))
    Text("tweak(\"Heat\", state::heat, 0f..1f)", style = "code")
    Text("choice(\"Weapon\", state::weapon, Weapons)", style = "code")
    Text("colour(\"Marker tint\", state::tint)", style = "code")
}

@Composable
private fun DockingCard() = Card("Docking", "Windows dock to the screen's edges and tab together. A drag onto the squares does it too; these buttons make the same moves.") {
    val state = LocalShowcase.current
    val windows = LocalWindows.current
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button("Tweaks to the left", {
            state.tuningOpen = true
            windows.dockToScreen(TweaksTitle, DockSide.Left)
        }, Modifier.testTag("dock-tweaks"), style = "button.quiet")
        Button("Tree to the right", {
            state.nodesOpen = true
            windows.dockToScreen(NodesTitle, DockSide.Right)
        }, style = "button.quiet")
        Button("Tab them together", {
            state.tuningOpen = true
            state.nodesOpen = true
            windows.dockWith(NodesTitle, TweaksTitle)
        }, style = "button.quiet")
        Button("Float them all", { windows.dockedWindows.toList().forEach(windows::undock) }, Modifier.testTag("undock"), enabled = windows.dockedWindows.isNotEmpty(), style = "button.quiet")
    }
    Text("Docked now: ${windows.dockedWindows.size}", Modifier.testTag("docked"), style = "label.dim")
}

@Composable
private fun ConsoleCard() = Card("Developer console", "Press the backtick key, or the button. Tab completes a command, Up brings back the last. Try: heat 0.9, weapon lance, page hud.") {
    val console = LocalConsole.current
    if (console == null) {
        Text("No console on this host.", style = "label.dim")
        return@Card
    }
    FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
        Button(if (console.isOpen) "Close the console" else "Open the console", { console.toggle() }, Modifier.testTag("open-console"), style = "button.primary")
        Button("Run help", { console.run("help") }, Modifier.testTag("run-help"), style = "button.quiet")
        Button("Overheat", { console.run("heat 0.95") }, Modifier.testTag("run-heat"), style = "button.quiet")
    }
    Text("${console.commands.size} commands · ${console.lines.size} lines logged", Modifier.testTag("console-count"), style = "label.dim")
}

@Composable
private fun PlotsCard() = Card("Live plots", "Heat over the last few seconds, and how long frames took. Fire to heat up; it cools by itself. Hover a plot to read a sample.") {
    val state = LocalShowcase.current
    val budget = LocalBudget.current
    val heat = rememberPlotBuffer(capacity = 180)
    val frames = remember { FloatArray(FrameWindow) }
    var measured by remember { mutableIntStateOf(0) }
    val running = !state.reduceMotion
    // A sample a frame while things move; with motion reduced, a sample only when the heat changes,
    // so a still screen stays still.
    LaunchedEffect(running) {
        while (running) {
            withFrameNanos { }
            heat.add(state.heat)
            if (state.heat > 0f) state.heat = (state.heat - 0.002f).coerceAtLeast(0f)
            if (budget != null) measured = budget.recentFrameMillis(frames)
        }
    }
    LaunchedEffect(running, state.heat) { if (!running) heat.add(state.heat) }
    Plot(heat, Modifier.fillMaxWidth().height(64f).testTag("plot"), range = 0f..1f, guides = listOf(0.8f), label = "heat")
    if (budget != null) Histogram(frames, Modifier.fillMaxWidth().height(56f), count = measured, label = "frame ms")
    Button("Fire", { state.heat = min(1f, state.heat + 0.25f) }, Modifier.testTag("fire"), style = "button.danger")
}

private const val FrameWindow = 60

@Composable
private fun NodeTreeCard() = Card("Node tree", "What the toolkit actually built for this page, this card included. Type to filter; the counts glow as nodes redraw.") {
    NodeTree(LocalInterfaceRoot.current.value, Modifier.fillMaxWidth().height(240f).testTag("node-tree"))
}

/** The titles the two windows are docked by: a window is known by its title. */
internal const val TweaksTitle = "Tweaks"
internal const val NodesTitle = "Node tree"

/**
 * The tour's knobs in a floating window. Every line is a property the rest of the showcase already
 * reads, so the window is only another way to change it.
 */
@Composable
internal fun TuningWindow(state: ShowcaseState) {
    DebugWindow(
        TweaksTitle,
        initialPosition = Offset(min(260f, state.width * 0.25f), 90f),
        initialSize = Size(min(300f, state.width - 20f), 300f),
        onClose = { state.tuningOpen = false },
        menuBar = {
            Menu("&Presets") {
                Item("&Cold") { state.heat = 0f }
                Item("&Overheating") { state.heat = 0.95f }
            }
        },
    ) {
        tweak("Heat", state::heat, 0f..1f, step = 0.05f)
        choice("Weapon", state::weapon, ShowcaseState.Weapons)
        CollapsingHeader("Look", initiallyExpanded = true) {
            colour("Marker tint", state::tint, alpha = false)
            choice("Text size", state::textScale, ShowcaseState.TextScales) { "${(it * 100).toInt()}%" }
            toggle("Reduce motion", state::reduceMotion)
        }
        button("Next page") { state.next() }
        text("Page", state.section.title)
    }
}

/** The interface's own node tree, in a window of its own. */
@Composable
internal fun NodesWindow(state: ShowcaseState, root: UiNode?) {
    DebugWindow(
        NodesTitle,
        initialPosition = Offset(min(620f, state.width * 0.45f), 120f),
        initialSize = Size(min(320f, state.width - 20f), 360f),
        onClose = { state.nodesOpen = false },
    ) {
        NodeTree(root, Modifier.fillMaxWidth().height(300f))
    }
}

/** The console the backtick opens, with commands that reach the showcase's own state. */
@Composable
internal fun rememberShowcaseConsole(state: ShowcaseState): DevConsoleState {
    val console = rememberDevConsole {
        command("heat", arg<Float>("level"), help = "How hot the weapon is, 0 to 1") { state.heat = it.coerceIn(0f, 1f) }
        command(
            "weapon",
            arg<String>("name", suggest = { ShowcaseState.Weapons.map { it.lowercase() } }),
            help = "Equip a weapon from the wheel",
        ) { name ->
            val gun = ShowcaseState.Weapons.firstOrNull { it.equals(name, ignoreCase = true) } ?: error("no weapon called $name")
            state.weapon = gun
        }
        command(
            "page",
            arg<String>("name", suggest = { Section.entries.map { it.tag } }),
            help = "Turn to a page of the tour",
        ) { name ->
            val section = Section.entries.firstOrNull { it.tag.equals(name, ignoreCase = true) } ?: error("no page called $name")
            state.goTo(section)
        }
        command("tweaks", help = "Open the tweaks window") { state.tuningOpen = true }
    }
    LaunchedEffect(console) {
        console.log("ComposeGL showcase console. Type help for what it can do.")
    }
    return console
}
