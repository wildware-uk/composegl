package composegl.showcase.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import composegl.showcase.Ability
import composegl.showcase.TargetReadout
import kotlin.math.cos
import kotlin.math.sin

/**
 * A targeting reticle: a slowly counter-rotating pair of segmented rings, ticks, and a centre dot
 * that breathes.
 *
 * All of it is `Canvas`, which is Compose's ordinary drawing API — the same one a phone app would
 * use. It happens to be rasterising into a framebuffer the game owns.
 */
@Composable
fun Reticle(time: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(190.dp)) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)

        // Outer ring: four arcs with gaps, turning one way.
        rotate(time * 18f, centre) {
            repeat(4) { index ->
                drawArc(
                    color = Hud.Cyan.copy(alpha = 0.75f),
                    startAngle = index * 90f + 12f,
                    sweepAngle = 66f,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius * 0.92f, centre.y - radius * 0.92f),
                    size = Size(radius * 1.84f, radius * 1.84f),
                    style = Stroke(width = 2f),
                )
            }
        }

        // Inner ring: three arcs, turning the other way.
        rotate(-time * 28f, centre) {
            repeat(3) { index ->
                drawArc(
                    color = Hud.Cyan.copy(alpha = 0.4f),
                    startAngle = index * 120f + 20f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius * 0.66f, centre.y - radius * 0.66f),
                    size = Size(radius * 1.32f, radius * 1.32f),
                    style = Stroke(width = 1.5f),
                )
            }
        }

        // Ticks around the outside.
        repeat(36) { index ->
            val angle = Math.toRadians(index * 10.0).toFloat()
            val long = index % 9 == 0
            val inner = radius * if (long) 0.96f else 1.0f
            val outer = radius * if (long) 1.12f else 1.06f
            drawLine(
                color = Hud.Cyan.copy(alpha = if (long) 0.8f else 0.28f),
                start = centre + Offset(cos(angle) * inner, sin(angle) * inner),
                end = centre + Offset(cos(angle) * outer, sin(angle) * outer),
                strokeWidth = if (long) 2f else 1f,
            )
        }

        // Crosshair, with a gap in the middle.
        val gap = radius * 0.18f
        val arm = radius * 0.42f
        listOf(
            Offset(-1f, 0f) to Offset(-1f, 0f),
            Offset(1f, 0f) to Offset(1f, 0f),
            Offset(0f, -1f) to Offset(0f, -1f),
            Offset(0f, 1f) to Offset(0f, 1f),
        ).forEach { (direction, _) ->
            drawLine(
                color = Hud.Cyan,
                start = centre + Offset(direction.x * gap, direction.y * gap),
                end = centre + Offset(direction.x * arm, direction.y * arm),
                strokeWidth = 2f,
            )
        }

        // A centre dot that breathes.
        val breath = 0.65f + 0.35f * sin(time * 3.2f)
        drawCircle(Hud.Cyan.copy(alpha = 0.35f + 0.3f * breath), radius * 0.09f * breath, centre)
        drawCircle(Hud.Cyan, radius * 0.028f, centre)
    }
}

/**
 * A radar with a sweeping arm and blips for whatever the scene is carrying.
 *
 * The sweep is a `sweepGradient` rotated by the clock — a two-line effect in Compose that would be
 * a shader anywhere else.
 */
@Composable
fun Radar(time: Float, targets: List<TargetReadout>, modifier: Modifier = Modifier) {
    Canvas(modifier.size(150.dp)) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)

        drawCircle(Hud.Ink.copy(alpha = 0.55f), radius, centre)

        // Range rings.
        listOf(0.33f, 0.66f, 1f).forEach { fraction ->
            drawCircle(
                color = Hud.Cyan.copy(alpha = 0.22f),
                radius = radius * fraction,
                center = centre,
                style = Stroke(width = 1f),
            )
        }
        drawLine(Hud.Cyan.copy(alpha = 0.18f), centre.copy(x = 0f), centre.copy(x = size.width), strokeWidth = 1f)
        drawLine(Hud.Cyan.copy(alpha = 0.18f), centre.copy(y = 0f), centre.copy(y = size.height), strokeWidth = 1f)

        // The sweep: a gradient that fades behind the arm.
        rotate(time * 90f, centre) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0.0f to Hud.Cyan.copy(alpha = 0.38f),
                    0.12f to Hud.Cyan.copy(alpha = 0.06f),
                    0.35f to Color.Transparent,
                    1.0f to Color.Transparent,
                    center = centre,
                ),
                radius = radius,
                center = centre,
            )
            drawLine(
                color = Hud.Cyan,
                start = centre,
                end = centre + Offset(radius, 0f),
                strokeWidth = 1.5f,
            )
        }

        // Blips, placed by bearing and distance.
        targets.forEach { target ->
            val range = (target.distance / 12f).coerceIn(0f, 1f)
            val position = centre + Offset(
                cos(target.bearing) * radius * range,
                sin(target.bearing) * radius * range,
            )
            drawCircle(Hud.Amber.copy(alpha = 0.25f), 7f, position)
            drawCircle(Hud.Amber, 3f, position)
        }

        drawCircle(Hud.Cyan.copy(alpha = 0.5f), radius, centre, style = Stroke(width = 1.5f))
    }
}

