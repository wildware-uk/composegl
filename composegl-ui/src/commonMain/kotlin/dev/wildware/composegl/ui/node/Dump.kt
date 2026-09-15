package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.BorderSide
import dev.wildware.composegl.ui.graphics.BorderStyle
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.BoxPolicy
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.FlowPolicy
import dev.wildware.composegl.ui.layout.GridPolicy
import dev.wildware.composegl.ui.layout.LinearPolicy
import dev.wildware.composegl.ui.layout.MeasurePolicy
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.modifier.AlignElement
import dev.wildware.composegl.ui.modifier.AlphaElement
import dev.wildware.composegl.ui.modifier.AspectRatioElement
import dev.wildware.composegl.ui.modifier.BackgroundElement
import dev.wildware.composegl.ui.modifier.BlendElement
import dev.wildware.composegl.ui.modifier.BorderElement
import dev.wildware.composegl.ui.modifier.BorderSidesElement
import dev.wildware.composegl.ui.modifier.ClickableElement
import dev.wildware.composegl.ui.modifier.ClipElement
import dev.wildware.composegl.ui.modifier.DefaultMinSizeElement
import dev.wildware.composegl.ui.modifier.FillElement
import dev.wildware.composegl.ui.modifier.FocusTrapElement
import dev.wildware.composegl.ui.modifier.FocusableElement
import dev.wildware.composegl.ui.modifier.LayoutIdElement
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.OffsetElement
import dev.wildware.composegl.ui.modifier.PaddingElement
import dev.wildware.composegl.ui.modifier.RotateElement
import dev.wildware.composegl.ui.modifier.ScaleElement
import dev.wildware.composegl.ui.modifier.ShadowElement
import dev.wildware.composegl.ui.modifier.SizeElement
import dev.wildware.composegl.ui.modifier.SizeInElement
import dev.wildware.composegl.ui.modifier.TestTagElement
import dev.wildware.composegl.ui.modifier.WeightElement
import dev.wildware.composegl.ui.modifier.ZIndexElement
import dev.wildware.composegl.ui.modifier.elements
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * This subtree as text: one node per line, and where everything ended up.
 *
 * ```kotlin
 * println(host.root.dump())
 * ```
 * ```
 * root 0,0 1280x720  given 1280 x 720
 *   column #buttons 540,300 200x160  pad 8,8,8,8  given 0..1280 x 0..720
 *     button #play 548,308 184x40  given 0..184 x 0..∞
 * ```
 *
 * Plain text rather than a picture, so it works where there is no screen — Kotlin/Native, a CI
 * log, a bug report, a test failure. Each line says:
 *
 * - the node's name, and its test tag as `#tag`;
 * - its box in the root's coordinates, left,top widthxheight, where layout put it;
 * - `drawn` and the box it is really drawn in, when a `scale` here or above makes that differ;
 * - `pad` left,top,right,bottom, when it has padding;
 * - `given` and the room its parent offered on the last layout pass, width then height — one
 *   number for an axis that allowed only one size, `min..max` for a range, `∞` for no limit;
 * - `z`, when a `zIndex` lifts or sinks it among its siblings;
 * - `alpha`, when it is drawn see-through;
 * - `focused`, on the node that is [focused];
 * - `not laid out`, for a node no layout pass has reached, whose box means nothing yet.
 *
 * Numbers are rounded to two places and whole ones lose their `.0`, so a dump reads the same on a
 * JVM and on Native, whose floats print differently.
 *
 * @param modifiers also list each node's modifier chain on the line under it, in chain order.
 * @param focused the node to mark as focused, usually `FocusManager.focused`.
 * @see debugTree for the shape alone.
 */
fun UiNode.dump(modifiers: Boolean = false, focused: UiNode? = null): String =
    buildString { dumpInto(this, "", modifiers, focused) }

private fun UiNode.dumpInto(out: StringBuilder, indent: String, modifiers: Boolean, focused: UiNode?) {
    out.append(indent).append(name)
    testTag?.let { out.append(" #").append(it) }

    val box = layoutBoundsInRoot
    out.append(' ').append(describeNumber(box.left)).append(',').append(describeNumber(box.top))
    out.append(' ').append(describeNumber(width)).append('x').append(describeNumber(height))

    val drawn = boundsInRoot
    if (drawn != box) {
        out.append("  drawn ").append(describeNumber(drawn.left)).append(',').append(describeNumber(drawn.top))
        out.append(' ').append(describeNumber(drawn.width)).append('x').append(describeNumber(drawn.height))
    }

    val resolved = resolved
    if (resolved.padding != Padding.None) out.append("  pad ").append(describePadding(resolved.padding))

    val given = givenConstraints
    if (given != null) out.append("  given ").append(describeConstraints(given))
    if (resolved.zIndex != 0f) out.append("  z ").append(describeNumber(resolved.zIndex))
    if (resolved.alpha != 1f) out.append("  alpha ").append(describeNumber(resolved.alpha))
    if (this === focused) out.append("  focused")
    if (!everMeasured) out.append("  not laid out")

    if (modifiers && modifier != Modifier) {
        out.append('\n').append(indent).append("    modifier ")
        modifier.elements().forEachIndexed { index, element ->
            if (index > 0) out.append(" -> ")
            out.append(describe(element))
        }
    }

    children.forEach { child ->
        out.append('\n')
        child.dumpInto(out, "$indent  ", modifiers, focused)
    }
}

