package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.game.Reticle
import dev.wildware.composegl.ui.game.rememberReticleState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Text

/** The landing page: what this is in one line, how to drive it, and a way into every section. */
@Composable
fun HomePage() {
    val state = LocalShowcase.current
    val open = LocalOpenLink.current
    Column(Modifier.fillMaxWidth().testTag("page-home"), verticalArrangement = Arrangement.spacedBy(16f)) {
        Text("ComposeGL", style = "label.title", textStyle = TextStyle(size = if (state.compact) 34f else 44f))
        Text(
            "Compose-style game UI, drawn inside your game's own OpenGL or WebGL frame.",
            Modifier.fillMaxWidth(),
            style = "label.heading",
        )
        Text(
            "Everything on this page, every panel, letter and animation, is ComposeGL drawing into one WebGL canvas " +
                "from Kotlin compiled to WebAssembly. There is no HTML here and no second window: the same code draws a LibGDX or OpenGL game's menus.",
            Modifier.fillMaxWidth(),
            style = "label.dim",
        )
        FlowRow(horizontalSpacing = 10f, verticalSpacing = 10f) {
            Button("Start the tour", { state.goTo(Section.Widgets) }, Modifier.testTag("start"), style = "button.primary")
            Button("Source on GitHub", { open(RepoUrl) }, style = "button.quiet")
            Button("Read the wiki", { open(WikiUrl) }, style = "button.quiet")
        }

        HeroHud()

        Text(
            "Drive it any way you like. Mouse or touch: click and drag. Keyboard: Tab and the arrows move, Enter presses, " +
                "Page Up and Page Down turn the page. Gamepad: press a button so the browser shows it the pad, then the d-pad moves, A presses and the bumpers turn the page.",
            Modifier.fillMaxWidth(),
            style = "label.dim",
        )

        FlowRow(Modifier.fillMaxWidth(), horizontalSpacing = 12f, verticalSpacing = 12f) {
            Section.entries.drop(1).forEach { section ->
                Button(
                    onClick = { state.goTo(section) },
                    modifier = Modifier.width(LocalCardWidth.current.coerceAtMost(300f)).testTag("tile-${section.tag}"),
                    style = "button.quiet",
                    contentAlignment = Alignment.CentreStart,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4f)) {
                        Text(section.title, style = "label.heading")
                        Text(section.blurb, style = "label.dim")
                    }
                }
            }
        }
    }
}

/** A little heads-up display that never stops, so the first screen moves. */
@Composable
private fun HeroHud() {
    var hull by remember { mutableFloatStateOf(0.9f) }
    val reticle = rememberReticleState()
    val clocks = LocalClocks.current
    val still = LocalShowcase.current.reduceMotion
    LaunchedEffect(still) {
        while (!still) {
            clocks.wait(Clock.Ui, 1400)
            hull = if (hull < 0.3f) 0.95f else hull - 0.22f
            reticle.hit(kill = hull < 0.3f)
        }
    }
    Box(Modifier.fillMaxWidth().height(150f).background(Ink, corner = 12f)) {
        Panel(Modifier.align(Alignment.BottomStart).padding(16f).width(250f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("HULL", style = "label.dim")
                    Text("${(hull * 100).toInt()}%")
                }
                Bar(hull, Modifier.fillMaxWidth(), pulseBelow = 0.3f, clock = Clock.Ui)
            }
        }
        Reticle(reticle, Modifier.align(Alignment.Centre), gap = 8f, arm = 14f, thickness = 3f)
    }
}
