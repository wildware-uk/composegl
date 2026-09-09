package composegl.snake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import composegl.snake.game.SnakeSession

/**
 * The top three, on a sign above the board.
 *
 * This one is not part of the overlay: it is a `ComposeTexture` that the game draws into its own
 * scene with its own `SpriteBatch`, alongside the snake. Same Compose, a completely different way
 * in — the overlay is composited by ComposeGL, and this is composited by the game.
 */
@Composable
fun WallScoreboard(session: SnakeSession, fontFamily: FontFamily) {
    SnakeTheme(fontFamily) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xB3161B24))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "BEST",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            val top = session.highScores.take(3)
            if (top.isEmpty()) {
                Text(
                    "no runs yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                top.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${entry.score}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
