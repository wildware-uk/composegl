package dev.wildware.composegl.snake.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import dev.wildware.composegl.snake.game.Difficulty
import dev.wildware.composegl.snake.game.Screen
import dev.wildware.composegl.snake.game.SnakeSession
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.animation.animateFloatAsState
import dev.wildware.composegl.ui.backend.Clipboard
import dev.wildware.composegl.ui.backend.SoftKeyboard
import dev.wildware.composegl.ui.backend.TextInput
import dev.wildware.composegl.ui.debug.FrameBudget
import dev.wildware.composegl.ui.debug.FrameBudgetOverlay
import dev.wildware.composegl.ui.input.InputSource
import dev.wildware.composegl.ui.input.InputSourceTracker
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.skin.ProvideSkin
import dev.wildware.composegl.ui.skin.Skin
import dev.wildware.composegl.ui.text.FontProvider
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.LazyColumn
import dev.wildware.composegl.ui.widget.LocalClipboard
import dev.wildware.composegl.ui.widget.LocalFonts
import dev.wildware.composegl.ui.widget.LocalInputSource
import dev.wildware.composegl.ui.widget.LocalSoftKeyboard
import dev.wildware.composegl.ui.widget.LocalTextInput
import dev.wildware.composegl.ui.widget.Panel
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.composegl.ui.widget.Toggle
import kotlin.math.roundToInt

/**
 * The whole interface: menu, HUD, pause and game over, drawn over the game's own frame.
 *
 * Which screen is showing is ordinary Compose state, so changing screens is a recomposition rather
 * than anything the toolkit has to know about. Nothing here names a colour: every widget asks the
 * skin for a style, and the skin is `ui/snake.skin.json`, watched while the game runs.
 */
@Composable
fun SnakeUi(
    session: SnakeSession,
    fonts: FontProvider,
    skin: Skin,
    clipboard: Clipboard = Clipboard.None,
    softKeyboard: SoftKeyboard = SoftKeyboard.None,
    textInput: TextInput = TextInput.None,
    source: InputSourceTracker = InputSourceTracker(),
    budget: FrameBudget = FrameBudget().also { it.isOn = false },
) {
    CompositionLocalProvider(
        LocalFonts provides fonts,
        LocalClipboard provides clipboard,
        // On a desktop this is a no-op; on a phone it is what puts the keyboard up when the name
        // field takes focus, and takes it away again when the field loses it.
        LocalSoftKeyboard provides softKeyboard,
        // The phone's own keyboard driving the name field, rather than one character per key. It
        // is what makes autocorrect, swipe typing and a Japanese keyboard work at all.
        LocalTextInput provides textInput,
        LocalInputSource provides source,
    ) {
        ProvideSkin(skin) {
            Box(Modifier.fillMaxSize()) {
                // The HUD stays mounted behind the dialogues, so the score does not vanish while
                // the game is paused.
                if (session.screen != Screen.Menu) Hud(session)

                when (session.screen) {
                    Screen.Menu -> MenuScreen(session)
                    Screen.Paused -> PauseScreen(session)
                    Screen.GameOver -> GameOverScreen(session)
                    Screen.Playing -> Unit
                }

                // F3, in the corner every game puts it in.
                if (budget.isOn) {
                    FrameBudgetOverlay(
                        budget,
                        Modifier.align(Alignment.BottomEnd).padding(right = 28f, bottom = 28f),
                    )
                }
            }
        }
    }
}

/** The panel beside the board while a game is on. Still between mouthfuls, which is the point. */
@Composable
private fun Hud(session: SnakeSession) {
    Panel(
        Modifier.align(Alignment.CentreStart).padding(left = 28f).width(260f),
        style = "panel",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
            Text(session.playerName.trim().ifEmpty { "Player" }, style = "label.title")

            Column(verticalArrangement = Arrangement.spacedBy(2f)) {
                Text("SCORE", style = "label.dim")
                // The score rolls up rather than snapping, and once it has arrived the HUD is
                // static again — no animation left subscribed to anything.
                val shown by animateFloatAsState(session.score.toFloat(), Tween(350, easing = Easings.EaseOut))
                Text("${shown.roundToInt()}", style = "label.score")
            }

            StatRow("Length", "${session.length}")
            StatRow("Best", "${session.highScores.maxOfOrNull { it.score } ?: 0}")
            StatRow("Speed", session.difficulty.label)

            if (session.screen == Screen.Playing) {
                Button("PAUSE", { session.pause() }, Modifier.fillMaxWidth(), style = "button.quiet")
            }
            // Named for whatever is in the player's hands, because a phone has no arrow keys and
            // a hint about keys nobody can press is worse than no hint.
            Text(controlHint(LocalInputSource.current.current), style = "label.dim")
        }
    }
}

private fun controlHint(source: InputSource): String = when (source) {
    InputSource.Touch -> "Swipe to steer  ·  PAUSE to stop"
    InputSource.Gamepad -> "D-pad steers  ·  Start pauses"
    InputSource.Mouse, InputSource.Keyboard -> "Arrows or WASD  ·  Space pauses"
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = "label.dim")
        Text(value, style = "label")
    }
}

