package composegl.smoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Run it with `./gradlew :composegl-smoke-lwjgl3:run`.
 *
 * A Compose UI in a GLFW window, with no game engine involved: click the button, type in the
 * field. If this works, the core/adapter seam is real.
 */
fun main() {
    SmokeApp(800, 600).use { app ->
        app.run {
            var clicks by remember { mutableIntStateOf(0) }
            var text by remember { mutableStateOf(TextFieldValue("")) }
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0E12))) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No engine here.", color = Color.White)
                    Button(onClick = { clicks++ }) { Text("Clicked $clicks times") }
                    TextField(text, { text = it }, singleLine = true)
                }
            }
        }
    }
}
