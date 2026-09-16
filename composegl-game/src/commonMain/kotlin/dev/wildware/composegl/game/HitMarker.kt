package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.sqrt

/** What a hit was worth, which is the whole of what a hit marker says. */
enum class HitKind {

    /** A shot that landed. */
    Normal,

    /** A weak spot, a headshot, a backstab: the same hit, worth more. */
    Critical,

    /** The one that finished the target. */
    Kill,
}

/**
 * The hit marker's state: what was hit, and how many times.
 *
 * [hit] is a **call rather than a flag**, for the reason [ReticleState] gives: a flag has to be
 * turned off again, and the bug that always follows is two hits in the same breath leaving two
 * markers drawn on top of each other, each fading on its own, so the marker gets brighter the
 * faster the player shoots. Here there is one marker, and hitting again restarts it.
 */
class HitMarkerState(val clock: Clock = Clock.World) {

    /** How many hits have been marked. Watched by the widget, so a new hit restarts the marker. */
    var hits by mutableStateOf(0)
        private set

    /** What the last hit was worth. */
    var kind by mutableStateOf(HitKind.Normal)
        private set

    /** Marks a hit. A hit while the last one is still fading takes it over rather than adding to it. */
    fun hit(kind: HitKind = HitKind.Normal) {
        this.kind = kind
        hits++
    }
}

/** A [HitMarkerState] that lives as long as the screen it is on. */
@Composable
fun rememberHitMarkerState(clock: Clock = Clock.World): HitMarkerState =
    remember(clock) { HitMarkerState(clock) }

/**
 * The ticks that flash over the crosshair when a shot lands.
 *
 * Drawn **at the centre of whatever box it is given**, like [Reticle], so it is right at every
 * window size without the game working out where the middle is. It is a separate widget from the
 * crosshair rather than part of it because the two are not always in the same place: a game with
 * no crosshair at all still wants to say that a shot landed, and a marker over a reticle that is
 * being hidden should stay.
 *
 * Each kind has its own shape as well as its own colour, because a player reading a marker out of
 * the corner of an eye is reading the shape: four ticks for an ordinary hit, eight for a critical,
 * and four with a diamond in the middle for a kill. Each grows a little as it fades, which is what
 * makes it read as something that happened rather than as part of the crosshair.
 *
 * The skin names the colours: `"<style>"`, `"<style>.critical"` and `"<style>.kill"`.
 *
 * ```kotlin
 * val marker = rememberHitMarkerState()
 * HitMarker(marker, onHit = { kind -> audio.play(if (kind == HitKind.Kill) kill else tick) })
 * // and in the game
 * if (shot.landed) marker.hit(if (shot.killed) HitKind.Kill else HitKind.Normal)
 * ```
 *
 * A marker that has faded costs no frames: the animation unsubscribes when it arrives.
 *
 * @param gap how far a tick starts from the middle.
 * @param length how long a tick is.
 * @param thickness how thick a tick is. Whole numbers stay crisp.
 * @param grow how much bigger the marker is by the time it has gone. Just enough to be movement.
 * @param onHit the sound hook: called once, on the frame a marker starts, with what was hit. The
 *   toolkit has no idea how a game plays a sound, so it hands the moment over instead. It is
 *   called for a hit that takes over a marker still fading as much as for one on a clear screen —
 *   the only hit it does not call for is a second one in the *same frame* as the first, which is
 *   one marker and so one sound. A widget that comes back after being taken off the screen does
 *   not call it for whatever the state was last marked with either: that hit has already happened.
 */
