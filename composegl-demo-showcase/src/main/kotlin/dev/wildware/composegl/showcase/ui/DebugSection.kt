package dev.wildware.composegl.showcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.debug.DebugWindowsState
import dev.wildware.composegl.debug.DevConsoleState
import dev.wildware.composegl.debug.DockSide
import dev.wildware.composegl.debug.Histogram
import dev.wildware.composegl.debug.NodeTree
import dev.wildware.composegl.debug.Plot
import dev.wildware.composegl.debug.rememberPlotBuffer
import dev.wildware.composegl.showcase.Exhibit
import dev.wildware.composegl.showcase.Module
import dev.wildware.composegl.showcase.ShowcaseState
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

/** So a test can find the live plot without knowing which group it is in. */
internal const val DebugPlotTag = "showcase.debug.plot"

/** And the interface's own tree, inline rather than in its window. */
internal const val DebugNodesTag = "showcase.debug.nodes"

/**
 * What is in **composegl-debug**: the tools that sit over a game while it is running.
 *
 * Everything here drives the real tools rather than copies of them. The buttons open, dock and
 * float the same windows a drag moves; the console is the showcase's own, so a command typed at
 * the backtick and a command run from this button land in the same log; the plot is the weapon's
 * heat as it is now; and the node tree is the interface you are looking at, itself included.
 */
@Composable
internal fun DebugSection(
    state: ShowcaseState,
    budget: FrameBudget,
    windows: DebugWindowsState,
    console: DevConsoleState,
    interfaceRoot: UiNode?,
) {
    Group(
        "Windows and tweaks",
        "A window is four lines of the tweak DSL; every line is a property the game already had.",
        open = true,
    ) {
        Labelled("Fight window") { Toggle(state.tuningOpen, { state.tuningOpen = it }) }
        Labelled("UI tree window") {
            Toggle(state.isOn(Exhibit.Nodes), { state.toggle(Exhibit.Nodes) })
        }
        Text(
            "tweak(\"Damage\", state::damageScale, 0.1f..8f) — a slider bound straight to a game's " +
                "own property. Drag the Fight window by its title, fold it, pull an edge.",
            style = "label.dim",
        )
    }

    Group("Docking", "The same moves a drag makes, from a button.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button(
                "Fight left",
                { windows.dockToScreen(FightWindow, DockSide.Left) },
                enabled = state.tuningOpen,
                style = "button.quiet",
            )
            Button(
                "Tree right",
                { windows.dockToScreen(NodeWindowTitle, DockSide.Right) },
                enabled = state.isOn(Exhibit.Nodes),
                style = "button.quiet",
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button(
                "Tab together",
                { windows.dockWith(NodeWindowTitle, FightWindow) },
                enabled = state.tuningOpen && state.isOn(Exhibit.Nodes),
                style = "button.quiet",
            )
            Button(
                "Float them",
                { windows.dockedWindows.toList().forEach(windows::undock) },
                enabled = windows.dockedWindows.isNotEmpty(),
                style = "button.quiet",
            )
        }
        Labelled("Docked now") { Text("${windows.dockedWindows.size}", style = "label") }
        Text("Neither has to be docked first; a window taken in brings its neighbour along.", style = "label.dim")
    }

    Group("Live plots", "A value a frame, over a ring that is written to rather than grown.") {
        val heat = rememberPlotBuffer(capacity = 180)
        val frames = remember(budget) { FloatArray(DebugFrameWindow) }
        var measured by remember { mutableStateOf(0) }

        // A sample a frame while the fight is running, and none at all while it is held. A graph
        // that takes a sample a frame writes state a frame, which is a screen that never stands
        // still — so "hold fire" is also what makes a still screenshot of this panel possible.
        val sampling = !state.holdFire
        LaunchedEffect(state, budget, sampling) {
            while (sampling) {
                withFrameNanos {
                    heat.add(state.heat)
                    measured = budget.recentFrameMillis(frames)
                }
            }
        }

        Plot(
            heat,
            Modifier.testTag(DebugPlotTag).fillMaxWidth().height(56f),
            range = 0f..1f,
            guides = listOf(0.8f),
            label = "heat",
        )
        Histogram(frames, Modifier.fillMaxWidth().height(56f), count = measured, label = "frame ms")
        Text("Hover either of them and the sample under the pointer is read out.", style = "label.dim")
    }

    Group("The interface's tree", "Not the game's tree: what the toolkit actually built.") {
        NodeTree(interfaceRoot, Modifier.testTag(DebugNodesTag).fillMaxWidth().height(240f))
        Text(
            "Type in the box to keep the rows that match. The counts beside a row go red as they " +
                "tick, so a widget costing a frame every frame is the one still glowing.",
            style = "label.dim",
        )
    }

    Group("The developer console", "One place to poke a running game from, with completion in it.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6f)) {
            Button("Open it", { console.open() }, style = "button.quiet")
            Button("Run help", { console.run("help") }, style = "button.quiet")
            Button("Overheat", { console.run("heat 0.95") }, style = "button.quiet")
        }
        Labelled("Commands defined") { Text("${console.commands.size}", style = "label") }
        Labelled("Lines logged") { Text("${console.lines.size}", style = "label") }
        Text("` opens it anywhere; Tab finishes a command or a callsign, Up brings back the last.", style = "label.dim")
    }

    Group("The frame budget", "What the interface cost, split three ways.") {
        Labelled("Overlay") { Toggle(budget.isOn, { budget.toggle() }) }
        val reading = budget.reading
        Labelled("Recompose") { Text(millis(reading.recomposeMillis), style = "label") }
        Labelled("Layout") { Text(millis(reading.layoutMillis), style = "label") }
        Labelled("Draw") { Text(millis(reading.drawMillis), style = "label") }
        Labelled("Worst frame") { Text(millis(reading.worstMillis), style = "label") }
    }

    Group("What this module draws", "The exhibits composegl-debug owns.") {
        Exhibit.entries.filter { it.module == Module.Debug }.forEach { ExhibitSwitch(state, it) }
    }
}

/** Two decimals and the unit, which is how a frame time is read. */
private fun millis(value: Float): String {
    val hundredths = (value * 100f).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} ms"
}

/** How many frames the section's bars cover. */
private const val DebugFrameWindow = 60

/** The titles the two windows are docked by. A window is known by what its title bar says. */
internal const val FightWindow = "Fight"

internal const val NodeWindowTitle = "UI tree"
