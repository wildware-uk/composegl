package composegl.snake.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import composegl.snake.game.SnakeSession

/** The end of a run: what you scored, whether it counted, and how to go again. */
@Composable
fun GameOverScreen(session: SnakeSession) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC05070B)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(380.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Game over", style = MaterialTheme.typography.headlineMedium)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${session.score}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (session.lastRunWasHighScore) {
                        // The one thing on screen that keeps animating, so the difference between
                        // a still HUD and a busy one is visible in the counters.
                        val transition = rememberInfiniteTransition(label = "badge")
                        val pulse by transition.animateFloat(
                            initialValue = 0.55f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "pulse",
                        )
                        Badge(Modifier.alpha(pulse)) { Text("High score") }
                    }
                }

                HorizontalDivider()

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Length reached", style = MaterialTheme.typography.bodyMedium)
                    Text("${session.length}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed", style = MaterialTheme.typography.bodyMedium)
                    Text(session.difficulty.label, style = MaterialTheme.typography.bodyMedium)
                }

                Button(onClick = { session.startGame() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Play again")
                }
                TextButton(onClick = { session.toMenu() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Main menu")
                }
            }
        }
    }
}
