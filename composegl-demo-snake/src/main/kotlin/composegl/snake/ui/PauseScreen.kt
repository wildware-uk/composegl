package composegl.snake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import composegl.snake.game.SnakeSession

/** Paused: a scrim over the running board, and somewhere to change your mind. */
@Composable
fun PauseScreen(session: SnakeSession) {
    Box(
        Modifier.fillMaxSize().background(Color(0xAA05070B)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(340.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Paused", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Score ${session.score}  ·  length ${session.length}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(session)

                Button(onClick = { session.resume() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume")
                }
                OutlinedButton(onClick = { session.startGame() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restart")
                }
                TextButton(onClick = { session.toMenu() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Main menu")
                }
            }
        }
    }
}

/** Grid lines are changeable mid-game, so you can watch OpenGL react to a Compose switch. */
@Composable
private fun Row(session: SnakeSession) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Grid lines", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = session.showGrid, onCheckedChange = { session.showGrid = it })
    }
}
