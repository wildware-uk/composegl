package dev.wildware.composegl.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.ColourPickerButton
import dev.wildware.composegl.ui.widget.Dropdown
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.Toggle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.reflect.KMutableProperty0

/**
 * The lines that go in a [DebugWindow]: each one a label and a control, the labels in one column so
 * the controls line up.
 *
 * ```kotlin
 * DebugWindow("Physics") {
 *     tweak("Gravity", physics::gravity, 0f..50f)
 *     toggle("God mode", cheats::godMode)
 *     button("Spawn wave") { spawnWave() }
 * }
 * ```
 *
 * Every control is the toolkit's own — a [Slider], a [Toggle], a [Dropdown], a [Button] — so a mouse,
 * the keyboard and a pad all work them the way they work anywhere else, and the skin draws them.
 *
 * Each takes either a property, `physics::gravity`, or a value and what to do with a new one, for a
 * local `var` a reference cannot be taken to. A property backed by `mutableStateOf` shows a change
 * made anywhere, the moment it is made. A plain `var` shows every change made through the window, and
 * a change made elsewhere the next time the window is composed.
 *
 * Ordinary composables can go between the lines. The scope's [CollapsingHeader] folds a group of
 * lines away and remembers whether it was open, with the window.
 */
class DebugWindowScope internal constructor(
    private val state: DebugWindowsState,
    private val window: String,
    /** How wide the column of labels is. */
    val labelWidth: Float,
    private val style: String,
) {

    /**
     * One line: [label] in the label column and [control] in the rest of the width. What every other
     * line is made of, and the way to put a control of the game's own in a window.
     */
    @Composable
    fun row(label: String, control: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().testTag(DebugWindowTags.row(window, label)),
            horizontalArrangement = Arrangement.spacedBy(LabelGap),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            Text(label, Modifier.width(labelWidth), style = "$style.label", softWrap = false, maxLines = 1, ellipsis = "...")
            Box(Modifier.weight(1f)) { control() }
        }
    }

    /**
     * A number on a slider, with what it is now written beside it.
     *
     * @param step what the slider snaps to, and how many places the readout shows. `0f` for no snapping
     *   and two places.
     */
    @Composable
    fun tweak(label: String, property: KMutableProperty0<Float>, range: ClosedFloatingPointRange<Float>, step: Float = 0f) {
        val (value, set) = rememberProperty(property)
        tweak(label, value, set, range, step)
    }

    /** The same, for a value the game holds. */
    @Composable
    fun tweak(label: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, step: Float = 0f) {
        row(label) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LabelGap), verticalAlignment = VerticalAlignment.Centre) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).testTag(DebugWindowTags.control(window, label)),
                    range = range,
                    step = step,
                    length = SliderLength,
                )
                Text(
                    formatTweak(value, step),
                    Modifier.width(ReadoutWidth),
                    style = "$style.value",
                    align = HorizontalAlignment.End,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }

    /** A whole number on a slider, a [step] at a time. */
    @Composable
    fun tweak(label: String, property: KMutableProperty0<Int>, range: IntRange, step: Int = 1) {
        val (value, set) = rememberProperty(property)
        tweak(label, value, set, range, step)
    }

    /** The same, for a value the game holds. */
    @Composable
    fun tweak(label: String, value: Int, onValueChange: (Int) -> Unit, range: IntRange, step: Int = 1) {
        require(step > 0) { "a step has to be at least 1, was $step" }
        tweak(
            label = label,
            value = value.toFloat(),
            onValueChange = { changed -> changed.roundToInt().let { if (it != value) onValueChange(it) } },
            range = range.first.toFloat()..range.last.toFloat(),
            step = step.toFloat(),
        )
    }

    /** Something on or off, on a [Toggle]. */
    @Composable
    fun toggle(label: String, property: KMutableProperty0<Boolean>) {
        val (value, set) = rememberProperty(property)
        toggle(label, value, set)
    }

    /** The same, for a value the game holds. */
    @Composable
    fun toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        row(label) {
            Toggle(checked, onCheckedChange, Modifier.testTag(DebugWindowTags.control(window, label)))
        }
    }

    /**
     * One of [options], on a [Dropdown]. An enum's `entries` is the usual list.
     *
     * @param name what an option is called in the list. Its `toString()` unless given.
     */
    @Composable
    fun <T> choice(label: String, property: KMutableProperty0<T>, options: List<T>, name: (T) -> String = { it.toString() }) {
        val (value, set) = rememberProperty(property)
        choice(label, value, set, options, name)
    }

    /** The same, for a value the game holds. */
    @Composable
    fun <T> choice(label: String, selected: T, onSelect: (T) -> Unit, options: List<T>, name: (T) -> String = { it.toString() }) {
        row(label) {
            Dropdown(
                options = options,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().testTag(DebugWindowTags.control(window, label)),
                label = {
                    val option = name(it)
                    Text(option, Modifier.testTag(DebugWindowTags.option(window, label, option)), softWrap = false, maxLines = 1)
                },
            )
        }
    }

    /**
     * A colour: a [ColourPickerButton], the swatch that opens the toolkit's colour picker under it,
     * with the hex beside it.
     *
     * @param alpha whether the colour's alpha can be changed too, and is written in the hex.
     */
    @Composable
    fun colour(label: String, property: KMutableProperty0<Colour>, alpha: Boolean = true) {
        val (value, set) = rememberProperty(property)
        colour(label, value, set, alpha)
    }

    /** The same, for a value the game holds. */
    @Composable
    fun colour(label: String, value: Colour, onValueChange: (Colour) -> Unit, alpha: Boolean = true) {
        row(label) {
            Row(horizontalArrangement = Arrangement.spacedBy(LabelGap), verticalAlignment = VerticalAlignment.Centre) {
                ColourPickerButton(
                    colour = value,
                    onColourChange = onValueChange,
                    modifier = Modifier.testTag(DebugWindowTags.control(window, label)),
                    alpha = alpha,
                    size = SwatchSize,
                )
                Text(hexOf(value, alpha), style = "$style.value", softWrap = false, maxLines = 1)
            }
        }
    }

    /** Something to do, on a [Button] as wide as the window. */
    @Composable
    fun button(label: String, onClick: () -> Unit) {
        Button(label, onClick, Modifier.fillMaxWidth().testTag(DebugWindowTags.control(window, label)))
    }

    /** A value to read and not change: a count, a state name, a frame time. */
    @Composable
    fun text(label: String, value: String) {
        row(label) {
            Text(value, Modifier.testTag(DebugWindowTags.control(window, label)), style = "$style.value", softWrap = false, maxLines = 1)
        }
    }

    /**
     * Lines that fold away under a heading, with the toolkit's `CollapsingHeader`.
     *
     * Whether it is open is remembered with the window, under [title], and kept across runs by the
     * window's store.
     */
    @Composable
    fun CollapsingHeader(title: String, initiallyExpanded: Boolean = false, content: @Composable DebugWindowScope.() -> Unit) {
        dev.wildware.composegl.ui.widget.CollapsingHeader(
            title = title,
            expanded = state.isSectionOpen(window, title, initiallyExpanded),
            onExpandedChange = { state.setSectionOpen(window, title, it) },
            modifier = Modifier.fillMaxWidth().testTag(DebugWindowTags.row(window, title)),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) { content() }
        }
    }
}