/** An ability button: a cooldown arc that wipes round as it recharges. */
@Composable
fun AbilityButton(ability: Ability, modifier: Modifier = Modifier) {
    val glow by animateFloatAsState(
        targetValue = if (ability.ready) 1f else 0.35f,
        animationSpec = tween(250, easing = LinearEasing),
        label = "glow",
    )
    Box(modifier.size(62.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 3f
            val centre = Offset(size.width / 2f, size.height / 2f)

            drawCircle(Hud.Ink.copy(alpha = 0.7f), radius, centre)
            drawCircle(Hud.Cyan.copy(alpha = 0.25f * glow + 0.1f), radius, centre, style = Stroke(2f))

            if (!ability.ready) {
                // The unfilled part of the arc, sweeping clockwise from the top.
                drawArc(
                    color = Hud.Cyan.copy(alpha = 0.85f),
                    startAngle = -90f,
                    sweepAngle = 360f * ability.progress,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius, centre.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 3f),
                )
            } else {
                drawCircle(Hud.Cyan.copy(alpha = 0.10f * glow), radius * 0.86f, centre)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                ability.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (ability.ready) Hud.Cyan else Hud.CyanDim,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (ability.ready) ability.key else "%.1f".format(ability.remaining),
                style = MaterialTheme.typography.labelMedium,
                color = if (ability.ready) Color.White else Hud.CyanDim,
            )
        }
    }
}

/**
 * A target readout: shields that snap, and integrity with a trail that catches up a moment later.
 *
 * The trail is the reason games look expensive, and here it is one `animateFloatAsState` with a
 * delay on it.
 */
@Composable
fun TargetPanel(target: TargetReadout, modifier: Modifier = Modifier) {
    val trail by animateFloatAsState(
        targetValue = target.integrity,
        animationSpec = tween(durationMillis = 900, delayMillis = 220, easing = LinearEasing),
        label = "trail",
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.width(320.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                target.callsign,
                style = MaterialTheme.typography.labelLarge,
                color = Hud.Cyan,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "%.1f m".format(target.distance),
                style = MaterialTheme.typography.labelSmall,
                color = Hud.CyanDim,
            )
        }

        Canvas(Modifier.width(320.dp).height(18.dp)) {
            val barHeight = 6f
            // Shields on top, integrity beneath, both skewed like a fighter's readout.
            drawSegmentedBar(0f, barHeight, target.shield, Hud.Shield, trail = null)
            drawSegmentedBar(barHeight + 5f, barHeight, target.integrity, Hud.Integrity, trail = trail)
        }
    }
}

/**
 * One bar, with an optional trail drawn behind it in warning colour, and notches along its length.
 */
private fun DrawScope.drawSegmentedBar(top: Float, height: Float, value: Float, colour: Color, trail: Float?) {
    val width = size.width
    drawRect(Hud.Ink.copy(alpha = 0.65f), Offset(0f, top), Size(width, height))

    if (trail != null && trail > value) {
        drawRect(Hud.Danger.copy(alpha = 0.75f), Offset(0f, top), Size(width * trail, height))
    }
    drawRect(colour, Offset(0f, top), Size(width * value.coerceIn(0f, 1f), height))

    // Notches, so it reads as segments rather than a progress bar.
    var x = 0f
    while (x < width) {
        drawRect(Hud.Ink.copy(alpha = 0.8f), Offset(x, top), Size(2f, height))
        x += width / 24f
    }
    drawRect(colour.copy(alpha = 0.5f), Offset(0f, top), Size(width, height), style = Stroke(1f))
}

/** The player's own state: two arcs and a round of ammunition. */
@Composable
fun PlayerStatus(hull: Float, heat: Float, ammo: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 8f
            val centre = Offset(size.width / 2f, size.height / 2f)

            fun arc(fraction: Float, colour: Color, inset: Float, stroke: Float) {
                val r = radius - inset
                drawArc(
                    color = colour.copy(alpha = 0.18f),
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(centre.x - r, centre.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = colour,
                    startAngle = 140f,
                    sweepAngle = 260f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(centre.x - r, centre.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = stroke),
                )
            }

            arc(hull, Hud.Integrity, inset = 0f, stroke = 7f)
            arc(heat, if (heat > 0.75f) Hud.Danger else Hud.Amber, inset = 13f, stroke = 4f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$ammo",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text("ROUNDS", style = MaterialTheme.typography.labelSmall, color = Hud.CyanDim)
        }
    }
}

/** Thin frame lines and corner brackets, the cheapest way to make a screen look like a cockpit. */
@Composable
fun CornerFrame(time: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val margin = 26f
        val arm = 46f
        val pulse = 0.55f + 0.25f * sin(time * 1.6f)
        val colour = Hud.Cyan.copy(alpha = pulse)

        listOf(
            Offset(margin, margin) to listOf(Offset(arm, 0f), Offset(0f, arm)),
            Offset(size.width - margin, margin) to listOf(Offset(-arm, 0f), Offset(0f, arm)),
            Offset(margin, size.height - margin) to listOf(Offset(arm, 0f), Offset(0f, -arm)),
            Offset(size.width - margin, size.height - margin) to listOf(Offset(-arm, 0f), Offset(0f, -arm)),
        ).forEach { (corner, arms) ->
            arms.forEach { drawLine(colour, corner, corner + it, strokeWidth = 2f) }
        }

        // Faint edges joining the brackets.
        val edge = Hud.Cyan.copy(alpha = 0.12f)
        drawLine(edge, Offset(margin + arm, margin), Offset(size.width - margin - arm, margin))
        drawLine(edge, Offset(margin + arm, size.height - margin), Offset(size.width - margin - arm, size.height - margin))
    }
}

/** Scanlines and a vignette. Subtle, and the thing that stops it looking like a web page. */
@Composable
fun ScreenGrade(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.10f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += 3f
        }
        drawRect(
            brush = Brush.radialGradient(
                0.55f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.55f),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension * 0.85f,
            ),
        )
    }
}
