package composegl.snake.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import composegl.snake.game.Screen
import composegl.snake.game.SnakeSession

/**
 * The whole interface: menu, HUD, pause and game over, drawn by Compose inside the game's own
 * OpenGL frame.
 *
 * Which screen is showing is ordinary Compose state, so switching screens is a recomposition
 * rather than anything ComposeGL has to know about.
 */
@Composable
fun SnakeUi(session: SnakeSession, fontFamily: FontFamily) {
    SnakeTheme(fontFamily) {
        val focusManager = LocalFocusManager.current

        // While the snake is moving the keyboard belongs to the game, so nothing may hold focus —
        // otherwise the arrow keys would walk the HUD's buttons instead of turning the snake.
        LaunchedEffect(session.screen) {
            if (session.screen == Screen.Playing) focusManager.clearFocus(force = true)
        }

        Box(Modifier.fillMaxSize()) {
            // The HUD stays mounted behind the dialogs, so the score does not vanish while paused.
            AnimatedVisibility(
                visible = session.screen != Screen.Menu,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
            ) {
                HudOverlay(session)
            }

            AnimatedVisibility(
                visible = session.screen == Screen.Menu,
                enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.96f),
                exit = fadeOut(tween(150)),
            ) {
                MenuScreen(session)
            }

            AnimatedVisibility(
                visible = session.screen == Screen.Paused,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
                PauseScreen(session)
            }

            AnimatedVisibility(
                visible = session.screen == Screen.GameOver,
                enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.9f),
                exit = fadeOut(tween(150)),
            ) {
                GameOverScreen(session)
            }
        }
    }
}
