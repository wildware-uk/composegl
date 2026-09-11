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
import dev.wildware.composegl.ui.animation.wait
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.Placeable
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.alpha
import dev.wildware.composegl.ui.skin.styled
import dev.wildware.composegl.ui.widget.Orientation

/**
 * A colour the bar takes on below a fraction.
 *
 * Ordered by [below], lowest first, and the first one the value is under wins: with a threshold at
 * 0.25 and one at 0.5, a bar at 0.3 is the 0.5 one.
 */
data class BarThreshold(val below: Float, val style: String)

/**
 * Health, stamina, shields — the bar with a damage trail.
 *
 * The trail is the whole point and it is the part every hand-rolled bar gets wrong. The real value
 * moves the instant it changes, and a second bar behind it holds for a moment and then drains down
 * to meet it, so a player sees *how much* they just lost rather than only that they lost some. Two
 * hits in quick succession keep draining towards the newer value instead of the trail jumping back
 * up and starting again. Healing has no trail at all: the ghost bar goes straight up with the real
 * one, because a ghost bar that lags behind a heal reads as damage.
 *
 * Everything it looks like is the skin's: `"<style>.track"`, `"<style>.fill"`, `"<style>.trail"`
 * and `"<style>.segment"`. [thresholds] name their own fill styles, so "red below a quarter" is a
 * line at the call site and a colour in the skin file.
 *
 * It costs nothing while the value is steady. The trail's animation unsubscribes when it arrives,
 * and a bar with no pulse and no movement on it asks for no frames at all.
 *
 * ```kotlin
 * Bar(health, thresholds = listOf(BarThreshold(0.25f, "bar.fill.critical")), pulseBelow = 0.25f)
 * ```
 *
 * @param value how full it is, from 0 to 1. Anything outside that is clamped.
 * @param segments how many notches to divide it into, or zero for a plain bar. A segmented bar is
 *   easier to read at a glance: a player counts blocks rather than judging a length.
 * @param holdMillis how long the trail waits before it starts draining. Long enough to be seen.
 * @param drainMillis how long the trail takes to catch up, whatever the size of the loss. A fixed
 *   speed instead would make a scratch and a near-death drain at wildly different rates.
 * @param pulseBelow the fraction under which the bar breathes, or zero for never. A player fighting
 *   for their life should not have to read a number.
 * @param clock which clock the trail and the pulse run on. The world's by default: a bar that keeps
 *   draining while the game is paused is showing damage that is not happening.
 */
@Composable
fun Bar(
    value: Float,
    modifier: Modifier = Modifier,
    style: String = "bar",
    orientation: Orientation = Orientation.Horizontal,
    thickness: Float = 10f,
    length: Float = 160f,
    segments: Int = 0,
    thresholds: List<BarThreshold> = emptyList(),
    trail: Boolean = true,
    holdMillis: Int = 300,
    drainMillis: Int = 450,
    pulseBelow: Float = 0f,
    clock: Clock = Clock.World,
) {
    val fraction = value.coerceIn(0f, 1f)
    val clocks = LocalClocks.current

    val ghost = remember(clocks, clock) { Animatable(fraction, FloatVectoriser, clock, clocks) }
    // Whether the trail is already on its way down. A second hit while it is draining carries on to
    // the new value rather than holding again, which is what "queue" means here: the trail is
    // showing one continuous loss, not one animation per hit.
    var draining by remember { mutableStateOf(false) }

    LaunchedEffect(fraction, trail, holdMillis, drainMillis) {
        if (!trail || fraction >= ghost.value) {
            // Healing, or a bar that does not want a trail. Straight there, no ghost.
            draining = false
            ghost.snapTo(fraction)
            return@LaunchedEffect
        }
        if (!draining) clocks.wait(clock, holdMillis)
        draining = true
        ghost.animateTo(fraction, Tween(drainMillis, easing = Easings.Linear))
        draining = false
    }

    val pulse = remember(clocks, clock) { Animatable(1f, FloatVectoriser, clock, clocks) }
    val pulsing = pulseBelow > 0f && fraction <= pulseBelow && fraction > 0f
    LaunchedEffect(pulsing) {
        if (!pulsing) {
            pulse.snapTo(1f)
            return@LaunchedEffect
        }
        // Breathing, rather than blinking: a bar that flashes on and off is read as broken.
        while (true) {
            pulse.animateTo(PulseLow, Tween(PulseMillis, easing = Easings.Sine))
            pulse.animateTo(1f, Tween(PulseMillis, easing = Easings.Sine))
        }
    }

    val fillStyle = thresholds.sortedBy { it.below }.firstOrNull { fraction <= it.below }?.style
        ?: "$style.fill"

    Layout(
        modifier = modifier,
        name = "bar",
        content = {
            LeafLayout(Modifier.styled("$style.track"), name = "bar.track")
            LeafLayout(Modifier.styled("$style.trail"), name = "bar.trail")
            LeafLayout(Modifier.styled(fillStyle).alpha(pulse.value), name = "bar.fill")
            repeat((segments - 1).coerceAtLeast(0)) {
                LeafLayout(Modifier.styled("$style.segment"), name = "bar.segment")
            }
        },
        measurePolicy = BarPolicy(
            fraction = fraction,
            trailFraction = if (trail) maxOf(ghost.value, fraction) else fraction,
            horizontal = orientation == Orientation.Horizontal,
            thickness = thickness,
            length = length,
            segments = segments,
        ),
    )
}

