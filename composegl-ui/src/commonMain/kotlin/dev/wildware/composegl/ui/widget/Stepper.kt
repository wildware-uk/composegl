package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.wildware.composegl.ui.focus.FocusDirection
import dev.wildware.composegl.ui.input.DirectionHandler
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusDirection
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled

/**
 * One of a few, picked with left and right: `< Medium >`.
 *
 * The console settings control, because it needs nothing but the stick. Nothing opens, nothing
 * scrolls, and the player can see the answer without committing to it. Left and right are the
 * stepper's while it has focus — the arrow keys and the pad arrive by the same door, as they do for
 * a [Slider] — and holding either one repeats, at the pad's rate on a pad and at the keyboard's own
 * rate on a keyboard. At either end it stops claiming the direction, so one more press leaves the
 * control rather than grinding against the last option. [wrap] turns that off and goes round.
 *
 * The mouse holds an arrow down to repeat too, on the frame clock: once at once, then after a pause,
 * then steadily, waiting while the pointer is dragged off the arrow. Clicking the value, Enter, or the pad's South button moves to the next option and
 * goes round at the end, because a click that does nothing on the last option looks broken.
 *
 * The value is as wide as the widest option, so the arrows stay put while it changes. Everything it
 * looks like is the skin's: `"<style>"` behind the whole thing, `"<style>.arrow"` for the two arrows
 * — disabled at an end they cannot pass, pressed while held — and `"<style>.value"` for the words.
 *
 * ```kotlin
 * Stepper(options = listOf("Low", "Medium", "High"), selected = quality, onSelect = { quality = it })
 * ```
 *
 * @param label how an option is written. `toString` by default, which suits strings and enums.
 * @param wrap whether the last option steps round to the first. Off by default, so a player can
 *   leave the control sideways.
 */
@Composable
fun <T> Stepper(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    wrap: Boolean = false,
    style: String = "stepper",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    val index = options.indexOf(selected)
    val labels = options.map(label)
    StepperControl(
        count = options.size,
        index = index,
        onIndex = { onSelect(options[it]) },
        text = if (index >= 0) labels[index] else "",
        between = false,
        sizing = labels,
        modifier = modifier,
        wrap = wrap,
        style = style,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
    )
}

/**
 * A whole number, picked with left and right: `< 7 >`.
 *
 * A [Stepper] whose options are a range, for a setting that is counted rather than named — a volume
 * out of ten, a number of lives. It never reports a value off its steps or outside its range.
 *
 * ```kotlin
 * NumberStepper(value = volume, range = 0..10, onValueChange = { volume = it })
 * ```
 *
 * @param step the gap between values. `5` on `0..100` gives twenty-one of them.
 * @param format how a value is written: `"70%"`, `"x2"`.
 */
@Composable
fun NumberStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..10,
    step: Int = 1,
    wrap: Boolean = false,
    format: (Int) -> String = { it.toString() },
    style: String = "stepper",
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    require(step > 0) { "a NumberStepper's step must be above zero, not $step" }
    require(!range.isEmpty()) { "a NumberStepper needs at least one value, and $range has none" }

    val count = (range.last - range.first) / step + 1
    // A value between two steps shows as it is. Right lands it on the step above and left on the
    // one below, so neither direction skips a step on the way. Below the range it is on no step at
    // all, like a Stepper's selection that is not an option: right goes to the first, left nowhere.
    val below = value < range.first
    val index = if (below) -1 else ((value.coerceAtMost(range.last) - range.first) / step).coerceAtMost(count - 1)
    StepperControl(
        count = count,
        index = index,
        onIndex = { onValueChange(range.first + it * step) },
        text = format(value),
        between = !below && value != range.first + index * step,
        // The ends are the widest a counted value usually gets, and measuring all of a big range
        // to find out would be a frame spent on nothing.
        sizing = listOf(format(range.first), format(range.first + (count - 1) * step)),
        modifier = modifier,
        wrap = wrap,
        style = style,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
    )
}

/** How long a held arrow waits before it repeats. The same as a held stick on [GamepadNavigator]. */
private const val FirstRepeatNanos = 400_000_000L

/** And how long between repeats after that. */
private const val RepeatNanos = 110_000_000L

