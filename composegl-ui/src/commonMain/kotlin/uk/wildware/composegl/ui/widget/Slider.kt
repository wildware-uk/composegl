package uk.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import uk.wildware.composegl.ui.focus.FocusDirection
import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.input.DirectionHandler
import uk.wildware.composegl.ui.input.InteractionState
import uk.wildware.composegl.ui.input.PointerEvent
import uk.wildware.composegl.ui.input.PointerHandler
import uk.wildware.composegl.ui.layout.Constraints
import uk.wildware.composegl.ui.layout.Layout
import uk.wildware.composegl.ui.layout.LeafLayout
import uk.wildware.composegl.ui.layout.Measurable
import uk.wildware.composegl.ui.layout.MeasurePolicy
import uk.wildware.composegl.ui.layout.MeasureResult
import uk.wildware.composegl.ui.layout.MeasureScope
import uk.wildware.composegl.ui.modifier.Modifier
import uk.wildware.composegl.ui.modifier.focusable
import uk.wildware.composegl.ui.modifier.interaction
import uk.wildware.composegl.ui.modifier.onFocusDirection
import uk.wildware.composegl.ui.modifier.onPointer
import uk.wildware.composegl.ui.skin.rememberStates
import uk.wildware.composegl.ui.skin.styled
import kotlin.math.round

/** Which way a control runs. */
enum class Orientation { Horizontal, Vertical }

/**
 * A value the player drags.
 *
 * Three ways in, all of them the same arithmetic: drag the knob, press an arrow key, or push the
 * stick. The keyboard and the pad arrive by the same door — both ask focus to move, and a focused
 * slider claims the direction along its own axis before focus goes looking for a neighbour. At
 * either end it stops claiming, so one more press to the right leaves the slider rather than
 * grinding against the maximum: a control a pad cannot get out of is worse than no control.
 *
 * Everything it looks like is the skin's: `"<style>.track"`, `"<style>.fill"` and `"<style>.knob"`.
 *
 * ```kotlin
 * Slider(volume, onValueChange = { volume = it }, range = 0f..100f, step = 5f)
 * ```
 *
 * @param range the ends. A vertical slider has its maximum at the top, which is where a player
 *   reaches for "more".
 * @param step the gap between the values it may take. Zero is continuous; `5f` on `0f..100f` gives
 *   twenty-one positions and the knob lands on nothing in between. Also the size of one nudge.
 * @param thickness how thick the track is drawn. The knob is [knob] across, and the control is as
 *   thick as the larger of the two.
 * @param length how long it is when nothing else decides — a slider in a row with no width given
 *   to it. A `fillMaxWidth` or a `width` wins.
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    step: Float = 0f,
    orientation: Orientation = Orientation.Horizontal,
    style: String = "slider",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    thickness: Float = 6f,
    knob: Float = 16f,
    length: Float = 160f,
) {
    val states = rememberStates(interaction, enabled)
    val horizontal = orientation == Orientation.Horizontal

    // One object holds what the widget knows and what layout measured, so the pointer and the pad
    // can work out a value from a position without either of them being recomposed into existence
    // again on every frame of a drag.
    val slider = remember { SliderLogic() }
    slider.value = value
    slider.range = range
    slider.step = step
    slider.horizontal = horizontal
    slider.enabled = enabled
    slider.knob = knob
    slider.report = onValueChange

    val pointer = remember(slider) { PointerHandler { slider.pointer(it) } }
    val directions = remember(slider) { DirectionHandler { slider.nudge(it) } }

    val span = range.endInclusive - range.start
    val fraction = if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)

    Layout(
        modifier = modifier
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .onPointer(pointer)
            .onFocusDirection(directions),
        name = "slider",
        content = {
            LeafLayout(Modifier.styled("$style.track", states), name = "slider.track")
            LeafLayout(Modifier.styled("$style.fill", states), name = "slider.fill")
            LeafLayout(Modifier.styled("$style.knob", states), name = "slider.knob")
        },
        measurePolicy = SliderPolicy(slider, fraction, horizontal, thickness, knob, length),
    )
}

/**
 * Where the three pieces go.
 *
 * The knob travels between the ends rather than off them, so the whole of it is always on the
 * track — the reason the travel is the length minus the knob, and the reason the filled part
 * reaches the knob's middle rather than its edge.
 */
