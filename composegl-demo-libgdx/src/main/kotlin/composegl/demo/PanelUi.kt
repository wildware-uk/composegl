package composegl.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** What the in-world panel controls. The cube reads these every frame. */
class PanelState {
    var spinSpeed by mutableFloatStateOf(0.25f)
    var paused by mutableStateOf(false)
}

/**
 * The panel that lives on the quad in the scene. Ordinary Compose: it has no idea it is being
 * drawn onto a rotating rectangle three metres away.
 */
@Composable
fun PanelUi(state: PanelState, fontFamily: FontFamily) {
    MaterialTheme(typography = demoTypography(fontFamily)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF01B1F27)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Cube control", color = Color.White, style = MaterialTheme.typography.titleMedium)

            Text(
                "spin ${"%.2f".format(state.spinSpeed)}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.spinSpeed,
                onValueChange = { state.spinSpeed = it },
                valueRange = 0f..1f,
            )

            Button(onClick = { state.paused = !state.paused }) {
                Text(if (state.paused) "Resume" else "Pause")
            }
        }
    }
}