/** What both steppers are: a count of options, which one is showing, and a way to pick another. */
@Composable
private fun StepperControl(
    count: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    text: String,
    between: Boolean,
    sizing: List<String>,
    modifier: Modifier,
    wrap: Boolean,
    style: String,
    enabled: Boolean,
    initialFocus: Boolean,
    interaction: InteractionState,
) {
    val states = rememberStates(interaction, enabled)

    val stepper = remember { StepperLogic() }
    stepper.count = count
    stepper.index = index
    stepper.between = between
    stepper.wrap = wrap
    stepper.enabled = enabled
    stepper.report = onIndex
    // Disabled while an arrow was held: the hold is over, or its repeat would outlive the control.
    if (!enabled) stepper.held = 0

    val pointer = remember(stepper) { PointerHandler { stepper.pointer(it) } }
    val directions = remember(stepper) { DirectionHandler { stepper.nudge(it) } }

    // Hold to repeat, for a pointer. A pad and a keyboard repeat on their own and arrive as more
    // nudges; a mouse button held on an arrow says nothing more after the press, so the frame clock
    // is what keeps it going.
    val held = stepper.held
    LaunchedEffect(stepper, held) {
        if (held == 0) return@LaunchedEffect
        var next = withFrameNanos { it } + FirstRepeatNanos
        while (true) {
            val now = withFrameNanos { it }
            // Let go since the last frame: the release has not recomposed this effect away yet, and
            // one more step after it would be a step nobody asked for.
            if (stepper.held != held) break
            if (now < next) continue
            // Dragged off the arrow, it waits — the way a scrollbar's arrow does — and carries on
            // if the pointer comes back while still held.
            if (!stepper.overHeld) continue
            // At an end with nowhere to go, stop rather than ask again every frame.
            if (!stepper.step(held)) break
            next = now + RepeatNanos
        }
    }

    val valueStyle = rememberStyle("$style.value", states)
    val fonts = rememberFonts()
    val face = valueStyle.textStyle
    val widest = remember(sizing, face, fonts) { sizing.maxOfOrNull { fonts.measure(it, face).size.width } ?: 0f }

    Layout(
        modifier = modifier
            .interaction(interaction)
            .focusable(interaction, enabled = enabled, initial = initialFocus)
            .onPointer(pointer)
            .onFocusDirection(directions)
            .clickable(enabled = enabled) { if (!stepper.swallowClick()) stepper.step(1, wrapAnyway = true) }
            .styled(style, states),
        name = "stepper",
        content = {
            Arrow("<", "$style.arrow", arrowStates(states, pressed = held < 0 && stepper.overHeld, blocked = !stepper.canStep(-1)))
            Box(Modifier.styled("$style.value", states), contentAlignment = Alignment.Centre) {
                ProvideContentStyle(valueStyle) { Text(text) }
            }
            Arrow(">", "$style.arrow", arrowStates(states, pressed = held > 0 && stepper.overHeld, blocked = !stepper.canStep(1)))
        },
        measurePolicy = StepperPolicy(stepper, widest + valueStyle.padding.horizontal),
    )
}

@Composable
private fun Arrow(glyph: String, style: String, states: Set<WidgetState>) {
    Box(Modifier.styled(style, states), contentAlignment = Alignment.Centre) {
        ProvideContentStyle(rememberStyle(style, states)) { Text(glyph) }
    }
}

/**
 * An arrow's states: the control's, except that it is pressed only while it is the one held, and
 * disabled at an end it cannot step past — which is how a player sees there is nothing further.
 */
private fun arrowStates(control: Set<WidgetState>, pressed: Boolean, blocked: Boolean): Set<WidgetState> =
    buildSet {
        addAll(control)
        remove(WidgetState.Pressed)
        if (pressed) add(WidgetState.Pressed)
        if (blocked) add(WidgetState.Disabled)
    }

/**
 * Arrow, value, arrow, in a row and centred on each other.
 *
 * The value is at least [valueWidth] — the widest option — so the arrows do not move when the words
 * do. A width given to the whole control goes to the value, which is the part that has a use for it.
 */
private class StepperPolicy(
    private val stepper: StepperLogic,
    private val valueWidth: Float,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val left = measurables[0].measure(loose)
        val right = measurables[2].measure(loose)
        val arrows = left.width + right.width

        val fixed = constraints.minWidth == constraints.maxWidth && constraints.hasBoundedWidth
        val wanted = if (fixed) (constraints.maxWidth - arrows).coerceAtLeast(0f) else valueWidth
        val value = measurables[1].measure(
            Constraints(
                minWidth = wanted,
                maxWidth = if (fixed) wanted else Float.POSITIVE_INFINITY,
                maxHeight = constraints.maxHeight,
            ),
        )

        val width = constraints.constrainWidth(arrows + value.width)
        val height = constraints.constrainHeight(maxOf(left.height, value.height, right.height))

        // Written down for the pointer, which has only a position to go on.
        stepper.leftEnd = left.width
        stepper.rightStart = left.width + value.width
        stepper.width = width
        stepper.height = height

        return layout(width, height) {
            left.at(0f, (height - left.height) / 2f)
            value.at(left.width, (height - value.height) / 2f)
            right.at(left.width + value.width, (height - right.height) / 2f)
        }
    }
}