/**
 * One modifier element, written the way it was asked for: `padding(8)`, `size(40x25)`.
 *
 * Elements that hold a lambda say only what they are, because a lambda prints differently on every
 * platform and says nothing a person reading a dump can use.
 */
fun describe(element: Modifier.Element): String = when (element) {
    is SizeElement -> when {
        element.width != null && element.height != null ->
            "size(${describeNumber(element.width)}x${describeNumber(element.height)})"
        element.width != null -> "width(${describeNumber(element.width)})"
        element.height != null -> "height(${describeNumber(element.height)})"
        else -> "size()"
    }
    is FillElement -> {
        val w = element.widthFraction
        val h = element.heightFraction
        fun share(f: Float) = if (f == 1f) "" else "(${describeNumber(f)})"
        when {
            w != null && h != null && w == h -> "fillMaxSize${share(w)}"
            w != null && h != null -> "fillMaxWidth${share(w)} -> fillMaxHeight${share(h)}"
            w != null -> "fillMaxWidth${share(w)}"
            h != null -> "fillMaxHeight${share(h)}"
            else -> "fill()"
        }
    }
    is AspectRatioElement ->
        "aspectRatio(${describeNumber(element.ratio)}${if (element.matchHeightConstraintsFirst) " height first" else ""})"
    is SizeInElement -> {
        val bounds = listOfNotNull(
            element.minWidth?.let { "minWidth ${describeNumber(it)}" },
            element.maxWidth?.let { "maxWidth ${describeNumber(it)}" },
            element.minHeight?.let { "minHeight ${describeNumber(it)}" },
            element.maxHeight?.let { "maxHeight ${describeNumber(it)}" },
        )
        "sizeIn(${bounds.joinToString(" ")})"
    }
    is DefaultMinSizeElement -> {
        val bounds = listOfNotNull(
            element.minWidth?.let { "minWidth ${describeNumber(it)}" },
            element.minHeight?.let { "minHeight ${describeNumber(it)}" },
        )
        "defaultMinSize(${bounds.joinToString(" ")})"
    }
    is PaddingElement -> "padding(${describePadding(element.padding)})"
    is OffsetElement -> "offset(${describeNumber(element.x)},${describeNumber(element.y)})"
    is WeightElement -> "weight(${describeNumber(element.weight)})"
    is AlignElement -> "align(${alignment(element.alignment)})"
    is LayoutIdElement -> "layoutId(${element.layoutId})"
    is BackgroundElement -> "background(${colour(element.colour)}${corners(element.corners)})"
    is BorderElement ->
        "border(${colour(element.colour)} ${describeNumber(element.width)}${corners(element.corners)}${style(element.style)})"
    is BorderSidesElement -> listOfNotNull(
        element.left?.let { "left ${side(it)}" },
        element.top?.let { "top ${side(it)}" },
        element.right?.let { "right ${side(it)}" },
        element.bottom?.let { "bottom ${side(it)}" },
    ).joinToString(", ", "border(", ")")
    is ShadowElement -> "shadow(${colour(element.colour)} spread ${describeNumber(element.spread)}${corners(element.corners)})"
    is ClipElement -> if (element.corners == Corners.None) "clip" else "clip(${corners(element.corners).trim()})"
    is AlphaElement -> "alpha(${describeNumber(element.alpha)})"
    is BlendElement -> "blend(${element.mode.name})"
    is ZIndexElement -> "zIndex(${describeNumber(element.z)})"
    is ScaleElement -> "scale(${describeNumber(element.factor)}${origin(element.origin)})"
    is RotateElement -> "rotate(${describeNumber(element.degrees)}${origin(element.origin)})"
    is ClickableElement -> if (element.enabled) "clickable" else "clickable(disabled)"
    is FocusableElement -> buildString {
        append("focusable")
        val notes = listOfNotNull("disabled".takeUnless { element.enabled }, "initial".takeIf { element.initial })
        if (notes.isNotEmpty()) append(notes.joinToString(" ", "(", ")"))
    }
    is FocusTrapElement -> if (element.enabled) "focusTrap" else "focusTrap(off)"
    is TestTagElement -> "testTag(\"${element.tag}\")"
    // Everything else holds a handler, a picture or a shader: its name is what there is to say.
    else -> element::class.simpleName
        ?.removeSuffix("Element")
        ?.let { NamesByElement[it] ?: it.replaceFirstChar { first -> first.lowercaseChar() } }
        ?: "element"
}

