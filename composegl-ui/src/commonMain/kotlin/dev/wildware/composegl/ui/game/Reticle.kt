package dev.wildware.composegl.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.Animatable
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Easings
import dev.wildware.composegl.ui.animation.FloatVectoriser
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.animation.Tween
import dev.wildware.composegl.ui.draw.RectCache
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.skin.ResolvedStyle
import dev.wildware.composegl.ui.skin.SkinDrawable
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.sqrt

/**
 * What the crosshair is being told, by the game that owns it.
 *
 * A reticle is a readout, not a control: the game writes [spread] as the player moves and fires,
 * says [hostile] when what is under it can be shot at, and calls [hit] when a shot landed. The
 * widget turns all of that into something a player can read without looking at it.
 *
 * [hit] is deliberately a **call rather than a flag**. A flag has to be turned off again, and the
 * bug that always follows is two hits in the same breath leaving two markers drawn on top of each
 * other, each fading on its own — the marker gets brighter the faster the player shoots, which is
 * backwards. Here there is one marker: hitting again restarts it.
 */
class ReticleState(val clock: Clock = Clock.World) {

    /**
     * How wide the crosshair is opened, from 0 for a standing-still shot to 1 for the worst the
     * gun does. The widget animates towards whatever is written here, so a game can set it in
     * steps — 0.2 walking, 0.6 sprinting, 1 the moment a shot goes off — without it snapping.
     */
    var spread by mutableStateOf(0f)

    /** Whether what is under the crosshair can be shot at. Changes its colour and nothing else. */
    var hostile by mutableStateOf(false)

    /** How many hits have been marked. Watched by the widget so a new hit restarts the marker. */
    var hits by mutableStateOf(0)
        private set

    /** Whether the last hit was the one that finished the target. */
    var killed by mutableStateOf(false)
        private set

    /**
     * Marks a hit: the four ticks round the crosshair flash and fade.
     *
     * @param kill whether that hit killed the target, which the skin draws in its own colour.
     */
    fun hit(kill: Boolean = false) {
        killed = kill
        hits++
    }
}

/** A [ReticleState] that lives as long as the screen it is on. */
@Composable
fun rememberReticleState(clock: Clock = Clock.World): ReticleState =
    remember(clock) { ReticleState(clock) }

/**
 * The crosshair.
 *
 * Four arms round a gap, drawn **at the centre of whatever box it is given** — so it is right at
 * every window size and every aspect ratio without the game working out where the middle is.
 * Firing and moving open it up, landing a shot flashes four ticks over it, and a target that can
 * be shot at turns it the skin's hostile colour.
 *
 * The spread animates. A crosshair that snaps open is read as a glitch rather than as recoil, and
 * closing is slower than opening for the same reason a gun settles slower than it kicks.
 *
 * A still crosshair over nothing costs no frames: both animations unsubscribe when they arrive.
 *
 * The skin names the colours: `"<style>"` for the arms, `"<style>.hostile"`, `"<style>.hit"` and
 * `"<style>.kill"`.
 *
 * ```kotlin
 * val reticle = rememberReticleState()
 * Reticle(reticle)
 * // and in the game
 * reticle.spread = if (player.sprinting) 1f else 0.2f
 * reticle.hostile = aim.target?.isEnemy == true
 * if (shot.landed) reticle.hit(kill = shot.killed)
 * ```
 *
 * @param gap how far the arms start from the middle when the crosshair is closed.
 * @param arm how long each arm is.
 * @param thickness how thick an arm is. Whole numbers stay crisp.
 * @param spreadDistance how much further out the arms go at full [ReticleState.spread].
 * @param dot how big the centre dot is, or zero for no dot. A dot is worth having on a slow gun
 *   and in the way on a fast one, so it is the game's choice rather than the skin's.
 */
