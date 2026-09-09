package composegl.showcase.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import composegl.showcase.Exhibit
import composegl.showcase.ShowcaseState
import composegl.showcase.TargetReadout
import kotlin.math.roundToInt

/**
 * The whole interface, over the game's own 3D frame.
 *
 * Every exhibit here is ordinary Compose. What makes it a game interface rather than an app is
 * where it is drawn — inside the framebuffer the scene was rendered into, so it composites with
 * the particles and the world instead of sitting in a window above them.
 */
@Composable
fun ShowcaseUi(state: ShowcaseState, fontFamily: FontFamily) {
    ShowcaseTheme(fontFamily) {
        Box(Modifier.fillMaxSize()) {

            if (state.isOn(Exhibit.Tracking)) {
                state.targets.forEach { TrackingPanel(it) }
            }

            if (state.isOn(Exhibit.Damage)) {
                DamageNumbers(state)
            }

            if (state.isOn(Exhibit.Hud)) {
                CornerFrame(state.time)

                Reticle(state.time, Modifier.align(Alignment.Center))

                Radar(
                    state.time,
                    state.targets,
                    Modifier.align(Alignment.BottomStart).padding(start = 44.dp, bottom = 44.dp),
                )

                PlayerStatus(
                    state.hull,
                    state.heat,
                    state.ammo,
                    Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 34.dp),
                )

                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.abilities.forEach { AbilityButton(it) }
                }

                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 42.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    state.targets.take(2).forEach { TargetPanel(it) }
                }

                ScreenGrade()
            }

            ControlPanel(state, Modifier.align(Alignment.TopStart).padding(40.dp))
        }
    }
}

/**
 * A panel pinned to a drone out in the scene.
 *
 * The app projects the drone's world position to screen pixels once a frame; this places itself
 * there and scales with distance, so it behaves like part of the world rather than part of a
 * window.
 */
@Composable
private fun TrackingPanel(target: TargetReadout) {
    if (!target.onScreen) return

    // Nearer things are bigger, but not without limit — a readout you cannot read is decoration.
    val scale = (7f / target.distance.coerceAtLeast(2f)).coerceIn(0.55f, 1.15f)
    val fade = ((14f - target.distance) / 8f).coerceIn(0.25f, 1f)

    Box(
        Modifier
            .offset { IntOffset(target.screenX.roundToInt(), target.screenY.roundToInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = fade
                // Grow from the point it is pinned to, not from its own middle.
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        Column(
            Modifier
                .offset(x = 18.dp, y = (-14).dp)
                // Fixed, so a longer readout cannot clip mid-character.
                .width(148.dp)
                .background(Hud.Panel)
                .border(1.dp, Hud.Line)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                target.callsign,
                style = MaterialTheme.typography.labelMedium,
                color = Hud.Cyan,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "SHD ${(target.shield * 100).roundToInt()}%   HUL ${(target.integrity * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                "%.1f m".format(target.distance),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Hud.CyanDim,
            )
        }

        // A leader line back to the thing itself.
        Canvas(Modifier.size(1.dp)) {
            drawLine(
                color = Hud.Cyan.copy(alpha = 0.7f),
                start = Offset(0f, 0f),
                end = Offset(46f, -34f),
                strokeWidth = 1.5f,
            )
            drawCircle(Hud.Cyan, 3f, Offset(0f, 0f))
        }
    }
}

/** Numbers that float off whatever just took a hit, spawned at a world position. */
@Composable
private fun DamageNumbers(state: ShowcaseState) {
    state.damageNumbers.forEach { number ->
        if (!number.visible) return@forEach
        val progress = (number.age / number.lifetime).coerceIn(0f, 1f)
        val rise = -60f * progress
        val fade = (1f - progress * progress).coerceIn(0f, 1f)
        val pop = if (progress < 0.12f) 0.6f + progress / 0.12f * 0.55f else 1.15f - progress * 0.15f

        Text(
            text = if (number.critical) "${number.amount}!" else "${number.amount}",
            style = if (number.critical) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
            color = if (number.critical) Hud.Amber else Color.White,
            modifier = Modifier
                .offset {
                    IntOffset(number.screenX.roundToInt(), (number.screenY + rise).roundToInt())
                }
                .graphicsLayer {
                    alpha = fade
                    scaleX = pop
                    scaleY = pop
                },
        )
    }
}

/** What you are looking at, and switches to turn each piece off so you can see the difference. */
@Composable
private fun ControlPanel(state: ShowcaseState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(330.dp)
            .background(Hud.Panel)
            .border(1.dp, Hud.Line)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "COMPOSEGL SHOWCASE",
            style = MaterialTheme.typography.labelLarge,
            color = Hud.Cyan,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "The scene is OpenGL. Everything drawn over it is Compose.",
            style = MaterialTheme.typography.labelSmall,
            color = Hud.CyanDim,
        )

        Exhibit.entries.forEach { exhibit ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.isOn(exhibit),
                    onCheckedChange = { state.toggle(exhibit) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Hud.Ink,
                        checkedTrackColor = Hud.Cyan,
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.75f },
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        exhibit.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    Text(
                        exhibit.blurb,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Hud.CyanDim,
                    )
                }
            }
        }

        Stats(state)
    }
}

@Composable
private fun Stats(state: ShowcaseState) {
    val renders by animateFloatAsState(
        state.composeRenders.toFloat(),
        tween(400),
        label = "renders",
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatRow("game frames", "${state.gameFrames}")
        StatRow("compose renders", "${renders.roundToInt()}")
        StatRow("last render", "${state.lastRenderMicros} us")
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hud.CyanDim)
        Text(value, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
