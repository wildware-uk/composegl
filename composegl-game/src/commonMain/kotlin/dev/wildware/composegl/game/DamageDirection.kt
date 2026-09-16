package dev.wildware.composegl.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Clocks
import dev.wildware.composegl.ui.animation.LocalClocks
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.skin.flatColour
import dev.wildware.composegl.ui.skin.rememberStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the hits are coming from.
 *
 * A pool, like [DamageNumbers], and for the same reason: being shot at is exactly when a game must
 * not stop to ask for memory. Every arc a screen can show at once is made when this is, [hit] takes
 * the next one, and an arc that has run out of life goes back in the pool. Nothing here allocates
 * once it is going — not the arcs, not the list of what is alive.
 *
 * Arcs stack. Two hits from the same side leave two arcs on top of each other, each fading on its
 * own, which is what tells a player they are being shot at repeatedly rather than once. The pool
 * is small on purpose: past about eight, arcs are a ring rather than a direction, and the newest
 * hit is the one worth reading, so it takes the oldest one's place.
 *
 * Time is a [Clock]'s, so arcs freeze when the world does rather than all expiring behind a pause
 * menu. An arc is timed from the hit rather than from the frame it is drawn on, so a pool held by
 * the game — above a HUD the player can hide — does not save up every hit taken while the layer
 * was off the screen and show them all at once when it comes back.
 *
 * ```kotlin
 * val hits = rememberDamageDirections()
 * DamageDirectionLayer(hits)
 * // when the player is hit:
 * hits.hit(fromAngle = bearingOf(shooter), strength = 0.7f)
 * ```
 *
 * @param capacity how many arcs can be on screen at once. The oldest gives up its place.
 * @param lifeMillis how long an arc lives, from the hit to nothing left.
 * @param clock which clock they age on. The world's, so a pause freezes them.
 */
class DamageDirections(
    val capacity: Int = 8,
    val lifeMillis: Int = 1_200,
    val clock: Clock = Clock.World,
) {

    init {
        require(capacity > 0) { "a pool of $capacity arcs can hold nothing" }
    }

    internal val entries = Array(capacity) { Entry() }

    /** How many arcs are alive. Snapshot state, so a layer with nothing to draw stops drawing. */
    var active by mutableStateOf(0)
        private set

    private var next = 0

    /**
     * The clocks a layer drawing this pool is on, once one has been composed.
     *
     * Only so that [hit] can stamp the time. A pool that has never been drawn has no clock to ask,
     * and its arcs are born on the frame they are first drawn instead.
     */
    private var clocks: Clocks? = null

    /**
     * Ties the pool to the clocks the host is winding on. Called by the layer.
     *
     * It is what makes a hit taken while the layer is off the screen — a HUD the player has
     * hidden — age like any other, rather than sitting there and coming back at full strength
     * along with every other one when the layer returns.
     */
    internal fun attach(clocks: Clocks) {
        this.clocks = clocks
        clocks.register(clock)
    }

    /**
     * Marks a hit from [fromAngle].
     *
     * @param fromAngle where it came from, in **degrees clockwise from straight ahead**: 0 is
     *   something in front of the player, 90 something on their right, 180 behind them, 270 (or
     *   -90) on their left. That is the player's own bearing rather than a compass one, because
     *   the arc is drawn round the middle of their screen — what they need to know is how far to
     *   turn, not which way is north. A game with a camera works it out from the two positions and
     *   the way the camera is facing:
     *
     *   ```kotlin
     *   val bearing = atan2(shooter.x - player.x, shooter.z - player.z) * 180f / PI.toFloat()
     *   hits.hit(fromAngle = bearing - camera.yawDegrees)
     *   ```
     *
     *   Any value is allowed; one that has gone round more than once means the same as the one
     *   that has not, and no sign is special.
     * @param strength how hard it was, from 0 for a graze to 1 for the worst a hit gets. A weak
     *   hit draws a thin, faint arc and a heavy one a thick, bright one, so a player can read how
     *   much trouble they are in out of the corner of an eye.
     */
    fun hit(fromAngle: Float, strength: Float = 1f) {
        val entry = entries[next]
        next = (next + 1) % capacity
        if (!entry.alive) active++

        entry.angle = fromAngle
        entry.strength = strength.coerceIn(0f, 1f)
        // Stamped now when there is a clock to stamp from, so an arc ages from the hit rather than
        // from whenever it is next drawn. Before the first layer there is none, and then it is born
        // on the frame it is first drawn.
        entry.bornNanos = clocks?.time(clock) ?: NotBornYet
        entry.alive = true
    }

    /** Everything gone at once: a respawn, a screen change, a reset between rounds. */
    fun clear() {
        entries.forEach { it.alive = false }
        active = 0
    }

    /** Retires whatever has run out of life. Called by the layer, once a frame. */
    internal fun expire(nowNanos: Long) {
        var alive = 0
        val life = lifeMillis * 1_000_000L
        entries.forEach { entry ->
            if (!entry.alive) return@forEach
            if (entry.bornNanos != NotBornYet && nowNanos - entry.bornNanos >= life) {
                entry.alive = false
            } else {
                alive++
            }
        }
        if (alive != active) active = alive
    }

    /** One arc. Made once, filled in again and again. */
    internal class Entry {
        var alive = false
        var angle = 0f
        var strength = 1f
        var bornNanos = NotBornYet
    }

    internal companion object {
        /**
         * An arc marked before the pool has ever been drawn, and so before it has a clock to ask.
         * It is born on the frame it is first drawn, rather than being half over by then.
         */
        const val NotBornYet = Long.MIN_VALUE
    }
}

