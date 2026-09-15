package dev.wildware.composegl.demo.previews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.game.Bar
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.preview.Preview
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text

/**
 * Previews of the example's widgets, through the toolkit's default skin.
 *
 * `./gradlew :composegl-demo:renderPreviews` writes each of these to `build/previews`, with no
 * demo launched and nothing clicked. Adding a picture is adding a function here.
 */

@Preview(width = 200, height = 80, name = "button")
@Composable
fun ButtonPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Button("PLAY", onClick = {})
    }
}

@Preview(width = 320, height = 200, name = "pause-menu", background = 0xFF08090C)
@Composable
fun PauseMenuPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Panel(Modifier.width(220f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10f),
                horizontalAlignment = HorizontalAlignment.Centre,
            ) {
                Text("PAUSED")
                Button("RESUME", onClick = {})
                Button("OPTIONS", onClick = {})
                Button("QUIT", onClick = {})
            }
        }
    }
}

@Preview(width = 320, height = 150, name = "settings", background = 0xFF08090C)
@Composable
fun SettingsPreview() {
    var music by remember { mutableStateOf(0.7f) }
    var subtitles by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Panel(Modifier.width(280f)) {
            Column(verticalArrangement = Arrangement.spacedBy(12f)) {
                Row(verticalAlignment = VerticalAlignment.Centre, horizontalArrangement = Arrangement.spacedBy(12f)) {
                    Text("MUSIC")
                    Slider(music, onValueChange = { music = it }, modifier = Modifier.width(160f))
                }
                Row(verticalAlignment = VerticalAlignment.Centre, horizontalArrangement = Arrangement.spacedBy(12f)) {
                    Checkbox(subtitles, onCheckedChange = { subtitles = it })
                    Text("SUBTITLES")
                }
            }
        }
    }
}

@Preview(width = 260, height = 60, name = "health-bar", background = 0xFF08090C)
@Composable
fun HealthBarPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Centre) {
        Bar(0.65f, length = 220f)
    }
}
