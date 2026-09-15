package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.PointerEvent
import dev.wildware.composegl.ui.input.PointerHandler
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onPointer
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.node.UiApplier
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * One choice out of a list that opens and closes: a resolution, a language, a difficulty.
 *
 * ```kotlin
 * Dropdown(
 *     options = resolutions,
 *     selected = current,
 *     onSelect = { current = it },
 *     label = { Text(it.toString()) },
 * )
 * ```
 *
 * Closed, it is a field showing the chosen option and an arrow. Pressed — by a click, Enter or the
 * pad's South — it opens the list under itself, over everything else on the screen, with focus on
 * the chosen option. From there:
 *
 * - **Choosing** is the same press on an option. It calls [onSelect], closes the list, and focus
 *   goes back to the field.
 * - **Moving** is the arrows or the d-pad, and focus cannot leave the list while it is open.
 * - **Closing without choosing** is Escape, the pad's East or Back, or a press anywhere outside the
 *   list. That press does nothing else: it closes the list and is used up.
 *
 * The list is drawn by the screen's [PopupHost], so one has to be round the screen. It is as wide
 * as the field — give the field a width that fits the longest option — and opens upwards when there
 * is no room below. A list taller than [maxListHeight] or than the room it has scrolls.
 *
 * Like [RadioButton], it draws and reports and the screen holds the answer.
 *
 * @param label how an option is drawn, both in the field and in the list.
 * @param style the skin style for the field. The list's panel is `"<style>.list"`.
 * @param itemStyle the skin style for an option in the list. The chosen one is `"<itemStyle>.selected"`.
 */
@Composable
fun <T> Dropdown(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: String = "dropdown",
    listStyle: String = "$style.list",
    itemStyle: String = "item",
    maxListHeight: Float = 280f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    label: @Composable (T) -> Unit,
) {
    requirePopups()
    var expanded by remember { mutableStateOf(false) }
    val open = expanded && enabled
    // Turned off while open, it closes for good, rather than springing open again when it is
    // turned back on long after the player has forgotten it.
    if (expanded && !enabled) SideEffect { expanded = false }
    val anchor = remember { PopupAnchor() }
    val resolved = rememberStyle(style, rememberStates(interaction, enabled))

    ComposeNode<UiNode, UiApplier>(
        factory = { UiNode("dropdown").also { anchor.node = it } },
        update = {
            set(
                modifier
                    .interaction(interaction)
                    .focusable(interaction, enabled = enabled, initial = initialFocus)
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .styled(resolved),
            ) { this.modifier = it }
            set(FieldPolicy) { this.measurePolicy = it }
        },
        content = {
            ProvideContentStyle(resolved) {
                // Unselectable inside a SelectionContainer, so a press on the chosen option still opens the list.
                Box { DisableSelection { label(selected) } }
                DropdownArrow(resolved.textColour)
            }
        },
    )

    if (!open) return

    OnBack { expanded = false }

    Popup(anchor, onDismiss = { expanded = false }, maxHeight = maxListHeight) {
        // A press on the panel's own padding, between options, is inside the list and not a reason
        // to close it.
        val inside = remember { PointerHandler { it is PointerEvent.Press } }
        Panel(Modifier.fillMaxWidth().onPointer(inside), style = listStyle) {
            ScrollArea(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    options.forEach { option ->
                        val chosen = option == selected
                        Button(
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = if (chosen) "$itemStyle.selected" else itemStyle,
                            initialFocus = chosen,
                            contentAlignment = Alignment.CentreStart,
                        ) { label(option) }
                    }
                }
            }
        }
    }
}

/** The small downward triangle at the end of the field, in the field's text colour. */
@Composable
private fun DropdownArrow(colour: Colour) {
    val draw: UiCanvas.(Rect) -> Unit = remember(colour) {
        { box -> fan(floatArrayOf(box.left, box.top, box.right, box.top, (box.left + box.right) / 2f, box.bottom), colour) }
    }
    LeafLayout(Modifier.size(ArrowWidth, ArrowHeight), name = "dropdown.arrow", draw = draw)
}

private const val ArrowWidth = 10f
private const val ArrowHeight = 6f
private const val ArrowGap = 10f

/**
 * The label on the left, the arrow on the right, both centred up and down.
 *
 * A policy of its own rather than a row, because the arrow belongs at the field's right edge
 * whatever width the field was given, and only the node that holds the field's size can see that
 * width — a box inside it is offered loosened room and never learns it.
 */
private object FieldPolicy : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val placeables = placeables(2)
        val placements = placements(2)

        val arrow = measurables[1].measure(Constraints(0f, constraints.maxWidth, 0f, constraints.maxHeight))
        val room = (constraints.maxWidth - arrow.width - ArrowGap).coerceAtLeast(0f)
        val label = measurables[0].measure(Constraints(0f, room, 0f, constraints.maxHeight))
        placeables[0] = label
        placeables[1] = arrow

        val width = constraints.constrainWidth(label.width + ArrowGap + arrow.width)
        val height = constraints.constrainHeight(maxOf(label.height, arrow.height))

        placements[0] = 0f
        placements[1] = (height - label.height) / 2f
        placements[2] = width - arrow.width
        placements[3] = (height - arrow.height) / 2f

        return layout(width, height, 2)
    }
}