/** How thick a notch between two segments is drawn. Not the skin's: it is a gap, not a colour. */
private const val SegmentWidth = 2f

private const val PulseLow = 0.45f

private const val PulseMillis = 420

/**
 * Track, trail, fill, notches — all of them the same rectangle at different lengths.
 *
 * The trail is placed before the fill so the fill draws over it, which is what makes the pair read
 * as one bar with a tail rather than as two bars.
 */
private class BarPolicy(
    private val fraction: Float,
    private val trailFraction: Float,
    private val horizontal: Boolean,
    private val thickness: Float,
    private val length: Float,
    private val segments: Int,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val along = if (horizontal) {
            if (constraints.minWidth == constraints.maxWidth) constraints.maxWidth
            else minOf(length, constraints.maxWidth)
        } else {
            if (constraints.minHeight == constraints.maxHeight) constraints.maxHeight
            else minOf(length, constraints.maxHeight)
        }

        val width = constraints.constrainWidth(if (horizontal) along else thickness)
        val height = constraints.constrainHeight(if (horizontal) thickness else along)
        val span = if (horizontal) width else height

        // Lent by the node and used again next frame; see MeasureScope.
        val count = measurables.size
        val placeables = placeables(count)
        val placements = placements(count)
        val offers = offers(count)

        fun piece(index: Int, howMuch: Float): Placeable {
            val offer = offers[index]
            val fixed = if (horizontal) {
                offer.of(span * howMuch, span * howMuch, thickness, thickness)
            } else {
                offer.of(thickness, thickness, span * howMuch, span * howMuch)
            }
            return measurables[index].measure(fixed)
        }

        val track = piece(0, 1f)
        val trail = piece(1, trailFraction)
        val fill = piece(2, fraction)
        placeables[0] = track
        placeables[1] = trail
        placeables[2] = fill

        // A vertical bar fills from the bottom, which is where a player expects a tank to empty
        // from, so both of the moving pieces hang off the bottom edge rather than the top.
        placements[1] = 0f
        placements[3] = if (horizontal) 0f else height - trail.height
        placements[5] = if (horizontal) 0f else height - fill.height
        placements[0] = 0f
        placements[2] = 0f
        placements[4] = 0f

        for (index in 3 until count) {
            val offer = offers[index]
            val notch = measurables[index].measure(
                if (horizontal) offer.of(SegmentWidth, SegmentWidth, thickness, thickness)
                else offer.of(thickness, thickness, SegmentWidth, SegmentWidth),
            )
            placeables[index] = notch
            val at = span * (index - 2) / segments.toFloat() - SegmentWidth / 2f
            placements[index * 2] = if (horizontal) at else 0f
            placements[index * 2 + 1] = if (horizontal) 0f else at
        }

        return layout(width, height, count)
    }
}
