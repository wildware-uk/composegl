package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.debug.FrameBudgetOverlay
import dev.wildware.composegl.ui.graphics.BlendMode
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.blend
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

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
