package composegl.snake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import composegl.snake.game.Difficulty
import composegl.snake.game.SnakeSession
import kotlin.math.roundToInt

/**
 * The main menu: a name, a difficulty, a board size, a switch, and the high score table.
 *
 * Every control here is a stock Material 3 component doing what it does everywhere else. That is
 * the claim being demonstrated — none of it knows it is inside a game's framebuffer.
 */
@Composable
fun MenuScreen(session: SnakeSession) {
    Box(
        // Not quite opaque: the board keeps playing behind the menu, which is worth seeing.
        Modifier.fillMaxSize().background(Color(0xCC0B0E13)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(
                Modifier.width(400.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("SNAKE", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The board is OpenGL. Everything you can click is Compose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()

                    TextField(
                        value = session.playerName,
                        onValueChange = { session.playerName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Speed", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Difficulty.entries.forEach { level ->
                                FilterChip(
                                    selected = session.difficulty == level,
                                    onClick = { session.difficulty = level },
                                    label = { Text(level.label, maxLines = 1) },
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Board: ${session.boardWidth} squares wide",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = session.boardWidth.toFloat(),
                            onValueChange = { session.boardWidth = it.roundToInt() },
                            valueRange = 12f..40f,
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Grid lines", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = session.showGrid, onCheckedChange = { session.showGrid = it })
                    }

                    Button(
                        onClick = { session.startGame() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text("Play", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Arrows or WASD to steer  •  Space to pause",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HighScoreCard(session, Modifier.width(280.dp))
        }
    }
}

@Composable
private fun HighScoreCard(session: SnakeSession, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Best runs", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()

            if (session.highScores.isEmpty()) {
                Text(
                    "Nothing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(session.highScores) { index, entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${index + 1}. ${entry.name}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${entry.score}  ·  ${entry.difficulty.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
