package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.game.Bar
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle

@Composable
fun SettingsPage() {
    val state = LocalShowcase.current
    Page(Section.Settings, "Both settings apply to the whole showcase, live. A skin is one JSON file; switching is swapping one object.") {
        Card("Skin", "High contrast is a skin the toolkit ships: black, white and heavy edges.") {
            Stepper(SkinChoice.entries, state.skin, { state.skin = it }, label = { it.title }, modifier = Modifier.testTag("skin"))
            FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
                SkinChoice.entries.forEach { choice ->
                    Button(choice.title, { state.skin = choice }, Modifier.testTag("skin-${choice.name}"), style = if (state.skin == choice) "button.primary" else "button.quiet")
                }
            }
        }
        Card("Text size", "Only the words grow. The layout keeps its shape, so nothing falls off the screen.") {
            Stepper(ShowcaseState.TextScales, state.textScale, { state.textScale = it }, label = { "${(it * 100).toInt()}%" }, modifier = Modifier.testTag("text-scale"))
            FlowRow(horizontalSpacing = 8f, verticalSpacing = 8f) {
                ShowcaseState.TextScales.forEach { scale ->
                    Button("${(scale * 100).toInt()}%", { state.textScale = scale }, Modifier.testTag("scale-${(scale * 100).toInt()}"), style = if (state.textScale == scale) "button.primary" else "button.quiet")
                }
            }
        }
        Card("Motion", "For anybody who finds constant movement hard to read past.") {
            Toggle(state.reduceMotion, { state.reduceMotion = it }, label = "Reduce motion", modifier = Modifier.testTag("reduce-motion"))
            Text("Stops the looping animations. Anything you start yourself still plays.", Modifier.fillMaxWidth(), style = "label.dim")
        }
        Card("Preview") {
            var subtitles by remember { mutableStateOf(true) }
            var brightness by remember { mutableFloatStateOf(0.5f) }
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
                    Text("OPTIONS", style = "label.heading")
                    Checkbox(subtitles, { subtitles = it }, label = "Subtitles")
                    Row(horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
                        Text("Brightness")
                        Slider(brightness, { brightness = it }, length = 140f)
                    }
                    Bar(brightness, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                        Button("Apply", {}, style = "button.primary")
                        Button("Cancel", {})
                    }
                }
            }
        }
    }
}