@Composable
fun Reticle(
    state: ReticleState,
    modifier: Modifier = Modifier,
    style: String = "reticle",
    gap: Float = 6f,
    arm: Float = 10f,
    thickness: Float = 2f,
    spreadDistance: Float = 18f,
    dot: Float = 0f,
) {
    val clocks = LocalClocks.current
    val normal = rememberStyle(style)
    val hostile = rememberStyle("$style.hostile")
    val hitStyle = rememberStyle("$style.hit")
    val killStyle = rememberStyle("$style.kill")

    val opening = remember(clocks, state.clock) { Animatable(0f, FloatVectoriser, state.clock, clocks) }
    val marker = remember(clocks, state.clock) { Animatable(0f, FloatVectoriser, state.clock, clocks) }

    // A gun kicks faster than it settles, so opening is the quick half.
    LaunchedEffect(state, state.spread) {
        val target = state.spread.coerceIn(0f, 1f)
        val millis = if (target > opening.value) OpenMillis else CloseMillis
        opening.animateTo(target, Tween(millis, easing = Easings.EaseOut))
    }

    // Keyed on the count, so a hit while the last one is still fading cancels that animation and
    // starts again from full rather than adding a second marker to it.
    LaunchedEffect(state, state.hits) {
        if (state.hits == 0) return@LaunchedEffect
        marker.snapTo(1f)
        marker.animateTo(0f, Tween(MarkerMillis, easing = Easings.EaseOut))
    }

    val arms = (if (state.hostile) hostile else normal).fill()
    val markerColour = (if (state.killed) killStyle else hitStyle).fill()

    val painter = remember(arms, markerColour, gap, arm, thickness, spreadDistance, dot, opening.value, marker.value) {
        ReticlePainter(
            arms = arms,
            marker = markerColour,
            gap = gap + opening.value * spreadDistance,
            arm = arm,
            thickness = thickness,
            dot = dot,
            markerAlpha = marker.value,
        )
    }

    LeafLayout(modifier.fillMaxSize(), name = "reticle", draw = painter.draw)
}

/** How long the crosshair takes to kick open, and how long it takes to settle back. */
private const val OpenMillis = 90
private const val CloseMillis = 260

/** How long a hit marker lasts. Long enough to see out of the corner of an eye, and no longer. */
private const val MarkerMillis = 320

/** How far a hit marker's ticks start from the middle, and how far they travel as they fade. */
private const val MarkerGap = 7f
private const val MarkerLength = 7f
private const val MarkerTravel = 5f

/**
 * The colour a style fills with.
 *
 * A crosshair is lines rather than boxes, so a skin that puts a nine-patch here gets nothing —
 * which is visible, and better than a picture stretched into the shape of a crosshair.
 */
private fun ResolvedStyle.fill(): Colour = (background as? SkinDrawable.Fill)?.colour ?: Colour.Transparent

/**
 * The crosshair, drawn round the middle of the box it is given.
 *
 * Everything it needs is worked out at composition, so a frame is four rectangles and, while a
 * marker is up, four quads — and the scratch array those quads are written into is made once.
 */
private class ReticlePainter(
    private val arms: Colour,
    private val marker: Colour,
    private val gap: Float,
    private val arm: Float,
    private val thickness: Float,
    private val dot: Float,
    private val markerAlpha: Float,
) {

    private val quad = FloatArray(8)

    /**
     * The five rectangles a crosshair is made of, handed back when they have not moved.
     *
     * A crosshair is drawn every frame of the whole game, and a still one is the same five
     * rectangles every time; see RectCache. One cache each, because two of them sharing one would
     * mean neither is ever the one that was kept.
     */
    private val rects = Array(5) { RectCache() }

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        val half = thickness / 2f

        if (arm > 0f && thickness > 0f) {
            rect(rects[0].of(x - half, y - gap - arm, x + half, y - gap), arms)
            rect(rects[1].of(x - half, y + gap, x + half, y + gap + arm), arms)
            rect(rects[2].of(x - gap - arm, y - half, x - gap, y + half), arms)
            rect(rects[3].of(x + gap, y - half, x + gap + arm, y + half), arms)
        }
        if (dot > 0f) {
            rect(rects[4].of(x - dot / 2f, y - dot / 2f, x + dot / 2f, y + dot / 2f), arms)
        }
        if (markerAlpha > 0f) drawMarker(this, x, y)
    }

    /**
     * The four ticks of a hit marker: diagonals leaning away from the middle, drifting outwards as
     * they fade, which is what makes one register as a *hit* rather than as part of the crosshair.
     */
    private fun drawMarker(canvas: UiCanvas, x: Float, y: Float) {
        val out = MarkerGap + (1f - markerAlpha) * MarkerTravel
        val far = out + MarkerLength
        val colour = marker.scaleAlpha(markerAlpha)

        stroke(canvas, x - out, y - out, x - far, y - far, colour)
        stroke(canvas, x + out, y - out, x + far, y - far, colour)
        stroke(canvas, x - out, y + out, x - far, y + far, colour)
        stroke(canvas, x + out, y + out, x + far, y + far, colour)
    }

    /** One diagonal line, as the four corners of a quad, because a fan is what draws a line at an angle. */
    private fun stroke(canvas: UiCanvas, x1: Float, y1: Float, x2: Float, y2: Float, colour: Colour) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return

        // Across the line rather than along it: half a thickness either side of both ends.
        val nx = -dy / length * thickness / 2f
        val ny = dx / length * thickness / 2f

        quad[0] = x1 + nx; quad[1] = y1 + ny
        quad[2] = x1 - nx; quad[3] = y1 - ny
        quad[4] = x2 - nx; quad[5] = y2 - ny
        quad[6] = x2 + nx; quad[7] = y2 + ny
        canvas.fan(quad, colour)
    }
}
