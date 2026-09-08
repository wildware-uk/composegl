package composegl.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import composegl.SurfaceStats

/** What the HUD shows about the game. In a real game this would be your view model. */
class DemoState {
    var clicks by mutableStateOf(0)
    var name by mutableStateOf(TextFieldValue("player one"))
    var difficulty by mutableStateOf("Normal")
    /** Sampled once a second, so the HUD stays genuinely static in between. */
    var stats by mutableStateOf(SurfaceStats())
}

@Composable
fun Hud(state: DemoState, fontFamily: FontFamily) {
    MaterialTheme(typography = demoTypography(fontFamily)) {
        Box(Modifier.fillMaxSize()) {
            ControlPanel(state, Modifier.align(Alignment.TopStart).padding(24.dp))
            StatsCorner(state.stats, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            Text(
                "Drag anywhere the HUD isn't to spin the cube.",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ControlPanel(state: DemoState, modifier: Modifier = Modifier) {
    Card(modifier.width(320.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ComposeGL", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { state.clicks++ }) { Text("Click me") }
                Text("${state.clicks}", style = MaterialTheme.typography.bodyMedium)
            }

            TextField(
                value = state.name,
                onValueChange = { state.name = it },
                label = { Text("Name") },
                singleLine = true,
            )

            DifficultyPicker(state)
        }
    }
}

@Composable
private fun DifficultyPicker(state: DemoState) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Difficulty: ${state.difficulty}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("Easy", "Normal", "Hard", "Nightmare").forEach { level ->
                DropdownMenuItem(
                    text = { Text(level) },
                    onClick = { state.difficulty = level; open = false },
                )
            }
        }
    }
}

/**
 * The claim, on screen: frames counts every game frame, renders counts the times Compose actually
 * redrew. Leave the HUD alone and the second number stops moving while the first races away.
 */
@Composable
private fun StatsCorner(stats: SurfaceStats, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xAA000000))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            StatLine("game frames", "${stats.frames}")
            StatLine("compose renders", "${stats.composeRenders}")
            StatLine("last render", "${stats.lastRenderNanos / 1000} us")
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.width(200.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
