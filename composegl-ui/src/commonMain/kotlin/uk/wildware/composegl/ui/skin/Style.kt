package uk.wildware.composegl.ui.skin

import uk.wildware.composegl.ui.geometry.Offset
import uk.wildware.composegl.ui.graphics.Colour
import uk.wildware.composegl.ui.layout.Padding
import uk.wildware.composegl.ui.text.TextStyle

/**
 * What a widget is doing, as far as its appearance is concerned.
 *
 * A set rather than one value, because these genuinely overlap: a button can be focused *and*
 * hovered *and* held down, and a skin that had to pick one of the three would have to choose
 * between showing where the pad is and showing that the button is pressed.
 */
enum class WidgetState { Hovered, Focused, Pressed, Disabled }

/**
 * One piece of a style: what to change when the widget is in a particular state.
 *
 * Everything is nullable and everything absent means "leave it as it was". That is what makes a
 * skin short: a hovered button that only brightens says so in one line and inherits the rest, and
 * a state a skin never mentions still draws, because it draws the base.
 *
 * @param background what goes behind the widget.
 * @param textColour the colour of the widget's own text and icons.
 * @param tint multiplied into everything drawn, art included. How a disabled state is dimmed
 *   without a second set of pictures.
 * @param padding how far the contents sit from the edge. Usually the art's, not the skin's.
 * @param textStyle the family, size and line height of the widget's text.
 * @param contentOffset how far the contents move. A pressed button that shifts down a pixel is the
 *   cheapest convincing thing in game interfaces, and it belongs in the skin rather than in a
 *   widget's code.
 */
data class StateStyle(
    val background: SkinDrawable? = null,
    val textColour: Colour? = null,
    val tint: Colour? = null,
    val padding: Padding? = null,
    val textStyle: TextStyle? = null,
    val contentOffset: Offset? = null,
)

/**
 * How one kind of widget looks, in every state it can be in.
 *
 * A base plus overrides. Resolving lays the overrides on in a fixed order — focused, then hovered,
 * then pressed, then disabled — so a button that is focused and hovered gets both, and disabled
 * always has the last word. Anything an override does not mention keeps the value underneath it.
 *
 * The fallback is the point of the design. A skin that mentions only [base] still draws in every
 * state; a skin that adds one hovered colour changes one thing. Nothing anywhere has to enumerate
 * all five combinations, and a state the artist forgot looks plain rather than invisible.
 */
data class Style(
    val base: StateStyle = StateStyle(),
    val hovered: StateStyle? = null,
    val focused: StateStyle? = null,
    val pressed: StateStyle? = null,
    val disabled: StateStyle? = null,
) {

    /**
     * The style to actually draw with, for a widget in [states].
     *
     * @param defaults what an unspecified value falls back to, usually the skin's own defaults.
     */
    fun resolve(states: Set<WidgetState>, defaults: ResolvedStyle = ResolvedStyle.Plain): ResolvedStyle {
        var style = defaults.with(base)
        // Order matters and is fixed: focus is the quietest statement, being held down is louder,
        // and disabled overrules everything because a disabled button is not hovered, whatever the
        // pointer is doing.
        if (WidgetState.Focused in states) style = style.with(focused)
        if (WidgetState.Hovered in states) style = style.with(hovered)
        if (WidgetState.Pressed in states) style = style.with(pressed)
        if (WidgetState.Disabled in states) style = style.with(disabled)
        return style
    }

    /**
     * This style with [other] laid over it, for a game that wants one widget slightly different.
     *
     * Per state, and per field within a state, so overriding the hovered colour does not throw away
     * the skin's hovered padding.
     */
    fun mergedWith(other: Style) = Style(
        base = merge(base, other.base),
        hovered = hovered.overlaid(other.hovered),
        focused = focused.overlaid(other.focused),
        pressed = pressed.overlaid(other.pressed),
        disabled = disabled.overlaid(other.disabled),
    )

    private fun StateStyle?.overlaid(other: StateStyle?): StateStyle? = when {
        other == null -> this
        this == null -> other
        else -> merge(this, other)
    }

    private fun merge(under: StateStyle, over: StateStyle) = StateStyle(
        over.background ?: under.background,
        over.textColour ?: under.textColour,
        over.tint ?: under.tint,
        over.padding ?: under.padding,
        over.textStyle ?: under.textStyle,
        over.contentOffset ?: under.contentOffset,
    )
}

/**
 * A style with every question answered, which is what a widget actually draws from.
 *
 * There is no nullable field here on purpose. By the time a widget has one of these, "what colour
 * is the text" has an answer, and a widget that still had to decide would be a widget with a
 * colour in it — which is the thing this whole milestone exists to stop.
 */
data class ResolvedStyle(
    val background: SkinDrawable,
    val textColour: Colour,
    val tint: Colour,
    val padding: Padding,
    val textStyle: TextStyle,
    val contentOffset: Offset,
) {

    /**
     * This, with anything [state] names changed.
     *
     * One rule that is not simply "the override wins": a state that brings its own background and
     * says nothing about padding takes the padding the *art* carries. Art with a six-pixel bevel
     * and a four-pixel glow needs its contents ten pixels in, and that number belongs to the
     * picture rather than to the code using it — change the picture and the gap changes with it.
     * A drawable whose padding is [Padding.None] has no opinion, and leaves the padding alone.
     */
    fun with(state: StateStyle?): ResolvedStyle {
        if (state == null) return this
        val fromArt = state.background?.padding?.takeIf { it != Padding.None }
        return ResolvedStyle(
            state.background ?: background,
            state.textColour ?: textColour,
            state.tint ?: tint,
            state.padding ?: fromArt ?: padding,
            state.textStyle ?: textStyle,
            state.contentOffset ?: contentOffset,
        )
    }

    companion object {

        /**
         * White text on nothing, at the default size.
         *
         * What a widget gets when no skin has said anything at all. Deliberately drab: something a
         * game can read, and obviously not what anybody chose.
         */
        val Plain = ResolvedStyle(
            background = SkinDrawable.Blank,
            textColour = Colour.White,
            tint = Colour.White,
            padding = Padding.None,
            textStyle = TextStyle.Default,
            contentOffset = Offset.Zero,
        )
    }
}