/** A pool of arcs that lives as long as the screen it is on. */
@Composable
fun rememberDamageDirections(
    capacity: Int = 8,
    lifeMillis: Int = 1_200,
    clock: Clock = Clock.World,
): DamageDirections = remember(capacity, lifeMillis, clock) {
    DamageDirections(capacity = capacity, lifeMillis = lifeMillis, clock = clock)
}

/**
 * Draws [directions] as arcs round the middle of the box it is given.
 *
 * One node for the whole layer and one pass for every arc on it, like [DamageNumberLayer]: a
 * handful of arcs that live a second each are not worth a node apiece. It asks for frames only
 * while something is alive, so a player nobody is shooting at costs nothing.
 *
 * The middle of the box rather than the middle of the window, so in split-screen each player's
 * layer draws round their own half with nothing told about the split. The arcs do not turn round
 * in a right-to-left interface either: an arc says where something is in the world, and the world
 * does not swap sides with the language — the same reason a [WorldMarkerLayer]'s markers stay put.
 *
 * The skin names the colour: `"<style>"`, whose own alpha is the most an arc ever reaches.
 *
 * @param radius how far the middle of the arc band is from the centre, in interface pixels. Zero,
 *   the default, takes a third of the shorter side of the box instead, which is the same shape of
 *   ring on a phone held upright as on a television.
 * @param thickness how thick the band is at full strength.
 * @param sweepDegrees how much of the ring one arc covers. Wide enough to read at a glance, narrow
 *   enough that two hits from different sides are two arcs rather than a halo.
 */
@Composable
fun DamageDirectionLayer(
    directions: DamageDirections,
    modifier: Modifier = Modifier,
    style: String = "damage.direction",
    radius: Float = 0f,
    thickness: Float = 16f,
    sweepDegrees: Float = 62f,
) {
    val clocks = LocalClocks.current
    val colour = rememberStyle(style).background.flatColour ?: Colour.Transparent

    // The clock has to be one the host is winding on, or every arc is forever a frame old — and
    // the pool keeps it, so a hit taken while this layer is off the screen still ages.
    remember(directions, clocks) { directions.attach(clocks) }

    // Nothing is asked of the runtime while nobody is shooting: the effect only exists while an
    // arc is alive, and it ends with the last one.
    LaunchedEffect(directions, directions.active > 0) {
        while (directions.active > 0) {
            withFrameNanos { }
            directions.expire(clocks.time(directions.clock))
        }
    }

    val painter = remember(directions, clocks, colour, radius, thickness, sweepDegrees) {
        DirectionPainter(directions, clocks, colour, radius, thickness, sweepDegrees)
    }

    LeafLayout(modifier.fillMaxSize(), name = "damage direction", draw = painter.draw)
}