private class SliderPolicy(
    private val slider: SliderLogic,
    private val fraction: Float,
    private val horizontal: Boolean,
    private val thickness: Float,
    private val knob: Float,
    private val length: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val across = maxOf(thickness, knob)
        // A width given to it wins; otherwise it is [length] long, or as much of it as there is
        // room for. A slider with no length at all would be a control nobody can aim at.
        val along = if (horizontal) {
            if (constraints.minWidth == constraints.maxWidth) constraints.maxWidth
            else minOf(length, constraints.maxWidth)
        } else {
            if (constraints.minHeight == constraints.maxHeight) constraints.maxHeight
            else minOf(length, constraints.maxHeight)
        }

        val width = if (horizontal) constraints.constrainWidth(along) else constraints.constrainWidth(across)
        val height = if (horizontal) constraints.constrainHeight(across) else constraints.constrainHeight(along)
        val span = if (horizontal) width else height

        val travel = (span - knob).coerceAtLeast(0f)
        slider.travel = travel

        // A vertical slider's maximum is at the top, because that is where a player reaches for
        // "more". So its knob runs the other way down the screen.
        val knobAt = if (horizontal) fraction * travel else (1f - fraction) * travel
        val middle = knobAt + knob / 2f

        val track = measurables[0].measure(fixed(span, thickness))
        val fill = measurables[1].measure(
            if (horizontal) fixed(middle, thickness) else fixed(span - middle, thickness),
        )
        val handle = measurables[2].measure(Constraints.fixed(knob, knob))

        return layout(width, height) {
            if (horizontal) {
                val centre = (height - thickness) / 2f
                track.at(0f, centre)
                fill.at(0f, centre)
                handle.at(knobAt, (height - knob) / 2f)
            } else {
                val centre = (width - thickness) / 2f
                track.at(centre, 0f)
                fill.at(centre, middle)
                handle.at((width - knob) / 2f, knobAt)
            }
        }
    }

    /** Along and across, in the order this slider's axis puts them. */
    private fun fixed(along: Float, across: Float) =
        if (horizontal) Constraints.fixed(along, across) else Constraints.fixed(across, along)
}

/**
 * What a slider knows, in one mutable object that outlives a recomposition.
 *
 * The arithmetic is here rather than in the composable because the pointer and the pad ask for it
 * outside composition, in the middle of a drag, when there is nothing to read a `remember` from.
 */
private class SliderLogic {

    var value = 0f
    var range = 0f..1f
    var step = 0f
    var horizontal = true
    var enabled = true
    var knob = 0f
    var report: (Float) -> Unit = {}

    /** How far the knob can move, in pixels. Written by layout, read by input. */
    var travel = 0f

    fun pointer(event: PointerEvent): Boolean {
        if (!enabled) return false
        return when (event) {
            // The press takes the pointer, so the rest of the drag arrives here even when it
            // wanders off the control — which is what makes a slider draggable past its own ends
            // rather than sticking wherever the pointer left it.
            is PointerEvent.Press -> { moveTo(event.position); true }
            is PointerEvent.Move -> if (event.pressed.isEmpty()) false else { moveTo(event.position); true }
            is PointerEvent.Release, is PointerEvent.Cancel -> true
            is PointerEvent.Scroll, is PointerEvent.Exit -> false
        }
    }

    /** A direction along this slider's axis. False for anything else, so focus can move on. */
    fun nudge(direction: FocusDirection): Boolean {
        if (!enabled) return false
        val by = when (direction) {
            FocusDirection.Left -> if (horizontal) -1f else return false
            FocusDirection.Right -> if (horizontal) 1f else return false
            FocusDirection.Up -> if (horizontal) return false else 1f
            FocusDirection.Down -> if (horizontal) return false else -1f
            else -> return false
        }

        // A nudge is one step, or a twentieth of the range when there are no steps: twenty presses
        // from end to end, which is also about two seconds of a held stick at the repeat rate.
        val amount = if (step > 0f) step else (range.endInclusive - range.start) / 20f
        val wanted = settle(value + by * amount)

        // At the end, the direction is not used. That is how a player leaves the control.
        if (wanted == value) return false
        report(wanted)
        return true
    }

    private fun moveTo(position: Offset) {
        if (travel <= 0f) return
        val along = if (horizontal) position.x else position.y
        // The knob is grabbed by its middle, so the value under the pointer is the value it gets.
        val fraction = ((along - knob / 2f) / travel).coerceIn(0f, 1f)
        val wanted = settle(range.start + (if (horizontal) fraction else 1f - fraction) * span)
        if (wanted != value) report(wanted)
    }

    private val span: Float get() = range.endInclusive - range.start

    /** Clamped to the ends, and landed on a step if there are any. */
    private fun settle(raw: Float): Float {
        val clamped = raw.coerceIn(range.start, range.endInclusive)
        if (step <= 0f) return clamped
        val stepped = range.start + round((clamped - range.start) / step) * step
        return stepped.coerceIn(range.start, range.endInclusive)
    }
}
