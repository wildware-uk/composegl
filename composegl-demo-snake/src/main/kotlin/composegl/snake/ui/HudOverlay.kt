package composegl.snake.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import composegl.snake.game.Screen
import composegl.snake.game.SnakeSession

/**
 * The panel beside the board while a game is on.
 *
 * It is deliberately quiet: the score only changes when the snake eats, so between mouthfuls this
 * costs nothing at all — no recomposition, no Skia, just the same texture blitted over the frame.
 * The counters at the bottom are there so you can watch that happen.
 */
@Composable
fun HudOverlay(session: SnakeSession) {
    Box(Modifier.fillMaxSize()) {
        Card(
            Modifier.align(Alignment.CenterStart).padding(28.dp).width(280.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    session.playerName.text.trim().ifEmpty { "Player" },
                    style = MaterialTheme.typography.titleMedium,
                )

                // animateIntAsState so the score rolls up rather than snapping. It settles, and
                // once it has, the HUD is static again.
                val shownScore by animateIntAsState(session.score, tween(350), label = "score")
                Column {
                    Text(
                        "Score",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "$shownScore",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                HorizontalDivider()

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Length", style = MaterialTheme.typography.bodyMedium)
                    Text("${session.length}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Best", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${session.highScores.maxOfOrNull { it.score } ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                AssistChip(onClick = {}, label = { Text(session.difficulty.label) })

                if (session.screen == Screen.Playing) {
                    OutlinedButton(
                        onClick = { session.pause() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Pause")
                    }
                }
            }
        }
    }
}
