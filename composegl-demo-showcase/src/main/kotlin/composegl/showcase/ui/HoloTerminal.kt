package composegl.showcase.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import composegl.showcase.ShowcaseState
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The hologram standing on the pedestal in the middle of the scene.
 *
 * This one is not part of the overlay. It is a `ComposeTexture` painted onto a panel in the world,
 * drawn by the game with additive blending, so it reads as light rather than as a surface — which
 * is exactly how a hologram is done in a real game, except that the contents are Compose.
 *
 * It is drawn on black on purpose: with additive blending, black is transparent.
 */
@Composable
fun HoloTerminal(state: ShowcaseState, fontFamily: FontFamily) {
    ShowcaseTheme(fontFamily) {
        Box(Modifier.fillMaxSize()) {
            // The scan bar that crawls up a hologram.
            Canvas(Modifier.fillMaxSize()) {
                val y = (sin(state.time * 0.9f) * 0.5f + 0.5f) * size.height
                drawRect(
                    color = Hud.Cyan.copy(alpha = 0.10f),
                    topLeft = Offset(0f, y - 22f),
                    size = Size(size.width, 44f),
                )
                var line = 0f
                while (line < size.height) {
                    drawLine(
                        color = Hud.Cyan.copy(alpha = 0.06f),
                        start = Offset(0f, line),
                        end = Offset(size.width, line),
                        strokeWidth = 1f,
                    )
                    line += 4f
                }
            }

            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "ORBITAL RELAY",
                    style = MaterialTheme.typography.titleSmall,
                    color = Hud.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    typewriter(BODY, state.time),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Hud.Shield.copy(alpha = 0.9f),
                )

                Spacer()

                state.targets.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            target.callsign,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Hud.Cyan,
                        )
                        Text(
                            "${(target.integrity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color.White,
                        )
                    }
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        drawRect(Hud.Cyan.copy(alpha = 0.15f), size = size)
                        drawRect(
                            color = Hud.Shield,
                            size = Size(size.width * target.integrity.coerceIn(0f, 1f), size.height),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Spacer() = Box(Modifier.height(6.dp))

/**
 * Reveals [text] a character at a time, then holds, then starts again — the terminal effect, in
 * one line of arithmetic rather than an animation graph.
 */
private fun typewriter(text: String, time: Float): String {
    val charactersPerSecond = 22f
    val cycle = text.length / charactersPerSecond + 3.5f
    val position = (time % cycle) * charactersPerSecond
    return text.take(position.toInt().coerceIn(0, text.length))
}

private const val BODY =
    "Uplink stable. Three contacts in the ring, none hostile.\n" +
        "Reactor nominal. Shield lattice holding at 94 percent."