/**
 * Every arc on screen, drawn in one pass.
 *
 * The one scratch array is the four corners of a quad, written again for every piece of every arc,
 * so a frame of arcs allocates nothing. A band is quads rather than one shape because a ring
 * segment is not convex and [UiCanvas.fan] draws convex shapes — and quads are what lets each
 * piece carry its own opacity, which is what gives an arc its soft ends.
 */
private class DirectionPainter(
    private val directions: DamageDirections,
    private val clocks: Clocks,
    private val colour: Colour,
    private val radius: Float,
    private val thickness: Float,
    private val sweepDegrees: Float,
) {

    private val quad = FloatArray(8)

    val draw: UiCanvas.(Rect) -> Unit = { bounds ->
        val now = clocks.time(directions.clock)
        directions.entries.forEach { entry ->
            if (entry.alive) drawOne(this, entry, now, bounds)
        }
    }

    private fun drawOne(canvas: UiCanvas, entry: DamageDirections.Entry, now: Long, bounds: Rect) {
        if (entry.bornNanos == DamageDirections.NotBornYet) entry.bornNanos = now

        val life = (directions.lifeMillis * 1_000_000L).toFloat()
        val age = ((now - entry.bornNanos).toFloat() / life).coerceIn(0f, 1f)
        // Full for the first part of its life and then fading: an arc that starts fading the
        // moment it appears is one a player glancing down at their feet misses altogether.
        val fade = if (age < HoldUntil) 1f else 1f - (age - HoldUntil) / (1f - HoldUntil)
        val alpha = fade * (WeakestShare + (1f - WeakestShare) * entry.strength)
        if (alpha <= 0f) return

        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        val middle = if (radius > 0f) radius else min(bounds.right - bounds.left, bounds.bottom - bounds.top) * RingShare
        val half = thickness * (WeakestShare + (1f - WeakestShare) * entry.strength) / 2f
        if (middle <= 0f || half <= 0f) return

        // The angle the game gave is measured from straight ahead, which is up the screen; the
        // canvas measures from three o'clock. A quarter turn is the whole difference.
        val start = (entry.angle - 90f - sweepDegrees / 2f) * DegreesToRadians
        val step = sweepDegrees * DegreesToRadians / Segments

        var innerX = x + cos(start) * (middle - half)
        var innerY = y + sin(start) * (middle - half)
        var outerX = x + cos(start) * (middle + half)
        var outerY = y + sin(start) * (middle + half)

        for (piece in 0 until Segments) {
            val angle = start + step * (piece + 1)
            val nextInnerX = x + cos(angle) * (middle - half)
            val nextInnerY = y + sin(angle) * (middle - half)
            val nextOuterX = x + cos(angle) * (middle + half)
            val nextOuterY = y + sin(angle) * (middle + half)

            quad[0] = innerX; quad[1] = innerY
            quad[2] = outerX; quad[3] = outerY
            quad[4] = nextOuterX; quad[5] = nextOuterY
            quad[6] = nextInnerX; quad[7] = nextInnerY
            canvas.fan(quad, colour.scaleAlpha(alpha * taper(piece)))

            innerX = nextInnerX
            innerY = nextInnerY
            outerX = nextOuterX
            outerY = nextOuterY
        }
    }

    /**
     * How solid one piece of the band is: nothing at the two ends, full in the middle.
     *
     * A band that simply stops at its ends reads as a shape — a lozenge, a slice of pie — and a
     * shape has a meaning a player looks for. Faded out at both ends it reads as a direction.
     */
    private fun taper(piece: Int): Float {
        val across = (piece + 0.5f) / Segments
        return sin(across * PI.toFloat())
    }
}

/** How many quads one arc is made of. Enough that its ends fade rather than step. */
private const val Segments = 14

/** How far through its life an arc starts fading. Late: a fading arc is already hard to read. */
private const val HoldUntil = 0.35f

/** What is left of an arc's opacity and thickness at no strength at all, so a graze still shows. */
private const val WeakestShare = 0.35f

/** Where the ring sits when no radius is given: this much of the shorter side of the box. */
private const val RingShare = 0.33f

private const val DegreesToRadians = PI.toFloat() / 180f
