package composegl.ui.graphics

import composegl.ui.geometry.Offset
import composegl.ui.geometry.Rect
import composegl.ui.text.TextLayout

/**
 * Everything the toolkit can draw, and the whole of what a backend must implement.
 *
 * Deliberately short. A long drawing interface is a long list of things every future backend has
 * to reimplement, and most of what an interface actually draws is a rectangle with rounded corners
 * and some text on it. Anything richer goes through [raw], which hands back the backend's own
 * drawing object for a game or a widget to use directly.
 *
 * Coordinates are virtual pixels with y growing downwards. Backends flip once, internally.
 *
 * Clip and alpha are stacks: a nested clip is the intersection of the two, and a nested alpha
 * multiplies. [CanvasState] implements both correctly and backends are expected to hold one.
 */
interface UiCanvas {

    /** A filled rectangle. [corner] is the corner radius; zero is a plain rectangle. */
    fun rect(rect: Rect, colour: Colour, corner: Float = 0f)

    /** An outline drawn inside [rect], [width] thick. */
    fun border(rect: Rect, colour: Colour, width: Float, corner: Float = 0f)

    /**
     * A soft shadow under [rect], reaching [spread] beyond it.
     *
     * Its own call rather than part of [rect] because a shadow is drawn behind a whole group as
     * often as it is drawn behind one box.
     */
    fun shadow(rect: Rect, colour: Colour, spread: Float, corner: Float = 0f)

    /** Text that has already been measured, with [at] as its top-left. */
    fun text(layout: TextLayout, at: Offset, colour: Colour)

    /** A picture, stretched to fill [destination]. [tint] multiplies; white leaves it alone. */
    fun image(texture: TextureHandle, destination: Rect, tint: Colour = Colour.White)

    /** Nothing outside [rect] is drawn until the matching [popClip]. Nests by intersection. */
    fun pushClip(rect: Rect)

    fun popClip()

    /** Everything drawn until the matching [popAlpha] is faded. Nests by multiplication. */
    fun pushAlpha(alpha: Float)

    fun popAlpha()

    /**
     * The backend's own drawing object, for whatever this interface does not cover — a shader, a
     * particle system, a mesh, a game's existing render code.
     *
     * The escape hatch, and the reason the interface above is allowed to stay short. Whatever is
     * passed is the backend's business; a widget that uses it is choosing to be backend-specific
     * and should say so.
     *
     * A backend must leave its own state as it found it around this call, and so must the block.
     */
    fun raw(block: (Any) -> Unit)
}
