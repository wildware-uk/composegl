package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled as styledWith
import dev.wildware.composegl.ui.skin.WidgetState
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle
import dev.wildware.composegl.ui.skin.styled

/**
 * A box with a tick in it.
 *
 * The control and its label are one thing: the label is part of what you click, because a tick box
 * is eighteen pixels across and nobody should have to hit it.
 *
 * @param label written beside the box. Null for the box on its own, in a table of them.
 * @param style the skin style for the box. The tick is `"<style>.tick"`, so a game restyles both
 *   by naming two styles rather than by passing a picture in here.
 * @param size how big the box is drawn. The tick fills whatever the style's padding leaves.
 */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: String = "checkbox",
    tickStyle: String = "$style.tick",
    labelStyle: String = "label",
    size: Float = 18f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Control(
        modifier = modifier,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
        onClick = { onCheckedChange(!checked) },
        label = label,
        labelStyle = labelStyle,
    ) { states ->
        Box(Modifier.size(size).styled(style, states)) {
            if (checked) Box(Modifier.fillMaxSize().styled(tickStyle, states))
        }
    }
}

/**
 * One of several, only one of which can be chosen.
 *
 * Which ones belong together is the game's business: this draws and reports, and the screen holds
 * the answer. That is deliberately simpler than a group object — a radio button in a game is
 * usually one of three difficulty settings in a `when`, not a form control.
 *
 * @param onSelect called when this one is chosen. Never called to unchoose: picking another one is
 *   how this one stops being picked.
 */
@Composable
fun RadioButton(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: String = "radio",
    dotStyle: String = "$style.dot",
    labelStyle: String = "label",
    size: Float = 18f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Control(
        modifier = modifier,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
        onClick = onSelect,
        label = label,
        labelStyle = labelStyle,
    ) { states ->
        Box(Modifier.size(size).styled(style, states)) {
            if (selected) Box(Modifier.fillMaxSize().styled(dotStyle, states))
        }
    }
}

/**
 * A switch: on at one end, off at the other.
 *
 * The same answer as a checkbox to a different question — a checkbox agrees to something, a switch
 * turns something on. Games use switches for settings, and players read the position rather than
 * the tick.
 *
 * The knob is as tall as the track leaves it after the style's padding, so a skin can make the
 * track fatter or the knob tighter without anybody editing this.
 *
 * @param style the track when it is off. The track when it is on is `"<style>.on"` and the knob is
 *   `"<style>.knob"`.
 */
@Composable
fun Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: String = "toggle",
    knobStyle: String = "$style.knob",
    labelStyle: String = "label",
    width: Float = 40f,
    height: Float = 22f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
) {
    Control(
        modifier = modifier,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
        onClick = { onCheckedChange(!checked) },
        label = label,
        labelStyle = labelStyle,
    ) { states ->
        val track = rememberStyle(if (checked) "$style.on" else style, states)
        val knob = (height - track.padding.vertical).coerceAtLeast(0f)

        Box(
            modifier = Modifier.size(width, height).styledWith(track),
            // The whole animation, for now: the knob is at one end or the other. It slides once
            // there is a clock to slide it on, which is M11.
            contentAlignment = if (checked) Alignment.CentreEnd else Alignment.CentreStart,
        ) {
            Box(Modifier.size(knob).styled(knobStyle, states))
        }
    }
}

/**
 * What the three of them share: something small that shows a value, a label you can also hit, and
 * one set of states driving both.
 *
 * Written once because the interesting part is identical and the drawing is not. The states are
 * handed to the control rather than read again inside it, so the box and the label can never
 * disagree about whether the thing is hovered.
 */
@Composable
private fun Control(
    modifier: Modifier,
    enabled: Boolean,
    initialFocus: Boolean,
    interaction: InteractionState,
    onClick: () -> Unit,
    label: String?,
    labelStyle: String,
    spacing: Float = 8f,
    control: @Composable (Set<WidgetState>) -> Unit,
) {
    val states = rememberStates(interaction, enabled)
    val touchable = modifier
        .interaction(interaction)
        .focusable(interaction, enabled = enabled, initial = initialFocus)
        .clickable(enabled = enabled, onClick = onClick)

    if (label == null) {
        Box(touchable) { control(states) }
        return
    }

    Row(
        modifier = touchable,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        control(states)
        ProvideContentStyle(rememberStyle(labelStyle, states)) { Text(label) }
    }
}