/** The main menu: a name, a speed, a board size, a toggle, and the table of best runs. */
@Composable
private fun MenuScreen(session: SnakeSession) {
    Scrim {
        Row(horizontalArrangement = Arrangement.spacedBy(20f)) {
            Panel(Modifier.width(400f)) {
                // Scrolling, because on a phone the keyboard takes two thirds of the screen and
                // this menu is taller than what is left. Focus scrolls a field into view by itself,
                // so tapping NAME brings the field up out from under the keyboard.
                ScrollArea {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16f)) {
                        Text("SNAKE", style = "label.display")
                        Text("The board is OpenGL. Everything you can click is the toolkit.", style = "label.dim")

                        Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                            Text("NAME", style = "label.dim")
                            TextField(
                                value = session.playerName,
                                onValueChange = { session.playerName = it },
                                modifier = Modifier.fillMaxWidth(),
                                maxLength = 16,
                                onSubmit = { session.startGame() },
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                            Text("SPEED", style = "label.dim")
                            Row(horizontalArrangement = Arrangement.spacedBy(8f)) {
                                Difficulty.entries.forEach { level ->
                                    Button(
                                        level.label.uppercase(),
                                        { session.difficulty = level },
                                        style = if (session.difficulty == level) "chip.chosen" else "chip",
                                        initialFocus = level == session.difficulty,
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6f)) {
                            Text("BOARD: ${session.boardWidth} SQUARES WIDE", style = "label.dim")
                            Slider(
                                value = session.boardWidth.toFloat(),
                                onValueChange = { session.boardWidth = it.roundToInt() },
                                modifier = Modifier.fillMaxWidth(),
                                range = 12f..40f,
                                step = 1f,
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = VerticalAlignment.Centre,
                        ) {
                            Text("Grid lines", style = "label")
                            Toggle(session.showGrid, { session.showGrid = it })
                        }

                        Button("PLAY", { session.startGame() }, Modifier.fillMaxWidth().height(46f))
                    }
                }
            }

            BestRuns(session, Modifier.width(260f))
        }
    }
}

/** Paused: somewhere to change your mind, over a board that has stopped. */
@Composable
private fun PauseScreen(session: SnakeSession) {
    Scrim {
        Panel(Modifier.width(320f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
                Text("PAUSED", style = "label.title")
                Text("Score ${session.score}  ·  length ${session.length}", style = "label.dim")

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = VerticalAlignment.Centre,
                ) {
                    // Changeable mid-game, so you can watch OpenGL react to a toggle.
                    Text("Grid lines", style = "label")
                    Toggle(session.showGrid, { session.showGrid = it })
                }

                Button("RESUME", { session.resume() }, Modifier.fillMaxWidth(), initialFocus = true)
                Button("RESTART", { session.startGame() }, Modifier.fillMaxWidth(), style = "button.quiet")
                Button("MAIN MENU", { session.toMenu() }, Modifier.fillMaxWidth(), style = "button.quiet")
            }
        }
    }
}

/** The end of a run: what you scored, whether it counted, and how to go again. */
@Composable
private fun GameOverScreen(session: SnakeSession) {
    Scrim {
        Panel(Modifier.width(360f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14f)) {
                Text("GAME OVER", style = "label.title")

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12f),
                    verticalAlignment = VerticalAlignment.Centre,
                ) {
                    Text("${session.score}", style = "label.score")
                    if (session.lastRunWasHighScore) {
                        // The one thing on screen still moving, so "settled costs nothing" is
                        // something you can watch rather than something the README claims.
                        val pulse by animateFloatAsState(1f, Tween(700, easing = Easings.EaseOut))
                        Box(Modifier.alpha(pulse).styled("badge")) {
                            Text("BEST RUN", style = "label.badge")
                        }
                    }
                }

                StatRow("Length reached", "${session.length}")
                StatRow("Speed", session.difficulty.label)

                Button("PLAY AGAIN", { session.startGame() }, Modifier.fillMaxWidth(), initialFocus = true)
                Button("MAIN MENU", { session.toMenu() }, Modifier.fillMaxWidth(), style = "button.quiet")
            }
        }
    }
}

/** The table, which is a lazy list because a game with a hundred runs should not build a hundred rows. */
@Composable
private fun BestRuns(session: SnakeSession, modifier: Modifier = Modifier) {
    Panel(modifier, style = "panel.quiet") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10f)) {
            Text("BEST RUNS", style = "label.dim")
            if (session.highScores.isEmpty()) {
                Text("Nothing yet.", style = "label.dim")
            } else {
                LazyColumn(session.highScores.size, Modifier.fillMaxWidth().height(240f), spacing = 6f) { index ->
                    val entry = session.highScores[index]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${entry.name}", style = "label")
                        Text("${entry.score}  ·  ${entry.difficulty.label}", style = "label.dim")
                    }
                }
            }
        }
    }
}

/** A wash over the board, dark enough to read on and light enough that the game shows through. */
@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().styled("scrim"),
        contentAlignment = Alignment.Centre,
        content = content,
    )
}