/** Elements whose class name is not what a person wrote in the chain. */
private val NamesByElement = mapOf(
    "PointerInput" to "onPointer",
    "KeyInput" to "onKeyEvent",
    "TextInput" to "onTextEvent",
    "FocusDirection" to "onFocusDirection",
    "Reveal" to "onReveal",
    "FocusWithin" to "onFocusWithin",
    "SkinBackground" to "styled",
)

/** The room a parent gave, as `0..200 x 40`: a range per axis, or one number where it is fixed. */
fun describeConstraints(given: Constraints): String =
    axis(given.minWidth, given.maxWidth) + " x " + axis(given.minHeight, given.maxHeight)

private fun axis(min: Float, max: Float) = if (min == max) describeNumber(min) else "${describeNumber(min)}..${describeNumber(max)}"

/** Padding as `8`, or left, top, right and bottom as `8,4,8,4` where the sides differ. */
fun describePadding(padding: Padding): String = with(padding) {
    if (left == top && top == right && right == bottom) describeNumber(left)
    else "${describeNumber(left)},${describeNumber(top)},${describeNumber(right)},${describeNumber(bottom)}"
}

private fun colour(colour: Colour) = "#" + colour.argb.toUInt().toString(16).uppercase().padStart(8, '0')

/** One radius as `corner 4`, four different ones clockwise from the top left as `corners 4,4,0,0`. */
private fun corners(corners: Corners) = with(corners) {
    when {
        corners == Corners.None -> ""
        isUniform -> " corner ${describeNumber(topLeft)}"
        else -> " corners ${describeNumber(topLeft)},${describeNumber(topRight)},${describeNumber(bottomRight)},${describeNumber(bottomLeft)}"
    }
}

/** Nothing for a solid line, which is what a border is unless it says otherwise. */
private fun style(style: BorderStyle) = when (style) {
    BorderStyle.Solid -> ""
    is BorderStyle.Dashed -> " dashed ${describeNumber(style.on)},${describeNumber(style.off)}"
    BorderStyle.Dotted -> " dotted"
}

private fun side(side: BorderSide) = "${colour(side.colour)} ${describeNumber(side.width)}${style(side.style)}"

private fun origin(origin: Alignment) = if (origin == Alignment.Centre) "" else " about ${alignment(origin)}"

private fun alignment(alignment: Alignment) = when (alignment) {
    Alignment.TopStart -> "TopStart"
    Alignment.TopCentre -> "TopCentre"
    Alignment.TopEnd -> "TopEnd"
    Alignment.CentreStart -> "CentreStart"
    Alignment.Centre -> "Centre"
    Alignment.CentreEnd -> "CentreEnd"
    Alignment.BottomStart -> "BottomStart"
    Alignment.BottomCentre -> "BottomCentre"
    else -> "BottomEnd"
}

/**
 * What arranges a node's children, as somebody would have written it: `Row`, `Column spaced 8`.
 * A policy a game wrote itself is called by its class name, or `custom` when it has none.
 */
fun describePolicy(policy: MeasurePolicy): String = when {
    policy === MeasurePolicy.Stack -> "Stack"
    policy === MeasurePolicy.Empty -> "Empty"
    policy is LinearPolicy -> (if (policy.horizontal) "Row" else "Column") +
        (policy.arrangement.spacing.takeIf { it > 0f }?.let { " spaced ${describeNumber(it)}" } ?: "")
    policy is BoxPolicy -> "Box"
    policy is FlowPolicy -> if (policy.horizontal) "FlowRow" else "FlowColumn"
    policy is GridPolicy -> "Grid"
    else -> policy::class.simpleName?.takeIf { it.isNotEmpty() && '$' !in it } ?: "custom"
}

/**
 * A float the same way on every platform: two places at most, and no `.0` on a whole number.
 *
 * `Float.toString` is not that — a JVM writes `1280.0` and `1.0E7`, and Native has its own
 * opinions — and a dump that differs between the two cannot be pasted into a test.
 */
fun describeNumber(value: Float): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0f) "∞" else "-∞"
    val hundredths = (value.toDouble() * 100.0).roundToLong()
    val sign = if (hundredths < 0L) "-" else ""
    val whole = abs(hundredths) / 100L
    val fraction = abs(hundredths) % 100L
    return when {
        fraction == 0L -> "$sign$whole"
        fraction % 10L == 0L -> "$sign$whole.${fraction / 10L}"
        else -> "$sign$whole.${fraction.toString().padStart(2, '0')}"
    }
}
