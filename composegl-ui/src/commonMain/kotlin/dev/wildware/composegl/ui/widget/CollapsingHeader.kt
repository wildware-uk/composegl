package dev.wildware.composegl.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.animation.AnimationSpec
import dev.wildware.composegl.ui.animation.Clock
import dev.wildware.composegl.ui.animation.Spring
import dev.wildware.composegl.ui.focus.FocusWithinHandler
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.UiCanvas
import dev.wildware.composegl.ui.input.InteractionState
import dev.wildware.composegl.ui.input.LocalUiSounds
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Layout
import dev.wildware.composegl.ui.layout.LayoutDirection
import dev.wildware.composegl.ui.layout.LeafLayout
import dev.wildware.composegl.ui.layout.LocalLayoutDirection
import dev.wildware.composegl.ui.layout.Measurable
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.MeasureResult
import dev.wildware.composegl.ui.layout.MeasureScope
import dev.wildware.composegl.ui.layout.PlacedHandler
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.SizeChangedHandler
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.animateContentSize
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.clip
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.focusable
import dev.wildware.composegl.ui.modifier.interaction
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.styled
import dev.wildware.composegl.ui.node.UiNode
import dev.wildware.composegl.ui.saveable.rememberSaveable
import dev.wildware.composegl.ui.skin.rememberStates
import dev.wildware.composegl.ui.skin.rememberStyle

/**
 * A heading that folds away what is under it: a section of a long settings page, a group of
 * tweakables in a debug window, a chapter of a codex.
 *
 * ```kotlin
 * CollapsingHeader("Physics", initiallyExpanded = true) {
 *     Slider(gravity, onValueChange = { gravity = it })
 * }
 * ```
 *
 * The header is one control, the width it is given, with a small triangle at its start and the
 * title after it. A click, Enter, Space or the pad's South opens it or closes it, and the contents
 * grow out from under it and shrink back with `animateContentSize`, so the rows below slide rather
 * than jump. Closed, the contents are not composed at all: nothing in them can be focused, clicked
 * or reached with a pad, and they cost nothing.
 *
 * Whether it is open is kept with `rememberSaveable`, so a section the player opened is still open
 * when they come back to the screen. A game that wants to hold the answer itself uses the overload
 * that takes `expanded`.
 *
 * On a right-to-left screen the triangle is at the right and a closed one points left, which is the
 * way the contents read.
 *
 * Everything it looks like is the skin's: `"<style>"` for the header bar in its states, and
 * `"<style>.open"` for the bar while it is open (falling back to `"<style>"`); `"<style>.glyph"`
 * for the triangle, drawn in that style's text colour with its background behind it; and
 * `"<style>.body"` round the contents, whose padding is how far they are indented.
 *
 * @param initiallyExpanded whether it starts open, the first time this screen is ever shown.
 * @param style the skin style for the header bar.
 * @param glyphSize how big the triangle is drawn, across its longest side.
 * @param spec how the contents grow and shrink. A spring by default, as `animateContentSize`'s is.
 * @param clock which clock the growing runs on. [Clock.Ui], so a paused game still folds its menus.
 */
@Composable
fun CollapsingHeader(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    style: String = "collapsingheader",
    glyphSize: Float = 10f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    spec: AnimationSpec = Spring(threshold = 0.5f),
    clock: Clock = Clock.Ui,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    CollapsingHeader(
        title = title,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        style = style,
        glyphSize = glyphSize,
        enabled = enabled,
        initialFocus = initialFocus,
        interaction = interaction,
        spec = spec,
        clock = clock,
        content = content,
    )
}

/**
 * The same, with the game holding whether it is open: an "expand all" button, a section opened
 * from somewhere else, a choice written to a save file.
 *
 * Like [Checkbox], it draws and reports and the screen holds the answer. If it is closed from
 * outside while focus is inside the contents, focus comes back to the header rather than being
 * lost with them.
 *
 * @param onExpandedChange called with the new answer when the player opens or closes it.
 */
