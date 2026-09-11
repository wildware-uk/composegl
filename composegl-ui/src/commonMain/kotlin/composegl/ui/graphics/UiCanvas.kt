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

    /**
     * Text that has already been measured, with [x] and [y] as its top-left.
     *
     * Floats rather than an [Offset] because of what draws the most text per frame: a layer of
     * damage numbers places a couple of hundred runs every frame and would otherwise make a
     * couple of hundred throwaway objects doing it.
     */
    fun text(layout: TextLayout, x: Float, y: Float, colour: Colour)

    /** The same, for the ordinary case where the caller already has the point. */
    fun text(layout: TextLayout, at: Offset, colour: Colour) = text(layout, at.x, at.y, colour)

    /**
     * A picture, stretched to fill [destination]. [tint] multiplies; white leaves it alone.
     *
     * [source] picks a part of the texture, in texture pixels from its top-left, y downwards.
     * Null means all of it. It is here so that slicing a picture up — a nine-patch, a sprite
     * sheet, one frame of an animation — is arithmetic the toolkit does once, rather than a
     * feature every backend has to implement and can implement differently.
     */
    fun image(
        texture: TextureHandle,
        destination: Rect,
        tint: Colour = Colour.White,
        source: Rect? = null,
    )

    /**
     * A triangle fan: a filled shape a rectangle cannot be.
     *
     * [points] is x, y, x, y… in design coordinates, and the first point is the hub every triangle
     * shares. So a cooldown's wedge, a radial menu's slice and a compass's needle are all one call
     * with a handful of points in it, rather than three primitives every backend has to write.
     *
     * It is the only shape here that is not a box, and it is deliberately low level: no curve, no
     * radius, no stroke. Whatever wants a circle walks one itself, at whatever smoothness it is
     * being drawn at, which is the only place that knows.
     *
     * Fewer than three points draws nothing.
     */
    fun fan(points: FloatArray, colour: Colour)

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

    /**
     * How many times this canvas has handed work to the GPU since the frame began, or -1 when the
     * backend does not count.
     *
     * Here, rather than on each backend, so that [composegl.ui.debug.FrameBudget] can show the
     * number without knowing what a backend is. It is the one number in a frame budget that the
     * toolkit genuinely cannot work out for itself.
     */
    val drawCalls: Int get() = -1
}