private const val LabelGap = 8f
private const val SliderLength = 120f
private const val ReadoutWidth = 44f
private const val SwatchSize = 20f

/**
 * A property's value, and a setter that recomposes the line: a property backed by snapshot state
 * would recompose it anyway, and one that is a plain `var` would otherwise never show what was set.
 */
@Composable
private fun <T> rememberProperty(property: KMutableProperty0<T>): Pair<T, (T) -> Unit> {
    var writes by remember { mutableIntStateOf(0) }
    // Read, so a write through the setter below comes back round as a recomposition.
    @Suppress("UNUSED_EXPRESSION")
    writes
    val set = remember(property) {
        { value: T ->
            property.set(value)
            writes++
            Unit
        }
    }
    return property.get() to set
}

/** [value] with as many places as [step] has, or two for no step: `9.81`, `0.05`, `12`. */
internal fun formatTweak(value: Float, step: Float): String {
    val places = if (step <= 0f) 2 else placesIn(step)
    var factor = 1L
    repeat(places) { factor *= 10 }
    val scaled = (abs(value.toDouble()) * factor).roundToLong()
    val whole = scaled / factor
    val fraction = scaled % factor
    return buildString {
        if (value < 0f && scaled != 0L) append('-')
        append(whole)
        if (places > 0) append('.').append(fraction.toString().padStart(places, '0'))
    }
}

/** How many decimal places [step] needs, up to four. */
private fun placesIn(step: Float): Int {
    var places = 0
    var scaled = step.toDouble()
    while (places < 4 && abs(scaled - scaled.roundToLong()) > 1e-4) {
        scaled *= 10
        places++
    }
    return places
}

/** `#RRGGBB`, or `#RRGGBBAA` with [alpha]. */
internal fun hexOf(colour: Colour, alpha: Boolean): String = buildString {
    append('#')
    fun channel(value: Int) = append(value.toString(16).uppercase().padStart(2, '0'))
    channel(colour.red)
    channel(colour.green)
    channel(colour.blue)
    if (alpha) channel(colour.alpha)
}