@Composable
fun HitMarker(
    state: HitMarkerState,
    modifier: Modifier = Modifier,
    style: String = "hitmarker",
    gap: Float = 7f,
    length: Float = 8f,
    thickness: Float = 2f,
    grow: Float = 1.35f,
    onHit: (HitKind) -> Unit = {},
) {
    val clocks = LocalClocks.current
    val normal = rememberStyle(style)
    val critical = rememberStyle("$style.critical")
    val kill = rememberStyle("$style.kill")

    val marker = remember(clocks, state.clock) { Animatable(0f, FloatVectoriser, state.clock, clocks) }

    // Held in a box the effect reads, so a game that builds its handler inline — which is every
    // game — does not restart the animation every time it recomposes.
    val sound by rememberUpdatedState(onHit)

    // How many hits this widget has already marked. Remembered rather than read from the state,
    // because the state usually outlives the widget: a HUD toggled off and on comes back with the
    // count it left behind, and without this the effect would take the last hit for a new one and
    // flash a marker — and play a sound — for a shot that landed minutes ago.
    val seen = remember(state) { mutableStateOf(state.hits) }

    // Keyed on the count, so a hit while the last one is still fading cancels that animation and
    // starts again from full rather than adding a second marker to it.
    LaunchedEffect(state, state.hits) {
        if (state.hits == seen.value) return@LaunchedEffect
        seen.value = state.hits
        sound(state.kind)
        marker.snapTo(1f)
        marker.animateTo(0f, Tween(state.kind.millis, easing = Easings.EaseOut))
    }

    val colour = when (state.kind) {
        HitKind.Normal -> normal
        HitKind.Critical -> critical
        HitKind.Kill -> kill
    }.background.flatColour ?: Colour.Transparent

    val painter = remember(colour, state.kind, gap, length, thickness, grow, marker.value) {
        HitMarkerPainter(
            colour = colour,
            kind = state.kind,
            gap = gap,
            length = length,
            thickness = thickness,
            scale = 1f + (grow - 1f) * (1f - marker.value),
            alpha = marker.value,
        )
    }

    LeafLayout(modifier.fillMaxSize(), name = "hit marker", draw = painter.draw)
}

/** How long each kind stays up. A kill is the one worth looking at, so it is the one that waits. */
private val HitKind.millis: Int
    get() = when (this) {
        HitKind.Normal -> 260
        HitKind.Critical -> 380
        HitKind.Kill -> 520
    }

/**
 * The ticks, drawn round the middle of the box.
 *
 * Everything it needs is worked out at composition, so a frame is four quads — eight for a
 * critical — and the scratch array they are written into is made once.
 */
private class HitMarkerPainter(
    private val colour: Colour,
    private val kind: HitKind,
    private val gap: Float,
    private val length: Float,
    private val thickness: Float,
    private val scale: Float,
    private val alpha: Float,
) {

    private val quad = FloatArray(8)

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        if (alpha > 0f && thickness > 0f && length > 0f) {
            val x = (bounds.left + bounds.right) / 2f
            val y = (bounds.top + bounds.bottom) / 2f
            drawMarker(this, x, y)
        }
    }

    private fun drawMarker(canvas: UiCanvas, x: Float, y: Float) {
        val near = gap * scale
        val far = (gap + length) * scale
        val faded = colour.scaleAlpha(alpha)

        // The diagonals, leaning away from the middle. Every kind has these; they are what a hit
        // marker is.
        stroke(canvas, x - near, y - near, x - far, y - far, faded)
        stroke(canvas, x + near, y - near, x + far, y - far, faded)
        stroke(canvas, x - near, y + near, x - far, y + far, faded)
        stroke(canvas, x + near, y + near, x + far, y + far, faded)

        when (kind) {
            HitKind.Normal -> Unit

            // Four more between them: a star rather than a cross, which is the difference a player
            // sees before they have read either.
            HitKind.Critical -> {
                val axisNear = near * AxisShare
                val axisFar = far * AxisShare
                stroke(canvas, x, y - axisNear, x, y - axisFar, faded)
                stroke(canvas, x, y + axisNear, x, y + axisFar, faded)
                stroke(canvas, x - axisNear, y, x - axisFar, y, faded)
                stroke(canvas, x + axisNear, y, x + axisFar, y, faded)
            }

            // A filled diamond inside the cross: something closed, which is what a kill is.
            HitKind.Kill -> {
                val point = near * DiamondShare
                quad[0] = x; quad[1] = y - point
                quad[2] = x + point; quad[3] = y
                quad[4] = x; quad[5] = y + point
                quad[6] = x - point; quad[7] = y
                canvas.fan(quad, faded)
            }
        }
    }

    /** One tick, as the four corners of a quad, because a fan is what draws a line at an angle. */
    private fun stroke(canvas: UiCanvas, x1: Float, y1: Float, x2: Float, y2: Float, colour: Colour) {
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= 0f) return

        // Across the line rather than along it: half a thickness either side of both ends.
        val nx = -dy / distance * thickness / 2f
        val ny = dx / distance * thickness / 2f

        quad[0] = x1 + nx; quad[1] = y1 + ny
        quad[2] = x1 - nx; quad[3] = y1 - ny
        quad[4] = x2 - nx; quad[5] = y2 - ny
        quad[6] = x2 + nx; quad[7] = y2 + ny
        canvas.fan(quad, colour)
    }
}

/** How far out the critical's extra four ticks sit, next to the diagonals' own distances. */
private const val AxisShare = 1.15f

/** How big a kill's diamond is, as a share of the gap the ticks leave round the middle. */
private const val DiamondShare = 0.55f
