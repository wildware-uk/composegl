package dev.wildware.composegl.ui.node

import dev.wildware.composegl.ui.geometry.Corners
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Constraints
import dev.wildware.composegl.ui.layout.Padding
import dev.wildware.composegl.ui.modifier.AlignElement
import dev.wildware.composegl.ui.modifier.AlphaElement
import dev.wildware.composegl.ui.modifier.AspectRatioElement
import dev.wildware.composegl.ui.modifier.BackgroundElement
import dev.wildware.composegl.ui.modifier.BlendElement
import dev.wildware.composegl.ui.modifier.BorderElement
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
    out.append(' ').append(number(box.left)).append(',').append(number(box.top))
    out.append(' ').append(number(width)).append('x').append(number(height))

    val drawn = boundsInRoot
    if (drawn != box) {
        out.append("  drawn ").append(number(drawn.left)).append(',').append(number(drawn.top))
        out.append(' ').append(number(drawn.width)).append('x').append(number(drawn.height))
    }

    val resolved = resolved
    if (resolved.padding != Padding.None) out.append("  pad ").append(padding(resolved.padding))

    val given = givenConstraints
    if (given != null) out.append("  given ").append(constraints(given))
    if (resolved.zIndex != 0f) out.append("  z ").append(number(resolved.zIndex))
    if (resolved.alpha != 1f) out.append("  alpha ").append(number(resolved.alpha))
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
internal fun describe(element: Modifier.Element): String = when (element) {
    is SizeElement -> when {
        element.width != null && element.height != null ->
            "size(${number(element.width)}x${number(element.height)})"
        element.width != null -> "width(${number(element.width)})"
        element.height != null -> "height(${number(element.height)})"
        else -> "size()"
    }
    is FillElement -> {
        val w = element.widthFraction
        val h = element.heightFraction
        fun share(f: Float) = if (f == 1f) "" else "(${number(f)})"
        when {
            w != null && h != null && w == h -> "fillMaxSize${share(w)}"
            w != null && h != null -> "fillMaxWidth${share(w)} -> fillMaxHeight${share(h)}"
            w != null -> "fillMaxWidth${share(w)}"
            h != null -> "fillMaxHeight${share(h)}"
            else -> "fill()"
        }
    }
    is AspectRatioElement ->
        "aspectRatio(${number(element.ratio)}${if (element.matchHeightConstraintsFirst) " height first" else ""})"
    is SizeInElement -> {
        val bounds = listOfNotNull(
            element.minWidth?.let { "minWidth ${number(it)}" },
            element.maxWidth?.let { "maxWidth ${number(it)}" },
            element.minHeight?.let { "minHeight ${number(it)}" },
            element.maxHeight?.let { "maxHeight ${number(it)}" },
        )
        "sizeIn(${bounds.joinToString(" ")})"
    }
    is DefaultMinSizeElement -> {
        val bounds = listOfNotNull(
            element.minWidth?.let { "minWidth ${number(it)}" },
            element.minHeight?.let { "minHeight ${number(it)}" },
        )
        "defaultMinSize(${bounds.joinToString(" ")})"
    }
    is PaddingElement -> "padding(${padding(element.padding)})"
    is OffsetElement -> "offset(${number(element.x)},${number(element.y)})"
    is WeightElement -> "weight(${number(element.weight)})"
    is AlignElement -> "align(${alignment(element.alignment)})"
    is LayoutIdElement -> "layoutId(${element.layoutId})"
    is BackgroundElement -> "background(${colour(element.colour)}${corners(element.corners)})"
    is BorderElement -> "border(${colour(element.colour)} ${number(element.width)}${corners(element.corners)})"
    is ShadowElement -> "shadow(${colour(element.colour)} spread ${number(element.spread)}${corners(element.corners)})"
    is ClipElement -> if (element.corners == Corners.None) "clip" else "clip(${corners(element.corners).trim()})"
    is AlphaElement -> "alpha(${number(element.alpha)})"
    is BlendElement -> "blend(${element.mode.name})"
    is ZIndexElement -> "zIndex(${number(element.z)})"
    is ScaleElement -> "scale(${number(element.factor)}${origin(element.origin)})"
    is RotateElement -> "rotate(${number(element.degrees)}${origin(element.origin)})"
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

private fun constraints(given: Constraints) =
    axis(given.minWidth, given.maxWidth) + " x " + axis(given.minHeight, given.maxHeight)

private fun axis(min: Float, max: Float) = if (min == max) number(min) else "${number(min)}..${number(max)}"

private fun padding(padding: Padding) = with(padding) {
    if (left == top && top == right && right == bottom) number(left)
    else "${number(left)},${number(top)},${number(right)},${number(bottom)}"
}

private fun colour(colour: Colour) = "#" + colour.argb.toUInt().toString(16).uppercase().padStart(8, '0')

/** One radius as `corner 4`, four different ones clockwise from the top left as `corners 4,4,0,0`. */
private fun corners(corners: Corners) = with(corners) {
    when {
        corners == Corners.None -> ""
        isUniform -> " corner ${number(topLeft)}"
        else -> " corners ${number(topLeft)},${number(topRight)},${number(bottomRight)},${number(bottomLeft)}"
    }
}

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
 * A float the same way on every platform: two places at most, and no `.0` on a whole number.
 *
 * `Float.toString` is not that — a JVM writes `1280.0` and `1.0E7`, and Native has its own
 * opinions — and a dump that differs between the two cannot be pasted into a test.
 */
internal fun number(value: Float): String {
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