@Composable
fun CollapsingHeader(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: String = "collapsingheader",
    glyphSize: Float = 10f,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
    interaction: InteractionState = remember { InteractionState() },
    spec: AnimationSpec = Spring(threshold = 0.5f),
    clock: Clock = Clock.Ui,
    content: @Composable () -> Unit,
) {
    val states = rememberStates(interaction, enabled)
    val bar = rememberStyle(if (expanded) "$style.open" else style, states)
    val glyph = rememberStyle("$style.glyph", states)
    val direction = LocalLayoutDirection.current

    // Composed while open and while closing, so the contents are there to shrink away; let go of
    // once the body has arrived at nothing.
    val folding = remember { Folding(expanded) }
    folding.expanded = expanded
    val composed = expanded || folding.shown

    SideEffect {
        if (expanded) {
            folding.shown = true
        } else {
            // Closed from outside with focus in the contents: back to the header, before they go.
            if (folding.focusInside) {
                folding.focusInside = false
                focusOnNode(folding.header)
            }
            // Closed again before the body ever grew — two presses in one frame — leaves no size
            // to change, so nothing below would hear that it is time to let go.
            val body = folding.body
            if (body != null && body.height <= 0f && folding.shown) folding.shown = false
        }
    }

    val sounds = LocalUiSounds.current
    val toggle = rememberTapped(
        remember(sounds, expanded, onExpandedChange) {
            {
                sounds.change()
                onExpandedChange(!expanded)
            }
        },
    )
    val placedHeader = remember(folding) { PlacedHandler { folding.header = it } }
    val placedBody = remember(folding) { PlacedHandler { folding.body = it } }
    val within = remember(folding) { FocusWithinHandler { folding.focusInside = it } }
    val sized = remember(folding) {
        SizeChangedHandler { if (!folding.expanded && it.height <= 0f) folding.shown = false }
    }
    val bodyStyle = rememberStyle("$style.body")

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .interaction(interaction)
                .focusable(interaction, enabled = enabled, initial = initialFocus)
                .clickable(enabled = enabled, onClick = toggle)
                .onPlaced(placedHeader)
                .styled(bar),
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
            verticalAlignment = VerticalAlignment.Centre,
        ) {
            Box(Modifier.styled(glyph)) {
                HeaderGlyph(glyph.textColour, glyphSize, expanded, direction)
            }
            // Unselectable inside a SelectionContainer, so a press on the title still folds it.
            ProvideContentStyle(bar) { DisableSelection { Text(title, softWrap = false) } }
        }

        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spec, clock = clock)
                // While closing, the contents are still there under a body heading for nothing. The
                // resize cuts them off on the way; this keeps them cut off on the frame it arrives.
                .then(if (expanded) Modifier else Modifier.clip())
                .onSizeChanged(sized)
                .onPlaced(placedBody)
                .onFocusWithin(within),
            name = "$style.body",
            content = {
                if (composed) {
                    Box(Modifier.fillMaxWidth().styled(bodyStyle)) { content() }
                }
            },
            measurePolicy = if (expanded) BodyPolicy.Open else BodyPolicy.Closing,
        )
    }
}

/**
 * What one header remembers between frames that is not the game's answer.
 *
 * [shown] is state, because letting go of the contents is a recomposition. The rest is written from
 * composition, layout and focus callbacks and read by the others, and changes nothing by itself.
 */
private class Folding(expanded: Boolean) {
    var shown by mutableStateOf(expanded)
    var expanded = expanded
    var focusInside = false
    var header: UiNode? = null
    var body: UiNode? = null
}

/** Between the triangle and the title. */
private const val GlyphGap = 8f

/**
 * The triangle: pointing along the line when closed — right, or left on a right-to-left screen — and
 * down when open. In [colour], which is the glyph style's text colour.
 */
@Composable
private fun HeaderGlyph(colour: Colour, size: Float, expanded: Boolean, direction: LayoutDirection) {
    val draw: UiCanvas.(Rect) -> Unit = remember(colour, expanded, direction) {
        when {
            expanded -> { box ->
                val top = box.top + box.height * 0.2f
                val bottom = box.bottom - box.height * 0.2f
                fan(floatArrayOf(box.left, top, box.right, top, (box.left + box.right) / 2f, bottom), colour)
            }
            direction == LayoutDirection.Ltr -> { box ->
                val left = box.left + box.width * 0.2f
                val right = box.right - box.width * 0.2f
                fan(floatArrayOf(left, box.top, right, (box.top + box.bottom) / 2f, left, box.bottom), colour)
            }
            else -> { box ->
                val left = box.left + box.width * 0.2f
                val right = box.right - box.width * 0.2f
                fan(floatArrayOf(right, box.top, left, (box.top + box.bottom) / 2f, right, box.bottom), colour)
            }
        }
    }
    LeafLayout(Modifier.size(size), name = "collapsingheader.glyph", draw = draw)
}

/**
 * The body: as tall as its contents while open, and no height at all while closing.
 *
 * Closing keeps the contents measured at their real size and only says the body wants none of it,
 * so `animateContentSize` shrinks the body over contents that stay put — cut off from the bottom
 * up — rather than squashing them.
 */
private class BodyPolicy(private val open: Boolean) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(constraints.constrainWidth(0f), constraints.constrainHeight(0f)) {}
        }
        val child = measurables[0].measure(
            Constraints(0f, constraints.maxWidth, 0f, Float.POSITIVE_INFINITY),
        )
        val width = constraints.constrainWidth(child.width)
        val height = constraints.constrainHeight(if (open) child.height else 0f)
        val x = if (layoutDirection == LayoutDirection.Rtl) width - child.width else 0f
        return layout(width, height) { child.at(x, 0f) }
    }

    companion object {
        val Open = BodyPolicy(open = true)
        val Closing = BodyPolicy(open = false)
    }
}