/**
 * What a stepper knows, in one object that outlives a recomposition — because the pointer, the pad
 * and the repeat all ask it outside composition.
 */
private class StepperLogic {

    var count = 0
    var index = 0
    var wrap = false
    var enabled = true

    /** A number showing between two of its steps, so left goes to [index] itself rather than past it. */
    var between = false
    var report: (Int) -> Unit = {}

    /** Where the left arrow ends and the right one starts, in the control's own units. From layout. */
    var leftEnd = 0f
    var rightStart = 0f

    /** The whole control, for telling a release inside it from one outside. */
    var width = 0f
    var height = 0f

    /** Which arrow the pointer is holding down: -1, 1, or 0 for neither. State, so the arrow redraws. */
    var held by mutableStateOf(0)

    /** Whether the pointer holding an arrow is still over it. State, so the arrow redraws. */
    var overHeld by mutableStateOf(true)

    /**
     * The press that started on an arrow is also, as far as the pointer is concerned, a press on a
     * clickable control, and its release would be a click. That click has already been spent as a
     * step, so it is swallowed once.
     */
    private var arrowClick = false

    fun canStep(by: Int): Boolean = enabled && target(by, wrap) != null

    /** One step. False when there is nowhere to go, which is what lets focus move on. */
    fun step(by: Int, wrapAnyway: Boolean = false): Boolean {
        if (!enabled) return false
        val wanted = target(by, wrap || wrapAnyway) ?: return false
        // Written straight away as well as reported, so two steps inside one frame — a key and its
        // repeat — count as two rather than as the same one twice.
        index = wanted
        between = false
        report(wanted)
        return true
    }

    fun nudge(direction: FocusDirection): Boolean {
        if (!enabled) return false
        return when (direction) {
            FocusDirection.Left -> step(-1)
            FocusDirection.Right -> step(1)
            else -> false
        }
    }

    fun pointer(event: PointerEvent): Boolean {
        // A release still ends a hold that began before the control was disabled.
        if (!enabled && event !is PointerEvent.Release && event !is PointerEvent.Cancel) return false
        return when (event) {
            is PointerEvent.Press -> {
                val side = sideAt(event.position.x, event.position.y)
                // Not on an arrow: say nothing, and the control's click takes the press instead.
                if (side == 0) return false
                step(side)
                held = side
                overHeld = true
                true
            }
            is PointerEvent.Move -> {
                if (held == 0) return false
                overHeld = sideAt(event.position.x, event.position.y) == held
                true
            }
            is PointerEvent.Release -> {
                val wasHeld = held != 0
                held = 0
                // Only a release inside is a click; one outside must not leave a swallow behind
                // for the next Enter.
                val p = event.position
                arrowClick = wasHeld && p.x >= 0f && p.y >= 0f && p.x < width && p.y < height
                wasHeld
            }
            is PointerEvent.Cancel -> {
                val wasHeld = held != 0
                held = 0
                arrowClick = false
                wasHeld
            }
            is PointerEvent.Scroll, is PointerEvent.Exit -> false
        }
    }

    /** Which arrow is under a point: -1 the left, 1 the right, 0 the value or outside the control. */
    private fun sideAt(x: Float, y: Float): Int = when {
        y < 0f || y >= height || x < 0f || x >= width -> 0
        x < leftEnd -> -1
        x >= rightStart -> 1
        else -> 0
    }

    fun swallowClick(): Boolean {
        val was = arrowClick
        arrowClick = false
        return was
    }

    private fun target(by: Int, wrap: Boolean): Int? {
        if (count <= 0) return null
        if (index !in 0 until count) return if (by > 0) 0 else if (wrap) count - 1 else null
        if (between && by < 0) return index
        val wanted = index + by
        return when {
            wanted in 0 until count -> wanted
            !wrap || count == 1 -> null
            else -> (wanted % count + count) % count
        }
    }
}
